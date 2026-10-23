package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class FeedbackLocationTest {
    @Test fun `unknown keep cannot report successful SARIF and raw inheritance survives reporting`() {
        val rule = KeepRule(KeepDeclarationKind.CLASS, "Missing", "sample.Contract", SourceLocation("keep.pro", 2, 1),
            rawSpecification = "-keep class Missing implements sample.Contract")
        for (format in ReportFormat.entries) {
            val report = AdoptionReporter.render(format, emptyList(), emptyList(), 0,
                unmatchedKeepRules = listOf(rule), inputHints = listOf(InputHint.UNKNOWN_KEEP_RULES))
            assertContains(report, "implements sample.Contract")
            assertContains(report, "unknown")
            if (format == ReportFormat.SARIF) assertContains(report, "\"executionSuccessful\": false")
        }
    }
    @Test fun `missing source coordinates are null in JSON and absent from SARIF region`() {
        val findings = listOf(Finding(NodeId("class:Example"), SourceLocation("Example.java")))
        val json = AdoptionReporter.render(ReportFormat.JSON, findings, emptyList(), 0)
        assertContains(json, "\"line\": null")
        assertContains(json, "\"column\": null")
        val sarif = AdoptionReporter.render(ReportFormat.SARIF, findings, emptyList(), 0)
        assertContains(sarif, "\"uri\": \"Example.java\"")
        assertFalse(sarif.contains("\"region\""))
        val known = AdoptionReporter.render(ReportFormat.SARIF, listOf(findings.single().copy(location = SourceLocation("Example.java", 5))), emptyList(), 0)
        assertContains(known, "\"startLine\": 5")
        assertFalse(known.contains("\"startColumn\""))
    }
}
