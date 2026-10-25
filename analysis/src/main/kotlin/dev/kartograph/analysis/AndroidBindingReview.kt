package dev.kartograph.analysis

import dev.kartograph.core.*

/** XML 호환 후보와 독립적인 compiled 호출 근거다. 둘의 공존은 XML→생성 메서드 대응 증명이 아니다. */
public data class AndroidBindingCandidate(
    val adapter: AndroidBindingAdapter,
    val element: AndroidBindingElement,
    val attributeMatch: String,
    val viewMatch: String,
    val overloadCount: Int,
    val compiledCallers: List<NodeId>,
    val reachedCallers: List<NodeId>,
)

public data class AndroidBindingReviewResult(
    val candidates: List<AndroidBindingCandidate>,
    val unresolvedAdapters: Set<NodeId>,
    val diagnostics: List<String>,
    val truncated: Boolean,
    val unresolvedOwners: Set<NodeId> = emptySet(),
    val uncertainty: AndroidBindingUncertainty = AndroidBindingUncertainty(),
) {
    /** callback 자체와 관측 호출 후보를 검토 대상으로 남기며 실제 retention은 바꾸지 않는다. */
    public fun requiresReview(id: NodeId): Boolean = id in unresolvedAdapters || id in unresolvedOwners || uncertainty.requiresReview(id)
}

/** 부재·overload·overlay를 보수적으로 설명하며 그래프나 보존 root를 바꾸지 않는다. */
public object AndroidBindingReview {
    /** 일반 JVM 입력에는 새 BFS 비용을 추가하지 않는다. 이미 계산한 도달성은 아래 overload로 재사용한다. */
    public fun analyze(
        graph: CodeGraph, hierarchy: ClassHierarchy, observations: AndroidBindingObservations,
        retention: Iterable<RetentionEvidence>,
    ): AndroidBindingReviewResult {
        if (graph.nodes.values.none { node -> node.annotations.any(ANNOTATIONS::contains) })
            return AndroidBindingReviewResult(emptyList(), emptySet(), emptyList(), false)
        return analyze(graph, hierarchy, observations, ReachabilityAnalyzer.analyze(graph, retention))
    }

    public fun analyze(
        graph: CodeGraph, hierarchy: ClassHierarchy, observations: AndroidBindingObservations,
        reachability: ReachabilityResult, limit: Int = 1_000,
        uncertaintyNodeBudget: Int = AndroidBindingUncertainty.DEFAULT_NODE_BUDGET,
        uncertaintyEdgeBudget: Int = AndroidBindingUncertainty.DEFAULT_EDGE_BUDGET,
    ): AndroidBindingReviewResult {
        require(limit in 1..10_000) { "binding report limit must be between 1 and 10000" }
        require(observations.overlayOrder.distinct().size == observations.overlayOrder.size &&
            observations.roots.containsAll(observations.overlayOrder)) { "overlay order requires unique declared resource roots" }
        val allAdapters = graph.nodes.values.filter { node -> node.annotations.any { it in ANNOTATIONS } }.mapTo(linkedSetOf()) { it.id }
        val unresolved = linkedSetOf<NodeId>()
        val rows = ArrayList<AndroidBindingCandidate>()
        val diagnostics = ArrayList<String>()
        if (allAdapters.isEmpty()) return AndroidBindingReviewResult(emptyList(), emptySet(), emptyList(), false)
        val missing = allAdapters - observations.adapters.map { it.method }.toSet()
        unresolved += missing
        diagnostics += observations.limitations.map { "$it: annotation/XML evidence is incomplete; no runtime selection or deletion permission is inferred" }
        if (missing.isNotEmpty()) diagnostics += "data-binding-annotation-unavailable: ${missing.size} adapter declaration(s) have no complete captured annotation values"
        if (observations.limitations.isNotEmpty()) unresolved += allAdapters
        val order = observations.overlayOrder.withIndex().associate { it.value to it.index }
        val winners = observations.layouts.groupBy { it.resourcePath }.mapValues { (_, layouts) -> layouts.mapNotNull { order[it.root] }.maxOrNull() }
        val elements = if (order.isEmpty()) observations.elements else observations.elements.groupBy { it.resourcePath }.flatMap { (_, definitions) ->
            // 설정 qualifier가 다른 정의는 같은 key가 아니다. winner는 지정한 roots에만 적용한다.
            val winner = winners[definitions.first().resourcePath]
            definitions.filter { it.root !in order || order[it.root] == winner }
        }
        val byAttribute = observations.adapters.flatMap { adapter -> adapter.attributes.distinct().map { it to adapter } }
            .groupBy({ it.first }, { it.second })
        val calls = observations.adapters.associate { adapter -> adapter.method to graph.incomingEdgesTo(adapter.method)
            .filter { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE }.map { it.source }.distinct().sorted() }
        // XML 후보 부재는 runtime 부재 증명이 아니다. 관측되지 않은 scope와 미사용 속성을 구분해 보존한다.
        unresolved += allAdapters.filter { id -> calls[id].orEmpty().none { it in reachability.reachableNodeIds } }
        if (observations.roots.isEmpty()) diagnostics += "data-binding-resource-scope-unobserved: no complete resource root was observed; adapter selection remains unknown"
        else if (observations.layouts.isEmpty()) diagnostics += "data-binding-layouts-unobserved: supplied roots contain no captured layout definitions; adapter selection remains unknown"
        var examined = 0
        var truncated = false
        for (element in elements.sortedWith(compareBy({ it.location.path }, { it.ordinal }))) {
            val adapters = element.boundAttributes.flatMap { byAttribute[it].orEmpty() }.distinctBy { it.method }.sortedBy { it.method }
            for (adapter in adapters) {
                if (++examined > 200_000) { truncated = true; break }
                val matched = adapter.attributes.count(element.boundAttributes::contains)
                val attributeMatch = if (adapter.requireAll && matched != adapter.attributes.size) "missingRequiredAttributes"
                    else if (adapter.requireAll) "allAttributesPresent" else "someAttributesPresent"
                val view = viewMatch(graph, hierarchy, element.viewType, adapter.viewType)
                val eligible = attributeMatch != "missingRequiredAttributes" && (view != "incompatible" || !adapter.supportedSignature)
                val callers = calls[adapter.method].orEmpty()
                val reached = callers.filter { it in reachability.reachableNodeIds }
                if (eligible && reached.isEmpty()) unresolved += adapter.method
                if (!adapter.supportedSignature && eligible) unresolved += adapter.method
                if (rows.size >= limit) { truncated = true; continue }
                rows += AndroidBindingCandidate(adapter, element, attributeMatch, view,
                    adapters.count { other -> other.attributes.toSet() == adapter.attributes.toSet() }, callers, reached)
            }
            if (examined > 200_000) break
        }
        if (truncated) {
            unresolved += allAdapters
            diagnostics += "data-binding-evidence-truncated: limit=$limit, pairBudget=200000; omitted candidates remain unknown"
        }
        for (row in rows.filter { it.adapter.method in unresolved && it.attributeMatch != "missingRequiredAttributes" &&
                (it.viewMatch != "incompatible" || !it.adapter.supportedSignature) }.take(64)) {
            diagnostics += "data-binding-xml-candidate: ${row.adapter.method.value} at ${row.element.location.path}:${row.element.location.line}; " +
                "${row.attributeMatch}, view=${row.viewMatch}, overloads=${row.overloadCount}; runtime selection unknown; " +
                (if (row.reachedCallers.isEmpty()) "no reached compiled caller observed" else "compiled caller does not establish XML linkage")
        }
        if (unresolved.isNotEmpty()) diagnostics += "data-binding-unresolved: ${unresolved.size} adapter declaration(s) require review; XML candidates do not create retention roots"
        val matchedIds = rows.filter { it.attributeMatch != "missingRequiredAttributes" }.mapTo(hashSetOf()) { it.adapter.method }
        if (!truncated && observations.roots.isNotEmpty()) for (id in (unresolved - matchedIds).sorted().take(64)) {
            diagnostics += "data-binding-no-matching-expression-observed: ${id.value}; supplied XML does not prove runtime absence or a missing compiled callback"
        }
        diagnostics += "data-binding-scope: resources=${observations.roots.size}; overlay=" +
            (if (order.isEmpty()) "union-unresolved" else "explicit-input-order-assertion") +
            "; XML-to-generated-method linkage, conversions, device configuration and runtime invocation are not proven"
        val uncertainty = AndroidBindingUncertainty.collect(graph, unresolved, uncertaintyNodeBudget, uncertaintyEdgeBudget)
        val owners = uncertainty.ownerMembers.filterValues { it in unresolved }.keys
        if (unresolved.isNotEmpty()) diagnostics += "data-binding-call-uncertainty: ${uncertainty.steps.size} declaration(s), " +
            "${uncertainty.ownerMembers.size} owner(s) have unresolved adapter / BYTECODE CALL evidence; " +
            "only confidence is lowered, no retention roots are added; modeled dispatch, field dependencies and unobserved runtime paths are not proven"
        if (uncertainty.truncated) diagnostics += "data-binding-call-uncertainty-truncated: nodeBudget=${uncertainty.nodeBudget}, " +
            "edgeBudget=${uncertainty.edgeBudget}, examinedEdges=${uncertainty.examinedEdges}; all unreachable findings require review " +
            "because omitted paths remain unknown; declarations without observed paths are not confirmed call candidates"
        return AndroidBindingReviewResult(rows, unresolved, diagnostics, truncated || uncertainty.truncated, owners, uncertainty)
    }

    private fun viewMatch(graph: CodeGraph, hierarchy: ClassHierarchy, actual: String?, expected: String?): String {
        if (actual == null || expected == null) return "unknown"
        val pending = ArrayDeque<String>()
        pending += actual
        val seen = HashSet<String>()
        var unknown = false
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!seen.add(type)) continue
            if (seen.size > 1024) return "unknown"
            if (type == expected) return "compatible"
            if (type == "java/lang/Object") continue
            val supers = graph.nodes[NodeId("class:$type")]?.supertypes ?: hierarchy.directSupertypesOf(type)
            if (supers == null) unknown = true else pending.addAll(supers)
        }
        return if (unknown) "unknown" else "incompatible"
    }

    private val ANNOTATIONS = setOf("androidx/databinding/BindingAdapter", "android/databinding/BindingAdapter")
}
