package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeKind
import kotlin.math.abs

/** 모듈 하나의 Robert C. Martin 결합도와 주계열 지표다. */
public data class MartinMetric(
    val module: String,
    val afferentCoupling: Int,
    val efferentCoupling: Int,
    val abstractTypes: Int,
    val totalTypes: Int,
) {
    /** I = Ce / (Ca + Ce), 고립 모듈은 0이다. */
    public val instability: Double
        get() = (afferentCoupling + efferentCoupling).let { if (it == 0) 0.0 else efferentCoupling.toDouble() / it }
    /** A = 추상 타입 / 전체 타입이다. */
    public val abstractness: Double
        get() = if (totalTypes == 0) 0.0 else abstractTypes.toDouble() / totalTypes
    /** D = |A + I - 1|이다. */
    public val distance: Double
        get() = abs(abstractness + instability - 1.0)
}

/** CodeGraph의 공용 `impliesUsage` 의미만 사용해 모듈 지표를 계산한다. */
public object MartinMetrics {
    /** moduleName이 없으면 JVM package를 단위로 삼아 정렬된 지표를 만든다. */
    public fun calculate(graph: CodeGraph): List<MartinMetric> {
        val units = graph.nodes.mapValues { (_, node) -> ArchitectureGraph.unitOf(node) }
        val nodesByModule = graph.nodes.values.groupBy { units.getValue(it.id) }
        val moduleEdges = graph.edges.asSequence()
            .filter { it.kind.impliesUsage }
            .mapNotNull { edge ->
                val source = units[edge.source]
                val target = units[edge.target]
                if (source == null || target == null || source == target) null else source to target
            }.toSet()
        return nodesByModule.keys.sorted().map { module ->
            val types = nodesByModule.getValue(module).filter { it.kind in TYPE_KINDS }
            MartinMetric(
                module,
                moduleEdges.count { it.second == module },
                moduleEdges.count { it.first == module },
                types.count { it.kind in ABSTRACT_KINDS || JvmModifier.ABSTRACT in it.jvmModifiers },
                types.size,
            )
        }
    }

    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
    private val ABSTRACT_KINDS = setOf(NodeKind.INTERFACE, NodeKind.ANNOTATION_CLASS)
}
