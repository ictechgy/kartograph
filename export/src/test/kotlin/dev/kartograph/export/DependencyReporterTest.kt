package dev.kartograph.export

import dev.kartograph.analysis.DependencyAnalysisResult
import dev.kartograph.analysis.DependencyAdvice
import dev.kartograph.analysis.UnusedDependency
import dev.kartograph.core.DependencyScope
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DependencyReporterTest {
    private val result = DependencyAnalysisResult(
        findings = listOf(
            UnusedDependency("com.example:lib:1.0", DependencyScope.IMPLEMENTATION, "build/libs/lib-1.0.jar"),
        ),
        analyzedCount = 3,
        skippedCount = 1,
        withoutClassCount = 1,
    )

    @Test
    fun `text renders findings then sorted limitations`() {
        val text = DependencyReporter.render(ReportFormat.TEXT, result, listOf("limitation b", "limitation a"))

        assertEquals(
            "unused-dependency\tcom.example:lib:1.0\timplementation\tbuild/libs/lib-1.0.jar\n" +
                "limitation\tlimitation a\n" +
                "limitation\tlimitation b\n",
            text,
        )
    }

    @Test
    fun `json carries stable fields and measured counts`() {
        val json = DependencyReporter.render(ReportFormat.JSON, result, listOf("only supplied roots are measured"))

        assertContains(json, "\"command\": \"dependencies\"")
        assertContains(json, "\"artifact\": \"build/libs/lib-1.0.jar\"")
        assertContains(json, "\"coordinate\": \"com.example:lib:1.0\"")
        assertContains(json, "\"ruleId\": \"unused-dependency\"")
        assertContains(json, "\"scope\": \"implementation\"")
        assertContains(json, "\"state\": \"unused\"")
        assertContains(json, "\"analyzedDependencies\": 3")
        assertContains(json, "\"skippedDependencies\": 1")
        assertContains(json, "\"withoutClassArtifacts\": 1")
        assertContains(json, "\"limitations\"")
        val releaseVersion = Path.of("../VERSION").readText().trim()
        assertContains(json, "\"version\": \"$releaseVersion\"")
    }

    @Test
    fun `empty results keep machine documents stable`() {
        val empty = DependencyAnalysisResult(emptyList(), analyzedCount = 0, skippedCount = 0, withoutClassCount = 0)

        assertEquals("limitation\tkept\n", DependencyReporter.render(ReportFormat.TEXT, empty, listOf("kept")))
        assertContains(DependencyReporter.render(ReportFormat.JSON, empty, emptyList()), "\"diagnostics\": []")
        assertContains(DependencyReporter.render(ReportFormat.JSON, empty, emptyList()), "\"limitations\": []")
    }

    @Test
    fun `artifact labels keep relative paths and strip absolute prefixes`() {
        val relative = UnusedDependency("com.example:lib:1.0", DependencyScope.API, "gradle-plugin/build/classes/kotlin/main")
        val relativeText = DependencyReporter.render(ReportFormat.TEXT, result.copy(findings = listOf(relative)), emptyList())
        assertContains(relativeText, "gradle-plugin/build/classes/kotlin/main")

        val absolute = UnusedDependency("com.example:lib:1.0", DependencyScope.API, "/Users/private/libs/lib.jar")
        val text = DependencyReporter.render(ReportFormat.TEXT, result.copy(findings = listOf(absolute)), emptyList())
        val json = DependencyReporter.render(ReportFormat.JSON, result.copy(findings = listOf(absolute)), emptyList())

        assertFalse(text.contains("/Users/private"))
        assertContains(text, "lib.jar")
        assertFalse(json.contains("/Users/private"))
        assertContains(json, "\"artifact\": \"lib.jar\"")

        val windows = UnusedDependency("com.example:lib:1.0", DependencyScope.API, "C:\\libs\\lib.jar")
        val windowsText = DependencyReporter.render(ReportFormat.TEXT, result.copy(findings = listOf(windows)), emptyList())
        assertFalse(windowsText.contains("C:\\libs"))
        assertContains(windowsText, "lib.jar")
    }

    @Test
    fun `all report formats preserve configuration advice evidence and limitations`() {
        val advice = DependencyAdvice("example:api:1", DependencyScope.IMPLEMENTATION, "libs/api.jar",
            "dependency-scope-mismatch", DependencyScope.API, listOf("example/Exposed"))
        val resolved = DependencyAdvice("example:transitive:1", null, "/private/cache/transitive.jar",
            "undeclared-dependency", DependencyScope.IMPLEMENTATION, listOf("example/Transitive"))
        val combined = result.copy(advice = listOf(advice, resolved))
        for (format in ReportFormat.entries) {
            val text = DependencyReporter.render(format, combined, listOf("scope remains limited"))
            assertContains(text, "example:api:1")
            assertContains(text, "example:transitive:1")
            assertContains(text, "scope remains limited")
            assertFalse(text.contains("/private/cache"))
        }
        val json = DependencyReporter.render(ReportFormat.JSON, combined, emptyList())
        assertContains(json, "\"suggestedScope\": \"api\"")
        assertContains(json, "\"evidenceClasses\": [\"example/Exposed\"]")
        val sarif = DependencyReporter.render(ReportFormat.SARIF, combined, listOf("kept gap"))
        assertContains(sarif, "\"version\": \"2.1.0\"")
        assertContains(sarif, "toolExecutionNotifications")
        assertFalse(sarif.contains("physicalLocation"))
        val parsed = McpJsonCodec.parse(sarif) as Map<*, *>
        assertEquals("2.1.0", parsed["version"])
        val run = (parsed["runs"] as List<*>).single() as Map<*, *>
        assertEquals(3, (run["results"] as List<*>).size)
    }

    @Test
    fun `CI and markdown escaping does not turn input into a new annotation or table row`() {
        val hostile = result.copy(findings = listOf(UnusedDependency("lib:%0A\n::error title=x::x|[link]",
            DependencyScope.API, "folder/<name>.jar")))
        val github = DependencyReporter.render(ReportFormat.GITHUB_ACTIONS, hostile, listOf("line1\nline2"))
        assertContains(github, "%250A%0A::error")
        assertEquals(2, github.lineSequence().filter(String::isNotBlank).count())
        val markdown = DependencyReporter.render(ReportFormat.MARKDOWN, hostile, emptyList())
        assertContains(markdown, "\\|\\[link\\]")
        assertContains(markdown, "&lt;name&gt;")
    }
}
