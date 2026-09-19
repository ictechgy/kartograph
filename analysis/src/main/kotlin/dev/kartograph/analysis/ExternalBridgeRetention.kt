package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** 외부에서 호출되는 멤버의 실제 소유 선언도 살리되 형제 멤버까지 확장하지 않는다. */
public object ExternalBridgeRetention {
    /** 인덱스의 MEMBER 관계만 역으로 따라가며 동일한 외부 호출 근거를 보존한다. */
    public fun expand(graph: CodeGraph, evidence: Iterable<RetentionEvidence>): List<RetentionEvidence> = buildList {
        val ownersByNode = mutableMapOf<NodeId, List<NodeId>>()
        for (item in evidence) {
            add(item)
            if (item.reason != RetentionReason.EXTERNAL_BRIDGE || !graph.contains(item.nodeId)) continue
            val owners = ownersByNode.getOrPut(item.nodeId) { ownersOf(graph, item.nodeId) }
            owners.forEach { add(item.copy(nodeId = it)) }
        }
    }.distinct()

    private fun ownersOf(graph: CodeGraph, nodeId: NodeId): List<NodeId> {
        val pending = ArrayDeque(listOf(nodeId))
        val seen = linkedSetOf(nodeId)
        while (pending.isNotEmpty()) {
            graph.incomingEdgesTo(pending.removeFirst()).filter { it.kind == EdgeKind.MEMBER }.forEach { edge ->
                if (seen.add(edge.source)) pending.add(edge.source)
            }
        }
        return seen.filter { it != nodeId }
    }
}
