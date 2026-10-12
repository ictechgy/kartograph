package dev.kartograph.index

import dev.kartograph.core.*
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 프로젝트 정점 호출의 opcode 사실을 잃지 않고 실행 가능한 후보를 보존한다. */
class ProjectDispatchRegressionTest {
    @Test
    fun `project calls keep legal bodies without expanding special static or private calls`(@TempDir root: Path) {
        val source = root.resolve("Cases.java")
        Files.writeString(source, """
            public class Cases {
                public static class Base { public String m() { return "base"; } }
                public static class Left extends Base {}
                public static class LeftSub extends Left { public String m() { return "left"; } }
                public static class Right extends Base { public String m() { return "right"; } }
                public static class Special extends Base {
                    public String m() { return "special"; }
                    public String exact() { return super.m(); }
                    private String privateBody() { return "private"; }
                    public String privateCall() { return privateBody(); }
                    public static String staticBody() { return "static"; }
                    public String staticCall() { return staticBody(); }
                    public final String finalBody() { return "final"; }
                    public String finalCall() { return finalBody(); }
                }
                public interface I { String m(); }
                public static class Super { public String m() { return "inherited"; } }
                public static class Impl extends Super implements I {}
                public static class Other implements I { public String m() { return "other"; } }
                public abstract static class Abstract { public abstract String m(); }
                public static class Concrete extends Abstract { public String m() { return "concrete"; } }
                public abstract static class Open { public String m() { return "open"; } }
                public static String direct(Base value) { return value.m(); }
                public static String left(Left value) { return value.m(); }
                public static String iface(I value) { return value.m(); }
                public static String abstractCall(Abstract value) { return value.m(); }
                public static String open(Open value) { return value.m(); }
                public static void oracle() {
                    if (!direct(new Base()).equals("base") || !direct(new LeftSub()).equals("left") ||
                        !direct(new Right()).equals("right") || !direct(new Special()).equals("special") ||
                        !left(new Left()).equals("base") || !left(new LeftSub()).equals("left") ||
                        !iface(new Impl()).equals("inherited") || !iface(new Other()).equals("other") ||
                        !abstractCall(new Concrete()).equals("concrete") || !new Special().exact().equals("base") ||
                        !new Special().privateCall().equals("private") || !new Special().staticCall().equals("static") ||
                        !new Special().finalCall().equals("final")) throw new AssertionError("runtime oracle");
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()), errors.toString())
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { it.loadClass("Cases").getMethod("oracle").invoke(null) }
        val cache = ClassIndexCache(root.resolve("cache"), "project-dispatch-regression")
        val uncached = ClassFileIndexer().indexWithObservations(listOf(classes))
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(uncached.graph.edges, cold.graph.edges)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(warm.statistics.classFiles, warm.statistics.cacheHits)
        val graph = warm.graph
        fun method(owner: String, name: String = "m", descriptor: String = "()Ljava/lang/String;") =
            JvmNodeId.methodId(owner, name, descriptor)
        fun bodies(caller: NodeId): Set<NodeId> = graph.edges.filter { it.source == caller &&
            (it.origin == EdgeOrigin.DISPATCH_MODEL || it.kind == EdgeKind.CALL) &&
            JvmModifier.ABSTRACT !in graph.nodes.getValue(it.target).jvmModifiers }.map { it.target }.toSet()
        fun entry(name: String, receiver: String) = method("Cases", name, "(LCases\$$receiver;)Ljava/lang/String;")
        assertEquals(setOf("Base", "LeftSub", "Right", "Special").map { method("Cases\$$it") }.toSet(), bodies(entry("direct", "Base")))
        assertEquals(setOf("Base", "LeftSub").map { method("Cases\$$it") }.toSet(), bodies(entry("left", "Left")))
        assertEquals(setOf("Super", "Other").map { method("Cases\$$it") }.toSet(), bodies(entry("iface", "I")))
        assertEquals(setOf(method("Cases\$Concrete")), bodies(entry("abstractCall", "Abstract")))
        assertEquals(setOf(method("Cases\$Open")), bodies(entry("open", "Open")), "open-world project body can serve an external concrete subtype")
        assertEquals(setOf(method("Cases\$Base")), bodies(method("Cases\$Special", "exact")))
        listOf("private", "static", "final").forEach { kind ->
            val caller = method("Cases\$Special", "${kind}Call")
            assertEquals(setOf(method("Cases\$Special", "${kind}Body")), bodies(caller))
            assertFalse(graph.edges.any { it.source == caller && it.origin == EdgeOrigin.DISPATCH_MODEL })
        }
        assertFalse(graph.externalCalls.any { it.target in graph.nodes }, "internal invocation metadata must not inflate public external-call diagnostics")
        assertTrue(graph.edges.any { it.source == entry("iface", "I") && it.target == method("Cases\$I") && it.kind == EdgeKind.CALL },
            "abstract declaration references remain call facts, separately from executable dispatch bodies")
    }
    @Test
    fun `external concrete static receiver inherits a project body`(@TempDir root: Path) {
        val sources = listOf(
            "Open.java" to "public abstract class Open { public String m() { return \"open\"; } }",
            "External.java" to "public class External extends Open {}",
            "Entry.java" to "public class Entry { public static String run(External value) { return value.m(); } }",
        ).map { (name, source) -> Files.writeString(root.resolve(name), source) }
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "--release", "17", "-d", classes.toString(), *sources.map(Path::toString).toTypedArray()))
        val dependency = root.resolve("dependency.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(dependency)).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("External.class"))
            jar.write(Files.readAllBytes(classes.resolve("External.class")))
            jar.closeEntry()
        }
        Files.delete(classes.resolve("External.class"))
        URLClassLoader(arrayOf(classes.toUri().toURL(), dependency.toUri().toURL()), null).use { loader ->
            val receiver = loader.loadClass("External")
            assertEquals("open", loader.loadClass("Entry").getMethod("run", receiver).invoke(null, receiver.getConstructor().newInstance()))
        }
        val cache = ClassIndexCache(root.resolve("cache"), "external-concrete-receiver")
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(dependency))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(dependency))
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(listOf(JvmNodeId.methodId("Open", "m", "()Ljava/lang/String;")),
            warm.graph.externalCalls.single { it.owner == "External" && it.name == "m" }.resolvedTargets)
    }

}
