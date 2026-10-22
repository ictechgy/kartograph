package dev.kartograph.cli

import dev.kartograph.core.CallDeclarationScope
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ClassFileIndexer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** 새 선언 근거가 원래 CALL·후보·CLI/MCP 질의 의미를 바꾸지 않는지 실제 javac 입력으로 검증한다. */
class CallDeclarationCliTest {
    @Test
    fun `graph declaration snapshots and CLI MCP queries preserve independent observable calls`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("Entry.java"), """
            package p;
            public class Entry {
                public static class Base { public String value() { return "base"; } }
                public static class Boundary extends Base {}
                public static class Child extends Boundary { public String value() { return "child"; } }
                public static String run(Boundary value) { return value.value(); }
            }
        """.trimIndent())
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()))
        val graph = ClassFileIndexer().index(listOf(classes))
        val call = graph.externalCalls.single { it.name == "value" }
        val declaration = assertNotNull(call.declaration)
        assertEquals(CallDeclarationScope.PROJECT, declaration.scope)
        assertEquals("method:p/Entry\$Base#value()Ljava/lang/String;", declaration.target.value)
        assertFalse(graph.edges.any { it.source == call.caller && it.kind == EdgeKind.CALL && it.target == declaration.target })
        assertTrue(graph.edges.any { it.source == call.caller && it.origin == EdgeOrigin.DISPATCH_MODEL && it.target == declaration.target })
        for (format in listOf("json", "ndjson")) {
            val rendered = execute("graph", "--classes", classes.toString(), "--format", format)
            assertContains(rendered, "\"declaration\"")
            assertContains(rendered, "\"scope\": \"project\"")
            assertContains(rendered, "\"target\": \"${call.target.value}\"")
        }
        val excluded = execute("graph", "--classes", classes.toString(), "--format", "json", "--exclude-origin", "dispatchModel")
        assertContains(excluded, "\"declaration\"")
        assertFalse(excluded.contains("\"origin\": \"dispatchModel\""))
        val old = CodeGraph(graph.nodes.values, graph.edges, graph.externalCalls.map { it.copy(declaration = null) })
        val legacy = Files.writeString(root.resolve("legacy.json"), QuerySnapshotCodec.render(QuerySnapshot(old, emptyList(), emptyList())))
        val expected = execute("query", declaration.target.value, "--graph-file", legacy.toString())
        for (compact in listOf(false, true)) {
            val flags = if (compact) arrayOf("--compact") else emptyArray()
            val captured = execute("snapshot", "--classes", classes.toString(), "--project", root.toString(), *flags)
            assertEquals(call, QuerySnapshotCodec.parse(captured).graph.externalCalls.single { it.name == "value" })
            val saved = Files.writeString(root.resolve("snapshot-$compact.json"),
                QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact))
            val cli = execute("query", declaration.target.value, "--graph-file", saved.toString())
            assertEquals(expected, cli)
            val document = McpJsonCodec.parse(cli) as Map<*, *>
            McpTestClient { input, output, errors ->
                KartographCli.runWithInput(arrayOf("mcp", "--graph-file", saved.toString()), output, errors, input)
            }.use { client ->
                client.initialize()
                val result = client.call("query_symbol", mapOf("symbol" to declaration.target.value, "depth" to 1))
                assertEquals(false, result["isError"])
                val queried = (result["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
                assertEquals(document["result"], queried["result"])
            }
        }
    }

    private fun execute(vararg args: String): String {
        val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(args, PrintStream(output), PrintStream(errors)), errors.toString())
        return output.toString()
    }
}
