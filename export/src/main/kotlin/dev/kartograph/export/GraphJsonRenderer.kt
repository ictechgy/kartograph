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
    ): String = jsonValue(document(graph, toolVersion, projectRelativePaths, limitations)) + "\n"

    /** 전체 문자열이나 정점·간선 JSON 목록을 만들지 않고 목적지에 순서대로 기록한다. */
    public fun write(
        graph: CodeGraph,
        toolVersion: String,
        output: Appendable,
        projectRelativePaths: Map<NodeId, String> = emptyMap(),
        limitations: List<String> = emptyList(),
    ) {
        output.appendJsonValue(document(graph, toolVersion, projectRelativePaths, limitations))
        output.append('\n')
    }

    /** 버전 header와 독립 JSON 레코드를 한 줄씩 기록하는 대용량 그래프 내보내기다. */
    public fun writeNdjson(
        graph: CodeGraph,
        toolVersion: String,
        output: Appendable,
        projectRelativePaths: Map<NodeId, String> = emptyMap(),
        limitations: List<String> = emptyList(),
        includeGraphRecords: Boolean = true,
    ) {
        fun record(kind: String, value: Map<String, Any?>) {
            output.appendJsonValue(sortedMapOf(kind to value, "record" to kind))
            output.append('\n')
        }
        output.appendJsonValue(sortedMapOf(
            "edgeCount" to exportEdges(graph).count(),
            "rawEdgeCount" to graph.edgeCount,
            "format" to "code-graph-ndjson",
            "graphVersion" to VERSION,
            "limitations" to limitations.sorted(),
            "nodeCount" to graph.nodeCount,
            "record" to "header",
            "tool" to sortedMapOf("name" to "kartograph", "version" to toolVersion),
            "version" to 1,
        ))
        output.append('\n')
        if (includeGraphRecords) {
        graph.nodeIds.forEach { id ->
            val node = graph.nodes.getValue(id).toJsonValue(projectRelativePaths[id]).toMutableMap()
            node.putIfAbsent("attributes", emptyList<String>())
            node["annotations"] = graph.nodes.getValue(id).annotations.sorted()
            node.putIfAbsent("location", null)
            node.putIfAbsent("module", null)
            record("node", node.toSortedMap())
        }
        exportEdges(graph).forEach { record("edge", it.toJsonValue()) }
        }
        graph.serviceProviders.forEach { provider ->
            record("serviceProvider", sortedMapOf("service" to provider.service, "provider" to provider.provider.value,
                "location" to provider.location.toJsonValue(null)))
        }
        graph.nodes.values.filter { it.sourceDeclaration != null }.sortedBy { it.id }.forEach { node ->
            record("sourceDeclaration", requireNotNull(node.sourceDeclaration).toJsonValue() + ("usr" to node.id.value))
        }
        graph.externalCalls.forEach { call ->
            val value = sortedMapOf<String, Any?>(
                "caller" to call.caller.value, "target" to call.target.value,
                "kind" to call.kind.name.lowerCamel(), "ordinal" to call.ordinal,
                "resolvedTargets" to call.resolvedTargets.map { it.value }.sorted(),
                "resolution" to call.resolution.name.lowerCamel(),
            )
            call.location?.toJsonValue(projectRelativePaths[call.caller])?.let { value["location"] = it }
            call.model?.let { value["model"] = it }
            record("externalCall", value)
        }
    }

    /** 큰 목록은 소비되는 원소 하나씩만 JSON 객체로 바꾸며 기존 정렬·공백을 보존한다. */
    private fun <T, R> Iterable<T>.jsonRows(transform: (T) -> R): Iterable<R> =
        Iterable { asSequence().map(transform).iterator() }

    /** 문서 수준의 고정 필드와 지연 레코드 목록이다. */
    private fun document(
        graph: CodeGraph, toolVersion: String, projectRelativePaths: Map<NodeId, String>, limitations: List<String>,
    ): Map<String, Any?> =
        sortedMapOf<String, Any?>(
            "edges" to graph.edges.jsonRows { edge -> edge.toJsonValue() },
            "format" to FORMAT,
            "limitations" to limitations.sorted(),
            "nodes" to graph.nodeIds.jsonRows { nodeId ->
                graph.nodes.getValue(nodeId).toJsonValue(projectRelativePaths[nodeId])
            },
            "tool" to sortedMapOf("name" to "kartograph", "version" to toolVersion),
            "version" to VERSION,
        ).apply {
            if (graph.serviceProviders.isNotEmpty()) put("serviceProviders", graph.serviceProviders.jsonRows { provider ->
                sortedMapOf("service" to provider.service, "provider" to provider.provider.value,
                    "location" to provider.location.toJsonValue(null))
            })
            if (graph.externalCalls.isNotEmpty()) put("externalCalls", graph.externalCalls.jsonRows { call ->
                sortedMapOf<String, Any?>(
                    "caller" to call.caller.value,
                    "target" to call.target.value,
                    "kind" to call.kind.name.lowerCamel(),
                    "ordinal" to call.ordinal,
                    "resolvedTargets" to call.resolvedTargets.map { it.value }.sorted(),
                    "resolution" to call.resolution.name.lowerCamel(),
                ).apply {
                    call.location?.toJsonValue(projectRelativePaths[call.caller])?.let { put("location", it) }
                    call.model?.let { put("model", it) }
                }
            })
        }

    // query 문서와 같은 필드 이름(usr·qualifiedName·accessibility·location)을 써서 두 표면을 join할 수 있게 한다.
    private fun GraphNode.toJsonValue(projectRelativePath: String?): Map<String, Any?> = buildMap<String, Any?> {
        put("accessibility", visibility.name.lowerCamel())
        if (attributes.isNotEmpty()) put("attributes", attributes.map { it.name.lowerCamel() }.sorted())
        put("kind", kind.name.lowerCamel())
        if (dev.kartograph.core.NodeAttribute.EXTERNAL_STUB in attributes) put("external", true)
        if (annotations.isNotEmpty()) put("annotations", annotations.sorted())
        location?.toJsonValue(projectRelativePath)?.let { put("location", it) }
        sourceDeclaration?.let { put("sourceDeclaration", it.toJsonValue()) }
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
     * `ClassFileIndexer`가 그래프에 넣기 전에 이미 파일 이름 성분만 남기므로 여기서의 축약은 이중 방어이고,
     * 다른 그래프 생산자가 들어와도 `pathKind`가 약속한 의미를 지키게 한다. 남는 이름이 없으면 위치를 생략한다.
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

    private fun GraphEdge.toJsonValue(): Map<String, Any?> = sortedMapOf<String, Any?>(
        "kind" to kind.name.lowerCamel(),
        "source" to source.value,
        "target" to target.value,
        "weight" to weight,
    ).apply {
        if (origin != dev.kartograph.core.EdgeOrigin.BYTECODE) put("origin", origin.name.lowerCamel())
        if (callSiteLines.isNotEmpty()) put("callSiteLines", callSiteLines)
    }
}
