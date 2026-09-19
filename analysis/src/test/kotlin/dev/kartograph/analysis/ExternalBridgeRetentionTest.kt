package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.ExternalBridgeCaller
import dev.kartograph.core.ExternalBridgeEvidence
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalBridgeRetentionTest {
    @Test
    fun `nested owners preserve each caller while siblings and unrelated roots remain distinct`() {
        val outer = GraphNode(NodeId("class:Outer"), "Outer", NodeKind.CLASS)
        val inner = GraphNode(NodeId("class:Inner"), "Inner", NodeKind.CLASS)
        val method = GraphNode(NodeId("method:Inner#call()V"), "call", NodeKind.METHOD)
        val sibling = GraphNode(NodeId("method:Inner#unused()V"), "unused", NodeKind.METHOD)
        val graph = CodeGraph(listOf(outer, inner, method, sibling), listOf(
            GraphEdge(outer.id, inner.id, EdgeKind.MEMBER), GraphEdge(inner.id, method.id, EdgeKind.MEMBER),
            GraphEdge(inner.id, sibling.id, EdgeKind.MEMBER),
        ))
        val inputs = listOf("first", "second").map { channel -> RetentionEvidence(method.id,
            RetentionReason.EXTERNAL_BRIDGE, null,
            ExternalBridgeEvidence(channel, "call", ExternalBridgeCaller("js", "src/api.ts", 3))) }
        val result = DefaultRetention.find(graph, inputs, emptyList())
            .filter { it.reason == RetentionReason.EXTERNAL_BRIDGE }
        assertEquals(setOf(outer.id, inner.id, method.id), result.map { it.nodeId }.toSet())
        assertEquals(6, result.size)
        assertEquals(setOf("first", "second"), result.filter { it.nodeId == outer.id }.map { it.externalBridge?.channel }.toSet())
        assertEquals(true, result.all { it.externalBridge?.caller?.path == "src/api.ts" })
    }
}
