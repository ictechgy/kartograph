package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class ResourceFeedbackTest {
    @Test
    fun `repeated resource roots accumulate independently of order and duplicate paths`(@TempDir root: Path) {
        val classes = fixture(root)
        for (command in listOf("dead", "why")) {
            val selector = if (command == "why") listOf("class:fixture/A") else emptyList()
            val format = if (command == "dead") listOf("--report-format", "json") else emptyList()
            fun run(roots: List<String>) = execute(listOf(command) + selector + base(root, classes) +
                roots.flatMap { listOf("--resources", it) } + format)
            val first = run(listOf("res-a", "res-b"))
            val reversed = run(listOf("res-b", "res-a"))
            val duplicated = run(listOf("res-a", "res-a", "res-b", "res-b"))
            assertEquals(0, first.status, first.error)
            assertEquals(first, reversed)
            assertEquals(first, duplicated)
            if (command == "dead") {
                assertContains(first.output, "class:fixture/Unused")
                assertFalse(first.output.contains("class:fixture/A\""))
                assertFalse(first.output.contains("class:fixture/B\""))
                assertContains(run(listOf("res-a")).output, "class:fixture/B")
            } else assertContains(first.output, "retained")
        }
    }

    @Test
    fun `compiled flat resources fail with an actionable diagnostic even beside valid XML`(@TempDir root: Path) {
        val classes = fixture(root)
        Files.write(root.resolve("res-a/layout_screen.xml.flat"), byteArrayOf(1, 2, 3))
        for (options in listOf(emptyList(), listOf("--report-format", "json"), listOf("--report-format", "markdown"),
            listOf("--report-format", "sarif"))) {
            val result = execute(listOf("dead") + base(root, classes) + listOf("--resources", "res-a") + options)
            assertEquals(2, result.status)
            assertContains(result.error, "compiled Android .flat resources are unsupported")
            assertContains(result.error, "XML")
        }
        // 존재하지 않는 출력 옵션은 분석 성공으로 해석하지 않고 기존 사용 오류를 유지한다.
        for (option in listOf("--quiet", "--summary")) {
            assertEquals(64, execute(listOf("dead") + base(root, classes) + listOf("--resources", "res-a", option)).status)
        }
    }

    private fun fixture(root: Path): Path {
        val classes = Files.createDirectories(root.resolve("classes"))
        val sources = listOf("A", "B", "Unused").map { name ->
            val file = root.resolve("src/fixture/$name.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package fixture; public class $name {}")
            file.toString()
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), *sources.toTypedArray()))
        Files.writeString(root.resolve("AndroidManifest.xml"), "<manifest package=\"fixture\" />")
        for ((directory, name) in listOf("res-a" to "A", "res-b" to "B")) {
            val xml = root.resolve("$directory/layout/screen.xml")
            Files.createDirectories(xml.parent)
            Files.writeString(xml, "<fixture.$name />")
        }
        return classes
    }

    private fun base(root: Path, classes: Path) = listOf("--classes", classes.toString(), "--project", root.toString(),
        "--manifest", "AndroidManifest.xml", "--namespace", "fixture")

    private fun execute(arguments: List<String>): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments.toTypedArray(), PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    private data class Execution(val status: Int, val output: String, val error: String)
}
