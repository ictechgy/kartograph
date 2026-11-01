package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeKind
import kotlin.math.abs

/**
 * 모듈 하나의 Robert C. Martin 결합도와 주계열 지표다.
 *
 * 결합이 없는 단위의 I와 작성된 타입이 없는 단위의 A는 수학적으로 정의되지 않는다. 기존 값 계약을 지키려고
 * [instability]·[abstractness]는 그 경우에도 0.0을 돌려주므로, D를 해석하거나 정렬하기 전에
 * [distanceDefined]로 실제 주계열 거리인지 확인한다.
 */
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
    /** A = 추상 타입 / 작성된(합성되지 않은) 전체 타입이다. */
    public val abstractness: Double
        get() = if (totalTypes == 0) 0.0 else abstractTypes.toDouble() / totalTypes
    /** D = |A + I - 1|이다. */
    public val distance: Double
        get() = abs(abstractness + instability - 1.0)
    /** Ca 또는 Ce가 있어 I가 정의되는지 나타낸다. 거짓이면 [instability]의 0.0은 자리표시 값이다. */
    public val instabilityDefined: Boolean
        get() = afferentCoupling + efferentCoupling > 0
    /** 작성된 타입이 있어 A가 정의되는지 나타낸다. 거짓이면 [abstractness]의 0.0은 자리표시 값이다. */
    public val abstractnessDefined: Boolean
        get() = totalTypes > 0
    /** I와 A가 모두 정의될 때만 [distance]가 zone of pain/uselessness를 뜻하는 주계열 거리다. */
    public val distanceDefined: Boolean
        get() = instabilityDefined && abstractnessDefined
}

/** CodeGraph의 공유 선언 의존 관계로 모듈 지표를 계산한다. */
public object MartinMetrics {
    /** moduleName이 없으면 JVM package를 단위로 삼아 정렬된 지표를 만든다. */
    public fun calculate(graph: CodeGraph): List<MartinMetric> {
        val units = graph.nodes.mapValues { (_, node) -> ArchitectureGraph.unitOf(node) }
        val nodesByModule = graph.nodes.values.groupBy { units.getValue(it.id) }
        val moduleEdges = graph.edges.asSequence()
            .filter(ArchitectureGraph::isDependency)
            .mapNotNull { edge ->
                val source = units[edge.source]
                val target = units[edge.target]
                if (source == null || target == null || source == target) null else source to target
            }.toSet()
        return nodesByModule.keys.sorted().map { module ->
            // file facade·multi-file facade·Kotlin 합성 class·생성 class는 작성된 설계 타입이 아니므로 A에서 뺀다.
            // 결합 간선은 그대로 단위에 귀속한다.
            val types = nodesByModule.getValue(module).filter { it.kind in TYPE_KINDS && !it.synthesized }
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
