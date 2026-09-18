package dev.kartograph.export

import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.Finding
import dev.kartograph.core.InputHint
import dev.kartograph.core.KartographVersion
import dev.kartograph.core.KeepRule
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import java.net.URI

/** Phase 3에서 지원하는 진단 출력 형식이다. */
public enum class ReportFormat(public val option: String) {
    TEXT("text"), GRADLE("gradle"), GITHUB_ACTIONS("github-actions"), SARIF("sarif"), JSON("json"),
    MARKDOWN("markdown");

    public companion object {
        /** CLI/Gradle 설정 문자열과 정확히 일치하는 형식만 반환한다. */
        public fun fromOption(value: String): ReportFormat? = entries.firstOrNull { it.option == value }
    }
}

/** 같은 finding 집합을 사람과 CI가 소비하는 결정적 문서로 렌더링한다. */
public object AdoptionReporter {
    /**
     * finding과 한계를 선택한 표준 형식으로 정렬해 렌더링한다. confidence는 소스별로 측정한
     * 보고 보조 신호이고, 만료된 억제 항목 수는 0보다 클 때만 machine 형식에 나타난다.
     */
    public fun render(
        format: ReportFormat,
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        suppressedCount: Int,
        confidence: Map<NodeId, FindingConfidence> = emptyMap(),
        expiredSuppressions: Int = 0,
        unmatchedKeepRules: Collection<KeepRule> = emptyList(),
        inputHints: Collection<InputHint> = emptyList(),
    ): String {
        val sortedRules = unmatchedKeepRules.sortedWith(
            compareBy({ it.location.path }, { it.location.line ?: 0 }),
        )
        // 호출자의 컬렉션 종류와 무관하게 enum 선언 순서로 고정해 결정적 출력을 만든다.
        val sortedHints = inputHints.distinct().sortedBy { hint -> hint.ordinal }
        return when (format) {
            ReportFormat.TEXT -> text(findings, limitations, sortedRules, sortedHints)
            ReportFormat.GRADLE -> gradle(findings, limitations, sortedRules, sortedHints)
            ReportFormat.GITHUB_ACTIONS -> github(findings, limitations, sortedRules, sortedHints)
            ReportFormat.JSON -> json(findings, limitations, suppressedCount, confidence, expiredSuppressions, sortedRules, sortedHints)
            ReportFormat.SARIF -> sarif(findings, limitations, confidence, sortedRules, sortedHints)
            ReportFormat.MARKDOWN -> markdown(findings, limitations, suppressedCount, confidence, expiredSuppressions, sortedRules, sortedHints)
        }
    }

    private fun text(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: Collection<InputHint>,
    ): String = buildString {
        findings.sorted().forEach { finding ->
            append("unreachable\t${finding.nodeId}\t${finding.location.toPlainTextLocation()}")
            if (finding.testOnly) append("\ttest-only")
            append('\n')
        }
        unmatchedKeepRules.forEach { rule ->
            append("unmatched-keep-rule\t${rule.location.toPlainTextLocation()}\t${rule.unmatchedMessage()}\n")
        }
        inputHints.forEach { hint ->
            append("input-hint\t${hint.id}\t${hint.description}\n")
        }
        limitations.sortedBy(AnalysisLimitation::name)
            .forEach { append("limitation\t${it.name}\t${it.description}\n") }
    }

    private fun gradle(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: Collection<InputHint>,
    ): String = buildString {
        findings.sorted().forEach { finding ->
            val location = finding.location.toPlainTextLocation().takeUnless { it == "-" }?.plus(": ").orEmpty()
            append("${location}warning: ${finding.unreachableMessage()} [kartograph.dead]\n")
        }
        unmatchedKeepRules.forEach { rule ->
            append("${rule.location.toPlainTextLocation()}: ${rule.unmatchedMessage()} [kartograph.keep-rule]\n")
        }
        inputHints.forEach { hint ->
            append("kartograph input-hint ${hint.id}: ${hint.description}\n")
        }
        limitations.sortedBy(AnalysisLimitation::name).forEach { limitation ->
            append("kartograph limitation ${limitation.name}: ${limitation.description}\n")
        }
    }

    private fun github(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: Collection<InputHint>,
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
            append(githubMessage(finding.unreachableMessage())).append('\n')
        }
        unmatchedKeepRules.forEach { rule ->
            val properties = buildList {
                add("file=${githubProperty(rule.location.path)}")
                rule.location.line?.let { add("line=$it") }
                rule.location.column?.let { add("col=$it") }
                add("title=kartograph unmatched keep rule")
            }
            append("::notice ${properties.joinToString(",")}::")
            append(githubMessage(rule.unmatchedMessage())).append('\n')
        }
        inputHints.forEach { hint ->
            append("::notice title=kartograph input hint ${hint.id}::")
            append(githubMessage(hint.description)).append('\n')
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
        confidence: Map<NodeId, FindingConfidence>,
        expiredSuppressions: Int,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: Collection<InputHint>,
    ): String = buildString {
        append("{\n  \"command\": \"dead\",\n  \"diagnostics\": [")
        val sorted = findings.sorted()
        if (sorted.isEmpty()) {
            append("]")
        } else {
            append('\n')
            sorted.forEachIndexed { index, finding ->
                append("    {\n      \"location\": ")
                appendLocation(finding.location)
                append(",\n      \"message\": \"").append(jsonEscape(finding.unreachableMessage()))
                append("\",\n      \"nodeId\": \"").append(jsonEscape(finding.nodeId.toString()))
                append("\",\n      \"ruleId\": \"dead\",\n      \"state\": \"unreachable\"")
                if (finding.testOnly) append(",\n      \"testOnly\": true")
                confidence[finding.nodeId]?.let { tier ->
                    append(",\n      \"confidence\": \"").append(tier.label).append('"')
                }
                append("\n    }")
                if (index != sorted.lastIndex) append(',')
                append('\n')
            }
            append("  ]")
        }
        append(",\n  \"unmatchedKeepRules\": [")
        if (unmatchedKeepRules.isEmpty()) {
            append("]")
        } else {
            append('\n')
            unmatchedKeepRules.forEachIndexed { index, rule ->
                append("    {\"location\": ")
                appendLocation(rule.location)
                append(", \"message\": \"").append(jsonEscape(rule.unmatchedMessage()))
                append("\"}")
                if (index != unmatchedKeepRules.lastIndex) append(',')
                append('\n')
            }
            append("  ]")
        }
        append(",\n  \"inputHints\": [")
        if (inputHints.isEmpty()) {
            append("]")
        } else {
            append('\n')
            inputHints.forEachIndexed { index, hint ->
                append("    {\"id\": \"").append(jsonEscape(hint.id))
                append("\", \"message\": \"").append(jsonEscape(hint.description)).append("\"}")
                if (index != inputHints.size - 1) append(',')
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
        append(",\n  \"suppressedCount\": $suppressedCount")
        if (expiredSuppressions > 0) append(",\n  \"expiredSuppressions\": $expiredSuppressions")
        append(",\n  \"tool\": \"kartograph\",\n  \"version\": \"")
        append(jsonEscape(KartographVersion.current)).append("\"\n}\n")
    }

    private fun StringBuilder.appendLocation(location: SourceLocation?) {
        if (location == null) append("null") else {
            append("{\"column\": ${location.column ?: 1}, \"line\": ${location.line ?: 1}, \"path\": \"")
            append(jsonEscape(location.path)).append("\"}")
        }
    }

    private fun sarif(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        confidence: Map<NodeId, FindingConfidence>,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: Collection<InputHint>,
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
        unmatchedKeepRules.forEachIndexed { index, rule ->
            if (sortedLimitations.isNotEmpty() || index > 0) append(',')
            append("{\"descriptor\": {\"id\": \"unmatchedKeepRule\"}, \"level\": \"note\", ")
            append("\"locations\": [{\"physicalLocation\": {\"artifactLocation\": {\"uri\": \"")
            append(jsonEscape(uriReference(rule.location.path))).append("\"}, \"region\": {\"startColumn\": ")
            append(rule.location.column ?: 1).append(", \"startLine\": ").append(rule.location.line ?: 1).append("}}}]")
            append(", \"message\": {\"text\": \"")
            append(jsonEscape("${rule.location.toPlainTextLocation()}: ${rule.unmatchedMessage()}"))
            append("\"}}")
        }
        inputHints.forEachIndexed { index, hint ->
            if (sortedLimitations.isNotEmpty() || unmatchedKeepRules.isNotEmpty() || index > 0) append(',')
            append("{\"descriptor\": {\"id\": \"").append(jsonEscape(hint.id))
            append("\"}, \"level\": \"note\", \"message\": {\"text\": \"")
            append(jsonEscape(hint.description)).append("\"}}")
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
                append("], \"message\": {\"text\": \"").append(jsonEscape(finding.unreachableMessage()))
                append("\"}, \"ruleId\": \"dead\"")
                val tier = confidence[finding.nodeId]
                if (finding.testOnly || tier != null) {
                    append(", \"properties\": {")
                    val properties = buildList {
                        if (finding.testOnly) add("\"testOnly\": true")
                        tier?.let { add("\"confidence\": \"${it.label}\"") }
                    }
                    append(properties.joinToString(", "))
                    append("}")
                }
                append("}")
                if (index != sorted.lastIndex) append(',')
                append('\n')
            }
            append("      ]")
        }
        append(",\n      \"tool\": {\"driver\": {\"name\": \"kartograph\", \"rules\": [{\"id\": \"dead\"}], \"version\": \"")
        append(jsonEscape(KartographVersion.current)).append("\"}}\n")
        append("    }\n  ],\n  \"version\": \"2.1.0\"\n}\n")
    }

    /** 사람이 리뷰에서 바로 읽도록 finding 표와 한계를 마크다운으로 렌더링한다. */
    private fun markdown(
        findings: Collection<Finding>,
        limitations: Collection<AnalysisLimitation>,
        suppressedCount: Int,
        confidence: Map<NodeId, FindingConfidence>,
        expiredSuppressions: Int,
        unmatchedKeepRules: List<KeepRule>,
        inputHints: Collection<InputHint>,
    ): String = buildString {
        append("## kartograph dead findings\n\n")
        if (findings.isEmpty()) {
            append("No unreachable declarations.\n")
        } else {
            append("| Location | Declaration | Confidence |\n")
            append("|---|---|---|\n")
            findings.sorted().forEach { finding ->
                append("| `").append(markdownCell(finding.location.toPlainTextLocation()))
                append("` | `").append(markdownCell(finding.nodeId.toString()))
                append("` | ")
                append(confidence[finding.nodeId]?.label ?: FindingConfidence.UNMEASURED.label)
                if (finding.testOnly) append(" (used only by tests)")
                append(" |\n")
            }
        }
        append("\n").append(findings.size).append(" finding(s) reported; ")
        append(suppressedCount).append(" suppressed by baseline or suppress entries")
        if (expiredSuppressions > 0) append("; ").append(expiredSuppressions).append(" suppression(s) expired")
        append(". Findings are reachability facts, not deletion approvals.\n")
        if (unmatchedKeepRules.isNotEmpty()) {
            append("\n## Unmatched keep rules\n\n")
            unmatchedKeepRules.forEach { rule ->
                append("- `").append(markdownCell(rule.location.toPlainTextLocation()))
                append("` — keep rule ").append(rule.declarationKind.name.lowercase())
                append(" `").append(markdownCell(rule.classNamePattern))
                append("` matched no declarations in the indexed graph\n")
            }
            append("\nUnmatched rules may target declarations outside the indexed inputs; ")
            append("an unmatched rule is not proof that it can be removed.\n")
        }
        if (inputHints.isNotEmpty()) {
            append("\n## Input hints\n\n")
            inputHints.forEach { hint ->
                append("- `").append(markdownCell(hint.id)).append("` — ").append(markdownCell(hint.description)).append('\n')
            }
            append("\nMissing inputs can under-measure reachability; hints are not findings ")
            append("and do not fail strict mode.\n")
        }
        append("\n## Limitations\n\n")
        limitations.sortedBy(AnalysisLimitation::name).forEach { limitation ->
            append("- ").append(limitation.name).append(" — ").append(limitation.description).append('\n')
        }
    }

    private fun markdownCell(value: String): String = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun uriReference(path: String): String = URI(null, null, path, null).rawPath
    private fun githubMessage(value: String): String = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A").replace(":", "%3A")
    private fun githubProperty(value: String): String = githubMessage(value).replace(",", "%2C")

    // test-only finding은 production graph에서는 도달 불가하나 test가 참조한다는 사실을 메시지에 덧붙인다.
    private fun Finding.unreachableMessage(): String =
        "$nodeId is unreachable" + if (testOnly) " (used only by tests)" else ""

    // 규칙이 무용하다는 판정이 아니라 색인된 그래프 입력에서 매칭이 없었다는 측정 사실만 담는다.
    private fun KeepRule.unmatchedMessage(): String =
        "keep rule ${declarationKind.name.lowercase()} $classNamePattern matched no declarations in the indexed graph"
}
