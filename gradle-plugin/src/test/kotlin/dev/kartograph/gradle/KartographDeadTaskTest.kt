package dev.kartograph.gradle

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class KartographDeadTaskTest {
    @Test
    fun `writes a report from configured variant inputs`(@TempDir projectRoot: Path) {
        val task = configuredTask(projectRoot, strict = false)

        task.analyze()

        val report = projectRoot.resolve("build/reports/kartograph/debug.txt").readText()
        assertFalse(report.contains("class:dev/kartograph/gradle/KartographDeadTaskTest\t"))
    }

    @Test
    fun `uses runtime entry point retention for variant reports`(@TempDir projectRoot: Path) {
        val task = configuredTask(projectRoot, strict = false)

        task.analyze()

        val report = projectRoot.resolve("build/reports/kartograph/debug.txt").readText()
        assertFalse(report.contains("class:dev/kartograph/gradle/PluginNativeEntryFixture\t"))
        assertContains(report, "unreachable\tclass:dev/kartograph/gradle/PluginUnreachableFixture\t")
        assertContains(report, "limitation\tREFLECTION_STRINGS\t")
        assertContains(report, "limitation\tDYNAMIC_REGISTRATION\t")
    }

    @Test
    fun `strict mode writes the report before failing on findings`(@TempDir projectRoot: Path) {
        val task = configuredTask(projectRoot, strict = true, retainTestClass = false)

        val error = assertFailsWith<GradleException> { task.analyze() }

        assertContains(error.message.orEmpty(), "unreachable declarations")
        assertContains(projectRoot.resolve("build/reports/kartograph/debug.txt").readText(), "unreachable\tclass:")
        assertFalse(error.message.orEmpty().contains(projectRoot.toString()))
    }

    @Test
    fun `baseline makes strict mode safe for existing findings`(@TempDir projectRoot: Path) {
        val baseline = projectRoot.resolve("baseline.json")
        val capture = configuredTask(projectRoot, strict = false, retainTestClass = false)
        capture.baselineWriteFile.set(projectRoot.resolve("captured.json").toFile())
        capture.analyze()
        baseline.writeText(projectRoot.resolve("captured.json").readText())
        val strict = configuredTask(projectRoot, strict = true, retainTestClass = false)
        strict.baselineFile.set(baseline.toFile())

        strict.analyze()

        val report = projectRoot.resolve("build/reports/kartograph/debug.txt").readText()
        assertFalse(report.contains("unreachable\t"))
        assertContains(report, "limitation\tREFLECTION_STRINGS")
    }

    @Test
    fun `plugin creates an extension with non strict default`(@TempDir projectRoot: Path) {
        val project = ProjectBuilder.builder().withProjectDir(projectRoot.toFile()).build()

        project.pluginManager.apply(KartographPlugin::class.java)

        val extension = project.extensions.getByType(KartographExtension::class.java)
        assertEquals(false, extension.strict.get())
        assertEquals("gradle", extension.reportFormat.get())
    }

    private fun configuredTask(
        projectRoot: Path,
        strict: Boolean,
        retainTestClass: Boolean = true,
    ): KartographDeadTask {
        val project = ProjectBuilder.builder().withProjectDir(projectRoot.toFile()).build()
        val manifest = projectRoot.resolve("AndroidManifest.xml")
        val activity = if (retainTestClass) {
            "<activity android:name=\"dev.kartograph.gradle.KartographDeadTaskTest\" />"
        } else {
            ""
        }
        manifest.writeText(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <application>$activity</application>
                </manifest>
            """.trimIndent(),
        )
        val resources = projectRoot.resolve("res").createDirectories()
        val testClasses = Path.of(requireNotNull(javaClass.protectionDomain.codeSource).location.toURI())
        return project.tasks.register("kartographDeadDebug", KartographDeadTask::class.java).get().apply {
            projectJars.set(emptyList())
            projectDirectories.set(listOf(project.layout.dir(project.provider { testClasses.toFile() }).get()))
            classpathJars.set(emptyList())
            classpathDirectories.set(emptyList())
            this.manifest.set(project.layout.file(project.provider { manifest.toFile() }))
            resourceDirectories.from(resources)
            keepRuleFiles.from(emptyList<Any>())
            namespace.set("dev.kartograph.gradle")
            variantName.set("debug")
            this.strict.set(strict)
            reportFormat.set("text")
            projectDirectory.set(project.layout.projectDirectory)
            reportFile.set(project.layout.buildDirectory.file("reports/kartograph/debug.txt"))
        }
    }
}

private class PluginNativeEntryFixture {
    external fun invoke(value: Long): Int
}

private class PluginUnreachableFixture
