package dev.kartograph.export

import dev.kartograph.core.*
import java.io.IOException
import java.io.StringWriter
import kotlin.test.*

class GraphStreamingTest {
    private fun graph(): CodeGraph {
        val source = GraphNode(NodeId("method:sample/Client#send()V"), "send", NodeKind.METHOD,
            location = SourceLocation("Client.java", 9))
        val target = GraphNode(NodeId("method:sample/Receiver#accept()V"), "accept", NodeKind.METHOD)
        return CodeGraph(listOf(source, target), listOf(GraphEdge(source.id, target.id, EdgeKind.CALL,
            weight = 2, callSiteLines = listOf(9, 12))))
    }

    @Test fun `streamed JSON preserves the released small document exactly`() {
        val graph = graph()
        val expected = GraphJsonRenderer.render(graph, "test", limitations = listOf("z", "a"))
        val writer = StringWriter()
        GraphJsonRenderer.write(graph, "test", writer, limitations = listOf("z", "a"))
        assertEquals(expected, writer.toString())
    }

    @Test fun `writer failure stops before materializing later node records`() {
        val nodes = (0 until 96).map { GraphNode(NodeId("class:sample/N${it.toString().padStart(3, '0')}"),
            "N$it", NodeKind.CLASS, location = SourceLocation("N$it.java")) }
        val graph = CodeGraph(nodes, emptyList())
        var pathsRead = 0
        val paths = object : Map<NodeId, String> by emptyMap() {
            override fun get(key: NodeId): String? { pathsRead++; return null }
        }
        var written = 0
        val writer = object : Appendable {
            override fun append(value: CharSequence?): Appendable = append(value, 0, (value ?: "null").length)
            override fun append(value: CharSequence?, start: Int, end: Int): Appendable {
                written += end - start
                if (written > 240) throw IOException("synthetic writer exhausted")
                return this
            }
            override fun append(value: Char): Appendable {
                written++
                if (written > 240) throw IOException("synthetic writer exhausted")
                return this
            }
        }
        assertFailsWith<IOException> { GraphJsonRenderer.write(graph, "test", writer, paths) }
        assertTrue(pathsRead < nodes.size, "later nodes must not be projected before the writer needs them")
    }

    @Test fun `NDJSON emits independently parseable versioned header node and edge records`() {
        val graph = graph()
        val writer = StringWriter()
        GraphJsonRenderer.writeNdjson(graph, "test", writer, limitations = listOf("synthetic-gap"))
        val records = writer.toString().lineSequence().filter(String::isNotBlank)
            .map { SnapshotJsonParser(it).parse() as Map<*, *> }.toList()
        val header = records.first()
        assertEquals("header", header["record"])
        assertEquals("code-graph-ndjson", header["format"])
        assertEquals(1L, header["version"])
        assertEquals(listOf("synthetic-gap"), header["limitations"])
        val nodes = records.filter { it["record"] == "node" }
        assertEquals(graph.nodeIds.map { it.value }, nodes.map { (it["node"] as Map<*, *>)["usr"] })
        val edge = (records.single { it["record"] == "edge" }["edge"] as Map<*, *>)
        assertEquals(listOf(9L, 12L), edge["callSiteLines"])
        assertEquals(2L, edge["weight"])
        assertTrue(writer.toString().endsWith("\n"))
    }
}
