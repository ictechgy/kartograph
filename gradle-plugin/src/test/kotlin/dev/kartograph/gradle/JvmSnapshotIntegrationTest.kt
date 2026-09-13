package dev.kartograph.gradle

import dev.kartograph.core.NodeId
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.export.ExternalInputBindingsCodec
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

class JvmSnapshotIntegrationTest {
    @Test
    fun `Java main and test snapshot follows configured compiler outputs without running tests`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'snapshot-consumer'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins {
                id 'java'
                id 'io.github.ictechgy.kartograph'
            }
            java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
            kartograph {
                snapshotsEnabled = false
                includeSourcePaths = true
                snapshotRevision = '${"a".repeat(40)}'
            }
            sourceSets.main.java.destinationDirectory = layout.buildDirectory.dir('custom/main')
            sourceSets.test.java.destinationDirectory = layout.buildDirectory.dir('custom/test')
            tasks.named('test') { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        val main = Files.createDirectories(root.resolve("src/main/java/p"))
        val tests = Files.createDirectories(root.resolve("src/test/java/p"))
        Files.writeString(main.resolve("Target.java"), "package p; public class Target { public static void run() {} }")
        Files.writeString(tests.resolve("TargetCheck.java"), "package p; public class TargetCheck { public void check() { Target.run(); } }")

        val ordinary = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("testClasses", "--offline", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, ordinary.task(":compileTestJava")!!.outcome)
        assertTrue(Files.isRegularFile(root.resolve("build/custom/main/p/Target.class")))
        val buildFile = root.resolve("build.gradle")
        Files.writeString(buildFile, Files.readString(buildFile).replace("snapshotsEnabled = false", "snapshotsEnabled = true"))

        fun build() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace").build()
        val first = build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJava")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileTestJava")!!.outcome)
        assertEquals(null, first.task(":test"))
        val file = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val text = Files.readString(file)
        val snapshot = QuerySnapshotCodec.parse(text)
        assertEquals("a".repeat(40), snapshot.revision)
        assertTrue(NodeId("class:p/Target") in snapshot.graph.nodes)
        assertTrue(NodeId("class:p/TargetCheck") in snapshot.graph.nodes)
        assertEquals("src/test/java/p/TargetCheck.java", snapshot.graph.nodes.getValue(NodeId("class:p/TargetCheck")).location?.path)
        assertEquals(2, snapshot.provenance!!.witnesses.size)
        assertEquals(setOf("build/custom/main", "build/custom/test"), snapshot.provenance!!.inputs.filter { it.role == "classes" }.map { it.path }.toSet())
        val external = (snapshot.provenance!!.inputs + snapshot.provenance!!.witnesses.flatMap { it.inputs + it.outputs })
            .filter { it.path.startsWith("external/") }
        assertTrue(external.all { it.role == "compiler" })
        val localFile = root.resolve("build/kartograph/jvm-input-bindings.json")
        val localBindings = ExternalInputBindingsCodec.parse(Files.readString(localFile)).mapValues { Path.of(it.value) }
        assertEquals(external.map { it.path }.toSet(), localBindings.keys)
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope,
            localBindings).status)
        assertFalse(text.contains(root.toString()))
        assertTrue(Files.isRegularFile(localFile))

        val again = build()
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileTestJava")!!.outcome)
        assertEquals(text, Files.readString(file))
    }

    @Test
    fun `missing declared library is not treated as an optional resource output`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'missing-library'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph { snapshotsEnabled = true }
            dependencies { implementation files('missing-library.jar') }
        """.trimIndent())
        val source = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(source.resolve("Entry.java"), "public class Entry {}")
        val result = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--stacktrace").buildAndFail()
        assertTrue(result.output.contains("fingerprint input is missing"), result.output)
        assertFalse(Files.exists(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
    }
}
