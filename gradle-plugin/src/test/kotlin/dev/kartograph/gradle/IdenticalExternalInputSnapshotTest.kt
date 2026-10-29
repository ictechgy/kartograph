package dev.kartograph.gradle

import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * project 밖의 같은 바이트 JAR 두 개를 compiler classpath로 받는 capture를 검증한다.
 *
 * AndroidX KMP 분할 artifact는 항목 없는 stub JAR를 여러 transform 위치에 만든다. 이전에는 witness의 두 입력이 모두
 * 두 후보와 일치해 "compiler input binding is missing or ambiguous"로 capture가 실패했다.
 */
class IdenticalExternalInputSnapshotTest {
    @Test
    fun `identical external jars bind deterministically and the snapshot stays verifiable`(@TempDir root: Path) {
        val project = Files.createDirectories(root.resolve("project"))
        val first = stubJar(root.resolve("libs/a/stub.jar"))
        val second = stubJar(root.resolve("libs/b/stub.jar"))
        assertTrue(Files.readAllBytes(first).contentEquals(Files.readAllBytes(second)))
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'identical-inputs'\n")
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
            dependencies { implementation files('../libs/a/stub.jar', '../libs/b/stub.jar') }
            kartograph { snapshotsEnabled = true }
            tasks.named('test') { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        Files.createDirectories(project.resolve("src/main/java/p"))
        Files.writeString(project.resolve("src/main/java/p/Entry.java"), "package p; public class Entry { public int value() { return 1; } }")

        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--stacktrace").build()

        val snapshot = QuerySnapshotCodec.parse(Files.readString(project.resolve("build/reports/kartograph/jvm-snapshot.json")))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(project.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        val slots = snapshot.provenance!!.witnesses.single { it.artifact == ":compileJava" }.inputs
            .filter { it.role == "classpath" && it.path.startsWith("external/") }
        assertEquals(2, slots.size)
        // 같은 내용의 두 입력은 서로 다른 파일에 짝지어진다.
        assertEquals(setOf(first.toRealPath(), second.toRealPath()), slots.map { bindings.getValue(it.path).toRealPath() }.toSet())
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, project, snapshot.scope, bindings).status)
    }

    /** 항목 하나만 있는 결정적 JAR를 만든다. 같은 입력이면 바이트가 같다. */
    private fun stubJar(path: Path): Path {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/stub.txt").apply { time = 0 })
            jar.write("stub".toByteArray())
            jar.closeEntry()
        }
        return path
    }
}
