package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.qualifiedName
import java.util.BitSet

/** 순회 방향이다. `DEPENDENCIES`는 root가 기대는 쪽(호출·참조 대상), `DEPENDENTS`는 root에 기대는 쪽이다. */
public enum class TraversalDirection { DEPENDENCIES, DEPENDENTS }

/**
 * 따를 dispatch 간선의 범위다. 뒤로 갈수록 간선 집합이 넓어진다.
 *
 * @property label 문서 `dispatch` 필드에 싣는 표식이다
 * @property tiers 이 방식이 따르는 간선 등급이다
 */
public enum class TraversalDispatch(public val label: String, internal val tiers: Set<TraversalEdgeTier>) {
    DIRECT("direct", setOf(TraversalEdgeTier.DIRECT)),
    BOUND("bound", setOf(TraversalEdgeTier.DIRECT, TraversalEdgeTier.BOUND)),
    CANDIDATES("candidates", setOf(TraversalEdgeTier.DIRECT, TraversalEdgeTier.BOUND, TraversalEdgeTier.CANDIDATE)),
    ALL("all", TraversalEdgeTier.entries.toSet()),
}

/** 도달 정점의 근거 등급이다. `direct ⊂ bound ⊂ candidate`로 간선 집합이 포개진다. */
public enum class TraversalEvidence { DIRECT, BOUND, CANDIDATE }

/**
 * 순회의 시작점이다.
 *
 * @property id 생산자 id다. 해석한 root면 정점 usr, 못 한 요청이면 원문이다
 * @property node 해석한 정점이다. null이면 `root-not-found`다
 * @property unresolvedCalls 이 정점 자신의 잇지 못한 호출 지점 수다
 */
public data class TraversalRoot(val id: String, val node: GraphNode?, val unresolvedCalls: Int = 0)

/**
 * 순회가 도달한 정점 하나다.
 *
 * @property via 가장 짧은 경로 하나의 직전 정점(root id 또는 다른 도달 정점의 usr)이다
 * @property depth 이 정점에 닿는 가장 가까운(자기 제외) root까지의 간선 수다
 * @property roots 이 정점에 닿는 모든(자기 제외) root 인덱스의 오름차순 전체 목록이다. 64개 상한은 codec이 적용한다
 * @property relationships via → 이 정점 사이의 허용된 간선 관계 이름이다
 * @property evidence root마다 가장 강한 등급을 구한 뒤 그중 가장 약한 것이다
 */
public data class TraversalReached(
    val node: GraphNode,
    val via: String,
    val depth: Int,
    val roots: List<Int>,
    val relationships: List<String>,
    val evidence: TraversalEvidence,
    val unresolvedCalls: Int = 0,
)

/** 한 번의 다중 root 순회 결과다. [reached]는 (depth, usr) 오름차순이다. */
public data class LanguageTraversalResult(
    val direction: TraversalDirection,
    val dispatch: TraversalDispatch,
    val roots: List<TraversalRoot>,
    val reached: List<TraversalReached>,
    val depthTruncated: Boolean,
    val reachedTruncated: Boolean,
    val limitations: List<String>,
) {
    /** 해석하지 못한 root 요청이 있는지다. */
    public val rootNotFound: Boolean get() = roots.any { it.node == null }
}

/**
 * `language-traversal` v1용 다중 root 순회다(isthmus docs/LANGUAGE-TRAVERSAL.md).
 *
 * root마다 따로 순회하지 않고 한 번에 계산한다.
 * - depth·via: 정점마다 가장 가까운 서로 다른 root 두 개를 기록하는 다중 출발 BFS다. 두 번째 root는
 *   "다른 root에서 닿은 root" 항목의 depth(자기 제외 기준)를 구하는 데 쓴다.
 * - roots·evidence: 등급별 간선 부분 그래프를 강연결요소로 접고 위상 순서로 root 비트 집합을 전파한다.
 *   정점의 evidence는 전체 root 집합이 어느 등급 부분 그래프의 도달 집합에 모두 들어가는지로 정한다.
 * roots·evidence는 depth 상한과 무관한 실제 도달 관계이고, depth 상한은 목록에 싣는 정점만 자른다.
 */
public object LanguageTraversal {
    /** 교환 형식의 depth 상한이다. */
    public const val MAX_DEPTH: Int = 128

    /** 교환 형식의 도달 정점 상한이다. */
    public const val MAX_REACHED: Int = 100_000

    /** 교환 형식의 root 상한이다. */
    public const val MAX_ROOTS: Int = 10_000

    /**
     * @param requested root 요청이다. 정확한 usr를 우선하고, 없으면 유일한 한정 이름·이름으로 해석한다
     * @param enclosuresCaptured snapshot이 어휘적 소속 사실을 실었는지다
     * @param callbackFactsCaptured snapshot이 콜백 값 흐름 관측을 실었는지다. 거짓이면 콜백 간선이 없다는 한계를 알린다
     */
    public fun traverse(
        graph: CodeGraph,
        requested: List<String>,
        direction: TraversalDirection,
        dispatch: TraversalDispatch = TraversalDispatch.CANDIDATES,
        maxDepth: Int = MAX_DEPTH,
        maxReached: Int = MAX_REACHED,
        enclosuresCaptured: Boolean = true,
        callbackFactsCaptured: Boolean = true,
    ): LanguageTraversalResult = traverse(graph, TraversalEdges.assemble(graph, enclosuresCaptured), requested, direction, dispatch,
        maxDepth, maxReached, enclosuresCaptured, callbackFactsCaptured)

    /** 이미 만든 순회 간선으로 순회한다. 테스트가 임의의 콜백 간선으로 계산을 검증할 때 쓴다. */
    internal fun traverse(
        graph: CodeGraph,
        assembled: TraversalGraph,
        requested: List<String>,
        direction: TraversalDirection,
        dispatch: TraversalDispatch = TraversalDispatch.CANDIDATES,
        maxDepth: Int = MAX_DEPTH,
        maxReached: Int = MAX_REACHED,
        enclosuresCaptured: Boolean = true,
        callbackFactsCaptured: Boolean = true,
    ): LanguageTraversalResult {
        require(maxDepth in 1..MAX_DEPTH && maxReached in 1..MAX_REACHED && requested.size <= MAX_ROOTS)
        val edges = assembled.edges
        val unresolved = graph.externalCalls.filter { it.isUnresolvedTarget() }.groupingBy { it.caller }.eachCount()
        val roots = resolveRoots(graph, requested, unresolved)
        val space = TraversalSpace(graph, edges, direction, dispatch)
        val computation = space.compute(roots.map { root -> root.node?.let { space.index.getValue(it.id) } ?: -1 }, maxDepth)
        val (reached, reachedTruncated) = cap(computation.rows.map { row -> row.toReached(space, roots, unresolved) }, roots, maxReached)
        return LanguageTraversalResult(direction, dispatch, roots, reached, computation.depthTruncated, reachedTruncated,
            limitations(Limits(edges, direction, dispatch, enclosuresCaptured, callbackFactsCaptured, assembled.callbacks), roots,
                computation.depthTruncated, reachedTruncated, maxDepth, maxReached))
    }

    /** 한계 문구를 만드는 데 쓰는 순회 설정과 간선 집계다. */
    private data class Limits(
        val edges: List<TraversalEdge>, val direction: TraversalDirection, val dispatch: TraversalDispatch,
        val enclosuresCaptured: Boolean, val callbackFactsCaptured: Boolean, val callbacks: CallbackFlowSummary,
    )

    /** 요청을 입력 순서대로 root로 해석한다. 같은 정점을 가리키는 뒤 요청은 버린다(root id는 유일해야 한다). */
    private fun resolveRoots(graph: CodeGraph, requested: List<String>, unresolved: Map<NodeId, Int>): List<TraversalRoot> {
        val seen = mutableSetOf<String>()
        return requested.mapNotNull { text ->
            val node = graph.node(NodeId(text)) ?: graph.nodes.values.filter { it.qualifiedName == text || it.name == text }.singleOrNull()
            val id = node?.id?.value ?: text
            if (!seen.add(id)) null else TraversalRoot(id, node, node?.let { unresolved[it.id] } ?: 0)
        }
    }

    /**
     * 도달 정점 상한을 (depth, usr) 순서로 적용한다. via가 잘린 정점을 가리키는 항목(순환 root 항목뿐)은 함께 뺀다.
     */
    private fun cap(rows: List<TraversalReached>, roots: List<TraversalRoot>, maxReached: Int): Pair<List<TraversalReached>, Boolean> {
        if (rows.size <= maxReached) return rows to false
        var kept = rows.take(maxReached)
        val rootIds = roots.map { it.id }.toSet()
        while (true) {
            val usrs = kept.map { it.node.id.value }.toSet()
            val valid = kept.filter { it.via in rootIds || it.via in usrs }
            if (valid.size == kept.size) return kept to true
            kept = valid
        }
    }

    private fun limitations(
        limits: Limits, roots: List<TraversalRoot>, depthTruncated: Boolean, reachedTruncated: Boolean, maxDepth: Int, maxReached: Int,
    ): List<String> = buildList {
        val (all, direction, dispatch, enclosuresCaptured, callbackFactsCaptured, callbacks) = limits
        // 정방향 순회는 콜백 간선을 따르지 않으므로 제외 수에도 넣지 않는다.
        val edges = all.filter { !it.terminal || direction == TraversalDirection.DEPENDENTS }
        val lambda = edges.count { it.tier == TraversalEdgeTier.LAMBDA }
        if (lambda > 0 && TraversalEdgeTier.LAMBDA !in dispatch.tiers) add("lambda-dispatch-excluded: $lambda callback dispatch candidate edge(s) " +
            "into lambda or anonymous-class bodies (FunctionN/SAM invoke) were not followed; lexical containment links those bodies to " +
            "their enclosing declarations; use --dispatch all to follow them")
        val excluded = edges.count { it.tier != TraversalEdgeTier.LAMBDA && it.tier !in dispatch.tiers }
        if (excluded > 0) add("dispatch-excluded: $excluded ${dispatch.label}-mode dispatch edge(s) of a weaker tier were not followed")
        if (!enclosuresCaptured) add("lexical-enclosures-unavailable: the snapshot predates enclosing-declaration facts; lambda dispatch " +
            "candidates are followed as candidate edges; recapture the snapshot with this kartograph version")
        if (direction == TraversalDirection.DEPENDENTS) addAll(callbackLimitations(edges, dispatch, callbackFactsCaptured, callbacks))
        if (TraversalEdgeTier.BOUND in dispatch.tiers) add("bound-dispatch-closed-world: bound dispatch assumes the analyzed classes " +
            "contain every implementation of project types; runtime proxies, mocks and classes outside the snapshot are not modeled")
        add("unresolved-calls: counts unresolved external virtual dispatch, runtime-modeled reflective calls without resolved values " +
            "and invokedynamic sites with unmodeled bootstraps")
        val missing = roots.count { it.node == null }
        if (missing > 0) add("root-not-found: $missing requested root(s) did not match exactly one graph declaration; pass exact JVM USRs")
        if (depthTruncated) add("depth-limit: some reachable declarations are more than $maxDepth edge(s) from every root and are not listed")
        if (reachedTruncated) add("reached-limit: more than $maxReached reachable declarations; the (depth, usr) prefix is listed")
    }
}

/**
 * 역방향 순회의 콜백 한계 문구다. 콜백 간선으로 닿은 함수는 그 호출 문맥에서만 영향을 받으므로 다른 호출자로 퍼뜨리지
 * 않는다는 점, 실행자를 잇지 못한 흐름 수, 옛 snapshot이면 관측이 없다는 점을 알린다.
 */
private fun callbackLimitations(
    edges: List<TraversalEdge>, dispatch: TraversalDispatch, captured: Boolean, callbacks: CallbackFlowSummary,
): List<String> = buildList {
    if (!captured) {
        add("callback-facts-unavailable: the snapshot predates callback value-flow facts; functions that invoke lambda arguments " +
            "are not linked to the lambda bodies; recapture the snapshot with this kartograph version")
        return@buildList
    }
    val followed = edges.count { it.terminal && it.tier in dispatch.tiers }
    if (followed > 0) add("callback-flow: ${callbacks.bound} bound and ${callbacks.candidate} candidate lambda argument flow(s) link " +
        "functions that invoke or forward a lambda argument to its body; those functions are listed for that call context only and " +
        "are not expanded to their other callers")
    if (callbacks.unresolved > 0) add("callback-flow-unresolved: ${callbacks.unresolved} lambda argument flow(s) reached no invocation " +
        "before escaping (${callbacks.unresolvedReasons.entries.joinToString(", ") { "${it.key} ${it.value}" }}; forwarding depth limit " +
        "${CallbackFlows.MAX_FORWARD_DEPTH}); their eventual invokers are not linked")
}

/**
 * 인덱스 기반 인접 목록과 등급별 부분 그래프를 한 번만 만든다.
 *
 * 역방향 순회에서는 콜백 간선의 호출자 G마다 그림자 정점을 하나 더 둔다. 콜백 간선은 람다 본문(또는 그 그림자)에서
 * G의 그림자로만 이어지고, 그림자는 다른 콜백 간선으로만 나간다. 그래서 G는 람다 본문의 root·depth·evidence를 받아
 * 목록에 오르지만, G의 다른 호출자(다른 람다를 넘기는 화면)로는 퍼지지 않는다. 출력에서 그림자는 본 정점과 합친다.
 */
private class TraversalSpace(graph: CodeGraph, edges: List<TraversalEdge>, direction: TraversalDirection, dispatch: TraversalDispatch) {
    val ids: List<NodeId> = graph.nodeIds
    val nodes: List<GraphNode> = ids.map(graph.nodes::getValue)
    val index: Map<NodeId, Int> = ids.withIndex().associate { it.value to it.index }
    private val allowed = edges.filter { it.tier in dispatch.tiers && (!it.terminal || direction == TraversalDirection.DEPENDENTS) }
    private val callbacks = allowed.filter { it.terminal && it.source != it.target }
    private val shadowed: List<Int> = callbacks.map { index.getValue(it.source) }.distinct().sorted()
    private val shadowIndex: Map<Int, Int> = shadowed.withIndex().associate { it.value to ids.size + it.index }

    /** 본 정점과 그림자 정점을 합친 수다. 그림자는 [NodeId] 순서 뒤에 붙는다. */
    val vertexCount: Int = ids.size + shadowed.size

    /** 정점(본·그림자)의 본 정점 인덱스다. */
    val realOf: IntArray = IntArray(vertexCount) { if (it < ids.size) it else shadowed[it - ids.size] }

    /** 본 정점의 그림자 인덱스다. 없으면 -1이다. */
    fun shadowOf(vertex: Int): Int = shadowIndex[vertex] ?: -1

    fun usrOf(vertex: Int): String = ids[realOf[vertex]].value

    /** 순회 방향 기준 (from, to, 관계, 등급 순위)다. 등급 순위는 evidence 단계(0 direct, 1 bound, 2 candidate)다. */
    private val oriented: List<OrientedEdge> = allowed.filter { !it.terminal }.map { edge ->
        val (from, to) = if (direction == TraversalDirection.DEPENDENTS) edge.target to edge.source else edge.source to edge.target
        OrientedEdge(index.getValue(from), index.getValue(to), edge.relationship, levelOf(edge.tier))
    }.plus(callbacks.flatMap { edge ->
        val body = index.getValue(edge.target)
        val caller = shadowIndex.getValue(index.getValue(edge.source))
        listOfNotNull(body, shadowIndex[body]).map { from -> OrientedEdge(from, caller, edge.relationship, levelOf(edge.tier)) }
    }).filter { it.from != it.to }

    /** 이 dispatch 방식이 쓰는 evidence 단계 수다. */
    val levels: Int = (oriented.maxOfOrNull { it.level } ?: 0) + 1
    val successors: Array<IntArray> = adjacency(oriented, levels - 1, forward = true)
    val predecessors: Array<IntArray> = adjacency(oriented, levels - 1, forward = true.not())
    private val relationships: Map<Long, List<String>> = oriented.groupBy({ it.from.toLong() * vertexCount + it.to }, { it.relationship })
        .mapValues { (_, names) -> names.distinct().sorted() }

    fun relationshipsOf(from: Int, to: Int): List<String> = relationships[from.toLong() * vertexCount + to].orEmpty()

    /** 단계 [level] 이하 간선만 쓴 인접 목록이다. */
    fun adjacency(level: Int): Array<IntArray> = adjacency(oriented, level, forward = true)

    private fun adjacency(edges: List<OrientedEdge>, level: Int, forward: Boolean): Array<IntArray> {
        val lists = Array(vertexCount) { mutableSetOf<Int>() }
        edges.filter { it.level <= level }.forEach { if (forward) lists[it.from] += it.to else lists[it.to] += it.from }
        return Array(vertexCount) { lists[it].sorted().toIntArray() }
    }

    fun compute(rootVertices: List<Int>, maxDepth: Int): TraversalComputation = TraversalComputation.run(this, rootVertices, maxDepth)

    private data class OrientedEdge(val from: Int, val to: Int, val relationship: String, val level: Int)

    private companion object {
        fun levelOf(tier: TraversalEdgeTier): Int = when (tier) {
            TraversalEdgeTier.DIRECT -> 0
            TraversalEdgeTier.BOUND -> 1
            TraversalEdgeTier.CANDIDATE, TraversalEdgeTier.LAMBDA -> 2
        }
    }
}

/**
 * 목록에 실을 도달 정점 한 줄의 인덱스 표현이다. [viaRoot]가 0 이상이면 via는 그 인덱스의 root, 아니면 [viaUsr]다.
 * [relationships]는 via → 정점(또는 그 그림자) 사이의 관계 이름이다.
 */
private data class ReachedRow(
    val vertex: Int, val viaRoot: Int, val viaUsr: String, val relationships: List<String>, val depth: Int, val roots: List<Int>, val level: Int,
) {
    fun toReached(space: TraversalSpace, rootEntries: List<TraversalRoot>, unresolved: Map<NodeId, Int>): TraversalReached {
        val via = if (viaRoot >= 0) rootEntries[viaRoot].id else viaUsr
        val node = space.nodes[vertex]
        return TraversalReached(node, via, depth, roots, relationships, TraversalEvidence.entries[level], unresolved[node.id] ?: 0)
    }
}

/** 다중 출발 BFS와 등급별 도달 비트 집합으로 도달 정점을 한 번에 계산한다. */
private class TraversalComputation private constructor(
    private val space: TraversalSpace,
    private val rootVertices: List<Int>,
    private val maxDepth: Int,
) {
    private val size = space.vertexCount
    private val rootOf = IntArray(size) { -1 }.also { owner -> rootVertices.forEachIndexed { root, vertex -> if (vertex >= 0) owner[vertex] = root } }
    // 정점마다 가장 가까운 서로 다른 root 두 개(root 인덱스, 거리)다. -1은 없음이다.
    private val firstRoot = IntArray(size) { -1 }
    private val firstDistance = IntArray(size) { -1 }
    private val secondRoot = IntArray(size) { -1 }
    private val secondDistance = IntArray(size) { -1 }

    val rows = mutableListOf<ReachedRow>()
    var depthTruncated = false
        private set

    private fun execute() {
        nearestRoots()
        val reach = (0 until space.levels).map { level -> ReachSets.compute(space.adjacency(level), rootVertices, size) }
        val full = reach.last()
        for (vertex in space.ids.indices) {
            val roots = rootsOf(full, vertex).also { if (rootOf[vertex] >= 0) it.clear(rootOf[vertex]) }
            if (roots.isEmpty) continue
            val row = row(vertex, roots, reach)
            if (row == null) depthTruncated = true else rows += row
        }
        rows.sortWith(compareBy({ it.depth }, { space.ids[it.vertex].value }))
    }

    /** 본 정점과 그 그림자에 닿는 root의 합집합이다. */
    private fun rootsOf(sets: ReachSets, vertex: Int): BitSet = sets.rootsOf(vertex).also { roots ->
        space.shadowOf(vertex).takeIf { it >= 0 }?.let { roots.or(sets.rootsOf(it)) }
    }

    /** 가장 가까운 서로 다른 root 두 개를 BFS 순서로 기록한다. 먼저 도착한 두 root가 가장 가까운 두 root다. */
    private fun nearestRoots() {
        val queue = ArrayDeque<IntArray>()
        rootVertices.forEachIndexed { root, vertex -> if (vertex >= 0 && accept(vertex, root, 0)) queue += intArrayOf(vertex, root, 0) }
        while (queue.isNotEmpty()) {
            val (vertex, root, distance) = queue.removeFirst()
            if (distance >= maxDepth) continue
            for (next in space.successors[vertex]) if (accept(next, root, distance + 1)) queue += intArrayOf(next, root, distance + 1)
        }
    }

    private fun accept(vertex: Int, root: Int, distance: Int): Boolean = when {
        firstRoot[vertex] == -1 -> { firstRoot[vertex] = root; firstDistance[vertex] = distance; true }
        firstRoot[vertex] == root || secondRoot[vertex] != -1 -> false
        else -> { secondRoot[vertex] = root; secondDistance[vertex] = distance; true }
    }

    /** 자기 root를 제외한 가장 가까운 root까지의 거리다. 없으면 -1이다. */
    private fun distanceExcluding(vertex: Int, excluded: Int): Int =
        if (firstRoot[vertex] != excluded) firstDistance[vertex] else secondDistance[vertex]

    /**
     * 정점 한 줄을 만든다. 본 정점과 그림자 중 더 가까운 쪽의 depth를 쓴다. depth 상한 안에 목격 경로가 없으면 null이다.
     */
    private fun row(vertex: Int, roots: BitSet, reach: List<ReachSets>): ReachedRow? {
        val own = rootOf[vertex]
        val targets = listOf(vertex, space.shadowOf(vertex)).filter { it >= 0 }
        val depth = targets.map { depthOf(it, vertex, own) }.filter { it >= 0 }.minOrNull() ?: return null
        if (depth !in 1..maxDepth) return null
        val level = reach.indexOfFirst { sets -> rootsOf(sets, vertex).let { reached -> roots.stream().allMatch(reached::get) } }
        return witness(vertex, targets, own, depth).copy(depth = depth, roots = roots.stream().toArray().toList(), level = level)
    }

    /**
     * 자기 root를 제외한 가장 가까운 root까지의 거리다. root가 아닌 본 정점과 그림자는 BFS 거리 그대로다. root인 본 정점은
     * 자기 거리가 0이므로 선행 정점 거리로 구한다. 그림자는 root가 아니므로 자기 root를 뺀 두 번째 거리를 쓴다.
     */
    private fun depthOf(target: Int, vertex: Int, own: Int): Int = when {
        own < 0 -> firstDistance[target]
        target != vertex -> distanceExcluding(target, own)
        else -> space.predecessors[vertex].filter { space.realOf[it] != vertex }
            .mapNotNull { predecessor -> distanceExcluding(predecessor, own).takeIf { it >= 0 }?.plus(1) }.minOrNull() ?: -1
    }

    /**
     * depth - 1 거리의 선행 정점 중 usr가 가장 작은 것을 via로 고른다. 선행 정점이 root 거리 0이면 via는 그 root다.
     * 본 정점과 그림자의 선행 정점을 함께 보고, 같은 via의 관계 이름은 합친다.
     */
    private fun witness(vertex: Int, targets: List<Int>, own: Int, depth: Int): ReachedRow {
        val candidates = targets.flatMap { target ->
            space.predecessors[target].filter { predecessor ->
                space.realOf[predecessor] != vertex && (if (own < 0) firstDistance[predecessor] else distanceExcluding(predecessor, own)) == depth - 1
            }.map { it to target }
        }
        val via = candidates.minOf { space.usrOf(it.first) }
        val chosen = candidates.filter { space.usrOf(it.first) == via }
        val relationships = chosen.flatMap { (predecessor, target) -> space.relationshipsOf(predecessor, target) }.distinct().sorted()
        val viaRoot = if (depth == 1) rootOf[chosen.first().first] else -1
        return ReachedRow(vertex, viaRoot, via, relationships, depth, emptyList(), 0)
    }

    companion object {
        fun run(space: TraversalSpace, rootVertices: List<Int>, maxDepth: Int): TraversalComputation =
            TraversalComputation(space, rootVertices, maxDepth).also { it.execute() }
    }
}

/**
 * 한 간선 부분 그래프에서 정점마다 닿는 root 비트 집합이다.
 * root에서 닿는 정점만 강연결요소로 접고, 위상 순서대로 선행 요소의 비트를 합친다.
 */
private class ReachSets private constructor(private val component: IntArray, private val bits: List<BitSet>) {
    /** 정점에 닿는 root 인덱스 집합의 사본이다. 닿지 않으면 빈 집합이다. */
    fun rootsOf(vertex: Int): BitSet = component[vertex].let { if (it < 0) BitSet() else bits[it].clone() as BitSet }

    companion object {
        fun compute(successors: Array<IntArray>, rootVertices: List<Int>, size: Int): ReachSets {
            val components = StronglyConnected(successors, rootVertices.filter { it >= 0 }, size)
            val bits = List(components.count) { BitSet() }
            rootVertices.forEachIndexed { root, vertex -> if (vertex >= 0) bits[components.componentOf[vertex]].set(root) }
            // Tarjan은 역위상 순서로 요소를 낸다. 큰 번호부터 처리하면 선행 요소가 먼저 끝난다.
            for (component in components.count - 1 downTo 0) {
                for (vertex in components.members[component]) for (next in successors[vertex]) {
                    val target = components.componentOf[next]
                    if (target != component) bits[target].or(bits[component])
                }
            }
            return ReachSets(components.componentOf, bits)
        }
    }
}

/** 출발 정점에서 닿는 부분 그래프의 반복형 Tarjan 강연결요소다. [componentOf]는 닿지 않으면 -1이다. */
private class StronglyConnected(private val successors: Array<IntArray>, starts: List<Int>, size: Int) {
    val componentOf = IntArray(size) { -1 }
    val members = mutableListOf<IntArray>()
    val count: Int get() = members.size
    private val order = IntArray(size) { -1 }
    private val low = IntArray(size)
    private val onStack = BooleanArray(size)
    private val stack = ArrayDeque<Int>()
    private var counter = 0

    init { starts.forEach { if (order[it] == -1) visit(it) } }

    private fun visit(start: Int) {
        val frames = ArrayDeque<IntArray>().apply { add(open(start)) }
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            val (vertex, position) = frame
            if (position < successors[vertex].size) {
                frame[1] = position + 1
                val next = successors[vertex][position]
                if (order[next] == -1) frames += open(next) else if (onStack[next]) low[vertex] = minOf(low[vertex], order[next])
                continue
            }
            frames.removeLast()
            frames.lastOrNull()?.let { parent -> low[parent[0]] = minOf(low[parent[0]], low[vertex]) }
            if (low[vertex] == order[vertex]) close(vertex)
        }
    }

    private fun open(vertex: Int): IntArray {
        order[vertex] = counter; low[vertex] = counter++
        stack.addLast(vertex); onStack[vertex] = true
        return intArrayOf(vertex, 0)
    }

    private fun close(root: Int) {
        val component = mutableListOf<Int>()
        do {
            val vertex = stack.removeLast()
            onStack[vertex] = false
            componentOf[vertex] = members.size
            component += vertex
        } while (vertex != root)
        members += component.toIntArray()
    }
}
