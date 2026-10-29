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
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class JvmProjectClasspathSnapshotTest {
    @Test fun `ordinary main compilation does not resolve unavailable test dependencies`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='main-only'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            repositories { maven { url = uri('empty-repository') } }
            dependencies { testImplementation 'example.unavailable:tests:1.0' }
            kartograph { snapshotsEnabled = true }
        """.trimIndent())
        val source = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(source.resolve("Entry.java"), "public class Entry {}")
        val result = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("compileJava", "--offline", "--configuration-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")!!.outcome)
        assertTrue(Files.isRegularFile(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
    }

    @Test fun `missing project class directories are watched with configuration cache`(@TempDir root: Path) {
        scenario(root, false)
    }

    @Test fun `instrumented project class directory watches retain missing library failure`(@TempDir root: Path) {
        scenario(root, true)
    }

    private fun scenario(root: Path, instrumented: Boolean) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='project-classpath'\ninclude 'app', 'support', 'other'\n")
        Files.createDirectories(root.resolve("app/src/main/java/p"))
        Files.createDirectories(root.resolve("support"))
        Files.createDirectories(root.resolve("other"))
        Files.writeString(root.resolve("app/src/main/java/p/Entry.java"), "package p; public class Entry {}")
        val build = root.resolve("build.gradle")
        Files.writeString(build, """
            plugins { id 'io.github.ictechgy.kartograph' apply false }
            subprojects { apply plugin: 'java-library' }
            project(':app') {
                apply plugin: 'io.github.ictechgy.kartograph'
                dependencies { implementation project(':support'); implementation project(':other') }
                kartograph { snapshotsEnabled = true }
            }
        """.trimIndent())
        fun runner() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withDebug(instrumented)
            .withArguments(listOf(":app:kartographSnapshot", "--offline", "--stacktrace") +
                if (instrumented) emptyList() else listOf("--configuration-cache"))
        val result = runner().build()
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":support:compileJava")!!.outcome)
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":other:compileJava")!!.outcome)
        val missingOutput = root.resolve("support/build/classes/java/main").toFile().canonicalFile.toPath()
        assertFalse(Files.exists(missingOutput))
        val project = root.resolve("app")
        val snapshot = QuerySnapshotCodec.parse(Files.readString(project.resolve("build/reports/kartograph/jvm-snapshot.json")))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(project.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        val provenance = requireNotNull(snapshot.provenance)
        assertEquals("matched", ProvenanceVerifier.verify(provenance, project, snapshot.scope, bindings).status)
        assertTrue(provenance.witnesses.flatMap { it.inputs }.any {
            it.role == "directory-watch" && bindings[it.path] == missingOutput
        })
        assertEquals(2, provenance.witnesses.flatMap { it.inputs }.filter {
            it.role == "directory-watch" && it.path.startsWith("external/")
        }.map { it.path }.distinct().size)
        val again = runner().build()
        if (!instrumented) assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":app:compileJava")!!.outcome)

        // 라이브러리 출력이 나중에 생기면 부재를 기록한 compiler 입력은 stale이다.
        Files.createDirectories(missingOutput)
        val added = missingOutput.resolve("Changed.class")
        Files.write(added, byteArrayOf(1, 2, 3))
        assertEquals("stale", ProvenanceVerifier.verify(provenance, project, snapshot.scope, bindings).status)
        Files.delete(added)

        // 임의 파일 의존성의 누락을 Gradle 프로젝트의 선언된 class 출력으로 취급하지 않는다.
        Files.writeString(build, Files.readString(build) + "\nproject(':app') { dependencies { implementation files('missing-directory') } }\n")
        val failure = runner().buildAndFail()
        assertTrue(failure.output.contains("fingerprint input is missing"), failure.output)
        assertFalse(Files.exists(project.resolve("build/kartograph/witnesses/compileJava/witness.json")))
    }
}
