package dev.kartograph.gradle

import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class SafeArgsBuildEvidenceTest {
    @Test
    @Timeout(180)
    fun `실제 실패 빌드의 출력과 완료 실패 task를 저장하고 원래 예외를 던진다`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'failure-evidence'\n")
        Files.writeString(root.resolve("build.gradle"), """
            tasks.register('beforeFailure') { doLast { println 'before-failure-sentinel' } }
            tasks.register('expectedFailure') {
                dependsOn 'beforeFailure'
                doLast { println 'failure-output-sentinel'; throw new GradleException('intentional-fixture-failure') }
            }
        """.trimIndent())
        Files.writeString(root.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx256m\norg.gradle.workers.max=1\n")
        val directory = root.resolve("observations")
        var original: UnexpectedBuildFailure? = null
        val thrown = assertFailsWith<UnexpectedBuildFailure> {
            SafeArgsBuildEvidence.build(directory) {
                try {
                    GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                        "expectedFailure", "--offline", "--stacktrace", "--no-build-cache",
                    ).build()
                } catch (failure: UnexpectedBuildFailure) {
                    original = failure
                    throw failure
                }
            }
        }
        assertSame(original, thrown)
        val output = Files.readString(directory.resolve("gradle-output.txt"))
        assertTrue(output.contains("failure-output-sentinel"))
        assertTrue(output.contains("intentional-fixture-failure"))
        assertEquals(":beforeFailure=SUCCESS\n:expectedFailure=FAILED\n", Files.readString(directory.resolve("task-outcomes.txt")))
        assertEquals("failed", metadata(directory).getProperty("buildResult"))
        assertEquals("false", metadata(directory).getProperty("outputTruncated"))
        assertFalse(output.contains("safe-args-native complete="))
    }

    @Test
    fun `결과 저장 실패는 빌드 예외를 대체하지 않는다`(@TempDir root: Path) {
        val occupied = root.resolve("occupied")
        Files.writeString(occupied, "directory creation must fail")
        val original = UnexpectedBuildFailure("original build failure", result("output", emptyList()))
        val thrown = assertFailsWith<UnexpectedBuildFailure> {
            SafeArgsBuildEvidence.build(occupied) { throw original }
        }
        assertSame(original, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertTrue(thrown.suppressed.single() is java.nio.file.FileAlreadyExistsException)
        assertEquals("directory creation must fail", Files.readString(occupied))
    }

    @Test
    fun `최종 복사 실패는 원래 실패와 함께 남고 단독 실패는 전달한다`() {
        val original = IllegalStateException("primary")
        val secondary = IllegalArgumentException("capture")
        val thrown = assertFailsWith<IllegalStateException> {
            SafeArgsBuildEvidence.withFinalCapture({ throw secondary }) { throw original }
        }
        assertSame(original, thrown)
        assertSame(secondary, thrown.suppressed.single())
        assertSame(secondary, assertFailsWith<IllegalArgumentException> {
            SafeArgsBuildEvidence.withFinalCapture({ throw secondary }) { "success" }
        })
    }

    @Test
    fun `출력 surrogate 경계와 task 개수 제한을 잘림으로 표시한다`(@TempDir root: Path) {
        val output = "x".repeat(SafeArgsBuildEvidence.MAX_OUTPUT_CHARS - 1) + "😀suffix"
        val tasks = List(SafeArgsBuildEvidence.MAX_TASKS + 1) { task(":t$it") }
        val buildResult = result(output, tasks)
        assertSame(buildResult, SafeArgsBuildEvidence.build(root) { buildResult })
        assertEquals("x".repeat(SafeArgsBuildEvidence.MAX_OUTPUT_CHARS - 1), Files.readString(root.resolve("gradle-output.txt")))
        val properties = metadata(root)
        assertEquals("true", properties.getProperty("outputTruncated"))
        assertEquals("true", properties.getProperty("taskOutcomesTruncated"))
        assertEquals(SafeArgsBuildEvidence.MAX_TASKS.toString(), properties.getProperty("tasksCaptured"))
        assertEquals("succeeded", properties.getProperty("buildResult"))
        assertFalse(Files.readString(root.resolve("gradle-output.txt")).contains("safe-args-native complete="))
    }

    @Test
    fun `task 문자 예산을 넘긴 행은 부분 저장하지 않는다`(@TempDir root: Path) {
        val first = task(":first")
        SafeArgsBuildEvidence.build(root) {
            result("ok", listOf(first, task(":" + "x".repeat(SafeArgsBuildEvidence.MAX_TASK_CHARS))))
        }
        assertEquals(":first=SUCCESS\n", Files.readString(root.resolve("task-outcomes.txt")))
        assertEquals("true", metadata(root).getProperty("taskOutcomesTruncated"))
        assertEquals("1", metadata(root).getProperty("tasksCaptured"))
    }

    private fun metadata(directory: Path) = Properties().apply {
        Files.newBufferedReader(directory.resolve("gradle-result.properties")).use { load(it) }
    }

    private fun task(path: String) = object : BuildTask {
        override fun getPath() = path
        override fun getOutcome() = TaskOutcome.SUCCESS
    }

    private fun result(output: String, tasks: List<BuildTask>) = object : BuildResult {
        override fun getOutput() = output
        override fun getOutputReader() = BufferedReader(StringReader(output))
        override fun getTasks() = tasks
        override fun tasks(outcome: TaskOutcome) = tasks.filter { it.outcome == outcome }
        override fun taskPaths(outcome: TaskOutcome) = tasks(outcome).map { it.path }
        override fun task(taskPath: String) = tasks.find { it.path == taskPath }
    }
}
