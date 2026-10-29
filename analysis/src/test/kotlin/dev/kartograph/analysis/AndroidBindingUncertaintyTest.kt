package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class AndroidBindingUncertaintyTest {
    @Test
    fun `unresolved callback follows bytecode calls and cycles but never owner members or modeled edges`() {
        val graph = fixture()
        val before = ReachabilityAnalyzer.analyze(graph, emptyList())
        val review = analyze(graph)
        assertFalse(review.truncated)
        for (id in listOf("adapter", "helper", "tail", "helperOwner")) assertTrue(review.requiresReview(NodeId(id)))
        for (id in listOf("sibling", "unrelated", "dispatch")) assertFalse(review.requiresReview(NodeId(id)))
        val proof = assertNotNull(review.uncertainty.proofFor(NodeId("tail")))
        assertEquals(NodeId("adapter"), proof.adapter)
        assertEquals(listOf("adapter" to "helper", "helper" to "tail"), proof.calls.map { it.source.value to it.target.value })
        assertTrue(proof.calls.all { it.origin == EdgeOrigin.BYTECODE && it.kind == EdgeKind.CALL })
        val owner = assertNotNull(review.uncertainty.proofFor(NodeId("helperOwner")))
        assertEquals(NodeId("helper"), owner.ownerOf)
        assertEquals(listOf("adapter" to "helper"), owner.calls.map { it.source.value to it.target.value })
        val after = ReachabilityAnalyzer.analyze(graph, emptyList())
        assertEquals(before.reachableNodeIds, after.reachableNodeIds)
        assertTrue(after.reachableNodeIds.isEmpty())
        assertNull(after.pathFromRootTo(NodeId("helper")))
        assertEquals(review.uncertainty, analyze(CodeGraph(graph.nodes.values.reversed(), graph.edges.reversed())).uncertainty)
        assertTrue(review.diagnostics.any { "modeled dispatch, field dependencies and unobserved runtime paths" in it })
    }

    @Test
    fun `node and edge exhaustion lower confidence without inventing paths`() {
        for ((nodes, edges) in listOf(2 to 100, 100 to 1)) {
            val review = analyze(fixture(), nodes, edges)
            assertTrue(review.truncated)
            assertTrue(review.uncertainty.truncated)
            assertTrue(review.requiresReview(NodeId("unrelated")))
            assertNull(review.uncertainty.proofFor(NodeId("unrelated")))
            assertTrue(review.uncertainty.steps.size + review.uncertainty.ownerMembers.size <= nodes)
            assertTrue(review.uncertainty.examinedEdges <= edges)
            assertTrue(review.diagnostics.any { "data-binding-call-uncertainty-truncated:" in it && "not confirmed call candidates" in it })
        }
    }

    @Test
    fun `bounded path returns an explicitly truncated sink suffix`() {
        val proof = assertNotNull(analyze(fixture()).uncertainty.proofFor(NodeId("tail"), maxCalls = 1))
        assertTrue(proof.truncated)
        assertEquals(NodeId("adapter"), proof.adapter)
        assertEquals(listOf("helper" to "tail"), proof.calls.map { it.source.value to it.target.value })
    }

    private fun fixture(): CodeGraph = CodeGraph(
        listOf("adapter", "helper", "tail", "helperOwner", "sibling", "unrelated", "dispatch").map {
            GraphNode(NodeId(it), it, if (it == "helperOwner") NodeKind.CLASS else NodeKind.METHOD,
                annotations = if (it == "adapter") setOf("androidx/databinding/BindingAdapter") else emptySet())
        },
        listOf(call("adapter", "helper"), call("helper", "tail"), call("tail", "helper"), call("sibling", "unrelated"),
            GraphEdge(NodeId("helperOwner"), NodeId("helper"), EdgeKind.MEMBER),
            GraphEdge(NodeId("helperOwner"), NodeId("sibling"), EdgeKind.MEMBER),
            call("helper", "dispatch").copy(origin = EdgeOrigin.DISPATCH_MODEL)),
    )

    private fun call(source: String, target: String) = GraphEdge(NodeId(source), NodeId(target), EdgeKind.CALL)
    private fun analyze(graph: CodeGraph, nodes: Int = 100, edges: Int = 100): AndroidBindingReviewResult =
        AndroidBindingReview.analyze(graph, ClassHierarchy.EMPTY,
            AndroidBindingObservations(emptyList(), emptyList(), emptyList()), ReachabilityAnalyzer.analyze(graph, emptyList()),
            uncertaintyNodeBudget = nodes, uncertaintyEdgeBudget = edges)
}
