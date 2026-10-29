package dev.kartograph.index

import dev.kartograph.core.*

// 9b9082의 개별 호출별 전개를 고정한 테스트 전용 비교 구현이다.
internal object LegacyDispatchExpansion {
    fun enrich(graph: CodeGraph, hierarchy: ClassHierarchy): CodeGraph = enrichBounded(graph, hierarchy, null).graph

    /** 후보를 한도 넘게 모으지 않으며 초과한 선언은 일부 후보로 완전성을 가장하지 않는다. */
    fun enrichBounded(graph: CodeGraph, hierarchy: ClassHierarchy, maximumCandidates: Int?,
        projectCalls: List<dev.kartograph.core.ExternalCall> = emptyList()): DispatchEnrichment {
        val types = graph.nodes.values.filter { it.kind in TYPE_KINDS && it.jvmSignature != null }
            .associateBy { requireNotNull(it.jvmSignature) }
        val ancestors = mutableMapOf<String, Set<String>>()
        fun ancestorsOf(name: String): Set<String> = ancestors.getOrPut(name) {
            val found = linkedSetOf<String>()
            val pending = ArrayDeque(listOf(name))
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!found.add(current)) continue
                val supers = types[current]?.supertypes ?: hierarchy.directSupertypesOf(current).orEmpty()
                pending.addAll(supers.sorted())
            }
            if (name != "java/lang/Object") found += "java/lang/Object"
            found
        }
        val subtypes = mutableMapOf<String, MutableList<String>>()
        types.keys.forEach { type -> ancestorsOf(type).forEach { parent -> subtypes.getOrPut(parent) { mutableListOf() }.add(type) } }
        fun isInterface(owner: String): Boolean? = types[owner]?.let { it.kind in INTERFACE_KINDS } ?: hierarchy.isInterface(owner)
        val targetCache = mutableMapOf<dev.kartograph.core.NodeId, LegacyDispatchTargets>()
        var overLimitCalls = 0
        val derived = mutableListOf<GraphEdge>()
        // CodeGraph는 내부 target 호출을 externalCalls에서 제거한다. assembly의 원래 opcode 사실을 별도로 받는다.
        val internalVirtualCalls = projectCalls.filter { call ->
            if (call.kind !in VIRTUAL_KINDS) return@filter false
            val method = graph.node(call.target) ?: return@filter false
            method.jvmVisibility != Visibility.PRIVATE &&
                JvmModifier.FINAL !in method.jvmModifiers && JvmModifier.STATIC !in method.jvmModifiers &&
                JvmModifier.FINAL !in types[call.owner]?.jvmModifiers.orEmpty()
        }
        val calls = (graph.externalCalls + internalVirtualCalls).map { call ->
            if (call.kind !in VIRTUAL_KINDS || call.resolution == CallResolution.RUNTIME_MODEL) return@map call
            val callTarget = call.target
            val resolved = targetCache.getOrPut(callTarget) {
                // 프로젝트 선언을 먼저 확인한다. 외부 hierarchy에는 앱 메서드가 들어 있지 않다.
                fun declaredMethod(owner: String): HierarchyMethod? {
                    if (owner !in types) return hierarchy.declaredMethodsOf(owner)
                        ?.firstOrNull { it.name == call.name && it.descriptor == call.descriptor }
                    val method = graph.node(JvmNodeId.methodId(owner, call.name, call.descriptor)) ?: return null
                    return HierarchyMethod(call.name, call.descriptor, method.jvmVisibility,
                        JvmModifier.STATIC in method.jvmModifiers, JvmModifier.FINAL in method.jvmModifiers)
                }
                val declarations = ancestorsOf(call.owner).mapNotNull { owner -> declaredMethod(owner)?.let { owner to it } }
                // class 선언은 인터페이스보다 먼저 해석하며 더 가까운 선언을 건너뛰지 않는다.
                val declared = declarations.firstOrNull { it.first == call.owner }
                    ?: declarations.firstOrNull { isInterface(it.first) == false }
                    ?: declarations.firstOrNull()
                // kind가 없는 legacy/partial header는 class shadow로 확정하지 않는다.
                var incompleteKinds = declarations.any { isInterface(it.first) == null }
                val declarationOwner = declared?.first ?: call.owner
                val declaration = declared?.second
                fun visibleOverride(owner: String): Boolean {
                    if (declaration?.visibility != Visibility.PACKAGE_PRIVATE || owner == declarationOwner) return true
                    val ownerAncestors = ancestorsOf(owner)
                    if (declarationOwner !in ownerAncestors) return false
                    val declarationPackage = declarationOwner.substringBeforeLast('/', "")
                    if (owner.substringBeforeLast('/', "") == declarationPackage) return true
                    // 같은 package의 공개/protected override를 거쳐 다른 package로 이어지는 JVM override도 허용한다.
                    return ownerAncestors.any { intermediate ->
                        intermediate != declarationOwner && intermediate.substringBeforeLast('/', "") == declarationPackage &&
                            declarationOwner in ancestorsOf(intermediate) && declaredMethod(intermediate)?.let {
                                !it.isStatic && it.visibility in setOf(Visibility.PUBLIC, Visibility.PROTECTED)
                            } == true
                    }
                }
                if (declaration?.isFinal == true || declaration?.isStatic == true || declaration?.visibility == Visibility.PRIVATE ||
                    (call.owner == "java/lang/Object" && call.name in FINAL_OBJECT_METHODS)) {
                    // 상속 operand로 호출한 final 프로젝트 구현은 남기되 override 후보는 만들지 않는다.
                    val exact = if (declaration?.isFinal == true && !declaration.isStatic && declarationOwner in types)
                        listOf(JvmNodeId.methodId(declarationOwner, call.name, call.descriptor)) else emptyList()
                    LegacyDispatchTargets(exact, when {
                        incompleteKinds -> CallResolution.UNRESOLVED
                        exact.isEmpty() -> CallResolution.NO_PROJECT_TARGET
                        else -> CallResolution.PROJECT_CANDIDATES
                    })
                } else {
                    val matching = subtypes[call.owner].orEmpty()
                    val candidates = linkedSetOf<dev.kartograph.core.NodeId>()
                    var overLimit = false
                    // 외부 정적 수신 타입도 프로젝트 superclass body를 그대로 상속할 수 있다.
                    candidateLoop@ for (type in (matching + call.owner).distinct()) {
                        val owners = ancestorsOf(type)
                        if (owners.any { isInterface(it) == null && declaredMethod(it) != null }) incompleteKinds = true
                        // 가장 가까운 class 선언이 외부 body여도 더 먼 프로젝트 구현으로 건너뛰지 않는다.
                        val classBody = owners.firstOrNull { owner ->
                            isInterface(owner) == false && declaredMethod(owner)?.let {
                                !it.isStatic && it.visibility != Visibility.PRIVATE && visibleOverride(owner)
                            } == true
                        }
                        val bodyOwners = if (classBody != null) listOf(classBody) else {
                            val interfaces = owners.filter { owner ->
                                isInterface(owner) == true && declaredMethod(owner)?.let {
                                    !it.isStatic && it.visibility != Visibility.PRIVATE && visibleOverride(owner)
                                } == true
                            }
                            // 최대 특수 인터페이스 선언만 남긴다. 무관한 abstract 선언은 default를 가리지 않는다.
                            interfaces.filter { owner -> interfaces.none { other -> other != owner && owner in ancestorsOf(other) } }
                        }
                        for (owner in bodyOwners) {
                            if (owner !in types) continue
                            val id = JvmNodeId.methodId(owner, call.name, call.descriptor)
                            val method = graph.node(id) ?: continue
                            if (method.kind !in METHOD_KINDS || JvmModifier.ABSTRACT in method.jvmModifiers) continue
                            candidates += id
                            if (maximumCandidates != null && candidates.size > maximumCandidates) {
                                overLimit = true
                                break@candidateLoop
                            }
                        }
                    }
                    LegacyDispatchTargets(if (overLimit) emptyList() else candidates.sorted(), when {
                        overLimit || incompleteKinds -> CallResolution.UNRESOLVED
                        candidates.isNotEmpty() -> CallResolution.PROJECT_CANDIDATES
                        matching.isEmpty() -> CallResolution.NO_PROJECT_TARGET
                        else -> CallResolution.UNRESOLVED
                    }, overLimit)
                }
            }
            if (resolved.overLimit) overLimitCalls++
            resolved.targets.forEach { target ->
                if (target != callTarget) {
                    derived += GraphEdge(call.caller, target, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)
                }
            }
            call.copy(resolvedTargets = resolved.targets, resolution = resolved.resolution)
        }
        return DispatchEnrichment(graph.enrichedWith(derived, calls, replacingOrigin = EdgeOrigin.DISPATCH_MODEL), overLimitCalls)
    }

    private val INTERFACE_KINDS = setOf(NodeKind.INTERFACE, NodeKind.ANNOTATION_CLASS)
    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
    private val METHOD_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
    private val VIRTUAL_KINDS = setOf(InvocationKind.VIRTUAL, InvocationKind.INTERFACE)
    private val FINAL_OBJECT_METHODS = setOf("getClass", "wait", "notify", "notifyAll")
}

private data class LegacyDispatchTargets(val targets: List<NodeId>, val resolution: CallResolution, val overLimit: Boolean = false)
