package dev.kartograph.analysis

import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
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
 * 순회 간선의 근거 등급이다. 앞선 값일수록 강하다.
 *
 * - [DIRECT]: 컴파일러가 확정한 호출·참조·필드 접근과 classfile `EnclosingMethod`의 어휘적 소속이다.
 * - [BOUND]: 닫힌 세계 가정에서 대상이 하나로 정해지는 dispatch와 런타임 모델 간선이다.
 * - [CANDIDATE]: 클래스 계층(CHA)으로 가능성만 있는 override 대상이다.
 * - [LAMBDA]: `FunctionN`/SAM `invoke`처럼 콜백 호출 지점에서 모든 람다·익명 class 본문으로 퍼지는 후보다.
 *   등급으로는 candidate지만 어휘적 소속이 같은 본문을 정확히 잇기 때문에 기본 순회에서 따로 뺀다.
 */
public enum class TraversalEdgeTier { DIRECT, BOUND, CANDIDATE, LAMBDA }

/**
 * 순회 간선이 class 정점을 드나드는 방식이다. `--class-hops member-only`가 이 표식으로 class 정점을 거치는 경로를 좁힌다.
 *
 * - [OWNER_CALLBACK]: 프레임워크 콜백 모델(`runtimeModel`)이 class에서 자기 멤버로 잇는 간선이다. 런타임이 그 class의
 *   인스턴스에서 멤버를 부른다는 가정이다(예: `androidx/lifecycle/ViewModel` 하위 class → 비공개·정적이 아닌 모든 멤버).
 * - [TYPE_REFERENCE]: class 정점을 가리키는 `reference` 간선 중 인스턴스를 만들지 않는 것이다 — 시그니처·필드 타입·
 *   cast·class literal·호출 소유자·중첩 class의 바깥 class 참조가 모두 같은 REFERENCE 간선으로 합쳐져 있다. 같은 source가
 *   그 class의 생성자를 부르면(인스턴스 생성) 달지 않는다. 런타임 모델 참조(reflection으로 해석한 class)도 달지 않는다.
 */
public enum class TraversalClassRole { OWNER_CALLBACK, TYPE_REFERENCE }

/**
 * 의존하는 정점([source])에서 의존 대상([target])으로 향하는 순회 간선이다.
 *
 * @property relationship 출력 `relationships`에 싣는 관계 이름(`call`·`reference`·`override`·`contains`·`callback` 등)이다
 * @property tier 이 간선이 기대는 근거 등급이다
 * @property terminal 호출 문맥 안에서만 참인 콜백 간선이다. 역방향 순회에서 [source]를 목록에 싣되 그 정점에서 일반
 *   간선으로 더 퍼지지 않는다(다른 호출자는 다른 람다를 넘긴다). 정방향 순회는 따르지 않는다 — 람다를 만든 함수에서
 *   본문으로 가는 길은 어휘적 소속이 이미 잇는다
 * @property classRole class 정점을 드나드는 간선이면 그 방식이다. 기본 순회는 쓰지 않고 `--class-hops member-only`만 쓴다
 */
public data class TraversalEdge(
    val source: NodeId,
    val target: NodeId,
    val relationship: String,
    val tier: TraversalEdgeTier,
    val terminal: Boolean = false,
    val classRole: TraversalClassRole? = null,
)

/**
 * 코드 그래프를 근거 등급이 붙은 순회 간선으로 바꾼다.
 *
 * 기존 `impact` 탐색과 같은 사용 관계(MEMBER 제외, 멤버→자기 class 참조 제외)를 쓰고, 여기에 지역·익명 class의
 * 어휘적 소속(`contains`)을 더한다. OVERRIDE 간선은 출처와 무관하게 dispatch로 보고 등급을 매긴다.
 */
public object TraversalEdges {
    /**
     * @param graph 순회할 그래프다
     * @param enclosuresCaptured 그래프가 어휘적 소속 사실을 실었는지다. 거짓이면 람다 후보를 일반 후보로 되돌려
     *   소속 간선 없이 본문이 끊기는 일을 막는다
     */
    public fun build(graph: CodeGraph, enclosuresCaptured: Boolean = true, callbackFactsCaptured: Boolean = true): List<TraversalEdge> =
        assemble(graph, enclosuresCaptured, callbackFactsCaptured).edges

    /** 간선과 콜백 흐름 집계를 함께 만든다. 콜백 간선은 [CallbackFlows]가 그래프의 콜백 관측 사실에서 만든다. */
    internal fun assemble(graph: CodeGraph, enclosuresCaptured: Boolean = true, callbackFactsCaptured: Boolean = true): TraversalGraph {
        val classifier = DispatchClassifier(graph, enclosuresCaptured)
        val instantiated = instantiatedClasses(graph)
        val usage = graph.edges.filter { it.kind.impliesUsage && !isOwnerReference(it) }.map { edge ->
            TraversalEdge(edge.source, edge.target, relationshipOf(edge.kind), classifier.tierOf(edge), classRole = classRoleOf(edge, instantiated))
        }
        // 콜백 사실을 다 싣지 않은 snapshot(일부 키만 있는 경우 포함)은 빠져나감을 놓칠 수 있으므로 콜백 간선을 만들지 않는다.
        val callbacks = if (callbackFactsCaptured) CallbackFlows(graph, classifier).analyze() else CallbackFlowResult(emptyList(), CallbackFlowSummary())
        return TraversalGraph(usage + containmentEdges(graph) + callbacks.edges, callbacks.summary)
    }

    /**
     * 어휘적 소속 간선이다. 감싼 선언이 지역 class와 그 메서드·생성자를 "포함"한다.
     * 역방향이면 람다 본문의 변경이 감싼 함수와 그 호출자에 닿고, 정방향이면 감싼 함수에서 본문의 호출 대상에 닿는다.
     */
    internal fun containmentEdges(graph: CodeGraph): List<TraversalEdge> {
        val members = graph.edges.filter { it.kind == EdgeKind.MEMBER }.groupBy({ it.source }, { it.target })
        return graph.enclosures.flatMap { enclosure ->
            val methods = members[enclosure.localClass].orEmpty().filter { graph.node(it)?.kind in CALLABLE_KINDS }
            (listOf(enclosure.localClass) + methods).map { TraversalEdge(enclosure.enclosing, it, CONTAINS, TraversalEdgeTier.DIRECT) }
        }
    }

    /** 관계 이름은 snapshot 간선 kind의 lowerCamel 표기를 그대로 쓴다. */
    private fun relationshipOf(kind: EdgeKind): String = kind.name.lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::titlecase) }
    }

    /**
     * 멤버가 자기 소유 class를 가리키는 REFERENCE 간선이다. `ChangeImpact`와 같은 이유로 제외한다 —
     * class 정점에 닿는 변경이 그 class의 모든 멤버로 퍼지지 않게 한다.
     */
    internal fun isOwnerReference(edge: GraphEdge): Boolean {
        if (edge.kind != EdgeKind.REFERENCE) return false
        val source = edge.source.value
        if (!source.startsWith("method:") && !source.startsWith("field:")) return false
        val owner = source.substringAfter(':').substringBefore('#', "")
        return owner.isNotEmpty() && edge.target.value == "class:$owner"
    }

    /**
     * 간선의 class 정점 드나듦 방식이다. 둘 다 아니면 null이다.
     *
     * @param instantiated 정점마다 그 정점이 생성자를 부르는 class 이름 집합이다([instantiatedClasses])
     */
    internal fun classRoleOf(edge: GraphEdge, instantiated: Map<NodeId, Set<String>>): TraversalClassRole? {
        if (edge.kind != EdgeKind.REFERENCE || !edge.target.value.startsWith("class:")) {
            return if (isOwnerCallback(edge)) TraversalClassRole.OWNER_CALLBACK else null
        }
        // reflection 등 런타임 모델이 해석한 class 사용은 이름만 적은 참조가 아니다.
        if (edge.origin == EdgeOrigin.RUNTIME_MODEL) return null
        return if (edge.target.value.removePrefix("class:") in instantiated[edge.source].orEmpty()) null else TraversalClassRole.TYPE_REFERENCE
    }

    /** 프레임워크 콜백 모델이 class에서 자기 멤버로 이은 간선인지다. 다른 class의 멤버를 가리키면 아니다. */
    private fun isOwnerCallback(edge: GraphEdge): Boolean {
        if (edge.kind != EdgeKind.REFERENCE || edge.origin != EdgeOrigin.RUNTIME_MODEL || !edge.source.value.startsWith("class:")) return false
        val target = edge.target.value
        if (!target.startsWith("method:") && !target.startsWith("field:")) return false
        return target.substringAfter(':').substringBefore('#', "") == edge.source.value.removePrefix("class:")
    }

    /**
     * 정점마다 생성자를 부르는 class 이름이다. `new C(...)`는 bytecode에서 `C.<init>` 호출이므로 CALL 간선 대상으로 안다.
     * 같은 정점의 C 참조(`new`의 타입 명령·호출 소유자)는 인스턴스 생성이라 이름만 적은 참조와 구분한다.
     */
    internal fun instantiatedClasses(graph: CodeGraph): Map<NodeId, Set<String>> = graph.edges
        .filter { it.kind == EdgeKind.CALL }
        .mapNotNull { edge -> splitMethod(edge.target)?.takeIf { it.second.startsWith("<init>(") }?.let { edge.source to it.first } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, owners) -> owners.toSet() }

    /** 어휘적 소속 간선의 관계 이름이다. */
    public const val CONTAINS: String = "contains"

    private val CALLABLE_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.CONSTRUCTOR)
}

/** 순회 간선과 콜백 흐름 집계다. */
internal data class TraversalGraph(val edges: List<TraversalEdge>, val callbacks: CallbackFlowSummary)

/**
 * OVERRIDE(dispatch) 간선의 등급을 정한다.
 *
 * - bound: 수신 정적 타입이 프로젝트 타입이고, 그 타입과 모든 프로젝트 하위 타입 중 구체 class가 이 메서드를
 *   예외 없이 같은 대상 하나로 해석한다. 분석한 class가 프로젝트 타입의 구현을 모두 담는다는 닫힌 세계 가정이다.
 * - lambda: 대상이 지역·익명 class의 멤버이거나, 호출 지점이 Kotlin 함수 타입·JDK 함수형 인터페이스 호출뿐이다.
 * - candidate: 그 밖의 계층 후보다.
 */
internal class DispatchClassifier(private val graph: CodeGraph, private val enclosuresCaptured: Boolean) {
    // 닫힌 세계의 전칭 범위는 그래프의 모든 class 정점이다. jvmSignature가 빠진 정점도 빠뜨리지 않도록 id에서 이름을 얻는다.
    val types: Map<String, GraphNode> = graph.nodes.values
        .filter { it.id.value.startsWith("class:") }.associateBy { it.id.value.removePrefix("class:") }
    private val localClasses: Set<String> = graph.enclosures.map { it.localClass.value.removePrefix("class:") }.toSet()
    private val callsByCaller: Map<NodeId, List<ExternalCall>> = graph.externalCalls.groupBy { it.caller }
    private val directSubtypes: Map<String, List<String>> by lazy {
        types.flatMap { (name, node) -> node.supertypes.filter(types::containsKey).map { it to name } }.groupBy({ it.first }, { it.second })
    }
    private val subtypeCache = mutableMapOf<String, Set<String>>()
    private val boundCache = mutableMapOf<Pair<String, String>, NodeId?>()

    // dispatch 모델 간선은 종류와 무관하게 dispatch로 판정한다. 모델 간선이 direct로 부풀지 않게 한다.
    fun tierOf(edge: GraphEdge): TraversalEdgeTier = when {
        edge.origin == EdgeOrigin.DISPATCH_MODEL -> modeledDispatchTier(edge)
        edge.kind != EdgeKind.OVERRIDE -> if (edge.origin == EdgeOrigin.RUNTIME_MODEL) TraversalEdgeTier.BOUND else TraversalEdgeTier.DIRECT
        else -> declaredDispatchTier(edge)
    }

    /** 상위 선언 → 구현 override다. 상위 선언의 소유 타입이 수신 정적 타입이다. */
    private fun declaredDispatchTier(edge: GraphEdge): TraversalEdgeTier {
        val (owner, signature) = splitMethod(edge.source) ?: return TraversalEdgeTier.CANDIDATE
        if (singleTarget(owner, signature) == edge.target) return TraversalEdgeTier.BOUND
        return if (isLocalMember(edge.target)) lambdaTier() else TraversalEdgeTier.CANDIDATE
    }

    /** 외부 소유 호출 → 프로젝트 구현 후보다. 같은 간선을 만든 호출 지점 중 가장 강한 근거를 쓴다. */
    private fun modeledDispatchTier(edge: GraphEdge): TraversalEdgeTier {
        val calls = callsByCaller[edge.source].orEmpty().filter { edge.target in it.resolvedTargets }
        if (calls.any { singleTarget(it.owner, it.name + it.descriptor) == edge.target }) return TraversalEdgeTier.BOUND
        val callback = isLocalMember(edge.target) || (calls.isNotEmpty() && calls.all { isFunctionalOwner(it.owner) })
        return if (callback) lambdaTier() else TraversalEdgeTier.CANDIDATE
    }

    /** 소속 사실이 없는 옛 snapshot에서는 람다 후보를 빼면 본문이 끊기므로 일반 후보로 둔다. */
    private fun lambdaTier(): TraversalEdgeTier = if (enclosuresCaptured) TraversalEdgeTier.LAMBDA else TraversalEdgeTier.CANDIDATE

    private fun isLocalMember(target: NodeId): Boolean = splitMethod(target)?.first in localClasses

    /**
     * 수신 정적 타입 [owner]의 모든 구체 프로젝트 하위 타입이 [signature]를 같은 프로젝트 메서드로 해석하면 그 메서드,
     * 아니면 null이다. 구체 타입이 없거나, 하나라도 프로젝트 밖으로 해석이 새면 null이다(보수적).
     */
    fun singleTarget(owner: String, signature: String): NodeId? = boundCache.getOrPut(owner to signature) {
        if (owner !in types) return@getOrPut null
        val concrete = (subtypesOf(owner) + owner).mapNotNull(types::get).filter(::isConcrete)
        val resolved = concrete.map { resolve(it.id.value.removePrefix("class:"), signature) }.distinct()
        resolved.singleOrNull()
    }

    /** 프로젝트 class 사슬(인터페이스 제외)을 따라 처음 만나는 구체 인스턴스 메서드다. 사슬이 프로젝트 밖으로 나가면 null이다. */
    fun resolve(type: String, signature: String): NodeId? {
        var current: String? = type
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            val method = graph.node(NodeId("method:$current#$signature"))
            if (method != null && isVirtualBody(method)) return method.id
            current = types.getValue(current).supertypes.singleOrNull { types[it]?.kind?.let { kind -> kind != NodeKind.INTERFACE } == true }
        }
        return null
    }

    fun isVirtualBody(node: GraphNode): Boolean = node.kind in METHOD_KINDS &&
        JvmModifier.ABSTRACT !in node.jvmModifiers && JvmModifier.STATIC !in node.jvmModifiers && node.jvmVisibility != Visibility.PRIVATE

    private fun isConcrete(node: GraphNode): Boolean = node.kind in CONCRETE_KINDS && JvmModifier.ABSTRACT !in node.jvmModifiers

    /** 프로젝트 타입의 전이적 하위 타입이다. supertypes에 적힌 프로젝트 타입만 따르며 수신 타입별로 한 번만 계산한다. */
    private fun subtypesOf(owner: String): Set<String> = subtypeCache.getOrPut(owner) {
        val found = mutableSetOf<String>()
        val pending = ArrayDeque(directSubtypes[owner].orEmpty())
        while (pending.isNotEmpty()) { val next = pending.removeFirst(); if (found.add(next)) pending.addAll(directSubtypes[next].orEmpty()) }
        found
    }

    companion object {
        private val CONCRETE_KINDS = setOf(NodeKind.CLASS, NodeKind.OBJECT, NodeKind.ENUM)
        private val METHOD_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
        private val FUNCTIONAL_OWNERS = Regex("kotlin/jvm/functions/.+|kotlin/Function|kotlin/jvm/internal/FunctionBase|kotlin/reflect/K(Suspend)?Function\\d*|" +
            "java/util/function/.+|java/lang/Runnable|java/util/concurrent/Callable")

        fun isFunctionalOwner(owner: String): Boolean = FUNCTIONAL_OWNERS.matches(owner)
    }
}

/** `method:owner#signature` 식별자를 (owner, signature)로 나눈다. 메서드 식별자가 아니면 null이다. */
internal fun splitMethod(id: NodeId): Pair<String, String>? {
    if (!id.value.startsWith("method:")) return null
    val body = id.value.removePrefix("method:")
    val owner = body.substringBefore('#', "")
    val signature = body.substringAfter('#', "")
    return if (owner.isEmpty() || signature.isEmpty()) null else owner to signature
}

/**
 * 정점 자신의 나가는 호출 지점 중 kartograph가 대상을 잇지 못한 것인지 판단한다.
 *
 * - 외부 소유 가상·인터페이스 호출 중 dispatch 해석이 `UNRESOLVED`로 끝난 것(`external-dispatch` 한계와 같은 집합)
 * - 런타임 라이브러리 모델(reflection 등)이 붙었지만 값이 해석되지 않은 호출
 * - LambdaMetafactory·문자열 결합처럼 모델이 있는 bootstrap이 아닌 invokedynamic
 * 정적·특수 호출로 라이브러리 메서드를 부르는 것은 대상이 확정된 호출이라 세지 않는다.
 */
public fun ExternalCall.isUnresolvedTarget(): Boolean = when {
    model != null && resolution != CallResolution.RUNTIME_MODEL -> true
    kind == InvocationKind.BOOTSTRAP -> owner !in MODELED_BOOTSTRAPS
    kind == InvocationKind.VIRTUAL || kind == InvocationKind.INTERFACE -> resolution == CallResolution.UNRESOLVED
    else -> false
}

private val MODELED_BOOTSTRAPS = setOf(
    "java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory",
    "java/lang/runtime/ObjectMethods", "java/lang/runtime/SwitchBootstraps",
)
