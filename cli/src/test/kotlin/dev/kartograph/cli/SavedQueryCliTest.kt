package dev.kartograph.cli

import dev.kartograph.core.Finding
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class SavedQueryCliTest {
    @Test
    fun `snapshot preserves private member entry policy baseline and ambiguous candidates`(@TempDir root: Path) {
        val source = root.resolve("Entry.java").apply { writeText("""
            public class Entry {
                public static void main(String[] args) {}
                public void external() { used(); }
                private void used() {}
                private void unused() {}
            }
        """.trimIndent()) }
        val a = root.resolve("a/Same.java").apply { parent.createDirectories(); writeText("package a; public class Same {}") }
        val b = root.resolve("b/Same.java").apply { parent.createDirectories(); writeText("package b; public class Same {}") }
        val classes = root.resolve("classes").createDirectories()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, "-g", "-d", classes.toString(),
            source.toString(), a.toString(), b.toString()))
        root.resolve("keep.pro").writeText("-keep class Entry\n")
        root.resolve("baseline.json").writeText(BaselineCodec.render(listOf(
            Finding(NodeId("method:Entry#unused()V"), SourceLocation("Entry.java")))))
        val args = arrayOf("--classes", classes.toString(), "--project", root.toString(), "--keep-rules", "keep.pro",
            "--baseline", "baseline.json", "--include-private-members")
        val capture = execute("snapshot", *args)
        assertEquals(0, capture.status, capture.error)
        assertEquals(capture.output, execute("snapshot", *args).output)
        val snapshot = root.resolve("snapshot.json").apply { writeText(capture.output) }
        assertContains(execute("query", "method:Entry#used()V", "--graph-file", snapshot.toString()).output, "\"state\": \"reachable\"")
        assertContains(execute("query", "method:Entry#unused()V", "--graph-file", snapshot.toString()).output,
            "\"suppressedByBaseline\": true")
        val ambiguous = execute("query", "Same", "--graph-file", snapshot.toString(), "--limit", "1")
        assertEquals(64, ambiguous.status)
        assertContains(ambiguous.output, "\"status\": \"ambiguous\"")
        assertContains(ambiguous.output, "class:a/Same")
        assertContains(ambiguous.output, "class:b/Same")
    }

    @Test
    fun `saved query preserves retention paths and missing status after all live inputs are removed`(@TempDir root: Path) {
        val source = root.resolve("Entry.java").apply {
            writeText("public class Entry { public static void main(String[] args) { new Used(); } } class Used {} class Unused {}")
        }
        val classes = root.resolve("classes").createDirectories()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val keep = root.resolve("keep.pro").apply { writeText("-keep class Entry { *; }\n") }
        val inputs = arrayOf("--classes", classes.toString(), "--project", root.toString(), "--keep-rules", "keep.pro")
        val live = execute("query", "Used", *inputs)
        assertEquals(0, live.status)
        val captured = execute("snapshot", *inputs)
        assertEquals(0, captured.status)
        val snapshot = root.resolve("snapshot.json").apply { writeText(captured.output) }
        Files.walk(classes).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        Files.delete(source)
        Files.delete(keep)

        val saved = execute("query", "Used", "--graph-file", snapshot.toString())
        assertEquals(0, saved.status)
        assertContains(saved.output, "\"state\": \"reachable\"")
        assertContains(saved.output, "saved-graph:")
        assertContains(saved.output, "Entry")
        assertContains(execute("query", "Entry", "--graph-file", snapshot.toString()).output, "\"reason\": \"keepRule\"")
        assertContains(execute("query", "Unused", "--graph-file", snapshot.toString()).output, "\"state\": \"unreachable\"")
        val missing = execute("query", "Missing", "--graph-file", snapshot.toString())
        assertEquals(64, missing.status)
        assertContains(missing.output, "\"status\": \"notFound\"")
        assertContains(missing.output, "saved-graph:")
        assertEquals(64, execute("query", "Used", "--graph-file", snapshot.toString(), "--classes", "missing").status)
        assertEquals(64, execute("query", "Used", "--graph-file", snapshot.toString(), "--baseline", "missing").status)
        assertEquals(64, execute("snapshot", *inputs, "--depth", "2").status)
    }

    @Test
    fun `missing or malformed saved graph fails without echoing content`(@TempDir root: Path) {
        val file = root.resolve("graph.json").apply { writeText("{\"private-value\":\"do-not-echo\"}") }
        val malformed = execute("query", "A", "--graph-file", file.toString())
        assertEquals(2, malformed.status)
        assertEquals(false, malformed.error.contains("do-not-echo"))
        assertEquals(false, malformed.error.contains(root.toString()))
        assertEquals(2, execute("query", "A", "--graph-file", root.resolve("missing.json").toString()).status)
    }

    @Test
    fun `invalid UTF8 is rejected rather than replacing snapshot values`(@TempDir root: Path) {
        val valid = QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(),
            toolVersion = "CORRUPTED_VALUE"))
        val parts = valid.split("CORRUPTED_VALUE")
        val file = root.resolve("corrupt.json").apply {
            writeBytes(parts[0].toByteArray() + byteArrayOf(0xff.toByte()) + parts[1].toByteArray())
        }
        assertEquals(2, execute("query", "Missing", "--graph-file", file.toString()).status)
    }

    private data class Result(val status: Int, val output: String, val error: String)
    private fun execute(vararg args: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(output), PrintStream(error))
        return Result(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }
}
