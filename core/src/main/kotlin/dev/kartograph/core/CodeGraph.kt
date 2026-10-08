package dev.kartograph.core

/**
 * 중복과 dangling edge를 정리하고 방향별 인접 목록을 한 번만 만드는 불변 코드 그래프다.
 * 분석 알고리즘이 전체 간선을 반복 스캔하지 않게 한다.
 */
public class CodeGraph private constructor(
    private val state: CanonicalState,
) {
    public constructor(
        nodes: Iterable<GraphNode>,
        edges: Iterable<GraphEdge>,
        externalCalls: Iterable<ExternalCall> = emptyList(),
        serviceProviders: Iterable<ServiceProviderRegistration> = emptyList(),
        enclosures: Iterable<LexicalEnclosure> = emptyList(),
        callbackArguments: Iterable<CallbackArgument> = emptyList(),
        parameterUses: Iterable<ParameterUse> = emptyList(),
        lambdaEscapes: Iterable<LambdaEscape> = emptyList(),
    ) : this(canonicalState(nodes, edges, externalCalls, serviceProviders, enclosures, callbackArguments, parameterUses, lambdaEscapes))

    public val nodes: Map<NodeId, GraphNode>
        get() = state.nodes

    /** 일반 간선과 분리해 보존한 외부 호출 목록이다. 앱 정점 수와 진단 대상에는 포함하지 않는다. */
    public val externalCalls: List<ExternalCall>
        get() = state.externalCalls

    /** 프로젝트 밖 provider도 누락된 입력을 설명할 수 있도록 선언 사실은 보존한다. */
    public val serviceProviders: List<ServiceProviderRegistration>
        get() = state.serviceProviders

    /**
     * 지역·익명 class의 어휘적 소속 사실이다. 양쪽 정점이 모두 그래프에 있는 것만 남긴다.
     * 일반 간선이 아니므로 도달성·dead·기존 impact 결과에는 쓰이지 않는다.
     */
    public val enclosures: List<LexicalEnclosure>
        get() = state.enclosures

    /**
     * 람다 값이 호출 인자로 넘어간 관측 사실이다. 넘긴 메서드와 람다 정점이 그래프에 있는 것만 남긴다.
     * 호출 대상은 가상 호출의 선언 owner일 수 있어 정점 존재를 요구하지 않는다. 일반 간선이 아니므로 도달성·dead·
     * 기존 impact 결과에는 쓰이지 않는다.
     */
    public val callbackArguments: List<CallbackArgument>
        get() = state.callbackArguments

    /** 콜백일 수 있는 파라미터의 관측된 쓰임이다. 파라미터를 가진 메서드가 그래프에 있는 것만 남긴다. */
    public val parameterUses: List<ParameterUse>
        get() = state.parameterUses

    /** 람다 값이 호출 인자가 아닌 방식으로 쓰인 관측 사실이다. 만든 메서드와 람다 정점이 그래프에 있는 것만 남긴다. */
    public val lambdaEscapes: List<LambdaEscape>
        get() = state.lambdaEscapes

    /** BYTECODE CALL과 분리해 보존한 compiler selector 위치다. 그래프 도달성·weight에는 참여하지 않는다. */
    public val locatedCompilerReferences: List<LocatedCompilerReference>
        get() = state.locatedCompilerReferences

    /** 완료된 v3 증거가 빈 결과까지 포함해 처리됐는지 구분한다. */
    public val compilerCallPositionsCaptured: Boolean
        get() = state.compilerCallPositionsCaptured

    public val edges: List<GraphEdge>
        get() = state.edges

    public val nodeIds: List<NodeId>
        get() = state.nodeIds

    public val nodeCount: Int
        get() = state.nodes.size

    public val edgeCount: Int
        get() = state.edges.size

    public val isEmpty: Boolean
        get() = state.nodes.isEmpty()

    /** 식별자와 일치하는 정점을 반환한다. */
    public fun node(id: NodeId): GraphNode? = state.nodes[id]

    /** 식별자가 그래프 안에 있는지 확인한다. */
    public fun contains(id: NodeId): Boolean = id in state.nodes

    /** 정점에서 나가는 간선을 결정적인 순서로 반환한다. */
    public fun outgoingEdgesFrom(id: NodeId): List<GraphEdge> = state.outgoing[id].orEmpty()

    /** 정점으로 들어오는 간선을 결정적인 순서로 반환한다. */
    public fun incomingEdgesTo(id: NodeId): List<GraphEdge> = state.incoming[id].orEmpty()

    /** 모든 관계를 포함한 후속 정점 목록을 반환한다. */
    public fun successorsOf(id: NodeId): List<NodeId> = outgoingEdgesFrom(id).map(GraphEdge::target)

    /** `EdgeKind.impliesUsage`를 공유해 도달성/query가 같은 관계를 따르게 한다. */
    public fun usageSuccessorsOf(id: NodeId): List<NodeId> = outgoingEdgesFrom(id)
        .filter { edge -> edge.kind.impliesUsage }
        .map(GraphEdge::target)

    /** 모든 관계를 포함한 선행 정점 목록을 반환한다. */
    public fun predecessorsOf(id: NodeId): List<NodeId> = incomingEdgesTo(id).map(GraphEdge::source)

    /** 기존 그래프를 바꾸지 않고 검증·정렬한 compiler CALL selector 사실을 붙인다. */
    public fun withCompilerCallPositions(references: Iterable<LocatedCompilerReference>): CodeGraph {
        val combined = ArrayList<LocatedCompilerReference>(state.locatedCompilerReferences.size)
        combined.addAll(state.locatedCompilerReferences)
        references.forEach(combined::add)
        val normalized = normalizeLocatedCompilerReferences(combined, state.nodes, state.edges)
        if (state.compilerCallPositionsCaptured && normalized == state.locatedCompilerReferences) return this
        return CodeGraph(CanonicalState(
            state.nodes,
            state.nodeIds,
            state.edges,
            state.externalCalls,
            state.serviceProviders,
            state.enclosures,
            state.callbackArguments,
            state.parameterUses,
            state.lambdaEscapes,
            normalized,
            true,
            state.outgoing,
            state.incoming,
        ))
    }

    /**
     * 이미 정규화된 그래프에 간선 delta를 더해 새 불변 그래프를 만든다.
     * 간선 identity는 `(source, target, kind, origin)`이며 기존 결정적 순서와 weight·call-site line 검증을 유지한다.
     * `replacingOrigin`이 있으면 현재 그래프에서 그 출처의 간선만 제거한 뒤 delta를 적용한다. delta iterable은 한 번만
     * 소비하고, 단독 delta의 wrapped weight는 기존 간선과 결합해 materialize할 때 검증한다.
     * 여러 signature가 잘못된 경우 기존 검증 오류 중 하나로 실패하며, 첫 오류의 선택 순서는 보장하지 않는다.
     * `externalCalls`는 기존 목록과 합치지 않고 전달한 전체 목록을 정규화해 대체하며, 기본값은 현재 정규화 목록이다.
     * 여러 signature가 동시에 잘못된 경우 기존 검증 오류 중 하나가 보고될 수 있다.
     */
    public fun enrichedWith(
        additionalEdges: Iterable<GraphEdge>,
        externalCalls: Iterable<ExternalCall> = this.externalCalls,
        replacingOrigin: EdgeOrigin? = null,
    ): CodeGraph {
        val delta = collectDelta(additionalEdges)
        var removedOrigin = false
        val selectedBase = if (replacingOrigin == null) state.edges else state.edges.filter {
            if (it.origin == replacingOrigin) {
                removedOrigin = true
                false
            } else true
        }
        val normalizedCalls = normalizeExternalCalls(externalCalls, state.nodes)
        val calls = if (normalizedCalls == state.externalCalls) state.externalCalls else normalizedCalls

        if (!removedOrigin && delta.isEmpty()) {
            return CodeGraph(CanonicalState(
                state.nodes,
                state.nodeIds,
                state.edges,
                calls,
                state.serviceProviders,
                state.enclosures,
                state.callbackArguments,
                state.parameterUses,
                state.lambdaEscapes,
                state.locatedCompilerReferences,
                state.compilerCallPositionsCaptured,
                state.outgoing,
                state.incoming,
            ))
        }

        val mergedEdges = mergeSortedEdges(selectedBase, delta)
        val positions = retainValidCompilerReferences(state.locatedCompilerReferences, mergedEdges)
        val outgoing = mergedEdges.groupBy(GraphEdge::source)
        val incoming = mergedEdges.groupBy(GraphEdge::target)
        return CodeGraph(CanonicalState(
            state.nodes,
            state.nodeIds,
            mergedEdges,
            calls,
            state.serviceProviders,
            state.enclosures,
            state.callbackArguments,
            state.parameterUses,
            state.lambdaEscapes,
            positions,
            state.compilerCallPositionsCaptured,
            outgoing,
            incoming,
        ))
    }

    private fun collectDelta(edges: Iterable<GraphEdge>): List<DeltaEntry> {
        val merged = mutableMapOf<EdgeSignature, EdgeAccumulator>()
        for (edge in edges) {
            if (edge.source !in state.nodes || edge.target !in state.nodes) continue
            val signature = EdgeSignature(edge.source, edge.target, edge.kind, edge.origin)
            merged.getOrPut(signature, ::EdgeAccumulator).add(edge)
        }
        return merged.entries.sortedWith(DELTA_ENTRY_COMPARATOR).map { (signature, value) -> DeltaEntry(signature, value) }
    }

    private fun mergeSortedEdges(base: List<GraphEdge>, delta: List<DeltaEntry>): List<GraphEdge> {
        if (delta.isEmpty()) return base
        val merged = ArrayList<GraphEdge>(base.size + delta.size)
        var baseIndex = 0
        var deltaIndex = 0
        while (baseIndex < base.size && deltaIndex < delta.size) {
            val baseEdge = base[baseIndex]
            val deltaEntry = delta[deltaIndex]
            val comparison = compareEdgeToSignature(baseEdge, deltaEntry.signature)
            when {
                comparison < 0 -> {
                    merged += baseEdge
                    baseIndex++
                }
                comparison > 0 -> {
                    merged += materialize(deltaEntry.signature, deltaEntry.accumulator.weight, deltaEntry.accumulator.callSiteLines)
                    deltaIndex++
                }
                else -> {
                    merged += materialize(
                        deltaEntry.signature,
                        baseEdge.weight + deltaEntry.accumulator.weight,
                        mergeCallSiteLines(baseEdge.callSiteLines, deltaEntry.accumulator.callSiteLines),
                    )
                    baseIndex++
                    deltaIndex++
                }
            }
        }
        while (baseIndex < base.size) merged += base[baseIndex++]
        while (deltaIndex < delta.size) {
            val entry = delta[deltaIndex++]
            merged += materialize(entry.signature, entry.accumulator.weight, entry.accumulator.callSiteLines)
        }
        return merged
    }

    private fun materialize(signature: EdgeSignature, weight: Int, callSiteLines: Iterable<Int>?): GraphEdge =
        GraphEdge(signature.source, signature.target, signature.kind, weight, signature.origin, callSiteLines?.sorted().orEmpty())

    private fun mergeCallSiteLines(base: List<Int>, delta: MutableSet<Int>?): List<Int> {
        if (delta == null) return base
        if (base.isEmpty()) return delta.sorted()
        return (base.asSequence() + delta.asSequence()).distinct().sorted().toList()
    }

    private class CanonicalState(
        val nodes: Map<NodeId, GraphNode>,
        val nodeIds: List<NodeId>,
        val edges: List<GraphEdge>,
        val externalCalls: List<ExternalCall>,
        val serviceProviders: List<ServiceProviderRegistration>,
        val enclosures: List<LexicalEnclosure>,
        val callbackArguments: List<CallbackArgument>,
        val parameterUses: List<ParameterUse>,
        val lambdaEscapes: List<LambdaEscape>,
        val locatedCompilerReferences: List<LocatedCompilerReference>,
        val compilerCallPositionsCaptured: Boolean,
        val outgoing: Map<NodeId, List<GraphEdge>>,
        val incoming: Map<NodeId, List<GraphEdge>>,
    )

    private data class EdgeSignature(
        val source: NodeId,
        val target: NodeId,
        val kind: EdgeKind,
        val origin: EdgeOrigin,
    )

    private data class DeltaEntry(val signature: EdgeSignature, val accumulator: EdgeAccumulator)

    /** 호출 줄이 없는 관계는 별도 set을 할당하지 않는다. */
    private class EdgeAccumulator {
        var weight: Int = 0
        var callSiteLines: MutableSet<Int>? = null

        fun add(edge: GraphEdge) {
            weight += edge.weight
            if (edge.callSiteLines.isNotEmpty()) {
                val lines = callSiteLines ?: mutableSetOf<Int>().also { callSiteLines = it }
                lines.addAll(edge.callSiteLines)
            }
        }
    }

    private companion object {
        private fun canonicalState(
            nodeInputs: Iterable<GraphNode>,
            edgeInputs: Iterable<GraphEdge>,
            externalCallInputs: Iterable<ExternalCall>,
            serviceProviderInputs: Iterable<ServiceProviderRegistration>,
            enclosureInputs: Iterable<LexicalEnclosure>,
            callbackInputs: Iterable<CallbackArgument>,
            parameterUseInputs: Iterable<ParameterUse>,
            lambdaEscapeInputs: Iterable<LambdaEscape>,
        ): CanonicalState {
            val nodes = buildMap {
                nodeInputs.forEach { node -> putIfAbsent(node.id, node) }
            }
            val externalCalls = normalizeExternalCalls(externalCallInputs, nodes)
            val serviceProviders = serviceProviderInputs.distinct().sorted()
            val enclosures = enclosureInputs
                .filter { it.localClass in nodes && it.enclosing in nodes && it.localClass != it.enclosing }
                .distinct().sorted()
            val callbackArguments = callbackInputs
                .filter { it.caller in nodes && it.lambda in nodes }
                .distinct().sorted()
            val parameterUses = parameterUseInputs
                .filter { it.method in nodes }
                .distinct().sorted()
            val lambdaEscapes = lambdaEscapeInputs
                .filter { it.caller in nodes && it.lambda in nodes }
                .distinct().sorted()
            val edges = mergeAllEdges(edgeInputs, nodes)
            return CanonicalState(
                nodes,
                nodes.keys.sorted(),
                edges,
                externalCalls,
                serviceProviders,
                enclosures,
                callbackArguments,
                parameterUses,
                lambdaEscapes,
                emptyList(),
                false,
                edges.groupBy(GraphEdge::source),
                edges.groupBy(GraphEdge::target),
            )
        }

        private fun normalizeExternalCalls(
            calls: Iterable<ExternalCall>,
            nodes: Map<NodeId, GraphNode>,
        ): List<ExternalCall> = calls
            .filter { it.caller in nodes && (it.target !in nodes || NodeAttribute.EXTERNAL_STUB in nodes.getValue(it.target).attributes) }
            .map { it.copy(resolvedTargets = it.resolvedTargets.filter(nodes::containsKey).distinct().sorted()) }
            .distinct().sorted()

        private fun mergeAllEdges(edges: Iterable<GraphEdge>, nodes: Map<NodeId, GraphNode>): List<GraphEdge> {
            val merged = mutableMapOf<EdgeSignature, EdgeAccumulator>()
            for (edge in edges) {
                if (edge.source !in nodes || edge.target !in nodes) continue
                val signature = EdgeSignature(edge.source, edge.target, edge.kind, edge.origin)
                merged.getOrPut(signature, ::EdgeAccumulator).add(edge)
            }
            return merged.map { (signature, value) ->
                GraphEdge(signature.source, signature.target, signature.kind, value.weight,
                    signature.origin, value.callSiteLines?.sorted().orEmpty())
            }.sorted()
        }

        private data class CompilerPositionKey(
            val source: NodeId,
            val target: NodeId,
            val path: String,
            val offsetUtf16: Int,
        )

        private fun normalizeLocatedCompilerReferences(
            references: Iterable<LocatedCompilerReference>,
            nodes: Map<NodeId, GraphNode>,
            edges: List<GraphEdge>,
        ): List<LocatedCompilerReference> {
            val bytecodeCalls = edges.asSequence()
                .filter { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE }
                .map { it.source to it.target }
                .toSet()
            val byKey = mutableMapOf<CompilerPositionKey, LocatedCompilerReference>()
            references.forEach { reference ->
                require(reference.source in nodes && reference.target in nodes) {
                    "compiler call position endpoint is absent from graph"
                }
                require(reference.source to reference.target in bytecodeCalls) {
                    "compiler call position requires a matching BYTECODE CALL"
                }
                val key = CompilerPositionKey(reference.source, reference.target, reference.file.path, reference.offsetUtf16)
                val previous = byKey.putIfAbsent(key, reference)
                require(previous == null || previous == reference) { "conflicting compiler call positions" }
            }
            return byKey.values.sortedWith(compareBy<LocatedCompilerReference>(
                { it.source }, { it.target }, { it.file.path }, { it.offsetUtf16 }, { it.endOffsetUtf16 },
                { it.line }, { it.column }, { it.file.sha256 }, { it.collector }, { it.compilerVersion },
                { it.coordinateBasis.name }, { it.generated },
            ))
        }

        private fun retainValidCompilerReferences(
            references: List<LocatedCompilerReference>,
            edges: List<GraphEdge>,
        ): List<LocatedCompilerReference> {
            if (references.isEmpty()) return references
            val calls = edges.asSequence()
                .filter { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE }
                .map { it.source to it.target }
                .toSet()
            val retained = references.filter { it.source to it.target in calls }
            return if (retained.size == references.size) references else retained
        }

        private val DELTA_ENTRY_COMPARATOR = Comparator<Map.Entry<EdgeSignature, EdgeAccumulator>> { left, right ->
            compareSignatures(left.key, right.key)
        }

        private fun compareEdgeToSignature(edge: GraphEdge, signature: EdgeSignature): Int {
            var comparison = edge.source.compareTo(signature.source)
            if (comparison != 0) return comparison
            comparison = edge.target.compareTo(signature.target)
            if (comparison != 0) return comparison
            comparison = edge.kind.compareTo(signature.kind)
            if (comparison != 0) return comparison
            return edge.origin.name.compareTo(signature.origin.name)
        }

        private fun compareSignatures(left: EdgeSignature, right: EdgeSignature): Int {
            var comparison = left.source.compareTo(right.source)
            if (comparison != 0) return comparison
            comparison = left.target.compareTo(right.target)
            if (comparison != 0) return comparison
            comparison = left.kind.compareTo(right.kind)
            if (comparison != 0) return comparison
            return left.origin.name.compareTo(right.origin.name)
        }
    }
}
