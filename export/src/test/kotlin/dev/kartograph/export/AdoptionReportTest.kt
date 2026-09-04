package dev.kartograph.export

import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
        assertContains(json, "\"version\": \"0.1.0\"")
        kotlin.test.assertFalse(json.contains("SNAPSHOT"))
        assertContains(sarif, "\"version\": \"2.1.0\"")
        assertContains(sarif, "\"version\": \"0.1.0\"")
        kotlin.test.assertFalse(sarif.contains("SNAPSHOT"))
        assertContains(sarif, "src/Z%20file.kt")
        assertContains(sarif, "\"toolExecutionNotifications\"")
        assertEquals(json.indexOf("class:a/Unused") < json.indexOf("class:z/Unused"), true)
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
}
