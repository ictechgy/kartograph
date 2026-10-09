package dev.kartograph.export

import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.core.*
import kotlin.test.*

class SourceDeclarationEvidenceTest {
    private val id = NodeId("method:sample/Example#work()V")
    private val evidence = SourceDeclarationEvidence("src/Example.java", "a".repeat(64), 38, 3, 10)
    private fun graph(evidence: SourceDeclarationEvidence? = this.evidence) = CodeGraph(listOf(
        GraphNode(id, "work", NodeKind.METHOD, location = SourceLocation("Example.java", 4), sourceDeclaration = evidence),
    ), emptyList())

    @Test fun `plain and compact snapshots retain declaration coordinates independently of compiler lines`() {
        listOf(false, true).forEach { compact ->
            val content = QuerySnapshotCodec.render(QuerySnapshot(graph(), emptyList(), emptyList()), compact = compact)
            val restored = QuerySnapshotCodec.parse(content).graph
            assertEquals(evidence, restored.nodes.getValue(id).sourceDeclaration)
            assertEquals(4, restored.nodes.getValue(id).location?.line)
            assertEquals(content, QuerySnapshotCodec.render(QuerySnapshot(restored, emptyList(), emptyList()), compact = compact))
        }
    }

    @Test fun `graph and query expose hash and UTF16 coordinates separately from compiler location`() {
        val graph = graph()
        listOf(GraphJsonRenderer.render(graph, "test"),
            AgentDocumentRenderer.query(SymbolQuery.query(graph,
                dev.kartograph.analysis.ReachabilityAnalyzer.analyze(graph, emptyList()), id.value, emptyList()))).forEach { content ->
            assertContains(content, "\"sourceDeclaration\"")
            assertContains(content, "\"offsetUtf16\": 38")
            assertContains(content, "\"coordinateBasis\": \"rawDecodedUtf16\"")
            assertContains(content, "\"sourceSha256\": \"${evidence.sourceSha256}\"")
            assertContains(content, "\"line\": 4")
        }
    }

    @Test fun `legacy snapshots and graphs do not gain an empty declaration field`() {
        val graph = graph(null)
        val content = QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()))
        assertFalse("sourceDeclarations" in content)
        assertNull(QuerySnapshotCodec.parse(content).graph.nodes.getValue(id).sourceDeclaration)
        assertFalse("sourceDeclaration" in GraphJsonRenderer.render(graph, "test"))
    }

    @Test fun `Kotlin metadata source names survive plain compact snapshots and malformed facts fail closed`() {
        val node = graph(null).nodes.getValue(id).copy(kotlinSourceName = "sourceWork")
        listOf(false, true).forEach { compact ->
            val content = QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(node), emptyList()), emptyList(), emptyList()), compact)
            assertEquals("sourceWork", QuerySnapshotCodec.parse(content).graph.nodes.getValue(id).kotlinSourceName)
            assertFailsWith<IllegalArgumentException> {
                QuerySnapshotCodec.parse(content.replace("\"name\": \"sourceWork\"", "\"name\": \"\""))
            }
            val row = jsonValue(sortedMapOf("usr" to id.value, "name" to "sourceWork"))
            assertFailsWith<IllegalArgumentException> {
                QuerySnapshotCodec.parse(content.replace("\"kotlinSourceNames\": [", "\"kotlinSourceNames\": [$row, "))
            }
        }
    }

    @Test fun `snapshot rejects duplicate unknown node malformed coordinates and forged basis`() {
        val content = QuerySnapshotCodec.render(QuerySnapshot(graph(), emptyList(), emptyList()))
        val row = jsonValue(evidence.toJsonValue() + ("usr" to id.value))
        val variants = listOf(
            content.replace(id.value, "method:sample/Example#different()V").replace("\"sourceDeclarations\": [", "\"sourceDeclarations\": [${row}, "),
            content.replace("\"offsetUtf16\": 38", "\"offsetUtf16\": -1"),
            content.replace("\"rawDecodedUtf16\"", "\"guessed\""),
            content.replace("\"src/Example.java\"", "\"../Example.java\""),
            content.replace("\"sourceHeader\"", "\"compiler\""),
        )
        variants.forEach { assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(it) } }
        val duplicate = content.replace("\"sourceDeclarations\": [", "\"sourceDeclarations\": [${row}, ")
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(duplicate) }
    }

    @Test fun `snapshot rejects evidence on unsupported synthetic and mismatched source nodes`() {
        val node = graph().nodes.getValue(id)
        val unsupported = listOf(node.copy(synthesized = true), node.copy(location = null),
            node.copy(kind = NodeKind.FUNCTION), node.copy(jvmModifiers = setOf(JvmModifier.SYNTHETIC)),
            node.copy(location = SourceLocation("Different.java", 4)),
            node.copy(location = SourceLocation("other/Example.java", 4))) +
            listOf(NodeAttribute.EXTERNAL_STUB, NodeAttribute.FILE_FACADE, NodeAttribute.PROPERTY_ACCESSOR,
                NodeAttribute.EXTENSION_FUNCTION).map { node.copy(attributes = setOf(it)) }
        unsupported.forEach { bad ->
            val content = QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(bad), emptyList()), emptyList(), emptyList()))
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(content) }
        }
    }

    @Test fun `snapshot cannot attach Kotlin coordinates to renamed or metadata unknown methods`() {
        val evidence = this.evidence.copy(path = "src/Example.kt")
        listOf(null, "renamed").forEach { name ->
            val node = graph().nodes.getValue(id).copy(location = SourceLocation("Example.kt", 4),
                sourceDeclaration = evidence, kotlinSourceName = name)
            val content = QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(node), emptyList()), emptyList(), emptyList()))
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(content) }
        }
    }
}
