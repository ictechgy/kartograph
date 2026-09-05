package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.Finding
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/** CLI와 Gradle의 보고 범위를 공유해 같은 그래프에서 같은 finding을 만든다. */
public object DeadFindings {
    /** Private member는 살아 있는 비생성 owner 아래에서만 추가해 class finding과 중복하지 않는다. */
    public fun collect(
        graph: CodeGraph,
        reachability: ReachabilityResult,
        includePrivateMembers: Boolean = false,
    ): List<Finding> = reachability.unreachableNodeIds.mapNotNull(graph::node).filter { node ->
        if (node.synthesized) return@filter false
        if (node.kind in TYPE_KINDS) return@filter true
        if (!includePrivateMembers || node.kind !in MEMBER_KINDS ||
            node.visibility !in PRIVATE_VISIBILITIES || node.jvmVisibility !in PRIVATE_VISIBILITIES ||
            JvmModifier.NATIVE in node.jvmModifiers || NodeAttribute.COMPILE_TIME_CONSTANT in node.attributes ||
            NodeAttribute.INLINE_FUNCTION in node.attributes || node.name in PrivateMemberRetention.SERIALIZATION_CALLBACKS
        ) return@filter false
        val ownerEdges = graph.incomingEdgesTo(node.id).filter { it.kind == EdgeKind.MEMBER }
        val owner = ownerEdges.singleOrNull()?.source?.let(graph::node) ?: return@filter false
        owner.kind in TYPE_KINDS && !owner.synthesized && owner.id in reachability.reachableNodeIds
    }.map { Finding(it.id, it.location) }.sorted()

    private val PRIVATE_VISIBILITIES = setOf(Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS)
    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
    private val MEMBER_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.FIELD, NodeKind.PROPERTY)
}
