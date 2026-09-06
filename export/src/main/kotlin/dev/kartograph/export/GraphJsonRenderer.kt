package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.qualifiedName

/**
 * 코드 그래프를 다른 도구가 소비할 수 있는 결정적인 교환 JSON으로 렌더링한다.
 *
 * bytecode의 source file attribute는 보통 확장자를 포함한 파일 이름만 남기므로, project 기준 상대경로는
 * 호출부가 해석해 [render]에 넘긴 경우에만 싣는다. 로컬 절대경로는 어떤 경우에도 문서에 넣지 않는다.
 */
public object GraphJsonRenderer {
    /** 이 renderer가 만드는 교환 문서의 형식 이름이다. */
    public const val FORMAT: String = "code-graph"

    /** 필드 추가만 허용하는 교환 문서의 version이다. */
    public const val VERSION: Int = 1

    /**
     * 정렬된 정점과 간선을 하나의 교환 문서로 만든다.
     *
     * @param graph 렌더링할 코드 그래프. 정점·간선 순서는 그래프의 결정적 순서를 그대로 따른다.
     * @param toolVersion 문서를 만든 kartograph release version.
     * @param projectRelativePaths project 기준 상대경로가 유일하게 확정된 정점의 경로. 없는 정점은
     *   bytecode가 남긴 source file 이름을 그대로 싣고 `location.pathKind`로 구분한다.
     * @param limitations 실제로 측정된 한계 문자열. 알릴 측정값이 없으면 빈 목록을 유지한다.
     */
    public fun render(
        graph: CodeGraph,
        toolVersion: String,
        projectRelativePaths: Map<NodeId, String> = emptyMap(),
        limitations: List<String> = emptyList(),
    ): String = jsonValue(
        sortedMapOf<String, Any?>(
            "edges" to graph.edges.map { edge -> edge.toJsonValue() },
            "format" to FORMAT,
            "limitations" to limitations.sorted(),
            "nodes" to graph.nodeIds.map { nodeId ->
                graph.nodes.getValue(nodeId).toJsonValue(projectRelativePaths[nodeId])
            },
            "tool" to sortedMapOf("name" to "kartograph", "version" to toolVersion),
            "version" to VERSION,
        ),
    ) + "\n"

    // query 문서와 같은 필드 이름(usr·qualifiedName·accessibility·location)을 써서 두 표면을 join할 수 있게 한다.
    private fun GraphNode.toJsonValue(projectRelativePath: String?): Map<String, Any?> = buildMap<String, Any?> {
        put("accessibility", visibility.name.lowerCamel())
        if (attributes.isNotEmpty()) put("attributes", attributes.map { it.name.lowerCamel() }.sorted())
        put("kind", kind.name.lowerCamel())
        location?.toJsonValue(projectRelativePath)?.let { put("location", it) }
        moduleName?.let { put("module", it) }
        put("name", name)
        put("qualifiedName", qualifiedName)
        put("synthesized", synthesized)
        put("usr", id.value)
    }.toSortedMap()

    /**
     * 소비자가 파일 이름과 실제 경로를 혼동하지 않도록 path의 출처를 항상 함께 싣는다.
     *
     * JVM `SourceFile` attribute는 임의 문자열이라 컴파일러나 후처리 도구에 따라 절대경로가 담길 수 있다.
     * 해석되지 않은 값은 `pathKind`가 약속한 대로 파일 이름 성분만 남겨, 빌드 기계의 로컬 경로가 교환 문서로
     * 새지 않게 한다. 남는 이름이 없으면 위치 자체를 싣지 않는다.
     */
    private fun SourceLocation.toJsonValue(projectRelativePath: String?): Map<String, Any?>? {
        val reported = projectRelativePath ?: path.substringAfterLast('/').substringAfterLast('\\')
        if (reported.isBlank()) return null
        return buildMap<String, Any?> {
            column?.let { put("column", it) }
            line?.let { put("line", it) }
            put("path", reported)
            put("pathKind", if (projectRelativePath != null) "projectRelative" else "sourceFileName")
        }.toSortedMap()
    }

    private fun GraphEdge.toJsonValue(): Map<String, Any?> = sortedMapOf(
        "kind" to kind.name.lowerCamel(),
        "source" to source.value,
        "target" to target.value,
        "weight" to weight,
    )
}
