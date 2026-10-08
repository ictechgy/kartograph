package dev.kartograph.gradle

import dev.kartograph.index.CompilerCallPositionOptions
import dev.kartograph.index.CompilerEvidenceToken
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class CompilerWitnessValidationTest {
    @Test
    fun `observed javac options append one token-bound call position marker only when enabled`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java")
        val sources = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(sources.resolve("Example.java"), "public class Example {}")
        val buildFile = Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }")
        val task = project.tasks.named("compileJava", JavaCompile::class.java).get()
        val modules = task.javaCompiler.get().metadata.installationPath.asFile.toPath().resolve("lib/modules")
        val byteInputs = project.files(sources, buildFile, modules)
        val spec = WitnessSpec(root.toFile(), "sample:main", task.name, task.path, "javac",
            project.files(sources), project.files(buildFile), byteInputs, project.files(),
            project.layout.buildDirectory.file("witness.json"), null, compilerEvidence = true)
        fun configure(value: String) {
            task.options.compilerArgs = listOf("-Xplugin:KartographEvidence root=file:///project%20root " +
                "output=file:///build/evidence.tsv token=file:///build/token callPositions=$value")
        }

        configure("true")
        val enabled = spec.observe(task)
        assertEquals(1, enabled.count { it == CompilerCallPositionOptions.enabledInput() })
        assertEquals(2, enabled.count { it.role == "options" })
        assertEquals(enabled, spec.observe(task))
        val enabledToken = CompilerEvidenceToken.create("sample:main", "javac", task.path, enabled)

        configure("false")
        val disabled = spec.observe(task)
        assertFalse(CompilerCallPositionOptions.isEnabled(disabled))
        assertEquals(1, disabled.count { it.role == "options" })
        assertTrue(disabled.single { it.role == "options" }.path.endsWith("-options"))
        assertNotEquals(enabledToken, CompilerEvidenceToken.create("sample:main", "javac", task.path, disabled))
    }

    @Test
    fun `a JDK input alias does not duplicate the compiler artifact as a generic input`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java")
        val sources = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(sources.resolve("Example.java"), "public class Example {}")
        val buildFile = Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }")
        val task = project.tasks.named("compileJava", JavaCompile::class.java).get()
        val installation = task.javaCompiler.get().metadata.installationPath.asFile.toPath()
        val alias = Files.createSymbolicLink(root.resolve("jdk-alias"), installation).resolve("lib/modules")
        task.inputs.file(alias)
        val spec = WitnessSpec(root.toFile(), "sample:main", task.name, task.path, "javac",
            project.files(sources), project.files(buildFile), project.files(sources, buildFile, alias), project.files(alias),
            project.layout.buildDirectory.file("witness.json"), null)

        val inputs = spec.observe(task)
        assertEquals(1, inputs.count { it.role == "compiler" })
        assertEquals(0, inputs.count { it.role == "compilerInput" })
    }

    @Test
    fun `a compiler configured to ignore errors cannot keep previous success evidence`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply("java")
        val sources = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(sources.resolve("Example.java"), "public class Example {}")
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }")
        val compiler = project.tasks.named("compileJava", JavaCompile::class.java)
        val witness = CompilerWitnesses.javaCompile(project, compiler, "sample:main",
            project.files(sources), project.files("build.gradle")).get().asFile.toPath()
        Files.createDirectories(witness.parent)
        Files.writeString(witness, "previous-evidence-sentinel")
        val task = compiler.get()
        task.options.isFailOnError = false

        // 시작 시 검증 실패를 검사한다. 이 action 호출을 실제 compiler 실행 증거로 사용하지 않는다.
        val failure = assertFailsWith<IllegalArgumentException> { task.actions.first().execute(task) }
        assertContains(failure.message.orEmpty(), "failOnError=true")
        assertFalse(Files.exists(witness))
    }
}
