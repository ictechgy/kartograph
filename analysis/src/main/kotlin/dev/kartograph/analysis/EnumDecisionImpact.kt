package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEnumDecisionEvidence
import dev.kartograph.core.CompilerEnumDecisionSite
import dev.kartograph.core.EnumDecisionAttribution
import dev.kartograph.core.EnumConstantIds
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import java.util.Collections

/** 같은 JVM enum의 두 capture를 비교할 수 있는지다. 없거나 미수집인 inventory는 빈 enum이 아니다. */
public enum class EnumInventoryComparisonStatus {
    AVAILABLE, BASE_ENUM_UNAVAILABLE, CURRENT_ENUM_UNAVAILABLE,
    BASE_INVENTORY_UNAVAILABLE, CURRENT_INVENTORY_UNAVAILABLE,
}

/** 실제 ACC_ENUM inventory의 차이이며 이름이나 ordinal 추정은 하지 않는다. */
public class EnumInventoryComparison internal constructor(
    public val owner: NodeId,
    public val status: EnumInventoryComparisonStatus,
    addedConstants: List<NodeId>,
    removedConstants: List<NodeId>,
) {
    public val addedConstants: List<NodeId> = freezeEnumList(addedConstants)
    public val removedConstants: List<NodeId> = freezeEnumList(removedConstants)
}

/** 새 상수 중 현재 판단의 명시된 유한 집합에 없는 것들이다. 실제 실행 결과를 뜻하지 않는다. */
public class EnumDecisionCandidate internal constructor(
    public val site: CompilerEnumDecisionSite,
    addedConstants: List<NodeId>,
) {
    public val addedConstants: List<NodeId> = freezeEnumList(addedConstants)
}

/** 원래 미귀속 이유 또는 현재 view에서 사라진 사실을 source 위치와 함께 표시한다. */
public data class EnumDecisionUnresolvedSite internal constructor(
    val site: CompilerEnumDecisionSite,
    val reason: String,
)

/** capture 분류와 현재 view 가용성 계수는 paging과 독립적이다. */
public class EnumDecisionImpactReport internal constructor(
    public val evidenceAvailable: Boolean,
    comparisons: List<EnumInventoryComparison>,
    candidates: List<EnumDecisionCandidate>,
    public val totalCandidates: Int,
    public val omittedCandidates: Int,
    public val resolvedSites: Int,
    public val unresolvedSites: Int,
    unresolved: List<EnumDecisionUnresolvedSite>,
    public val totalUnresolvedRecords: Int,
    public val omittedUnresolvedSites: Int,
    unresolvedAttributions: Map<EnumDecisionAttribution, Int>,
    public val viewUnavailableSites: Int,
    public val offset: Int,
    public val limit: Int,
) {
    public val comparisons: List<EnumInventoryComparison> = freezeEnumList(comparisons)
    public val candidates: List<EnumDecisionCandidate> = freezeEnumList(candidates)
    public val unresolved: List<EnumDecisionUnresolvedSite> = freezeEnumList(unresolved)
    public val unresolvedAttributions: Map<EnumDecisionAttribution, Int> = Collections.unmodifiableMap(LinkedHashMap(unresolvedAttributions))
}

/** 현재 compiler 판단만 검사하는 순수 비교다. snapshot scope/tool 호환성은 호출자가 먼저 확인한다. */
public object EnumDecisionImpact {
    /** 빈 owners는 현재 graph의 모든 enum이며 명시 필터는 정확한 JVM class ID다. */
    public fun analyze(
        base: CodeGraph,
        current: CodeGraph,
        evidence: CompilerEnumDecisionEvidence?,
        owners: Set<NodeId> = emptySet(),
        limit: Int = 100,
        offset: Int = 0,
    ): EnumDecisionImpactReport {
        require(limit in 1..1_000 && offset >= 0) { "invalid enum decision page" }
        val requested = owners.toSet()
        require(requested.all(EnumConstantIds::isOwner)) { "enum decision filters require exact JVM class IDs" }
        evidence?.requireMatchesGraph(current)
        val selected = if (requested.isEmpty()) current.nodes.values.filter { it.kind == NodeKind.ENUM }.map { it.id }.sorted() else requested.sorted()
        val comparisons = selected.map { id ->
            val before = base.node(id)
            val after = current.node(id)
            val status = when {
                after == null || after.kind != NodeKind.ENUM || NodeAttribute.EXTERNAL_STUB in after.attributes -> EnumInventoryComparisonStatus.CURRENT_ENUM_UNAVAILABLE
                before == null || before.kind != NodeKind.ENUM || NodeAttribute.EXTERNAL_STUB in before.attributes -> EnumInventoryComparisonStatus.BASE_ENUM_UNAVAILABLE
                after.enumConstants == null -> EnumInventoryComparisonStatus.CURRENT_INVENTORY_UNAVAILABLE
                before.enumConstants == null -> EnumInventoryComparisonStatus.BASE_INVENTORY_UNAVAILABLE
                else -> EnumInventoryComparisonStatus.AVAILABLE
            }
            val old = before?.enumConstants.orEmpty().toSet()
            val new = after?.enumConstants.orEmpty().toSet()
            EnumInventoryComparison(id, status,
                if (status == EnumInventoryComparisonStatus.AVAILABLE) (new - old).sorted() else emptyList(),
                if (status == EnumInventoryComparisonStatus.AVAILABLE) (old - new).sorted() else emptyList())
        }
        val byOwner = comparisons.associateBy { it.owner }
        var resolved = 0
        var unresolved = 0
        var unavailable = 0
        val candidates = ArrayList<EnumDecisionCandidate>()
        val candidateKeys = HashSet<List<Any?>>()
        val unresolvedRows = ArrayList<EnumDecisionUnresolvedSite>()
        val unresolvedKeys = HashSet<List<Any?>>()
        fun recordUnresolved(site: CompilerEnumDecisionSite, reason: String) {
            if (unresolvedKeys.add(enumSiteKey(site) + reason)) unresolvedRows.add(EnumDecisionUnresolvedSite(site, reason))
        }
        val reasons = linkedMapOf<EnumDecisionAttribution, Int>()
        evidence?.sites?.forEach { site ->
            if (requested.isNotEmpty() && site.enumOwner !in requested) return@forEach
            if (site.attribution != EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT) {
                unresolved++
                recordUnresolved(site, site.attribution.name)
                reasons[site.attribution] = (reasons[site.attribution] ?: 0) + 1
                return@forEach
            }
            resolved++
            val owner = current.node(site.enumOwner)
            if (owner == null || owner.enumConstants == null) {
                unavailable++
                recordUnresolved(site, "VIEW_UNAVAILABLE")
                return@forEach
            }
            val comparison = byOwner[site.enumOwner] ?: return@forEach
            if (comparison.status != EnumInventoryComparisonStatus.AVAILABLE || comparison.addedConstants.isEmpty()) return@forEach
            val mentioned = site.constants.toSet()
            val unmentioned = comparison.addedConstants.filter { it !in mentioned }
            val key = enumSiteKey(site)
            if (unmentioned.isNotEmpty() && candidateKeys.add(key)) candidates.add(EnumDecisionCandidate(site, unmentioned))
        }
        candidates.sortWith(compareBy({ it.site.enumOwner }, { it.site.file.path }, { it.site.offsetUtf16 }, { it.site.id }))
        unresolvedRows.sortWith(compareBy({ it.site.enumOwner }, { it.site.file.path }, { it.site.offsetUtf16 }, { it.site.id }, { it.reason }))
        val page = candidates.drop(offset).take(limit)
        val unresolvedPage = unresolvedRows.drop(offset).take(limit)
        return EnumDecisionImpactReport(evidence != null, comparisons, page, candidates.size, candidates.size - page.size,
            resolved, unresolved, unresolvedPage, unresolvedRows.size, unresolvedRows.size - unresolvedPage.size,
            reasons.toSortedMap(compareBy { it.name }), unavailable, offset, limit)
    }
}

private fun enumSiteKey(site: CompilerEnumDecisionSite): List<Any?> =
    listOf(site.sourceRootId, site.file, site.coordinateBasis, site.offsetUtf16, site.endOffsetUtf16,
        site.line, site.column, site.enumOwner, site.enumRootId, site.constants, site.kind,
        site.lexicalClass, site.lexicalLambda, site.generated)

private fun <T> freezeEnumList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
