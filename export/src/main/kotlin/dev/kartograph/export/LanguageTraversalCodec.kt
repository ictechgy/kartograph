package dev.kartograph.export

import dev.kartograph.analysis.LanguageTraversalResult
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.analysis.TraversalReached
import dev.kartograph.analysis.TraversalEdges
import dev.kartograph.analysis.TraversalRoot
import dev.kartograph.analysis.isUnresolvedTarget
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.KartographVersion
import dev.kartograph.core.qualifiedName
import java.security.MessageDigest

/**
 * `language-traversal` v1 문서의 문서 수준 값이다(isthmus docs/LANGUAGE-TRAVERSAL.md).
 *
 * @property generatedAt bridge-facts와 같은 UTC 밀리초 시각 문자열이다
 * @property project bridge-facts와 같은 POSIX realpath다
 * @property revision 분석한 소스의 revision이다. `--revision`, snapshot의 commit 라벨, 깨끗한 작업 트리의 git HEAD 순으로
 *   정하고 모르면 null이다(CLI가 정한다)
 * @property graphRevision [LanguageTraversalCodec.graphRevision]이 만든 `sha256:` 그래프 내용 해시다
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

    /**
     * isthmus 교환 문서가 id·revision으로 받는 문자열인지다.
     *
     * isthmus는 C0(U+0000–U+001F)·DEL·C1(U+0080–U+009F)·U+2028·U+2029 제어 문자나 짝 없는 서러게이트가 든
     * id·revision이 있는 문서를 통째로 거부한다. 해석하지 못한 root는 원문이 그대로 `id`가 되므로 문서를 만들기
     * 전에 같은 규칙으로 막는다. 공백만 있는 값도 식별자가 아니므로 거부한다.
     */
    public fun isExchangeText(value: String): Boolean =
        value.isNotBlank() && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' } && !hasUnpairedSurrogate(value)

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) { index += 2; continue }
            if (character.isSurrogate()) return true
            index++
        }
        return false
    }

    /**
     * 순회에 쓴 그래프 내용의 `sha256:` 해시다.
     *
     * 정점(id·종류), 간선(양 끝·종류·출처), 순회 간선의 근거 등급, 어휘적 소속, 소속 사실 캡처 여부, 정점별 잇지 못한
     * 호출 수를 정렬해 담는다. 위치·이름·간선 weight는 넣지 않는다 — 줄만 옮긴 편집이나 snapshot 파일의 표기 차이
     * (`--compact`, `--include-paths`)는 순회 결과를 바꾸지 않는다. 방향과 무관한 입력만 쓰므로 같은 그래프 위의
     * 정·역방향 문서가 같은 값을 낸다. 근거 등급은 수정자·상위 타입 같은 정점 속성에서 오므로 등급째 담는다.
     *
     * @param graph 순회한 그래프다
     * @param enclosuresCaptured snapshot이 어휘적 소속 사실을 실었는지다. 거짓이면 람다 간선 처리가 달라지므로 넣는다
     * @param callbackFactsCaptured snapshot이 콜백 값 흐름 관측을 실었는지다. 콜백 간선 유무와 한계 문구가 달라지므로 넣는다
     * @return `sha256:` 뒤에 소문자 hex 64자가 붙은 문자열이다
     */
    public fun graphRevision(graph: CodeGraph, enclosuresCaptured: Boolean, callbackFactsCaptured: Boolean = true): String {
        val traversal = TraversalEdges.build(graph, enclosuresCaptured, callbackFactsCaptured)
            .map { listOf(it.source.value, it.target.value, it.relationship, it.tier.name) + if (it.terminal) listOf("terminal") else emptyList() }
            .distinct().sortedWith(::compareRows)
        val unresolved = graph.externalCalls.filter { it.isUnresolvedTarget() }.groupingBy { it.caller.value }.eachCount()
            .toSortedMap().map { (caller, count) -> listOf(caller, count.toString()) }
        val content = sortedMapOf<String, Any?>(
            "nodes" to graph.nodeIds.map { listOf(it.value, graph.nodes.getValue(it).kind.name) },
            "edges" to graph.edges.map { listOf(it.source.value, it.target.value, it.kind.name, it.origin.name) },
            "traversalEdges" to traversal, "enclosures" to graph.enclosures.map { listOf(it.localClass.value, it.enclosing.value) },
            "enclosuresCaptured" to enclosuresCaptured, "unresolvedCalls" to unresolved,
            // 콜백 간선은 traversalEdges에 들어 있다. 옛 snapshot과 관측 0건을 구별하려고 캡처 여부만 따로 넣는다.
            "callbackFactsCaptured" to callbackFactsCaptured,
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(jsonValue(content).toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun compareRows(left: List<String>, right: List<String>): Int {
        left.zip(right).forEach { (a, b) -> a.compareTo(b).let { if (it != 0) return it } }
        return left.size.compareTo(right.size)
    }

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
