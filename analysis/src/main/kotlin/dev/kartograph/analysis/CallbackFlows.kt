package dev.kartograph.analysis

import dev.kartograph.core.CallbackArgument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.ParameterUseKind

/**
 * 콜백 흐름 판정의 집계다. 한계 문구에 쓴다.
 *
 * @property bound 모든 호출 지점을 프로젝트 안에서 찾은 흐름 수다
 * @property candidate 라이브러리 코드에 넘기거나 값이 빠져나가 호출자가 더 있을 수 있는 흐름 수다
 * @property unresolved 호출 지점을 하나도 찾지 못한 채 값이 빠져나간 흐름 수다
 * @property unresolvedReasons [unresolved] 흐름이 빠져나간 이유별 수다. 한 흐름이 여러 이유를 가질 수 있다
 */
public data class CallbackFlowSummary(
    val bound: Int = 0,
    val candidate: Int = 0,
    val unresolved: Int = 0,
    val unresolvedReasons: Map<String, Int> = emptyMap(),
)

/** 콜백 간선과 그 집계다. */
internal data class CallbackFlowResult(val edges: List<TraversalEdge>, val summary: CallbackFlowSummary)

/**
 * 람다 전달 사실([CallbackArgument])과 파라미터 쓰임([ParameterUse])을 이어 "G가 F에서 받은 람다 L을 실행한다"를
 * 콜백 간선 G → L 본문으로 만든다.
 *
 * F가 만든 L을 G의 인자 i로 넘기면, G의 파라미터 i에서 출발해 그 값이 쓰인 곳을 따라간다. 수정 없이 다른 프로젝트
 * 메서드로 넘기면(인자 전달·invokedynamic 캡처) 그 메서드의 파라미터로 이어서 [MAX_FORWARD_DEPTH]까지 따라간다.
 * 이 경로에 있는 메서드 중 L을 실행하거나 라이브러리 코드에 넘기는 곳으로 이어지는 메서드마다 간선을 만든다.
 *
 * 등급:
 * - bound: 값이 한 번도 빠져나가지 않고(필드·반환·배열·해석 못 한 dispatch·기록 없는 파라미터·깊이 초과·라이브러리 전달 없음)
 *   실행 지점을 모두 프로젝트 안에서 찾았다. 닫힌 세계 가정에서 L을 실행하는 곳은 이 경로뿐이다.
 * - candidate: 실행 지점이나 라이브러리 전달은 찾았지만 값이 빠져나가 다른 실행자가 있을 수 있다.
 * 실행 지점 없이 빠져나가기만 하면 간선을 만들지 않고 [CallbackFlowSummary.unresolved]로 센다.
 *
 * 콜백 간선은 호출 문맥(F가 G를 부른 호출) 안에서만 참이다. 그래서 순회는 이 간선으로 닿은 G를 목록에 싣되, G의
 * 다른 호출자로 퍼뜨리지 않는다(`LanguageTraversal`). F는 L의 어휘적 소속(`contains`)으로 이미 닿는다.
 */
internal class CallbackFlows(private val graph: CodeGraph, private val dispatch: DispatchClassifier) {
    private val uses: Map<Pair<NodeId, Int>, List<ParameterUse>> = graph.parameterUses.groupBy { it.method to it.parameter }
    private val members: Map<NodeId, List<NodeId>> = graph.edges.filter { it.kind == EdgeKind.MEMBER }.groupBy({ it.source }, { it.target })

    fun analyze(): CallbackFlowResult {
        val tiers = mutableMapOf<Pair<NodeId, NodeId>, TraversalEdgeTier>()
        var bound = 0; var candidate = 0; var unresolved = 0
        val reasons = sortedMapOf<String, Int>()
        graph.callbackArguments.forEach { argument ->
            // 라이브러리에 바로 넘긴 람다는 프로젝트 안에 실행자가 없으므로 흐름이 아니다. 구현을 하나로 못 정한 호출은 센다.
            val callee = when (val resolved = resolve(argument.callee, argument.invocation)) {
                is Callee.Project -> resolved.id
                Callee.External -> return@forEach
                Callee.Ambiguous -> { unresolved++; reasons.merge("dispatch", 1, Int::plus); return@forEach }
            }
            val flow = Flow(argument).apply { walk(callee to argument.argument) }
            val edges = flow.edges()
            when {
                edges.isEmpty() -> if (flow.escapes.isNotEmpty()) {
                    unresolved++
                    flow.escapes.forEach { reasons.merge(it, 1, Int::plus) }
                }
                flow.tier == TraversalEdgeTier.BOUND -> bound++
                else -> candidate++
            }
            edges.forEach { edge -> tiers.merge(edge, flow.tier) { old, new -> if (new.ordinal < old.ordinal) new else old } }
        }
        val edges = tiers.map { (edge, tier) -> TraversalEdge(edge.first, edge.second, CALLBACK, tier, terminal = true) }
            .sortedWith(compareBy({ it.source.value }, { it.target.value }))
        return CallbackFlowResult(edges, CallbackFlowSummary(bound, candidate, unresolved, reasons))
    }

    /** 호출 지점 대상의 해석 결과다. */
    private sealed interface Callee {
        data class Project(val id: NodeId) : Callee
        data object External : Callee
        data object Ambiguous : Callee
    }

    /**
     * 정적·특수 호출과 invokedynamic 구현은 적힌 메서드(없으면 프로젝트 상위 class 사슬의 선언)다. 가상 호출은 닫힌 세계에서
     * 구현이 하나일 때만 프로젝트 대상이다. owner가 프로젝트 타입이 아니면 라이브러리 호출이다.
     */
    private fun resolve(target: NodeId, invocation: InvocationKind): Callee {
        if (graph.node(target) != null && invocation != InvocationKind.VIRTUAL && invocation != InvocationKind.INTERFACE) return Callee.Project(target)
        val (owner, signature) = splitMethod(target) ?: return Callee.Ambiguous
        if (owner !in dispatch.types) return Callee.External
        return when (invocation) {
            InvocationKind.VIRTUAL, InvocationKind.INTERFACE -> dispatch.singleTarget(owner, signature)?.let { Callee.Project(it) } ?: Callee.Ambiguous
            else -> inherited(owner, signature)?.let { Callee.Project(it) } ?: Callee.Ambiguous
        }
    }

    /** 정적·super 호출이 상속 선언으로 해석되는 경우다. 프로젝트 상위 class 사슬만 따른다. */
    private fun inherited(owner: String, signature: String): NodeId? {
        var current: String? = owner
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            graph.node(NodeId("method:$current#$signature"))?.let { return it.id }
            current = dispatch.types[current]?.supertypes?.singleOrNull { dispatch.types[it]?.kind?.let { kind -> kind != NodeKind.INTERFACE } == true }
        }
        return null
    }

    /** 한 람다 전달의 파라미터 흐름이다. (메서드, 파라미터 위치) 쌍을 정점으로 하는 전달 그래프를 너비 우선으로 만든다. */
    private inner class Flow(private val argument: CallbackArgument) {
        val escapes = mutableSetOf<String>()
        private var libraryHandoff = false
        private val depths = linkedMapOf<Pair<NodeId, Int>, Int>()
        private val forwards = mutableMapOf<Pair<NodeId, Int>, MutableList<Pair<Pair<NodeId, Int>, Boolean>>>()
        private val sites = mutableMapOf<Pair<NodeId, Int>, MutableSet<NodeId>>()

        val tier: TraversalEdgeTier
            get() = if (escapes.isEmpty() && !libraryHandoff) TraversalEdgeTier.BOUND else TraversalEdgeTier.CANDIDATE

        fun walk(start: Pair<NodeId, Int>) {
            depths[start] = 0
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val pair = queue.removeFirst()
                val recorded = uses[pair].orEmpty()
                if (recorded.none { it.kind == ParameterUseKind.DECLARED }) { escapes += "unanalyzed"; continue }
                recorded.forEach { use -> visit(pair, use)?.let { (next, closure) -> link(pair, next, closure, queue) } }
            }
            if (closureAddsSites(start)) escapes += "closure"
        }

        /** 쓰임 하나를 해석한다. 다른 파라미터로 이어지면 (다음 쌍, 캡처 여부)를 돌려준다. */
        private fun visit(pair: Pair<NodeId, Int>, use: ParameterUse): Pair<Pair<NodeId, Int>, Boolean>? {
            val target = use.target
            when (use.kind) {
                ParameterUseKind.DECLARED -> Unit
                ParameterUseKind.RECEIVER -> receiver(pair, requireNotNull(target))
                ParameterUseKind.ARGUMENT -> {
                    if (isInspectionOnly(requireNotNull(target))) return null
                    when (val callee = resolve(target, requireNotNull(use.invocation))) {
                        is Callee.Project -> return (callee.id to requireNotNull(use.position)) to false
                        Callee.External -> handOff(pair)
                        Callee.Ambiguous -> escapes += "dispatch"
                    }
                }
                ParameterUseKind.CAPTURE -> if (target != null && graph.node(target) != null) return (target to requireNotNull(use.position)) to true
                    else escapes += "capture"
                else -> escapes += use.kind.name.lowercase()
            }
            return null
        }

        private fun link(pair: Pair<NodeId, Int>, next: Pair<NodeId, Int>, closure: Boolean, queue: ArrayDeque<Pair<NodeId, Int>>) {
            forwards.getOrPut(pair) { mutableListOf() } += next to closure
            if (next in depths) return
            val depth = depths.getValue(pair) + 1
            if (depth > MAX_FORWARD_DEPTH) { escapes += "depth"; return }
            depths[next] = depth
            queue += next
        }

        /**
         * 수신 객체로 부른 메서드가 L의 함수형 메서드면 L을 실행하는 지점이다. 함수형 타입 메서드인데 L 본문을 찾지 못하면
         * (라이브러리 상위 class의 구현) 라이브러리가 실행하는 것으로 본다. `Object`의 `toString`·`equals`·`hashCode`는
         * 람다 본문을 실행하지 않는다. 그 밖의 메서드(인터페이스 default 메서드 등)는 본문을 부를 수 있어 빠져나간 것으로 본다.
         */
        private fun receiver(pair: Pair<NodeId, Int>, target: NodeId) {
            val body = bodyFor(target)
            val (owner, signature) = splitMethod(target) ?: return
            when {
                body != null -> sites.getOrPut(pair) { mutableSetOf() } += body
                DispatchClassifier.isFunctionalOwner(owner) -> handOff(pair)
                signature !in OBJECT_METHODS -> escapes += "receiver"
            }
        }

        /** 라이브러리 코드에 넘겼다. 그 코드가 L의 어느 함수형 메서드를 부를지 모르므로 모든 본문을 잇는다. */
        private fun handOff(pair: Pair<NodeId, Int>) {
            libraryHandoff = true
            sites.getOrPut(pair) { mutableSetOf() } += allBodies()
        }

        private fun bodyFor(invoked: NodeId): NodeId? {
            val signature = splitMethod(invoked)?.second ?: return null
            argument.samMethod?.let { sam -> return argument.lambda.takeIf { signature == sam } }
            val type = argument.lambda.value.removePrefix("class:")
            return if (type in dispatch.types) dispatch.resolve(type, signature) else null
        }

        private fun allBodies(): List<NodeId> {
            if (argument.samMethod != null) return listOf(argument.lambda)
            return members[argument.lambda].orEmpty().filter { id ->
                graph.node(id)?.let { node -> node.kind == NodeKind.METHOD && JvmModifier.STATIC !in node.jvmModifiers && dispatch.isVirtualBody(node) } == true
            }
        }

        /**
         * 캡처 경로가 새 실행 지점을 더하는지다. Compose의 재구성 람다처럼 캡처한 값을 같은 함수로 되돌려 넘기기만 하면
         * 실행 지점 집합이 같아 값이 새지 않는다. 새 지점이 생기면 그 람다를 누가 언제 실행할지 모르므로 빠져나간 것으로 본다.
         */
        private fun closureAddsSites(start: Pair<NodeId, Int>): Boolean {
            val withClosures = reachable(start, closures = true)
            val withoutClosures = reachable(start, closures = false)
            return withClosures.filterTo(mutableSetOf()) { it in sites } != withoutClosures.filterTo(mutableSetOf()) { it in sites }
        }

        private fun reachable(start: Pair<NodeId, Int>, closures: Boolean): Set<Pair<NodeId, Int>> {
            val found = linkedSetOf(start)
            val pending = ArrayDeque(listOf(start))
            while (pending.isNotEmpty()) forwards[pending.removeFirst()].orEmpty().forEach { (next, closure) ->
                if ((closures || !closure) && found.add(next)) pending += next
            }
            return found
        }

        /** 실행 지점으로 이어지는 쌍의 메서드마다, 그 쌍에서 닿는 실행 지점의 본문으로 간선을 만든다. */
        fun edges(): Set<Pair<NodeId, NodeId>> = depths.keys.flatMapTo(sortedSetOf(compareBy({ it.first.value }, { it.second.value }))) { pair ->
            reachable(pair, closures = true).flatMap { sites[it].orEmpty() }.filter { it != pair.first }.map { pair.first to it }
        }
    }

    /**
     * 값을 비교·검사만 하고 실행하거나 보관해 되돌려주지 않는 라이브러리 호출이다. 반환값이 객체면 같은 값을 돌려줄 수
     * 있어(`requireNonNull`) 별칭을 놓치므로 제외한다. Compose `Composer.changed*`는 다음 구성에서 비교할 값으로만 쓴다.
     */
    private fun isInspectionOnly(target: NodeId): Boolean {
        val (owner, signature) = splitMethod(target) ?: return false
        val name = signature.substringBefore('(')
        val returnsObject = signature.substringAfterLast(')').let { it.startsWith("L") || it.startsWith("[") }
        return !returnsObject && INSPECTIONS[owner]?.let { names -> name in names } == true
    }

    companion object {
        /** 콜백 간선의 관계 이름이다. */
        const val CALLBACK: String = "callback"

        /** 인자 전달·캡처로 파라미터를 따라가는 최대 단계다. 넘으면 빠져나간 것으로 본다. */
        const val MAX_FORWARD_DEPTH: Int = 8

        private val OBJECT_METHODS = setOf("toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z", "getClass()Ljava/lang/Class;")

        private val INSPECTIONS = mapOf(
            "kotlin/jvm/internal/Intrinsics" to setOf("checkNotNull", "checkNotNullParameter", "checkNotNullExpressionValue",
                "checkParameterIsNotNull", "checkExpressionValueIsNotNull", "areEqual"),
            "java/util/Objects" to setOf("equals", "hashCode", "isNull", "nonNull"),
            "androidx/compose/runtime/Composer" to setOf("changed", "changedInstance"),
        )
    }
}
