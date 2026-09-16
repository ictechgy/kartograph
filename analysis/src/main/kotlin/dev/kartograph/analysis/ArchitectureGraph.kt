package dev.kartograph.analysis

import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind

/** 선언 그래프를 moduleName 또는 JVM package 단위의 공유 usage 그래프로 접는다. */
public object ArchitectureGraph {
    /** 단위 내부 간선은 제외하고 단위 사이 간선 weight만 합친다. */
    public fun aggregate(graph: CodeGraph): CodeGraph {
        val units = graph.nodes.mapValues { (_, node) -> unitOf(node) }
        val names = units.values.toSortedSet()
        return CodeGraph(
            names.map { name -> GraphNode(NodeId("unit:$name"), name, NodeKind.CLASS, moduleName = name) },
            graph.edges.asSequence().filter(::isDependency).mapNotNull { edge ->
                val source = units[edge.source] ?: return@mapNotNull null
                val target = units[edge.target] ?: return@mapNotNull null
                if (source == target) null else GraphEdge(NodeId("unit:$source"), NodeId("unit:$target"), edge.kind, edge.weight, edge.origin)
            }.asIterable(),
        )
    }

    // receiver 후보는 도달성을 보수적으로 넓히지만 호출자가 구현 모듈을 선언 의존한다는 뜻은 아니다.
    internal fun isDependency(edge: GraphEdge): Boolean =
        edge.kind.impliesUsage && edge.origin != EdgeOrigin.DISPATCH_MODEL

    /** 명시 moduleName이 없을 때도 모든 JVM 선언을 같은 package 단위에 배정한다. */
    public fun unitOf(node: GraphNode): String = node.moduleName ?: packageName(node.id.value)

    private fun packageName(id: String): String {
        val owner = id.substringAfter(':').substringBefore('#')
        return owner.substringBeforeLast('/', missingDelimiterValue = "<default>").replace('/', '.')
    }
}
