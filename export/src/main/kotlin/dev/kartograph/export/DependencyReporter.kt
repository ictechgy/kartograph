package dev.kartograph.export

import dev.kartograph.analysis.DependencyAnalysisResult
import dev.kartograph.analysis.DependencyReview
import dev.kartograph.core.KartographVersion

/** 같은 의존성 진단과 관찰 한계를 사람·CI·machine 형식으로 손실 없이 전달한다. */
public object DependencyReporter {
    public fun render(format: ReportFormat, result: DependencyAnalysisResult, limitations: Collection<String>): String {
        val diagnostics = diagnostics(result)
        val gaps = (limitations + buildList {
            if (result.baselineSuppressedCount > 0 || result.suppressionCount > 0 || result.expiredSuppressionCount > 0) {
                add("dependency-review: ${result.baselineSuppressedCount} baselined; ${result.suppressionCount} temporarily suppressed; ${result.expiredSuppressionCount} expired suppression entries; filtering is not removal approval")
            }
        }).distinct().sorted()
        return when (format) {
            ReportFormat.TEXT -> buildString {
                diagnostics.forEach { item ->
                    append("${item.rule}\t${plain(item.coordinate)}\t${item.scope ?: "-"}\t${plain(item.artifact)}")
                    if (item.rule != "unused-dependency") append("\tto=${item.suggested ?: "unresolved"}\tclasses=${plain(item.evidence.joinToString(","))}")
                    append('\n')
                }
                gaps.forEach { append("limitation\t${plain(it)}\n") }
            }
            ReportFormat.JSON -> jsonValue(sortedMapOf(
                "command" to "dependencies", "diagnostics" to diagnostics.map(::jsonDiagnostic),
                "limitations" to gaps, "analyzedDependencies" to result.analyzedCount,
                "skippedDependencies" to result.skippedCount, "withoutClassArtifacts" to result.withoutClassCount,
                "ambiguousClassOwnership" to result.ambiguousClassCount,
                "baselineSuppressed" to result.baselineSuppressedCount, "suppressed" to result.suppressionCount,
                "expiredSuppressions" to result.expiredSuppressionCount,
                "tool" to "kartograph", "version" to KartographVersion.current,
            )) + "\n"
            ReportFormat.GRADLE -> buildString {
                diagnostics.forEach { append("warning: ${plain(message(it))}\n") }
                gaps.forEach { append("note: ${plain(it)}\n") }
            }
            ReportFormat.GITHUB_ACTIONS -> buildString {
                diagnostics.forEach { append("::warning title=${it.rule}::${github(message(it))}\n") }
                gaps.forEach { append("::notice title=analysis-limitation::${github(it)}\n") }
            }
            ReportFormat.MARKDOWN -> markdown(diagnostics, gaps)
            ReportFormat.SARIF -> sarif(diagnostics, gaps)
        }
    }

    private fun diagnostics(result: DependencyAnalysisResult): List<Item> =
        (result.findings.map { Item("unused-dependency", it.coordinate, it.scope.option, artifactLabel(it.artifact), null, emptyList()) } +
            result.advice.map { Item(it.ruleId, it.coordinate, it.scope?.option, artifactLabel(it.artifact), it.suggestedScope?.option, it.evidence.sorted()) })
            .distinct().sortedWith(compareBy({ it.coordinate }, { it.rule }, { it.scope.orEmpty() }, { it.suggested.orEmpty() }, { it.artifact }))

    private fun jsonDiagnostic(item: Item): Map<String, Any> = sortedMapOf<String, Any>(
        "ruleId" to item.rule, "coordinate" to item.coordinate, "artifact" to item.artifact,
        "fingerprint" to fingerprint(item),
        "state" to when (item.rule) { "unused-dependency" -> "unused"; "undeclared-dependency" -> "undeclared"; else -> "scope-mismatch" },
    ).apply {
        item.scope?.let { put("scope", it) }
        item.suggested?.let { put("suggestedScope", it) }
        if (item.rule != "unused-dependency") put("evidenceClasses", item.evidence)
    }

    private fun message(item: Item): String = when (item.rule) {
        "unused-dependency" -> "unused-dependency: ${item.coordinate} (${item.scope}); no compiled reference was observed"
        "undeclared-dependency" -> "undeclared-dependency: ${item.coordinate}; observed use requires a direct declaration (${item.suggested ?: "API scope unresolved"})"
        else -> "dependency-scope-mismatch: ${item.coordinate}; review ${item.scope} -> ${item.suggested} using the observed ABI references"
    }

    private fun fingerprint(item: Item): String = DependencyReview.fingerprint(item.rule, item.coordinate, item.scope, item.suggested, item.evidence)

    private fun markdown(items: List<Item>, gaps: List<String>): String = buildString {
        append("# Dependency analysis\n\n")
        if (items.isEmpty()) append("No dependency findings in the supplied compiled inputs.\n") else {
            append("| Rule | Coordinate | Scope | Suggested scope | Artifact | Evidence classes |\n|---|---|---|---|---|---|\n")
            items.forEach { item ->
                append(listOf(item.rule, item.coordinate, item.scope ?: "-", item.suggested ?: "-", item.artifact,
                    item.evidence.joinToString(", ")).joinToString(" | ", "| ", " |\n", transform = ::markdownCell))
            }
        }
        if (gaps.isNotEmpty()) { append("\n## Limitations\n\n"); gaps.forEach { append("- ${markdownCell(it)}\n") } }
    }

    private fun sarif(items: List<Item>, gaps: List<String>): String = jsonValue(sortedMapOf(
        "\$schema" to "https://json.schemastore.org/sarif-2.1.0.json", "version" to "2.1.0",
        "runs" to listOf(sortedMapOf(
            "tool" to sortedMapOf("driver" to sortedMapOf(
                "name" to "kartograph", "version" to KartographVersion.current,
                "rules" to items.map { it.rule }.distinct().sorted().map { sortedMapOf("id" to it, "shortDescription" to mapOf("text" to it)) },
            )),
            "results" to items.map { item -> sortedMapOf(
                "ruleId" to item.rule, "level" to "warning", "message" to mapOf("text" to message(item)),
                "partialFingerprints" to mapOf("kartograph/dependencies/v1" to fingerprint(item)),
                // 선언 위치를 모르는 상황에서 artifact 경로를 build 파일 위치로 위장하지 않는다.
                "properties" to jsonDiagnostic(item),
            ) },
            "invocations" to listOf(sortedMapOf("executionSuccessful" to true,
                "toolExecutionNotifications" to gaps.map { mapOf("level" to "note", "message" to mapOf("text" to it)) })),
        )),
    )) + "\n"

    private fun artifactLabel(artifact: String): String =
        if (artifact.startsWith('/') || artifact.startsWith('\\') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(artifact))
            artifact.substringAfterLast('/').substringAfterLast('\\') else artifact

    private fun plain(value: String): String = value.map { if (it.isISOControl()) ' ' else it }.joinToString("")
    private fun github(value: String): String = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    private fun markdownCell(value: String): String = buildString {
        plain(value).forEach { character ->
            when (character) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\\', '|', '`', '[', ']', '*', '_' -> { append('\\'); append(character) }
                else -> append(character)
            }
        }
    }
    private data class Item(val rule: String, val coordinate: String, val scope: String?, val artifact: String,
        val suggested: String?, val evidence: List<String>)
}
