package dev.kartograph.export

import dev.kartograph.analysis.DependencyAnalysisResult
import dev.kartograph.analysis.UnusedDependency
import dev.kartograph.core.DependencyScope
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    }

    @Test
    fun `unsupported formats fail closed`() {
        assertFailsWith<IllegalArgumentException> { DependencyReporter.render(ReportFormat.SARIF, result, emptyList()) }
        assertFailsWith<IllegalArgumentException> { DependencyReporter.render(ReportFormat.MARKDOWN, result, emptyList()) }
    }
}
