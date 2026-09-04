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
import kotlin.test.assertNull

class SymbolQueryTest {
    @Test
    fun `found query uses sibling document fields and usage edges`() {
        val root = NodeId("class:app/Root")
        val subject = NodeId("class:app/Subject")
        val member = NodeId("method:app/Subject#work()V")
        val graph = CodeGraph(
            listOf(
                GraphNode(root, "Root", NodeKind.CLASS, moduleName = "app"),
                GraphNode(subject, "Subject", NodeKind.CLASS, moduleName = "app"),
                GraphNode(member, "work", NodeKind.METHOD, moduleName = "app"),
            ),
            listOf(
                GraphEdge(root, subject, EdgeKind.CALL),
                GraphEdge(subject, member, EdgeKind.MEMBER),
            ),
        )
        val reachability = ReachabilityAnalyzer.analyze(
            graph,
            listOf(RetentionEvidence(root, RetentionReason.MANIFEST_COMPONENT, null)),
        )

        val document = SymbolQuery.query(graph, reachability, "Subject", limitations = listOf("jni-methods: 1"))

        assertEquals("found", document.status)
        assertEquals("Subject", document.requested)
        assertEquals("symbol", document.level)
        assertEquals(listOf("jni-methods: 1"), document.limitations)
        assertEquals("reachable", document.result?.reachability?.state)
        assertEquals(listOf("app.Root"), document.result?.usedBy?.map { it.qualifiedName })
        assertEquals(listOf("app.Subject.work"), document.result?.members?.map { it.qualifiedName })
        assertEquals(emptyList(), document.result?.dependsOn)
    }

    @Test
    fun `not found query still includes measured limitations`() {
        val graph = CodeGraph(emptyList(), emptyList())
        val reachability = ReachabilityAnalyzer.analyze(graph, emptyList())

        val document = SymbolQuery.query(
            graph,
            reachability,
            "Missing",
            limitations = listOf("reflection-strings: 2 Class.forName call(s) use runtime names"),
        )

        assertEquals("notFound", document.status)
        assertEquals(listOf("reflection-strings: 2 Class.forName call(s) use runtime names"), document.limitations)
        assertNull(document.result)
        assertNull(document.candidates)
    }

    @Test
    fun `reachable declarations are not marked as suppressed by an old baseline`() {
        val root = NodeId("class:app/Root")
        val subject = NodeId("class:app/Subject")
        val graph = CodeGraph(
            listOf(
                GraphNode(root, "Root", NodeKind.CLASS),
                GraphNode(subject, "Subject", NodeKind.CLASS),
            ),
            listOf(GraphEdge(root, subject, EdgeKind.REFERENCE)),
        )
        val reachability = ReachabilityAnalyzer.analyze(
            graph,
            listOf(RetentionEvidence(root, RetentionReason.MANIFEST_COMPONENT, null)),
        )

        val document = SymbolQuery.query(
            graph,
            reachability,
            "Subject",
            limitations = emptyList(),
            suppressedByBaseline = setOf(subject),
        )

        assertEquals(false, document.result?.reachability?.suppressedByBaseline)
    }

    @Test
    fun `unreachable declarations preserve baseline decisions`() {
        val subject = NodeId("class:app/Subject")
        val graph = CodeGraph(listOf(GraphNode(subject, "Subject", NodeKind.CLASS)), emptyList())

        val document = SymbolQuery.query(
            graph,
            ReachabilityAnalyzer.analyze(graph, emptyList()),
            "Subject",
            limitations = emptyList(),
            suppressedByBaseline = setOf(subject),
        )

        assertEquals(true, document.result?.reachability?.suppressedByBaseline)
    }

    @Test
    fun `member retention uses the sibling retainedByMember state`() {
        val subject = NodeId("class:app/Subject")
        val graph = CodeGraph(listOf(GraphNode(subject, "Subject", NodeKind.CLASS)), emptyList())
        val reachability = ReachabilityAnalyzer.analyze(
            graph,
            listOf(RetentionEvidence(subject, RetentionReason.KEEP_ANNOTATED_MEMBER, null)),
        )

        val document = SymbolQuery.query(graph, reachability, "Subject", limitations = emptyList())

        assertEquals("retainedByMember", document.result?.reachability?.state)
    }
}
