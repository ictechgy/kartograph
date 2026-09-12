package dev.kartograph.gradle

import dev.kartograph.export.BuildWitnessCodec
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
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
    fun `ABI identical dependency changes refresh cached compiler evidence`(@TempDir root: Path) {
        fixture(root, after = "dependencies { implementation files('lib/dependency.jar') }")
        fun dependency(value: Int) {
            val source = root.resolve("dependency-source/Dependency.java")
            Files.createDirectories(source.parent)
            Files.writeString(source, "package library; public class Dependency { public static int value(){ return $value; } }")
            val classes = Files.createDirectories(root.resolve("dependency-classes"))
            val errors = ByteArrayOutputStream()
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-d", classes.toString(), source.toString()), errors.toString())
            val jar = root.resolve("lib/dependency.jar")
            Files.createDirectories(jar.parent)
            JarOutputStream(Files.newOutputStream(jar)).use { archive ->
                archive.putNextEntry(JarEntry("library/Dependency.class").apply { time = 0 })
                archive.write(Files.readAllBytes(classes.resolve("library/Dependency.class")))
                archive.closeEntry()
            }
        }
        fun compile() = build(root, "compileJava", "--configuration-cache")
        dependency(1)
        compile()
        val path = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        val before = BuildWitnessCodec.parse(Files.readString(path))
        dependency(2)
        assertEquals(TaskOutcome.SUCCESS, compile().task(":compileJava")!!.outcome)
        val after = BuildWitnessCodec.parse(Files.readString(path))
        assertNotEquals(before.inputs.single { it.role == "classpath" }.sha256, after.inputs.single { it.role == "classpath" }.sha256)
        assertEquals(TaskOutcome.UP_TO_DATE, compile().task(":compileJava")!!.outcome)

        dependency(3)
        build(root, "clean")
        assertEquals(TaskOutcome.SUCCESS, compile().task(":compileJava")!!.outcome)
        val restored = BuildWitnessCodec.parse(Files.readString(path))
        assertNotEquals(after.inputs.single { it.role == "classpath" }.sha256, restored.inputs.single { it.role == "classpath" }.sha256)
        assertEquals(TaskOutcome.UP_TO_DATE, compile().task(":compileJava")!!.outcome)
    }

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
                files('src/main/kotlin', 'src/main/java'), files('build.gradle', 'settings.gradle'), launcher,
                files(configurations.named('kotlinBuildToolsApiClasspath')))
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
        val buildFile = root.resolve("build.gradle")
        val configured = Files.readString(buildFile)
        Files.writeString(buildFile, configured.replace("files(configurations.named('kotlinBuildToolsApiClasspath'))", "files()"))
        val omitted = build(root, "compileKotlin", fails = true)
        assertTrue(omitted.output.contains("selected compiler runtime artifacts in additionalInputs"), omitted.output)
        assertFalse(Files.exists(witness))
        Files.writeString(buildFile, configured)
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

    @Test
    fun `generated source providers run before hashing and continued failures remove evidence`(@TempDir root: Path) {
        fixture(root, before = """
            def generatedRoot = layout.buildDirectory.dir('generated/sources/sample')
            def generator = tasks.register('generateJava') {
                outputs.dir(generatedRoot)
                doLast {
                    def directory = generatedRoot.get().asFile
                    directory.mkdirs()
                    new File(directory, 'Generated.java').text = 'public class Generated {}'
                }
            }
            tasks.named('compileJava') { source generator }
            tasks.register('broken') { doLast { throw new GradleException('unrelated failure') } }
        """.trimIndent(), roots = "files('src/main/java', generator)")
        val first = build(root, "compileJava", "--configuration-cache")
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateJava")!!.outcome)
        val witness = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        val completed = Files.readString(witness)
        val again = build(root, "compileJava", "--configuration-cache")
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")!!.outcome)
        assertEquals(completed, Files.readString(witness))
        val failed = build(root, "broken", "compileJava", "--continue", "--rerun-tasks", fails = true)
        assertEquals(TaskOutcome.SUCCESS, failed.task(":compileJava")!!.outcome)
        assertFalse(Files.exists(witness))
    }

    @Test
    fun `additional compiler inputs must be explicitly included in the byte key`(@TempDir root: Path) {
        val declared = "tasks.named('compileJava') { inputs.file('extra.bin') }"
        fixture(root, after = declared)
        Files.write(root.resolve("extra.bin"), byteArrayOf(1, 2, 3))
        val failed = build(root, fails = true)
        assertTrue(failed.output.contains("supply additionalInputs explicitly"), failed.output)
        val witness = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        assertFalse(Files.exists(witness))
        fixture(root, after = declared, additionalInputs = "files('extra.bin')")
        assertEquals(TaskOutcome.SUCCESS, build(root).task(":compileJava")!!.outcome)
        assertTrue(Files.exists(witness))
    }

    private fun fixture(root: Path, before: String = "", after: String = "", roots: String = "files('src/main/java')",
        additionalInputs: String = "files()") {
        Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'sample'\nbuildCache { local { directory = file('.witness-test-cache') } }\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            $before
            dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, tasks.named('compileJava', JavaCompile),
                'sample:main', $roots, files('build.gradle', 'settings.gradle'), $additionalInputs)
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
