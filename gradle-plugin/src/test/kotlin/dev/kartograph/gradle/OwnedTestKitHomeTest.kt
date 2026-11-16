package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.io.TempDir

class OwnedTestKitHomeTest {
    @Test
    fun `owned stop awaits one daemon and leaves a different fixture daemon alive`(@TempDir root: Path) {
        val first = fixture(root, "first")
        val second = fixture(root, "second")
        first.use {
            second.use {
                first.build(runner(root, "first"))
                second.build(runner(root, "second"))
                val firstProcess = process(root, "first")
                val secondProcess = process(root, "second")
                assertTrue(firstProcess.isAlive, "TestKit daemon outlives build()")
                assertTrue(secondProcess.isAlive)
                first.close()
                assertFalse(firstProcess.isAlive, "owned process must exit before JUnit deletes its home")
                assertTrue(secondProcess.isAlive, "another owned registry must not be stopped")
                second.close()
                assertFalse(secondProcess.isAlive)
            }
        }
    }

    @Test
    fun `failed build preserves original failure and still awaits its owned daemon`(@TempDir root: Path) {
        val home = fixture(root, "failed")
        val failure = assertFailsWith<UnexpectedBuildFailure> {
            home.use { it.build(runner(root, "failed", "failAfterIdentity")) }
        }
        assertContains(failure.buildResult.output, "fixture-intended-failure")
        val identity = Files.readAllLines(root.resolve("failed/repository/build/testkit-process.txt"))
        // 정리 실패는 원래 실패의 suppressed로만 남으므로 단언 메시지에 함께 보여 준다.
        assertFalse(ProcessHandle.of(identity[0].toLong()).map(ProcessHandle::isAlive).orElse(false),
            "owned daemon outlived use(); cleanup failures: ${failure.suppressed.toList()}")
    }

    @Test
    fun `owned daemon that acknowledges stop but cannot exit is terminated before close reports the timeout`(@TempDir root: Path) {
        val home = fixture(root, "stuck")
        var daemon: ProcessHandle? = null
        try {
            home.build(runner(root, "stuck", "blockDaemonExit"))
            daemon = process(root, "stuck")
            val failure = runCatching { home.close() }.exceptionOrNull()
            assertFalse(checkNotNull(daemon).isAlive, "close must not give up while the owned daemon is alive: $failure")
            // 강제 종료로 끝냈더라도 정상 stop의 기한 초과는 숨기지 않는다.
            assertIs<IllegalStateException>(failure)
            assertContains(failure.message.orEmpty(), "terminated it forcibly")
            assertIs<TimeoutException>(failure.cause)
            // TempDir와 함께 지워질 stop client 로그의 앞부분을 예외에 남긴다.
            assertContains(failure.message.orEmpty(), "Fixture stop client log (")
            assertContains(failure.message.orEmpty(), "Stopping Daemon")
        } finally {
            // 수정 전 음성 대조 실행에서도 종료 hook에 묶인 daemon을 남기지 않는다.
            daemon?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
        }
    }

    @Test
    fun `identity reported under another JVM clock view still stops and awaits the owned daemon`(@TempDir root: Path) {
        val home = fixture(root, "skewed")
        var daemon: ProcessHandle? = null
        try {
            home.build(runner(root, "skewed", "skewIdentityClock"))
            daemon = process(root, "skewed")
            home.close()
            assertFalse(checkNotNull(daemon).isAlive)
        } finally {
            daemon?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
        }
    }

    @Test
    fun `identity naming a process other than the owned daemon child is never claimed`(@TempDir root: Path) {
        val home = fixture(root, "foreign")
        var daemon: ProcessHandle? = null
        try {
            val failure = assertFailsWith<IllegalStateException> {
                home.build(runner(root, "foreign", "reportParentIdentity"))
            }
            assertContains(failure.message.orEmpty(), "is neither this fixture's known daemon nor a live test JVM child started during this build")
            daemon = process(root, "foreign", "testkit-real-process.txt")
            val cleanup = assertFailsWith<IllegalStateException> { home.close() }
            assertContains(cleanup.message.orEmpty(), "unobserved fixture daemon")
            assertTrue(ProcessHandle.current().isAlive)
            // 소유를 증명하지 못한 daemon도 소유 registry stop은 받는다. 임시 폴더 삭제 전에 종료만 확인한다.
            checkNotNull(daemon).onExit().get(30, TimeUnit.SECONDS)
        } finally {
            daemon?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
        }
    }

    @Test
    fun `a test JVM child that existed before the first build is never claimed or killed`(@TempDir root: Path) {
        val sibling = startSibling(root)
        val home = fixture(root, "sibling")
        var daemon: ProcessHandle? = null
        try {
            val failure = runCatching { home.build(runner(root, "sibling", "reportSiblingIdentity", "-PforeignPid=${sibling.pid()}")) }
                .exceptionOrNull()
            daemon = process(root, "sibling", "testkit-real-process.txt")
            assertIs<IllegalStateException>(failure, "a pre-existing test JVM child must not be accepted as the owned daemon")
            assertContains(failure.message.orEmpty(), "is neither this fixture's known daemon nor a live test JVM child started during this build")
            val cleanup = assertFailsWith<IllegalStateException> { home.close() }
            assertContains(cleanup.message.orEmpty(), "unobserved fixture daemon")
            assertTrue(sibling.isAlive, "cleanup must not stop or kill a process the fixture does not own")
            checkNotNull(daemon).onExit().get(30, TimeUnit.SECONDS)
        } finally {
            daemon?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            sibling.destroyForcibly()
            sibling.waitFor(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `another fixture daemon started between builds is never claimed or killed`(@TempDir root: Path) {
        val first = fixture(root, "first-owner")
        val second = fixture(root, "second-owner")
        var firstDaemon: ProcessHandle? = null
        second.use {
            try {
                first.build(runner(root, "first-owner"))
                firstDaemon = process(root, "first-owner")
                second.build(runner(root, "second-owner"))
                val secondDaemon = process(root, "second-owner")
                // 두 번째 fixture의 daemon은 첫 fixture의 첫 빌드 뒤에 생겼지만 이번 빌드 전부터 있었다.
                val failure = runCatching {
                    first.build(runner(root, "first-owner", "reportSiblingIdentity", "-PforeignPid=${secondDaemon.pid()}"))
                }.exceptionOrNull()
                assertIs<IllegalStateException>(failure, "another fixture's daemon must not be accepted as owned")
                assertContains(failure.message.orEmpty(), "is neither this fixture's known daemon nor a live test JVM child started during this build")
                val cleanup = assertFailsWith<IllegalStateException> { first.close() }
                assertContains(cleanup.message.orEmpty(), "unobserved fixture daemon")
                assertFalse(checkNotNull(firstDaemon).isAlive, "the verified owned daemon is still stopped and awaited")
                assertTrue(secondDaemon.isAlive, "cleanup must not stop or kill another fixture's daemon")
            } finally {
                firstDaemon?.takeIf(ProcessHandle::isAlive)?.destroyForcibly()
            }
        }
    }

    @Test
    fun `configuration failure does not reuse a prior identity or hide unobserved cleanup`(@TempDir root: Path) {
        val home = fixture(root, "early-failure")
        var priorProcess: ProcessHandle? = null
        val failure = assertFailsWith<UnexpectedBuildFailure> {
            home.use {
                it.build(runner(root, "early-failure"))
                priorProcess = process(root, "early-failure")
                root.resolve("early-failure/repository/build.gradle")
                    .writeText("throw new GradleException('fixture-before-task-failure')\n")
                it.build(runner(root, "early-failure"))
            }
        }
        assertContains(failure.buildResult.output, "fixture-before-task-failure")
        assertFalse(Files.exists(root.resolve("early-failure/repository/build/testkit-process.txt")))
        assertTrue(failure.suppressed.any { it is java.nio.file.NoSuchFileException })
        assertTrue(failure.suppressed.any { it.message.orEmpty().contains("unobserved fixture daemon") })
        assertFalse(checkNotNull(priorProcess).isAlive)
    }

    @Test
    fun `interrupted cleanup preserves original failure and flag after owned daemon exits`(@TempDir root: Path) {
        val home = fixture(root, "interrupted")
        try {
            home.build(runner(root, "interrupted"))
            val daemon = process(root, "interrupted")
            val failure = assertFailsWith<IllegalStateException> {
                home.use {
                    Thread.currentThread().interrupt()
                    error("fixture-original-failure")
                }
            }
            assertContains(failure.message.orEmpty(), "fixture-original-failure")
            assertTrue(Thread.currentThread().isInterrupted, "cleanup must restore cancellation state")
            assertTrue(failure.suppressed.any { it is InterruptedException })
            assertFalse(daemon.isAlive, "interruption must not bypass owned daemon exit")
        } finally {
            // 음성 대조 실행에서도 JUnit thread와 fixture daemon을 남기지 않는다.
            Thread.interrupted()
            home.close()
        }
    }

    @Test
    fun `scope rejects an outside home before creating or stopping anything`(@TempDir root: Path) {
        val project = Files.createDirectory(root.resolve("repository"))
        val outside = root.resolveSibling(root.fileName.toString() + "-outside")
        assertFailsWith<IllegalArgumentException> { OwnedTestKitHome(root, outside, project) }
        assertFalse(Files.exists(outside))
    }

    private fun fixture(root: Path, name: String): OwnedTestKitHome {
        val project = Files.createDirectories(root.resolve("$name/repository"))
        project.resolve("settings.gradle").writeText("rootProject.name='owned-testkit-fixture'\n")
        project.resolve("build.gradle").writeText(OwnedTestKitHome.identityTask + "\n" + """
            tasks.register('failAfterIdentity') {
                dependsOn '${OwnedTestKitHome.IDENTITY_TASK}'
                doLast { throw new GradleException('fixture-intended-failure') }
            }
            // stop 요청은 받지만 JVM 종료 hook에서 멈춰 끝나지 않는 daemon이다.
            tasks.register('blockDaemonExit') {
                doLast { Runtime.runtime.addShutdownHook(new Thread({ while (true) { Thread.sleep(60000) } })) }
            }
            // Linux JDK는 startInstant를 JVM마다 따로 읽은 btime으로 계산한다. 1초 시계 보정 뒤 시작한 JVM이 보는 값이다.
            def identityFile = layout.buildDirectory.file('testkit-process.txt').get().asFile
            def realIdentityFile = layout.buildDirectory.file('testkit-real-process.txt').get().asFile
            tasks.register('skewIdentityClock') {
                dependsOn '${OwnedTestKitHome.IDENTITY_TASK}'
                doLast {
                    def lines = identityFile.readLines()
                    identityFile.text = lines[0] + '\n' + java.time.Instant.parse(lines[1]).plusSeconds(1).toString() + '\n'
                }
            }
            // 테스트가 첫 빌드 전에 띄운 테스트 JVM의 다른 자식을 신원으로 보고한다.
            def foreignPid = providers.gradleProperty('foreignPid')
            tasks.register('reportSiblingIdentity') {
                dependsOn '${OwnedTestKitHome.IDENTITY_TASK}'
                doLast {
                    realIdentityFile.text = identityFile.text
                    def sibling = ProcessHandle.of(foreignPid.get().toLong()).orElseThrow()
                    identityFile.text = sibling.pid().toString() + '\n' + sibling.info().startInstant().orElseThrow().toString() + '\n'
                }
            }
            // 소유 daemon이 아닌 프로세스(테스트 JVM)를 신원으로 보고한다. 실제 신원은 정리 확인용으로 따로 둔다.
            tasks.register('reportParentIdentity') {
                dependsOn '${OwnedTestKitHome.IDENTITY_TASK}'
                doLast {
                    realIdentityFile.text = identityFile.text
                    def parent = ProcessHandle.current().parent().orElseThrow()
                    identityFile.text = parent.pid().toString() + '\n' + parent.info().startInstant().orElseThrow().toString() + '\n'
                }
            }
        """.trimIndent())
        return OwnedTestKitHome(root, root.resolve("$name/gradle-home"), project)
    }

    private fun runner(root: Path, name: String, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.resolve("$name/repository").toFile())
        .withArguments(listOf(OwnedTestKitHome.IDENTITY_TASK) + tasks + listOf("--offline", "--configuration-cache"))

    /** 테스트 JVM이 직접 띄운, fixture와 무관한 오래 사는 자식 프로세스다(JDK 단일 파일 실행). */
    private fun startSibling(root: Path): Process {
        val source = Files.createDirectories(root.resolve("sibling-source")).resolve("Sibling.java")
        source.writeText("public class Sibling { public static void main(String[] a) throws Exception { Thread.sleep(300_000); } }\n")
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        return ProcessBuilder(java.toString(), source.toString()).redirectErrorStream(true)
            .redirectOutput(root.resolve("sibling.log").toFile()).start()
    }

    private fun process(root: Path, name: String, file: String = "testkit-process.txt"): ProcessHandle {
        val identity = Files.readAllLines(root.resolve("$name/repository/build/$file"))
        return ProcessHandle.of(identity[0].toLong()).orElseThrow()
    }
}
