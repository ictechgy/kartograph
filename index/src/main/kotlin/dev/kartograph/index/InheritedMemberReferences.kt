package dev.kartograph.index

import dev.kartograph.core.CallResolution
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/**
 * JVM operand의 owner가 선언 owner와 다를 때, 입력 안에서 증명한 상속 멤버 참조만 복원한다.
 * 필드는 JVMS 5.4.3.2의 인터페이스 우선 순서를, static/super 호출은 class 사슬의 첫 선언을 따른다.
 * virtual dispatch·인터페이스 default 선택·누락된 hierarchy는 추측하지 않는다.
 */
internal class InheritedMemberReferences(classFacts: List<ClassFacts>) {
    private val nodes = classFacts.flatMap(ClassFacts::nodes).associateBy(GraphNode::id)
    private val types = classFacts.mapNotNull { facts -> nodes[JvmNodeId.classId(facts.internalName)] }
        .associateBy { requireNotNull(it.jvmSignature) }
    private val fields = mutableMapOf<NodeId, FieldLookup>()
    private val methods = mutableMapOf<Pair<NodeId, InvocationKind>, NodeId?>()
    private val inputCalls = classFacts.flatMap(ClassFacts::calls)
    private val callTargets: Map<ExternalCall, NodeId> = inputCalls
        .filter { it.target !in nodes && it.owner in types && isExactCall(it) }
        .mapNotNull { call ->
            val cacheKey = call.target to call.kind
            if (cacheKey !in methods) methods[cacheKey] = resolveMethod(call)
            methods[cacheKey]?.let { call to it }
        }.toMap()

    /** 정확한 invocation의 횟수·줄만 복원하며 같은 operand의 virtual 호출 근거를 섞지 않는다. */
    val edges: List<GraphEdge> = inheritedCallEdges(classFacts) + classFacts.asSequence()
        .flatMap { it.edges.asSequence() }.mapNotNull { edge ->
            if (edge.origin != EdgeOrigin.BYTECODE || edge.kind != EdgeKind.FIELD_ACCESS || edge.target in nodes) return@mapNotNull null
            resolveField(edge.target).target?.let { edge.copy(target = it) }
        }.toList()

    /** 원래 operand는 바꾸지 않고, 정확한 호출의 프로젝트 선언을 기존 해석 필드로 함께 남긴다. */
    val calls: List<ExternalCall> = inputCalls.map { call ->
        callTargets[call]?.let { target ->
            call.copy(resolvedTargets = listOf(target), resolution = CallResolution.PROJECT_CANDIDATES)
        } ?: call
    }

    private fun inheritedCallEdges(classFacts: List<ClassFacts>): List<GraphEdge> {
        // 복원할 대상이 없으면 호출·간선을 재탐색할 필요가 없다. 필드 복원은 별도로 계속한다.
        if (callTargets.isEmpty()) return emptyList()
        val relevant = callTargets.keys.mapTo(mutableSetOf()) { it.caller to it.target }
        val observations = inputCalls.filter { it.caller to it.target in relevant }.groupBy { it.caller to it.target }
        val rawEdges = classFacts.asSequence().flatMap { it.edges.asSequence() }
            .filter { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE && it.source to it.target in relevant }
            .groupBy { it.source to it.target }
        return observations.flatMap { (key, observedCalls) ->
            val observedEdges = rawEdges[key].orEmpty()
            // visitor와 class cache는 두 목록의 명령 순서를 보존한다. 매칭을 확인하지 못하면 줄을 추측하지 않는다.
            val aligned = observedEdges.size == observedCalls.size && observedEdges.all { it.weight == 1 }
            observedCalls.mapIndexedNotNull { index, call ->
                callTargets[call]?.let { target -> GraphEdge(call.caller, target, EdgeKind.CALL,
                    callSiteLines = if (aligned) observedEdges[index].callSiteLines else emptyList()) }
            }
        }
    }

    private fun isExactCall(call: ExternalCall): Boolean {
        if (call.kind !in EXACT_KINDS || call.name.startsWith('<')) return false
        if (call.kind != InvocationKind.SPECIAL) return true
        val callerOwner = call.caller.value.removePrefix("method:").substringBefore('#')
        // ACC_SUPER가 별도로 보존되지 않으므로 직접 superclass operand만 정확한 호출로 복원한다.
        return call.owner in types[callerOwner]?.supertypes.orEmpty()
    }

    private fun resolveMethod(call: ExternalCall): NodeId? {
        var owner = call.owner
        val seen = mutableSetOf<String>()
        while (seen.add(owner)) {
            val type = types[owner]?.takeUnless { it.kind in INTERFACE_KINDS } ?: return null
            val id = JvmNodeId.methodId(owner, call.name, call.descriptor)
            nodes[id]?.let { method ->
                // 첫 선언이 조건에 맞지 않아도 그보다 먼 선언으로 건너뛰지 않는다.
                if (method.jvmVisibility in PRIVATE_VISIBILITIES || JvmModifier.ABSTRACT in method.jvmModifiers ||
                    (JvmModifier.STATIC in method.jvmModifiers) != (call.kind == InvocationKind.STATIC)) return null
                return id
            }
            owner = type.supertypes.singleOrNull { types[it]?.kind !in INTERFACE_KINDS } ?: return null
        }
        return null
    }

    private fun resolveField(id: NodeId): FieldLookup {
        val pending = ArrayDeque(listOf(id to false))
        val active = mutableSetOf<NodeId>()
        while (pending.isNotEmpty()) {
            val (current, expanded) = pending.removeLast()
            if (current in fields) continue
            val owner = current.value.removePrefix("field:").substringBefore('#')
            val signature = current.value.substringAfter('#')
            val type = types[owner]
            val field = nodes[current]
            when {
                field != null -> fields[current] = if (field.jvmVisibility in PRIVATE_VISIBILITIES) FieldLookup.UNKNOWN else FieldLookup(field.id)
                type == null || type.supertypes.any { it !in types } -> fields[current] = FieldLookup.UNKNOWN
                expanded -> {
                    val interfaces = type.supertypes.filter { types.getValue(it).kind in INTERFACE_KINDS }
                        .map { fields.getValue(NodeId("field:$it#$signature")) }
                    val candidates = interfaces.mapNotNull(FieldLookup::target).distinct()
                    val parents = type.supertypes.filter { types.getValue(it).kind !in INTERFACE_KINDS }
                    fields[current] = when {
                        interfaces.any { !it.complete } || candidates.size > 1 -> FieldLookup.UNKNOWN
                        candidates.size == 1 -> FieldLookup(candidates.single())
                        parents.size > 1 -> FieldLookup.UNKNOWN
                        else -> parents.singleOrNull()?.let { fields.getValue(NodeId("field:$it#$signature")) } ?: FieldLookup(null)
                    }
                    active.remove(current)
                }
                !active.add(current) -> fields[current] = FieldLookup.UNKNOWN
                else -> {
                    // 명시적 stack으로 깊은 hierarchy도 JVM stack 한계 없이 처리한다.
                    pending.addLast(current to true)
                    type.supertypes.sorted().forEach { pending.addLast(NodeId("field:$it#$signature") to false) }
                }
            }
        }
        return fields.getValue(id)
    }

    private data class FieldLookup(val target: NodeId?, val complete: Boolean = true) {
        companion object { val UNKNOWN = FieldLookup(null, false) }
    }

    private companion object {
        val INTERFACE_KINDS = setOf(NodeKind.INTERFACE, NodeKind.ANNOTATION_CLASS)
        val PRIVATE_VISIBILITIES = setOf(Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS)
        val EXACT_KINDS = setOf(InvocationKind.STATIC, InvocationKind.SPECIAL)
    }
}
