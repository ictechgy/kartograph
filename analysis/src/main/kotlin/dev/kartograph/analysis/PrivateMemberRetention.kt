package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.Visibility

/** Private 보고에서는 외부 호출 가능한 reachable owner의 진입점을 보수적으로 따라간다. */
public object PrivateMemberRetention {
    /** Usage와 외부 member 경계를 한 번의 worklist로 확장해 private helper 오탐을 막는다. */
    public fun expand(graph: CodeGraph, evidence: List<RetentionEvidence>): List<RetentionEvidence> {
        val expanded = evidence.toMutableList()
        val queue = ArrayDeque(evidence.map { it.nodeId }.sorted())
        val visited = mutableSetOf<dev.kartograph.core.NodeId>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            graph.outgoingEdgesFrom(id).forEach { edge ->
                if (edge.kind.impliesUsage) queue.addLast(edge.target)
                if (edge.kind != EdgeKind.MEMBER) return@forEach
                val member = graph.node(edge.target) ?: return@forEach
                if (member.visibility !in PRIVATE_VISIBILITIES || member.jvmVisibility !in PRIVATE_VISIBILITIES ||
                    NodeAttribute.INLINE_FUNCTION in member.attributes || member.name in SERIALIZATION_CALLBACKS
                ) {
                    expanded += RetentionEvidence(member.id, RetentionReason.EXTERNAL_MEMBER_ENTRY, member.location)
                    queue.addLast(member.id)
                }
            }
        }
        return expanded.distinct()
    }

    private val PRIVATE_VISIBILITIES = setOf(Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS)
    internal val SERIALIZATION_CALLBACKS = setOf("readObject", "writeObject", "readObjectNoData", "readResolve", "writeReplace", "serialVersionUID", "serialPersistentFields")
}
