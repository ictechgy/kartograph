package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.qualifiedName

/** cartograph의 SymbolQueryDocument와 같은 필드 계약을 쓰는 심볼 질의 응답이다. */
public data class SymbolQueryDocument(
    val status: String,
    val requested: String,
    val level: String,
    val limitations: List<String>,
    val result: SymbolQueryResult? = null,
    val candidates: List<SymbolQueryCandidate>? = null,
)

/** 모호한 이름을 임의로 고르지 않고 다시 질의할 안정 식별자를 제공한다. */
public data class SymbolQueryCandidate(val qualifiedName: String, val usr: String?)

/** 한 선언의 양방향 그래프 사실과 도달성 관측값이다. */
public data class SymbolQueryResult(
    val subject: SymbolQuerySubject,
    val reachability: SymbolReachability,
    val usedBy: List<SymbolQueryNeighbor>,
    val dependsOn: List<SymbolQueryNeighbor>,
    val members: List<SymbolQueryNeighbor>,
    val declaredIn: SymbolQueryNeighbor?,
    val truncated: SymbolQueryTruncation,
)

/** 질의 대상 선언의 공개 가능한 속성이다. */
public data class SymbolQuerySubject(
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val module: String?,
    val usr: String?,
    val accessibility: String,
    val location: SourceLocation?,
)

/** 질의 대상과 관계가 있는 선언 및 그 관계다. */
public data class SymbolQueryNeighbor(
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val usr: String?,
    val module: String?,
    val edges: List<String>,
    val depth: Int,
    val location: SourceLocation?,
)

/** 보존 뿌리에서 관측한 도달성 사실이며 삭제 판단이 아니다. */
public data class SymbolReachability(
    val state: String,
    val reason: RetentionReason?,
    val path: List<String>?,
    val suppressedByBaseline: Boolean,
)

/** 응답 한도 때문에 각 관계가 잘렸는지 명시한다. */
public data class SymbolQueryTruncation(val usedBy: Boolean, val dependsOn: Boolean, val members: Boolean)

/** 공통 usage-edge 술어를 이용해 결정적인 심볼 질의를 만든다. */
public object SymbolQuery {
    /** 모호성과 응답 한도를 숨기지 않는 한 심볼의 graph 사실 문서를 만든다. */
    public fun query(
        graph: CodeGraph,
        reachability: ReachabilityResult,
        requested: String,
        limitations: List<String>,
        depth: Int = 1,
        limit: Int = 50,
        suppressedByBaseline: Set<NodeId> = emptySet(),
    ): SymbolQueryDocument {
        val matches = graph.nodes.values.filter { node ->
            node.id.value == requested || node.name == requested || node.qualifiedName == requested
        }.sortedBy { it.id }
        if (matches.isEmpty()) return SymbolQueryDocument("notFound", requested, "symbol", limitations)
        if (matches.size > 1) return SymbolQueryDocument(
            "ambiguous", requested, "symbol", limitations,
            candidates = matches.map { SymbolQueryCandidate(it.qualifiedName, it.id.value) },
        )
        val node = matches.single()
        val actualDepth = depth.coerceAtLeast(1)
        val actualLimit = limit.coerceAtLeast(1)
        val incoming = neighbors(graph, node.id, actualDepth, actualLimit, true)
        val outgoing = neighbors(graph, node.id, actualDepth, actualLimit, false)
        val memberEdges = graph.outgoingEdgesFrom(node.id).filter { it.kind == EdgeKind.MEMBER }
        val members = memberEdges.mapNotNull { edge -> graph.node(edge.target)?.toNeighbor(listOf("member"), 1) }
        val owner = graph.incomingEdgesTo(node.id).firstOrNull { it.kind == EdgeKind.MEMBER }
            ?.source?.let(graph::node)?.toNeighbor(listOf("member"), 1)
        val direct = reachability.retentionEvidenceFor(node.id).firstOrNull()
        val reachable = node.id in reachability.reachableNodeIds
        val path = reachability.pathFromRootTo(node.id)?.mapNotNull(graph::node)?.map { it.qualifiedName }
        val state = when {
            direct?.reason == RetentionReason.KEEP_ANNOTATED_MEMBER -> "retainedByMember"
            direct != null -> "retained"
            reachable -> "reachable"
            else -> "unreachable"
        }
        return SymbolQueryDocument(
            "found", requested, "symbol", limitations,
            result = SymbolQueryResult(
                node.toSubject(),
                SymbolReachability(
                    state,
                    direct?.reason,
                    path?.takeIf { direct == null },
                    state == "unreachable" && node.id in suppressedByBaseline,
                ),
                incoming.items,
                outgoing.items,
                members.take(actualLimit),
                owner,
                SymbolQueryTruncation(incoming.truncated, outgoing.truncated, members.size > actualLimit),
            ),
        )
    }

    private fun neighbors(graph: CodeGraph, subject: NodeId, depth: Int, limit: Int, incoming: Boolean): NeighborPage {
        data class Visit(val id: NodeId, val depth: Int, val edges: List<String>)
        val seen = mutableSetOf(subject)
        val queue = ArrayDeque<Visit>()
        val firstEdges = if (incoming) graph.incomingEdgesTo(subject) else graph.outgoingEdgesFrom(subject)
        firstEdges.filter { it.kind.impliesUsage }.groupBy { if (incoming) it.source else it.target }
            .toSortedMap().forEach { (id, edges) ->
                queue += Visit(id, 1, edges.map { it.kind.name.lowerCamel() }.distinct().sorted())
            }
        val found = mutableListOf<SymbolQueryNeighbor>()
        while (queue.isNotEmpty()) {
            val visit = queue.removeFirst()
            if (!seen.add(visit.id)) continue
            graph.node(visit.id)?.let { found += it.toNeighbor(visit.edges, visit.depth) }
            if (visit.depth >= depth) continue
            val edges = if (incoming) graph.incomingEdgesTo(visit.id) else graph.outgoingEdgesFrom(visit.id)
            edges.filter { it.kind.impliesUsage }.groupBy { if (incoming) it.source else it.target }
                .toSortedMap().forEach { (id, grouped) ->
                    queue += Visit(id, visit.depth + 1, grouped.map { it.kind.name.lowerCamel() }.distinct().sorted())
                }
        }
        return NeighborPage(found.take(limit), found.size > limit)
    }

    private fun GraphNode.toSubject() = SymbolQuerySubject(
        name, qualifiedName, kind.name.lowerCamel(), moduleName, id.value, visibility.name.lowerCamel(), location,
    )

    private fun GraphNode.toNeighbor(edges: List<String>, depth: Int) = SymbolQueryNeighbor(
        name, qualifiedName, kind.name.lowerCamel(), id.value, moduleName, edges, depth, location,
    )

    private data class NeighborPage(val items: List<SymbolQueryNeighbor>, val truncated: Boolean)

    private fun String.lowerCamel(): String = lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
    }
}
