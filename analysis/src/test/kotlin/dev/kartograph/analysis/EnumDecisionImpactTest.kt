package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEnumDecisionEvidence
import dev.kartograph.core.CompilerEnumDecisionImportCounts
import dev.kartograph.core.CompilerEnumDecisionProducerCounts
import dev.kartograph.core.CompilerEnumDecisionRoot
import dev.kartograph.core.CompilerEnumDecisionSite
import dev.kartograph.core.CompilerEnumDecisionUnit
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.EnumDecisionAttribution
import dev.kartograph.core.EnumDecisionKind
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnumDecisionImpactTest {
    private val owner = NodeId("class:p/Mode")
    private val a = NodeId("field:p/Mode#A:Lp/Mode;")
    private val b = NodeId("field:p/Mode#B:Lp/Mode;")
    private val output = InputFingerprint("classes", "build/classes", "1".repeat(64))
    private val root = CompilerEnumDecisionRoot(0, output, listOf(owner))

    @Test
    fun `only additions absent from current explicit constants produce candidates`() {
        val evidence = evidence()
        val report = EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), evidence)
        assertTrue(report.evidenceAvailable)
        assertEquals(listOf(b), report.comparisons.single().addedConstants)
        assertEquals(2, report.totalCandidates)
        assertEquals(setOf(EnumDecisionKind.EXPLICIT_FALLBACK, EnumDecisionKind.NEGATED_FINITE_MEMBERSHIP),
            report.candidates.map { it.site.kind }.toSet())
        assertTrue(report.candidates.all { it.addedConstants == listOf(b) })
        assertEquals(3, report.resolvedSites)
        assertEquals(1, report.unresolvedSites)
        assertEquals("SOURCE_ROOT_UNRESOLVED", report.unresolved.single().reason)
        assertEquals(1, report.unresolvedAttributions[EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED])
        assertEquals(0, EnumDecisionImpact.analyze(graph(listOf(a, b)), graph(listOf(a, b)), evidence).totalCandidates)
        assertEquals(0, EnumDecisionImpact.analyze(graph(listOf(a, b)), graph(listOf(a)), evidence(constants = listOf(a))).totalCandidates)
        assertEquals(listOf(b), EnumDecisionImpact.analyze(graph(listOf(a, b)), graph(listOf(a)), evidence(constants = listOf(a))).comparisons.single().removedConstants)
        // 이전 소스가 어떠했는지와 무관하게 현재 모든 판단이 새 상수를 명시하면 후보가 없다.
        assertEquals(0, EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), evidence(constants = listOf(a, b))).totalCandidates)
    }

    @Test
    fun `paging never changes original counts and reports omitted unresolved independently`() {
        val full = EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), evidence())
        val page = EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), evidence(), limit = 1, offset = 1)
        assertEquals(1, page.candidates.size)
        assertEquals(2, page.totalCandidates)
        assertEquals(1, page.omittedCandidates)
        assertEquals(full.resolvedSites, page.resolvedSites)
        assertEquals(full.unresolvedSites, page.unresolvedSites)
        assertEquals(full.unresolvedAttributions, page.unresolvedAttributions)
        assertTrue(page.unresolved.isEmpty())
        assertEquals(1, page.totalUnresolvedRecords)
        assertEquals(1, page.omittedUnresolvedSites)
        val emptyPage = EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), evidence(), offset = Int.MAX_VALUE)
        assertEquals(2, emptyPage.omittedCandidates)
        assertTrue(emptyPage.candidates.isEmpty())
    }

    @Test
    fun `null and inspected empty are distinct and unavailable inventories are never empty enums`() {
        val unknown = EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), null)
        assertFalse(unknown.evidenceAvailable)
        assertTrue(unknown.candidates.isEmpty())
        assertTrue(EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), emptyEvidence()).evidenceAvailable)
        val noBase = EnumDecisionImpact.analyze(CodeGraph(emptyList(), emptyList()), graph(listOf(a, b)), evidence())
        assertEquals(EnumInventoryComparisonStatus.BASE_ENUM_UNAVAILABLE, noBase.comparisons.single().status)
        assertEquals(0, noBase.totalCandidates)
        val noInventory = CodeGraph(listOf(GraphNode(owner, "Mode", NodeKind.ENUM)), emptyList())
        val hidden = EnumDecisionImpact.analyze(graph(listOf(a)), noInventory, evidence())
        assertEquals(EnumInventoryComparisonStatus.CURRENT_INVENTORY_UNAVAILABLE, hidden.comparisons.single().status)
        assertEquals(3, hidden.viewUnavailableSites)
        assertEquals(4, hidden.totalUnresolvedRecords)
        assertEquals(3, hidden.unresolved.count { it.reason == "VIEW_UNAVAILABLE" })
        val pruned = EnumDecisionImpact.analyze(graph(listOf(a)), CodeGraph(emptyList(), emptyList()), evidence(), setOf(owner))
        assertEquals(EnumInventoryComparisonStatus.CURRENT_ENUM_UNAVAILABLE, pruned.comparisons.single().status)
        assertEquals(3, pruned.viewUnavailableSites)
        assertEquals(0, pruned.totalCandidates)
        assertEquals(listOf(b), EnumDecisionImpact.analyze(graph(), graph(listOf(b)), emptyEvidence()).comparisons.single().addedConstants)
    }

    @Test
    fun `same source from two receipts is displayed once without rewriting capture counts`() {
        val first = unit(1, 1, "build/one.tsv")
        val second = unit(1, 1, "build/two.tsv")
        val rows = listOf(first, second).flatMap { unit ->
            listOf(site(unit, 1, listOf(a)), site(unit, 20, listOf(a), attribution = EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED))
        }
        val evidence = CompilerEnumDecisionEvidence(listOf(root), listOf(first, second), rows)
        val report = EnumDecisionImpact.analyze(graph(listOf(a)), graph(listOf(a, b)), evidence)
        assertEquals(1, report.totalCandidates)
        assertEquals(2, report.resolvedSites)
        assertEquals(2, report.unresolvedSites)
        assertEquals(1, report.totalUnresolvedRecords)
        assertEquals(2, report.unresolvedAttributions[EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED])
        assertFailsWith<UnsupportedOperationException> { (report.candidates as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.comparisons.single().addedConstants as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.unresolvedAttributions as MutableMap<*, *>).clear() }
    }

    @Test
    fun `exact enum selectors isolate other owners and reject field filters`() {
        val other = NodeId("class:p/Other")
        val x = NodeId("field:p/Other#X:Lp/Other;")
        val y = NodeId("field:p/Other#Y:Lp/Other;")
        val base = CodeGraph(graph(listOf(a)).nodes.values + GraphNode(other, "Other", NodeKind.ENUM, enumConstants = listOf(x)), emptyList())
        val current = CodeGraph(graph(listOf(a, b)).nodes.values + GraphNode(other, "Other", NodeKind.ENUM, enumConstants = listOf(x, y)), emptyList())
        val selected = EnumDecisionImpact.analyze(base, current, evidence(), setOf(other))
        assertEquals(listOf(other), selected.comparisons.map { it.owner })
        assertEquals(listOf(y), selected.comparisons.single().addedConstants)
        assertEquals(0, selected.totalCandidates)
        assertEquals(0, selected.resolvedSites)
        assertEquals(0, selected.unresolvedSites)
        assertEquals(2, EnumDecisionImpact.analyze(base, current, evidence(), setOf(owner)).totalCandidates)
        assertFailsWith<IllegalArgumentException> { EnumDecisionImpact.analyze(base, current, evidence(), setOf(a)) }
    }

    private fun graph(constants: List<NodeId> = emptyList()) = CodeGraph(listOf(GraphNode(owner, "Mode", NodeKind.ENUM, enumConstants = constants.sorted())), emptyList())
    private fun unit(resolved: Int, unresolved: Int, receipt: String = "build/enum.tsv") =
        CompilerEnumDecisionUnit(InputFingerprint("compilerEvidence", receipt, "2".repeat(64)), "kotlin-constants", "2.4.10",
            "3".repeat(64), "4".repeat(64), listOf(CompilerSourceUseClaim("sample:main", "kotlin", ":compileKotlin", listOf(output))),
            CompilerEnumDecisionProducerCounts(resolved + unresolved, resolved + unresolved, 0, 0),
            CompilerEnumDecisionImportCounts(resolved, unresolved))
    private fun site(unit: CompilerEnumDecisionUnit, offset: Int, constants: List<NodeId>,
        kind: EnumDecisionKind = EnumDecisionKind.EXPLICIT_FALLBACK,
        attribution: EnumDecisionAttribution = EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT) =
        CompilerEnumDecisionSite(unit.id, owner, constants, kind, null, true,
            CompilerEvidenceSource("src/p/Use.kt", "a".repeat(64)), CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
            offset, offset + 5, 1, offset + 1, false,
            if (attribution == EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT) root.id else null, root.id, attribution)
    private fun evidence(constants: List<NodeId>? = null): CompilerEnumDecisionEvidence {
        val unit = unit(3, 1)
        return CompilerEnumDecisionEvidence(listOf(root), listOf(unit), listOf(
            site(unit, 1, constants ?: listOf(a)),
            site(unit, 20, constants ?: listOf(a, b), EnumDecisionKind.FINITE_MEMBERSHIP),
            site(unit, 40, constants ?: emptyList(), EnumDecisionKind.NEGATED_FINITE_MEMBERSHIP),
            site(unit, 60, constants ?: listOf(a), attribution = EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED)))
    }
    private fun emptyEvidence() = CompilerEnumDecisionEvidence(listOf(root), listOf(unit(0, 0)), emptyList())
}
