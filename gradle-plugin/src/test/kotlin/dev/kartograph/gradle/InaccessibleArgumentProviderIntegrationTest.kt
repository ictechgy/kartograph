package dev.kartograph.gradle

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class InaccessibleArgumentProviderIntegrationTest {
    @Test
    fun `private provider preserves native compile while rejecting witness and snapshot`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'private-provider'\n")
        Files.writeString(root.resolve("metadata.txt"), "declared processor input")
        Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(root.resolve("src/main/java/Entry.java"), "public class Entry {}")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            def compiler = tasks.named('compileJava', JavaCompile).get()
            compiler.options.compilerArgumentProviders.add(
                dev.kartograph.gradle.fixtures.PrivateArgumentProvider.INSTANCE.create(file('metadata.txt')))
            // AGP 없는 JVM fixture에서는 Kotlin 경유로 관련 없는 generic Component 탐색을 피한다.
            def capture = dev.kartograph.gradle.fixtures.PrivateArgumentProvider.INSTANCE.capture(project, compiler)
            assert capture.rejection.name() == 'UNSUPPORTED_ARGUMENT_PROVIDER'
            def register = dev.kartograph.gradle.CompilerWitnesses.methods.find {
                it.name.startsWith('automaticJavaCompile') && it.parameterCount == 8 && it.parameterTypes[1] == JavaCompile
            }
            def witness = register.invoke(dev.kartograph.gradle.CompilerWitnesses.INSTANCE, project, compiler,
                ':main', files('src/main/java'), files('build.gradle'), files(), capture.files, capture.rejection)
            assert compiler.inputs.properties.kartographCaptureRejection == 'UNSUPPORTED_ARGUMENT_PROVIDER'
            def compilation = objects.newInstance(dev.kartograph.gradle.SnapshotCompilation)
            compilation.identity.set(compiler.path)
            compilation.compiler.set('javac')
            compilation.primarySources.from(compiler.source)
            compilation.classDirectories.from(compiler.destinationDirectory)
            compilation.witnessFiles.from(witness)
            tasks.register('snapshot', dev.kartograph.gradle.KartographSnapshotTask) {
                dependsOn compiler
                compilations.set([compilation])
                scope.set(':main')
                projectDirectory.set(layout.projectDirectory)
                buildDirectory.set(layout.buildDirectory)
                includeSourcePaths.set(false)
                includePrivateMembers.set(false)
                classRoots.from(compiler.destinationDirectory)
                sourceDirectories.from('src/main/java')
                sourceFiles.from(compiler.source)
                buildInputFiles.from('build.gradle')
                buildWitnessFiles.from(witness)
                snapshotFile.set(layout.buildDirectory.file('snapshot.json'))
                localBindingsFile.set(layout.buildDirectory.file('bindings.json'))
            }
        """.trimIndent())
        val metadata = Properties().also { properties ->
            javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use(properties::load)
        }
        val classpath = metadata.getProperty("implementation-classpath").split(File.pathSeparator).map(::File) +
            File(dev.kartograph.gradle.fixtures.PrivateArgumentProvider::class.java.protectionDomain.codeSource.location.toURI())
        fun runner(task: String) = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath(classpath)
            .withArguments(task, "--offline", "--configuration-cache", "--stacktrace")
        val witness = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        Files.createDirectories(witness.parent)
        Files.writeString(witness, "stale observation must not survive")
        val compiled = runner("compileJava").build()
        assertEquals(TaskOutcome.SUCCESS, compiled.task(":compileJava")!!.outcome)
        assertTrue(Files.isRegularFile(root.resolve("build/classes/java/main/Entry.class")))
        assertFalse(Files.exists(witness))
        assertEquals("UNSUPPORTED_ARGUMENT_PROVIDER", Files.readString(witness.parent.resolve("rejection.txt")))
        val rejected = runner("snapshot").buildAndFail()
        assertContains(rejected.output, "missing compiler witness for :compileJava")
        assertContains(rejected.output, WitnessRejection.UNSUPPORTED_ARGUMENT_PROVIDER.description)
        assertFalse(Files.exists(root.resolve("build/snapshot.json")))
        assertFalse(Files.exists(witness))
    }
}
