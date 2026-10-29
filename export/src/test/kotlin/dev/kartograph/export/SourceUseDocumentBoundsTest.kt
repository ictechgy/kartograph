package dev.kartograph.export

import dev.kartograph.analysis.*
import dev.kartograph.core.CodeGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceUseDocumentBoundsTest {
    @Test
    fun `whole wrapper cap minus one and exact cap include final LF and cap plus one fails atomically`() {
        val emptyBytes = SourceUseDocumentRenderer.query(document("")).toByteArray(Charsets.UTF_8).size
        for (size in listOf(SourceUseDocumentRenderer.MAX_BYTES - 1, SourceUseDocumentRenderer.MAX_BYTES)) {
            val rendered = SourceUseDocumentRenderer.query(document("x".repeat(size - emptyBytes)))
            assertEquals(size, rendered.toByteArray(Charsets.UTF_8).size)
            assertTrue(rendered.endsWith("\n"))
        }
        val stdout = StringBuilder()
        val failure = assertFailsWith<SourceUseDocumentSizeException> {
            stdout.append(SourceUseDocumentRenderer.query(document("x".repeat(SourceUseDocumentRenderer.MAX_BYTES + 1 - emptyBytes))))
        }
        assertEquals("", stdout.toString())
        assertTrue(failure.message!!.contains("reduce the graph/source page"))
    }

    @Test
    fun `escape tokens preserve exact serializer bytes and each expansion is counted before final LF`() {
        val tokens = listOf(
            "\ue000" to "\"\\ue000\"", "\ud800\udc00" to "\"\\ud800\\udc00\"",
            "\n" to "\"\\n\"", "\\" to "\"\\\\\"", "\"" to "\"\\\"\"",
        )
        assertEquals(listOf(8, 14, 4, 4, 4), tokens.map { it.second.length })
        for ((source, token) in tokens) {
            assertEquals(token + "\n", sourceBoundedDocument(source, token.length + 1))
            assertFailsWith<SourceUseDocumentSizeException> { sourceBoundedDocument(source, token.length) }
            val baseline = SourceUseDocumentRenderer.query(document(source)).length
            val padding = SourceUseDocumentRenderer.MAX_BYTES - baseline
            val exact = SourceUseDocumentRenderer.query(document(source + "x".repeat(padding)))
            assertEquals(SourceUseDocumentRenderer.MAX_BYTES, exact.toByteArray(Charsets.UTF_8).size)
            assertTrue(exact.contains(token.substring(1, token.lastIndex)))
            val stdout = StringBuilder()
            assertFailsWith<SourceUseDocumentSizeException> {
                stdout.append(SourceUseDocumentRenderer.query(document(source + "x".repeat(padding + 1))))
            }
            assertEquals("", stdout.toString())
        }
    }

    @Test
    fun `small internal caps enforce byte punctuation and LF boundary for both wrappers`() {
        val query = document("quote=\" slash=\\ newline=\n private=\ue000 supplementary=\ud800\udc00")
        val queryText = SourceUseDocumentRenderer.query(query)
        assertEquals(queryText, SourceUseDocumentRenderer.query(query, null, queryText.length))
        assertFailsWith<SourceUseDocumentSizeException> { SourceUseDocumentRenderer.query(query, null, queryText.length - 1) }
        val graph = CodeGraph(emptyList(), emptyList())
        val snapshot = QuerySnapshot(graph, emptyList(), emptyList())
        val report = ChangeImpact.analyze(ImpactInput(graph))
        val inspection = SourceEditImpact.analyze(graph, emptySet())
        val impact = SourceEditImpactDocument(report, mapOf("current" to snapshot),
            mapOf("current" to SourceSnapshotInput(snapshot.toolVersion, null, null, "0".repeat(64))),
            mapOf("current" to SourceEditImpactRevision("noChanges", emptyList(), inspection.coverage, inspection)))
        val impactText = SourceUseDocumentRenderer.impact(impact)
        assertEquals(impactText, SourceUseDocumentRenderer.impact(impact, impactText.length))
        assertFailsWith<SourceUseDocumentSizeException> { SourceUseDocumentRenderer.impact(impact, impactText.length - 1) }
        assertFailsWith<IllegalArgumentException> { sourceBoundedDocument(null, SourceUseDocumentRenderer.MAX_BYTES + 1) }
    }

    @Test
    fun `budget stops before requesting a lazy item or object field beyond remaining space`() {
        var descriptors = 0
        val values = sourceJsonItems(0 until 200_000) { descriptors++; null }
        val stdout = StringBuilder()
        assertFailsWith<SourceUseDocumentSizeException> { stdout.append(sourceBoundedDocument(values, 6)) }
        assertEquals(1, descriptors)
        assertEquals("", stdout.toString())
        var tailFields = 0
        val objectValue = sourceJsonObject("a" to { "x".repeat(100) }, "b" to { tailFields++; "tail" })
        assertFailsWith<SourceUseDocumentSizeException> { sourceBoundedDocument(objectValue, 20) }
        assertEquals(0, tailFields)
    }

    @Test
    fun `ordinary candidate descriptors remain lazy under the unchanged serializer guard`() {
        var candidatesRead = 0
        val candidates = object : AbstractList<SymbolQueryCandidate>() {
            override val size: Int get() = 200_000
            override fun get(index: Int): SymbolQueryCandidate {
                candidatesRead++
                return SymbolQueryCandidate("x".repeat(100), "candidate-$index")
            }
        }
        val query = SymbolQueryDocument("ambiguous", "query", "symbol", emptyList(), candidates = candidates)
        val view = sourceOrdinaryQueryValue(query, null)
        assertEquals(0, candidatesRead)
        val stdout = StringBuilder()
        assertFailsWith<SourceUseDocumentSizeException> { stdout.append(sourceBoundedDocument(view, 100)) }
        assertEquals(1, candidatesRead)
        assertEquals("", stdout.toString())
    }

    @Test
    fun `ordinary affected descriptors and summary buckets are not expanded before budget traversal`() {
        var nodesRead = 0
        val affected = object : AbstractList<ImpactNode>() {
            override val size: Int get() = 200_000
            override fun get(index: Int): ImpactNode {
                nodesRead++
                return ImpactNode(dev.kartograph.core.GraphNode(dev.kartograph.core.NodeId("method:p/A#run()V"),
                    "x".repeat(100), dev.kartograph.core.NodeKind.METHOD), setOf(ImpactRevision.CURRENT))
            }
        }
        val report = ImpactReport(emptyList(), affected, affected.size, emptyList(), emptyList(), ImpactTruncation())
        val view = sourceOrdinaryImpactValue(report, emptyMap(), null)
        assertEquals(0, nodesRead)
        assertFailsWith<SourceUseDocumentSizeException> { sourceBoundedDocument(view, 100) }
        assertEquals(1, nodesRead)
    }

    @Test
    fun `new documents defensively freeze typed ordinary collections without altering defaults`() {
        val limitations = mutableListOf("original")
        val candidates = mutableListOf(SymbolQueryCandidate("name", null))
        val ordinary = SymbolQueryDocument("ambiguous", "name", "symbol", limitations, candidates = candidates)
        val graph = CodeGraph(emptyList(), emptyList())
        val wrapper = SourceUseQueryDocument(ordinary, input, SourceUseCoverage.from(graph), null)
        val expected = SourceUseDocumentRenderer.query(wrapper)
        limitations.add("later")
        candidates.clear()
        assertEquals(expected, SourceUseDocumentRenderer.query(wrapper))
        assertFailsWith<UnsupportedOperationException> { (wrapper.query.limitations as MutableList<String>).clear() }
        assertEquals(listOf("original", "later"), ordinary.limitations)

        val buckets = mutableListOf(ImpactBucket("original", 1))
        val unresolved = mutableListOf(ImpactUnresolved("missing", "notFound"))
        val report = ImpactReport(emptyList(), emptyList(), 0, unresolved, limitations, ImpactTruncation(),
            navigation = ImpactNavigation(observed = ImpactSummary(byModule = buckets)))
        val snapshot = QuerySnapshot(graph, emptyList(), emptyList())
        val snapshots = mutableMapOf("current" to snapshot)
        val inputs = mutableMapOf("current" to SourceSnapshotInput(snapshot.toolVersion, null, null, input.snapshotSha256))
        val revisions = mutableMapOf("current" to SourceEditImpactRevision("unresolved", emptyList(), SourceUseCoverage.from(graph), null))
        val impact = SourceEditImpactDocument(report, snapshots, inputs, revisions)
        val impactExpected = SourceUseDocumentRenderer.impact(impact)
        buckets.clear(); unresolved.clear(); limitations.clear(); snapshots.clear(); inputs.clear(); revisions.clear()
        assertEquals(impactExpected, SourceUseDocumentRenderer.impact(impact))
        assertFailsWith<UnsupportedOperationException> { (impact.graphImpact.navigation.observed.byModule as MutableList<ImpactBucket>).clear() }
        assertFailsWith<UnsupportedOperationException> { (impact.inputs as MutableMap<String, SourceSnapshotInput>).clear() }
    }

    private fun document(requested: String): SourceUseQueryDocument = SourceUseQueryDocument(
        SymbolQueryDocument("notFound", requested, "symbol", emptyList()), input,
        SourceUseCoverage.from(CodeGraph(emptyList(), emptyList())), null,
    )

    private companion object {
        val input = SourceSnapshotInput("fixture", null, null, "0".repeat(64))
    }
}
