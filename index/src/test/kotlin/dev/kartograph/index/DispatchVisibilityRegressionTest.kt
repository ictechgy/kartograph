package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** javac와 JVM의 실제 호출 결과를 프로젝트 dispatch 후보의 가시성 경계와 비교한다. */
class DispatchVisibilityRegressionTest {
    @Test
    fun `package private project declaration excludes unrelated cross package method`(@TempDir root: Path) {
        val classes = compile(root, mapOf(
            "p/Base.java" to """
                package p;
                public class Base {
                    String m() { return "base"; }
                    public static String callBase(Base value) { return value.m(); }
                    public static String callInherited(Middle value) { return value.m(); }
                }
            """,
            "p/Middle.java" to "package p; public class Middle extends Base {}",
            "q/Child.java" to "package q; public class Child extends p.Middle { public String m() { return \"child\"; } }",
        ))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val base = loader.loadClass("p.Base")
            val middle = loader.loadClass("p.Middle")
            val child = loader.loadClass("q.Child").getConstructor().newInstance()
            assertEquals("base", base.getMethod("callBase", base).invoke(null, child))
            assertEquals("base", base.getMethod("callInherited", middle).invoke(null, child))
        }
        val graph = cachedGraph(root, classes)
        val inheritedCall = graph.externalCalls.single { it.name == "m" }
        assertEquals("p/Middle", inheritedCall.owner)
        listOf(inheritedCall).forEach { call ->
            assertEquals(listOf(JvmNodeId.methodId("p/Base", "m", "()Ljava/lang/String;")), call.resolvedTargets)
        }
        assertFalse(graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL && it.target == JvmNodeId.methodId("q/Child", "m", "()Ljava/lang/String;") })
        assertFalse(graph.edges.any { it.kind == EdgeKind.OVERRIDE && it.source == JvmNodeId.methodId("p/Base", "m", "()Ljava/lang/String;") && it.target == JvmNodeId.methodId("q/Child", "m", "()Ljava/lang/String;") })
    }

    @Test
    fun `same package public protected and transitive overrides remain candidates`(@TempDir root: Path) {
        val classes = compile(root, mapOf(
            "p/Base.java" to """
                package p;
                public class Base {
                    String m() { return "base"; }
                    public String pub() { return "base"; }
                    protected String prot() { return "base"; }
                    public static String callBase(Base value) { return value.m() + value.pub() + value.prot(); }
                    public static String callInherited(Middle value) { return value.m() + value.pub() + value.prot(); }
                }
            """,
            "p/Middle.java" to "package p; public class Middle extends Base {}",
            "p/Bridge.java" to "package p; public class Bridge extends Middle { public String m() { return \"middle\"; } }",
            "q/Child.java" to """
                package q;
                public class Child extends p.Bridge {
                    public String m() { return "child"; }
                    public String pub() { return "child"; }
                    protected String prot() { return "child"; }
                }
            """,
            "q/Leaf.java" to "package q; public class Leaf extends Child {}",
        ))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val base = loader.loadClass("p.Base")
            val child = loader.loadClass("q.Leaf").getConstructor().newInstance()
            assertEquals("childchildchild", base.getMethod("callBase", base).invoke(null, child))
        }
        val graph = cachedGraph(root, classes)
        val calls = graph.externalCalls.filter { it.name in setOf("m", "pub", "prot") }
        assertEquals(setOf("m", "pub", "prot"), calls.map { it.name }.toSet())
        calls.forEach { call ->
            assertTrue(JvmNodeId.methodId("q/Child", call.name, call.descriptor) in call.resolvedTargets, call.toString())
            if (call.name == "m") assertTrue(JvmNodeId.methodId("p/Bridge", call.name, call.descriptor) in call.resolvedTargets)
            assertEquals(call.resolvedTargets.distinct(), call.resolvedTargets)
        }
    }

    @Test
    fun `inherited final project method retains its only implementation`(@TempDir root: Path) {
        val classes = compile(root, mapOf(
            "p/Base.java" to "package p; public class Base { public final String m() { return \"base\"; } }",
            "q/Child.java" to "package q; public class Child extends p.Base {}",
            "p/Entry.java" to "package p; public class Entry { public String run(q.Child value) { return value.m(); } }",
        ))
        val call = cachedGraph(root, classes).externalCalls.single { it.name == "m" }
        assertEquals("q/Child", call.owner)
        assertEquals(listOf(JvmNodeId.methodId("p/Base", "m", "()Ljava/lang/String;")), call.resolvedTargets)
    }

    @Test
    fun `external class override shadows project ancestor and interface bodies`(@TempDir root: Path) {
        listOf(false, true).forEach { isFinal ->
            val directory = root.resolve(if (isFinal) "final" else "virtual").createDirectories()
            val sources = linkedMapOf(
                "app/G.java" to "package app; public class G { public String m() { return \"grandparent\"; } }",
                "app/I.java" to "package app; public interface I { default String m() { return \"interface\"; } }",
                "lib/ExternalI.java" to "package lib; public interface ExternalI { String m(); }",
                "lib/P.java" to "package lib; public class P extends app.G implements ExternalI { public ${if (isFinal) "final " else ""}String m() { return \"external\"; } }",
                "app/C.java" to "package app; public class C extends lib.P implements I {}",
                "app/Entry.java" to "package app; public class Entry { public static String run(C value) { return value.m(); } }",
            )
            if (!isFinal) sources["app/Sub.java"] = "package app; public class Sub extends C { public String m() { return \"sub\"; } }"
            val classes = compile(directory, sources)
            val dependency = directory.resolve("dependency.jar")
            JarOutputStream(Files.newOutputStream(dependency)).use { jar ->
                listOf("lib/P.class", "lib/ExternalI.class").forEach { name ->
                    jar.putNextEntry(JarEntry(name))
                    jar.write(Files.readAllBytes(classes.resolve(name)))
                    jar.closeEntry()
                }
            }
            Files.delete(classes.resolve("lib/P.class"))
            Files.delete(classes.resolve("lib/ExternalI.class"))
            URLClassLoader(arrayOf(classes.toUri().toURL(), dependency.toUri().toURL()), null).use { loader ->
                val child = loader.loadClass("app.C")
                assertEquals("external", loader.loadClass("app.Entry").getMethod("run", child)
                    .invoke(null, child.getConstructor().newInstance()))
            }
            val graph = cachedGraph(directory, classes, listOf(dependency))
            val call = graph.externalCalls.single { it.name == "m" }
            val expected = if (isFinal) emptyList() else listOf(JvmNodeId.methodId("app/Sub", "m", "()Ljava/lang/String;"))
            assertEquals(expected, call.resolvedTargets, "an external body shadows both older project and interface bodies")
        }
    }

    @Test
    fun `unknown legacy hierarchy kinds do not shadow a known project class`(@TempDir root: Path) {
        val classes = compile(root, mapOf(
            "a/I.java" to "package a; public interface I { String m(); }",
            "z/Base.java" to "package z; public class Base { public String m() { return \"base\"; } }",
            "z/Child.java" to "package z; public class Child extends Base implements a.I {}",
            "z/Entry.java" to "package z; public class Entry { public static String run(Child value) { return value.m(); } }",
        ))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val child = loader.loadClass("z.Child")
            assertEquals("base", loader.loadClass("z.Entry").getMethod("run", child)
                .invoke(null, child.getConstructor().newInstance()))
        }
        Files.delete(classes.resolve("a/I.class"))
        val graph = ClassFileIndexer().index(listOf(classes))
        val entries = mapOf("a/I" to setOf("java/lang/Object"))
        val methods = mapOf("a/I" to listOf(dev.kartograph.core.HierarchyMethod(
            "m", "()Ljava/lang/String;", dev.kartograph.core.Visibility.PUBLIC)))
        val legacy = dev.kartograph.core.ClassHierarchy(entries, methods, emptyMap())
        val known = dev.kartograph.core.ClassHierarchy(entries, methods, emptyMap(), mapOf("a/I" to true))
        val expected = listOf(JvmNodeId.methodId("z/Base", "m", "()Ljava/lang/String;"))
        val legacyCall = ExternalDispatchIndexer.enrich(graph, legacy).externalCalls.single { it.name == "m" }
        val knownCall = ExternalDispatchIndexer.enrich(graph, known).externalCalls.single { it.name == "m" }
        assertEquals(expected, legacyCall.resolvedTargets, "unknown kind must not hide the inherited project implementation")
        assertEquals(dev.kartograph.core.CallResolution.UNRESOLVED, legacyCall.resolution)
        assertEquals(expected, knownCall.resolvedTargets)
        assertEquals(dev.kartograph.core.CallResolution.PROJECT_CANDIDATES, knownCall.resolution)
    }

    private fun cachedGraph(root: Path, classes: Path, classpath: List<Path>? = null): dev.kartograph.core.CodeGraph {
        val uncached = ClassFileIndexer().indexWithObservations(listOf(classes), classpath)
        val cache = ClassIndexCache(root.resolve("cache"), "dispatch-visibility-regression")
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes), classpath)
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes), classpath)
        assertEquals(uncached.graph.nodes, cold.graph.nodes)
        assertEquals(uncached.graph.edges, cold.graph.edges)
        assertEquals(uncached.graph.externalCalls, cold.graph.externalCalls)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(cold.statistics.classFiles, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.parsedClasses)
        if (!classpath.isNullOrEmpty()) {
            assertEquals(1, warm.statistics.hierarchyCacheHits)
            listOf(uncached, cold, warm).forEach {
                assertEquals(false, it.hierarchy.isInterface("lib/P"))
                assertEquals(true, it.hierarchy.isInterface("lib/ExternalI"))
            }
        }
        return warm.graph
    }

    private fun compile(root: Path, sources: Map<String, String>): Path {
        val files = sources.map { (name, content) -> root.resolve("src/$name").also {
            it.parent.createDirectories()
            Files.writeString(it, content.trimIndent())
        } }
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            "--release", "17", "-g", "-d", classes.toString(), *files.map(Path::toString).toTypedArray()), errors.toString())
        return classes
    }
}
