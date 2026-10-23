package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.UnexpectedBuildFailure

/** TestKit이 이미 반환한 결과의 저장량만 제한한다. 빌드 메모리나 실행 시간 제한은 아니다. */
internal object SafeArgsBuildEvidence {
    internal const val MAX_OUTPUT_CHARS = 1_048_576
    internal const val MAX_TASK_CHARS = 262_144
    internal const val MAX_TASKS = 10_000

    fun build(directory: Path, run: () -> BuildResult): BuildResult {
        val result = try {
            run()
        } catch (failure: UnexpectedBuildFailure) {
            captureSecondary(failure, "failed-build-result") { save(directory, failure.buildResult, "failed") }
            throw failure
        }
        save(directory, result, "succeeded")
        return result
    }

    /** 원래 실패를 유지하며 증거 복사 실패는 suppressed 예외와 테스트 stderr에 남긴다. */
    fun <T> withFinalCapture(capture: () -> Unit, run: () -> T): T {
        var primary: Throwable? = null
        try {
            return run()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (primary == null) capture() else captureSecondary(primary, "fixture-evidence", capture)
        }
    }

    private fun captureSecondary(primary: Throwable, stage: String, capture: () -> Unit) {
        try {
            capture()
        } catch (secondary: Throwable) {
            if (secondary !== primary) primary.addSuppressed(secondary)
            val detail = (secondary.message ?: "no message").take(2_048).replace('\n', ' ').replace('\r', ' ')
            System.err.println("safe-args-evidence capture-failed stage=$stage type=${secondary.javaClass.name} message=$detail")
        }
    }

    private fun save(directory: Path, result: BuildResult, outcome: String) {
        Files.createDirectories(directory)
        val output = result.output
        var length = minOf(output.length, MAX_OUTPUT_CHARS)
        // UTF-16 surrogate 쌍을 중간에서 자르면 UTF-8 파일 쓰기까지 실패할 수 있다.
        if (length < output.length && length > 0 && output[length - 1].isHighSurrogate()) length--
        Files.writeString(directory.resolve("gradle-output.txt"), output.substring(0, length))
        val tasks = result.tasks
        val rows = StringBuilder()
        var capturedTasks = 0
        for (task in tasks.take(MAX_TASKS)) {
            val row = "${task.path}=${task.outcome}\n"
            if (rows.length + row.length > MAX_TASK_CHARS) break
            rows.append(row)
            capturedTasks++
        }
        Files.writeString(directory.resolve("task-outcomes.txt"), rows)
        // 이 상태는 Gradle 결과만 설명한다. native 관측/검증 완료 표식으로 사용하지 않는다.
        Files.writeString(directory.resolve("gradle-result.properties"), """
            buildResult=$outcome
            outputOriginalUtf16Units=${output.length}
            outputCapturedUtf16Units=$length
            outputTruncated=${length != output.length}
            tasksOriginal=${tasks.size}
            tasksCaptured=$capturedTasks
            taskOutcomesTruncated=${capturedTasks != tasks.size}
        """.trimIndent() + "\n")
    }
}
