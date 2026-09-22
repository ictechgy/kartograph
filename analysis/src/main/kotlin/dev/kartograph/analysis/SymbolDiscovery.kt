package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
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
    val offset: Int = 0,
)

/** 기존 query 선택과 분리된, case-sensitive source-name discovery 문법을 제공한다. */
public object SymbolDiscovery {
    /**
     * Exact query 이름 또는 `Owner.member(optional-looking-parameters)`를 찾는다.
     * FILE_FACADE 근거가 있는 최상위 함수는 `package.function`으로도 제안한다.
     * 괄호 안 텍스트는 signature로 해석하지 않으며 모든 overload를 유지한다.
     */
    public fun suggest(graphs: Collection<CodeGraph>, requested: String, limit: Int = 10, offset: Int = 0): SymbolSuggestionPage {
        val open = requested.lastIndexOf('(')
        val sourceName = if (open > 0 && requested.endsWith(')')) requested.substring(0, open) else requested
        val ownerQualified = '.' in sourceName
        val candidates = graphs.flatMap { graph -> graph.nodes.values.filter { node ->
                node.id.value == requested || node.name == requested || node.qualifiedName == requested ||
                    (ownerQualified && (node.qualifiedName == sourceName || node.qualifiedName.endsWith(".$sourceName"))) ||
                    packageFunction(graph, node) == sourceName
            } }
        return page(requested, candidates, limit, offset)
    }

    /** 정확한 파일 경로를 우선하고, 없을 때만 경로 구성 요소 단위 suffix 후보를 모두 제안한다. 파일은 읽지 않는다. */
    public fun inFile(graphs: Collection<CodeGraph>, requested: String, limit: Int = 10, offset: Int = 0): SymbolSuggestionPage {
        val nodes = graphs.flatMap { it.nodes.values }
        val exact = nodes.filter { it.location?.path == requested }
        val candidates = exact.ifEmpty { nodes.filter { node -> node.location?.path?.let { path ->
            requested.endsWith("/$path") || path.endsWith("/$requested")
        } == true } }
        return page(requested, candidates, limit, offset)
    }

    private fun packageFunction(graph: CodeGraph, node: GraphNode): String? {
        if (node.kind !in setOf(NodeKind.METHOD, NodeKind.FUNCTION) || node.synthesized ||
            NodeAttribute.PROPERTY_ACCESSOR in node.attributes || '#' !in node.id.value) return null
        val owner = node.id.value.substringAfter(':').substringBefore('#')
        val facade = graph.node(NodeId("class:$owner")) ?: return null
        if (NodeAttribute.FILE_FACADE !in facade.attributes) return null
        val packageName = owner.substringBeforeLast('/', "").replace('/', '.')
        return if (packageName.isEmpty()) node.name else "$packageName.${node.name}"
    }

    private fun page(requested: String, nodes: List<GraphNode>, limit: Int, offset: Int): SymbolSuggestionPage {
        require(limit > 0 && offset >= 0)
        val candidates = nodes.distinctBy { it.id }
            .sortedBy { it.id }
        val selected = candidates.drop(offset).take(limit).map { SymbolSuggestion(it.qualifiedName, it.id.value, it.location) }
        return SymbolSuggestionPage(requested, candidates.size, selected.size, candidates.size > selected.size, selected, offset)
    }
}
