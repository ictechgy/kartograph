package dev.kartograph.index

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.CallResolution
import dev.kartograph.core.ExternalCall

/** 실제 framework header의 callback을 재정의한 프로젝트 구현만 소유 타입에서 연결한다. */
internal object AndroidFrameworkCallbacks {
    fun enrich(graph: CodeGraph, hierarchy: ClassHierarchy): AndroidFrameworkEnrichment {
        val types = graph.nodes.values.filter { it.jvmSignature?.contains('#') == false && NodeAttribute.EXTERNAL_STUB !in it.attributes }
            .associateBy { requireNotNull(it.jvmSignature) }
        val methods by lazy(LazyThreadSafetyMode.NONE) {
            graph.nodes.values.filter { it.kind == NodeKind.METHOD }.groupBy { it.jvmSignature?.substringBefore('#') }
        }
        val ancestors = mutableMapOf<String, Set<String>>()
        fun ancestorsOf(type: GraphNode): Set<String> = ancestors.getOrPut(requireNotNull(type.jvmSignature)) {
            val pending = ArrayDeque(type.supertypes)
            val seen = linkedSetOf<String>()
            while (pending.isNotEmpty() && seen.size < 256) {
                val name = pending.removeFirst()
                if (!seen.add(name)) continue
                pending.addAll(types[name]?.supertypes ?: hierarchy.directSupertypesOf(name).orEmpty())
            }
            // 손상되거나 지나치게 깊은 상속은 부분 증거로 callback을 확정하지 않는다.
            if (pending.isEmpty()) seen else emptySet()
        }
        val derived = mutableListOf<GraphEdge>()
        val originalCalls = mutableListOf<ExternalCall>()
        for ((name, type) in types) {
            val contracts = ancestorsOf(type).filter { it in CALLBACK_NAMES }
            if (contracts.isEmpty()) continue
            val signatures = contracts.flatMap { owner ->
                val allowedNames = CALLBACK_NAMES.getValue(owner)
                val project = types[owner]
                if (project != null) methods[owner].orEmpty().filter { it.name in allowedNames && it.canOverrideContract() }
                    .mapNotNull { it.jvmSignature?.substringAfter('#') }
                else hierarchy.declaredMethodsOf(owner).orEmpty().filter {
                    it.name in allowedNames && it.visibility in setOf(Visibility.PUBLIC, Visibility.PROTECTED) && !it.isStatic && !it.isFinal
                }.map { it.name + it.descriptor }
            }.toSet()
            methods[name].orEmpty().filter { it.canImplement() && it.jvmSignature?.substringAfter('#') in signatures }
                .forEach { method -> derived += GraphEdge(type.id, method.id, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL) }
        }
        // DataBindingUtil의 정적 API는 앱의 정확한 root mapper 이름을 통해 생성된 mapper 체인에 진입한다.
        // layout ID의 분기를 선택하지 않으므로 해당 체인에 기록된 모든 binding은 보수적 후보로 남는다.
        val rootMapper = types[ROOT_MAPPER]?.takeIf {
            "androidx/databinding/MergedDataBinderMapper" in ancestorsOf(it) &&
                "androidx/databinding/DataBinderMapper" in ancestorsOf(it)
        }
        val rootConstructor = rootMapper?.let { graph.node(JvmNodeId.methodId(ROOT_MAPPER, "<init>", "()V")) }
        val calls = if (rootMapper == null || rootConstructor == null) graph.externalCalls else graph.externalCalls.map { call ->
            if (call.kind != InvocationKind.STATIC || call.owner != "androidx/databinding/DataBindingUtil" ||
                call.name + call.descriptor !in BINDING_ENTRY_SIGNATURES) return@map call
            if (call.resolution == CallResolution.RUNTIME_MODEL) return@map call
            originalCalls += call
            derived += GraphEdge(call.caller, rootMapper.id, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL)
            derived += GraphEdge(call.caller, rootConstructor.id, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL)
            call.copy(resolvedTargets = listOf(rootMapper.id, rootConstructor.id).sorted(),
                resolution = CallResolution.RUNTIME_MODEL, model = "android-data-binding-mapper")
        }
        if (derived.isEmpty()) return AndroidFrameworkEnrichment(graph)
        return AndroidFrameworkEnrichment(graph.enrichedWith(derived, calls),
            AndroidFrameworkDerivation(derived.groupingBy { it }.eachCount(), originalCalls))
    }

    private fun GraphNode.canImplement(): Boolean = jvmVisibility in setOf(Visibility.PUBLIC, Visibility.PROTECTED) &&
        JvmModifier.STATIC !in jvmModifiers && JvmModifier.ABSTRACT !in jvmModifiers

    private fun GraphNode.canOverrideContract(): Boolean = jvmVisibility in setOf(Visibility.PUBLIC, Visibility.PROTECTED) &&
        JvmModifier.STATIC !in jvmModifiers && JvmModifier.FINAL !in jvmModifiers

    private val FRAGMENT_CALLBACKS = setOf("onAttach", "onCreate", "onCreateView", "onViewCreated", "onActivityCreated",
        "onViewStateRestored", "onStart", "onResume", "onPause", "onStop", "onDestroyView", "onDestroy", "onDetach",
        "onSaveInstanceState", "onConfigurationChanged", "onLowMemory", "onActivityResult", "onRequestPermissionsResult")
    private val BINDING_CALLBACKS = setOf("executeBindings", "onFieldChange", "invalidateAll", "hasPendingBindings", "setVariable")
    private val MAPPER_CALLBACKS = setOf("getDataBinder", "getLayoutId", "convertBrIdToString", "collectDependencies")
    private const val ROOT_MAPPER = "androidx/databinding/DataBinderMapperImpl"
    private val BINDING_ENTRY_SIGNATURES = setOf(
        "setContentView(Landroid/app/Activity;I)Landroidx/databinding/ViewDataBinding;",
        "setContentView(Landroid/app/Activity;ILandroidx/databinding/DataBindingComponent;)Landroidx/databinding/ViewDataBinding;",
        "inflate(Landroid/view/LayoutInflater;ILandroid/view/ViewGroup;Z)Landroidx/databinding/ViewDataBinding;",
        "inflate(Landroid/view/LayoutInflater;ILandroid/view/ViewGroup;ZLandroidx/databinding/DataBindingComponent;)Landroidx/databinding/ViewDataBinding;",
        "bind(Landroid/view/View;)Landroidx/databinding/ViewDataBinding;",
        "bind(Landroid/view/View;Landroidx/databinding/DataBindingComponent;)Landroidx/databinding/ViewDataBinding;",
    )
    private val CALLBACK_NAMES = mapOf(
        "android/app/Activity" to setOf("onCreate"),
        "androidx/recyclerview/widget/RecyclerView\$Adapter" to setOf("onBindViewHolder"),
        "android/view/View\$OnClickListener" to setOf("onClick"),
        "android/app/Fragment" to FRAGMENT_CALLBACKS,
        "androidx/fragment/app/Fragment" to FRAGMENT_CALLBACKS,
        "android/databinding/ViewDataBinding" to BINDING_CALLBACKS,
        "androidx/databinding/ViewDataBinding" to BINDING_CALLBACKS,
        "android/databinding/DataBinderMapper" to MAPPER_CALLBACKS,
        "androidx/databinding/DataBinderMapper" to MAPPER_CALLBACKS,
    )
}

internal data class AndroidFrameworkEnrichment(
    val graph: CodeGraph,
    val derivation: AndroidFrameworkDerivation = AndroidFrameworkDerivation(),
)

/** 같은 origin의 다른 모델을 지우지 않고 이 단계가 더한 가중치와 call 해석만 되돌린다. */
internal data class AndroidFrameworkDerivation(
    val edgeWeights: Map<GraphEdge, Int> = emptyMap(),
    val originalCalls: List<ExternalCall> = emptyList(),
) {
    fun removeFrom(graph: CodeGraph): CodeGraph {
        if (edgeWeights.isEmpty() && originalCalls.isEmpty()) return graph
        val remaining = graph.edges.asSequence().filter { it.origin == EdgeOrigin.RUNTIME_MODEL }.mapNotNull { edge ->
            val removed = edgeWeights[if (edge.weight == 1) edge else edge.copy(weight = 1)] ?: 0
            when {
                removed == 0 -> edge
                edge.weight > removed -> edge.copy(weight = edge.weight - removed)
                else -> null
            }
        }.toList()
        // compareTo의 키는 원래 호출 위치·operand이며 재계산된 resolution/declaration은 포함하지 않는다.
        val originals = java.util.TreeMap<ExternalCall, ExternalCall>()
        originalCalls.forEach { originals[it] = it }
        val calls = graph.externalCalls.map { call ->
            val original = originals[call]
            if (original == null || call.model != "android-data-binding-mapper") call else
                call.copy(resolvedTargets = original.resolvedTargets, resolution = original.resolution, model = original.model)
        }
        return graph.enrichedWith(remaining, calls, replacingOrigin = EdgeOrigin.RUNTIME_MODEL)
    }
}
