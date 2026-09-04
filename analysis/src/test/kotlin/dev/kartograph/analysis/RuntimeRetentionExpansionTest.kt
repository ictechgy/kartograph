package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeRetentionExpansionTest {
    @Test
    fun `runtime-owned classes retain direct member bodies as reachable entry paths`() {
        val owner = node("class:fixture/Screen", "Screen", NodeKind.CLASS)
        val callback = node("method:fixture/Screen#onCreate()V", "onCreate", NodeKind.METHOD)
        val graph = CodeGraph(
            listOf(owner, callback),
            listOf(GraphEdge(owner.id, callback.id, EdgeKind.MEMBER)),
        )
        val original = RetentionEvidence(owner.id, RetentionReason.MANIFEST_COMPONENT, null)

        val expanded = RuntimeRetentionExpansion.expand(graph, listOf(original))

        assertEquals(listOf(owner.id, callback.id), expanded.map { it.nodeId })
        assertEquals(setOf(RetentionReason.MANIFEST_COMPONENT), expanded.map { it.reason }.toSet())
    }

    @Test
    fun `returns deterministic evidence order without expanding non-owner reasons`() {
        val early = node("class:fixture/Early", "Early", NodeKind.CLASS)
        val late = node("class:fixture/Late", "Late", NodeKind.CLASS)
        val ignoredMember = node("method:fixture/Late#ignored()V", "ignored", NodeKind.METHOD)
        val graph = CodeGraph(
            listOf(early, late, ignoredMember),
            listOf(GraphEdge(late.id, ignoredMember.id, EdgeKind.MEMBER)),
        )
        val evidence = listOf(
            RetentionEvidence(late.id, RetentionReason.KEEP_RULE, null),
            RetentionEvidence(early.id, RetentionReason.GENERATED_CODE, null),
        )

        val expanded = RuntimeRetentionExpansion.expand(graph, evidence)

        assertEquals(listOf(early.id, late.id), expanded.map { it.nodeId })
    }

    private fun node(id: String, name: String, kind: NodeKind): GraphNode = GraphNode(NodeId(id), name, kind)
}
