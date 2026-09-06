package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/** CLI와 Gradle의 보고 범위를 공유해 같은 그래프에서 같은 finding을 만든다. */
public object DeadFindings {
    /**
     * 도달 불가 class, file facade의 top-level 선언, opt-in private member를 보고한다.
     * top-level 선언은 inline·property 접근자·native·상수·launcher main처럼
     * bytecode로 사용 부재를 증명할 수 없는 형태를 제외한다.
     */
    public fun collect(
        graph: CodeGraph,
        reachability: ReachabilityResult,
        includePrivateMembers: Boolean = false,
    ): List<Finding> = reachability.unreachableNodeIds.mapNotNull(graph::node).filter { node ->
        if (node.synthesized) return@filter false
        if (node.kind in TYPE_KINDS) return@filter true
        if (isFacadeMember(graph, node)) {
            return@filter isReportableTopLevelDeclaration(node, includePrivateMembers)
        }
        if (!includePrivateMembers || node.kind !in MEMBER_KINDS ||
            node.visibility !in PRIVATE_VISIBILITIES || node.jvmVisibility !in PRIVATE_VISIBILITIES ||
            JvmModifier.NATIVE in node.jvmModifiers || NodeAttribute.COMPILE_TIME_CONSTANT in node.attributes ||
            NodeAttribute.INLINE_FUNCTION in node.attributes || node.name in PrivateMemberRetention.SERIALIZATION_CALLBACKS
        ) return@filter false
        val owner = singleOwner(graph, node) ?: return@filter false
        owner.kind in TYPE_KINDS && !owner.synthesized && owner.id in reachability.reachableNodeIds
    }.map { Finding(it.id, it.location) }.sorted()

    // owner가 file facade인 선언만 top-level 보고 대상이다. synthetic lambda facade는 포함하지 않는다.
    private fun isFacadeMember(graph: CodeGraph, node: GraphNode): Boolean {
        if (node.kind !in TOP_LEVEL_KINDS) return false
        val owner = singleOwner(graph, node) ?: return false
        return NodeAttribute.FILE_FACADE in owner.attributes
    }

    private fun isReportableTopLevelDeclaration(node: GraphNode, includePrivateMembers: Boolean): Boolean {
        if (JvmModifier.NATIVE in node.jvmModifiers) return false
        if (node.attributes.any(EXCLUDED_TOP_LEVEL_ATTRIBUTES::contains)) return false
        if (node.name in PrivateMemberRetention.SERIALIZATION_CALLBACKS) return false
        if (JvmEntryPoint.isLauncherMain(node)) return false
        val isPrivate = node.visibility in PRIVATE_VISIBILITIES || node.jvmVisibility in PRIVATE_VISIBILITIES
        return includePrivateMembers || !isPrivate
    }

    private fun singleOwner(graph: CodeGraph, node: GraphNode): GraphNode? =
        graph.incomingEdgesTo(node.id).filter { it.kind == EdgeKind.MEMBER }
            .singleOrNull()?.source?.let(graph::node)

    private val PRIVATE_VISIBILITIES = setOf(Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS)
    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
    private val MEMBER_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.FIELD, NodeKind.PROPERTY)
    private val TOP_LEVEL_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
    private val EXCLUDED_TOP_LEVEL_ATTRIBUTES = setOf(
        NodeAttribute.INLINE_FUNCTION,
        NodeAttribute.PROPERTY_ACCESSOR,
        NodeAttribute.COMPILE_TIME_CONSTANT,
    )
}
