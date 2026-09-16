package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** Runtime가 class 이름으로 소유하는 선언의 member body도 도달성 진입점으로 확장한다. */
public object RuntimeRetentionExpansion {
    /** 이름 기반 runtime 소유권 근거에 class의 직접 member를 같은 근거로 추가한다. */
    public fun expand(graph: CodeGraph, evidence: Iterable<RetentionEvidence>): List<RetentionEvidence> =
        evidence.flatMap { item ->
            if (item.reason !in OWNER_REASONS) return@flatMap listOf(item)
            listOf(item) + graph.outgoingEdgesFrom(item.nodeId)
                .filter { edge -> edge.kind == EdgeKind.MEMBER }
                .map { edge -> RetentionEvidence(edge.target, item.reason, item.location) }
        }.distinct().sortedWith(
            compareBy(
                RetentionEvidence::nodeId,
                { item -> item.reason.name },
                { item -> item.location?.path.orEmpty() },
                { item -> item.location?.line ?: 0 },
            ),
        )

    private val OWNER_REASONS = setOf(
        RetentionReason.SERVICE_PROVIDER,
        RetentionReason.MANIFEST_COMPONENT,
        RetentionReason.XML_LAYOUT,
        RetentionReason.DEPENDENCY_INJECTION,
        RetentionReason.SERIALIZATION,
        RetentionReason.GENERATED_CODE,
        RetentionReason.RUNTIME_ENTRY_POINT,
    )
}
