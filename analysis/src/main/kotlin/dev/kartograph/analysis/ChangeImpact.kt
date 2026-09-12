package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
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

/** 영향 후보가 변경 선언과 연결된 방식이다. 점수나 안전성 판정이 아니다. */
public enum class ImpactRelation { DIRECT, STRUCTURAL, TRANSITIVE, UNKNOWN }

/** 경로의 source 위치로 확인할 수 있는 test 여부다. 확인할 수 없으면 UNKNOWN이다. */
public enum class ImpactTestStatus { TEST, PRODUCTION, UNKNOWN }

/** 후보의 경로 증거가 관찰된 시점에 대해 얼마나 materialize 되었는지 나타낸다. */
public enum class ImpactPathStatus { COMPLETE, PARTIAL, UNAVAILABLE }

/** 결과 페이지를 구성할 때 사용하는 명시적인 정렬 방식이다. */
public enum class ImpactSort { USR, MODULE, FILE, TEST_STATUS, RELATION, PATH_STATUS, PATH_DEPTH }

/** 결과 페이지에 적용할 선택 조건이다. 같은 필드의 값은 OR, 서로 다른 필드는 AND다. */
public data class ImpactFilter(
    val modules: Set<String> = emptySet(),
    val affectedFiles: Set<String> = emptySet(),
    val kinds: Set<NodeKind> = emptySet(),
    val testStatus: ImpactTestStatus? = null,
    val relation: ImpactRelation? = null,
    val pathStatus: ImpactPathStatus? = null,
) {
    init {
        require(modules.all { it.isNotBlank() }) { "impact module filters must not be blank" }
        require(affectedFiles.all { it.isNotBlank() }) { "impact file filters must not be blank" }
    }
}

/** 경로 전체를 출력하지 못한 경우에도 누락 원인과 필요한 간선 수를 보존한다. */
public enum class ImpactPathOmissionReason { PATH_BUDGET }

public data class ImpactPathOmission(
    val revision: ImpactRevision,
    val reason: ImpactPathOmissionReason,
    val requiredEdges: Int,
    /** 경로 전체 대신 중복 없는 관계 종류만 보존해 누락 메모리를 제한한다. */
    val edgeKinds: List<EdgeKind> = emptyList(),
) {
    init { require(requiredEdges > 0) { "an omitted impact path must contain an edge" } }
}

/** 한 선언의 revision별 module/source 사실이다. null은 해당 사실을 확인하지 못했다는 뜻이다. */
public data class ImpactNodeFact(
    val revision: ImpactRevision,
    val module: String?,
    val location: dev.kartograph.core.SourceLocation?,
    val testStatus: ImpactTestStatus,
)

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
    /** 이 후보가 실제 역방향 탐색에서 관찰된 revision이다. presentIn과 다를 수 있다. */
    val observedIn: Set<ImpactRevision> = emptySet(),
    /** path budget 때문에 materialize하지 못한 revision별 경로다. */
    val pathOmissions: List<ImpactPathOmission> = emptyList(),
    /** base/current를 합치지 않은 module/source/test 사실이다. */
    val facts: List<ImpactNodeFact> = emptyList(),
    /** 경로에서 관찰한 관계 유형이다. 우선순위나 위험 점수가 아니다. */
    val relation: ImpactRelation = ImpactRelation.UNKNOWN,
    val pathStatus: ImpactPathStatus = ImpactPathStatus.UNAVAILABLE,
    val testStatus: ImpactTestStatus = ImpactTestStatus.UNKNOWN,
)

/** 입력에 대응하는 사실을 확정하지 못한 경우와 명시적으로 선택할 후보다. */
public data class ImpactUnresolved(val requested: String, val reason: String, val candidates: List<NodeId> = emptyList())

/** 결과 수·깊이·방문 예산에 따른 잘림을 각각 알린다. */
public data class ImpactTruncation(val results: Boolean = false, val depth: Boolean = false, val budget: Boolean = false)

/** 후보 집합의 한 축을 설명하는 결정적인 count bucket이다. null value는 unknown이다. */
public data class ImpactBucket(val value: String?, val count: Int)

/** 페이지 한도 전의 후보 집합과 필터 후 집합을 설명하는 요약이다. */
public data class ImpactSummary(
    val candidates: Int = 0,
    val byModule: List<ImpactBucket> = emptyList(),
    val byFile: List<ImpactBucket> = emptyList(),
    val byTestStatus: List<ImpactBucket> = emptyList(),
    val byRelation: List<ImpactBucket> = emptyList(),
    val byPathStatus: List<ImpactBucket> = emptyList(),
)

/** 전체 관찰 후보에 적용한 필터와 안정적인 페이지 상태다. */
public data class ImpactNavigation(
    val offset: Int = 0,
    val limit: Int = 500,
    val sort: ImpactSort = ImpactSort.USR,
    val filters: ImpactFilter = ImpactFilter(),
    val observed: ImpactSummary = ImpactSummary(),
    val filtered: ImpactSummary = ImpactSummary(),
    val returned: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/** 탐색·경로 예산과 실제 사용량을 함께 기록한다. */
public data class ImpactBudget(
    val visitLimit: Int = 100_000,
    val pathLimit: Int = 100_000,
    val visited: Map<ImpactRevision, Int> = emptyMap(),
    val visitLimitReached: Set<ImpactRevision> = emptySet(),
    val pathEdgesUsed: Int = 0,
    val pathOmissions: Int = 0,
)

/** 잠재적 변경 영향이며 실행 결과나 테스트 생략/삭제 안전성을 증명하지 않는다. */
public data class ImpactReport(
    val changed: List<ImpactNode>,
    val affected: List<ImpactNode>,
    val totalAffected: Int,
    val unresolved: List<ImpactUnresolved>,
    val limitations: List<String>,
    val truncated: ImpactTruncation,
    val navigation: ImpactNavigation = ImpactNavigation(),
    val budgets: ImpactBudget = ImpactBudget(),
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
        offset: Int = 0,
        filters: ImpactFilter = ImpactFilter(),
        sort: ImpactSort = ImpactSort.USR,
    ): ImpactReport {
        require(depth > 0 && limit > 0 && visitLimit > 0 && pathLimit > 0 && offset >= 0)
        val inputs = buildMap {
            base?.let { put(ImpactRevision.BASE, it) }
            put(ImpactRevision.CURRENT, current)
        }
        val nodeById = buildMap<NodeId, GraphNode> {
            base?.graph?.nodes?.forEach { (id, node) -> put(id, node) }
            current.graph.nodes.forEach { (id, node) -> put(id, node) }
        }
        val selected = inputs.mapValues { sortedSetOf<NodeId>() }
        val unresolved = mutableListOf<ImpactUnresolved>()
        val limitations = inputs.flatMap { (revision, input) ->
            input.limitations.map { "${revision.name.lowercase()}: $it" }
        }.toMutableList()
        for (symbol in symbols.distinct().sorted()) {
            val matches = inputs.values.flatMap { it.graph.nodes.values }
                .filter { it.id.value == symbol || it.name == symbol || it.qualifiedName == symbol }
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
        val pathOmissions = sortedMapOf<NodeId, MutableList<ImpactPathOmission>>()
        val observedIn = sortedMapOf<NodeId, MutableSet<ImpactRevision>>()
        val affectedIds = sortedSetOf<NodeId>()
        val visitedBy = sortedMapOf<ImpactRevision, Int>()
        val visitLimitReached = sortedSetOf<ImpactRevision>()
        var depthTruncated = false
        var budgetTruncated = false
        var pathEdgesUsed = 0
        var pathOmissionCount = 0
        for ((revision, input) in inputs) {
            val seeds = selected.getValue(revision)
            // dispatch 간선은 상위 선언→구현이다. 직접 변경한 계약의 하위 override도 영향에 포함한다.
            // 구현 변경이 상위 dispatch에 도달했다는 이유만으로 무관한 sibling 구현까지 확장하지 않는다.
            val contracts = seeds.toMutableSet()
            val contractQueue = ArrayDeque(seeds)
            while (contractQueue.isNotEmpty()) {
                for (edge in input.graph.outgoingEdgesFrom(contractQueue.removeFirst()).filter { it.kind == EdgeKind.OVERRIDE }) {
                    if (edge.target in contracts) continue
                    if (contracts.size >= visitLimit) {
                        budgetTruncated = true
                        visitLimitReached += revision
                        continue
                    }
                    contracts += edge.target
                    contractQueue += edge.target
                }
            }
            val visited = seeds.toMutableSet()
            if (seeds.size > visitLimit) {
                budgetTruncated = true
                visitLimitReached += revision
            }
            val pending = ArrayDeque(seeds.map { it to 0 })
            val towardChange = mutableMapOf<NodeId, Pair<NodeId, GraphEdge>>()
            val distances = mutableMapOf<NodeId, Int>()
            val pathKinds = mutableMapOf<NodeId, Set<EdgeKind>>()
            val root = seeds.associateWith { it }.toMutableMap()
            while (pending.isNotEmpty()) {
                val (target, distance) = pending.removeFirst()
                val incoming = input.graph.incomingEdgesTo(target).filter { it.kind.impliesUsage }.map { it.source to it }
                val overrides = if (target in contracts) input.graph.outgoingEdgesFrom(target)
                    .filter { it.kind == EdgeKind.OVERRIDE }.map { it.target to it } else emptyList()
                for ((dependent, edge) in (incoming + overrides).sortedWith(compareBy({ it.first }, { it.second }))) {
                    if (dependent in visited) continue
                    if (distance >= depth) {
                        depthTruncated = true
                        continue
                    }
                    if (visited.size >= visitLimit) {
                        budgetTruncated = true
                        visitLimitReached += revision
                        continue
                    }
                    visited += dependent
                    towardChange[dependent] = target to edge
                    distances[dependent] = distance + 1
                    val inheritedKinds = pathKinds[target].orEmpty()
                    pathKinds[dependent] = if (edge.kind in inheritedKinds) inheritedKinds else inheritedKinds + edge.kind
                    root[dependent] = root.getValue(target)
                    pending += dependent to distance + 1
                }
            }
            visitedBy[revision] = visited.size
            val affected = (visited - changedIds).sorted()
            for (id in affected) {
                affectedIds += id
                observedIn.getOrPut(id) { sortedSetOf() } += revision
                val requiredEdges = distances.getValue(id)
                if (requiredEdges > pathLimit - pathEdgesUsed) {
                    budgetTruncated = true
                    pathOmissionCount++
                    pathOmissions.getOrPut(id) { mutableListOf() } += ImpactPathOmission(
                        revision, ImpactPathOmissionReason.PATH_BUDGET, requiredEdges, pathKinds.getValue(id).sorted(),
                    )
                } else {
                    val chain = mutableListOf<GraphEdge>()
                    var cursor = id
                    while (cursor !in seeds) {
                        val (next, edge) = towardChange.getValue(cursor)
                        chain += edge
                        cursor = next
                    }
                    pathEdgesUsed += chain.size
                    val nodes = buildList {
                        var pathCursor = id
                        add(pathCursor)
                        chain.forEach { edge ->
                            pathCursor = when {
                                edge.source == pathCursor -> edge.target
                                edge.target == pathCursor -> edge.source
                                else -> error("impact path edge is not adjacent to its predecessor")
                            }
                            add(pathCursor)
                        }
                    }
                    paths.getOrPut(id) { mutableListOf() } += ImpactPath(revision, root.getValue(id), chain, nodes)
                }
            }
        }

        val retentionByRevision = inputs.mapValues { (_, input) -> input.retention.groupBy { it.nodeId } }

        fun describe(id: NodeId, witnesses: List<ImpactPath> = paths[id].orEmpty()): ImpactNode {
            val node = nodeById.getValue(id)
            val present = inputs.filterValues { it.graph.contains(id) }.keys.toSortedSet()
            val sortedPaths = witnesses.sortedWith(compareBy({ it.revision }, { it.changed }, { it.nodes.joinToString("\u0000") }))
            val omissions = pathOmissions[id].orEmpty().sortedWith(compareBy({ it.revision }, { it.reason }, { it.requiredEdges }))
            val facts = inputs.mapNotNull { (revision, input) -> input.graph.node(id)?.let { factNode ->
                ImpactNodeFact(revision, factNode.moduleName, factNode.location, testStatus(factNode.location))
            } }
            val knownTestStatuses = facts.map { it.testStatus }.distinct()
            val aggregateTestStatus = knownTestStatuses.singleOrNull() ?: ImpactTestStatus.UNKNOWN
            val observed = observedIn[id].orEmpty().toSortedSet()
            val available = sortedPaths.map { it.revision }.toSet()
            val pathStatus = when {
                observed.isEmpty() -> ImpactPathStatus.UNAVAILABLE
                observed.all { it in available } -> ImpactPathStatus.COMPLETE
                available.isEmpty() -> ImpactPathStatus.UNAVAILABLE
                else -> ImpactPathStatus.PARTIAL
            }
            return ImpactNode(
                node = node,
                presentIn = present,
                paths = sortedPaths,
                retention = inputs.flatMap { (revision, _) -> retentionByRevision.getValue(revision)[id].orEmpty().distinct()
                    .sortedWith(compareBy({ it.reason.name }, { it.location?.path.orEmpty() }, { it.location?.line ?: 0 },
                        { it.location?.column ?: 0 })).map { ImpactRetention(revision, it) } },
                observedIn = observed,
                pathOmissions = omissions,
                facts = facts,
                relation = relation(sortedPaths, omissions),
                pathStatus = pathStatus,
                testStatus = aggregateTestStatus,
            )
        }

        val changed = changedIds.map(::describe)
        val observedCandidates = affectedIds.map(::describe)
        val filteredCandidates = observedCandidates.filter { matches(it, filters) }
        val orderedCandidates = filteredCandidates.sortedWith(candidateComparator(sort))
        val pageEnd = (offset.toLong() + limit.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val returned = orderedCandidates.drop(offset).take(limit)
        val resultTruncated = orderedCandidates.isNotEmpty() && (offset > 0 || orderedCandidates.size > pageEnd)
        val observedSummary = summary(observedCandidates)
        val filteredSummary = summary(orderedCandidates)
        if (pathOmissionCount > 0) limitations += "path-budget: $pathOmissionCount candidate path omission(s) required more than the $pathLimit edge limit"
        if (visitLimitReached.isNotEmpty()) limitations += "visit-budget: reverse traversal reached the $visitLimit node limit for ${visitLimitReached.joinToString(",") { it.name.lowercase() }}"
        if (resultTruncated) limitations += "result-page: ${orderedCandidates.size - returned.size} matching candidate(s) are outside this page; use --offset, --limit or --all"
        limitations += "potential-impact: graph dependencies are review candidates; behavior changes, external consumers and runtime paths may require additional validation"
        if (inputs.values.any { it.graph.nodes.values.any { node -> node.location == null } }) {
            limitations += "missing-source-locations: some compiled declarations cannot be matched to changed source files"
        }
        return ImpactReport(
            changed = changed,
            affected = returned,
            totalAffected = affectedIds.size,
            unresolved = unresolved,
            limitations = limitations.distinct().sorted(),
            truncated = ImpactTruncation(resultTruncated, depthTruncated, budgetTruncated),
            navigation = ImpactNavigation(
                offset, limit, sort, filters, observedSummary, filteredSummary, returned.size,
                offset > 0 && orderedCandidates.isNotEmpty(), pageEnd < orderedCandidates.size,
            ),
            budgets = ImpactBudget(visitLimit, pathLimit, visitedBy, visitLimitReached, pathEdgesUsed, pathOmissionCount),
        )
    }

    private fun matches(item: ImpactNode, filter: ImpactFilter): Boolean {
        val facts = item.facts.ifEmpty { listOf(ImpactNodeFact(ImpactRevision.CURRENT, item.node.moduleName, item.node.location, item.testStatus)) }
        if (filter.modules.isNotEmpty() && facts.none { it.module != null && it.module in filter.modules }) return false
        if (filter.affectedFiles.isNotEmpty() && facts.none { it.location?.path in filter.affectedFiles }) return false
        if (filter.kinds.isNotEmpty() && item.node.kind !in filter.kinds) return false
        if (filter.testStatus != null && facts.none { it.testStatus == filter.testStatus }) return false
        if (filter.relation != null && item.relation != filter.relation) return false
        if (filter.pathStatus != null && item.pathStatus != filter.pathStatus) return false
        return true
    }

    private fun summary(items: List<ImpactNode>): ImpactSummary = ImpactSummary(
        candidates = items.size,
        byModule = buckets(items.flatMap { factValues(it) { fact -> fact.module } }),
        byFile = buckets(items.flatMap { factValues(it) { fact -> fact.location?.path } }),
        byTestStatus = buckets(items.flatMap { factValues(it) { fact -> fact.testStatus.name.lowercase() } }),
        byRelation = buckets(items.map { it.relation.name.lowercase() }),
        byPathStatus = buckets(items.map { it.pathStatus.name.lowercase() }),
    )

    private fun factValues(item: ImpactNode, value: (ImpactNodeFact) -> String?): List<String?> {
        val facts = item.facts.ifEmpty { listOf(ImpactNodeFact(ImpactRevision.CURRENT, item.node.moduleName, item.node.location, item.testStatus)) }
        return facts.map(value).distinct()
    }

    private fun buckets(values: List<String?>): List<ImpactBucket> = values.groupingBy { it }.eachCount()
        .entries.sortedWith(compareBy<Map.Entry<String?, Int>>({ it.key != null }, { it.key.orEmpty() }))
        .map { ImpactBucket(it.key, it.value) }

    private fun candidateComparator(sort: ImpactSort): Comparator<ImpactNode> = Comparator { left, right ->
        val primary = when (sort) {
            ImpactSort.USR -> compareValues(left.node.id.value, right.node.id.value)
            ImpactSort.MODULE -> compareNullable(firstValue(left) { it.module }, firstValue(right) { it.module })
            ImpactSort.FILE -> compareNullable(firstValue(left) { it.location?.path }, firstValue(right) { it.location?.path })
            ImpactSort.TEST_STATUS -> compareValues(left.testStatus.name, right.testStatus.name)
            ImpactSort.RELATION -> compareValues(left.relation.name, right.relation.name)
            ImpactSort.PATH_STATUS -> compareValues(left.pathStatus.name, right.pathStatus.name)
            ImpactSort.PATH_DEPTH -> compareValues(pathDepth(left), pathDepth(right))
        }
        if (primary != 0) primary else compareValues(left.node.id.value, right.node.id.value)
    }

    private fun firstValue(item: ImpactNode, value: (ImpactNodeFact) -> String?): String? =
        factValues(item, value).filterNotNull().sorted().firstOrNull()

    private fun compareNullable(left: String?, right: String?): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }

    private fun pathDepth(item: ImpactNode): Int = (item.paths.map { it.edges.size } + item.pathOmissions.map { it.requiredEdges })
        .minOrNull() ?: Int.MAX_VALUE

    private fun relation(paths: List<ImpactPath>, omissions: List<ImpactPathOmission>): ImpactRelation {
        val kinds = paths.flatMap { it.edges.map(GraphEdge::kind) } + omissions.flatMap { it.edgeKinds }
        return when {
            kinds.isEmpty() -> ImpactRelation.UNKNOWN
            kinds.any { it == EdgeKind.INHERITANCE || it == EdgeKind.OVERRIDE || it == EdgeKind.ANNOTATION } -> ImpactRelation.STRUCTURAL
            paths.any { it.edges.size == 1 } || omissions.any { it.requiredEdges == 1 } -> ImpactRelation.DIRECT
            else -> ImpactRelation.TRANSITIVE
        }
    }

    private fun testStatus(location: dev.kartograph.core.SourceLocation?): ImpactTestStatus {
        val parts = location?.path?.replace('\\', '/')?.split('/') ?: return ImpactTestStatus.UNKNOWN
        val sourceIndex = parts.indexOf("src")
        val root = if (sourceIndex >= 0) parts.getOrNull(sourceIndex + 1) else null
        return when {
            root in setOf("test", "androidTest", "testFixtures", "integrationTest") || parts.firstOrNull() in setOf("test", "tests") -> ImpactTestStatus.TEST
            root in setOf("main", "production", "prod", "debug", "release") -> ImpactTestStatus.PRODUCTION
            else -> ImpactTestStatus.UNKNOWN
        }
    }
}
