package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReachabilityAnalyzerTest {
    @Test
    fun `follows only usage edges from retained roots and records a path`() {
        val graph = CodeGraph(
            nodes = listOf(node("root"), node("used"), node("member"), node("dead")),
            edges = listOf(
                edge("root", "used", EdgeKind.CALL),
                edge("root", "member", EdgeKind.MEMBER),
            ),
        )
        val root = evidence("root", RetentionReason.MANIFEST_COMPONENT)

        val result = ReachabilityAnalyzer.analyze(graph, listOf(root))

        assertEquals(setOf(NodeId("root"), NodeId("used")), result.reachableNodeIds)
        assertEquals(listOf(NodeId("dead"), NodeId("member")), result.unreachableNodeIds)
        assertEquals(listOf(NodeId("root"), NodeId("used")), result.pathFromRootTo(NodeId("used")))
        assertNull(result.pathFromRootTo(NodeId("dead")))
    }

    @Test
    fun `keeps all evidence for matching roots and reports unmatched evidence`() {
        val graph = CodeGraph(nodes = listOf(node("root")), edges = emptyList())
        val manifest = evidence("root", RetentionReason.MANIFEST_COMPONENT)
        val xml = evidence("root", RetentionReason.XML_LAYOUT)
        val missing = evidence("missing", RetentionReason.KEEP_ANNOTATION)

        val result = ReachabilityAnalyzer.analyze(graph, listOf(xml, missing, manifest))

        assertEquals(listOf(manifest, xml), result.retentionEvidenceFor(NodeId("root")))
        assertEquals(listOf(missing), result.unmatchedEvidence)
    }

    @Test
    fun `graph without retention evidence is entirely unreachable`() {
        val graph = CodeGraph(nodes = listOf(node("a"), node("b")), edges = emptyList())

        val result = ReachabilityAnalyzer.analyze(graph, emptyList())

        assertEquals(emptySet(), result.reachableNodeIds)
        assertEquals(listOf(NodeId("a"), NodeId("b")), result.unreachableNodeIds)
    }

    private fun node(id: String): GraphNode = GraphNode(NodeId(id), id, NodeKind.CLASS)

    private fun edge(source: String, target: String, kind: EdgeKind): GraphEdge =
        GraphEdge(NodeId(source), NodeId(target), kind)

    private fun evidence(id: String, reason: RetentionReason): RetentionEvidence =
        RetentionEvidence(NodeId(id), reason, SourceLocation("fixture.xml", line = 1))
}
