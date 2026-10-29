package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation

/** source 관찰만의 독립적인 페이지다. graph traversal 옵션에는 영향을 주지 않는다. */
public data class SourceUsePageRequest(val limit: Int = 100, val offset: Int = 0) {
    init {
        require(limit in 1..1_000) { "source limit must be between 1 and 1000" }
        require(offset in 0..200_000) { "source offset must be between 0 and 200000" }
    }
}

/** 현재 graph view의 선언 사실이며 원본 selector 위치와 구분한다. */
public data class SourceUseNodeFact(val usr: NodeId, val module: String?, val location: SourceLocation?)

/** 한 원본 선택과 현재 graph의 정확한 BYTECODE CALL 상관 여부다. */
public class SourceUseObservation internal constructor(
    public val use: CompilerSourceUse,
    public val caller: SourceUseNodeFact,
    public val target: SourceUseNodeFact,
    public val matchingBytecodeCall: Boolean,
    directions: List<String>,
) {
    public val directions: List<String> = freezeSourceUseList(directions)

    override fun equals(other: Any?): Boolean = other is SourceUseObservation && use == other.use &&
        caller == other.caller && target == other.target && matchingBytecodeCall == other.matchingBytecodeCall &&
        directions == other.directions

    override fun hashCode(): Int = listOf(use, caller, target, matchingBytecodeCall, directions).hashCode()
}

/** 전체 선택의 관찰 수와 반환 페이지의 수를 분리하며 unit binding은 반환 행에 한정한다. */
public class SourceUsePage<T> internal constructor(
    public val observedUses: Int,
    public val observedDistinctCallers: Int,
    public val returnedDistinctCallers: Int,
    public val offset: Int,
    public val limit: Int,
    public val omittedBefore: Int,
    public val omittedAfter: Int,
    bindings: List<SourceUseUnitBinding>,
    observations: List<T>,
) {
    public val bindings: List<SourceUseUnitBinding> = freezeSourceUseList(bindings)
    public val observations: List<T> = freezeSourceUseList(observations)
    public val returnedUses: Int get() = observations.size
    public val hasPrevious: Boolean get() = omittedBefore > 0
    public val hasNext: Boolean get() = omittedAfter > 0

    override fun equals(other: Any?): Boolean = other is SourceUsePage<*> && observedUses == other.observedUses &&
        observedDistinctCallers == other.observedDistinctCallers && returnedDistinctCallers == other.returnedDistinctCallers &&
        offset == other.offset && limit == other.limit && omittedBefore == other.omittedBefore &&
        omittedAfter == other.omittedAfter && bindings == other.bindings && observations == other.observations

    override fun hashCode(): Int = listOf(observedUses, observedDistinctCallers, returnedDistinctCallers,
        offset, limit, omittedBefore, omittedAfter, bindings, observations).hashCode()
}

/** 존재하는 정확한 선언의 양방향 원본 선택이다. unavailable 채널만 page가 null이다. */
public data class SourceUseInspection internal constructor(
    val subject: NodeId,
    val coverage: SourceUseCoverage,
    val page: SourceUsePage<SourceUseObservation>?,
)

/** source 행을 graph edge나 전이 경로로 바꾸지 않는 순수 질의다. */
public object SourceUseQuery {
    /** 해당 선언이 source 또는 target인 행을 한 번씩 반환하며 자기 선택은 두 방향을 갖는다. */
    public fun inspect(
        graph: CodeGraph,
        subject: NodeId,
        request: SourceUsePageRequest = SourceUsePageRequest(),
    ): SourceUseInspection {
        require(graph.contains(subject)) { "source use subject is absent from graph: ${subject.value}" }
        val coverage = SourceUseCoverage.from(graph)
        val page = sourceUsePage(graph, request, { it.source == subject || it.target == subject }) { use, calls ->
            val directions = ArrayList<String>(2)
            if (use.target == subject) directions.add("incoming")
            if (use.source == subject) directions.add("outgoing")
            SourceUseObservation(use, graph.sourceUseFact(use.source), graph.sourceUseFact(use.target),
                (use.source to use.target) in calls, directions)
        }
        return SourceUseInspection(subject, coverage, page)
    }
}

internal fun CodeGraph.sourceUseFact(id: NodeId): SourceUseNodeFact = nodes.getValue(id).let {
    SourceUseNodeFact(it.id, it.moduleName, it.location)
}

internal fun <T> sourceUsePage(
    graph: CodeGraph,
    request: SourceUsePageRequest,
    matches: (CompilerSourceUse) -> Boolean,
    observation: (CompilerSourceUse, Set<Pair<NodeId, NodeId>>) -> T,
): SourceUsePage<T>? {
    val evidence = graph.compilerSourceUseEvidence ?: return null
    val selected = ArrayList<CompilerSourceUse>()
    val callers = HashSet<NodeId>()
    for (use in evidence.uses) if (matches(use)) {
        selected.add(use)
        callers.add(use.source)
    }
    selected.sortWith(SourceUseOrder.comparator)
    val before = minOf(request.offset, selected.size)
    val returned = minOf(request.limit, selected.size - before)
    val rows = selected.subList(before, before + returned)
    val calls = HashSet<Pair<NodeId, NodeId>>()
    for (edge in graph.edges) if (edge.kind == EdgeKind.CALL && edge.origin == EdgeOrigin.BYTECODE) {
        calls.add(edge.source to edge.target)
    }
    val unitIds = rows.mapTo(HashSet()) { it.unitId }
    val bindings = evidence.units.filter { it.id in unitIds }.map { unit ->
        SourceUseUnitBinding(unit.id, unit.receipt, unit.collector, unit.compilerVersion,
            unit.collectorArtifactFingerprint, unit.inputToken, unit.claims.size)
    }.sortedBy { it.id }
    return SourceUsePage(selected.size, callers.size, rows.mapTo(HashSet()) { it.source }.size,
        request.offset, request.limit, before, selected.size - before - returned, bindings,
        rows.map { observation(it, calls) })
}

/** 최종 id tie는 admission 이후 도달할 수 없어 별도 key control에서만 검증한다. */
internal data class SourceUseOrderKey(
    val source: String,
    val target: String,
    val path: String,
    val offsetUtf16: Int,
    val unitId: String,
    val id: String,
)

/** 기존 NodeId 정렬은 보존하고 새 source 행만 decoded Unicode scalar 순서를 쓴다. */
internal object SourceUseOrder {
    val comparator: Comparator<CompilerSourceUse> = Comparator { left, right ->
        compareFields(left.source.value, left.target.value, left.file.path, left.offsetUtf16, left.unitId, left.id,
            right.source.value, right.target.value, right.file.path, right.offsetUtf16, right.unitId, right.id)
    }

    fun compare(left: SourceUseOrderKey, right: SourceUseOrderKey): Int = compareFields(
        left.source, left.target, left.path, left.offsetUtf16, left.unitId, left.id,
        right.source, right.target, right.path, right.offsetUtf16, right.unitId, right.id,
    )

    private fun compareFields(
        leftSource: String, leftTarget: String, leftPath: String, leftOffset: Int, leftUnit: String, leftId: String,
        rightSource: String, rightTarget: String, rightPath: String, rightOffset: Int, rightUnit: String, rightId: String,
    ): Int {
        var result = compareText(leftSource, rightSource)
        if (result == 0) result = compareText(leftTarget, rightTarget)
        if (result == 0) result = compareText(leftPath, rightPath)
        if (result == 0) result = leftOffset.compareTo(rightOffset)
        if (result == 0) result = compareText(leftUnit, rightUnit)
        if (result == 0) result = compareText(leftId, rightId)
        return result
    }

    fun compareText(left: String, right: String): Int {
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            val x = left.codePointAt(a)
            val y = right.codePointAt(b)
            if (x != y) return x.compareTo(y)
            a += Character.charCount(x)
            b += Character.charCount(y)
        }
        return (left.length - a).compareTo(right.length - b)
    }
}
