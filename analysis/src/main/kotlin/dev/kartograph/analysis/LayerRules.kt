package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.SourceLocation

/** 이름, 모듈 또는 source 경로에 적용할 레이어 패턴 묶음이다. */
public data class LayerDefinition(val name: String, val patterns: List<String>)

/** allow 또는 deny 중 하나로 표현하는 방향성 레이어 규칙이다. */
public data class LayerRule(
    val name: String,
    val from: String,
    val allow: List<String>? = null,
    val deny: List<String>? = null,
) {
    /** 서로 모순되는 allow/deny를 조용히 적용하지 않는다. */
    init {
        require((allow == null) != (deny == null)) { "layer rule must declare exactly one of allow or deny" }
    }
}

/** 레이어 배정 근거다. */
public data class LayerAssignment(val layer: String?, val pattern: String?, val candidate: String?)

/** 규칙과 실제 usage 간선을 함께 보존하는 위반 근거다. */
public data class LayerViolation(
    val rule: LayerRule,
    val sourceLayer: String,
    val targetLayer: String,
    val edge: GraphEdge,
    val location: SourceLocation?,
)

/** 먼저 선언된 패턴을 우선해 레이어 규칙을 평가한다. */
public class LayerRuleEvaluator(
    private val layers: List<LayerDefinition>,
    private val rules: List<LayerRule>,
) {
    /** 이름, 모듈, source 경로 중 실제로 먼저 맞은 값을 근거와 함께 반환한다. */
    public fun assignment(node: GraphNode): LayerAssignment {
        val candidates = listOfNotNull(node.name, node.moduleName, ArchitectureGraph.unitOf(node), node.location?.path).distinct()
        for (layer in layers) for (pattern in layer.patterns) {
            candidates.firstOrNull { globMatches(pattern, it) }?.let { candidate ->
                return LayerAssignment(layer.name, pattern, candidate)
            }
        }
        return LayerAssignment(null, null, null)
    }

    /** 선언 의존 간선에 걸린 모든 위반을 간선의 결정적 순서로 반환한다. */
    public fun evaluate(graph: CodeGraph): List<LayerViolation> {
        val assignments = graph.nodes.mapValues { assignment(it.value).layer }
        return graph.edges.filter(ArchitectureGraph::isDependency).flatMap { edge ->
            val source = assignments[edge.source] ?: return@flatMap emptyList()
            val target = assignments[edge.target] ?: return@flatMap emptyList()
            if (source == target) return@flatMap emptyList()
            rules.filter { rule -> rule.from == source && violates(rule, target) }.map { rule ->
                LayerViolation(rule, source, target, edge, graph.node(edge.source)?.location)
            }
        }
    }

    /** 설정이 덮지 못해 규칙 판정에서 제외된 선언을 숨기지 않는다. */
    public fun unassignedNodes(graph: CodeGraph) = graph.nodeIds.filter { assignment(graph.nodes.getValue(it)).layer == null }

    private fun violates(rule: LayerRule, target: String): Boolean =
        rule.deny?.contains(target) == true || (rule.allow != null && target !in rule.allow)

    private fun globMatches(pattern: String, candidate: String): Boolean {
        val regex = buildString {
            append('^')
            pattern.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(character.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(candidate)
    }
}
