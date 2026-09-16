package dev.kartograph.gradle

import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class SnapshotIndexCacheIntegrationTest {
    @Test
    fun `local parse cache preserves current compiler proof and configuration cache`(@TempDir root: Path) {
        verifyCache(root, false)
    }

    @Test
    fun `instrumented snapshot cache retains full capture semantics`(@TempDir root: Path) {
        verifyCache(root, true)
    }

    private fun verifyCache(root: Path, inProcess: Boolean) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'snapshot-cache-fixture'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
            kartograph { snapshotsEnabled = true; includeSourcePaths = true }
            tasks.named('test') { doFirst { throw new GradleException('snapshot must not execute tests') } }
        """.trimIndent())
        val source = Files.createDirectories(root.resolve("src/main/java/p")).resolve("Target.java")
        Files.writeString(source, "package p; public class Target { public int value() { return 1; } }")
        val tests = Files.createDirectories(root.resolve("src/test/java/p"))
        Files.writeString(tests.resolve("TargetCheck.java"), "package p; public class TargetCheck { public int check() { return new Target().value(); } }")
        fun runner(enabled: String) = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withDebug(inProcess).withArguments(listOf("kartographSnapshot", "--offline", "--info", "--stacktrace",
                "-Pkartograph.indexCache=$enabled") + if (inProcess) emptyList() else listOf("--configuration-cache"))
        fun counts(result: BuildResult): Pair<Int, Int> {
            val match = Regex("kartograph index: classes=2 hits=(\\d+) parsed=(\\d+)").find(result.output)
            assertTrue(match != null, result.output)
            return match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }
        val snapshotFile = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val cache = root.resolve("build/kartograph/index-cache")
        val full = runner("false").build()
        assertEquals(TaskOutcome.SUCCESS, full.task(":kartographSnapshot")!!.outcome)
        val oracle = Files.readString(snapshotFile)
        assertFalse(Files.exists(cache))
        val cold = runner("true").build()
        assertEquals(0 to 2, counts(cold))
        assertEquals(oracle, Files.readString(snapshotFile))
        val warm = runner("true").build()
        assertEquals(2 to 0, counts(warm))
        assertEquals(oracle, Files.readString(snapshotFile))
        assertEquals(TaskOutcome.SUCCESS, warm.task(":kartographSnapshot")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, warm.task(":compileJava")!!.outcome)
        assertEquals(null, warm.task(":test"))
        if (!inProcess) assertTrue(warm.output.contains("Configuration cache entry reused"))

        Files.writeString(source, Files.readString(source).replace("return 1", "return 2"))
        val changed = runner("true").build()
        assertEquals(1 to 1, counts(changed))
        val updated = Files.readString(snapshotFile)
        runner("false").build()
        assertEquals(updated, Files.readString(snapshotFile))
        val snapshot = QuerySnapshotCodec.parse(updated)
        val bindingFile = root.resolve("build/kartograph/jvm-input-bindings.json")
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(bindingFile)).mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)

        val invalidOption = runner("invalid-option").buildAndFail()
        assertTrue(invalidOption.output.contains("kartograph.indexCache must be true or false"), invalidOption.output)
        Files.writeString(source, "invalid Java source")
        val failed = runner("true").buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":compileJava")!!.outcome)
        assertTrue(ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status != "matched")
    }
}
