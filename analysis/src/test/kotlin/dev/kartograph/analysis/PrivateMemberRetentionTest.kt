package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivateMemberRetentionTest {
    @Test
    fun `possible callbacks on newly reached owners retain helpers without rooting dead classes`() {
        fun node(id: String, kind: NodeKind, visibility: Visibility = Visibility.PUBLIC) =
            GraphNode(NodeId(id), id, kind, visibility = visibility)
        val first = node("First", NodeKind.CLASS)
        val second = node("Second", NodeKind.CLASS)
        val dead = node("Dead", NodeKind.CLASS)
        val firstCall = node("firstCall", NodeKind.METHOD)
        val secondCall = node("secondCall", NodeKind.METHOD)
        val helper = node("helper", NodeKind.METHOD, Visibility.PRIVATE)
        val unused = node("unused", NodeKind.METHOD, Visibility.PRIVATE)
        val graph = CodeGraph(listOf(first, second, dead, firstCall, secondCall, helper, unused), listOf(
            GraphEdge(first.id, firstCall.id, EdgeKind.MEMBER),
            GraphEdge(firstCall.id, second.id, EdgeKind.REFERENCE),
            GraphEdge(second.id, secondCall.id, EdgeKind.MEMBER),
            GraphEdge(second.id, helper.id, EdgeKind.MEMBER),
            GraphEdge(second.id, unused.id, EdgeKind.MEMBER),
            GraphEdge(secondCall.id, helper.id, EdgeKind.CALL),
        ))
        val roots = listOf(RetentionEvidence(first.id, RetentionReason.KEEP_RULE, null))
        val evidence = PrivateMemberRetention.expand(graph, roots)
        val reachable = ReachabilityAnalyzer.analyze(graph, evidence)
        assertEquals(setOf(first.id, second.id, firstCall.id, secondCall.id, helper.id), reachable.reachableNodeIds)
        assertEquals(listOf(firstCall.id, secondCall.id), evidence.filter {
            it.reason == RetentionReason.EXTERNAL_MEMBER_ENTRY
        }.map { it.nodeId })
        assertEquals(listOf(dead.id, unused.id), DeadFindings.collect(graph, reachable, true).map { it.nodeId })
    }
}
