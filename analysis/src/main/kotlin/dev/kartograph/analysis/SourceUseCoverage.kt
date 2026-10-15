package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerSourceUseImportCounts
import dev.kartograph.core.CompilerSourceUseProducerCounts
import dev.kartograph.core.InputFingerprint
import java.util.Collections

/** 원본 unit의 producer 계수를 Long으로 보존하며 query/page 계수와 섞지 않는다. */
public data class SourceUseProducerTotals(
    val observed: Long,
    val emitted: Long,
    val unmapped: Long,
    val ambiguous: Long,
)

/** 원본 emitted 행의 import 분류를 보존한 계수다. */
public data class SourceUseImportTotals(
    val admitted: Long,
    val outsideGraph: Long,
    val shadowedCaller: Long,
    val shadowedTarget: Long,
    val outsideClaimedOutputs: Long,
    val sourceAssociationUnverified: Long,
)

/** 전체 captured revision/view에 속한 계수이며 선택한 선언만의 coverage가 아니다. */
public data class SourceUseCountTotals(
    val producer: SourceUseProducerTotals,
    val importCounts: SourceUseImportTotals,
    val viewOmitted: Long,
    val retainedUses: Long,
)

/** 한 unit을 여러 claim이나 관찰에서 참조해도 원본 계수는 한 번만 보고한다. */
public data class SourceUseUnitSummary(
    val id: String,
    val producer: SourceUseProducerTotals,
    val importCounts: SourceUseImportTotals,
    val viewOmitted: Int,
    val retainedUses: Int,
)

/** 반환한 관찰의 unit을 bound snapshot의 원본 provenance와 연결한다. */
public data class SourceUseUnitBinding(
    val id: String,
    val receipt: InputFingerprint,
    val collector: String,
    val compilerVersion: String,
    val collectorArtifactFingerprint: String,
    val inputToken: String,
    val claimCount: Int,
)

/** null 채널과 실제 capture를 구분한다. 알려진 누락이 없어도 완전성을 주장하지 않는다. */
public class SourceUseCoverage internal constructor(
    public val availability: String,
    public val coverage: String,
    public val selectedRootCount: Int?,
    uncoveredRootIds: List<String>?,
    public val counts: SourceUseCountTotals?,
    units: List<SourceUseUnitSummary>?,
) {
    public val uncoveredRootIds: List<String>? = uncoveredRootIds?.let(::freezeSourceUseList)
    public val units: List<SourceUseUnitSummary>? = units?.let(::freezeSourceUseList)

    override fun equals(other: Any?): Boolean = other is SourceUseCoverage && availability == other.availability &&
        coverage == other.coverage && selectedRootCount == other.selectedRootCount &&
        uncoveredRootIds == other.uncoveredRootIds && counts == other.counts && units == other.units

    override fun hashCode(): Int = listOf(availability, coverage, selectedRootCount, uncoveredRootIds, counts, units).hashCode()

    public companion object {
        /** 원본 root ordinal과 unit 계수를 유지하며 현재 view의 누락도 독립적으로 집계한다. */
        public fun from(graph: CodeGraph): SourceUseCoverage {
            val evidence = graph.compilerSourceUseEvidence ?: return SourceUseCoverage(
                "unavailable", "unknown", null, null, null, null,
            )
            val units = evidence.units.map { unit ->
                SourceUseUnitSummary(unit.id, unit.producer.toTotals(), unit.importCounts.toTotals(),
                    unit.viewOmitted, unit.importCounts.admitted - unit.viewOmitted)
            }.sortedBy { it.id }
            val producer = SourceUseProducerTotals(
                checkedSourceUseSum(units) { it.producer.observed },
                checkedSourceUseSum(units) { it.producer.emitted },
                checkedSourceUseSum(units) { it.producer.unmapped },
                checkedSourceUseSum(units) { it.producer.ambiguous },
            )
            val imported = SourceUseImportTotals(
                checkedSourceUseSum(units) { it.importCounts.admitted },
                checkedSourceUseSum(units) { it.importCounts.outsideGraph },
                checkedSourceUseSum(units) { it.importCounts.shadowedCaller },
                checkedSourceUseSum(units) { it.importCounts.shadowedTarget },
                checkedSourceUseSum(units) { it.importCounts.outsideClaimedOutputs },
                checkedSourceUseSum(units) { it.importCounts.sourceAssociationUnverified },
            )
            val totals = SourceUseCountTotals(producer, imported,
                checkedSourceUseSum(units) { it.viewOmitted.toLong() },
                checkedSourceUseSum(units) { it.retainedUses.toLong() })
            val uncovered = evidence.uncoveredRoots.map { it.id }
            val partial = producer.unmapped != 0L || producer.ambiguous != 0L ||
                imported.outsideGraph != 0L || imported.shadowedCaller != 0L || imported.shadowedTarget != 0L ||
                imported.outsideClaimedOutputs != 0L || imported.sourceAssociationUnverified != 0L ||
                totals.viewOmitted != 0L || uncovered.isNotEmpty()
            return SourceUseCoverage("captured", if (partial) "partial" else "unknown",
                evidence.roots.size, uncovered, totals, units)
        }
    }
}

private fun CompilerSourceUseProducerCounts.toTotals(): SourceUseProducerTotals =
    SourceUseProducerTotals(observed.toLong(), emitted.toLong(), unmapped.toLong(), ambiguous.toLong())

private fun CompilerSourceUseImportCounts.toTotals(): SourceUseImportTotals = SourceUseImportTotals(
    admitted.toLong(), outsideGraph.toLong(), shadowedCaller.toLong(), shadowedTarget.toLong(),
    outsideClaimedOutputs.toLong(), sourceAssociationUnverified.toLong(),
)

internal fun <T> freezeSourceUseList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

internal fun <T> checkedSourceUseSum(values: Iterable<T>, count: (T) -> Long): Long {
    var result = 0L
    for (value in values) result = Math.addExact(result, count(value))
    return result
}
