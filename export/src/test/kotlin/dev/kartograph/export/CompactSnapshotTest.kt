package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class CompactSnapshotTest {
    @Test fun `compact snapshots keep all facts and materially reduce repeated edge identities`() {
        val nodes=(0..25).map { GraphNode(NodeId("class:long/package/with/repeated/names/Type$it"),"Type$it",NodeKind.CLASS) }
        val graph=CodeGraph(nodes,nodes.zipWithNext { a,b -> GraphEdge(a.id,b.id,EdgeKind.CALL,3,EdgeOrigin.RUNTIME_MODEL) })
        val original=QuerySnapshot(graph,listOf(RetentionEvidence(nodes.first().id,RetentionReason.KEEP_RULE,null)),listOf("measured: 1"),
            revision="a".repeat(40),scope="sample:debug")
        val plain=QuerySnapshotCodec.render(original)
        val compact=QuerySnapshotCodec.render(original,compact=true)
        assertTrue(compact.length < plain.length)
        val decoded=QuerySnapshotCodec.parse(compact)
        assertEquals(graph.nodes,decoded.graph.nodes)
        assertEquals(graph.edges,decoded.graph.edges)
        assertEquals(original.retention,decoded.retention)
        assertEquals(original.limitations,decoded.limitations)
        assertEquals(original.revision,decoded.revision)
        assertEquals(original.scope,decoded.scope)
        assertEquals(compact,QuerySnapshotCodec.render(decoded,compact=true))
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(compact.replace("[0, 1,", "[-1, 1,")) }
    }
}
