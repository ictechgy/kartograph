package dev.kartograph.export

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** language-traversal 문서 렌더링과 snapshot의 어휘적 소속 필드 호환성을 확인한다. */
class LanguageTraversalCodecTest {
    private val metadata = LanguageTraversalMetadata("2026-09-27T00:00:00.000Z", "/work/app", graphRevision = "ab")

    @Test
    fun `more than 64 reaching roots are capped with rootsTruncated`() {
        val sink = GraphNode(NodeId("method:p/Sink#run()V"), "run", NodeKind.METHOD)
        val sources = (0 until 70).map { GraphNode(NodeId("method:p/S$it#run()V"), "run", NodeKind.METHOD) }
        val graph = CodeGraph(sources + sink, sources.map { GraphEdge(it.id, sink.id, EdgeKind.CALL) })
        val result = LanguageTraversal.traverse(graph, sources.map { it.id.value }, TraversalDirection.DEPENDENCIES)
        val document = McpJsonCodec.parse(LanguageTraversalCodec.render(result, metadata)) as Map<*, *>
        assertEquals(true, document["rootsTruncated"])
        val row = (document["reached"] as List<*>).single() as Map<*, *>
        assertEquals((0L until 64L).toList(), row["roots"])
        assertEquals(false, document["truncated"])
        assertTrue((document["limitations"] as List<*>).any { (it as String).startsWith("roots-per-reached:") })
    }

    @Test
    fun `symbols omit unknown lines and bare file names`() {
        val located = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD, location = SourceLocation("src/p/A.kt", 3, 7))
        val bare = GraphNode(NodeId("method:p/B#run()V"), "run", NodeKind.METHOD, location = SourceLocation("B.kt"))
        val graph = CodeGraph(listOf(located, bare), listOf(GraphEdge(bare.id, located.id, EdgeKind.CALL)))
        val text = LanguageTraversalCodec.render(LanguageTraversal.traverse(graph, listOf(located.id.value), TraversalDirection.DEPENDENTS), metadata)
        assertTrue(text.contains("\"location\": {\"column\": 7, \"line\": 3, \"path\": \"src/p/A.kt\"}"))
        assertFalse(text.contains("B.kt"))
        assertTrue(text.contains("\"dispatch\": \"candidates\""))
        assertFalse(text.contains("unresolvedCalls"))
    }

    @Test
    fun `root lists come from string arrays or bridge facts`() {
        assertEquals(listOf("a", "b"), LanguageTraversalCodec.parseRoots("""["a", "b", "a"]"""))
        assertEquals(listOf("u1"), LanguageTraversalCodec.parseRoots(
            """{"format": "bridge-facts", "facts": [{"symbol": {"usr": "u1", "qualifiedName": "q"}}, {"symbol": {"qualifiedName": "q"}}]}"""))
    }

    @Test
    fun `snapshots round trip enclosures and mark legacy documents`() {
        val local = GraphNode(NodeId("class:p/A\$run\$1"), "A\$run\$1", NodeKind.CLASS)
        val owner = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD)
        val snapshot = QuerySnapshot(CodeGraph(listOf(local, owner), emptyList(), enclosures = listOf(LexicalEnclosure(local.id, owner.id))),
            emptyList(), emptyList())
        listOf(false, true).forEach { compact ->
            val parsed = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact))
            assertEquals(listOf(LexicalEnclosure(local.id, owner.id)), parsed.graph.enclosures)
            assertTrue(parsed.enclosuresCaptured)
        }
        val legacy = QuerySnapshotCodec.render(snapshot).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), "")
        val parsed = QuerySnapshotCodec.parse(legacy)
        assertFalse(parsed.enclosuresCaptured)
        assertTrue(parsed.graph.enclosures.isEmpty())
    }

    /** graphRevision 대조용 작은 그래프다. B.run이 A.run을 부르고 람다 class L이 A.run 안에 있다. */
    private fun revisionGraph(
        location: SourceLocation? = null, origin: EdgeOrigin = EdgeOrigin.BYTECODE, weight: Int = 1,
        enclosing: String = "method:p/A#run()V", externalCalls: List<ExternalCall> = emptyList(),
    ): CodeGraph {
        val a = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD, location = location)
        val b = GraphNode(NodeId("method:p/B#run()V"), "run", NodeKind.METHOD)
        val local = GraphNode(NodeId("class:p/A\$run\$1"), "A\$run\$1", NodeKind.CLASS)
        return CodeGraph(listOf(a, b, local), listOf(GraphEdge(b.id, a.id, EdgeKind.CALL, weight, origin)), externalCalls,
            enclosures = listOf(LexicalEnclosure(local.id, NodeId(enclosing))))
    }

    @Test
    fun `graph revision hashes graph content but not locations or weights`() {
        val base = LanguageTraversalCodec.graphRevision(revisionGraph(), enclosuresCaptured = true)
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(base), base)
        assertEquals(base, LanguageTraversalCodec.graphRevision(revisionGraph(location = SourceLocation("src/p/A.kt", 9, 2)), true))
        assertEquals(base, LanguageTraversalCodec.graphRevision(revisionGraph(weight = 4), true))
        val unresolved = ExternalCall(NodeId("method:p/A#run()V"), "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE,
            resolution = CallResolution.UNRESOLVED)
        listOf(
            LanguageTraversalCodec.graphRevision(revisionGraph(origin = EdgeOrigin.DISPATCH_MODEL), true),
            LanguageTraversalCodec.graphRevision(revisionGraph(enclosing = "method:p/B#run()V"), true),
            LanguageTraversalCodec.graphRevision(revisionGraph(), enclosuresCaptured = false),
            LanguageTraversalCodec.graphRevision(revisionGraph(externalCalls = listOf(unresolved)), true),
        ).forEach { assertNotEquals(base, it) }
    }

    @Test
    fun `graph revision does not depend on the order facts were captured in`() {
        val nodes = (0 until 6).map { GraphNode(NodeId("method:p/N$it#run()V"), "run", NodeKind.METHOD) }
        val locals = (0 until 2).map { GraphNode(NodeId("class:p/N$it\$1"), "N$it\$1", NodeKind.CLASS) }
        val edges = nodes.zipWithNext { a, b -> GraphEdge(a.id, b.id, EdgeKind.CALL) } + GraphEdge(nodes[5].id, nodes[0].id, EdgeKind.REFERENCE)
        val enclosures = locals.mapIndexed { index, local -> LexicalEnclosure(local.id, nodes[index].id) }
        val forward = CodeGraph(nodes + locals, edges, enclosures = enclosures)
        val shuffled = CodeGraph((nodes + locals).reversed(), edges.reversed(), enclosures = enclosures.reversed())
        assertEquals(LanguageTraversalCodec.graphRevision(forward, true), LanguageTraversalCodec.graphRevision(shuffled, true))
    }

    @Test
    fun `exchange text rejects the isthmus control range and lone surrogates`() {
        assertTrue(LanguageTraversalCodec.isExchangeText("method:p/A#run()V"))
        assertTrue(LanguageTraversalCodec.isExchangeText("v1.2-\uD83D\uDE00"))
        listOf("", "  ", "a\u0000", "a\u001f", "a\u007f", "a\u0080", "a\u0085", "a\u009f", "a\u2028", "a\u2029", "a\uD800", "\uDC00a")
            .forEach { assertFalse(LanguageTraversalCodec.isExchangeText(it), it) }
    }
}
