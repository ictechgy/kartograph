package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.qualifiedName

/** 실패한 machine selector를 exact USR로 다시 시도할 수 있게 하는 결정적 discovery 결과다. */
public data class SymbolSuggestion(
    val qualifiedName: String,
    val usr: String,
    val location: SourceLocation?,
)

/** 후보 전체 수와 반환 경계를 숨기지 않는 discovery page다. */
public data class SymbolSuggestionPage(
    val requested: String,
    val total: Int,
    val returned: Int,
    val truncated: Boolean,
    val candidates: List<SymbolSuggestion>,
)

/** 기존 query 선택과 분리된, case-sensitive source-name discovery 문법을 제공한다. */
public object SymbolDiscovery {
    /**
     * Exact query 이름 또는 `Owner.member(optional-looking-parameters)`를 찾는다.
     * 괄호 안 텍스트는 signature로 해석하지 않으며 모든 overload를 유지한다.
     */
    public fun suggest(graphs: Collection<CodeGraph>, requested: String, limit: Int = 10): SymbolSuggestionPage {
        require(limit > 0)
        val open = requested.lastIndexOf('(')
        val sourceName = if (open > 0 && requested.endsWith(')')) requested.substring(0, open) else requested
        val ownerQualified = '.' in sourceName
        val candidates = graphs.flatMap { it.nodes.values }
            .distinctBy { it.id }
            .filter { node ->
                node.id.value == requested || node.name == requested || node.qualifiedName == requested ||
                    (ownerQualified && (node.qualifiedName == sourceName || node.qualifiedName.endsWith(".$sourceName")))
            }
            .sortedBy { it.id }
            .map { SymbolSuggestion(it.qualifiedName, it.id.value, it.location) }
        return SymbolSuggestionPage(requested, candidates.size, minOf(limit, candidates.size), candidates.size > limit,
            candidates.take(limit))
    }
}
