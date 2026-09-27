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
 * 의존하는 정점([source])에서 의존 대상([target])으로 향하는 순회 간선이다.
 *
 * @property relationship 출력 `relationships`에 싣는 관계 이름(`call`·`reference`·`override`·`contains` 등)이다
 * @property tier 이 간선이 기대는 근거 등급이다
 */
public data class TraversalEdge(
    val source: NodeId,
    val target: NodeId,
    val relationship: String,
    val tier: TraversalEdgeTier,
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
    public fun build(graph: CodeGraph, enclosuresCaptured: Boolean = true): List<TraversalEdge> {
        val classifier = DispatchClassifier(graph, enclosuresCaptured)
        val usage = graph.edges.filter { it.kind.impliesUsage && !isOwnerReference(it) }.map { edge ->
            TraversalEdge(edge.source, edge.target, relationshipOf(edge.kind), classifier.tierOf(edge))
        }
        return usage + containmentEdges(graph)
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

    /** 어휘적 소속 간선의 관계 이름이다. */
    public const val CONTAINS: String = "contains"

    private val CALLABLE_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.CONSTRUCTOR)
}

/**
 * OVERRIDE(dispatch) 간선의 등급을 정한다.
 *
 * - bound: 수신 정적 타입이 프로젝트 타입이고, 그 타입과 모든 프로젝트 하위 타입 중 구체 class가 이 메서드를
 *   예외 없이 같은 대상 하나로 해석한다. 분석한 class가 프로젝트 타입의 구현을 모두 담는다는 닫힌 세계 가정이다.
 * - lambda: 대상이 지역·익명 class의 멤버이거나, 호출 지점이 Kotlin 함수 타입·JDK 함수형 인터페이스 호출뿐이다.
 * - candidate: 그 밖의 계층 후보다.
 */
private class DispatchClassifier(private val graph: CodeGraph, private val enclosuresCaptured: Boolean) {
    private val types: Map<String, GraphNode> = graph.nodes.values
        .filter { it.kind in TYPE_KINDS && it.jvmSignature != null }.associateBy { requireNotNull(it.jvmSignature) }
    private val localClasses: Set<String> = graph.enclosures.map { it.localClass.value.removePrefix("class:") }.toSet()
    private val callsByCaller: Map<NodeId, List<ExternalCall>> = graph.externalCalls.groupBy { it.caller }
    private val directSubtypes: Map<String, List<String>> by lazy {
        types.flatMap { (name, node) -> node.supertypes.filter(types::containsKey).map { it to name } }.groupBy({ it.first }, { it.second })
    }
    private val subtypeCache = mutableMapOf<String, Set<String>>()
    private val boundCache = mutableMapOf<Pair<String, String>, NodeId?>()

    fun tierOf(edge: GraphEdge): TraversalEdgeTier = when {
        edge.kind != EdgeKind.OVERRIDE -> if (edge.origin == EdgeOrigin.RUNTIME_MODEL) TraversalEdgeTier.BOUND else TraversalEdgeTier.DIRECT
        edge.origin == EdgeOrigin.DISPATCH_MODEL -> modeledDispatchTier(edge)
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
    private fun singleTarget(owner: String, signature: String): NodeId? = boundCache.getOrPut(owner to signature) {
        if (owner !in types) return@getOrPut null
        val concrete = (subtypesOf(owner) + owner).mapNotNull(types::get).filter(::isConcrete)
        val resolved = concrete.map { resolve(requireNotNull(it.jvmSignature), signature) }.distinct()
        resolved.singleOrNull()
    }

    /** 프로젝트 class 사슬(인터페이스 제외)을 따라 처음 만나는 구체 인스턴스 메서드다. 사슬이 프로젝트 밖으로 나가면 null이다. */
    private fun resolve(type: String, signature: String): NodeId? {
        var current: String? = type
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            val method = graph.node(NodeId("method:$current#$signature"))
            if (method != null && isVirtualBody(method)) return method.id
            current = types.getValue(current).supertypes.singleOrNull { types[it]?.kind?.let { kind -> kind != NodeKind.INTERFACE } == true }
        }
        return null
    }

    private fun isVirtualBody(node: GraphNode): Boolean = node.kind in METHOD_KINDS &&
        JvmModifier.ABSTRACT !in node.jvmModifiers && JvmModifier.STATIC !in node.jvmModifiers && node.jvmVisibility != Visibility.PRIVATE

    private fun isConcrete(node: GraphNode): Boolean = node.kind in CONCRETE_KINDS && JvmModifier.ABSTRACT !in node.jvmModifiers

    /** 프로젝트 타입의 전이적 하위 타입이다. supertypes에 적힌 프로젝트 타입만 따르며 수신 타입별로 한 번만 계산한다. */
    private fun subtypesOf(owner: String): Set<String> = subtypeCache.getOrPut(owner) {
        val found = mutableSetOf<String>()
        val pending = ArrayDeque(directSubtypes[owner].orEmpty())
        while (pending.isNotEmpty()) { val next = pending.removeFirst(); if (found.add(next)) pending.addAll(directSubtypes[next].orEmpty()) }
        found
    }

    private companion object {
        val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
        val CONCRETE_KINDS = setOf(NodeKind.CLASS, NodeKind.OBJECT, NodeKind.ENUM)
        val METHOD_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
        val FUNCTIONAL_OWNERS = Regex("kotlin/jvm/functions/.+|kotlin/Function|kotlin/jvm/internal/FunctionBase|kotlin/reflect/K(Suspend)?Function\\d*|" +
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
