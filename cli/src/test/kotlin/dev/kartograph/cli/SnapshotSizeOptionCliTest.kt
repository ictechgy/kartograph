package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SnapshotSizeOptionCliTest {
    @Test
    fun `saved snapshot readers share the selected startup limit`(@TempDir root: Path) {
        val snapshot = QuerySnapshot(
            CodeGraph(listOf(GraphNode(NodeId("class:Target"), "Target", NodeKind.CLASS)), emptyList()),
            emptyList(),
            emptyList(),
        )
        val encoded = QuerySnapshotCodec.render(snapshot)
        val padding = " ".repeat(MIB + 1 - encoded.toByteArray(Charsets.UTF_8).size)
        val file = root.resolve("large.json").also { Files.writeString(it, encoded + padding) }
        assertTrue(Files.size(file) in (MIB + 1L)..(2L * MIB))

        assertEquals(2, execute("query", "Target", "--graph-file", file.toString(), "--snapshot-max-mib", "1").status)
        assertEquals(0, execute("query", "Target", "--graph-file", file.toString(), "--snapshot-max-mib", "2").status)
        assertEquals(2, execute("impact", "Target", "--graph-file", file.toString(), "--snapshot-max-mib", "1").status)
        assertEquals(0, execute("impact", "Target", "--graph-file", file.toString(), "--snapshot-max-mib", "2").status)
        assertEquals(2, execute("verify-snapshot", "--graph-file", file.toString(), "--project", root.toString(),
            "--snapshot-max-mib", "1").status)
        assertEquals(1, execute("verify-snapshot", "--graph-file", file.toString(), "--project", root.toString(),
            "--snapshot-max-mib", "2").status)
        assertEquals(2, executeMcp(file, "1"))
        assertEquals(0, executeMcp(file, "2"))

        McpTestClient { input, output, error ->
            KartographCli.runWithInput(
                arrayOf("mcp", "--graph-file", file.toString(), "--base-graph", file.toString(),
                    "--snapshot-max-mib", "2"),
                output,
                error,
                input,
            )
        }.use { client ->
            client.initialize()
            Files.writeString(file, "invalid replacement")
            val result = client.call("query_symbol", mapOf("symbol" to "Target"))
            val wrapper = result["structuredContent"] as Map<*, *>
            val document = wrapper["document"] as Map<*, *>
            assertEquals("found", document["status"])
        }
    }

    @Test
    fun `snapshot writer uses the selected limit without changing its document`(@TempDir root: Path) {
        val methods = (0 until 5_500).joinToString("\n") { index -> "public abstract void value$index();" }
        val source = root.resolve("Large.java")
        Files.writeString(source, "public abstract class Large {\n$methods\n}")
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(
            null, null, errors, "--release", "17", "-g:none", "-d", classes.toString(), source.toString(),
        ), errors.toString())
        val arguments = arrayOf("snapshot", "--classes", classes.toString(), "--project", root.toString())
        val default = execute(*arguments)
        assertEquals(0, default.status, default.error)
        val bytes = default.output.toByteArray(Charsets.UTF_8).size
        val requiredMiB = ((bytes + MIB - 1) / MIB)
        assertTrue(requiredMiB in 2..QuerySnapshotCodec.MAX_MIB, "snapshot bytes=$bytes")

        val rejected = execute(*arguments, "--snapshot-max-mib", (requiredMiB - 1).toString())
        val accepted = execute(*arguments, "--snapshot-max-mib", requiredMiB.toString())

        assertEquals(2, rejected.status)
        assertContains(rejected.error, "${requiredMiB - 1} MiB")
        assertEquals(0, accepted.status, accepted.error)
        assertEquals(default.output, accepted.output)
    }

    @Test
    fun `snapshot size option rejects invalid duplicates and live query use`(@TempDir root: Path) {
        val file = root.resolve("snapshot.json").also {
            Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())))
        }
        for (value in listOf("0", "129", "invalid")) {
            assertEquals(64, execute("snapshot", "--snapshot-max-mib", value).status)
            assertEquals(64, execute("query", "Missing", "--graph-file", file.toString(), "--snapshot-max-mib", value).status)
            assertEquals(64, execute("impact", "Missing", "--graph-file", file.toString(), "--snapshot-max-mib", value).status)
            assertEquals(64, execute("verify-snapshot", "--graph-file", file.toString(), "--project", root.toString(),
                "--snapshot-max-mib", value).status)
            assertEquals(64, executeMcp(file, value))
        }
        assertEquals(64, execute("query", "Missing", "--graph-file", file.toString(), "--snapshot-max-mib", "1",
            "--snapshot-max-mib", "2").status)
        assertEquals(64, execute("snapshot", "--snapshot-max-mib", "1", "--snapshot-max-mib", "2").status)
        assertEquals(64, execute("impact", "Missing", "--graph-file", file.toString(), "--snapshot-max-mib", "1",
            "--snapshot-max-mib", "2").status)
        assertEquals(64, execute("verify-snapshot", "--graph-file", file.toString(), "--project", root.toString(),
            "--snapshot-max-mib", "1", "--snapshot-max-mib", "2").status)
        assertEquals(64, executeMcpArguments("mcp", "--graph-file", file.toString(), "--snapshot-max-mib", "1",
            "--snapshot-max-mib", "2"))
        assertEquals(64, execute("query", "Missing", "--classes", root.toString(), "--project", root.toString(),
            "--snapshot-max-mib", "2").status)
    }

    @Test
    fun `snapshot help exposes the bounded opt in consistently`() {
        for (command in listOf("snapshot", "query", "impact", "verify-snapshot", "mcp")) {
            val result = execute(command, "--help")
            assertEquals(0, result.status)
            assertContains(result.output, "--snapshot-max-mib")
            assertContains(result.output, "128")
        }
    }

    private fun execute(vararg arguments: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Result(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private fun executeMcp(file: Path, maximumMiB: String): Int {
        return executeMcpArguments("mcp", "--graph-file", file.toString(), "--snapshot-max-mib", maximumMiB)
    }

    private fun executeMcpArguments(vararg arguments: String): Int {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        return KartographCli.runWithInput(
            arguments,
            PrintStream(output),
            PrintStream(error),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    private data class Result(val status: Int, val output: String, val error: String)

    private companion object {
        const val MIB = 1024 * 1024
    }
}
