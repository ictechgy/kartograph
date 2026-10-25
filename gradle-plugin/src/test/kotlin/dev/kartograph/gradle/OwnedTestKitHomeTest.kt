package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertFalse(ProcessHandle.of(identity[0].toLong()).map(ProcessHandle::isAlive).orElse(false))
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
        """.trimIndent())
        return OwnedTestKitHome(root, root.resolve("$name/gradle-home"), project)
    }

    private fun runner(root: Path, name: String, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root.resolve("$name/repository").toFile())
        .withArguments(listOf(OwnedTestKitHome.IDENTITY_TASK) + tasks + listOf("--offline", "--configuration-cache"))

    private fun process(root: Path, name: String): ProcessHandle {
        val identity = Files.readAllLines(root.resolve("$name/repository/build/testkit-process.txt"))
        return ProcessHandle.of(identity[0].toLong()).orElseThrow()
    }
}
