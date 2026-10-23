package dev.kartograph.analysis

import dev.kartograph.core.*

/** 미확인 adapter에서 관측한 BYTECODE CALL 경로다. 도달성이나 실제 callback 선택의 증거가 아니다. */
public data class AndroidBindingUncertaintyProof(
    val adapter: NodeId,
    val calls: List<GraphEdge>,
    val ownerOf: NodeId? = null,
    val truncated: Boolean = false,
)

/** 대표 경로의 한 단계만 보관해 callback별 경로 조합이 메모리를 늘리지 않게 한다. */
public data class AndroidBindingUncertaintyStep(val adapter: NodeId, val incomingCall: GraphEdge? = null)

/** 예산 초과는 경로가 없는 분석 불완전성이다. 이를 관측된 호출 후보 경로와 섞지 않는다. */
public data class AndroidBindingUncertainty(
    val steps: Map<NodeId, AndroidBindingUncertaintyStep> = emptyMap(),
    val ownerMembers: Map<NodeId, NodeId> = emptyMap(),
    val examinedEdges: Int = 0,
    val truncated: Boolean = false,
    val nodeBudget: Int = DEFAULT_NODE_BUDGET,
    val edgeBudget: Int = DEFAULT_EDGE_BUDGET,
) {
    /** 경로가 관측됐거나, 중단 때문에 제외를 증명하지 못한 선언의 confidence만 낮춘다. */
    public fun requiresReview(id: NodeId): Boolean = truncated || id in steps || id in ownerMembers

    /** 긴 경로는 sink 쪽 suffix와 truncation을 반환한다. 전역 불완전성에는 가짜 경로를 만들지 않는다. */
    public fun proofFor(id: NodeId, maxCalls: Int = 64): AndroidBindingUncertaintyProof? {
        require(maxCalls in 1..1_000)
        val member = if (id in steps) null else ownerMembers[id]
        var current = member ?: id
        val step = steps[current] ?: return null
        val calls = ArrayList<GraphEdge>()
        while (true) {
            val incoming = steps[current]?.incomingCall ?: break
            if (calls.size == maxCalls) return AndroidBindingUncertaintyProof(step.adapter, calls.asReversed(), member, true)
            calls += incoming
            current = incoming.source
        }
        return AndroidBindingUncertaintyProof(step.adapter, calls.asReversed(), member)
    }

    public companion object {
        public const val DEFAULT_NODE_BUDGET: Int = 20_000
        public const val DEFAULT_EDGE_BUDGET: Int = 200_000

        /** 실제 bytecode 호출만 순방향으로 따라가며 MEMBER는 owner 표시만 하고 탐색하지 않는다. */
        internal fun collect(graph: CodeGraph, adapters: Set<NodeId>, nodeBudget: Int, edgeBudget: Int): AndroidBindingUncertainty {
            require(nodeBudget in 1..1_000_000 && edgeBudget in 1..10_000_000)
            val steps = linkedMapOf<NodeId, AndroidBindingUncertaintyStep>()
            val owners = linkedMapOf<NodeId, NodeId>()
            val pending = ArrayDeque<NodeId>()
            var examined = 0
            var truncated = false
            fun add(id: NodeId, step: AndroidBindingUncertaintyStep): Boolean {
                if (id in steps) return true
                if (steps.size + owners.size >= nodeBudget) { truncated = true; return false }
                steps[id] = step
                pending += id
                return true
            }
            for (id in adapters.sorted()) if (!add(id, AndroidBindingUncertaintyStep(id))) break
            while (pending.isNotEmpty() && !truncated) {
                val id = pending.removeFirst()
                for (edge in graph.incomingEdgesTo(id)) {
                    if (examined == edgeBudget) { truncated = true; break }
                    examined++
                    if (edge.kind != EdgeKind.MEMBER || edge.source in steps || edge.source in owners) continue
                    if (steps.size + owners.size >= nodeBudget) { truncated = true; break }
                    owners[edge.source] = id
                }
                if (truncated) break
                for (edge in graph.outgoingEdgesFrom(id)) {
                    if (examined == edgeBudget) { truncated = true; break }
                    examined++
                    if (edge.kind == EdgeKind.CALL && edge.origin == EdgeOrigin.BYTECODE &&
                        !add(edge.target, AndroidBindingUncertaintyStep(steps.getValue(id).adapter, edge))) break
                }
            }
            return AndroidBindingUncertainty(steps, owners, examined, truncated, nodeBudget, edgeBudget)
        }
    }
}
