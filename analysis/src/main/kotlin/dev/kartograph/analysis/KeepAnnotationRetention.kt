package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** bytecode에 남은 AndroidX Keep annotation을 위치와 무관하게 보존 근거로 바꾼다. */
public object KeepAnnotationRetention {
    /** graph의 결정적 정점 순서로 Keep annotation evidence를 반환한다. */
    public fun find(graph: CodeGraph): List<RetentionEvidence> = annotationRetentionEvidence(
        graph = graph,
        reasonByAnnotation = mapOf(ANDROIDX_KEEP to RetentionReason.KEEP_ANNOTATION),
        ownerReason = { reason ->
            if (reason == RetentionReason.KEEP_ANNOTATION) RetentionReason.KEEP_ANNOTATED_MEMBER else reason
        },
        memberReason = { RetentionReason.KEEP_ANNOTATED_CLASS_MEMBER },
    )

    private const val ANDROIDX_KEEP = "androidx/annotation/Keep"
}
