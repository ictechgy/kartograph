package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** test 전용 정점에서만 닿는 production 정점을 고르고, production끼리의 도달은 seed로 삼지 않는다. */
class TestOnlyReachabilityTest {
    @Test
    fun `only declarations reached from test-only nodes are test reachable`() {
        val used = GraphNode(NodeId("method:app/A#used()V"), "used", NodeKind.METHOD)
        val unused = GraphNode(NodeId("method:app/A#unused()V"), "unused", NodeKind.METHOD)
        val helper = GraphNode(NodeId("method:app/A#helper()V"), "helper", NodeKind.METHOD)
        val test = GraphNode(NodeId("method:app/ATest#check()V"), "check", NodeKind.METHOD)
        val production = CodeGraph(listOf(used, unused, helper), listOf(GraphEdge(unused.id, helper.id, EdgeKind.CALL)))
        val combined = CodeGraph(listOf(used, unused, helper, test),
            listOf(GraphEdge(unused.id, helper.id, EdgeKind.CALL), GraphEdge(test.id, used.id, EdgeKind.CALL)))

        assertEquals(setOf(test.id, used.id), TestOnlyReachability.reachableFromTestSide(production, combined))
        assertEquals(emptySet(), TestOnlyReachability.reachableFromTestSide(combined, combined))
    }
}
