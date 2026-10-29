package dev.kartograph.export

import dev.kartograph.core.*
import java.io.StringWriter
import kotlin.test.*

class GraphCsvRendererTest {
    @Test fun `unsorted duplicate origin observations are canonicalized before streamed coalescing`() {
        val a = GraphNode(NodeId("class:A"), "A", NodeKind.CLASS)
        val b = GraphNode(NodeId("class:B"), "B", NodeKind.CLASS)
        val graph = CodeGraph(listOf(b, a), listOf(
            GraphEdge(a.id, b.id, EdgeKind.REFERENCE, 3),
            GraphEdge(b.id, a.id, EdgeKind.REFERENCE),
            GraphEdge(a.id, b.id, EdgeKind.REFERENCE, 2, EdgeOrigin.KOTLIN_METADATA),
            GraphEdge(a.id, b.id, EdgeKind.REFERENCE, 4)))
        val edges = exportEdges(graph).toList()
        assertEquals(2, edges.size)
        val projected = edges.single { it.source == a.id }
        assertEquals(9, projected.weight)
        assertEquals(mapOf("bytecode" to 7, "kotlinMetadata" to 2), projected.originWeights)
        assertEquals(projected.weight, projected.originWeights.values.sum().toLong())
    }

    @Test fun `CSV fixes node columns and merges true origins without mixing candidates`() {
        val caller = GraphNode(NodeId("method:sample/Client#run()V"), "line\n\"quoted, name", NodeKind.METHOD)
        val target = GraphNode(NodeId("method:sample/Port#send()V"), "send", NodeKind.METHOD)
        val graph = CodeGraph(listOf(caller, target), listOf(
            GraphEdge(caller.id, target.id, EdgeKind.OVERRIDE),
            GraphEdge(caller.id, target.id, EdgeKind.OVERRIDE, origin = EdgeOrigin.KOTLIN_METADATA),
            GraphEdge(caller.id, target.id, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)))
        val nodes = StringWriter(); val edges = StringWriter()
        GraphCsvRenderer.write(graph, nodes, edges)
        assertTrue(nodes.toString().startsWith("\"usr:ID\""))
        assertTrue(nodes.toString().contains("line\n\"\"quoted, name"))
        val rows = edges.toString().lineSequence().filter(String::isNotBlank).toList()
        assertEquals(3, rows.size)
        assertTrue(rows[0].contains(":START_ID"))
        assertTrue(rows.any { it.contains("dispatchCandidate") && it.contains("dispatchModel") })
        assertTrue(rows.any { it.contains("bytecode;kotlinMetadata") && it.contains("\"2\"") })
    }
}
