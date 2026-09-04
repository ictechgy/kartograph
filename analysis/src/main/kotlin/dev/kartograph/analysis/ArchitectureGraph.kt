package dev.kartograph.analysis

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
            graph.edges.asSequence().filter { it.kind.impliesUsage }.mapNotNull { edge ->
                val source = units[edge.source] ?: return@mapNotNull null
                val target = units[edge.target] ?: return@mapNotNull null
                if (source == target) null else GraphEdge(NodeId("unit:$source"), NodeId("unit:$target"), edge.kind, edge.weight)
            }.asIterable(),
        )
    }

    /** 명시 moduleName이 없을 때도 모든 JVM 선언을 같은 package 단위에 배정한다. */
    public fun unitOf(node: GraphNode): String = node.moduleName ?: packageName(node.id.value)

    private fun packageName(id: String): String {
        val owner = id.substringAfter(':').substringBefore('#')
        return owner.substringBeforeLast('/', missingDelimiterValue = "<default>").replace('/', '.')
    }
}
