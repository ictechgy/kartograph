package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.*
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.GraphExportIndexOptions
import dev.kartograph.cli.fixture.defaultbridge.BridgeFixture
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/** source association은 graph export에만 추가하고 raw CALL 기반 CLI/MCP 질의를 바꾸지 않는다. */
class KotlinDefaultBridgeCliTest {
    @Test fun `graph export exposes evidence while snapshots CLI and MCP retain the lowered call`(@TempDir root: Path) {
        val owner = "dev/kartograph/cli/fixture/defaultbridge/BridgeFixture"
        val classes = root.resolve("classes")
        val file = classes.resolve("$owner.class")
        Files.createDirectories(file.parent)
        val compiled = Path.of(BridgeFixture::class.java.protectionDomain.codeSource.location.toURI()).resolve("$owner.class")
        Files.copy(compiled, file)
        val graph = ClassFileIndexer().index(listOf(classes))
        val caller = NodeId("method:$owner#caller()I")
        val target = NodeId("method:$owner#label(I)I")
        val bridge = graph.nodes.values.single { it.kotlinDefaultBridge != null }
        assertEquals(target, bridge.kotlinDefaultBridge!!.target)
        assertTrue(graph.outgoingEdgesFrom(caller).any { it.kind == EdgeKind.CALL && it.target == bridge.id })
        assertFalse(graph.outgoingEdgesFrom(caller).any { it.kind == EdgeKind.CALL && it.target == target })
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(arrayOf("graph", "--classes", classes.toString(), "--format", "json"),
            PrintStream(output), PrintStream(error)), error.toString())
        assertContains(output.toString(), "kotlinDefaultBridge")
        assertContains(output.toString(), "kotlinMetadataAndBytecode")
        val selected = ClassFileIndexer().indexForGraphExport(listOf(classes), options =
            GraphExportIndexOptions(excludedOrigins = setOf(EdgeOrigin.BYTECODE))).indexed.graph
        assertTrue(selected.nodes.values.all { it.kotlinDefaultBridge == null })
        for (compact in listOf(false, true)) {
            val selectedRestored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(selected, emptyList(), emptyList()), compact)).graph
            assertEquals(selected.nodes, selectedRestored.nodes)
            assertEquals(selected.edges, selectedRestored.edges)
        }
        for (compact in listOf(false, true)) {
            val snapshot = Files.writeString(root.resolve("snapshot-$compact.json"),
                QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact))
            assertEquals(bridge.kotlinDefaultBridge, QuerySnapshotCodec.parse(Files.readString(snapshot)).graph.node(bridge.id)!!.kotlinDefaultBridge)
            output.reset(); error.reset()
            assertEquals(0, KartographCli.run(arrayOf("query", bridge.id.value, "--graph-file", snapshot.toString()),
                PrintStream(output), PrintStream(error)), error.toString())
            val document = McpJsonCodec.parse(output.toString()) as Map<*, *>
            val usedBy = (document["result"] as Map<*, *>)["usedBy"] as List<*>
            assertTrue(usedBy.any { it.toString().contains(caller.value) })
            McpTestClient { input, out, err ->
                KartographCli.runWithInput(arrayOf("mcp", "--graph-file", snapshot.toString()), out, err, input)
            }.use { client ->
                client.initialize()
                val response = client.call("query_symbol", mapOf("symbol" to bridge.id.value))
                assertEquals(false, response["isError"])
                val queried = (response["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
                assertEquals(usedBy, (queried["result"] as Map<*, *>)["usedBy"])
            }
        }
    }
}
