package dev.kartograph.export

import dev.kartograph.core.*

/** 같은 의미의 간선을 provenance를 잃지 않고 한 행으로 내보내는 작은 투영이다. */
internal data class ExportEdge(
    val source: NodeId, val target: NodeId, val kind: String, val weight: Long,
    val origins: List<String>, val originWeights: Map<String, Int>, val callSiteLines: List<Int>,
)

/** 정렬된 signature의 최대 origin 수만 모으므로 전체 간선 JSON 목록을 만들지 않는다. */
internal fun exportEdges(graph: CodeGraph): Sequence<ExportEdge> = sequence {
    val iterator = graph.edges.iterator()
    var pending: GraphEdge? = if (iterator.hasNext()) iterator.next() else null
    while (pending != null) {
        val first = pending
        val group = mutableListOf(first)
        pending = null
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (next.source == first.source && next.target == first.target && next.kind == first.kind) group += next
            else { pending = next; break }
        }
        group.groupBy { if (it.origin == EdgeOrigin.DISPATCH_MODEL) "dispatchCandidate" else it.kind.name.lowerCamel() }
            .toSortedMap().forEach { (kind, edges) ->
                val weights = edges.associate { it.origin.name.lowerCamel() to it.weight }.toSortedMap()
                yield(ExportEdge(first.source, first.target, kind, edges.sumOf { it.weight.toLong() },
                    weights.keys.toList(), weights, edges.flatMap(GraphEdge::callSiteLines).distinct().sorted()))
            }
    }
}

/** JSONL와 CSV가 같은 출처·weight 정의를 공유한다. */
internal fun ExportEdge.toJsonValue(): Map<String, Any?> = sortedMapOf(
    "callSiteLines" to callSiteLines,
    "kind" to kind,
    "originWeights" to originWeights,
    "origins" to origins,
    "source" to source.value,
    "target" to target.value,
    "weight" to weight,
)
