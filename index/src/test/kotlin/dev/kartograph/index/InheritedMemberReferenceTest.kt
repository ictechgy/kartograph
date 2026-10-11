package dev.kartograph.index

import dev.kartograph.core.CallResolution
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.index.fixture.InheritedMemberBase
import dev.kartograph.index.fixture.InheritedMemberMiddle
import dev.kartograph.index.fixture.InheritedMemberChild
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/** 실제 javac가 subclass owner로 기록한 참조를 선언 정점까지 복원하는 양방향 회귀다. */
class InheritedMemberReferenceTest {
    @Test
    fun `inherited static and super calls keep exact declaration evidence`(@TempDir root: Path) {
        val graph = ClassFileIndexer().index(listOf(compile(root, """
            package probe;
            class Base {
                public static String stat() { return "base"; }
                public String value() { return "base"; }
            }
            class Mid extends Base {}
            public class Entry extends Mid {
                public String run() {
                    Mid.stat();
                    Mid.stat();
                    return super.value();
                }
            }
        """)))
        val caller = JvmNodeId.methodId("probe/Entry", "run", "()Ljava/lang/String;")
        val calls = graph.outgoingEdgesFrom(caller).filter { it.kind == EdgeKind.CALL }
        assertEquals(setOf("method:probe/Base#stat()Ljava/lang/String;", "method:probe/Base#value()Ljava/lang/String;"),
            calls.map { it.target.value }.toSet())
        val stat = calls.single { it.target.value.contains("#stat(") }
        assertEquals(2, stat.weight)
        assertEquals(2, stat.callSiteLines.size)
        assertEquals(EdgeOrigin.BYTECODE, stat.origin)
        graph.externalCalls.filter { it.caller == caller }.forEach { call ->
            assertEquals("probe/Mid", call.owner, "the original bytecode operand remains observable")
            assertEquals(CallResolution.PROJECT_CANDIDATES, call.resolution)
            assertEquals(listOf(JvmNodeId.methodId("probe/Base", call.name, call.descriptor)), call.resolvedTargets)
        }
    }

    @Test
    fun `inherited field reads and writes resolve with their JVM descriptor`(@TempDir root: Path) {
        val graph = ClassFileIndexer().index(listOf(compile(root, """
            package probe;
            class Base { public int value; public static long shared; }
            class Mid extends Base {}
            public class Entry {
                public long run(Mid value) {
                    value.value = 3;
                    Mid.shared = 4;
                    return value.value + Mid.shared;
                }
            }
        """)))
        val calls = graph.edges.filter { it.kind == EdgeKind.FIELD_ACCESS && it.source.value.contains("#run(") }
        assertEquals(setOf("field:probe/Base#value:I", "field:probe/Base#shared:J"), calls.map { it.target.value }.toSet())
        calls.forEach { assertEquals(2, it.weight) }
    }

    @Test
    fun `nearest hidden members win without adding ancestor edges`(@TempDir root: Path) {
        val graph = ClassFileIndexer().index(listOf(compile(root, """
            package probe;
            class Base { public int value; public static int stat() { return 1; } }
            class Mid extends Base { public int value; public static int stat() { return 2; } }
            class Leaf extends Mid {}
            public class Entry { public int run(Leaf leaf) { return leaf.value + Leaf.stat(); } }
        """)))
        val refs = graph.edges.filter { it.source.value.contains("#run(") && it.kind in setOf(EdgeKind.CALL, EdgeKind.FIELD_ACCESS) }
        assertEquals(setOf("field:probe/Mid#value:I", "method:probe/Mid#stat()I"), refs.map { it.target.value }.toSet())
        assertFalse(refs.any { it.target.value.contains("probe/Base#") })
    }

    @Test
    fun `interface fields follow recursive lookup and deduplicate diamonds`(@TempDir root: Path) {
        val graph = ClassFileIndexer().index(listOf(compile(root, """
            package probe;
            interface Values { Object TOKEN = new Object(); }
            interface Left extends Values {}
            interface Right extends Values {}
            class Base implements Left, Right {}
            public class Entry {
                public Object run() { return Base.TOKEN; }
                public Object direct() { return Left.TOKEN; }
            }
        """)))
        val refs = graph.edges.filter { it.kind == EdgeKind.FIELD_ACCESS && it.source.value.startsWith("method:probe/Entry#") }
        assertEquals(2, refs.size)
        assertEquals(setOf("field:probe/Values#TOKEN:Ljava/lang/Object;"), refs.map { it.target.value }.toSet())
    }

    @Test
    fun `missing superclass inputs are not bridged to unrelated members`(@TempDir root: Path) {
        val classes = compile(root, """
            package probe;
            class Base { public static int stat() { return 1; } public int value; }
            class Mid extends Base {}
            class Leaf extends Mid {}
            public class Entry { public int run(Leaf leaf) { return leaf.value + Leaf.stat(); } }
        """)
        Files.delete(classes.resolve("probe/Mid.class"))
        val graph = ClassFileIndexer().index(listOf(classes))
        assertEquals(emptyList(), graph.edges.filter { it.source.value.contains("#run(") && it.kind in setOf(EdgeKind.CALL, EdgeKind.FIELD_ACCESS) })
        assertEquals(CallResolution.UNRESOLVED, graph.externalCalls.single { it.name == "stat" }.resolution)
    }

    @Test
    fun `Kotlin super and inherited field references preserve virtual uncertainty`(@TempDir root: Path) {
        listOf(InheritedMemberBase::class.java, InheritedMemberMiddle::class.java, InheritedMemberChild::class.java).forEach { type ->
            val name = type.name.replace('.', '/') + ".class"
            val destination = root.resolve(name)
            destination.parent.createDirectories()
            type.classLoader.getResourceAsStream(name)!!.use { Files.copy(it, destination) }
        }
        val graph = ClassFileIndexer().index(listOf(root))
        val prefix = "dev/kartograph/index/fixture/InheritedMember"
        val superCalls = graph.edges.filter { it.source == JvmNodeId.methodId(prefix + "Child", "callSuper", "()I") && it.kind == EdgeKind.CALL }
        assertEquals(listOf(JvmNodeId.methodId(prefix + "Base", "inherited", "()I")), superCalls.map { it.target })
        val fieldReads = graph.edges.filter { it.source == JvmNodeId.methodId(prefix + "Child", "readInherited", "()I") && it.kind == EdgeKind.FIELD_ACCESS }
        assertEquals(listOf(JvmNodeId.fieldId(prefix + "Base", "value", "I")), fieldReads.map { it.target })
        assertFalse(graph.edges.any { it.source.value.contains("#callVirtual(") && it.kind == EdgeKind.CALL && it.target.value.contains("#inherited(") },
            "virtual calls remain in the existing dispatch model")
    }

    @Test
    fun `cached class facts rederive inherited evidence without duplicate weights`(@TempDir root: Path) {
        val classes = compile(root, """
            package probe;
            class Base { public static int stat() { return 1; } public int value; }
            class Child extends Base {}
            public class Entry { public int run(Child child) { return child.value + Child.stat(); } }
        """)
        val cache = ClassIndexCache(root.resolve("cache"), "inherited-member-regression")
        val expected = ClassFileIndexer().index(listOf(classes))
        ClassFileIndexer(cache).index(listOf(classes))
        val warmed = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(3, warmed.statistics.cacheHits)
        assertEquals(0, warmed.statistics.parsedClasses)
        assertEquals(expected.edges, warmed.graph.edges)
        assertEquals(expected.externalCalls, warmed.graph.externalCalls)
        assertFalse(RuntimeLimitationScanner.scan(warmed, root).any { it.startsWith("dispatch-candidates:") },
            "an exact inherited static target is not uncertain virtual dispatch")
    }

    @Test
    fun `mixed special and virtual operands retain only exact call site evidence`(@TempDir root: Path) {
        val source = """
            package probe;
            class Base { public int value() { return 1; } }
            class Mid extends Base {}
            public class Entry extends Mid {
                public int run() {
                    ((Mid)this).value();
                    super.value();
                    ((Mid)this).value();
                    return super.value();
                }
                public int sameLine() { return super.value() + ((Mid)this).value(); }
            }
        """.trimIndent()
        val classes = compile(root, source)
        val cache = ClassIndexCache(root.resolve("cache"), "mixed-inherited-calls")
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        listOf(cold, warm).forEach { indexed ->
            val calls = indexed.graph.edges.filter { it.kind == EdgeKind.CALL && it.target == JvmNodeId.methodId("probe/Base", "value", "()I") }
            val repeated = calls.single { it.source == JvmNodeId.methodId("probe/Entry", "run", "()I") }
            assertEquals(2, repeated.weight)
            assertEquals(listOf(7, 9), repeated.callSiteLines, "virtual instruction lines must not become exact-call evidence")
            val sameLine = calls.single { it.source == JvmNodeId.methodId("probe/Entry", "sameLine", "()I") }
            assertEquals(1, sameLine.weight)
            assertEquals(listOf(11), sameLine.callSiteLines)
            assertEquals(listOf("dispatch-candidates: 3 external virtual call(s) use conservative hierarchy candidates rather than proven receivers"),
                RuntimeLimitationScanner.scan(indexed, root).filter { it.startsWith("dispatch-candidates:") })
        }
    }

    private fun compile(root: Path, source: String): Path {
        val file = root.resolve("Entry.java")
        Files.writeString(file, source.trimIndent())
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        val javac = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, javac.run(null, null, errors, "--release", "17", "-g", "-d", classes.toString(), file.toString()), errors.toString())
        return classes
    }
}
