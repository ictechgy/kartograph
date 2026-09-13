package dev.kartograph.gradle

import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
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

class JvmSnapshotLifecycleIntegrationTest {
    @Test
    fun `empty witness output history cannot keep compilation up to date`(@TempDir root: Path) {
        fixture(root)
        val build = root.resolve("build.gradle")
        Files.writeString(build, Files.readString(build) + """

            def eraseWitness = layout.projectDirectory.file('erase-witness')
            def witnessOutput = layout.buildDirectory.file('kartograph/witnesses/compileJava/witness.json')
            afterEvaluate {
                tasks.named('compileJava') {
                    doLast { if (eraseWitness.asFile.exists()) witnessOutput.get().asFile.delete() }
                }
            }
        """.trimIndent())
        val marker = root.resolve("erase-witness")
        Files.writeString(marker, "simulate a witness removed before output history is recorded")
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("compileJava", "--offline", "--configuration-cache", "--stacktrace").build()
        val witness = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        assertFalse(Files.exists(witness))
        assertTrue(Files.isDirectory(witness.parent))
        Files.delete(marker)
        val recovered = runner(root).build()
        assertEquals(TaskOutcome.SUCCESS, recovered.task(":compileJava")!!.outcome)
        assertEquals("matched", verify(root, snapshot(root)).status)
    }

    @Test
    fun `generated sources and test fixture artifacts survive cache relocation`(@TempDir root: Path) {
        val first = root.resolve("first")
        fixture(first)
        val built = runner(first).build()
        assertEquals(TaskOutcome.SUCCESS, built.task(":generateJava")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, built.task(":support:testFixturesJar")!!.outcome)
        assertEquals(null, built.task(":test"))
        val original = snapshot(first)
        assertTrue(NodeId("class:p/Generated") in original.graph.nodes)
        assertEquals("build/generated/sources/sample/p/Generated.java",
            original.graph.nodes.getValue(NodeId("class:p/Generated")).location?.path)
        assertTrue(original.provenance!!.inputs.any { it.role == "classpath" && it.path.endsWith("support-test-fixtures.jar") })
        assertEquals("matched", verify(first, original).status)

        val again = runner(first).build()
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileTestJava")!!.outcome)

        val second = root.resolve("second")
        fixture(second)
        val restored = runner(second).build()
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileJava")!!.outcome)
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileTestJava")!!.outcome)
        val relocated = snapshot(second)
        assertEquals(original.graph.nodes, relocated.graph.nodes)
        assertEquals(original.graph.edges, relocated.graph.edges)
        assertEquals(original.provenance, relocated.provenance)
        assertEquals("matched", verify(second, relocated).status)
        assertFalse(Files.readString(second.resolve("build/reports/kartograph/jvm-snapshot.json")).contains(first.toString()))
    }

    @Test
    fun `continued build failure invalidates captured producer evidence`(@TempDir root: Path) {
        fixture(root)
        runner(root).build()
        val failed = runner(root, "broken", "--continue", "--rerun-tasks").buildAndFail()
        assertEquals(TaskOutcome.SUCCESS, failed.task(":compileJava")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, failed.task(":compileTestJava")!!.outcome)
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileTestJava/witness.json")))
        assertEquals("stale", verify(root, snapshot(root)).status)
        runner(root).build()
        assertEquals("matched", verify(root, snapshot(root)).status)
    }

    private fun fixture(root: Path) {
        write(root, "settings.gradle", """
            rootProject.name = 'lifecycle-snapshot'
            include 'support'
            buildCache { local { directory = file('../cache') } }
        """.trimIndent())
        write(root, "build.gradle", """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
            kartograph { snapshotsEnabled = true; includeSourcePaths = true }
            dependencies {
                implementation project(':support')
                testImplementation testFixtures(project(':support'))
            }
            def generatedRoot = layout.buildDirectory.dir('generated/sources/sample')
            def generator = tasks.register('generateJava') {
                outputs.dir(generatedRoot)
                doLast {
                    def directory = new File(generatedRoot.get().asFile, 'p')
                    directory.mkdirs()
                    new File(directory, 'Generated.java').text = 'package p; public class Generated { public static int value() { return 1; } }'
                }
            }
            sourceSets.main.java.srcDir(generator)
            tasks.register('broken') { doLast { throw new GradleException('unrelated failure') } }
            tasks.named('test') { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        write(root, "support/build.gradle", "plugins { id 'java-library'; id 'java-test-fixtures' }\n")
        write(root, "support/src/main/java/support/Support.java", "package support; public class Support {}")
        write(root, "support/src/testFixtures/java/support/Fixture.java", "package support; public class Fixture { public static int value() { return 1; } }")
        write(root, "src/main/java/p/Entry.java", "package p; public class Entry { public static int value() { return Generated.value(); } }")
        write(root, "src/test/java/p/EntryCheck.java", "package p; public class EntryCheck { public int check() { return Entry.value() + support.Fixture.value(); } }")
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun runner(root: Path, vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments(listOf("kartographSnapshot", "--build-cache", "--offline", "--configuration-cache", "--stacktrace") + arguments)

    private fun snapshot(root: Path): QuerySnapshot =
        QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))

    private fun verify(root: Path, snapshot: QuerySnapshot): ProvenanceVerifier.Result {
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        return ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings)
    }
}
