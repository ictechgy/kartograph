package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/** 부분 hierarchy에서 남긴 보수적 후보와 미해결 상태를 snapshot·CLI가 함께 보존한다. */
class UnresolvedDispatchSnapshotTest {
    @Test
    fun `partial dispatch candidates roundtrip and stay unresolved rather than bound`(@TempDir root: Path) {
        val caller = NodeId("method:z/Entry#run(Lz/Child;)V")
        val target = NodeId("method:z/Base#m()V")
        val nodes = listOf(
            GraphNode(NodeId("class:z/Base"), "Base", NodeKind.CLASS, jvmSignature = "z/Base"),
            GraphNode(NodeId("class:z/Child"), "Child", NodeKind.CLASS, jvmSignature = "z/Child", supertypes = setOf("z/Base", "a/I")),
            GraphNode(caller, "run", NodeKind.METHOD),
            GraphNode(target, "m", NodeKind.METHOD, jvmVisibility = Visibility.PUBLIC),
        )
        val call = ExternalCall(caller, "z/Child", "m", "()V", InvocationKind.VIRTUAL,
            resolvedTargets = listOf(target), resolution = CallResolution.UNRESOLVED)
        val graph = CodeGraph(nodes, listOf(GraphEdge(caller, target, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)), listOf(call))
        for (compact in listOf(false, true)) {
            val encoded = QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact)
            val decoded = QuerySnapshotCodec.parse(encoded)
            assertEquals(listOf(call), decoded.graph.externalCalls)
            assertEquals(graph.edges, decoded.graph.edges)
            val file = Files.writeString(root.resolve("snapshot-$compact.json"), encoded)
            fun reach(mode: String): Map<*, *> {
                val output = ByteArrayOutputStream()
                val error = ByteArrayOutputStream()
                val status = KartographCli.run(arrayOf("reach", caller.value, "--graph-file", file.toString(),
                    "--project", root.toString(), "--dispatch", mode), PrintStream(output), PrintStream(error))
                assertEquals(0, status, error.toString())
                return McpJsonCodec.parse(output.toString()) as Map<*, *>
            }
            val document = reach("candidates")
            val rootRow = (document["roots"] as List<*>).single() as Map<*, *>
            assertEquals(1L, rootRow["unresolvedCalls"])
            val reached = (document["reached"] as List<*>).map { it as Map<*, *> }
            val row = reached.single { (it["symbol"] as Map<*, *>)["usr"] == target.value }
            assertEquals("candidate", row["evidence"])
            assertFalse((reach("bound")["reached"] as List<*>).any {
                ((it as Map<*, *>)["symbol"] as Map<*, *>)["usr"] == target.value
            })
        }
    }
}
