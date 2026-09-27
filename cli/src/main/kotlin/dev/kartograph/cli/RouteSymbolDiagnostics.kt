package dev.kartograph.cli

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.CodeGraph

/**
 * route-call 사실에 JVM 심볼 신원(`symbol.usr`)을 붙이지 못한 이유를 `missing-route-usrs:` 한계로 설명한다.
 *
 * isthmus trace는 사실의 usr와 순회 id의 정확한 문자열 일치로만 체인을 잇는다. usr가 조용히 빠지면 체인 전체가
 * 끊긴 채 "영향 없음"처럼 보이므로, 빠진 수와 가장 흔한 원인(snapshot과 routes의 `--project` 불일치)을 밝힌다.
 */
internal object RouteSymbolDiagnostics {
    /**
     * @param graph `--graph-file` snapshot의 그래프다. 신선도 때문에 쓰지 않았어도 경로 진단에는 쓴다
     * @param stale snapshot이 stale로 판정돼 신원 부착에 쓰이지 않았는지다
     * @return usr가 빠진 사실이 없으면 null
     */
    fun missingUsrs(facts: List<BridgeFact>, graph: CodeGraph?, stale: Boolean): String? {
        val missing = facts.filter { it.symbol?.usr == null }
        if (missing.isEmpty()) return null
        val prefix = "missing-route-usrs: ${missing.size} route-call fact(s) lack JVM symbol identities"
        if (graph == null) return "$prefix; no --graph-file snapshot was given, so only source facts are available"
        val paths = graph.nodes.values.mapNotNullTo(mutableSetOf()) { it.location?.path?.replace('\\', '/') }
        val reason = rootMismatch(missing.map { it.location.path }.distinct(), paths)
            ?: if (stale) "the --graph-file snapshot is stale for this project and was not used; rebuild and recapture it"
            else "no compiled declaration in the snapshot matched the enclosing source declaration"
        return "$prefix; $reason"
    }

    /**
     * 사실 경로와 snapshot 노드 경로를 비교해 `--project` 불일치를 찾는다. snapshot 경로가 사실 경로에 공통 접두사를
     * 더하거나 뺀 모양이면 그 접두사를 밝힌다. 경로가 하나도 겹치지 않으면 그 사실만 알린다.
     */
    internal fun rootMismatch(factPaths: List<String>, nodePaths: Set<String>): String? {
        if (nodePaths.isNotEmpty() && nodePaths.none { '/' in it }) {
            return "project-root-mismatch: the snapshot has no project-relative source paths; capture it with --include-paths"
        }
        val unmatched = factPaths.filter { it !in nodePaths }
        if (unmatched.isEmpty()) return null
        val offsets = unmatched.mapNotNull { path -> offsetOf(path, nodePaths) }
        val (kind, value) = offsets.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.first + it.key.second })
            .firstOrNull()?.key ?: return if (factPaths.none(nodePaths::contains)) "project-root-mismatch: none of the " +
                "route-call files appear among the snapshot's source paths; capture the snapshot and run routes with the same --project" else null
        return if (kind == PARENT) "project-root-mismatch: snapshot source paths start with \"$value\", so the snapshot was captured " +
            "with a parent --project; pass the same --project to snapshot and routes"
        else "project-root-mismatch: snapshot source paths omit \"$value\", so the snapshot was captured with the subdirectory " +
            "--project; pass the same --project to snapshot and routes"
    }

    /**
     * 사실 경로 하나에 대해 (방향, 접두사)를 찾는다. 노드 경로가 더 길면 PARENT, 짧으면 CHILD다.
     * 파일 이름만 남은 노드 경로(프로젝트 경로를 확정 못 한 위치)는 접두사를 부풀리므로 CHILD 비교에서 뺀다.
     */
    private fun offsetOf(path: String, nodePaths: Set<String>): Pair<String, String>? {
        nodePaths.filter { it.endsWith("/$path") }.minOrNull()?.let { return PARENT to it.removeSuffix(path) }
        return nodePaths.filter { '/' in it && path.endsWith("/$it") }.maxByOrNull { it.length }?.let { CHILD to path.removeSuffix(it) }
    }

    private const val PARENT = "parent"
    private const val CHILD = "child"
}
