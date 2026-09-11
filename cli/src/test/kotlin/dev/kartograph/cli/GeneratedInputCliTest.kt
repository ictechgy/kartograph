package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class GeneratedInputCliTest {
    @Test
    fun `explicit generated roots suppress only their findings while preserving graph declarations`(@TempDir root: Path) {
        val regular = compile(root.resolve("regular"), "User_Factory", "public class User_Factory {}")
        val generated = compile(root.resolve("generated"), "GeneratedWrapper",
            "public class GeneratedWrapper { public static class Payload {} }")
        root.resolve("manifest.xml").writeText("<manifest />")
        val resources = root.resolve("res").createDirectories()
        val args = arrayOf("dead", "--classes", regular.toString(), "--classes", generated.toString(),
            "--project", root.toString(), "--manifest", "manifest.xml", "--resources", resources.toString(), "--namespace", "app")
        val before = execute(*args)
        assertEquals(setOf("class:GeneratedWrapper", "class:GeneratedWrapper${'$'}Payload", "class:User_Factory"), findings(before))

        val after = execute(*args, "--generated-classes", generated.toString())
        assertEquals(0, after.first)
        assertEquals(setOf("class:User_Factory"), findings(after))
        val query = execute("query", "GeneratedWrapper", "--classes", regular.toString(), "--classes", generated.toString(),
            "--project", root.toString(), "--generated-classes", generated.toString())
        assertEquals(0, query.first)
        assertContains(query.second, "\"status\": \"found\"")
        val graph = execute("graph", "--classes", generated.toString(), "--generated-classes", generated.toString(), "--format", "json")
        assertEquals(0, graph.first)
        assertContains(graph.second, "generatedInput")
        assertContains(graph.second, "class:GeneratedWrapper")
    }

    @Test
    fun `unused generated marker is rejected and later duplicate does not relabel first input`(@TempDir root: Path) {
        val regular = compile(root.resolve("regular"), "Same", "public class Same {}")
        val generated = compile(root.resolve("generated"), "Same", "public class Same {}")
        val rejected = execute("graph", "--classes", regular.toString(), "--generated-classes", generated.toString())
        assertEquals(2, rejected.first)
        val graph = execute("graph", "--classes", regular.toString(), "--classes", generated.toString(),
            "--generated-classes", generated.toString(), "--format", "json")
        assertEquals(0, graph.first)
        assertContains(graph.second, "\"synthesized\": false")
        assertEquals(false, graph.second.contains("generatedInput"))
    }

    private fun compile(root: Path, name: String, text: String): Path {
        root.createDirectories()
        val source = root.resolve("$name.java").apply { writeText(text) }
        val classes = root.resolve("classes").createDirectories()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun findings(result: Pair<Int, String>): Set<String> = result.second.lineSequence()
        .filter { it.startsWith("unreachable\t") }.map { it.split('\t')[1] }.toSet()

    private fun execute(vararg args: String): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(output), PrintStream(error))
        return status to output.toString(Charsets.UTF_8)
    }
}
