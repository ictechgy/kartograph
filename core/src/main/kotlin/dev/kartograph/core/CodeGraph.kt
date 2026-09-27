package dev.kartograph.core

/**
 * 중복과 dangling edge를 정리하고 방향별 인접 목록을 한 번만 만드는 불변 코드 그래프다.
 * 분석 알고리즘이 전체 간선을 반복 스캔하지 않게 한다.
 */
public class CodeGraph(
    nodes: Iterable<GraphNode>,
    edges: Iterable<GraphEdge>,
    externalCalls: Iterable<ExternalCall> = emptyList(),
    serviceProviders: Iterable<ServiceProviderRegistration> = emptyList(),
    enclosures: Iterable<LexicalEnclosure> = emptyList(),
    callbackArguments: Iterable<CallbackArgument> = emptyList(),
    parameterUses: Iterable<ParameterUse> = emptyList(),
) {
    public val nodes: Map<NodeId, GraphNode> = buildMap {
        nodes.forEach { node -> putIfAbsent(node.id, node) }
    }

    /** 일반 간선과 분리해 보존한 외부 호출 목록이다. 앱 정점 수와 진단 대상에는 포함하지 않는다. */
    public val externalCalls: List<ExternalCall> = externalCalls
        .filter { it.caller in this.nodes && it.target !in this.nodes }
        .map { it.copy(resolvedTargets = it.resolvedTargets.filter(this.nodes::containsKey).distinct().sorted()) }
        .distinct().sorted()

    /** 프로젝트 밖 provider도 누락된 입력을 설명할 수 있도록 선언 사실은 보존한다. */
    public val serviceProviders: List<ServiceProviderRegistration> = serviceProviders.distinct().sorted()

    /**
     * 지역·익명 class의 어휘적 소속 사실이다. 양쪽 정점이 모두 그래프에 있는 것만 남긴다.
     * 일반 간선이 아니므로 도달성·dead·기존 impact 결과에는 쓰이지 않는다.
     */
    public val enclosures: List<LexicalEnclosure> = enclosures
        .filter { it.localClass in this.nodes && it.enclosing in this.nodes && it.localClass != it.enclosing }
        .distinct().sorted()

    /**
     * 람다 값이 호출 인자로 넘어간 관측 사실이다. 넘긴 메서드와 람다 정점이 그래프에 있는 것만 남긴다.
     * 호출 대상은 가상 호출의 선언 owner일 수 있어 정점 존재를 요구하지 않는다. 일반 간선이 아니므로 도달성·dead·
     * 기존 impact 결과에는 쓰이지 않는다.
     */
    public val callbackArguments: List<CallbackArgument> = callbackArguments
        .filter { it.caller in this.nodes && it.lambda in this.nodes }
        .distinct().sorted()

    /** 콜백일 수 있는 파라미터의 관측된 쓰임이다. 파라미터를 가진 메서드가 그래프에 있는 것만 남긴다. */
    public val parameterUses: List<ParameterUse> = parameterUses
        .filter { it.method in this.nodes }
        .distinct().sorted()

    public val edges: List<GraphEdge> = edges
        .filter { edge -> edge.source in this.nodes && edge.target in this.nodes }
        .groupingBy { edge -> EdgeSignature(edge.source, edge.target, edge.kind, edge.origin) }
        .fold(0) { weight, edge -> weight + edge.weight }
        .map { (signature, weight) ->
            GraphEdge(signature.source, signature.target, signature.kind, weight, signature.origin)
        }
        .sorted()

    private val outgoing: Map<NodeId, List<GraphEdge>> = this.edges.groupBy(GraphEdge::source)
    private val incoming: Map<NodeId, List<GraphEdge>> = this.edges.groupBy(GraphEdge::target)

    public val nodeIds: List<NodeId> = this.nodes.keys.sorted()
    public val nodeCount: Int = this.nodes.size
    public val edgeCount: Int = this.edges.size
    public val isEmpty: Boolean = this.nodes.isEmpty()

    /** 식별자와 일치하는 정점을 반환한다. */
    public fun node(id: NodeId): GraphNode? = nodes[id]

    /** 식별자가 그래프 안에 있는지 확인한다. */
    public fun contains(id: NodeId): Boolean = id in nodes

    /** 정점에서 나가는 간선을 결정적인 순서로 반환한다. */
    public fun outgoingEdgesFrom(id: NodeId): List<GraphEdge> = outgoing[id].orEmpty()

    /** 정점으로 들어오는 간선을 결정적인 순서로 반환한다. */
    public fun incomingEdgesTo(id: NodeId): List<GraphEdge> = incoming[id].orEmpty()

    /** 모든 관계를 포함한 후속 정점 목록을 반환한다. */
    public fun successorsOf(id: NodeId): List<NodeId> = outgoingEdgesFrom(id).map(GraphEdge::target)

    /** `EdgeKind.impliesUsage`를 공유해 도달성/query가 같은 관계를 따르게 한다. */
    public fun usageSuccessorsOf(id: NodeId): List<NodeId> = outgoingEdgesFrom(id)
        .filter { edge -> edge.kind.impliesUsage }
        .map(GraphEdge::target)

    /** 모든 관계를 포함한 선행 정점 목록을 반환한다. */
    public fun predecessorsOf(id: NodeId): List<NodeId> = incomingEdgesTo(id).map(GraphEdge::source)

    private data class EdgeSignature(
        val source: NodeId,
        val target: NodeId,
        val kind: EdgeKind,
        val origin: EdgeOrigin,
    )
}
