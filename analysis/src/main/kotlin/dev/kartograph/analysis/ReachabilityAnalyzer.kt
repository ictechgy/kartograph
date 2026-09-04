package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence

/** 보존 root에서 공통 usage edge만 따라 도달 가능한 정점을 계산한다. */
public object ReachabilityAnalyzer {
    /** 결정적인 BFS 경로와 graph에 연결되지 않은 evidence를 함께 반환한다. */
    public fun analyze(graph: CodeGraph, evidence: Iterable<RetentionEvidence>): ReachabilityResult {
        val sortedEvidence = evidence.sortedWith(RETENTION_EVIDENCE_ORDER)
        val matchingEvidence = sortedEvidence.filter { item -> graph.contains(item.nodeId) }
        val roots = matchingEvidence.map(RetentionEvidence::nodeId).distinct()
        val predecessor = mutableMapOf<NodeId, NodeId?>()
        val queue = ArrayDeque<NodeId>()

        roots.forEach { root ->
            predecessor[root] = null
            queue.addLast(root)
        }
        while (queue.isNotEmpty()) {
            val source = queue.removeFirst()
            graph.usageSuccessorsOf(source).forEach { target ->
                if (target !in predecessor) {
                    predecessor[target] = source
                    queue.addLast(target)
                }
            }
        }

        val reachable = predecessor.keys.toSet()
        return ReachabilityResult(
            reachableNodeIds = reachable,
            unreachableNodeIds = graph.nodeIds.filterNot(reachable::contains),
            evidenceByNode = matchingEvidence.groupBy(RetentionEvidence::nodeId),
            unmatchedEvidence = sortedEvidence.filterNot { item -> graph.contains(item.nodeId) },
            predecessor = predecessor,
        )
    }

    private val RETENTION_EVIDENCE_ORDER = compareBy<RetentionEvidence>(
        { evidence -> evidence.nodeId },
        { evidence -> evidence.reason.ordinal },
        { evidence -> evidence.location?.path.orEmpty() },
        { evidence -> evidence.location?.line ?: 0 },
        { evidence -> evidence.location?.column ?: 0 },
    )
}

/** 도달성 집합과 판정을 설명할 root evidence 및 대표 경로를 보관한다. */
public class ReachabilityResult internal constructor(
    public val reachableNodeIds: Set<NodeId>,
    public val unreachableNodeIds: List<NodeId>,
    private val evidenceByNode: Map<NodeId, List<RetentionEvidence>>,
    public val unmatchedEvidence: List<RetentionEvidence>,
    private val predecessor: Map<NodeId, NodeId?>,
) {
    /** 정점 자체를 root로 만든 모든 보존 근거를 반환한다. */
    public fun retentionEvidenceFor(nodeId: NodeId): List<RetentionEvidence> =
        evidenceByNode[nodeId].orEmpty()

    /** root부터 정점까지 첫 번째 결정적 usage 경로를 반환한다. */
    public fun pathFromRootTo(nodeId: NodeId): List<NodeId>? {
        if (nodeId !in reachableNodeIds) return null
        val reversedPath = mutableListOf<NodeId>()
        var current: NodeId? = nodeId
        while (current != null) {
            reversedPath += current
            current = predecessor[current]
        }
        return reversedPath.asReversed()
    }
}
