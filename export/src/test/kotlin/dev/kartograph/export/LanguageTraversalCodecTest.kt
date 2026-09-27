package dev.kartograph.export

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
