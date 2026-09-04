package dev.kartograph.export

import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.Finding
import dev.kartograph.core.KartographVersion
import java.net.URI

/** Phase 3에서 지원하는 진단 출력 형식이다. */
public enum class ReportFormat(public val option: String) {
    TEXT("text"), GRADLE("gradle"), GITHUB_ACTIONS("github-actions"), SARIF("sarif"), JSON("json");

    public companion object {
        /** CLI/Gradle 설정 문자열과 정확히 일치하는 형식만 반환한다. */
        public fun fromOption(value: String): ReportFormat? = entries.firstOrNull { it.option == value }
    }
}

/** 같은 finding 집합을 사람과 CI가 소비하는 결정적 문서로 렌더링한다. */
public object AdoptionReporter {
    /** finding과 한계를 선택한 표준 형식으로 정렬해 렌더링한다. */
    public fun render(
        format: ReportFormat,
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        suppressedCount: Int,
    ): String = when (format) {
        ReportFormat.TEXT -> text(findings, limitations)
        ReportFormat.GRADLE -> gradle(findings, limitations)
        ReportFormat.GITHUB_ACTIONS -> github(findings, limitations)
        ReportFormat.JSON -> json(findings, limitations, suppressedCount)
        ReportFormat.SARIF -> sarif(findings, limitations)
    }

    private fun text(findings: Collection<Finding>, limitations: Collection<AnalysisLimitation>): String = buildString {
        findings.sorted().forEach { finding ->
            append("unreachable\t${finding.nodeId}\t${finding.location.toPlainTextLocation()}\n")
        }
        limitations.sortedBy(AnalysisLimitation::name)
            .forEach { append("limitation\t${it.name}\t${it.description}\n") }
    }

    private fun gradle(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
    ): String = buildString {
        findings.sorted().forEach { finding ->
            val location = finding.location.toPlainTextLocation().takeUnless { it == "-" }?.plus(": ").orEmpty()
            append("${location}warning: ${finding.nodeId} is unreachable [kartograph.dead]\n")
        }
        limitations.sortedBy(AnalysisLimitation::name).forEach { limitation ->
            append("kartograph limitation ${limitation.name}: ${limitation.description}\n")
        }
    }

    private fun github(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
    ): String = buildString {
        findings.sorted().forEach { finding ->
            val properties = buildList {
                finding.location?.let { location ->
                    add("file=${githubProperty(location.path)}")
                    location.line?.let { add("line=$it") }
                    location.column?.let { add("col=$it") }
                }
                add("title=kartograph dead")
            }
            append("::warning ${properties.joinToString(",")}::")
            append(githubMessage("${finding.nodeId} is unreachable")).append('\n')
        }
        limitations.sortedBy(AnalysisLimitation::name).forEach { limitation ->
            append("::notice title=kartograph limitation ${limitation.name}::")
            append(githubMessage(limitation.description)).append('\n')
        }
    }

    private fun json(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        suppressedCount: Int,
    ): String = buildString {
        append("{\n  \"command\": \"dead\",\n  \"diagnostics\": [")
        val sorted = findings.sorted()
        if (sorted.isEmpty()) {
            append("]")
        } else {
            append('\n')
            sorted.forEachIndexed { index, finding ->
                append("    {\n      \"location\": ")
                appendLocation(finding)
                append(",\n      \"message\": \"").append(jsonEscape("${finding.nodeId} is unreachable"))
                append("\",\n      \"nodeId\": \"").append(jsonEscape(finding.nodeId.toString()))
                append("\",\n      \"ruleId\": \"dead\",\n      \"state\": \"unreachable\"\n    }")
                if (index != sorted.lastIndex) append(',')
                append('\n')
            }
            append("  ]")
        }
        append(",\n  \"limitations\": [")
        val descriptions = limitations.map(AnalysisLimitation::description).sorted()
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
        append(",\n  \"suppressedCount\": $suppressedCount,\n  \"tool\": \"kartograph\",\n  \"version\": \"")
        append(jsonEscape(KartographVersion.current)).append("\"\n}\n")
    }

    private fun StringBuilder.appendLocation(finding: Finding) {
        val location = finding.location
        if (location == null) append("null") else {
            append("{\"column\": ${location.column ?: 1}, \"line\": ${location.line ?: 1}, \"path\": \"")
            append(jsonEscape(location.path)).append("\"}")
        }
    }

    private fun sarif(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
    ): String = buildString {
        append("{\n  \"${'$'}schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",\n  \"runs\": [\n    {\n")
        append("      \"invocations\": [{\"executionSuccessful\": true, \"toolExecutionNotifications\": [")
        val sortedLimitations = limitations.sortedBy(AnalysisLimitation::name)
        sortedLimitations.forEachIndexed { index, limitation ->
            if (index > 0) append(',')
            append("{\"descriptor\": {\"id\": \"").append(jsonEscape(limitation.name))
            append("\"}, \"level\": \"note\", \"message\": {\"text\": \"")
            append(jsonEscape(limitation.description)).append("\"}}")
        }
        append("]}],\n")
        append("      \"results\": [")
        val sorted = findings.sorted()
        if (sorted.isEmpty()) {
            append("]")
        } else {
            append('\n')
            sorted.forEachIndexed { index, finding ->
                append("        {\"level\": \"warning\", \"locations\": [")
                finding.location?.let { location ->
                    append("{\"physicalLocation\": {\"artifactLocation\": {\"uri\": \"")
                    append(jsonEscape(uriReference(location.path))).append("\"}, \"region\": {\"startColumn\": ")
                    append(location.column ?: 1).append(", \"startLine\": ").append(location.line ?: 1).append("}}}")
                }
                append("], \"message\": {\"text\": \"").append(jsonEscape("${finding.nodeId} is unreachable"))
                append("\"}, \"ruleId\": \"dead\"}")
                if (index != sorted.lastIndex) append(',')
                append('\n')
            }
            append("      ]")
        }
        append(",\n      \"tool\": {\"driver\": {\"name\": \"kartograph\", \"rules\": [{\"id\": \"dead\"}], \"version\": \"")
        append(jsonEscape(KartographVersion.current)).append("\"}}\n")
        append("    }\n  ],\n  \"version\": \"2.1.0\"\n}\n")
    }

    private fun uriReference(path: String): String = URI(null, null, path, null).rawPath
    private fun githubMessage(value: String): String = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A").replace(":", "%3A")
    private fun githubProperty(value: String): String = githubMessage(value).replace(",", "%2C")
}
