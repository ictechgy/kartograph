package dev.kartograph.gradle

import dev.kartograph.index.KeepRuleScanningException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class KartographDeadTaskTest {
    @Test
    fun `generated input provenance is shared with dead reporting`(@TempDir projectRoot: Path) {
        val task = configuredTask(projectRoot, strict = true)
        task.generatedClassRoots.from(task.projectDirectories.get().map { it.asFile })
        task.analyze()
        assertFalse(projectRoot.resolve("build/reports/kartograph/debug.txt").readText().contains("unreachable\t"))
    }

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
    fun `strict mode writes the report before failing on findings`(@TempDir projectRoot: Path) {        val task = configuredTask(projectRoot, strict = true, retainTestClass = false)

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
    fun `baseline capture ignores an existing baseline`(@TempDir projectRoot: Path) {
        val initialCapture = configuredTask(projectRoot, strict = false, retainTestClass = false)
        initialCapture.baselineWriteFile.set(projectRoot.resolve("existing.json").toFile())
        initialCapture.analyze()
        val capture = configuredTask(projectRoot, strict = true, retainTestClass = false)
        capture.baselineFile.set(projectRoot.resolve("existing.json").toFile())
        capture.baselineWriteFile.set(projectRoot.resolve("captured.json").toFile())

        capture.analyze()

        assertContains(
            projectRoot.resolve("build/reports/kartograph/debug.txt").readText(),
            "unreachable\tclass:dev/kartograph/gradle/PluginUnreachableFixture\t",
        )
        assertEquals(projectRoot.resolve("existing.json").readText(), projectRoot.resolve("captured.json").readText())
    }

    @Test
    fun `plugin creates an extension with non strict default`(@TempDir projectRoot: Path) {
        val project = ProjectBuilder.builder().withProjectDir(projectRoot.toFile()).build()

        project.pluginManager.apply(KartographPlugin::class.java)

        val extension = project.extensions.getByType(KartographExtension::class.java)
        assertEquals(false, extension.strict.get())
        assertEquals(false, extension.includePrivateMembers.get())
        assertEquals("gradle", extension.reportFormat.get())
    }

    @Test
    fun `skips missing generated rule files under the build directory`(@TempDir projectRoot: Path) {
        // AGP 8의 variant.proguardFiles는 minify 전까지 생성되지 않는 default_proguard_files
        // 경로를 넘긴다. 없는 생성물은 건너뛰고 분석을 계속한다.
        val phantom = projectRoot.resolve("build/intermediates/default_proguard_files/global/proguard-android.txt-8.7.3")
        val task = configuredTask(projectRoot, strict = false, extraKeepRules = listOf(phantom))

        task.analyze()

        assertTrue(projectRoot.resolve("build/reports/kartograph/debug.txt").toFile().exists())
    }

    @Test
    fun `still fails on a missing keep rule outside the build directory`(@TempDir projectRoot: Path) {
        // 소스 트리의 누락은 생성물 스킵과 무관하게 기존대로 실패한다.
        val task = configuredTask(projectRoot, strict = false, extraKeepRules = listOf(projectRoot.resolve("missing.pro")))

        assertFailsWith<KeepRuleScanningException> { task.analyze() }
    }

    private fun configuredTask(
        projectRoot: Path,
        strict: Boolean,
        retainTestClass: Boolean = true,
        extraKeepRules: List<Path> = emptyList(),
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
            keepRuleFiles.from(extraKeepRules.map(Path::toFile))
            namespace.set("dev.kartograph.gradle")
            variantName.set("debug")
            this.strict.set(strict)
            reportFormat.set("text")
            projectDirectory.set(project.layout.projectDirectory)
            buildDirectory.set(project.layout.buildDirectory)
            reportFile.set(project.layout.buildDirectory.file("reports/kartograph/debug.txt"))
        }
    }
}

private class PluginNativeEntryFixture {
    external fun invoke(value: Long): Int
}

private class PluginUnreachableFixture
