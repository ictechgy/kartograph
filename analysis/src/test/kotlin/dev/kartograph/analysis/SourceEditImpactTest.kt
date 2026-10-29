package dev.kartograph.analysis

import dev.kartograph.core.*
import dev.kartograph.analysis.SourceUseAnalysisFixture.a
import dev.kartograph.analysis.SourceUseAnalysisFixture.b
import dev.kartograph.analysis.SourceUseAnalysisFixture.c
import dev.kartograph.analysis.SourceUseAnalysisFixture.d
import dev.kartograph.analysis.SourceUseAnalysisFixture.x
import dev.kartograph.analysis.SourceUseAnalysisFixture.contract
import dev.kartograph.analysis.SourceUseAnalysisFixture.implementation
import dev.kartograph.analysis.SourceUseAnalysisFixture.graph
import dev.kartograph.analysis.SourceUseAnalysisFixture.id
import kotlin.test.*

class SourceEditImpactTest {
    @Test fun `multi source graph then source and source then graph never propagate`() {
        val cases = listOf(
            graph(listOf(a to b, b to c)) to listOf(b),
            graph(listOf(b to c), listOf(GraphEdge(a, b, EdgeKind.CALL))) to listOf(b),
            graph(listOf(a to b), listOf(GraphEdge(b, c, EdgeKind.CALL))) to emptyList(),
        )
        for ((graph, expected) in cases) {
            val report = SourceEditImpact.analyze(graph, setOf(c))
            assertEquals(expected, report.page!!.observations.map { it.caller.usr })
            assertTrue(report.page!!.observations.all { it.target.usr == c })
        }
    }

    @Test fun `changed callers and self selections are excluded while incoming direct callers remain`() {
        val graph = graph(listOf(a to b, b to c, c to c, d to c))
        assertEquals(listOf(b, d), SourceEditImpact.analyze(graph, setOf(c)).page!!.observations.map { it.caller.usr })
        assertEquals(listOf(a, d), SourceEditImpact.analyze(graph, setOf(b, c)).page!!.observations.map { it.caller.usr })
        assertEquals(listOf(a to b, d to c), SourceEditImpact.analyze(graph, setOf(b, c)).page!!.observations.map {
            it.use.source to it.use.target
        })
        assertTrue(SourceEditImpact.analyze(graph, setOf(a, b, c, d)).page!!.observations.isEmpty())
    }

    @Test fun `override subtype owner member and retention edges do not expand direct selection`() {
        for (kind in listOf(EdgeKind.OVERRIDE, EdgeKind.INHERITANCE, EdgeKind.MEMBER, EdgeKind.RETENTION)) {
            val graph = graph(listOf(a to implementation), listOf(GraphEdge(contract, implementation, kind)))
            assertEquals(0, SourceEditImpact.analyze(graph, setOf(contract)).page!!.observedUses)
            assertEquals(listOf(a), SourceEditImpact.analyze(graph, setOf(implementation)).page!!.observations.map { it.caller.usr })
        }
    }

    @Test fun `base and current use independent pages counts coverage and unit bindings`() {
        val base = graph(listOf(d to c, b to c, a to c), unmapped = 4, uncovered = listOf(x))
        val current = graph(listOf(x to c))
        val request = SourceUsePageRequest(1, 1)
        val before = SourceEditImpact.analyze(base, setOf(c), request)
        val after = SourceEditImpact.analyze(current, setOf(c), request)
        val bp = before.page!!
        assertEquals(3, bp.observedUses)
        assertEquals(3, bp.observedDistinctCallers)
        assertEquals(1, bp.returnedUses)
        assertEquals(1, bp.returnedDistinctCallers)
        assertEquals(1, bp.omittedBefore)
        assertEquals(1, bp.omittedAfter)
        assertTrue(bp.hasPrevious)
        assertTrue(bp.hasNext)
        assertEquals(listOf(b), bp.observations.map { it.caller.usr })
        assertEquals(1, bp.bindings.size)
        assertEquals("partial", before.coverage.coverage)
        assertEquals(SourceUseProducerTotals(7, 3, 4, 0), before.coverage.counts!!.producer)
        assertEquals(3L, before.coverage.counts!!.importCounts.admitted)
        assertEquals(0L, before.coverage.counts!!.viewOmitted)
        assertEquals(1, before.coverage.uncoveredRootIds!!.size)
        val cp = after.page!!
        assertEquals(1, cp.observedUses)
        assertEquals(1, cp.observedDistinctCallers)
        assertEquals(0, cp.returnedUses)
        assertEquals(0, cp.returnedDistinctCallers)
        assertEquals(1, cp.omittedBefore)
        assertEquals(0, cp.omittedAfter)
        assertTrue(cp.hasPrevious)
        assertFalse(cp.hasNext)
        assertTrue(cp.observations.isEmpty())
        assertTrue(cp.bindings.isEmpty())
        assertEquals("unknown", after.coverage.coverage)
        assertEquals(SourceUseProducerTotals(1, 1, 0, 0), after.coverage.counts!!.producer)
        assertEquals(1L, after.coverage.counts!!.importCounts.admitted)
        assertEquals(0L, after.coverage.counts!!.viewOmitted)
        assertTrue(after.coverage.uncoveredRootIds!!.isEmpty())
        for (page in listOf(bp, cp)) { assertEquals(1, page.offset); assertEquals(1, page.limit) }
        assertEquals(1, before.coverage.units!!.size)
        assertEquals(1, after.coverage.units!!.size)
    }

    @Test fun `empty revision target set keeps a captured empty page with historical coverage`() {
        val base = graph(listOf(a to c))
        val current = CodeGraph(base.nodes.values.filter { it.id != c }, base.edges).preserveCompilerSourceUsesFrom(base)
        assertEquals(listOf(a), SourceEditImpact.analyze(base, setOf(c)).page!!.observations.map { it.caller.usr })
        val empty = SourceEditImpact.analyze(current, emptySet())
        assertTrue(empty.changed.isEmpty())
        assertNotNull(empty.page)
        assertEquals(0, empty.page!!.observedUses)
        assertEquals("partial", empty.coverage.coverage)
        assertEquals(1L, empty.coverage.counts!!.viewOmitted)
        assertFailsWith<IllegalArgumentException> { SourceEditImpact.analyze(current, setOf(c)) }
    }

    @Test fun `changed membership is copied and sorted with legacy NodeId ordering`() {
        val bmp = id("")
        val supplementary = id("𐀀")
        val graph = graph(ids = listOf(bmp, supplementary))
        val seeds = linkedSetOf(bmp, supplementary)
        val result = SourceEditImpact.analyze(graph, seeds)
        seeds.clear()
        assertEquals(listOf(supplementary, bmp), result.changed)
        assertEquals(0, result.page!!.observedUses)
        assertFailsWith<UnsupportedOperationException> { (result.changed as MutableList<NodeId>).clear() }
    }
}
