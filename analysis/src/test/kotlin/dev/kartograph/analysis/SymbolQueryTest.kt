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
import kotlin.test.assertTrue

class SymbolQueryTest {
    @Test
    fun `source style discovery returns every overload without resolving the query`() {
        val primitive = GraphNode(NodeId("method:com/acme/Writer#value(D)V"), "value", NodeKind.METHOD,
            jvmSignature = "com/acme/Writer#value(D)V", location = SourceLocation("src/Writer.java", 10))
        val objectValue = GraphNode(NodeId("method:com/acme/Writer#value(Ljava/lang/Object;)V"), "value", NodeKind.METHOD,
            jvmSignature = "com/acme/Writer#value(Ljava/lang/Object;)V", location = SourceLocation("src/Writer.java", 11))
        val collision = GraphNode(NodeId("method:other/Writer#value(D)V"), "value", NodeKind.METHOD,
            jvmSignature = "other/Writer#value(D)V")
        val graph = CodeGraph(listOf(primitive, objectValue, collision), emptyList())

        val page = SymbolDiscovery.suggest(listOf(graph), "com.acme.Writer.value(double)", 10)

        assertEquals(2, page.total)
        assertEquals(listOf(primitive.id.value, objectValue.id.value), page.candidates.map { it.usr })
        assertTrue(page.candidates.all { it.qualifiedName == "com.acme.Writer.value" })
        assertEquals(SourceLocation("src/Writer.java", 10), page.candidates.first().location)
        assertEquals("notFound", SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()),
            "com.acme.Writer.value(double)", emptyList()).status)
    }

    @Test
    fun `discovery is case sensitive owner qualified bounded and deterministic`() {
        val nodes = listOf("a/Writer", "b/Writer", "c/writer").map { owner ->
            GraphNode(NodeId("method:$owner#value()V"), "value", NodeKind.METHOD, jvmSignature = "$owner#value()V")
        }
        val graph = CodeGraph(nodes.reversed(), emptyList())

        val page = SymbolDiscovery.suggest(listOf(graph), "Writer.value()", 1)

        assertEquals(2, page.total)
        assertEquals(1, page.returned)
        assertTrue(page.truncated)
        assertEquals("method:a/Writer#value()V", page.candidates.single().usr)
        assertEquals(0, SymbolDiscovery.suggest(listOf(graph), "WRITER.value()", 10).total)
        assertEquals(0, SymbolDiscovery.suggest(listOf(graph), "value()", 10).total)
    }

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
