package dev.kartograph.gradle

import dev.kartograph.index.CompilerEvidenceToken
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AndroidCompilerArgumentInputsTest {
    @Test
    fun `nonpublic provider does not break ordinary task configuration`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java")
        val task = project.tasks.named("compileJava", JavaCompile::class.java).get()
        task.options.compilerArgumentProviders.add(dev.kartograph.gradle.fixtures.PrivateArgumentProvider.create(root.resolve("metadata.txt").toFile()))
        val captured = AndroidCompilerInputs.argumentProviderInputs(project, task)
        assertTrue(captured.files.isEmpty)
        assertEquals(WitnessRejection.UNSUPPORTED_ARGUMENT_PROVIDER, captured.rejection)
    }

    @Test
    fun `unsupported annotated file values and conflicting roles are rejected`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java")
        val task = project.tasks.named("compileJava", JavaCompile::class.java).get()
        task.options.compilerArgumentProviders.add(UnsupportedArguments())
        assertFails { AndroidCompilerInputs.argumentProviderInputs(project, task).files.files }
        task.options.compilerArgumentProviders.clear()
        task.options.compilerArgumentProviders.add(ConflictingArguments())
        assertContains(assertFailsWith<IllegalArgumentException> {
            AndroidCompilerInputs.argumentProviderInputs(project, task).files.files
        }.message.orEmpty(), "same property as input and output")
    }

    @Test
    fun `declared processor metadata joins witness inputs while output and undeclared inputs remain excluded`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java")
        val sources = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(sources.resolve("Example.java"), "public class Example {}")
        val build = Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }")
        val layout = Files.writeString(root.resolve("layout-info.xml"), "before")
        val output = Files.createDirectories(root.resolve("processor-output"))
        val task = project.tasks.named("compileJava", JavaCompile::class.java).get()
        val arguments = project.objects.newInstance(BindingArguments::class.java)
        arguments.layoutInfo.set(layout.toFile())
        arguments.output.set(output.toFile())
        task.options.compilerArgumentProviders.add(arguments)
        val modules = task.javaCompiler.get().metadata.installationPath.file("lib/modules")
        fun spec(additional: org.gradle.api.file.FileCollection) = WitnessSpec(root.toFile(), "sample:main", task.name,
            task.path, "javac", project.files(sources), project.files(build), project.files(sources, build, modules, additional),
            additional, project.layout.buildDirectory.file("witness.json"), null, automaticSourceInventory = true)
        val missing = assertFailsWith<IllegalArgumentException> { spec(project.files()).observe(task) }
        assertContains(missing.message.orEmpty(), "byte inputs omit declared compiler files")
        assertEquals(WitnessRejection.UNCOVERED_FILE_INPUT, WitnessRejection.from(missing))
        val capture = AndroidCompilerInputs.argumentProviderInputs(project, task)
        assertNull(capture.rejection)
        val captured = capture.files
        assertEquals(setOf(layout.toFile()), captured.files)
        val witness = spec(captured)
        val before = witness.observe(task)
        assertTrue(before.any { it.role == "compilerInput" && it.path.endsWith("layout-info.xml") })
        Files.writeString(output.resolve("generated.java"), "not an input")
        assertEquals(before, witness.observe(task))
        Files.writeString(layout, "after")
        assertNotEquals(CompilerEvidenceToken.create("sample:main", "javac", task.path, before),
            CompilerEvidenceToken.create("sample:main", "javac", task.path, witness.observe(task)))
        arguments.layoutInfo.set(output.resolve("generated.java").toFile())
        assertContains(assertFailsWith<IllegalArgumentException> { captured.files }.message.orEmpty(), "overlaps compiler outputs")
        val ownOutput = task.destinationDirectory.get().asFile.toPath()
        Files.createDirectories(ownOutput)
        val self = Files.writeString(ownOutput.resolve("prior.class"), "prior output")
        arguments.layoutInfo.set(self.toFile())
        assertContains(assertFailsWith<IllegalArgumentException> { captured.files }.message.orEmpty(), "overlaps compiler outputs")
        arguments.layoutInfo.set(layout.toFile())
        val arbitrary = Files.writeString(root.resolve("unmapped-input.txt"), "unknown")
        task.inputs.file(arbitrary)
        assertContains(assertFailsWith<IllegalArgumentException> { witness.observe(task) }.message.orEmpty(),
            "byte inputs omit declared compiler files")
    }

    abstract class BindingArguments : CommandLineArgumentProvider {
        @get:InputFiles abstract val layoutInfo: RegularFileProperty
        @get:InputFiles @get:Optional abstract val optionalMetadata: RegularFileProperty
        @get:OutputDirectory abstract val output: DirectoryProperty
        override fun asArguments(): Iterable<String> = listOf("-Ametadata=" + layoutInfo.get().asFile.absolutePath)
    }

    class UnsupportedArguments : CommandLineArgumentProvider {
        @get:InputFiles val unsupported: Any = Any()
        override fun asArguments(): Iterable<String> = emptyList()
    }

    class ConflictingArguments : CommandLineArgumentProvider {
        @get:InputFiles @get:OutputFile val conflict: String = "ambiguous.txt"
        override fun asArguments(): Iterable<String> = emptyList()
    }

}
