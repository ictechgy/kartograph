package dev.kartograph.export

import dev.kartograph.analysis.LanguageTraversalResult
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.analysis.TraversalReached
import dev.kartograph.analysis.TraversalRoot
import dev.kartograph.core.GraphNode
import dev.kartograph.core.KartographVersion
import dev.kartograph.core.qualifiedName

/**
 * `language-traversal` v1 문서의 문서 수준 값이다(isthmus docs/LANGUAGE-TRAVERSAL.md).
 *
 * @property generatedAt bridge-facts와 같은 UTC 밀리초 시각 문자열이다
 * @property project bridge-facts와 같은 POSIX realpath다
 * @property revision snapshot이 실은 커밋 hash다
 * @property graphRevision 순회에 쓴 snapshot 파일 바이트의 소문자 hex SHA-256이다
 */
public data class LanguageTraversalMetadata(
    val generatedAt: String,
    val project: String,
    val revision: String? = null,
    val graphRevision: String? = null,
)

/** 다중 root 순회 결과를 isthmus `trace`가 읽는 교환 문서로 렌더링한다. */
public object LanguageTraversalCodec {
    /** 정점 하나가 싣는 root 인덱스 상한이다. 넘으면 가장 작은 64개와 문서의 `rootsTruncated`로 알린다. */
    public const val MAX_ROOTS_PER_REACHED: Int = 64

    /** 관계 이름 상한이다. */
    private const val MAX_RELATIONSHIPS: Int = 32

    /** 잇지 못한 호출 수 상한이다. 계약의 범위를 넘는 값은 상한으로 자른다. */
    private const val MAX_UNRESOLVED_CALLS: Int = 1_000_000

    /** `dispatch`를 항상 실어 근거 등급과 잇지 못한 호출의 완전한 신고를 선언한다. */
    public fun render(result: LanguageTraversalResult, metadata: LanguageTraversalMetadata): String {
        val rootsTruncated = result.reached.any { it.roots.size > MAX_ROOTS_PER_REACHED }
        val reasons = truncationReasons(result)
        val limitations = result.limitations + if (rootsTruncated) listOf("roots-per-reached: some declarations are reached from " +
            "more than $MAX_ROOTS_PER_REACHED roots; only the $MAX_ROOTS_PER_REACHED smallest root indices are listed") else emptyList()
        val document = sortedMapOf<String, Any?>(
            "format" to "language-traversal", "version" to 1,
            "tool" to sortedMapOf("name" to "kartograph", "version" to KartographVersion.current),
            "generatedAt" to metadata.generatedAt, "platform" to "kotlin", "project" to metadata.project,
            "revision" to metadata.revision, "graphRevision" to metadata.graphRevision,
            "dispatch" to result.dispatch.label, "direction" to directionLabel(result.direction),
            "roots" to result.roots.map(::root), "reached" to result.reached.map(::reached),
            "rootsTruncated" to if (rootsTruncated) true else null,
            "truncated" to reasons.isNotEmpty(), "truncationReasons" to reasons.ifEmpty { null },
            "limitations" to limitations.distinct().sorted(),
        ).filterValues { it != null }
        return jsonValue(document) + "\n"
    }

    /**
     * `--roots-from` 입력에서 root 요청을 읽는다. JSON 문자열 배열이거나 bridge-facts 문서다.
     * bridge-facts면 사실의 `symbol.usr`를 문서 순서대로 중복 없이 모은다(route-call·relation-use를 감싼 심볼).
     */
    public fun parseRoots(content: String): List<String> {
        require(content.length <= 16 * 1024 * 1024) { "root list exceeds 16 MiB" }
        return when (val parsed = SnapshotJsonParser(content, generalNumbers = true).parse()) {
            is List<*> -> parsed.map { it as? String ?: throw IllegalArgumentException("root list must be a JSON string array") }
            is Map<*, *> -> factRoots(parsed)
            else -> throw IllegalArgumentException("root list must be a JSON string array or a bridge-facts document")
        }.distinct()
    }

    private fun factRoots(document: Map<*, *>): List<String> {
        require(document["format"] == "bridge-facts") { "root document must be a bridge-facts document" }
        val facts = document["facts"] as? List<*> ?: throw IllegalArgumentException("bridge-facts document has no facts array")
        return facts.mapNotNull { fact -> ((fact as? Map<*, *>)?.get("symbol") as? Map<*, *>)?.get("usr") as? String }
    }

    private fun truncationReasons(result: LanguageTraversalResult): List<String> = buildList {
        if (result.depthTruncated) add("depth")
        if (result.reachedTruncated) add("reached-limit")
        if (result.rootNotFound) add("root-not-found")
    }.sorted()

    private fun directionLabel(direction: TraversalDirection): String = when (direction) {
        TraversalDirection.DEPENDENCIES -> "dependencies"
        TraversalDirection.DEPENDENTS -> "dependents"
    }

    private fun root(root: TraversalRoot): Map<String, Any?> = sortedMapOf(
        "id" to root.id, "symbol" to root.node?.let(::symbol), "unresolvedCalls" to unresolved(root.unresolvedCalls),
    ).filterValues { it != null }

    private fun reached(row: TraversalReached): Map<String, Any?> = sortedMapOf(
        "symbol" to symbol(row.node), "via" to row.via, "depth" to row.depth,
        "roots" to row.roots.take(MAX_ROOTS_PER_REACHED),
        "relationships" to row.relationships.take(MAX_RELATIONSHIPS).ifEmpty { null },
        "evidence" to row.evidence.name.lowercase(), "unresolvedCalls" to unresolved(row.unresolvedCalls),
    ).filterValues { it != null }

    private fun unresolved(count: Int): Int? = if (count <= 0) null else minOf(count, MAX_UNRESOLVED_CALLS)

    /** 줄·열이 없으면 생략하고 1로 채우지 않는다. 파일 이름만 남은(프로젝트 경로를 확정 못 한) 위치는 싣지 않는다. */
    private fun symbol(node: GraphNode): Map<String, Any?> = sortedMapOf(
        "usr" to node.id.value, "qualifiedName" to node.qualifiedName, "kind" to node.kind.name.lowerCamel(),
        "location" to node.location?.takeIf { '/' in it.path }?.let { location ->
            sortedMapOf("path" to location.path, "line" to location.line,
                "column" to location.column?.takeIf { location.line != null }).filterValues { it != null }
        },
    ).filterValues { it != null }
}
