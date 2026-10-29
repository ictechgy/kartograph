package dev.kartograph.export

import dev.kartograph.core.*

/** 고정 컬럼·Neo4j bulk-import header를 사용하는 행 단위 그래프 내보내기다. */
public object GraphCsvRenderer {
    /** 노드/간선 목적지는 호출자가 소유한다. 여기서는 경로를 열거나 목적지를 닫지 않는다. */
    public fun write(graph: CodeGraph, nodes: Appendable, edges: Appendable,
        projectRelativePaths: Map<NodeId, String> = emptyMap()) {
        row(nodes, listOf("usr:ID", "name", "kind", "accessibility", "module", "sourcePath", "pathKind", "line:int", "column:int",
            "attributes:string[]", "annotations:string[]", "external:boolean", "synthesized:boolean", ":LABEL"))
        graph.nodeIds.forEach { id ->
            val node = graph.nodes.getValue(id)
            val path = projectRelativePaths[id] ?: node.location?.path?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
            row(nodes, listOf(id.value, node.name, node.kind.name.lowerCamel(), node.visibility.name.lowerCamel(),
                node.moduleName.orEmpty(), path,
                if (path.isEmpty()) "" else if (id in projectRelativePaths) "projectRelative" else "sourceFileName", node.location?.line?.toString().orEmpty(), node.location?.column?.toString().orEmpty(),
                node.attributes.map { it.name.lowerCamel() }.sorted().joinToString(";"), node.annotations.sorted().joinToString(";"),
                (NodeAttribute.EXTERNAL_STUB in node.attributes).toString(), node.synthesized.toString(), "Symbol"))
        }
        row(edges, listOf(":START_ID", ":END_ID", ":TYPE", "kind", "weight:long", "origins:string[]", "originWeights", "callSiteLines:int[]"))
        exportEdges(graph).forEach { edge ->
            row(edges, listOf(edge.source.value, edge.target.value, edge.kind, edge.kind, edge.weight.toString(),
                edge.origins.joinToString(";"), jsonValue(edge.originWeights), edge.callSiteLines.joinToString(";")))
        }
    }

    /** 완료 marker에 도구·생략 한계·예상 파일 목록을 남겨 부분 export를 완전한 그래프로 오인하지 않게 한다. */
    public fun manifest(graph: CodeGraph, toolVersion: String, limitations: List<String>): String = jsonValue(sortedMapOf(
        "edgeCount" to exportEdges(graph).count(),
        "files" to listOf("nodes.csv", "edges.csv", "facts.ndjson"),
        "format" to "code-graph-csv",
        "limitations" to limitations.distinct().sorted(),
        "nodeCount" to graph.nodeCount,
        "rawEdgeCount" to graph.edgeCount,
        "tool" to sortedMapOf("name" to "kartograph", "version" to toolVersion),
        "version" to 1,
    )) + "\n"

    /** RFC4180 quote를 사용해 쉼표·줄바꿈·따옴표가 있는 값도 고정 컬럼을 유지한다. */
    private fun row(output: Appendable, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) output.append(',')
            output.append('"')
            value.forEach { c -> if (c == '"') output.append("\"\"") else output.append(c) }
            output.append('"')
        }
        output.append('\n')
    }
}
