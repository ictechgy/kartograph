package dev.kartograph.export

import dev.kartograph.analysis.ImpactNode
import dev.kartograph.analysis.ImpactReport
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.qualifiedName

/** 사람·에이전트·CI가 같은 의미로 읽는 영향 보고서와 변경 경로 목록의 JSON codec이다. */
public object ImpactReportCodec {
    /** 간선의 시점·출처와 불완전한 선택/탐색을 숨기지 않고 결정적으로 출력한다. */
    public fun render(report: ImpactReport, snapshots: Map<String, QuerySnapshot> = emptyMap()): String = jsonValue(sortedMapOf(
        "format" to "kartograph-impact", "version" to 1,
        "inputs" to snapshots.toSortedMap().mapValues { (_, snapshot) -> sortedMapOf(
            "revision" to snapshot.revision, "scope" to snapshot.scope, "toolVersion" to snapshot.toolVersion) },
        "status" to when {
            report.unresolved.isNotEmpty() && report.changed.isEmpty() -> "notFound"
            report.unresolved.isNotEmpty() || report.truncated.results || report.truncated.depth || report.truncated.budget -> "partial"
            report.changed.isEmpty() -> "noChanges"
            else -> "found"
        },
        "changed" to report.changed.map(::node), "affected" to report.affected.map(::node),
        "observedAffected" to report.totalAffected,
        "unresolved" to report.unresolved.map { sortedMapOf("requested" to it.requested, "reason" to it.reason,
            "candidates" to it.candidates.map { id -> id.value }) },
        "limitations" to report.limitations,
        "truncated" to sortedMapOf("results" to report.truncated.results, "depth" to report.truncated.depth, "budget" to report.truncated.budget),
    )) + "\n"

    /** Git 경로의 공백·따옴표를 유지하며 빈 배열도 명시적인 변경 없음으로 읽는다. */
    public fun parseFiles(content: String): List<String> {
        require(content.length <= 1024 * 1024) { "changed file list exceeds 1 MiB" }
        val parsed = SnapshotJsonParser(content).parse()
        require(parsed is List<*> && parsed.all { it is String }) { "changed file list must be a JSON string array" }
        return parsed.map { it as String }
    }

    private fun node(item: ImpactNode): Map<String, Any?> = sortedMapOf(
        "usr" to item.node.id.value, "name" to item.node.name, "qualifiedName" to item.node.qualifiedName,
        "kind" to item.node.kind.name.lowerCamel(), "module" to item.node.moduleName,
        "accessibility" to item.node.visibility.name.lowerCamel(),
        "location" to location(item.node.location), "synthesized" to item.node.synthesized,
        "presentIn" to item.presentIn.map { it.name.lowercase() }.sorted(),
        "paths" to item.paths.map { path -> sortedMapOf(
            "revision" to path.revision.name.lowercase(), "changed" to path.changed.value,
            "nodes" to path.nodes.map { it.value },
            "edges" to path.edges.mapIndexed { index, edge -> sortedMapOf("source" to edge.source.value, "target" to edge.target.value,
                "kind" to edge.kind.name.lowerCamel(), "origin" to edge.origin.name.lowerCamel(),
                "traversal" to if (edge.source == path.nodes[index]) "dependency" else "overrideContract") },
        ) },
        "retention" to item.retention.map { sortedMapOf("revision" to it.revision.name.lowercase(),
            "reason" to it.evidence.reason.name.lowerCamel(), "location" to location(it.evidence.location)) },
    )

    private fun location(location: SourceLocation?): Map<String, Any?>? = location?.let {
        sortedMapOf("path" to it.path, "line" to it.line, "column" to it.column)
    }
}
