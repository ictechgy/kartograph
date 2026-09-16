package dev.kartograph.index

import dev.kartograph.core.CallResolution
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/** 외부 선언을 앱 정점으로 수입하지 않고 제공된 상속 사실에서 가능한 구현 간선을 만든다. */
internal object ExternalDispatchIndexer {
    fun enrich(graph: CodeGraph, hierarchy: ClassHierarchy): CodeGraph {
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
        val targetCache = mutableMapOf<dev.kartograph.core.NodeId, Pair<List<dev.kartograph.core.NodeId>, CallResolution>>()
        val derived = mutableListOf<GraphEdge>()
        val calls = graph.externalCalls.map { call ->
            if (call.kind !in VIRTUAL_KINDS || call.resolution == CallResolution.RUNTIME_MODEL) return@map call
            val (targets, resolution) = targetCache.getOrPut(call.target) {
                val declaration = hierarchy.declaredMethodsOf(call.owner)?.firstOrNull { it.name == call.name && it.descriptor == call.descriptor }
                if (declaration?.isFinal == true || declaration?.isStatic == true || declaration?.visibility == Visibility.PRIVATE ||
                    (call.owner == "java/lang/Object" && call.name in FINAL_OBJECT_METHODS)) {
                    emptyList<dev.kartograph.core.NodeId>() to CallResolution.NO_PROJECT_TARGET
                } else {
                    val matching = subtypes[call.owner].orEmpty()
                    val candidates = matching.flatMap { type ->
                        ancestorsOf(type).filter(types::containsKey).mapNotNull { owner ->
                            val id = JvmNodeId.methodId(owner, call.name, call.descriptor)
                            val method = graph.node(id) ?: return@mapNotNull null
                            if (method.kind !in METHOD_KINDS || method.jvmVisibility == Visibility.PRIVATE ||
                                JvmModifier.STATIC in method.jvmModifiers || JvmModifier.ABSTRACT in method.jvmModifiers) return@mapNotNull null
                            if (declaration?.visibility == Visibility.PACKAGE_PRIVATE && owner.substringBeforeLast('/', "") != call.owner.substringBeforeLast('/', "")) return@mapNotNull null
                            id
                        }
                    }.distinct().sorted()
                    candidates to when {
                        candidates.isNotEmpty() -> CallResolution.PROJECT_CANDIDATES
                        matching.isEmpty() -> CallResolution.NO_PROJECT_TARGET
                        else -> CallResolution.UNRESOLVED
                    }
                }
            }
            targets.forEach { derived += GraphEdge(call.caller, it, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL) }
            call.copy(resolvedTargets = targets, resolution = resolution)
        }
        return CodeGraph(graph.nodes.values, graph.edges.filter { it.origin != EdgeOrigin.DISPATCH_MODEL } + derived, calls, graph.serviceProviders)
    }

    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
    private val METHOD_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
    private val VIRTUAL_KINDS = setOf(InvocationKind.VIRTUAL, InvocationKind.INTERFACE)
    private val FINAL_OBJECT_METHODS = setOf("getClass", "wait", "notify", "notifyAll")
}
