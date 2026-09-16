package dev.kartograph.gradle

import dev.kartograph.export.QuerySnapshotCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class SnapshotSizeLimitIntegrationTest {
    @Test
    fun `snapshot size default opt in range and configuration cache are wired`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='snapshot-limit'\n")
        val build = root.resolve("build.gradle")
        Files.writeString(build, """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
            kartograph { snapshotsEnabled = true }
            def requestedSnapshotMax = providers.gradleProperty('snapshotMaxMiB')
            if (requestedSnapshotMax.present) {
                kartograph.snapshotMaxMiB = requestedSnapshotMax.map { it.toInteger() }
            }
            def configuredSnapshotMax = extensions.getByName('kartograph').snapshotMaxMiB
            tasks.register('printSnapshotLimit') {
                inputs.property('snapshotMaxMiB', configuredSnapshotMax)
                doLast { println "snapshotMaxMiB=" + inputs.properties['snapshotMaxMiB'] }
            }
        """.trimIndent())
        val sources = Files.createDirectories(root.resolve("src/main/java/p"))
        Files.writeString(sources.resolve("Entry.java"), "package p; public class Entry {}")
        val printDefault = runner(root, "printSnapshotLimit").build()
        assertContains(printDefault.output, "snapshotMaxMiB=64")
        runner(root, "kartographSnapshot").build()
        val snapshotFile = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val defaultSnapshot = Files.readString(snapshotFile)

        runner(root, "kartographSnapshot", "-PsnapshotMaxMiB=1").build()
        val reused = runner(root, "kartographSnapshot", "-PsnapshotMaxMiB=1").build()
        assertContains(reused.output, "Reusing configuration cache")
        assertEquals(defaultSnapshot, Files.readString(snapshotFile))
        QuerySnapshotCodec.parse(Files.readString(snapshotFile), QuerySnapshotCodec.maximumBytes(1))

        val invalid = runner(root, "kartographSnapshot", "-PsnapshotMaxMiB=129").buildAndFail()
        assertContains(invalid.output, "snapshot maximum must be 1..128 MiB")
    }

    private fun runner(root: Path, task: String, vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments(listOf(task, "--offline", "--configuration-cache", "--stacktrace") + arguments)
}
