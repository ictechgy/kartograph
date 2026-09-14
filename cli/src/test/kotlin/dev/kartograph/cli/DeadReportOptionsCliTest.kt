package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class DeadReportOptionsCliTest {
    @Test
    fun `dead json grades findings by measured runtime channels of their source`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val arguments = deadArguments(projectRoot, "<manifest />", "--report-format", "json")

        val execution = execute(*arguments, "--classes", classRoot.toString())

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "\"confidence\": \"needs-runtime-review\"")
        assertContains(execution.output, "\"confidence\": \"static\"")
        // 관측은 소스 파일 기준이므로 Reflective.java의 finding만 검토 등급이다.
        val reflectiveDiagnostic = execution.output
            .substringAfter("class:sample/Reflective", "")
            .substringBefore("class:sample/SuppressMe")
        assertFalse(reflectiveDiagnostic.contains("\"confidence\": \"static\""))
    }

    @Test
    fun `dead text format stays free of confidence annotations`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val arguments = deadArguments(projectRoot, "<manifest />", "--classes", classRoot.toString())

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "unreachable\tclass:sample/Reflective")
        assertFalse(execution.output.contains("confidence"))
    }

    @Test
    fun `dead markdown renders a findings table for review descriptions`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classes",
            classRoot.toString(),
            "--report-format",
            "markdown",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "| Location | Declaration | Confidence |")
        assertContains(execution.output, "`class:sample/Reflective`")
        assertContains(execution.output, "needs-runtime-review")
        assertContains(execution.output, "not deletion approvals")
        assertContains(execution.output, "## Limitations")
    }

    @Test
    fun `dead suppress hides findings until the expires date`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val suppressFile = projectRoot.resolve("suppress.json")
        suppressFile.writeText(
            """
            {
              "suppressions": [
                {
                  "expires": "2099-01-01",
                  "fingerprint": "dead|class:sample/SuppressMe|SuppressMe.java",
                  "reason": "reviewed deletion candidate, waiting for the next release"
                }
              ],
              "version": 1
            }
            """.trimIndent(),
        )
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classes",
            classRoot.toString(),
            "--suppress",
            "suppress.json",
            "--report-format",
            "json",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        // 억제는 해당 지문에만 적용되고 다른 finding은 그대로 보고된다.
        assertFalse(execution.output.contains("class:sample/SuppressMe"))
        assertContains(execution.output, "class:sample/Clean")
        assertContains(execution.output, "\"suppressedCount\": 1")
        assertFalse(execution.output.contains("expiredSuppressions"))
    }

    @Test
    fun `dead reports expired suppressions and keeps the finding`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val suppressFile = projectRoot.resolve("suppress.json")
        suppressFile.writeText(
            """
            {
              "suppressions": [
                {
                  "expires": "2000-01-01",
                  "fingerprint": "dead|class:sample/SuppressMe|SuppressMe.java",
                  "reason": "stale review window"
                }
              ],
              "version": 1
            }
            """.trimIndent(),
        )
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classes",
            classRoot.toString(),
            "--suppress",
            "suppress.json",
            "--report-format",
            "json",
            "--strict",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "class:sample/SuppressMe")
        assertContains(execution.output, "\"expiredSuppressions\": 1")
    }

    @Test
    fun `dead suppress fails closed on malformed files`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val suppressFile = projectRoot.resolve("suppress.json")
        suppressFile.writeText("{\n  \"version\": 1\n}")
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classes",
            classRoot.toString(),
            "--suppress",
            "suppress.json",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "suppress entries are missing")
    }

    @Test
    fun `dead suppress rejects unsupported fields and bad dates`(@TempDir projectRoot: Path) {
        val classRoot = compileSamplePair(projectRoot)
        val suppressFile = projectRoot.resolve("suppress.json")
        suppressFile.writeText(
            """
            {
              "suppressions": [
                { "expires": "next tuesday", "fingerprint": "dead|x", "reason": "y" }
              ],
              "version": 1
            }
            """.trimIndent(),
        )
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classes",
            classRoot.toString(),
            "--suppress",
            "suppress.json",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "suppress expires must be an ISO calendar date")
    }

    private fun execute(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(
            arguments = arguments,
            output = PrintStream(output),
            error = PrintStream(error),
        )
        return Execution(status, output.toString(), error.toString())
    }

    private fun deadArguments(projectRoot: Path, manifest: String, vararg extra: String): Array<String> {
        projectRoot.resolve("AndroidManifest.xml").writeText(manifest)
        projectRoot.resolve("res").createDirectories()
        return arrayOf(
            "dead",
            "--classes",
            classRoot.toString(),
            "--project",
            projectRoot.toString(),
            "--manifest",
            "AndroidManifest.xml",
            "--resources",
            "res",
            "--namespace",
            "dev.kartograph.cli",
            *extra,
        )
    }

    private val classRoot: Path
        get() = Path.of(requireNotNull(KartographCli::class.java.protectionDomain.codeSource).location.toURI())

    private fun compileSamplePair(root: Path): Path {
        val classes = root.resolve("sample-classes").createDirectories()
        val clean = root.resolve("sample-sources/sample/Clean.java")
        val reflective = root.resolve("sample-sources/sample/Reflective.java")
        val suppressMe = root.resolve("sample-sources/sample/SuppressMe.java")
        clean.parent.createDirectories()
        clean.writeText("package sample; public class Clean {}\n")
        reflective.writeText(
            "package sample; public class Reflective { public Object load() throws Exception { " +
                "return Class.forName(System.getProperty(\"sample.target\")).getDeclaredConstructor().newInstance(); } }\n",
        )
        suppressMe.writeText("package sample; public class SuppressMe {}\n")
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        check(
            compiler.run(
                null, null, null, "-d", classes.toString(),
                clean.toString(), reflective.toString(), suppressMe.toString(),
            ) == 0,
        )
        return classes
    }

    private data class Execution(
        val status: Int,
        val output: String,
        val error: String,
    )
}
