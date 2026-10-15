package dev.kartograph.analysis

import dev.kartograph.core.*
import dev.kartograph.analysis.SourceUseAnalysisFixture.a
import dev.kartograph.analysis.SourceUseAnalysisFixture.b
import dev.kartograph.analysis.SourceUseAnalysisFixture.c
import dev.kartograph.analysis.SourceUseAnalysisFixture.d
import dev.kartograph.analysis.SourceUseAnalysisFixture.x
import dev.kartograph.analysis.SourceUseAnalysisFixture.graph
import dev.kartograph.analysis.SourceUseAnalysisFixture.counts
import kotlin.test.*

class SourceUseCoverageTest {
    @Test fun `unavailable details are null rather than captured zeroes`() {
        val result = SourceUseCoverage.from(CodeGraph(emptyList(), emptyList()))
        assertEquals("unavailable", result.availability)
        assertEquals("unknown", result.coverage)
        assertNull(result.selectedRootCount)
        assertNull(result.uncoveredRootIds)
        assertNull(result.counts)
        assertNull(result.units)
        val captured = SourceUseCoverage.from(graph())
        assertEquals("captured", captured.availability)
        assertEquals("unknown", captured.coverage)
        assertEquals(1, captured.selectedRootCount)
        assertEquals(emptyList(), captured.uncoveredRootIds)
        assertEquals(0L, captured.counts!!.retainedUses)
        assertEquals(SourceUseProducerTotals(0, 0, 0, 0), captured.counts!!.producer)
        assertEquals(1, captured.units!!.size)
    }

    @Test fun `each producer importer view or callable root gap independently makes coverage partial`() {
        val imports = listOf(
            CompilerSourceUseImportCounts(0, 1, 0, 0, 0, 0),
            CompilerSourceUseImportCounts(0, 0, 1, 0, 0, 0),
            CompilerSourceUseImportCounts(0, 0, 0, 1, 0, 0),
            CompilerSourceUseImportCounts(0, 0, 0, 0, 1, 0),
            CompilerSourceUseImportCounts(0, 0, 0, 0, 0, 1),
        )
        val graphs = imports.map { graph(importCounts = it) } + listOf(
            graph(unmapped = 1), graph(ambiguous = 1), graph(importCounts = counts(1)), graph(uncovered = listOf(x)),
        )
        for (graph in graphs) {
            val result = SourceUseCoverage.from(graph)
            assertEquals("captured", result.availability)
            assertEquals("partial", result.coverage)
            assertEquals(0L, result.counts!!.retainedUses)
            assertEquals(0, SourceUseQuery.inspect(graph, c).page!!.observedUses)
        }
    }

    @Test fun `historical roots keep capture ordinal and gaps after all owners disappear`() {
        val graph = graph(listOf(a to c), uncovered = listOf(x, d), emptyRoot = true)
        val expected = graph.compilerSourceUseEvidence!!.uncoveredRoots.map { it.id }
        assertEquals(2, expected.size)
        assertEquals(4, SourceUseCoverage.from(graph).selectedRootCount)
        val narrowed = CodeGraph(emptyList(), emptyList()).preserveCompilerSourceUsesFrom(graph)
        val coverage = SourceUseCoverage.from(narrowed)
        assertEquals(expected, coverage.uncoveredRootIds)
        assertEquals(4, coverage.selectedRootCount)
        assertEquals(1L, coverage.counts!!.importCounts.admitted)
        assertEquals(1L, coverage.counts!!.viewOmitted)
        assertEquals(0L, coverage.counts!!.retainedUses)
        val widened = CodeGraph(graph.nodes.values, emptyList()).preserveCompilerSourceUsesFrom(narrowed)
        assertEquals(coverage, SourceUseCoverage.from(widened))
        assertEquals(0, SourceUseQuery.inspect(widened, c).page!!.observedUses)
    }

    @Test fun `non callable unclaimed root remains selected without inventing an uncovered callable gap`() {
        val result = SourceUseCoverage.from(graph(emptyRoot = true))
        assertEquals(2, result.selectedRootCount)
        assertTrue(result.uncoveredRootIds!!.isEmpty())
        assertEquals("unknown", result.coverage)
    }

    @Test fun `coverage stays global across subjects pages claims and modules`() {
        val graph = graph(listOf(a to c, b to c), unmapped = 4, extraClaim = true, uncovered = listOf(x))
        val coverage = SourceUseCoverage.from(graph)
        assertEquals(1, coverage.units!!.size)
        assertEquals(SourceUseProducerTotals(6, 2, 4, 0), coverage.counts!!.producer)
        for (subject in listOf(a, c, d)) for (offset in listOf(0, 1, 200_000)) {
            assertEquals(coverage, SourceUseQuery.inspect(graph, subject, SourceUsePageRequest(1, offset)).coverage)
        }
        val binding = SourceUseQuery.inspect(graph, c).page!!.bindings.single()
        val unit = graph.compilerSourceUseEvidence!!.units.single()
        assertEquals(2, binding.claimCount)
        assertEquals(unit.receipt, binding.receipt)
        assertEquals(unit.collectorArtifactFingerprint, binding.collectorArtifactFingerprint)
        assertEquals(unit.inputToken, binding.inputToken)
    }

    @Test fun `compatible repeated units keep distinct row identities unit summaries and binding order`() {
        val original = graph(listOf(a to c))
        val evidence = original.compilerSourceUseEvidence!!
        val unit = evidence.units.single()
        val second = CompilerSourceUseUnit(unit.receipt.copy(path = "other.tsv"), unit.collector, unit.compilerVersion,
            unit.collectorArtifactFingerprint, unit.inputToken, unit.claims, unit.producer, unit.importCounts, 0)
        val secondUse = evidence.uses.single().copy(unitId = second.id)
        val graph = CodeGraph(original.nodes.values, original.edges).withCompilerSourceUses(
            CompilerSourceUseEvidence(evidence.roots, listOf(second, unit), listOf(secondUse) + evidence.uses))
        val result = SourceUseQuery.inspect(graph, c)
        assertEquals(2, result.page!!.observedUses)
        assertEquals(1, result.page!!.observedDistinctCallers)
        assertEquals(2, result.page!!.observations.map { it.use.id }.toSet().size)
        val ids = listOf(unit.id, second.id).sorted()
        assertEquals(ids, result.coverage.units!!.map { it.id })
        assertEquals(ids, result.page!!.bindings.map { it.id })
        assertEquals(ids, result.page!!.observations.map { it.use.unitId })
        assertEquals(2L, result.coverage.counts!!.producer.observed)
        for (offset in 0..1) {
            val page = SourceUseQuery.inspect(graph, c, SourceUsePageRequest(1, offset)).page!!
            assertEquals(listOf(page.observations.single().use.unitId), page.bindings.map { it.id })
        }
    }

    @Test fun `global counts exceed Int safely and checked aggregation rejects Long overflow`() {
        val graph = graph()
        val evidence = graph.compilerSourceUseEvidence!!
        val unit = evidence.units.single()
        val dispositions = listOf(
            CompilerSourceUseImportCounts(Int.MAX_VALUE, 0, 0, 0, 0, 0),
            CompilerSourceUseImportCounts(0, Int.MAX_VALUE, 0, 0, 0, 0),
            CompilerSourceUseImportCounts(0, 0, Int.MAX_VALUE, 0, 0, 0),
            CompilerSourceUseImportCounts(0, 0, 0, Int.MAX_VALUE, 0, 0),
            CompilerSourceUseImportCounts(0, 0, 0, 0, Int.MAX_VALUE, 0),
            CompilerSourceUseImportCounts(0, 0, 0, 0, 0, Int.MAX_VALUE),
        )
        val units = (0 until 16).map { index ->
            val imported = if (index < 12) dispositions[index / 2] else counts(0)
            val producer = when {
                index < 12 -> CompilerSourceUseProducerCounts(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0)
                index < 14 -> CompilerSourceUseProducerCounts(Int.MAX_VALUE, 0, Int.MAX_VALUE, 0)
                else -> CompilerSourceUseProducerCounts(Int.MAX_VALUE, 0, 0, Int.MAX_VALUE)
            }
            CompilerSourceUseUnit(unit.receipt.copy(path = "large-$index.tsv"), unit.collector, unit.compilerVersion,
                unit.collectorArtifactFingerprint, unit.inputToken, unit.claims, producer, imported, imported.admitted)
        }
        val captured = CodeGraph(graph.nodes.values, emptyList()).withCompilerSourceUses(
            CompilerSourceUseEvidence(evidence.roots, units, emptyList()))
        val coverage = SourceUseCoverage.from(captured)
        val twice = 4_294_967_294L
        assertEquals(SourceUseProducerTotals(twice * 8, twice * 6, twice, twice), coverage.counts!!.producer)
        assertEquals(SourceUseImportTotals(twice, twice, twice, twice, twice, twice), coverage.counts!!.importCounts)
        assertEquals(twice, coverage.counts!!.viewOmitted)
        assertEquals(0L, coverage.counts!!.retainedUses)
        assertFailsWith<ArithmeticException> { checkedSourceUseSum(listOf(Long.MAX_VALUE, 1L)) { it } }
    }

    @Test fun `derived collections copy caller lists and reject mutation`() {
        val result = SourceUseQuery.inspect(graph(listOf(a to c), uncovered = listOf(x)), c)
        val originalPage = result.page!!
        val observations = originalPage.observations.toMutableList()
        val bindings = originalPage.bindings.toMutableList()
        val page = SourceUsePage(1, 1, 1, 0, 100, 0, 0, bindings, observations)
        val directions = mutableListOf("incoming")
        val row = observations.single().let { SourceUseObservation(it.use, it.caller, it.target, it.matchingBytecodeCall, directions) }
        val units = result.coverage.units!!.toMutableList()
        val uncovered = result.coverage.uncoveredRootIds!!.toMutableList()
        val coverage = SourceUseCoverage("captured", "partial", 2, uncovered, result.coverage.counts, units)
        observations.clear(); bindings.clear(); directions.clear(); units.clear(); uncovered.clear()
        assertEquals(1, page.observations.size)
        assertEquals(1, page.bindings.size)
        assertEquals(listOf("incoming"), row.directions)
        assertEquals(1, coverage.units!!.size)
        assertEquals(1, coverage.uncoveredRootIds!!.size)
        for (list in listOf(page.observations, page.bindings, row.directions, coverage.units, coverage.uncoveredRootIds)) {
            assertFailsWith<UnsupportedOperationException> { (list as MutableList<*>).clear() }
        }
        assertEquals(result, SourceUseQuery.inspect(graph(listOf(a to c), uncovered = listOf(x)), c))
        assertEquals(result.hashCode(), SourceUseQuery.inspect(graph(listOf(a to c), uncovered = listOf(x)), c).hashCode())
    }
}
