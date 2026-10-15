package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.NodeId

/** 변경한 선언을 직접 선택한 원본 행이다. 영향 경로나 실행 여부를 주장하지 않는다. */
public data class SourceEditObservation internal constructor(
    val use: CompilerSourceUse,
    val caller: SourceUseNodeFact,
    val target: SourceUseNodeFact,
    val matchingBytecodeCall: Boolean,
)

/** 한 revision의 원본 선택만 보고하며 다른 revision이나 graph 영향과 합치지 않는다. */
public class SourceEditImpactReport internal constructor(
    changed: List<NodeId>,
    public val coverage: SourceUseCoverage,
    public val page: SourceUsePage<SourceEditObservation>?,
) {
    public val changed: List<NodeId> = freezeSourceUseList(changed)

    override fun equals(other: Any?): Boolean = other is SourceEditImpactReport &&
        changed == other.changed && coverage == other.coverage && page == other.page

    override fun hashCode(): Int = listOf(changed, coverage, page).hashCode()
}

/** 정확한 changed target을 고른 관찰만 찾는 직접 source-edit 질의다. */
public object SourceEditImpact {
    /** changed 안의 호출자와 자기 선택을 제외하고 traversal 없이 retained 행을 한 번 검사한다. */
    public fun analyze(
        graph: CodeGraph,
        changed: Set<NodeId>,
        request: SourceUsePageRequest = SourceUsePageRequest(),
    ): SourceEditImpactReport {
        val seeds = changed.toSet()
        require(seeds.all(graph::contains)) { "source edit changed ID is absent from graph" }
        val coverage = SourceUseCoverage.from(graph)
        val page = sourceUsePage(graph, request, { it.target in seeds && it.source !in seeds }) { use, calls ->
            SourceEditObservation(use, graph.sourceUseFact(use.source), graph.sourceUseFact(use.target),
                (use.source to use.target) in calls)
        }
        // seed 정렬은 기존 NodeId 규칙이다. source 행의 scalar comparator를 재사용하지 않는다.
        return SourceEditImpactReport(seeds.sorted(), coverage, page)
    }
}
