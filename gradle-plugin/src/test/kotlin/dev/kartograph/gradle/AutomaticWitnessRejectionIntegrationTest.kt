package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class AutomaticWitnessRejectionIntegrationTest {
    @Test
    fun `automatic evidence rejects mid compilation mutation without hiding native success`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'changed-witness'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph { snapshotsEnabled = true }
            tasks.named('compileJava') {
                doFirst {
                    def file = source.singleFile
                    file.text = file.text.contains('return 1;') ? file.text.replace('return 1;', 'return 2;') : file.text.replace('return 2;', 'return 1;')
                }
            }
        """.trimIndent())
        val source = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(source.resolve("Entry.java"), "public class Entry { public int value() { return 1; } }")
        val result = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--stacktrace").buildAndFail()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")!!.outcome)
        assertTrue(result.output.contains("compiler inputs changed during compilation"), result.output)
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
        assertFalse(Files.exists(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
    }

    @Test
    fun `unsupported observation preserves ordinary compilation and rejects snapshot`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'unsupported-witness'\n")
        val build = root.resolve("build.gradle")
        Files.writeString(build, """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph { snapshotsEnabled = true }
            tasks.named('compileJava') { options.compilerArgs += ['-nowarn'] }
        """.trimIndent())
        val source = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(source.resolve("Entry.java"), "public class Entry {}")
        fun runner(task: String) = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments(task, "--offline", "--configuration-cache", "--stacktrace")
        val compiled = runner("compileJava").build()
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileJava")!!.outcome)
        assertTrue(Files.isRegularFile(root.resolve("build/classes/java/main/Entry.class")))
        val witness = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        assertFalse(Files.exists(witness))
        val again = runner("compileJava").build()
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")!!.outcome)
        val rejected = runner("kartographSnapshot").buildAndFail()
        assertTrue(rejected.output.contains("missing compiler witness for :compileJava"), rejected.output)
        assertTrue(rejected.output.contains("unsupported javac option for compiler witness"), rejected.output)
        assertFalse(Files.exists(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        Files.writeString(build, Files.readString(build).replace("options.compilerArgs += ['-nowarn']", "options.compilerArgs += ['-parameters']"))
        runner("kartographSnapshot").build()
        assertTrue(Files.isRegularFile(witness))
    }
}
