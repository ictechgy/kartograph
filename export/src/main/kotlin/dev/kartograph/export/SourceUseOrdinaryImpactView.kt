package dev.kartograph.export

import dev.kartograph.analysis.ImpactBucket
import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.ImpactNode
import dev.kartograph.analysis.ImpactReport
import dev.kartograph.analysis.ImpactSummary
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.qualifiedName

/** 기존 impact JSON의 모든 값·optional 요약 필드를 예산 안에서 지연 조립한다. */
internal fun sourceOrdinaryImpactValue(
    report: ImpactReport,
    snapshots: Map<String, SourceSnapshotMetadata>,
    summaryLimit: Int?,
    generationSummaries: Map<String, GenerationSummary> = emptyMap(),
): Map<String, Any?> {
    require(summaryLimit == null || summaryLimit in 1..100_000)
    val summaryTruncated = summaryLimit != null && listOf(report.navigation.observed, report.navigation.filtered)
        .any { value -> summaryAxes(value).any { it.size > summaryLimit } }
    return sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
        add("format" to { "kartograph-impact" })
        add("version" to { 1 })
        add("inputs" to { sourceJsonObject(*snapshots.entries.map { (name, snapshot) -> name to {
            sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
                add("revision" to { snapshot.revision }); add("scope" to { snapshot.scope }); add("toolVersion" to { snapshot.toolVersion })
                generationSummaries[name]?.let { add("generationSummary" to { it.value() }) }
            }.toTypedArray())
        } }.toTypedArray()) })
        add("status" to { when {
            report.unresolved.isNotEmpty() && report.changed.isEmpty() -> "notFound"
            report.unresolved.isNotEmpty() || report.truncated.results || report.truncated.depth || report.truncated.budget || summaryTruncated -> "partial"
            report.changed.isEmpty() -> "noChanges"
            else -> "found"
        } })
        add("changed" to { sourceJsonItems(report.changed, ::node) })
        add("affected" to { sourceJsonItems(report.affected, ::node) })
        add("observedAffected" to { report.totalAffected })
        add("summary" to { sourceJsonObject(
            "observed" to { summary(report.navigation.observed, summaryLimit) },
            "filtered" to { summary(report.navigation.filtered, summaryLimit) },
            "returned" to { report.navigation.returned },
        ) })
        add("navigation" to { sourceJsonObject(
            "offset" to { report.navigation.offset }, "limit" to { report.navigation.limit },
            "sort" to { report.navigation.sort.name.lowerCamel() }, "filters" to { filters(report.navigation.filters) },
            "observed" to { report.navigation.observed.candidates }, "filtered" to { report.navigation.filtered.candidates },
            "returned" to { report.navigation.returned }, "hasPrevious" to { report.navigation.hasPrevious },
            "hasNext" to { report.navigation.hasNext },
        ) })
        add("budgets" to { sourceJsonObject(
            "visitLimit" to { report.budgets.visitLimit }, "pathLimit" to { report.budgets.pathLimit },
            "visited" to { sourceJsonObject(*report.budgets.visited.entries.sortedBy { it.key }.map { (revision, count) ->
                revision.name.lowercase() to { count }
            }.toTypedArray()) },
            "visitLimitReached" to { sourceJsonItems(report.budgets.visitLimitReached.sortedBy { it.name.lowercase() }) { it.name.lowercase() } },
            "pathEdgesUsed" to { report.budgets.pathEdgesUsed }, "pathOmissions" to { report.budgets.pathOmissions },
        ) })
        add("unresolved" to { sourceJsonItems(report.unresolved) { unresolved -> sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
            add("requested" to { unresolved.requested }); add("reason" to { unresolved.reason })
            add("candidates" to { sourceJsonItems(unresolved.candidates) { it.value } })
            unresolved.revision?.let { revision -> add("revision" to { revision.name.lowercase() }) }
        }.toTypedArray()) } })
        add("limitations" to { report.limitations })
        add("truncated" to { sourceJsonObject("results" to { report.truncated.results },
            "depth" to { report.truncated.depth }, "budget" to { report.truncated.budget }) })
        if (report.resourceSelections.isNotEmpty()) {
            add("selectedByRevision" to { sourceJsonObject(*report.selectedByRevision.entries.sortedBy { it.key }.map { (revision, ids) ->
                revision.name.lowercase() to { sourceJsonItems(ids.sorted()) { it.value } }
            }.toTypedArray()) })
            add("resourceSelections" to { sourceJsonObject(*report.resourceSelections.entries.sortedBy { it.key }.map { (revision, selection) ->
                revision.name.lowercase() to { resourceImpactSelectionValue(selection) }
            }.toTypedArray()) })
        }
        if (summaryLimit != null) {
            add("resultMetadata" to { sourceJsonObject(
                "summary" to { "Summary buckets are a deterministic prefix; use CLI impact without --summary-limit for the complete summary or request more buckets." },
                "summaryLimit" to { summaryLimit },
            ) })
            add("summaryNavigation" to { sourceJsonObject(
                "filtered" to { summaryNavigation(report.navigation.filtered, summaryLimit) },
                "observed" to { summaryNavigation(report.navigation.observed, summaryLimit) },
                "truncated" to { summaryTruncated },
            ) })
        }
    }.toTypedArray())
}

private fun node(item: ImpactNode): Map<String, Any?> = sourceJsonObject(
    "usr" to { item.node.id.value }, "name" to { item.node.name }, "qualifiedName" to { item.node.qualifiedName },
    "kind" to { item.node.kind.name.lowerCamel() }, "module" to { item.node.moduleName },
    "accessibility" to { item.node.visibility.name.lowerCamel() }, "location" to { sourceRequiredLocation(item.node.location) },
    "synthesized" to { item.node.synthesized },
    "presentIn" to { sourceJsonItems(item.presentIn.sortedBy { it.name.lowercase() }) { it.name.lowercase() } },
    "observedIn" to { sourceJsonItems(item.observedIn.sortedBy { it.name.lowercase() }) { it.name.lowercase() } },
    "testStatus" to { item.testStatus.name.lowercase() }, "relation" to { item.relation.name.lowerCamel() },
    "pathStatus" to { item.pathStatus.name.lowerCamel() },
    "facts" to { sourceJsonItems(item.facts.sortedBy { it.revision }) { fact -> sourceJsonObject(
        "revision" to { fact.revision.name.lowercase() }, "module" to { fact.module },
        "location" to { sourceRequiredLocation(fact.location) }, "testStatus" to { fact.testStatus.name.lowercase() },
    ) } },
    "paths" to { sourceJsonItems(item.paths) { path -> sourceJsonObject(
        "revision" to { path.revision.name.lowercase() }, "changed" to { path.changed.value },
        "nodes" to { sourceJsonItems(path.nodes) { it.value } },
        "edges" to { sourceJsonItems(path.edges.withIndex()) { (index, edge) -> sourceJsonObject(
            "source" to { edge.source.value }, "target" to { edge.target.value }, "kind" to { edge.kind.name.lowerCamel() },
            "origin" to { edge.origin.name.lowerCamel() },
            "traversal" to { if (edge.source == path.nodes.getOrNull(index)) "dependency" else "overrideContract" },
        ) } },
    ) } },
    "pathOmissions" to { sourceJsonItems(item.pathOmissions) { omission -> sourceJsonObject(
        "revision" to { omission.revision.name.lowercase() }, "reason" to { omission.reason.name.lowerCamel() },
        "requiredEdges" to { omission.requiredEdges }, "edgeKinds" to { sourceJsonItems(omission.edgeKinds) { it.name.lowerCamel() } },
    ) } },
    "retention" to { sourceJsonItems(item.retention) { retention -> sourceJsonObject(
        "revision" to { retention.revision.name.lowercase() }, "reason" to { retention.evidence.reason.name.lowerCamel() },
        "location" to { sourceRequiredLocation(retention.evidence.location) },
    ) } },
)

private fun summary(value: ImpactSummary, limit: Int?): Map<String, Any?> = sourceJsonObject(
    "candidates" to { value.candidates }, "byModule" to { buckets(value.byModule, limit) },
    "byFile" to { buckets(value.byFile, limit) }, "byTestStatus" to { buckets(value.byTestStatus, limit) },
    "byRelation" to { buckets(value.byRelation, limit) }, "byPathStatus" to { buckets(value.byPathStatus, limit) },
)

private fun buckets(values: List<ImpactBucket>, limit: Int?): Iterable<Any?> =
    sourceJsonItems(values.asSequence().take(limit ?: values.size).asIterable()) {
        sourceJsonObject("value" to { it.value }, "count" to { it.count })
    }

private fun summaryAxes(value: ImpactSummary): List<List<ImpactBucket>> = listOf(
    value.byModule, value.byFile, value.byTestStatus, value.byRelation, value.byPathStatus,
)

private fun summaryNavigation(value: ImpactSummary, limit: Int): Map<String, Any?> = sourceJsonObject(
    "byModule" to { bucketNavigation(value.byModule, limit) }, "byFile" to { bucketNavigation(value.byFile, limit) },
    "byTestStatus" to { bucketNavigation(value.byTestStatus, limit) }, "byRelation" to { bucketNavigation(value.byRelation, limit) },
    "byPathStatus" to { bucketNavigation(value.byPathStatus, limit) },
)

private fun bucketNavigation(values: List<ImpactBucket>, limit: Int): Map<String, Any?> {
    val returned = minOf(values.size, limit)
    return sourceJsonObject(
        "originalBuckets" to { values.size }, "returnedBuckets" to { returned },
        "omittedBuckets" to { values.size - returned },
        "omittedCandidates" to { values.asSequence().drop(returned).fold(0L) { total, bucket -> total + bucket.count.toLong() } },
        "truncated" to { returned < values.size },
    )
}

private fun filters(value: ImpactFilter): Map<String, Any?> = sourceJsonObject(
    "modules" to { value.modules.sorted() }, "affectedFiles" to { value.affectedFiles.sorted() },
    "kinds" to { sourceJsonItems(value.kinds.sortedBy { it.name.lowerCamel() }) { it.name.lowerCamel() } },
    "testStatus" to { value.testStatus?.name?.lowercase() }, "relation" to { value.relation?.name?.lowerCamel() },
    "pathStatus" to { value.pathStatus?.name?.lowerCamel() },
)

/** source/impact의 location은 query와 달리 알려지지 않은 line·column도 null로 싣는다. */
internal fun sourceRequiredLocation(location: SourceLocation?): Map<String, Any?>? = location?.let {
    sourceJsonObject("path" to { it.path }, "line" to { it.line }, "column" to { it.column })
}
