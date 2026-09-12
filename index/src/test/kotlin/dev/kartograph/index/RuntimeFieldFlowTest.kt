package dev.kartograph.index

import dev.kartograph.core.EdgeOrigin
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

class RuntimeFieldFlowTest {
    @Test
    fun `unused Object field reads do not consume a literal reflection budget`(@TempDir root: Path) {
        val indexed = compile(root, """
            static Object f8=new Object();
            static Object f7=Entry.f8,f6=Entry.f7,f5=Entry.f6,f4=Entry.f5,f3=Entry.f4,f2=Entry.f3,f1=Entry.f2,f0=Entry.f1;
            static void read() throws Exception {
                Object unused=f0;
                Class.forName("probe.Entry${'$'}A").getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    @Test
    fun `unused reflected field values do not consume a literal reflection budget`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static Object f8=new Object();
            public static Object f7=Entry.f8,f6=Entry.f7,f5=Entry.f6,f4=Entry.f5,f3=Entry.f4,f2=Entry.f3,f1=Entry.f2,f0=Entry.f1;
            static void read() throws Exception {
                Object unused=Entry.class.getField("f0").get(null);
                Class.forName("probe.Entry${'$'}A").getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
        assertEquals(listOf("field:probe/Entry#f0:Ljava/lang/Object;"), indexed.graph.externalCalls.single {
            it.owner == "java/lang/reflect/Field" && it.name == "get"
        }.resolvedTargets.map { it.value })
    }

    @Test
    fun `Object typed fields retain Class and String values used by reflection`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static Object type=A.class;
            public static Object name="probe.Entry${'$'}B";
            static void direct() throws Exception {((Class<?>)type).getDeclaredConstructor().newInstance();}
            static void reflective() throws Exception {
                Class.forName((String)Entry.class.getField("name").get(null)).getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "direct", "A")
        assertTargets(indexed, "reflective", "B")
    }

    @Test
    fun `known reflective names avoid charging setters for another field`(@TempDir root: Path) {
        val setters = (0..128).joinToString("\n") {
            "static void set$it() throws Exception {Entry.class.getField(\"other\").set(null,new Object());}"
        }
        val indexed = compile(root, """
            public static Class<?> type=A.class; public static Object other;
            $setters
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    @Test
    fun `computed reflective names remain eligible writer candidates`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static Class<?> type=A.class;
            public static String field="type";
            static String name(){return "ty".concat("pe");}
            static void helper() throws Exception {Entry.class.getField(name()).set(null,B.class);}
            static void fromField() throws Exception {Entry.class.getField(field).set(null,C.class);}
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read", "A", "B", "C")
    }

    @Test
    fun `bounded demand collection falls back without losing a known field target`(@TempDir root: Path) {
        val choices = (0..64).joinToString("\n") { "case $it: selected=type;break;" }
        val indexed = compile(root, """
            static Class<?> type=A.class;
            static void read(int choice) throws Exception {
                Class<?> selected;
                switch(choice){$choices default:selected=type;}
                selected.getDeclaredConstructor().newInstance();
            }
        """)
        assertTargets(indexed, "read", "A")
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }


    @Test
    fun `looping concatenation and reset in a writer terminates with a measured limit`(@TempDir root: Path) {
        val source = root.resolve("Entry.java")
        Files.writeString(source, """
            package probe;
            public class Entry {
                static String type;
                static void write(int n) {
                    String name="probe.Entry${'$'}A";
                    for(int i=0;i<n;i++) name=(i%2==0) ? "probe.Entry${'$'}B" : name+"x";
                    type=name;
                }
                static void read() throws Exception {Class.forName(type).getDeclaredConstructor().newInstance();}
                public static class A {} public static class B {}
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-g", "-d", classes.toString(), source.toString()), errors.toString())
        val classpath = listOf(
            "dev.kartograph.index.fixture.RuntimeFieldProbe", "dev.kartograph.index.ClassFileIndexer",
            "dev.kartograph.core.CodeGraph", "org.objectweb.asm.ClassReader", "org.objectweb.asm.tree.MethodNode",
            "org.objectweb.asm.tree.analysis.Analyzer", "kotlin.metadata.jvm.KotlinClassMetadata", "kotlin.KotlinVersion",
        ).map { name -> Path.of(Class.forName(name).protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        val output = root.resolve("probe-output.txt")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m",
            "-cp", classpath, "dev.kartograph.index.fixture.RuntimeFieldProbe", classes.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "field writer analysis did not terminate")
            assertEquals(0, process.exitValue(), "field writer probe failed")
            assertTrue(Files.readString(output).trim().toInt() > 0, "overflow must retain its measured limit")
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `unrelated large writers do not exhaust a trivial field summary`(@TempDir root: Path) {
        compile(root, """
            static Class<?> type=A.class; static Object unrelated;
            static void read() throws Exception {type.getDeclaredConstructor().newInstance();}
        """)
        val file = root.resolve("classes/probe/Entry.class")
        val writer = ClassWriter(0)
        ClassReader(Files.readAllBytes(file)).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitEnd() {
                repeat(101) { number ->
                    val method = writer.visitMethod(Opcodes.ACC_STATIC, "noise$number", "()V", null, null)
                    method.visitCode()
                    repeat(10_000) { method.visitInsn(Opcodes.NOP) }
                    method.visitInsn(Opcodes.ACONST_NULL)
                    method.visitFieldInsn(Opcodes.PUTSTATIC, "probe/Entry", "unrelated", "Ljava/lang/Object;")
                    method.visitInsn(Opcodes.RETURN)
                    method.visitMaxs(1, 0)
                    method.visitEnd()
                }
                super.visitEnd()
            }
        }, 0)
        Files.write(file, writer.toByteArray())
        val indexed = ClassFileIndexer().indexWithObservations(listOf(root.resolve("classes")))
        assertTargets(indexed, "read", "A")
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

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
    fun `JVM initializes a static ConstantValue even without the final flag`(@TempDir root: Path) {
        compile(root, """
            public static final String type="probe.Entry${'$'}A";
            public static Object read() throws Exception {
                return Class.forName((String)Entry.class.getField("type").get(null)).getDeclaredConstructor().newInstance();
            }
        """)
        val file = root.resolve("classes/probe/Entry.class")
        val writer = ClassWriter(0)
        ClassReader(Files.readAllBytes(file)).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? =
                super.visitField(if (name == "type") access and Opcodes.ACC_FINAL.inv() else access, name, descriptor, signature, value)
        }, 0)
        Files.write(file, writer.toByteArray())
        URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL())).use { loader ->
            val entry = loader.loadClass("probe.Entry")
            assertFalse(java.lang.reflect.Modifier.isFinal(entry.getField("type").modifiers))
            assertEquals("probe.Entry${'$'}A", entry.getField("type").get(null))
            assertEquals("probe.Entry${'$'}A", entry.getMethod("read").invoke(null).javaClass.name)
        }
        assertTargets(ClassFileIndexer().indexWithObservations(listOf(root.resolve("classes"))), "read", "A")
    }

    @Test
    fun `internal class fact descriptions do not expose raw String constants`() {
        val value = "a-raw-value-that-must-not-appear-in-diagnostics"
        val facts = ClassFacts("probe/Entry", emptyList(), emptyList(), null, ClassRuntimeObservation(),
            constantStringFields = mapOf(JvmNodeId.fieldId("probe/Entry", "value", "Ljava/lang/String;") to value))
        assertFalse(facts.toString().contains(value))
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
    fun `oversized literal writes cannot hide their limit behind another known write`(@TempDir root: Path) {
        val indexed = compile(root, """
            static String type="probe.Entry${'$'}A";
            static void write(){type="${"x".repeat(4097)}";}
            static void read() throws Exception {Class.forName(type).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertTrue(indexed.observations.sumOf { it.valueAnalysisLimits } > 0)
    }

    @Test
    fun `bounded field arguments retain their limit through a static helper`(@TempDir root: Path) {
        val indexed = compile(root, """
            public static final String oversized="${"x".repeat(4097)}";
            static String type="probe.Entry${'$'}A";
            static String pass(String value){return value;}
            static void write() throws Exception {type=pass((String)Entry.class.getField("oversized").get(null));}
            static void read() throws Exception {Class.forName(type).getDeclaredConstructor().newInstance();}
        """)
        assertTargets(indexed, "read")
        assertTrue(indexed.observations.sumOf { it.valueAnalysisLimits } > 0)
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
    fun `cycle-cut summaries do not hide a later acyclic read through a helper`(@TempDir root: Path) {
        val indexed = compile(root, """
            static Class<?> left=Entry.right;
            static Class<?> right=Entry.left;
            static void select(){left=A.class;}
            static Class<?> alias(){return left;}
            static void assign(){right=alias();}
            public static void read() throws Exception {
                select();assign();
                left.getDeclaredConstructor().newInstance();
                right.getDeclaredConstructor().newInstance();
            }
        """)
        URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL())).use { loader ->
            loader.loadClass("probe.Entry").getMethod("read").invoke(null)
        }
        val calls = indexed.graph.externalCalls.filter {
            "#read(" in it.caller.value && it.owner == "java/lang/reflect/Constructor" && it.name == "newInstance"
        }
        assertEquals(2, calls.size)
        calls.forEach { assertEquals(listOf("method:probe/Entry${'$'}A#<init>()V"), it.resolvedTargets.map { target -> target.value }) }
        assertEquals(2, indexed.observations.sumOf { it.reflectiveConstructions })
    }

    @Test
    fun `supplied dependency hierarchy preserves a project ancestor field path`(@TempDir root: Path) {
        compile(root, """
            public static class Parent {public static Class<?> type=A.class;}
            public static class Dependency extends Parent {}
            public static class Child extends Dependency {}
            public static void read() throws Exception {
                ((Class<?>)Child.class.getField("type").get(null)).getDeclaredConstructor().newInstance();
            }
        """)
        val classes = root.resolve("classes")
        val dependency = Files.createDirectories(root.resolve("dependency"))
        val name = "probe/Entry${'$'}Dependency.class"
        Files.createDirectories(dependency.resolve(name).parent)
        Files.move(classes.resolve(name), dependency.resolve(name))
        URLClassLoader(arrayOf(classes.toUri().toURL(), dependency.toUri().toURL())).use { loader ->
            loader.loadClass("probe.Entry").getMethod("read").invoke(null)
        }
        val hierarchy = ClassHierarchyIndexer().index(listOf(dependency))
        assertEquals(setOf("probe/Entry${'$'}Parent"), hierarchy.directSupertypesOf("probe/Entry${'$'}Dependency"))
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        assertTargets(indexed, "read", "A")
        val access = indexed.graph.externalCalls.single { it.owner == "java/lang/reflect/Field" && it.name == "get" }
        assertEquals(listOf("field:probe/Entry${'$'}Parent#type:Ljava/lang/Class;"), access.resolvedTargets.map { it.value })
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
