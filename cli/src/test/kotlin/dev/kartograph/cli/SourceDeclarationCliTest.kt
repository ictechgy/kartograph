package dev.kartograph.cli

import dev.kartograph.core.NodeId
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SourceDeclarationCliTest {
    @Test fun `declaration enrichment keeps baseline fingerprints based on original compiler paths`(@TempDir root: Path) {
        val directory = Files.createDirectory(root.resolve("src"))
        val source = Files.writeString(directory.resolve("BaselineExample.java"),
            "package sample;\npublic class BaselineExample {\n public static void work() { }\n}\n")
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val usr = "class:sample/BaselineExample"
        val baseline = Files.writeString(root.resolve("baseline.json"),
            dev.kartograph.export.BaselineCodec.renderFingerprints(listOf("dead|$usr|BaselineExample.java")))
        val input = arrayOf("--classes", classes.toString(), "--project", root.toString(), "--baseline", baseline.toString())
        val query = execute("query", usr, *input)
        assertContains(query, "\"suppressedByBaseline\": true")
        assertContains(query, "\"sourceDeclaration\"")
        val snapshot = QuerySnapshotCodec.parse(execute("snapshot", *input, "--include-paths"))
        assertContains(snapshot.suppressed, NodeId(usr))
        assertEquals("src/BaselineExample.java", snapshot.graph.nodes.getValue(NodeId(usr)).sourceDeclaration?.path)
    }

    @Test fun `live query graph and source independent saved query retain distinct declaration and execution points`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("Example.java"), "package sample;\npublic class Example {\n public static void work() {\n  System.out.println(1);\n }\n}\n")
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val input = arrayOf("--classes", classes.toString(), "--project", root.toString())
        val usr = "method:sample/Example#work()V"
        val graph = execute("graph", *input, "--format", "json", "--include-paths")
        assertContains(graph, "\"sourceDeclaration\"")
        val live = execute("query", usr, *input)
        assertContains(live, "\"sourceDeclaration\"")
        assertContains(live, "\"line\": 3")
        assertContains(live, "\"line\": 4")
        listOf(false, true).forEach { compact ->
            val flags = if (compact) arrayOf("--compact") else emptyArray()
            val encoded = execute("snapshot", *input, "--include-paths", *flags)
            val snapshot = QuerySnapshotCodec.parse(encoded)
            val node = snapshot.graph.nodes.getValue(NodeId(usr))
            assertEquals(4, node.location?.line)
            assertEquals(3, node.sourceDeclaration?.line)
            val saved = Files.writeString(root.resolve("snapshot-$compact.json"), encoded)
            Files.move(source, root.resolve("source-held.java.txt"))
            try {
                val query = execute("query", usr, "--graph-file", saved.toString())
                assertContains(query, "\"sourceDeclaration\"")
                assertContains(query, "\"sourceSha256\": \"${node.sourceDeclaration?.sourceSha256}\"")
            } finally {
                Files.move(root.resolve("source-held.java.txt"), source)
            }
        }
    }

    private fun execute(vararg args: String): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val status = KartographCli.run(args.toList().toTypedArray(), PrintStream(output), PrintStream(errors))
        assertEquals(0, status, errors.toString(Charsets.UTF_8))
        return output.toString(Charsets.UTF_8)
    }
}
