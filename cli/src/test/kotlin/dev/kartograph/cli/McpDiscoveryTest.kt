package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class McpDiscoveryTest {
    private fun method(index: Int, path: String = "src/Value.kt") = GraphNode(
        NodeId("method:p/ValueKt#value(${"I".repeat(index)})V"), "value", NodeKind.METHOD,
        location = SourceLocation(path, index + 1))

    private fun client(root: Path, nodes: List<GraphNode>, base: List<GraphNode>? = null): McpTestClient {
        fun write(name: String, values: List<GraphNode>) = Files.writeString(root.resolve(name),
            QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(values, emptyList()), emptyList(), listOf("runtime: unresolved"))))
        val file = write("current.json", nodes)
        val options = base?.let { arrayOf("--base-graph", write("base.json", it).toString()) }.orEmpty()
        return McpTestClient { input, output, error -> KartographCli.runWithInput(
            arrayOf("mcp", "--graph-file", file.toString(), *options), output, error, input) }
    }

    private fun wrapper(result: Map<*, *>): Map<*, *> {
        assertEquals(false, result["isError"], result.toString())
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        assertTrue(text.toByteArray().size <= McpTools.INTERACTIVE_CONTENT_BYTES)
        assertEquals(McpJsonCodec.parse(text), result["structuredContent"])
        return result["structuredContent"] as Map<*, *>
    }

    @Test fun `missing package functions and module prefixed files expose exact recovery candidates`(@TempDir root: Path) {
        val target = method(1)
        val facade = GraphNode(NodeId("class:p/ValueKt"), "ValueKt", NodeKind.CLASS,
            attributes = setOf(NodeAttribute.FILE_FACADE))
        client(root, listOf(target, facade)).use { c ->
            c.initialize()
            for ((tool, args) in listOf(
                "impact" to mapOf("symbols" to listOf("p.value")),
                "query_symbol" to mapOf("symbol" to "p.value"),
                "impact" to mapOf("files" to listOf("module/src/Value.kt")))) {
                val response = wrapper(c.call(tool, args))
                assertEquals("notFound", (response["document"] as Map<*, *>)["status"])
                val page = (response["suggestions"] as? List<*>)?.single() as? Map<*, *>
                assertNotNull(page, "Missing recovery for $args")
                assertEquals(target.id.value, ((page["candidates"] as List<*>).single() as Map<*, *>)["usr"])
                val retry = wrapper(c.call("impact", mapOf("symbols" to listOf(target.id.value))))
                assertEquals("found", (retry["document"] as Map<*, *>)["status"])
            }
        }
    }

    @Test fun `discovery paginates actual symbols and base locations without performing impact`(@TempDir root: Path) {
        val nodes = (1..13).map { method(it) }
        val old = method(14, "src/Old.kt")
        client(root, nodes, listOf(old, nodes.first())).use { c ->
            c.initialize()
            val seen = mutableListOf<String>()
            var offset = 0
            do {
                val result = wrapper(c.call("discover_symbols", mapOf("file" to "module/src/Value.kt", "limit" to 5, "offset" to offset)))
                val page = result["document"] as Map<*, *>
                assertEquals("kartograph-symbol-discovery", page["format"])
                assertEquals(13, (page["total"] as Number).toInt())
                assertEquals(offset, (page["offset"] as Number).toInt())
                assertEquals(true, page["truncated"])
                seen += (page["candidates"] as List<Map<*, *>>).map { it["usr"] as String }
                offset += (page["returned"] as Number).toInt()
            } while (page["hasNext"] == true)
            assertEquals(nodes.map { it.id.value }.sorted(), seen)
            val oldPage = wrapper(c.call("discover_symbols", mapOf("file" to "src/Old.kt")))["document"] as Map<*, *>
            assertEquals(listOf("base"), ((oldPage["candidates"] as List<*>).single() as Map<*, *>)["presentIn"])
            val empty = wrapper(c.call("discover_symbols", mapOf("symbol" to "Missing")))["document"] as Map<*, *>
            assertEquals(0, (empty["total"] as Number).toInt())
            assertEquals(false, empty["hasNext"])
        }
    }

    @Test fun `discovery narrows files that cannot fit an impact response`(@TempDir root: Path) {
        val nodes = (1..100).map { method(it) }
        client(root, nodes).use { c ->
            c.initialize()
            val oversized = c.call("impact", mapOf("files" to listOf("src/Value.kt")))
            assertEquals(true, oversized["isError"])
            assertContains(oversized.toString(), "discover_symbols")
            val page = wrapper(c.call("discover_symbols", mapOf("file" to "src/Value.kt")))["document"] as Map<*, *>
            assertEquals(100, (page["total"] as Number).toInt())
            val target = ((page["candidates"] as List<*>).first() as Map<*, *>)["usr"] as String
            val impact = wrapper(c.call("impact", mapOf("symbols" to listOf(target))))["document"] as Map<*, *>
            assertEquals("found", impact["status"])
            assertEquals(1, (impact["changed"] as List<*>).size)
        }
    }

    @Test fun `discovery rejects missing conflicting and non portable selectors`(@TempDir root: Path) {
        client(root, listOf(method(1))).use { c ->
            c.initialize()
            for (args in listOf(emptyMap(), mapOf("symbol" to "value", "file" to "src/Value.kt"),
                mapOf("file" to "../Value.kt"), mapOf("file" to "/private/Value.kt"),
                mapOf("file" to "src\\Value.kt"), mapOf("symbol" to "value", "offset" to -1))) {
                assertEquals(true, c.call("discover_symbols", args)["isError"], args.toString())
            }
        }
    }

    @Test fun `discovery adapts pages and preserves usable next offsets for large candidates`(@TempDir root: Path) {
        val nodes = (1..20).map { method(it, "src/" + "long/".repeat(250) + "Value.kt") }
        client(root, nodes).use { c ->
            c.initialize()
            val response = wrapper(c.call("discover_symbols", mapOf("symbol" to "value", "limit" to 20)))
            val page = response["document"] as Map<*, *>
            assertEquals(20, (page["total"] as Number).toInt())
            val returned = (page["returned"] as Number).toInt()
            assertTrue(returned in 1..19)
            assertEquals(returned, (page["nextOffset"] as Number).toInt())
            assertEquals(true, (response["response"] as Map<*, *>)["adapted"])
            val next = wrapper(c.call("discover_symbols", mapOf("symbol" to "value", "offset" to returned)))["document"] as Map<*, *>
            val firstIds = (page["candidates"] as List<Map<*, *>>).map { it["usr"] }.toSet()
            assertTrue((next["candidates"] as List<Map<*, *>>).none { it["usr"] in firstIds })
            val beyond = wrapper(c.call("discover_symbols", mapOf("symbol" to "value", "offset" to Int.MAX_VALUE)))["document"] as Map<*, *>
            assertEquals(0, (beyond["returned"] as Number).toInt())
            assertEquals(false, beyond["hasNext"])
            assertNull(beyond["nextOffset"])
        }
    }

    @Test fun `discovery reports an error when a single candidate exceeds the content budget`(@TempDir root: Path) {
        client(root, listOf(method(1, "src/" + "x".repeat(17000)))).use { c ->
            c.initialize()
            val result = c.call("discover_symbols", mapOf("symbol" to "value"))
            assertEquals(true, result["isError"])
            assertContains(result.toString(), "limit 1")
            assertFalse(result.containsKey("structuredContent"))
        }
    }
}
