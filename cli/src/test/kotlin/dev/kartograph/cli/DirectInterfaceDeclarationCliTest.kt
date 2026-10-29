package dev.kartograph.cli

import dev.kartograph.core.*
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

class DirectInterfaceDeclarationCliTest {
    @Test
    fun `CLI exports and MCP replay preserve direct interface proof without changing queries`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("Entry.java"), """
            package p;
            public class Entry {
                public interface Contract { int value(); }
                public static class Implementation implements Contract { public int value() { return 11; } }
                public static int run(Contract value) { return value.value(); }
            }
        """.trimIndent())
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()))
        val dependency = Files.createDirectories(root.resolve("dependency/p"))
        Files.move(classes.resolve("p/Entry\$Contract.class"), dependency.resolve("Entry\$Contract.class"))
        val classpath = dependency.parent
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(classpath)).graph
        val call = graph.externalCalls.single { it.kind == InvocationKind.INTERFACE }
        assertEquals(CallDeclaration(call.target, CallDeclarationScope.EXTERNAL, listOf("p/Entry\$Contract")), call.declaration)
        val body = "method:p/Entry\$Implementation#value()I"
        assertEquals(listOf(NodeId(body)), call.resolvedTargets)
        for (format in listOf("json", "ndjson")) {
            val rendered = execute("graph", "--classes", classes.toString(), "--classpath", classpath.toString(), "--format", format)
            assertContains(rendered, "\"declaration\": {\"ownerChain\": [\"p/Entry\$Contract\"]")
            assertContains(rendered, "\"scope\": \"external\"")
            assertContains(rendered, "\"kind\": \"interface\"")
        }
        val legacy = CodeGraph(graph.nodes.values, graph.edges, graph.externalCalls.map { it.copy(declaration = null) })
        val old = Files.writeString(root.resolve("legacy.json"), QuerySnapshotCodec.render(QuerySnapshot(legacy, emptyList(), emptyList())))
        val expected = execute("query", body, "--graph-file", old.toString())
        for (compact in listOf(false, true)) {
            val captured = execute("snapshot", "--classes", classes.toString(), "--classpath", classpath.toString(),
                "--project", root.toString(), *if (compact) arrayOf("--compact") else emptyArray())
            assertEquals(call, QuerySnapshotCodec.parse(captured).graph.externalCalls.single { it.kind == InvocationKind.INTERFACE })
            val saved = Files.writeString(root.resolve("snapshot-$compact.json"), captured)
            val cli = execute("query", body, "--graph-file", saved.toString())
            // 입력 provenance가 다를 수 있어 relation 결과만 비교한다.
            val expectedResult = (McpJsonCodec.parse(expected) as Map<*, *>)["result"]
            val result = (McpJsonCodec.parse(cli) as Map<*, *>)["result"]
            assertEquals(expectedResult, result)
            McpTestClient { input, output, errors ->
                KartographCli.runWithInput(arrayOf("mcp", "--graph-file", saved.toString()), output, errors, input)
            }.use { client ->
                client.initialize()
                val queried = client.call("query_symbol", mapOf("symbol" to body, "depth" to 1))
                assertEquals(false, queried["isError"])
                assertEquals(result, ((queried["structuredContent"] as Map<*, *>)["document"] as Map<*, *>)["result"])
            }
        }
    }

    private fun execute(vararg args: String): String {
        val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(args, PrintStream(output), PrintStream(errors)), errors.toString())
        return output.toString()
    }
}
