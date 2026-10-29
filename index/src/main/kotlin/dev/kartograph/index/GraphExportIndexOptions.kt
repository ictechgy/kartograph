package dev.kartograph.index

import dev.kartograph.core.EdgeOrigin

/** 그래프 내보내기의 후보 확장 비용과 명시적 출처 필터다. 일반 분석의 기본 인덱싱은 바꾸지 않는다. */
public data class GraphExportIndexOptions(
    val excludedOrigins: Set<EdgeOrigin> = emptySet(),
    val maximumDispatchCandidates: Int = 256,
    val includeExternalStubs: Boolean = false,
) {
    init { require(maximumDispatchCandidates in 1..1_000_000) { "dispatch candidate maximum must be in 1..1000000" } }
}

/** 같은 class 관측과 내보내기에서 실제로 생략한 후보의 한계를 함께 반환한다. */
public data class GraphExportIndexResult(val indexed: IndexedClasses, val limitations: List<String>)
