package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class CompilerWitnessValidationTest {
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
