package dev.kartograph.index

import dev.kartograph.core.CallDeclarationScope
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 실제 JVM 실행과 dependency 교체를 함께 확인하며 선언과 dispatch를 독립적으로 비교한다. */
class CallDeclarationJvmTest {
    @Test
    fun `dependency replacement recomputes declaration with unchanged call operands`(@TempDir root: Path) {
        val classes = compile(root.resolve("before"), mapOf(
            "app/ProjectBase.java" to "package app; public class ProjectBase { public String value() { return \"base\"; } }",
            "lib/Boundary.java" to "package lib; public class Boundary extends app.ProjectBase {}",
            "app/Child.java" to "package app; public class Child extends lib.Boundary { public String value() { return \"child\"; } }",
            "app/I.java" to "package app; public interface I { String value(); }",
            "app/Leaf.java" to "package app; public class Leaf extends lib.Boundary implements I {}",
            "app/Entry.java" to "package app; public class Entry { public static String run(lib.Boundary value) { return value.value(); } public static String direct(ProjectBase value) { return value.value(); } public static String viaInterface(I value) { return value.value(); } }",
        ))
        val jar = root.resolve("dependency.jar")
        jar(classes.resolve("lib/Boundary.class"), jar)
        Files.delete(classes.resolve("lib/Boundary.class"))
        fun execute(expected: String) = URLClassLoader(arrayOf(classes.toUri().toURL(), jar.toUri().toURL()), null).use { loader ->
            val boundary = loader.loadClass("lib.Boundary")
            val run = loader.loadClass("app.Entry").getMethod("run", boundary)
            assertEquals(expected, run.invoke(null, boundary.getConstructor().newInstance()))
            assertEquals("child", run.invoke(null, loader.loadClass("app.Child").getConstructor().newInstance()))
            assertEquals(expected, loader.loadClass("app.Entry").getMethod("direct", loader.loadClass("app.ProjectBase"))
                .invoke(null, boundary.getConstructor().newInstance()))
            assertEquals(expected, loader.loadClass("app.Entry").getMethod("viaInterface", loader.loadClass("app.I"))
                .invoke(null, loader.loadClass("app.Leaf").getConstructor().newInstance()))
        }
        execute("base")
        val cache = ClassIndexCache(root.resolve("cache"), "call-declaration")
        fun index() = ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(jar))
        val cold = index()
        val warm = index()
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(0, warm.statistics.parsedClasses)
        assertEquals(1, warm.statistics.hierarchyCacheHits)
        assertEquals(warm.graph.edges, warm.withHierarchy(warm.hierarchy).graph.edges,
            "same hierarchy must retain internal interface and virtual invocation dispatch")
        val interfaceCaller = JvmNodeId.methodId("app/Entry", "viaInterface", "(Lapp/I;)Ljava/lang/String;")
        assertTrue(warm.graph.edges.any { it.source == interfaceCaller && it.origin == dev.kartograph.core.EdgeOrigin.DISPATCH_MODEL &&
            it.target == JvmNodeId.methodId("app/ProjectBase", "value", "()Ljava/lang/String;") })
        val before = warm.graph.externalCalls.single { it.name == "value" }
        assertEquals("lib/Boundary", before.owner)
        assertEquals(listOf("method:app/Child#value()Ljava/lang/String;", "method:app/ProjectBase#value()Ljava/lang/String;"),
            before.resolvedTargets.map { it.value })
        val declaration = assertNotNull(before.declaration)
        assertEquals("method:app/ProjectBase#value()Ljava/lang/String;", declaration.target.value)
        assertEquals(CallDeclarationScope.PROJECT, declaration.scope)
        assertEquals(listOf("lib/Boundary", "app/ProjectBase"), declaration.ownerChain)
        val changed = compile(root.resolve("after"), mapOf(
            "lib/Boundary.java" to "package lib; public class Boundary extends app.ProjectBase { public String value() { return \"external\"; } }",
        ), listOf(classes))
        jar(changed.resolve("lib/Boundary.class"), jar)
        execute("external")
        val replaced = index()
        val after = replaced.graph.externalCalls.single { it.name == "value" }
        fun rawCalls(graph: dev.kartograph.core.CodeGraph) = graph.externalCalls.map {
            it.copy(resolvedTargets = emptyList(), resolution = dev.kartograph.core.CallResolution.UNRESOLVED, declaration = null)
        }
        assertEquals(rawCalls(cold.graph), rawCalls(replaced.graph))
        assertEquals(before.copy(resolvedTargets = after.resolvedTargets, resolution = after.resolution, declaration = after.declaration), after)
        assertEquals(listOf("method:app/Child#value()Ljava/lang/String;"), after.resolvedTargets.map { it.value })
        assertEquals("method:lib/Boundary#value()Ljava/lang/String;", assertNotNull(after.declaration).target.value)
        assertEquals(CallDeclarationScope.EXTERNAL, after.declaration?.scope)
        assertTrue(after.declaration?.target !in replaced.graph.nodes)
        assertEquals(0, replaced.statistics.parsedClasses)
        assertEquals(1, replaced.statistics.hierarchyParsedJars)
        assertEquals(replaced.graph.externalCalls, index().graph.externalCalls)
        assertEquals(replaced.graph.externalCalls, ClassFileIndexer().indexWithObservations(listOf(classes), listOf(jar)).graph.externalCalls)
        // 이미 보강한 그래프에도 새 hierarchy를 적용하면 오래된 선언이 남지 않는다.
        val reEnriched = warm.withHierarchy(replaced.hierarchy)
        assertEquals(after.declaration, reEnriched.graph.externalCalls.single { it.name == "value" }.declaration)
        assertEquals(replaced.graph.edges, reEnriched.graph.edges)
        assertEquals(replaced.graph.externalCalls, reEnriched.graph.externalCalls)
        assertEquals(warm.graph.edges, reEnriched.withHierarchy(warm.hierarchy).graph.edges)
        assertTrue(reEnriched.graph.edges.none { it.source == interfaceCaller && it.origin == dev.kartograph.core.EdgeOrigin.DISPATCH_MODEL },
            "changed external shadow must remove stale internal-interface dispatch candidates")
    }

    @Test
    fun `javac final static direct super and abstract declarations preserve JVM behavior`(@TempDir root: Path) {
        val classes = compile(root, mapOf(
            "p/Base.java" to "package p; public class Base { public final String fin() { return \"final\"; } public static String stat() { return \"static\"; } public String value() { return \"base\"; } protected String prot() { return \"protected\"; } }",
            "p/Middle.java" to "package p; public class Middle extends Base {}",
            "q/Child.java" to "package q; public class Child extends p.Middle { public String value() { return \"child\"; } public String sup() { return super.value(); } public String protectedCall(Child value) { return value.prot(); } }",
            "p/AbstractBase.java" to "package p; public abstract class AbstractBase { public abstract String abs(); }",
            "p/AbstractMiddle.java" to "package p; public abstract class AbstractMiddle extends AbstractBase {}",
            "p/Concrete.java" to "package p; public class Concrete extends AbstractMiddle { public String abs() { return \"concrete\"; } }",
            "p/Entry.java" to "package p; public class Entry { public static String run(Middle value, AbstractMiddle abs) { return value.fin() + Middle.stat() + value.value() + abs.abs(); } }",
        ))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val childType = loader.loadClass("q.Child")
            val child = childType.getConstructor().newInstance()
            assertEquals("base", childType.getMethod("sup").invoke(child))
            assertEquals("protected", childType.getMethod("protectedCall", childType).invoke(child, child))
            assertEquals("finalstaticchildconcrete", loader.loadClass("p.Entry")
                .getMethod("run", loader.loadClass("p.Middle"), loader.loadClass("p.AbstractMiddle"))
                .invoke(null, child, loader.loadClass("p.Concrete").getConstructor().newInstance()))
        }
        val graph = ClassFileIndexer().index(listOf(classes))
        val calls = graph.externalCalls.filter { it.name in setOf("fin", "stat", "value", "abs", "prot") }
        calls.forEach { call ->
            val owner = if (call.name == "abs") "p/AbstractBase" else "p/Base"
            assertEquals("method:$owner#${call.name}()Ljava/lang/String;", assertNotNull(call.declaration).target.value)
            assertEquals(CallDeclarationScope.PROJECT, call.declaration?.scope)
        }
        assertEquals(6, calls.size)
        val abstractCall = calls.single { it.name == "abs" }
        assertEquals(listOf("method:p/Concrete#abs()Ljava/lang/String;"), abstractCall.resolvedTargets.map { it.value })
        assertTrue(abstractCall.declaration?.target !in abstractCall.resolvedTargets)
        val finalCall = calls.single { it.name == "fin" }
        assertEquals(listOf(finalCall.declaration?.target), finalCall.resolvedTargets)
        val superCall = calls.single { it.kind == dev.kartograph.core.InvocationKind.SPECIAL }
        assertEquals("p/Middle", superCall.owner)
        assertEquals("value", superCall.name)
    }

    private fun compile(root: Path, sources: Map<String, String>, classpath: List<Path> = emptyList()): Path {
        val files = sources.map { (name, text) -> root.resolve("src/$name").also {
            it.parent.createDirectories(); Files.writeString(it, text)
        } }
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        val options = listOf("--release", "17", "-g", "-d", classes.toString()) +
            if (classpath.isEmpty()) emptyList() else listOf("-classpath", classpath.joinToString(java.io.File.pathSeparator))
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            *(options + files.map(Path::toString)).toTypedArray()), errors.toString())
        return classes
    }

    private fun jar(source: Path, target: Path) {
        JarOutputStream(Files.newOutputStream(target)).use { jar ->
            jar.putNextEntry(JarEntry("lib/Boundary.class")); jar.write(Files.readAllBytes(source)); jar.closeEntry()
        }
    }
}
