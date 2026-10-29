package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.*
import dev.kartograph.index.ClassFileIndexer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 프로젝트 호출 후보가 새 external call 없이 snapshot·CLI·MCP에 같은 의미로 노출되는지 확인한다. */
class ProjectDispatchSnapshotTest {
    @Test
    fun `project dispatch is preserved by both snapshots and interactive queries`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("Cases.java"), """
            public class Cases {
                interface I { void m(); }
                static class A implements I { public void m() {} }
                static class B implements I { public void m() {} }
                static void run(I value) { value.m(); }
            }
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, "--release", "17", "-g", "-d", classes.toString(), source.toString()))
        val graph = ClassFileIndexer().index(listOf(classes))
        val caller = NodeId("method:Cases#run(LCases\$I;)V")
        val target = NodeId("method:Cases\$B#m()V")
        assertTrue(graph.externalCalls.none { it.caller == caller })
        assertTrue(graph.edges.any { it.source == caller && it.target == target && it.origin == EdgeOrigin.DISPATCH_MODEL })
        for (compact in listOf(false, true)) {
            val encoded = QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact)
            val restored = QuerySnapshotCodec.parse(encoded)
            assertEquals(graph.externalCalls, restored.graph.externalCalls)
            assertEquals(graph.edges, restored.graph.edges)
            val file = Files.writeString(root.resolve("graph-$compact.json"), encoded)
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            assertEquals(0, KartographCli.run(arrayOf("reach", caller.value, "--graph-file", file.toString(), "--project", root.toString()),
                PrintStream(output), PrintStream(error)), error.toString())
            val document = McpJsonCodec.parse(output.toString()) as Map<*, *>
            val reached = (document["reached"] as List<*>).map { it as Map<*, *> }
            assertTrue(reached.any { (it["symbol"] as Map<*, *>)["usr"] == target.value })
            val rootRow = (document["roots"] as List<*>).single() as Map<*, *>
            assertEquals(null, rootRow["unresolvedCalls"], "internal candidates must not add external diagnostics")
            McpTestClient { input, out, err ->
                KartographCli.runWithInput(arrayOf("mcp", "--graph-file", file.toString()), out, err, input)
            }.use { client ->
                client.initialize()
                val response = client.call("query_symbol", mapOf("symbol" to target.value))
                assertEquals(false, response["isError"])
                val queried = (response["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
                val usedBy = (queried["result"] as Map<*, *>)["usedBy"] as List<*>
                assertTrue(usedBy.any { it.toString().contains(caller.value) }, usedBy.toString())
            }
        }
    }
}
