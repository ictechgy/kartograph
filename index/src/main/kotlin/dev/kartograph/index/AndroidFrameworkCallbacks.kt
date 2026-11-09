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
import dev.kartograph.core.HierarchyMethod

/**
 * 실제 framework header의 callback을 재정의한 프로젝트 구현만 소유 타입에서 연결한다.
 * Android 명시 목록 외에도, 정확한 header가 있는 외부(라이브러리) 상위 타입 메서드의 재정의는 모두 소유 타입에서 잇는다.
 */
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
        // 명시 framework 콜백으로 이미 이은 재정의는 라이브러리 후보 간선으로 한 번 더 잇지 않는다(출처만 다른 중복 간선 방지).
        val connected = derived.mapTo(HashSet()) { it.source to it.target }
        libraryOverrideEdges(types, methods, hierarchy, ::ancestorsOf).forEach { edge ->
            if (connected.add(edge.source to edge.target)) derived += edge
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

    /**
     * 외부(라이브러리) 상위 타입이 선언한 재정의 가능 메서드를 같은 JVM 시그니처로 재정의한 프로젝트 메서드를 소유 타입에서 잇는다.
     *
     * `Thread`가 부르는 `Runnable.run`, `sortedWith`가 부르는 `Comparator.compare`처럼 호출이 라이브러리 body 안에 있어
     * bytecode에 보이지 않는 진입점이다. 소유 타입이 도달할 때만 메서드가 도달하므로 쓰이지 않는 구현은 그대로 보고된다.
     * 정확한 header가 있는 외부 선언만 쓰고(header가 없으면 [missingLibraryHeaderTypes]가 센다), static·final·private·
     * package-private 선언과 생성자는 재정의 계약이 아니라 제외한다.
     */
    private fun libraryOverrideEdges(
        types: Map<String, GraphNode>,
        methods: Map<String?, List<GraphNode>>,
        hierarchy: ClassHierarchy,
        ancestorsOf: (GraphNode) -> Set<String>,
    ): List<GraphEdge> {
        val contractsByOwner = mutableMapOf<String, Set<String>>()
        fun contractsOf(owner: String): Set<String> = contractsByOwner.getOrPut(owner) {
            hierarchy.declaredMethodsOf(owner).orEmpty().filter { it.isOverridableContract() }
                .mapTo(mutableSetOf()) { it.name + it.descriptor }
        }
        return types.entries.sortedBy { it.key }.flatMap { (name, type) ->
            // 타입마다 상위 계약 전체를 합치지 않는다. Android 상위 타입은 수백 개 메서드를 선언하므로, 후보 메서드 몇 개만
            // owner별로 캐시한 계약 집합에 대조한다.
            val candidates = methods[name].orEmpty().filter { it.canImplement() && it.kind == NodeKind.METHOD }
            if (candidates.isEmpty()) return@flatMap emptyList()
            val external = ancestorsOf(type).filter { it !in types }
            // 그래프의 supertypes는 java/lang/Object를 생략한다. collection·문자열 연결·logging이 부르는 Object 계약은 JVM 고정 사실이다.
            val objectContracts = type.kind != NodeKind.INTERFACE && type.kind != NodeKind.ANNOTATION_CLASS
            candidates.filter { method ->
                val signature = method.jvmSignature?.substringAfter('#') ?: return@filter false
                (objectContracts && signature in OBJECT_CONTRACTS) || external.any { owner -> signature in contractsOf(owner) }
            }.map { method -> GraphEdge(type.id, method.id, EdgeKind.REFERENCE, origin = EdgeOrigin.LIBRARY_CALLBACK_MODEL) }
        }
    }

    /**
     * header가 없는 외부 상위 타입을 가진 프로젝트 타입 수다. 그 타입들의 라이브러리 callback 재정의는 잇지 못하므로
     * dead 한계로 센다. 합성 타입(람다·생성물)은 metadata 경로로 따로 이어지므로 세지 않는다.
     */
    fun missingLibraryHeaderTypes(graph: CodeGraph, hierarchy: ClassHierarchy): Int {
        val types = graph.nodes.values.filter { it.jvmSignature?.contains('#') == false && NodeAttribute.EXTERNAL_STUB !in it.attributes }
            .associateBy { requireNotNull(it.jvmSignature) }
        val missing = mutableMapOf<String, Boolean>()
        fun hasMissingAncestor(start: GraphNode): Boolean {
            val pending = ArrayDeque(start.supertypes)
            val seen = mutableSetOf<String>()
            while (pending.isNotEmpty() && seen.size < 256) {
                val name = pending.removeFirst()
                if (!seen.add(name)) continue
                val project = types[name]
                if (project != null) { pending.addAll(project.supertypes); continue }
                if (missing.getOrPut(name) { hierarchy.directSupertypesOf(name) == null || hierarchy.declaredMethodsOf(name) == null }) return true
                pending.addAll(hierarchy.directSupertypesOf(name).orEmpty())
            }
            return false
        }
        return types.values.count { type -> !type.synthesized && hasMissingAncestor(type) }
    }

    /** 모든 class가 재정의할 수 있는 `java/lang/Object`의 비final 인스턴스 메서드다. */
    private val OBJECT_CONTRACTS = setOf(
        "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;", "finalize()V", "clone()Ljava/lang/Object;",
    )

    private fun HierarchyMethod.isOverridableContract(): Boolean =
        !isStatic && !isFinal && visibility in setOf(Visibility.PUBLIC, Visibility.PROTECTED) && name != "<init>" && name != "<clinit>"

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
        // 라이브러리 콜백 간선은 이 단계만 만든다. 같은 방식으로 이 단계가 더한 가중치만 되돌린다.
        val withoutLibrary = remainingAfter(graph, EdgeOrigin.LIBRARY_CALLBACK_MODEL)
            .let { graph.enrichedWith(it, graph.externalCalls, replacingOrigin = EdgeOrigin.LIBRARY_CALLBACK_MODEL) }
        return removeRuntimeModel(withoutLibrary)
    }

    private fun remainingAfter(graph: CodeGraph, origin: EdgeOrigin): List<GraphEdge> =
        graph.edges.asSequence().filter { it.origin == origin }.mapNotNull { edge ->
            val removed = edgeWeights[if (edge.weight == 1) edge else edge.copy(weight = 1)] ?: 0
            when {
                removed == 0 -> edge
                edge.weight > removed -> edge.copy(weight = edge.weight - removed)
                else -> null
            }
        }.toList()

    private fun removeRuntimeModel(graph: CodeGraph): CodeGraph {
        val remaining = remainingAfter(graph, EdgeOrigin.RUNTIME_MODEL)
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
