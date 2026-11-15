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
    // daemon이 스스로 보고한 시작 시각이다. 같은 daemon JVM이 쓴 값끼리만 비교해 빌드 사이 PID 재사용을 잡는다.
    private val identities = linkedMapOf<Long, Instant>()
    // 이 JVM이 직접 잡은 소유 daemon handle이다. 생존 확인·종료 대기·강제 종료는 이 handle로만 한다.
    private val daemons = linkedMapOf<Long, ProcessHandle>()
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
        val previous = identities[pid]
        require(previous == null || previous == start) { "Fixture daemon PID was reused" }
        // Linux JDK는 startInstant를 JVM마다 한 번 읽은 /proc/stat btime으로 계산한다. 그 사이 시계가 보정되면 같은
        // 프로세스도 JVM마다 초 단위로 다르게 보이므로 JVM 사이 시각은 비교하지 않는다. TestKit은 이 JVM에서 daemon을
        // 직접 띄우므로 이 JVM의 살아 있는 자식만 소유 daemon으로 인정하고, 이후 비교는 이 JVM의 handle로만 한다.
        val daemon = ProcessHandle.current().children().filter { it.pid() == pid }.findFirst().orElse(null)
        checkNotNull(daemon) { "Fixture daemon $pid is not a live child of the test JVM" }
        val known = daemons[pid]
        check(known == null || known == daemon) { "Fixture daemon PID was reused" }
        daemons[pid] = daemon
        identities[pid] = start
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
        // isAlive는 이 JVM이 기록한 시작 시각과 비교하므로 PID가 재사용됐으면 false다. 여기서는 실패하지 않아 stop을 막지 않는다.
        val live = daemons.values.filter(ProcessHandle::isAlive)
        val wait = CleanupWait()
        var failure: Throwable? = null
        try {
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
                wait.forExits(live)
            } catch (graceful: Exception) {
                // --stop은 아무 daemon도 멈추지 못해도 0으로 끝날 수 있고 기한은 runner 정지에도 지난다. 어느 경우든 소유가
                // 확인된 daemon을 남긴 채 돌아가지 않는다.
                throw terminate(live, wait, graceful)
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

    /** 정상 stop이 실패하면 소유가 확인된 daemon만 강제 종료하고 종료를 기다린 뒤, 원래 실패를 숨기지 않고 돌려준다. */
    private fun terminate(live: List<ProcessHandle>, wait: CleanupWait, graceful: Exception): Exception {
        val remaining = live.filter(ProcessHandle::isAlive)
        if (remaining.isEmpty()) return graceful
        // ProcessHandle.destroyForcibly는 기록된 시작 시각이 같을 때만 신호를 보낸다.
        remaining.forEach(ProcessHandle::destroyForcibly)
        val reported = IllegalStateException(
            "Fixture daemon ${remaining.map(ProcessHandle::pid)} did not exit after the owned stop; terminated it forcibly",
            graceful,
        )
        try {
            wait.forExits(remaining)
        } catch (forced: Exception) {
            reported.addSuppressed(forced)
        }
        return reported
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

        /** interrupt가 반복되어도 관측 daemon 전체에 같은 종료 기한을 적용한다. */
        fun forExits(processes: List<ProcessHandle>) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DAEMON_EXIT_SECONDS)
            processes.forEach { process ->
                val exit = process.onExit()
                try {
                    until(deadline) { remaining -> exit.get(remaining, TimeUnit.NANOSECONDS) }
                } catch (_: TimeoutException) {
                    throw TimeoutException("Fixture daemon ${process.pid()} did not exit within $DAEMON_EXIT_SECONDS seconds")
                }
            }
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
        private const val DAEMON_EXIT_SECONDS = 10L

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
