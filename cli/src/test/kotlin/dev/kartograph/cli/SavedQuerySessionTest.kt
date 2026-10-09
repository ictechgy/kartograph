package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.QuerySnapshot
import kotlin.test.*

class SavedQuerySessionTest {
    private val a = NodeId("class:alpha/Node")
    private val b = NodeId("class:beta/Node")
    private val graph = CodeGraph(listOf(GraphNode(a, "Node", NodeKind.CLASS), GraphNode(b, "Node", NodeKind.CLASS)),
        listOf(GraphEdge(a, b, EdgeKind.REFERENCE)))

    @Test fun `prepared queries preserve complete answers for found ambiguous missing and bounded selectors`() {
        val snapshot = QuerySnapshot(graph, listOf(RetentionEvidence(a, RetentionReason.KEEP_RULE, null)), listOf("known limit"), setOf(b))
        val session = SavedSnapshotOperations.QuerySession(snapshot)
        repeat(3) {
            listOf(a.value, b.value, "Node", "Missing").forEach { requested ->
                listOf(1, 3).forEach { depth ->
                    listOf(1, 5).forEach { limit ->
                        assertEquals(SavedSnapshotOperations.query(snapshot, requested, depth, limit), session.query(requested, depth, limit))
                    }
                }
            }
        }
    }

    @Test fun `separate immutable snapshot sessions do not share retention or suppression`() {
        val retained = QuerySnapshot(graph, listOf(RetentionEvidence(a, RetentionReason.KEEP_RULE, null)), emptyList(), setOf(b))
        val empty = QuerySnapshot(graph, emptyList(), emptyList())
        val first = SavedSnapshotOperations.QuerySession(retained)
        val second = SavedSnapshotOperations.QuerySession(empty)
        assertNotEquals(first.query(b.value, 1, 5), second.query(b.value, 1, 5))
        repeat(5) {
            assertEquals(SavedSnapshotOperations.query(retained, b.value, 1, 5), first.query(b.value, 1, 5))
            assertEquals(SavedSnapshotOperations.query(empty, b.value, 1, 5), second.query(b.value, 1, 5))
        }
    }
}
