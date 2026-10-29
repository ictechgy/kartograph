package dev.kartograph.index

import dev.kartograph.core.CodeGraph

/** 13277fbb의 최종 선택문을 식별자·조건·생성자 인자 변경 없이 추출한 테스트 oracle이다. */
internal fun fullRebuildGraphExportSelection(expanded: CodeGraph, exportOptions: GraphExportIndexOptions?): CodeGraph {
        val selected = if (exportOptions == null || exportOptions.excludedOrigins.isEmpty()) expanded else
            CodeGraph(expanded.nodes.values, expanded.edges.filter { it.origin !in exportOptions.excludedOrigins },
                expanded.externalCalls, expanded.serviceProviders, expanded.enclosures,
                expanded.callbackArguments, expanded.parameterUses, expanded.lambdaEscapes)
        return selected
}
