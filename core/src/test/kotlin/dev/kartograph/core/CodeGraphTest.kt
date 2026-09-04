package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeGraphTest {
    @Test
    fun `duplicate edges merge their weights and keep distinct kinds`() {
        val graph = CodeGraph(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(
                edge("a", "b", EdgeKind.CALL),
                edge("a", "b", EdgeKind.CALL, weight = 2),
                edge("a", "b", EdgeKind.REFERENCE),
            ),
        )

        assertEquals(2, graph.edgeCount)
        assertEquals(3, graph.edges.single { it.kind == EdgeKind.CALL }.weight)
    }

    @Test
    fun `edges without both endpoints are dropped`() {
        val graph = CodeGraph(
            nodes = listOf(node("a")),
            edges = listOf(
                edge("a", "missing", EdgeKind.CALL),
                edge("missing", "a", EdgeKind.CALL),
            ),
        )

        assertEquals(1, graph.nodeCount)
        assertEquals(0, graph.edgeCount)
    }

    @Test
    fun `adjacency is directional and usage traversal shares edge semantics`() {
        val graph = CodeGraph(
            nodes = listOf(node("c"), node("b"), node("a")),
            edges = listOf(
                edge("a", "b", EdgeKind.MEMBER),
                edge("a", "c", EdgeKind.CALL),
                edge("b", "c", EdgeKind.REFERENCE),
            ),
        )

        assertEquals(listOf(NodeId("b"), NodeId("c")), graph.successorsOf(NodeId("a")))
        assertEquals(listOf(NodeId("c")), graph.usageSuccessorsOf(NodeId("a")))
        assertEquals(listOf(NodeId("a"), NodeId("b")), graph.predecessorsOf(NodeId("c")))
        assertTrue(graph.successorsOf(NodeId("c")).isEmpty())
    }

    @Test
    fun `nodes and edges have deterministic order`() {
        val graph = CodeGraph(
            nodes = listOf(node("c"), node("a"), node("b")),
            edges = listOf(
                edge("b", "c", EdgeKind.CALL),
                edge("a", "c", EdgeKind.REFERENCE),
                edge("a", "b", EdgeKind.CALL),
            ),
        )

        assertEquals(listOf(NodeId("a"), NodeId("b"), NodeId("c")), graph.nodeIds)
        assertEquals(listOf("a:b:CALL", "a:c:REFERENCE", "b:c:CALL"), graph.edges.map(::edgeDescription))
    }

    @Test
    fun `first duplicate node wins and self loops are explicit`() {
        val first = node("a", name = "first")
        val duplicate = node("a", name = "duplicate")
        val graph = CodeGraph(
            nodes = listOf(first, duplicate),
            edges = listOf(edge("a", "a", EdgeKind.CALL)),
        )

        assertEquals(first, graph.node(NodeId("a")))
        assertTrue(graph.edges.single().isSelfLoop)
        assertFalse(edge("a", "b", EdgeKind.CALL).isSelfLoop)
    }

    private fun node(id: String, name: String = id): GraphNode =
        GraphNode(NodeId(id), name, NodeKind.CLASS)

    private fun edge(source: String, target: String, kind: EdgeKind, weight: Int = 1): GraphEdge =
        GraphEdge(NodeId(source), NodeId(target), kind, weight)

    private fun edgeDescription(edge: GraphEdge): String =
        "${edge.source.value}:${edge.target.value}:${edge.kind}"
}
