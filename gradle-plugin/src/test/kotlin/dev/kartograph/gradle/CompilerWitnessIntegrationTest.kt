package dev.kartograph.gradle

import dev.kartograph.export.BuildWitnessCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class CompilerWitnessIntegrationTest {
    @Test
    fun `real Kotlin compiler records configured toolchain and Kotlin Java inputs`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("src/main/kotlin"))
        Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'sample'\nbuildCache { local { directory = file('.witness-test-cache') } }\n")
        Files.writeString(root.resolve("build.gradle"), """
            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10' }
            }
            plugins { id 'io.github.ictechgy.kartograph' }
            apply plugin: 'org.jetbrains.kotlin.jvm'
            repositories { mavenCentral() }
            java { toolchain { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) } }
            def launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) }
            dev.kartograph.gradle.KotlinCompilerWitnesses.INSTANCE.kotlinCompile(project,
                tasks.named('compileKotlin', org.jetbrains.kotlin.gradle.tasks.KotlinCompile), 'sample:main',
                files('src/main/kotlin', 'src/main/java'), files('build.gradle', 'settings.gradle'), launcher)
        """.trimIndent())
        Files.writeString(root.resolve("src/main/kotlin/Entry.kt"), "class Entry { fun value() = Helper.value() }")
        Files.writeString(root.resolve("src/main/java/Helper.java"), "public class Helper { public static int value() { return 1; } }")
        build(root, "compileKotlin", "--configuration-cache", offline = false)
        val witness = root.resolve("build/kartograph/witnesses/compileKotlin/witness.json")
        val parsed = BuildWitnessCodec.parse(Files.readString(witness))
        assertEquals("kotlin", parsed.compiler)
        assertTrue(parsed.inputs.count { it.role == "sources" } >= 2)
        assertTrue(parsed.inputs.any { it.role == "compiler" })
        assertTrue(parsed.outputs.single().path.endsWith("main"))
        val again = build(root, "compileKotlin", "--configuration-cache", offline = false)
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileKotlin")!!.outcome)
        val completed = Files.readString(witness)
        build(root, "clean")
        assertEquals(TaskOutcome.FROM_CACHE, build(root, "compileKotlin").task(":compileKotlin")!!.outcome)
        assertEquals(completed, Files.readString(witness))
        Files.writeString(root.resolve("src/main/kotlin/Entry.kt"), "broken Kotlin source")
        build(root, "compileKotlin", fails = true)
        assertFalse(Files.exists(witness))
    }

    @Test
    fun `javac lifecycle binds bytes and cache output and invalidates failed attempts`(@TempDir root: Path) {
        fixture(root)
        val first = build(root)
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileJava")!!.outcome)
        val witness = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        val before = Files.readString(witness)
        assertEquals("sample:main", BuildWitnessCodec.parse(before).scope)
        assertEquals(":compileJava", BuildWitnessCodec.parse(before).artifact)
        assertFalse(before.contains(root.toString()))
        assertEquals(TaskOutcome.UP_TO_DATE, build(root).task(":compileJava")!!.outcome)
        assertEquals(before, Files.readString(witness))

        val source = root.resolve("src/main/java/Example.java")
        val stamp = Files.getLastModifiedTime(source)
        Files.writeString(source, "public class Example { public int value() { return 2; } }")
        Files.setLastModifiedTime(source, stamp)
        assertEquals(TaskOutcome.SUCCESS, build(root).task(":compileJava")!!.outcome)
        assertNotEquals(before, Files.readString(witness))

        val completed = Files.readString(witness)
        build(root, "clean")
        assertEquals(TaskOutcome.FROM_CACHE, build(root).task(":compileJava")!!.outcome)
        assertEquals(completed, Files.readString(witness))

        Files.writeString(source, "this is not Java")
        build(root, fails = true)
        assertFalse(Files.exists(witness))
    }

    @Test
    fun `mid compilation source edit and a late failing action cannot leave success evidence`(@TempDir root: Path) {
        fixture(root, before = "tasks.named('compileJava') { doLast { file('src/main/java/Example.java').append(' // changed') } }")
        build(root, fails = true)
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
        fixture(root)
        build(root)
        assertTrue(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
        fixture(root, after = "tasks.register('failedDependency') { doLast { throw new GradleException('dependency failure') } }; tasks.named('compileJava') { dependsOn 'failedDependency' }")
        build(root, fails = true)
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
        fixture(root, after = "tasks.named('compileJava') { doLast { throw new GradleException('late failure') } }")
        build(root, fails = true)
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileJava/witness.json")))
    }

    @Test
    fun `configuration cache and relocated checkout retain portable evidence`(@TempDir root: Path) {
        val first = root.resolve("first")
        fixture(first)
        build(first, "compileJava", "--configuration-cache")
        val again = build(first, "compileJava", "--configuration-cache")
        assertTrue(again.output.contains("Reusing configuration cache"))
        val second = root.resolve("second")
        fixture(second)
        build(second)
        assertEquals(Files.readString(first.resolve("build/kartograph/witnesses/compileJava/witness.json")),
            Files.readString(second.resolve("build/kartograph/witnesses/compileJava/witness.json")))
    }

    private fun fixture(root: Path, before: String = "", after: String = "") {
        Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'sample'\nbuildCache { local { directory = file('.witness-test-cache') } }\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            $before
            dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, tasks.named('compileJava', JavaCompile),
                'sample:main', files('src/main/java'), files('build.gradle', 'settings.gradle'))
            $after
        """.trimIndent())
        Files.writeString(root.resolve("src/main/java/Example.java"), "public class Example { public int value() { return 1; } }")
    }

    private fun build(root: Path, vararg arguments: String, fails: Boolean = false, offline: Boolean = true): org.gradle.testkit.runner.BuildResult {
        val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments((arguments.toList().ifEmpty { listOf("compileJava") }) + listOf("--build-cache", "--stacktrace") + if (offline) listOf("--offline") else emptyList())
        return if (fails) runner.buildAndFail() else runner.build()
    }
}
