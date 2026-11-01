package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

internal fun annotationRetentionEvidence(
    graph: CodeGraph,
    reasonByAnnotation: Map<String, RetentionReason>,
    ownerReason: (RetentionReason) -> RetentionReason = { reason -> reason },
    memberReason: ((RetentionReason) -> RetentionReason)? = null,
    includeOwners: Boolean = true,
    // 같은 순회 안에서 owner를 보존하지 않을 annotation이다. 해당 member만 root가 되고 형제 member로 넓히지 않는다.
    memberOnlyAnnotations: Set<String> = emptySet(),
): List<RetentionEvidence> = graph.nodeIds.flatMap { nodeId ->
    val annotatedNode = graph.nodes.getValue(nodeId)
    annotatedNode.annotations.mapNotNull { annotation ->
        reasonByAnnotation[annotation]?.let { reason -> reason to (includeOwners && annotation !in memberOnlyAnnotations) }
    }.flatMap { (reason, retainOwners) ->
        buildList {
            add(RetentionEvidence(annotatedNode.id, reason, annotatedNode.location))
            (if (retainOwners) owningDeclarations(nodeId, graph) else emptyList()).forEach { ownerId ->
                add(RetentionEvidence(ownerId, ownerReason(reason), annotatedNode.location))
            }
            memberReason?.let { retainedMemberReason ->
                graph.outgoingEdgesFrom(nodeId)
                    .filter { edge -> edge.kind == EdgeKind.MEMBER }
                    .forEach { edge ->
                        add(RetentionEvidence(edge.target, retainedMemberReason(reason), annotatedNode.location))
                    }
            }
        }
    }
}.distinct().sortedWith(RETENTION_ORDER)

private fun owningDeclarations(nodeId: NodeId, graph: CodeGraph): List<NodeId> {
    val owners = mutableListOf<NodeId>()
    val visited = mutableSetOf(nodeId)
    var current = nodeId
    while (true) {
        val owner = graph.incomingEdgesTo(current)
            .firstOrNull { edge -> edge.kind == EdgeKind.MEMBER }
            ?.source ?: break
        if (!visited.add(owner)) break
        owners += owner
        current = owner
    }
    return owners
}

internal val RETENTION_ORDER = compareBy<RetentionEvidence>(
    RetentionEvidence::nodeId,
    { evidence -> evidence.reason.ordinal },
    { evidence -> evidence.location?.path.orEmpty() },
    { evidence -> evidence.location?.line ?: 0 },
)
