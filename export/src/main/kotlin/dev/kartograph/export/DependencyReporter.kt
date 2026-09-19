package dev.kartograph.export

import dev.kartograph.analysis.DependencyAnalysisResult
import dev.kartograph.core.KartographVersion

/**
 * 선언 의존성 분석 결과를 결정적인 문서로 렌더링한다.
 * artifact는 project-relative 경로를 그대로 쓰고 절대경로는 파일 이름만 남기며, 한계는 모든 형식에 함께 싣는다.
 */
public object DependencyReporter {
    public fun render(
        format: ReportFormat,
        result: DependencyAnalysisResult,
        limitations: Collection<String>,
    ): String = when (format) {
        ReportFormat.TEXT -> text(result, limitations)
        ReportFormat.JSON -> json(result, limitations)
        else -> throw IllegalArgumentException("dependencies supports text and json report formats")
    }

    private fun text(result: DependencyAnalysisResult, limitations: Collection<String>): String = buildString {
        result.findings.forEach { finding ->
            append("unused-dependency\t${finding.coordinate}\t${finding.scope.option}\t${artifactLabel(finding.artifact)}\n")
        }
        limitations.sorted().forEach { limitation -> append("limitation\t$limitation\n") }
    }

    private fun json(result: DependencyAnalysisResult, limitations: Collection<String>): String = buildString {
        append("{\n  \"command\": \"dependencies\",\n  \"diagnostics\": [")
        if (result.findings.isEmpty()) {
            append("]")
        } else {
            append('\n')
            result.findings.forEachIndexed { index, finding ->
                append("    {\n      \"artifact\": \"").append(jsonEscape(artifactLabel(finding.artifact)))
                append("\",\n      \"coordinate\": \"").append(jsonEscape(finding.coordinate))
                append("\",\n      \"ruleId\": \"unused-dependency\",\n      \"scope\": \"").append(finding.scope.option)
                append("\",\n      \"state\": \"unused\"\n    }")
                if (index != result.findings.lastIndex) append(',')
                append('\n')
            }
            append("  ]")
        }
        append(",\n  \"limitations\": [")
        val descriptions = limitations.sorted()
        if (descriptions.isEmpty()) {
            append("]")
        } else {
            append('\n')
            descriptions.forEachIndexed { index, value ->
                append("    \"").append(jsonEscape(value)).append('"')
                if (index != descriptions.lastIndex) append(',')
                append('\n')
            }
            append("  ]")
        }
        append(",\n  \"analyzedDependencies\": ").append(result.analyzedCount)
        append(",\n  \"skippedDependencies\": ").append(result.skippedCount)
        append(",\n  \"withoutClassArtifacts\": ").append(result.withoutClassCount)
        append(",\n  \"tool\": \"kartograph\",\n  \"version\": \"")
        append(jsonEscape(KartographVersion.current)).append("\"\n}\n")
    }

    private fun artifactLabel(artifact: String): String =
        if (isAbsolutePath(artifact)) artifact.substringAfterLast('/').substringAfterLast('\\') else artifact

    private fun isAbsolutePath(artifact: String): Boolean =
        artifact.startsWith('/') || artifact.startsWith('\\') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(artifact)
}
