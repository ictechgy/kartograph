package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

/** 실제 컴파일 산출물에서 plugin과 별도 CLI의 형식·간선 선택·한계가 같은지 대조한다. */
class GraphExportParityTest {
    @Test
    fun `compiled export options and source evidence match the CLI byte for byte`(@TempDir root: Path) {
        val (classes, library) = compileFixture(root)
        val task = task(root, classes, library)
        val variants = listOf(
            emptyList(),
            listOf("--dispatch-candidate-limit", "1"),
            listOf("--exclude-origin", "dispatchModel", "--include-external-stubs"),
            listOf("--exclude-origin", "bytecode"),
        )
        for (format in listOf("json", "ndjson")) for (options in variants) {
            task.graphFormat.set(format)
            task.dispatchCandidateLimit.set(if ("--dispatch-candidate-limit" in options) 1 else 256)
            task.excludeOrigins.set(if ("--exclude-origin" in options) setOf(options[1]) else emptySet())
            task.includeExternalStubs.set("--include-external-stubs" in options)
            task.renderGraph()
            val actual = Files.readString(task.graphFile.get().asFile.toPath())
            val expected = cli(root, listOf("graph", "--classes", classes.toString(), "--classpath", library.toString(),
                "--project", root.toString(), "--include-paths", "--format", format) + options)
            assertEquals(expected, actual, "$format $options")
            if ("--dispatch-candidate-limit" in options) assertContains(actual, "dispatch-candidates-over-limit:")
            if ("--exclude-origin" in options) assertContains(actual, "edge-origin-excluded:")
            if ("--include-external-stubs" in options) assertContains(actual, "externalStub")
            assertFalse(actual.contains(root.toString()), "absolute source path leaked")
        }
        for (name in listOf("graphFormat", "excludeOrigins", "dispatchCandidateLimit", "includeExternalStubs")) {
            assertTrue(name in task.inputs.properties, "$name must invalidate task output")
        }
    }

    @Test
    fun `invalid export settings fail before producing a graph`(@TempDir root: Path) {
        val task = task(root, root, root)
        task.graphFormat.set("yaml")
        assertFailsWith<IllegalArgumentException> { task.renderGraph() }
        task.graphFormat.set("json")
        task.excludeOrigins.set(setOf("typo"))
        assertFailsWith<IllegalArgumentException> { task.renderGraph() }
        task.excludeOrigins.set(emptySet())
        for (limit in listOf(0, 1_000_001)) {
            task.dispatchCandidateLimit.set(limit)
            assertFailsWith<IllegalArgumentException> { task.renderGraph() }
        }
        assertFalse(Files.exists(task.graphFile.get().asFile.toPath()))
    }

    @Test
    fun `extension defaults match CLI export budgets and keep strict parsing`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        project.pluginManager.apply(KartographPlugin::class.java)
        val extension = project.extensions.getByType(KartographExtension::class.java)
        assertEquals("json", extension.graphFormat.get())
        assertEquals(emptySet(), extension.excludeOrigins.get())
        assertEquals(256, extension.dispatchCandidateLimit.get())
        assertFalse(extension.includeExternalStubs.get())
        assertEquals("strict", extension.keepRuleMode.get())
        assertTrue(extension.useVariantKeepRules.get())
        assertTrue(extension.excludedKeepRules.isEmpty)
    }

    /** 외부 interface와 여러 프로젝트 구현을 따로 컴파일해 실제 hierarchy와 fanout을 만든다. */
    private fun compileFixture(root: Path): Pair<Path, Path> {
        val library = Files.createDirectories(root.resolve("library"))
        val classes = Files.createDirectories(root.resolve("classes"))
        val source = Files.createDirectories(root.resolve("src/main/java/sample"))
        val api = source.resolve("Port.java")
        Files.writeString(api, "package sample; public interface Port { void send(); }")
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-g", "-d", library.toString(), api.toString()))
        val files = (0..2).map { index -> source.resolve("Handler$index.java").also {
            Files.writeString(it, "package sample; public class Handler$index implements Port { public void send() {} }")
        }} + listOf(source.resolve("Consumer.java").also {
            Files.writeString(it, "package sample; public class Consumer { public void use(Port port) { port.send(); } }")
        })
        assertEquals(0, compiler.run(null, null, null, "-g", "-classpath", library.toString(), "-d", classes.toString(),
            *files.map(Path::toString).toTypedArray()))
        return classes to library
    }

    /** AGP 제공 artifact와 같은 task input을 실제 컴파일 디렉터리로 채운다. */
    private fun task(root: Path, classes: Path, library: Path): KartographGraphTask {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        return project.tasks.register("graphParity", KartographGraphTask::class.java).get().apply {
            projectJars.set(emptyList())
            projectDirectories.set(listOf(project.layout.dir(project.provider { classes.toFile() }).get()))
            classpathDirectories.set(listOf(project.layout.dir(project.provider { library.toFile() }).get()))
            variantName.set("debug")
            includeSourcePaths.set(true)
            projectDirectory.set(project.layout.projectDirectory)
            graphFile.set(project.layout.buildDirectory.file("reports/graph.json"))
        }
    }

    /** CLI의 process 종료를 테스트 JVM과 분리하고 성공한 stdout 원문만 비교한다. */
    private fun cli(root: Path, arguments: List<String>): String {
        val output = root.resolve("cli-stdout.txt")
        val error = root.resolve("cli-stderr.txt")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val status = ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), "dev.kartograph.cli.MainKt") + arguments)
            .redirectOutput(output.toFile()).redirectError(error.toFile()).start().waitFor()
        assertEquals(0, status, Files.readString(error))
        return Files.readString(output)
    }
}
