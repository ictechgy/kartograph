package dev.kartograph.analysis

import dev.kartograph.core.NodeId
import kotlin.test.*

class SourceUseAcceptedEvidenceTest {
    private val wrap = NodeId("method:inlineprobe/ProbeKt#wrap(I)I")
    private val kotlinCaller = NodeId("method:inlineprobe/ProbeKt#kotlinCaller()I")
    private val javaDirect = NodeId("method:inlineprobe/Leaf#javaDirect()I")
    private val ordinary = NodeId("method:inlineprobe/ProbeKt#ordinaryControl()I")
    private val position = NodeId("method:inlineprobe/ProbeKt#positionControl()I")
    private val erasedUse = "f7113f4ae46a352e8f3f8ee0a228f74eecd49f611edc0848d5fd2957c888919c"
    private val ordinaryUse = "74b8e6fda865b59c7ffcd98d611b5b98f0e7c59f46aa58c6aa802a9e472d7618"

    @Test fun `accepted erased Kotlin selection and Java bytecode caller remain separate`() {
        val graph = SourceUseAcceptedFixture.graph()
        val edges = graph.edges.toList()
        val reachability = ReachabilityAnalyzer.analyze(graph, emptyList())
        val ordinaryQuery = SymbolQuery.query(graph, reachability, wrap.value, emptyList())
        assertEquals(listOf(javaDirect.value), ordinaryQuery.result!!.usedBy.map { it.usr })
        val selection = SourceUseQuery.inspect(graph, wrap).page!!.observations.single()
        assertEquals(erasedUse, selection.use.id)
        assertEquals(kotlinCaller, selection.caller.usr)
        assertEquals(wrap, selection.target.usr)
        assertEquals(listOf("incoming"), selection.directions)
        assertFalse(selection.matchingBytecodeCall)
        val outgoing = SourceUseQuery.inspect(graph, kotlinCaller).page!!.observations.single()
        assertEquals(erasedUse, outgoing.use.id)
        assertEquals(listOf("outgoing"), outgoing.directions)
        val impact = ChangeImpact.analyze(ImpactInput(graph), listOf(wrap.value))
        assertEquals(listOf(javaDirect), impact.affected.map { it.node.id })
        val source = SourceEditImpact.analyze(graph, setOf(wrap)).page!!.observations.single()
        assertEquals(erasedUse, source.use.id)
        assertEquals(kotlinCaller, source.caller.usr)
        assertEquals(edges, graph.edges)
        assertEquals(ordinaryQuery, SymbolQuery.query(graph, reachability, wrap.value, emptyList()))
        assertEquals(impact, ChangeImpact.analyze(ImpactInput(graph), listOf(wrap.value)))
    }

    @Test fun `accepted ordinary call correlation and exact source provenance are preserved`() {
        val graph = SourceUseAcceptedFixture.graph()
        val row = SourceEditImpact.analyze(graph, setOf(ordinary)).page!!.observations.single()
        assertEquals(ordinaryUse, row.use.id)
        assertEquals(position, row.caller.usr)
        assertTrue(row.matchingBytecodeCall)
        assertEquals("41532de79e6dedc17f8b2006c06cf41cddcfb50a5984014815791be1bb691f4a", row.use.unitId)
        assertEquals("eb68a8bff3640109d6d79e038c9fd786351bb92e22d8a2e0d4f7e2c733a31eb6", row.use.file.sha256)
        assertEquals("src/inlineprobe/Probe.kt", row.use.file.path)
        assertEquals(285, row.use.offsetUtf16)
        assertEquals(300, row.use.endOffsetUtf16)
        assertEquals(9, row.use.line)
        assertEquals(30, row.use.column)
        assertEquals("Probe.kt", row.caller.location!!.path)
        assertEquals(9, row.caller.location!!.line)
        val binding = SourceUseQuery.inspect(graph, ordinary).page!!.bindings.single()
        assertEquals("4d1d4f400ca308d135adc61a452853f8dbb54cf186e0284dcb395fbb12e8a9a5", binding.receipt.sha256)
        assertEquals("compilerEvidence", binding.receipt.role)
        assertEquals("49435cd2473f292b57e5dcd4efb85e15a4b8346400ab4e378107a2dd1969ef4e", binding.inputToken)
    }

    @Test fun `wrong overload unused inline and changed caller remain negative controls`() {
        val graph = SourceUseAcceptedFixture.graph()
        for (id in listOf("method:inlineprobe/ProbeKt#wrap(Ljava/lang/String;)I", "method:inlineprobe/ProbeKt#unusedInline(I)I")) {
            val report = SourceEditImpact.analyze(graph, setOf(NodeId(id)))
            assertEquals(0, report.page!!.observedUses)
            assertEquals("captured", report.coverage.availability)
            assertEquals("partial", report.coverage.coverage)
        }
        assertEquals(0, SourceEditImpact.analyze(graph, setOf(wrap, kotlinCaller)).page!!.observedUses)
    }

    @Test fun `accepted global coverage and paged empty totals remain historical`() {
        val graph = SourceUseAcceptedFixture.graph()
        val report = SourceUseQuery.inspect(graph, wrap, SourceUsePageRequest(1, 1))
        val coverage = report.coverage
        assertEquals("captured", coverage.availability)
        assertEquals("partial", coverage.coverage)
        assertEquals(2, coverage.selectedRootCount)
        assertEquals(listOf("75a736db03d72c2cd19e3d769f28663f61aad302cb198f62a272daadf33b30a1"), coverage.uncoveredRootIds)
        assertEquals(SourceUseProducerTotals(6, 2, 4, 0), coverage.counts!!.producer)
        assertEquals(SourceUseImportTotals(2, 0, 0, 0, 0, 0), coverage.counts!!.importCounts)
        assertEquals(0L, coverage.counts!!.viewOmitted)
        assertEquals(2L, coverage.counts!!.retainedUses)
        val page = report.page!!
        assertEquals(1, page.observedUses)
        assertEquals(0, page.returnedUses)
        assertEquals(1, page.omittedBefore)
        assertEquals(0, page.omittedAfter)
        assertTrue(page.hasPrevious)
        assertFalse(page.hasNext)
        assertTrue(page.bindings.isEmpty())
    }
}
