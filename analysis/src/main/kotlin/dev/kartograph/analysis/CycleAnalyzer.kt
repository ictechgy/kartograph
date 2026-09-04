package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.NodeId
import java.util.ArrayDeque

/** 하나의 SCC를 설명하는 결정적 대표 순환과 가장 가벼운 경계다. */
public data class DependencyCycle(
    val component: List<NodeId>,
    val path: List<NodeId>,
    val edges: List<GraphEdge>,
) {
    /** 참조 횟수가 같을 때도 그래프 간선 순서로 고정되는 수정 후보다. */
    public val weakestEdge: GraphEdge = edges.minWith(compareBy<GraphEdge>({ it.weight }, { it }))
}

/** 공유 usage 간선 위에서 반복형 Tarjan SCC 분석을 수행한다. */
public object CycleAnalyzer {
    /** 각 SCC에서 하나의 실제 대표 경로를 찾아 짧은 순으로 반환한다. */
    public fun analyze(graph: CodeGraph): List<DependencyCycle> = components(graph)
        .filter { component ->
            component.size > 1 || graph.usageSuccessorsOf(component.single()).contains(component.single())
        }
        .mapNotNull { component -> representativeCycle(graph, component) }
        .sortedWith(compareBy({ it.path.size }, { it.path.joinToString("\u0000") { id -> id.value } }))

    private fun components(graph: CodeGraph): List<List<NodeId>> {
        data class Frame(val node: NodeId, val successors: List<NodeId>, var next: Int = 0)
        var index = 0
        val indices = mutableMapOf<NodeId, Int>()
        val low = mutableMapOf<NodeId, Int>()
        val stack = ArrayDeque<NodeId>()
        val onStack = mutableSetOf<NodeId>()
        val result = mutableListOf<List<NodeId>>()
        for (root in graph.nodeIds) {
            if (root in indices) continue
            indices[root] = index
            low[root] = index++
            stack.addLast(root)
            onStack += root
            val frames = ArrayDeque<Frame>()
            frames.addLast(Frame(root, graph.usageSuccessorsOf(root).distinct().sorted()))
            while (frames.isNotEmpty()) {
                val frame = frames.last()
                if (frame.next < frame.successors.size) {
                    val successor = frame.successors[frame.next++]
                    if (successor !in indices) {
                        indices[successor] = index
                        low[successor] = index++
                        stack.addLast(successor)
                        onStack += successor
                        frames.addLast(Frame(successor, graph.usageSuccessorsOf(successor).distinct().sorted()))
                    } else if (successor in onStack) {
                        low[frame.node] = minOf(low.getValue(frame.node), indices.getValue(successor))
                    }
                } else {
                    frames.removeLast()
                    if (low.getValue(frame.node) == indices.getValue(frame.node)) {
                        val component = mutableListOf<NodeId>()
                        do {
                            val member = stack.removeLast()
                            onStack -= member
                            component += member
                        } while (member != frame.node)
                        result += component.sorted()
                    }
                    frames.lastOrNull()?.let { parent ->
                        low[parent.node] = minOf(low.getValue(parent.node), low.getValue(frame.node))
                    }
                }
            }
        }
        return result.sortedBy { it.joinToString("\u0000") { id -> id.value } }
    }

    private fun representativeCycle(graph: CodeGraph, component: List<NodeId>): DependencyCycle? {
        val members = component.toSet()
        for (start in component) {
            val queue = ArrayDeque<NodeId>()
            val parent = mutableMapOf<NodeId, NodeId?>()
            queue += start
            parent[start] = null
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (next in graph.usageSuccessorsOf(current).distinct().sorted().filter { it in members }) {
                    if (next == start) {
                        val reversed = mutableListOf(current)
                        var cursor = parent[current]
                        while (cursor != null) {
                            reversed += cursor
                            cursor = parent[cursor]
                        }
                        val path = reversed.asReversed()
                        val edges = path.indices.map { position ->
                            val target = path[(position + 1) % path.size]
                            graph.outgoingEdgesFrom(path[position])
                                .filter { it.target == target && it.kind.impliesUsage }
                                .minWith(compareBy<GraphEdge>({ it.weight }, { it }))
                        }
                        return DependencyCycle(component, path, edges)
                    }
                    if (next !in parent) {
                        parent[next] = current
                        queue += next
                    }
                }
            }
        }
        return null
    }
}
