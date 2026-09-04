package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** Compiler가 사용처를 값으로 치환해 reference를 남기지 않는 상수의 source owner를 보존한다. */
public object InlineConstantRetention {
    /** compile-time constant field를 가진 직접 owner에 bytecode 손실을 설명하는 근거를 반환한다. */
    public fun find(graph: CodeGraph): List<RetentionEvidence> = graph.nodes.values
        .filter { node -> NodeAttribute.COMPILE_TIME_CONSTANT in node.attributes }
        .flatMap { constant ->
            graph.incomingEdgesTo(constant.id)
                .filter { edge -> edge.kind == EdgeKind.MEMBER }
                .map { edge -> RetentionEvidence(edge.source, RetentionReason.INLINE_CONSTANT, constant.location) }
        }
        .distinct()
        .sortedBy(RetentionEvidence::nodeId)
}
