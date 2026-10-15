package dev.kartograph.analysis

import dev.kartograph.core.*
import dev.kartograph.analysis.SourceUseAnalysisFixture.a
import dev.kartograph.analysis.SourceUseAnalysisFixture.b
import dev.kartograph.analysis.SourceUseAnalysisFixture.c
import dev.kartograph.analysis.SourceUseAnalysisFixture.graph
import dev.kartograph.analysis.SourceUseAnalysisFixture.id
import dev.kartograph.analysis.SourceUseAnalysisFixture.Spec
import kotlin.test.*

class SourceUseOrderTest {
    private val low = "0".repeat(64)
    private val high = "f".repeat(64)
    private val key = SourceUseOrderKey("method:p/S#f()V", "method:p/T#g()V", "src/p/F.kt", 1, "8".repeat(64), high)

    @Test fun `scalar source target and path keys precede deliberately reversed later keys`() {
        val vectors = listOf(
            key.copy(source = "method:p/#f()V", target = "method:p/Z#g()V", id = high) to
                key.copy(source = "method:p/𐀀#f()V", target = "method:p/A#g()V", id = low),
            key.copy(target = "method:p/#g()V", path = "src/Z.kt", id = high) to
                key.copy(target = "method:p/𐀀#g()V", path = "src/A.kt", id = low),
            key.copy(path = "src/.kt", offsetUtf16 = 10, id = high) to
                key.copy(path = "src/𐀀.kt", offsetUtf16 = 2, id = low),
        )
        for ((first, last) in vectors) {
            assertTrue(SourceUseOrder.compare(first, last) < 0)
            assertTrue(SourceUseOrder.compare(last, first) > 0)
            assertEquals(listOf(high, low), listOf(last, first).sortedWith { left, right ->
                SourceUseOrder.compare(left, right)
            }.map { it.id })
        }
        assertTrue(SourceUseOrder.compareText("", "𐀀") < 0)
        assertTrue("".compareTo("𐀀") > 0, "legacy UTF-16 ordering remains unchanged")
    }

    @Test fun `numeric offset then unit then defensive final ID order is exact`() {
        val vectors = listOf(
            key.copy(offsetUtf16 = 2, unitId = high, id = high) to key.copy(offsetUtf16 = 10, unitId = low, id = low),
            key.copy(unitId = low, id = high) to key.copy(unitId = high, id = low),
            key.copy(id = low) to key.copy(id = high),
        )
        for ((first, last) in vectors) {
            assertTrue(SourceUseOrder.compare(first, last) < 0)
            assertTrue(SourceUseOrder.compare(last, first) > 0)
        }
        assertEquals(0, SourceUseOrder.compare(key, key.copy()))
        assertEquals(0, SourceUseOrder.compareText("same", "same"))
        assertTrue(SourceUseOrder.compareText("src/F", "src/FA") < 0)
        assertTrue(SourceUseOrder.compareText("src/FA", "src/F") > 0)
        assertTrue(SourceUseOrder.compareText("", "x") < 0)
    }

    @Test fun `admitted rows use scalar source target path and numeric offset order before paging`() {
        val bmp = id("")
        val supplementary = id("𐀀")
        val scalarSources = graph(ids = listOf(bmp, supplementary, c), specs = listOf(
            Spec(supplementary, c, 0), Spec(bmp, c, 1)))
        assertEquals(listOf(bmp, supplementary), SourceUseQuery.inspect(scalarSources, c).page!!.observations.map { it.use.source })
        assertEquals(listOf(bmp), SourceEditImpact.analyze(scalarSources, setOf(c), SourceUsePageRequest(1)).page!!.observations.map { it.use.source })
        val scalarTargets = graph(ids = listOf(a, bmp, supplementary), specs = listOf(
            Spec(a, supplementary, 0, "src/A.kt"), Spec(a, bmp, 1, "src/Z.kt")))
        assertEquals(listOf(bmp, supplementary), SourceUseQuery.inspect(scalarTargets, a).page!!.observations.map { it.use.target })
        val scalarPaths = graph(specs = listOf(Spec(a, c, 2, "src/𐀀.kt"), Spec(a, c, 10, "src/.kt")))
        assertEquals(listOf("src/.kt", "src/𐀀.kt"), SourceUseQuery.inspect(scalarPaths, c).page!!.observations.map { it.use.file.path })
        val offsets = graph(specs = listOf(Spec(a, c, 10), Spec(a, c, 2)))
        assertEquals(listOf(2, 10), SourceUseQuery.inspect(offsets, c).page!!.observations.map { it.use.offsetUtf16 })
    }

    @Test fun `duplicate or conflicting unit source path offset selectors still fail Stage A admission`() {
        assertFailsWith<IllegalArgumentException> {
            graph(specs = listOf(Spec(a, b, 1), Spec(a, c, 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            graph(specs = listOf(Spec(a, c, 1), Spec(a, c, 1)))
        }
        // final id tie용 두 행을 만들기 위해 admission을 완화하지 않는다.
        val original = graph(listOf(a to c))
        val evidence = original.compilerSourceUseEvidence!!
        val unit = evidence.units.single()
        val two = CompilerSourceUseUnit(unit.receipt, unit.collector, unit.compilerVersion,
            unit.collectorArtifactFingerprint, unit.inputToken, unit.claims,
            CompilerSourceUseProducerCounts(2, 2, 0, 0), CompilerSourceUseImportCounts(2, 0, 0, 0, 0, 0), 0)
        val row = evidence.uses.single().copy(unitId = two.id)
        assertNotEquals(row.id, row.copy(endOffsetUtf16 = row.endOffsetUtf16 + 1).id)
        assertFailsWith<IllegalArgumentException> {
            CompilerSourceUseEvidence(evidence.roots, listOf(two), listOf(row, row.copy(endOffsetUtf16 = row.endOffsetUtf16 + 1)))
        }
    }
}
