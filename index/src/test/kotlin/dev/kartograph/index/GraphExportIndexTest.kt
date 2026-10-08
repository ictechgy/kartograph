package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class GraphExportIndexTest {
    private fun fixture(root: Path): Pair<Path, Path> {
        val library = Files.createDirectories(root.resolve("library"))
        val input = Files.createDirectories(root.resolve("input"))
        val api = root.resolve("ServicePort.java")
        Files.writeString(api, "package sample; public interface ServicePort { void send(); }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", library.toString(), api.toString()))
        val files = (0 until 7).map { index -> root.resolve("Handler$index.java").also {
            Files.writeString(it, "package sample; public class Handler$index implements ServicePort { public void send() {} }")
        }} + listOf(root.resolve("Consumer.java").also {
            Files.writeString(it, "package sample; public class Consumer { public void use(ServicePort port) { port.send(); } }")
        })
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-classpath", library.toString(), "-d", input.toString(), *files.map(Path::toString).toTypedArray()))
        return input to library
    }

    @Test fun `excluding modeled dispatch preserves bytecode without generating candidate edges`(@TempDir root: Path) {
        val (input, library) = fixture(root)
        val normal = ClassFileIndexer(callbackFacts = false).indexWithObservations(listOf(input), listOf(library), emptyList())
        assertTrue(normal.graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        val result = ClassFileIndexer(callbackFacts = false).indexForGraphExport(listOf(input), listOf(library),
            options = GraphExportIndexOptions(excludedOrigins = setOf(EdgeOrigin.DISPATCH_MODEL)))
        assertFalse(result.indexed.graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        assertEquals(normal.graph.edges.filter { it.origin != EdgeOrigin.DISPATCH_MODEL }, result.indexed.graph.edges)
        assertTrue(result.indexed.graph.externalCalls.any { it.name == "send" })
    }

    @Test fun `over limit dispatch omits all candidates and reports the measured gap`(@TempDir root: Path) {
        val (input, library) = fixture(root)
        val result = ClassFileIndexer(callbackFacts = false).indexForGraphExport(listOf(input), listOf(library),
            options = GraphExportIndexOptions(maximumDispatchCandidates = 2))
        assertFalse(result.indexed.graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        assertTrue(result.limitations.any { it.startsWith("dispatch-candidates-over-limit: 1 ") })
        assertTrue(result.indexed.graph.externalCalls.single { it.name == "send" }.resolvedTargets.isEmpty())
        assertEquals(CallResolution.UNRESOLVED, result.indexed.graph.externalCalls.single { it.name == "send" }.resolution)
    }

    @Test fun `external stubs retain raw typed relationships and external invocation records`(@TempDir root: Path) {
        val (input, library) = fixture(root)
        val result = ClassFileIndexer(callbackFacts = false).indexForGraphExport(listOf(input), listOf(library),
            options = GraphExportIndexOptions(excludedOrigins = setOf(EdgeOrigin.DISPATCH_MODEL), includeExternalStubs = true))
        val graph = result.indexed.graph
        val type = JvmNodeId.classId("sample/ServicePort")
        val method = JvmNodeId.methodId("sample/ServicePort", "send", "()V")
        assertTrue(NodeAttribute.EXTERNAL_STUB in graph.nodes.getValue(type).attributes)
        assertTrue(NodeAttribute.EXTERNAL_STUB in graph.nodes.getValue(method).attributes)
        assertTrue(graph.edges.any { it.target == type && it.kind == EdgeKind.INHERITANCE })
        assertTrue(graph.edges.any { it.target == method && it.kind == EdgeKind.CALL })
        assertTrue(graph.externalCalls.any { it.target == method })
        assertEquals(null, graph.nodes.getValue(type).location)
        assertEquals("sample/ServicePort", graph.nodes.getValue(type).jvmSignature)
        assertEquals("sample/ServicePort#send()V", graph.nodes.getValue(method).jvmSignature)
        assertFalse(graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        val original = ClassFileIndexer(callbackFacts = false).indexForGraphExport(listOf(input), listOf(library),
            options = GraphExportIndexOptions(excludedOrigins = setOf(EdgeOrigin.DISPATCH_MODEL))).indexed.graph
        assertEquals(original.edges, graph.edges.filter { it.source in original.nodes && it.target in original.nodes })
    }

    @Test fun `exact calls across input roots are call edges rather than external records`(@TempDir root: Path) {
        val library = Files.createDirectories(root.resolve("library"))
        val input = Files.createDirectories(root.resolve("input"))
        val target = root.resolve("Target.java")
        Files.writeString(target, "package sample; public class Target { public static void hit() {} }")
        val caller = root.resolve("Caller.java")
        Files.writeString(caller, "package sample; public class Caller { public void run() { Target.hit(); } }")
        val javac = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, javac.run(null, null, null, "-g", "-d", library.toString(), target.toString()))
        assertEquals(0, javac.run(null, null, null, "-g", "-cp", library.toString(), "-d", input.toString(), caller.toString()))
        val graph = ClassFileIndexer(callbackFacts = false).index(listOf(input, library))
        val id = JvmNodeId.methodId("sample/Target", "hit", "()V")
        assertTrue(graph.edges.any { it.target == id && it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE })
        assertFalse(graph.externalCalls.any { it.target == id })
    }
}
