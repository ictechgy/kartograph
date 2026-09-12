package dev.kartograph.index

import dev.kartograph.core.EdgeOrigin
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RuntimeFieldFlowTest {
    @Test
    fun `mutable class initialization restores direct and reflective reads`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static class Holder { public static Class<?> type = A.class; }
            static void direct() throws Exception { Holder.type.getDeclaredConstructor().newInstance(); }
            static void reflective() throws Exception {
                ((Class<?>)Holder.class.getDeclaredField("type").get(null)).getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "direct", "A")
        assertTargets(indexed, "reflective", "A")
        assertEquals(2, indexed.observations.sumOf { it.reflectiveConstructions })
        assertTrue(indexed.graph.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL }.none { "Unused" in it.target.value })
    }

    @Test
    fun `String field writes restore class loading candidates and retain unknown possibilities`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static String type = initial();
            static String initial(){return "probe.Entry${'$'}A";}
            static void write(boolean flag, String unknown){type=flag ? "probe.Entry${'$'}B" : unknown;}
            static void direct() throws Exception {Class.forName(type).getDeclaredConstructor().newInstance();}
            static void reflective() throws Exception {
                String value=(String)Entry.class.getDeclaredField("type").get(null);
                Class.forName(value).getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "direct", "A", "B")
        assertTargets(indexed, "reflective", "A", "B")
        assertEquals(2, indexed.observations.sumOf { it.reflectionCalls })
        assertEquals(2, indexed.observations.sumOf { it.reflectiveConstructions })
        assertTrue(indexed.graph.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL }.none { "Unused" in it.target.value })
    }

    @Test
    fun `String field candidate overflow drops partial targets and reports its limit`(@TempDir root: Path) {
        val writes = (0..16).joinToString("\n") {
            "static void w$it(){type=\"probe.Entry${'$'}T$it\";} static class T$it {}"
        }
        val indexed = compile(root, """
            static String type; $writes
            static void read() throws Exception {Class.forName(type).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertTrue(indexed.observations.sumOf { it.valueAnalysisLimits } > 0)
        assertEquals(1, indexed.observations.sumOf { it.reflectionCalls })
    }

    @Test
    fun `reflection reads String constants stored in the classfile constant attribute`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static final String type="probe.Entry${'$'}A";
            static void read() throws Exception {
                Class.forName((String)Entry.class.getField("type").get(null)).getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(1, indexed.observations.sumOf { it.reflectionCalls })
        assertTrue(indexed.graph.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL }.none { "Unused" in it.target.value })
    }

    @Test
    fun `oversized String constants retain the runtime analysis limit`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static final String type="${"x".repeat(4097)}";
            static void read() throws Exception {
                Class.forName((String)Entry.class.getField("type").get(null)).getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "read")
        assertTrue(indexed.observations.sumOf { it.valueAnalysisLimits } > 0)
        assertEquals(1, indexed.observations.sumOf { it.reflectionCalls })
    }

    @Test
    fun `all direct writes include branches and void helpers while unknown writes stay disclosed`(@TempDir root: Path) {
        val indexed = compile(root, """
            static Class<?> type = A.class;
            static void write(){ type = System.nanoTime() == 0 ? B.class : C.class; }
            static void unknown(Class<?> value){type = value;}
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read", "A", "B", "C")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `unknown assignment and declared Class type alone never fabricate a target`(@TempDir root: Path) {
        val indexed = compile(root, """
            static Class<?> type;
            static void write(Class<?> value){type=value;}
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `field lookup stops at hidden declarations and follows inherited public declarations`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static class Parent {public static Class<?> type=A.class;}
            public static class Child extends Parent {}
            public static class Hidden extends Parent {public static Class<?> type=B.class;}
            static void direct() throws Exception {Child.type.getDeclaredConstructor().newInstance();}
            static void inherited() throws Exception {((Class<?>)Child.class.getField("type").get(null)).getDeclaredConstructor().newInstance();}
            static void hidden() throws Exception {((Class<?>)Hidden.class.getField("type").get(null)).getDeclaredConstructor().newInstance();}
            static void declared() throws Exception {((Class<?>)Child.class.getDeclaredField("type").get(null)).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "direct", "A")
        assertTargets(indexed, "inherited", "A")
        assertTargets(indexed, "hidden", "B")
        assertTargets(indexed, "declared")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveMemberMisses })
        val gets = indexed.graph.externalCalls.filter { it.owner == "java/lang/reflect/Field" && it.name == "get" }
        assertEquals(listOf("field:probe/Entry${'$'}Hidden#type:Ljava/lang/Class;"),
            gets.single { "#hidden(" in it.caller.value }.resolvedTargets.map { it.value })
    }

    @Test
    fun `public lookup ignores private hidden fields while declared lookup selects them`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static class Parent {public static Class<?> type=A.class;}
            public static class Child extends Parent {private static Class<?> type=B.class;}
            static void inherited() throws Exception {((Class<?>)Child.class.getField("type").get(null)).getDeclaredConstructor().newInstance();}
            static void declared() throws Exception {((Class<?>)Child.class.getDeclaredField("type").get(null)).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "inherited", "A")
        assertTargets(indexed, "declared", "B")
    }

    @Test
    fun `interface fields precede superclass fields during inherited lookup`(@TempDir root: Path) {
        val indexed = compile(root, """
            public interface Face {Class<?> type=A.class;}
            public static class Parent {public static Class<?> type=B.class;}
            public static class Child extends Parent implements Face {}
            static void read() throws Exception {((Class<?>)Child.class.getField("type").get(null)).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read", "A")
    }

    @Test
    fun `reflective setters add possible values without assuming write order`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static Class<?> type=A.class;
            static void read() throws Exception {
                java.lang.reflect.Field field = Entry.class.getDeclaredField("type");
                field.set(null,B.class);
                ((Class<?>)field.get(null)).getDeclaredConstructor().newInstance();
            }
            static void unknown(Object value) throws Exception {Entry.class.getField("type").set(null,value);}
        """)
        assertTargets(indexed, "read", "A", "B")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `escaped field handles and public mutation never produce complete resolution`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static Class<?> type=A.class;
            static native void escape(Object field);
            static void read() throws Exception {
                escape(Entry.class.getField("type"));
                type.getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `initializer cycles and helper reentry stay unknown without executing code`(@TempDir root: Path) {
        val indexed = compile(root, """
            static class Left {static Class<?> type=Right.type;}
            static class Right {static Class<?> type=Left.type;}
            static Class<?> type=initial();
            static Class<?> initial(){return type;}
            static void cycle() throws Exception {Left.type.getDeclaredConstructor().newInstance();}
            static void reentry() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "cycle")
        assertTargets(indexed, "reentry")
        assertEquals(2, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `class valued return summaries can feed field writes and reads`(@TempDir root: Path) {
        val indexed = compile(root, """
            static Class<?> type = initial(A.class);
            static Class<?> initial(Class<?> value){return value;}
            static Class<?> value(){return type;}
            static void read() throws Exception {value().getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `field candidate and depth bounds drop partial targets and report limits`(@TempDir root: Path) {
        val writes = (0..16).joinToString("\n") { "static void w$it(){many=T$it.class;} static class T$it {}" }
        val fields = (0..9).joinToString("\n") { "static Class<?> f$it=" + (if (it == 9) "A.class" else "initial$it()") + ";" }
        val helpers = (0..8).joinToString("\n") { "static Class<?> initial$it(){return f${it+1};}" }
        val indexed = compile(root, """
            static Class<?> many; $writes $fields $helpers
            static void wide() throws Exception {many.getDeclaredConstructor().newInstance();}
            static void deep() throws Exception {f0.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "wide")
        assertTargets(indexed, "deep")
        assertTrue(indexed.observations.sumOf { it.valueAnalysisLimits } >= 2)
        assertEquals(2, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `instance fields are not inferred from a global static value or declared type`(@TempDir root: Path) {
        val indexed = compile(root, """
            public Class<?> type=A.class;
            static void read(Entry entry) throws Exception {((Class<?>)Entry.class.getField("type").get(entry)).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `first class root selects field writers as well as declarations`(@TempDir root: Path) {
        val roots = listOf("A", "B").map { value ->
            val directory = Files.createDirectories(root.resolve(value))
            compile(directory, "static Class<?> type=$value.class; static void read() throws Exception {type.getDeclaredConstructor().newInstance();}")
            directory.resolve("classes")
        }
        assertTargets(ClassFileIndexer().indexWithObservations(roots), "read", "A")
        assertTargets(ClassFileIndexer().indexWithObservations(roots.reversed()), "read", "B")
    }

    @Test
    fun `actual Kotlin static fields preserve candidates and reentry uncertainty`(@TempDir root: Path) {
        val classes = Files.createDirectories(root.resolve("classes"))
        for (suffix in listOf("", "${'$'}A", "${'$'}B", "${'$'}Unused")) {
            val resource = "dev/kartograph/index/fixture/KotlinFieldFixture$suffix.class"
            val target = classes.resolve(resource)
            Files.createDirectories(target.parent)
            Files.write(target, requireNotNull(javaClass.getResourceAsStream("/$resource")).use { it.readAllBytes() })
        }
        dev.kartograph.index.fixture.KotlinFieldFixture.direct()
        dev.kartograph.index.fixture.KotlinFieldFixture.reflective()
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        val calls = indexed.graph.externalCalls.filter { it.owner == "java/lang/reflect/Constructor" && it.name == "newInstance" }
        for (method in listOf("direct", "reflective")) {
            assertEquals(listOf("A", "B").map { "method:dev/kartograph/index/fixture/KotlinFieldFixture${'$'}$it#<init>()V" },
                calls.single { "#$method(" in it.caller.value }.resolvedTargets.map { it.value })
        }
        assertTrue(calls.single { "#reentry(" in it.caller.value }.resolvedTargets.isEmpty())
        assertEquals(3, indexed.observations.sumOf { it.reflectiveConstructions })
        assertTrue(indexed.graph.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL }.none { "Unused" in it.target.value })
    }

    @Test
    fun `unknown branch keeps known write candidates without hiding uncertainty`(@TempDir root: Path) {
        val indexed = compile(root, """
            static Class<?> type=A.class;
            static void write(boolean flag, Class<?> unknown){type=flag ? B.class : unknown;}
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read", "A", "B")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `hidden field with different descriptor does not fall back to parent Class value`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static class Parent {public static Class<?> type=A.class;}
            public static class Child extends Parent {public static String type="irrelevant";}
            static void read() throws Exception {((Class<?>)Child.class.getField("type").get(null)).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `writer count budget never returns a partial candidate set`(@TempDir root: Path) {
        val writers = (0..128).joinToString("\n") { "static void w$it(){type=A.class;}" }
        val indexed = compile(root, """
            static Class<?> type; $writers
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertEquals(1, indexed.observations.sumOf { it.valueAnalysisLimits })
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `large javac writer frame budget is measured`(@TempDir root: Path) {
        val locals = (0..499).joinToString("\n") { "int v$it=$it;" }
        val indexed = compile(root, """
            static Class<?> type;
            static void write() { $locals type=A.class; }
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertEquals(1, indexed.observations.sumOf { it.valueAnalysisLimits })
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    private fun assertTargets(indexed: IndexedClasses, method: String, vararg names: String) {
        val call = indexed.graph.externalCalls.single { it.owner == "java/lang/reflect/Constructor" && it.name == "newInstance" && "#$method(" in it.caller.value }
        assertEquals(names.map { "method:probe/Entry${'$'}$it#<init>()V" }.sorted(), call.resolvedTargets.map { it.value })
    }

    private fun compile(root: Path, body: String): IndexedClasses {
        val source = root.resolve("Entry.java")
        Files.writeString(source, "package probe; public class Entry { public static class A {} public static class B {} public static class C {} public static class Unused {} $body }")
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-g", "-d", classes.toString(), source.toString()), errors.toString())
        return ClassFileIndexer().indexWithObservations(listOf(classes))
    }
}
