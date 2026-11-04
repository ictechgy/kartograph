package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/**
 * finding이 테스트에서만 도달하는지 판정하는 정책이다. CLI와 Gradle plugin이 같은 규칙을 쓰도록 analysis에 둔다.
 * 그래프 조립(production·test root를 함께 인덱싱)은 입력 adapter가 하고, 여기서는 두 그래프만 받는다.
 */
public object TestOnlyReachability {
    /**
     * [combined]에만 있고 [production]에는 없는 정점(test 전용 정점)을 seed로 삼아 [combined]에서 도달하는 정점이다.
     *
     * production root를 먼저 인덱싱해야 production 정점이 seed에서 빠진다. 같은 FQN이 양쪽에 있으면 첫 root(production)
     * 사실이 우선해 test 사본 간선이 가려질 수 있고, 같은 root를 양쪽에 넘기면 seed가 비어 빈 집합이 된다(문서화된 경계).
     */
    public fun reachableFromTestSide(production: CodeGraph, combined: CodeGraph): Set<NodeId> {
        val testSideRoots = combined.nodeIds.filter { it !in production.nodes }
            .map { RetentionEvidence(it, RetentionReason.RUNTIME_ENTRY_POINT, null) }
        return ReachabilityAnalyzer.analyze(combined, testSideRoots).reachableNodeIds
    }
}
