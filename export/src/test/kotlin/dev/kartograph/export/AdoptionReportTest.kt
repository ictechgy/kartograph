package dev.kartograph.export

import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AdoptionReportTest {
    private val findings = listOf(
        Finding(NodeId("class:z/Unused"), SourceLocation("src/Z file.kt", 7, 2)),
        Finding(NodeId("class:a/Unused"), SourceLocation("src/A.kt", 3)),
    )

    @Test
    fun `baseline codec sorts unique line independent fingerprints`() {
        val moved = findings.first().copy(location = SourceLocation("src/Z file.kt", 99))

        val text = BaselineCodec.render(listOf(findings.first(), findings.last(), moved))

        assertEquals(
            """
                {
                  "fingerprints": [
                    "dead|class:a/Unused|src/A.kt",
                    "dead|class:z/Unused|src/Z file.kt"
                  ],
                  "generatedBy": "kartograph",
                  "version": 1
                }

            """.trimIndent(),
            text,
        )
        assertEquals(setOf("dead|class:a/Unused|src/A.kt", "dead|class:z/Unused|src/Z file.kt"), BaselineCodec.parse(text))
    }

    @Test
    fun `baseline round trips JSON delimiters and field-like text inside paths`() {
        val finding = Finding(
            NodeId("class:fixture/Bracketed"),
            SourceLocation("src/[generated]/\"version\": 2.kt", 4),
        )

        val rendered = BaselineCodec.render(listOf(finding))

        assertEquals(setOf(finding.fingerprint), BaselineCodec.parse(rendered))
    }

    @Test
    fun `all adoption reports are sorted and machine readable`() {
        val limitations = AnalysisLimitation.entries

        val gradle = AdoptionReporter.render(ReportFormat.GRADLE, findings, limitations, 1)
        val github = AdoptionReporter.render(ReportFormat.GITHUB_ACTIONS, findings, limitations, 1)
        val json = AdoptionReporter.render(ReportFormat.JSON, findings, limitations, 1)
        val sarif = AdoptionReporter.render(ReportFormat.SARIF, findings, limitations, 1)

        assertContains(gradle, "src/A.kt:3: warning: class:a/Unused is unreachable [kartograph.dead]")
        assertContains(gradle, "kartograph limitation REFLECTION_STRINGS:")
        assertContains(github, "::warning file=src/A.kt,line=3,title=kartograph dead::class%3Aa/Unused is unreachable")
        assertContains(github, "::notice title=kartograph limitation REFLECTION_STRINGS::")
        assertContains(json, "\"suppressedCount\": 1")
        assertContains(json, "\"nodeId\": \"class:a/Unused\"")
        val releaseVersion = Path.of("../VERSION").readText().trim()
        assertContains(json, "\"version\": \"$releaseVersion\"")
        kotlin.test.assertFalse(json.contains("SNAPSHOT"))
        assertContains(sarif, "\"version\": \"2.1.0\"")
        assertContains(sarif, "\"version\": \"$releaseVersion\"")
        kotlin.test.assertFalse(sarif.contains("SNAPSHOT"))
        assertContains(sarif, "src/Z%20file.kt")
        assertContains(sarif, "\"toolExecutionNotifications\"")
        assertEquals(json.indexOf("class:a/Unused") < json.indexOf("class:z/Unused"), true)
    }

    @Test
    fun `test-only findings are annotated across report formats without changing plain findings`() {
        val testOnly = Finding(NodeId("class:t/OnlyTestUsed"), SourceLocation("src/T.kt", 5), testOnly = true)
        val plain = Finding(NodeId("class:p/Unused"), SourceLocation("src/P.kt", 2))
        val both = listOf(testOnly, plain)
        val noLimitations = emptyList<AnalysisLimitation>()

        val text = AdoptionReporter.render(ReportFormat.TEXT, both, noLimitations, 0)
        assertContains(text, "unreachable\tclass:t/OnlyTestUsed\tsrc/T.kt:5\ttest-only\n")
        assertContains(text, "unreachable\tclass:p/Unused\tsrc/P.kt:2\n")

        val gradle = AdoptionReporter.render(ReportFormat.GRADLE, both, noLimitations, 0)
        assertContains(gradle, "class:t/OnlyTestUsed is unreachable (used only by tests) [kartograph.dead]")
        assertContains(gradle, "class:p/Unused is unreachable [kartograph.dead]")

        val github = AdoptionReporter.render(ReportFormat.GITHUB_ACTIONS, both, noLimitations, 0)
        assertContains(github, "class%3At/OnlyTestUsed is unreachable (used only by tests)")

        val json = AdoptionReporter.render(ReportFormat.JSON, both, noLimitations, 0)
        assertContains(json, "\"testOnly\": true")
        assertContains(json, "class:t/OnlyTestUsed is unreachable (used only by tests)")
        assertFalse(json.contains("\"testOnly\": false"))

        val sarif = AdoptionReporter.render(ReportFormat.SARIF, both, noLimitations, 0)
        assertContains(sarif, "\"properties\": {\"testOnly\": true}")
        assertEquals(1, Regex("\"testOnly\": true").findAll(sarif).count())
    }

    @Test
    fun `empty baseline and machine reports use canonical empty arrays`() {
        assertContains(BaselineCodec.render(emptyList()), "\"fingerprints\": []")
        assertContains(
            AdoptionReporter.render(ReportFormat.JSON, emptyList(), emptyList(), 0),
            "\"diagnostics\": []",
        )
        assertContains(
            AdoptionReporter.render(ReportFormat.SARIF, emptyList(), emptyList(), 0),
            "\"results\": []",
        )
    }

    @Test
    fun `sarif percent encodes URI punctuation in source paths`() {
        val finding = Finding(
            NodeId("class:fixture/Generated"),
            SourceLocation("src/[generated]# file.kt", 4),
        )

        val sarif = AdoptionReporter.render(ReportFormat.SARIF, listOf(finding), emptyList(), 0)

        assertContains(sarif, "src/%5Bgenerated%5D%23%20file.kt")
    }
}
