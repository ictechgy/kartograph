package dev.kartograph.export

import dev.kartograph.analysis.EnumDecisionImpactReport
import dev.kartograph.analysis.EnumInventoryComparisonStatus
import dev.kartograph.core.EnumDecisionKind

/** 결정적인 검토 후보 보고서다. 소스 분기는 실제 실행이나 동작을 입증하지 않는다. */
public object EnumImpactReportRenderer {
    public fun json(report: EnumDecisionImpactReport, current: QuerySnapshot, base: QuerySnapshot,
        currentSha256: String, baseSha256: String): String {
        val evidence = current.enumDecisionEvidence
        val units = evidence?.units.orEmpty().associateBy { it.id }
        val status = when {
            !report.evidenceAvailable -> "evidenceUnavailable"
            report.comparisons.any { it.status != EnumInventoryComparisonStatus.AVAILABLE } ||
                report.unresolvedSites > 0 || report.viewUnavailableSites > 0 ||
                evidence?.units.orEmpty().any { it.producer.unsupported > 0 } ||
                evidence?.uncoveredRoots.orEmpty().isNotEmpty() -> "partial"
            report.totalCandidates > 0 -> "candidates"
            else -> "noCandidates"
        }
        val value = sortedMapOf<String, Any?>(
            "format" to "kartograph-enum-expansion", "version" to 1, "status" to status,
            "inputs" to sortedMapOf(
                "base" to input(base, baseSha256), "current" to input(current, currentSha256)),
            "comparisons" to report.comparisons.map { row -> sortedMapOf(
                "enum" to row.owner.value, "status" to row.status.name,
                "addedConstants" to row.addedConstants.map { it.value },
                "removedConstants" to row.removedConstants.map { it.value }) },
            "coverage" to sortedMapOf(
                "evidenceAvailable" to report.evidenceAvailable,
                "siteCountScope" to "selectedEnums", "producerCountScope" to "capturedUnits",
                "attributionCountScope" to "capturedSites",
                "capturedUnits" to evidence?.units?.size, "uncoveredOutputRoots" to evidence?.uncoveredRoots?.map { it.id },
                "resolvedSites" to report.resolvedSites, "unresolvedSites" to report.unresolvedSites,
                "viewUnavailableSites" to report.viewUnavailableSites,
                "producerObserved" to evidence?.units?.sumOf { it.producer.observed.toLong() },
                "producerEmitted" to evidence?.units?.sumOf { it.producer.emitted.toLong() },
                "producerUnsupported" to evidence?.units?.sumOf { it.producer.unsupported.toLong() },
                "producerIgnored" to evidence?.units?.sumOf { it.producer.ignored.toLong() },
                "capturedAttributionCounts" to evidence?.sites?.groupingBy { it.attribution.name }?.eachCount()?.toSortedMap()),
            "navigation" to sortedMapOf(
                "limit" to report.limit, "offset" to report.offset, "totalCandidates" to report.totalCandidates,
                "returnedCandidates" to report.candidates.size, "omittedCandidates" to report.omittedCandidates),
            "unresolvedNavigation" to sortedMapOf(
                "limit" to report.limit, "offset" to report.offset,
                "totalRecords" to report.totalUnresolvedRecords, "omittedRecords" to report.omittedUnresolvedSites),
            "unresolved" to report.unresolved.map { row -> sortedMapOf(
                "enum" to row.site.enumOwner.value, "reason" to row.reason,
                "kind" to row.site.kind.name, "revision" to "current",
                "source" to sortedMapOf("path" to row.site.file.path, "sha256" to row.site.file.sha256,
                    "offsetUtf16" to row.site.offsetUtf16, "endOffsetUtf16" to row.site.endOffsetUtf16,
                    "line" to row.site.line, "column" to row.site.column,
                    "coordinateBasis" to row.site.coordinateBasis.name),
                "unitId" to row.site.unitId, "sourceRootId" to row.site.sourceRootId, "enumRootId" to row.site.enumRootId) },
            "candidates" to report.candidates.map { candidate ->
                val site = candidate.site
                val unit = units.getValue(site.unitId)
                sortedMapOf(
                    "id" to site.id, "revision" to "current", "enum" to site.enumOwner.value,
                    "kind" to site.kind.name, "reason" to reason(site.kind),
                    "addedConstants" to candidate.addedConstants.map { it.value },
                    "listedConstants" to site.constants.map { it.value },
                    "attribution" to site.attribution.name, "sourceRootId" to site.sourceRootId,
                    "enumRootId" to site.enumRootId, "lexicalClass" to site.lexicalClass?.value,
                    "lexicalLambda" to site.lexicalLambda,
                    "source" to sortedMapOf(
                        "path" to site.file.path, "sha256" to site.file.sha256,
                        "offsetUtf16" to site.offsetUtf16, "endOffsetUtf16" to site.endOffsetUtf16,
                        "line" to site.line, "column" to site.column, "coordinateBasis" to site.coordinateBasis.name,
                        "generated" to site.generated),
                    "evidence" to sortedMapOf(
                        "unitId" to unit.id, "collector" to unit.collector, "compilerVersion" to unit.compilerVersion,
                        "collectorArtifactFingerprint" to unit.collectorArtifactFingerprint, "inputToken" to unit.inputToken,
                        "receipt" to fingerprint(unit.receipt),
                        "claims" to unit.claims.map { claim -> sortedMapOf(
                            "scope" to claim.scope, "compiler" to claim.compiler, "task" to claim.task,
                            "outputs" to claim.outputs.map(::fingerprint)) }),
                )
            },
            "limitations" to (current.limitations + base.limitations + LIMITATIONS).distinct().sorted(),
        )
        return boundedJsonValue(value, 16 * 1024 * 1024, "\n")
    }

    public fun text(report: EnumDecisionImpactReport, current: QuerySnapshot, base: QuerySnapshot): String = boundedTextValue(16 * 1024 * 1024) {
        append("Enum expansion candidates: ").append(report.totalCandidates).append('\n')
        append("Decision evidence: ").append(if (report.evidenceAvailable) "captured" else "unavailable").append('\n')
        current.enumDecisionEvidence?.let { evidence ->
            append("Producer decisions (all captured units): observed=").append(evidence.units.sumOf { it.producer.observed.toLong() })
                .append(", emitted=").append(evidence.units.sumOf { it.producer.emitted.toLong() })
                .append(", unsupported=").append(evidence.units.sumOf { it.producer.unsupported.toLong() })
                .append(", ignored=").append(evidence.units.sumOf { it.producer.ignored.toLong() }).append('\n')
            append("Uncovered output roots: ").append(evidence.uncoveredRoots.size).append('\n')
        }
        append("Sites (selected enums): resolved=").append(report.resolvedSites).append(", unresolved=").append(report.unresolvedSites)
            .append(", view-unavailable=").append(report.viewUnavailableSites).append('\n')
        for (comparison in report.comparisons) {
            append(comparison.owner.value).append(": ").append(comparison.status.name)
                .append("; added=").append(comparison.addedConstants.joinToString(",") { it.value }).append('\n')
        }
        for (candidate in report.candidates) {
            val site = candidate.site
            append(site.file.path).append(':').append(site.line).append(':').append(site.column).append(' ')
                .append(site.kind.name).append(" [").append(site.enumOwner.value).append("] ")
                .append(reason(site.kind)).append("; cases=")
                .append(candidate.addedConstants.joinToString(",") { it.value })
                .append("; source-sha256=").append(site.file.sha256).append('\n')
        }
        for (row in report.unresolved) {
            append(row.site.file.path).append(':').append(row.site.line).append(':').append(row.site.column)
                .append(" UNRESOLVED [").append(row.site.enumOwner.value).append("] ").append(row.reason)
                .append("; source-sha256=").append(row.site.file.sha256).append('\n')
        }
        append("Omitted unresolved records: ").append(report.omittedUnresolvedSites).append('\n')
        append("Omitted candidates: ").append(report.omittedCandidates).append('\n')
        (current.limitations + base.limitations + LIMITATIONS).distinct().sorted().forEach {
            append("Limit: ").append(it).append('\n')
        }
    }

    private fun Appendable.append(value: Number): Appendable = append(value.toString())

    private fun input(snapshot: QuerySnapshot, sha256: String): Map<String, Any?> = sortedMapOf(
        "sha256" to sha256, "toolVersion" to snapshot.toolVersion, "scope" to snapshot.scope, "revision" to snapshot.revision)

    private fun fingerprint(value: dev.kartograph.core.InputFingerprint): Map<String, String> = sortedMapOf(
        "role" to value.role, "path" to value.path, "sha256" to value.sha256)

    private fun reason(kind: EnumDecisionKind): String = when (kind) {
        EnumDecisionKind.EXPLICIT_FALLBACK -> "added constants may select an authored fallback"
        EnumDecisionKind.FINITE_MEMBERSHIP -> "added constants are absent from this finite membership set"
        EnumDecisionKind.NEGATED_FINITE_MEMBERSHIP -> "added constants may pass this negated finite membership test"
    }

    private val LIMITATIONS = listOf(
        "Candidates identify source decisions to review; they do not prove execution, reachability, or a behavioral defect.",
        "Only current source coordinates are actionable; baseline evidence contributes enum inventory comparison only.",
        "Saved snapshots use captured inputs; this query does not recheck live source or build freshness.",
        "Resolved sites are limited to one selected, completed output root shared by the source and enum.",
        "Only authored fallback and modeled finite literal membership are captured; arbitrary equality, dynamic/custom collections, and general filter semantics are unsupported.",
        "Kotlin explicit !contains/.not() chains are unsupported; Kotlin !in and Java direct ! membership are modeled.",
        "Unsupported syntax is counted per compilation unit without source-site rows; emitted but unresolved sites retain their own locations and attribution reasons.",
    )
}
