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
): List<RetentionEvidence> = graph.nodeIds.flatMap { nodeId ->
    val annotatedNode = graph.nodes.getValue(nodeId)
    annotatedNode.annotations.mapNotNull(reasonByAnnotation::get).flatMap { reason ->
        buildList {
            add(RetentionEvidence(annotatedNode.id, reason, annotatedNode.location))
            owningDeclarations(nodeId, graph).forEach { ownerId ->
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

private val RETENTION_ORDER = compareBy<RetentionEvidence>(
    RetentionEvidence::nodeId,
    { evidence -> evidence.reason.ordinal },
    { evidence -> evidence.location?.path.orEmpty() },
    { evidence -> evidence.location?.line ?: 0 },
)
