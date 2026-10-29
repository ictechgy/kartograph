package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion

/** 메서드 임시 폴더보다 오래 사는 TestKit daemon을 해당 fixture 소유 범위에서 먼저 종료한다. */
internal class OwnedTestKitHome(private val root: Path, val home: Path, private val project: Path) : AutoCloseable {
    private val identities = linkedMapOf<Long, Instant>()
    private var used = false
    private var closed = false
    private var unobservedAttempt = false

    init {
        require(home.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        Files.createDirectories(home)
        require(home.toRealPath().startsWith(root.toRealPath()) && home.toRealPath() != root.toRealPath())
        require(project.toRealPath().startsWith(root.toRealPath()))
    }

    fun build(runner: GradleRunner, expectedFailure: Boolean = false): BuildResult {
        check(!closed)
        // 이전 실행의 신원을 다음 실행 실패의 증거로 재사용하지 않는다.
        Files.deleteIfExists(identityFile())
        used = true
        // use는 build 예외를 원래 실패로 남기고 신원 기록 오류를 suppressed로 보존한다.
        return AutoCloseable {
            try {
                recordIdentity()
            } catch (failure: Exception) {
                unobservedAttempt = true
                throw failure
            }
        }.use {
            val scoped = runner.withTestKitDir(home.toFile()).withGradleInstallation(distribution().toFile())
            if (expectedFailure) scoped.buildAndFail() else scoped.build()
        }
    }

    private fun recordIdentity() {
        val file = identityFile()
        require(file.toRealPath().startsWith(root.toRealPath()))
        require(Files.size(file) <= 256) { "Fixture daemon identity exceeds its bound" }
        val values = Files.readAllLines(file)
        require(values.size == 2) { "Fixture daemon identity is incomplete" }
        val pid = values[0].toLong()
        val start = Instant.parse(values[1])
        require(pid > 0)
        val previous = identities.putIfAbsent(pid, start)
        require(previous == null || previous == start) { "Fixture daemon PID was reused" }
    }

    private fun identityFile(): Path {
        val file = project.resolve("build/testkit-process.txt")
        if (Files.exists(file.parent)) require(file.parent.toRealPath().startsWith(root.toRealPath()))
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(file)) { "Fixture daemon identity must not be a symbolic link" }
            require(file.toRealPath().startsWith(root.toRealPath()))
        }
        return file
    }

    private fun distribution(): Path = Path.of(checkNotNull(System.getProperty("kartograph.test.gradleHome")) {
        "The test task must provide its Gradle installation"
    })

    override fun close() {
        if (closed || !used) return
        val ownedHome = home.toRealPath()
        require(ownedHome.startsWith(root.toRealPath()) && ownedHome != root.toRealPath())
        val live = identities.mapNotNull { (pid, start) ->
            ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }?.also {
                check(it.info().startInstant().orElse(null) == start) { "Fixture daemon PID identity changed" }
            }
        }
        val wait = CleanupWait()
        var failure: Throwable? = null
        try {
            // TestKit은 일반 daemon 디렉터리와 별도의 registry를 쓴다. 전역 --stop은 호출하지 않는다.
            val launcher = distribution().resolve("lib/gradle-gradle-cli-main-${GradleVersion.current().version}.jar")
            check(Files.isRegularFile(launcher)) { "The fixture Gradle CLI installation is unavailable" }
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val java = Path.of(System.getProperty("java.home"), "bin", executable)
            val process = ProcessBuilder(java.toString(), "-cp", launcher.toString(), "org.gradle.launcher.GradleMain",
                "--stop", "--gradle-user-home", ownedHome.toString(),
                "-Dorg.gradle.daemon.registry.base=${ownedHome.resolve("test-kit-daemon")}")
                .redirectErrorStream(true).redirectOutput(home.parent.resolve("testkit-stop.log").toFile()).start()
            if (!wait.forProcess(process, 40)) {
                process.destroyForcibly()
                check(wait.forProcess(process, 5)) { "Fixture stop client did not terminate" }
                error("Fixture stop client timed out")
            }
            check(process.exitValue() == 0) { "Fixture stop client failed: ${process.exitValue()}" }
            // interrupt가 반복되어도 관측 daemon 전체에 같은 종료 기한을 적용한다.
            val daemonDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            live.forEach { processHandle ->
                val exit = processHandle.onExit()
                wait.until(daemonDeadline) { remaining -> exit.get(remaining, TimeUnit.NANOSECONDS) }
            }
            check(!unobservedAttempt && identities.isNotEmpty()) {
                "Stopped the owned registry but cannot prove every unobserved fixture daemon exited"
            }
            closed = true
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            wait.restoreAfter(failure)
        }
    }

    /** 취소를 잊지 않되, 소유 프로세스 정리는 interrupt마다 늘어나지 않는 기한 안에서 끝낸다. */
    private class CleanupWait {
        private var interruption = if (Thread.interrupted()) InterruptedException("Interrupted before fixture cleanup") else null

        fun <T> until(deadline: Long, action: (Long) -> T): T {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException("Fixture cleanup deadline expired")
                try {
                    return action(remaining)
                } catch (caught: InterruptedException) {
                    if (interruption == null) interruption = caught
                }
            }
        }

        fun forProcess(process: Process, seconds: Long): Boolean = try {
            until(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)) { process.waitFor(it, TimeUnit.NANOSECONDS) }
        } catch (_: TimeoutException) {
            false
        }

        fun restoreAfter(failure: Throwable?) {
            interruption?.let {
                Thread.currentThread().interrupt()
                if (failure == null) throw it
                failure.addSuppressed(it)
            }
        }
    }

    companion object {
        const val IDENTITY_TASK = "recordTestKitProcess"

        /** configuration cache에 저장 가능한 File 값만 action이 캡처한다. 분석 task의 입력은 바꾸지 않는다. */
        val identityTask = """
            def testKitIdentity = layout.buildDirectory.file('testkit-process.txt').get().asFile
            tasks.register('$IDENTITY_TASK') {
                doLast {
                    testKitIdentity.parentFile.mkdirs()
                    def handle = ProcessHandle.current()
                    testKitIdentity.text = handle.pid().toString() + '\n' + handle.info().startInstant().orElseThrow().toString() + '\n'
                }
            }
        """.trimIndent()
    }
}
