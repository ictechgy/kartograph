package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.qualifiedName

/** 한 시점의 그래프와 해당 시점에서 관측한 보존·한계다. */
public data class ImpactInput(
    val graph: CodeGraph,
    val retention: List<RetentionEvidence> = emptyList(),
    val limitations: List<String> = emptyList(),
)

/** 경로를 구성한 그래프 시점이며, 서로 다른 시점의 간선을 섞지 않는다. */
public enum class ImpactRevision { BASE, CURRENT }

/** 영향받는 선언에서 변경 선언까지의 실제 의존 간선 경로다. */
public data class ImpactPath(val revision: ImpactRevision, val changed: NodeId, val edges: List<GraphEdge>, val nodes: List<NodeId>)

/** 보존 근거가 관측된 시점을 유지한다. */
public data class ImpactRetention(val revision: ImpactRevision, val evidence: RetentionEvidence)

/** 존재 시점과 보존 근거를 포함한 변경/영향 선언이다. 보존은 호출 경로의 대체 근거가 아니다. */
public data class ImpactNode(
    val node: GraphNode,
    val presentIn: Set<ImpactRevision>,
    val paths: List<ImpactPath> = emptyList(),
    val retention: List<ImpactRetention> = emptyList(),
)

/** 입력에 대응하는 사실을 확정하지 못한 경우와 명시적으로 선택할 후보다. */
public data class ImpactUnresolved(val requested: String, val reason: String, val candidates: List<NodeId> = emptyList())

/** 결과 수·깊이·방문 예산에 따른 잘림을 각각 알린다. */
public data class ImpactTruncation(val results: Boolean = false, val depth: Boolean = false, val budget: Boolean = false)

/** 잠재적 변경 영향이며 실행 결과나 테스트 생략/삭제 안전성을 증명하지 않는다. */
public data class ImpactReport(
    val changed: List<ImpactNode>,
    val affected: List<ImpactNode>,
    val totalAffected: Int,
    val unresolved: List<ImpactUnresolved>,
    val limitations: List<String>,
    val truncated: ImpactTruncation,
)

/** 변경 선언의 역방향 사용 관계를 시점별로 탐색하는 순수 질의다. */
public object ChangeImpact {
    /** 파일은 상대 경로를 사용한다. basename만 있는 옛 입력은 후보를 넓히고 그 사실을 계량한다. */
    public fun analyze(
        current: ImpactInput,
        symbols: List<String> = emptyList(),
        files: List<String> = emptyList(),
        base: ImpactInput? = null,
        depth: Int = 100,
        limit: Int = 500,
        visitLimit: Int = 100_000,
        pathLimit: Int = 100_000,
    ): ImpactReport {
        require(depth > 0 && limit > 0 && visitLimit > 0 && pathLimit > 0)
        val inputs = buildMap {
            base?.let { put(ImpactRevision.BASE, it) }
            put(ImpactRevision.CURRENT, current)
        }
        val allNodes = base?.graph?.nodes.orEmpty() + current.graph.nodes
        val selected = inputs.mapValues { sortedSetOf<NodeId>() }
        val unresolved = mutableListOf<ImpactUnresolved>()
        val limitations = inputs.flatMap { (revision, input) ->
            input.limitations.map { "${revision.name.lowercase()}: $it" }
        }.toMutableList()
        for (symbol in symbols.distinct().sorted()) {
            val matches = inputs.values.flatMap { it.graph.nodes.values }.filter { it.id.value == symbol || it.name == symbol || it.qualifiedName == symbol }
                .map { it.id }.distinct().sorted()
            when (matches.size) {
                0 -> unresolved += ImpactUnresolved(symbol, "notFound")
                1 -> inputs.forEach { (revision, input) -> if (input.graph.contains(matches.single())) selected.getValue(revision) += matches.single() }
                else -> unresolved += ImpactUnresolved(symbol, "ambiguous", matches)
            }
        }
        var fallbackMatches = 0
        for (file in files.distinct().sorted()) {
            var matched = false
            for ((revision, input) in inputs) {
                val nodes = input.graph.nodes.values.filter { node ->
                    val path = node.location?.path ?: return@filter false
                    if (path == file) true else if ('/' !in path && path == file.substringAfterLast('/')) {
                        fallbackMatches++
                        true
                    } else false
                }.map { it.id }
                val retained = input.retention.filter { it.location?.path == file && input.graph.contains(it.nodeId) }.map { it.nodeId }
                selected.getValue(revision).addAll(nodes + retained)
                if (nodes.isNotEmpty() || retained.isNotEmpty()) matched = true
            }
            if (!matched) unresolved += ImpactUnresolved(file, "unmappedFile")
        }
        if (fallbackMatches > 0) limitations += "source-file-candidates: $fallbackMatches node match(es) used a source filename instead of an exact project-relative path"
        val changedIds = selected.values.flatten().toSortedSet()
        val paths = sortedMapOf<NodeId, MutableList<ImpactPath>>()
        val affectedIds = sortedSetOf<NodeId>()
        var depthTruncated = false
        var budgetTruncated = false
        var remainingPathEdges = pathLimit
        for ((revision, input) in inputs) {
            val seeds = selected.getValue(revision)
            // dispatch 간선은 상위 선언→구현이다. 직접 변경한 계약의 하위 override도 영향에 포함한다.
            // 구현 변경이 상위 dispatch에 도달했다는 이유만으로 무관한 sibling 구현까지 확장하지 않는다.
            val contracts = seeds.toMutableSet()
            val contractQueue = ArrayDeque(seeds)
            while (contractQueue.isNotEmpty()) {
                for (edge in input.graph.outgoingEdgesFrom(contractQueue.removeFirst()).filter { it.kind == dev.kartograph.core.EdgeKind.OVERRIDE }) {
                    if (edge.target in contracts) continue
                    if (contracts.size >= visitLimit) { budgetTruncated = true; continue }
                    contracts += edge.target
                    contractQueue += edge.target
                }
            }
            val visited = seeds.toMutableSet()
            if (seeds.size > visitLimit) budgetTruncated = true
            val pending = ArrayDeque(seeds.map { it to 0 })
            val towardChange = mutableMapOf<NodeId, Pair<NodeId, GraphEdge>>()
            val root = seeds.associateWith { it }.toMutableMap()
            while (pending.isNotEmpty()) {
                val (target, distance) = pending.removeFirst()
                val incoming = input.graph.incomingEdgesTo(target).filter { it.kind.impliesUsage }.map { it.source to it }
                val overrides = if (target in contracts) input.graph.outgoingEdgesFrom(target)
                    .filter { it.kind == dev.kartograph.core.EdgeKind.OVERRIDE }.map { it.target to it } else emptyList()
                for ((dependent, edge) in (incoming + overrides).sortedWith(compareBy({ it.first }, { it.second }))) {
                    if (dependent in visited) continue
                    if (distance >= depth) { depthTruncated = true; continue }
                    if (visited.size >= visitLimit) { budgetTruncated = true; continue }
                    visited += dependent
                    towardChange[dependent] = target to edge
                    root[dependent] = root.getValue(target)
                    pending += dependent to distance + 1
                }
            }
            val affected = (visited - changedIds).sorted()
            affectedIds.addAll(affected)
            // 전체 관측 수는 유지하되 출력하지 않을 긴 경로를 만들지 않는다.
            for (id in affected.take(limit)) {
                val chain = mutableListOf<GraphEdge>()
                val nodes = mutableListOf(id)
                var cursor = id
                while (cursor !in seeds) {
                    val (next, edge) = towardChange.getValue(cursor)
                    chain += edge
                    cursor = next
                    nodes += cursor
                }
                if (chain.size > remainingPathEdges) { budgetTruncated = true; continue }
                remainingPathEdges -= chain.size
                paths.getOrPut(id) { mutableListOf() } += ImpactPath(revision, root.getValue(id), chain, nodes)
            }
        }
        fun describe(id: NodeId, witnesses: List<ImpactPath> = emptyList()): ImpactNode = ImpactNode(
            allNodes.getValue(id), inputs.filterValues { it.graph.contains(id) }.keys, witnesses,
            inputs.flatMap { (revision, input) -> input.retention.filter { it.nodeId == id }.distinct()
                .sortedWith(compareBy({ it.reason.name }, { it.location?.path.orEmpty() }, { it.location?.line ?: 0 },
                    { it.location?.column ?: 0 })).map { ImpactRetention(revision, it) } },
        )
        limitations += "potential-impact: graph dependencies are review candidates; behavior changes, external consumers and runtime paths may require additional validation"
        if (inputs.values.any { it.graph.nodes.values.any { node -> node.location == null } }) {
            limitations += "missing-source-locations: some compiled declarations cannot be matched to changed source files"
        }
        return ImpactReport(changedIds.map { describe(it) }, paths.entries.take(limit).map { describe(it.key, it.value) },
            affectedIds.size, unresolved, limitations.distinct().sorted(), ImpactTruncation(affectedIds.size > limit, depthTruncated, budgetTruncated))
    }
}
