package dev.kartograph.cli

import dev.kartograph.core.Finding
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SavedQueryCliTest {
    @Test
    fun `saved queries distinguish uncaptured captured-empty and positioned compiler calls`(@TempDir root: Path) {
        val caller = GraphNode(NodeId("method:demo/Caller#use()V"), "use", NodeKind.METHOD,
            location = SourceLocation("Caller.java", 1))
        val target = GraphNode(NodeId("method:demo/Target#hit()V"), "hit", NodeKind.METHOD,
            location = SourceLocation("Target.java", 1))
        val base = CodeGraph(listOf(caller, target), listOf(
            GraphEdge(caller.id, target.id, EdgeKind.CALL, origin = EdgeOrigin.BYTECODE, callSiteLines = listOf(5)),
        ))
        val positioned = base.withCompilerCallPositions(listOf(
            LocatedCompilerReference(caller.id, target.id,
                CompilerEvidenceSource("src/main/java/demo/Caller.java", "a".repeat(64)),
                "javac-constants", "17.0.20+0", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
                63, 66, 1, 64),
        ))
        for (compact in listOf(false, true)) {
            val file = root.resolve("positioned-$compact.json")
            Files.writeString(file, QuerySnapshotCodec.render(QuerySnapshot(positioned, emptyList(), listOf(
                "compiler-call-positions-uncovered-roots: 1",
                "compiler-call-positions-source-unverified: 1",
            )), compact))
            val found = execute("query", target.id.value, "--graph-file", file.toString())
            assertEquals(0, found.status, found.error)
            assertContains(found.output, "\"origin\": \"bytecode\"")
            assertContains(found.output, "\"origin\": \"compiler\"")
            assertContains(found.output, "\"coordinateBasis\": \"javacUtf16CharSequence\"")
            assertContains(found.output, "\"collector\": \"javac-constants\"")
            assertContains(found.output, "\"compilerVersion\": \"17.0.20+0\"")
            assertContains(found.output, "compiler-call-positions-uncovered-roots: 1")
            assertContains(found.output, "compiler-call-positions-source-unverified: 1")
            assertEquals(-1, found.output.indexOf("compiler-call-positions: saved graph has no captured"))
            assertTrue(found.output.indexOf("\"origin\": \"bytecode\"") < found.output.indexOf("\"origin\": \"compiler\""))

            val emptyFile = root.resolve("captured-empty-$compact.json")
            Files.writeString(emptyFile, QuerySnapshotCodec.render(QuerySnapshot(
                base.withCompilerCallPositions(emptyList()), emptyList(), listOf("compiler-call-positions-unmapped: 2"),
            ), compact))
            val empty = execute("query", target.id.value, "--graph-file", emptyFile.toString())
            assertContains(empty.output, "compiler-call-positions-unmapped: 2")
            assertEquals(-1, empty.output.indexOf("compiler-call-positions: saved graph has no captured"))
        }

        val twin = GraphNode(NodeId("method:other/Target#hit()V"), "hit", NodeKind.METHOD)
        val partial = CodeGraph(base.nodes.values + twin, base.edges)
            .withCompilerCallPositions(positioned.locatedCompilerReferences)
        val partialFile = root.resolve("partial-statuses.json").apply { writeText(QuerySnapshotCodec.render(QuerySnapshot(
            partial, emptyList(), listOf("compiler-call-positions-uncovered-roots: 1"),
        ))) }
        for (requested in listOf("hit", "Missing")) {
            val result = execute("query", requested, "--graph-file", partialFile.toString())
            assertEquals(64, result.status, result.error)
            assertContains(result.output, "compiler-call-positions-uncovered-roots: 1")
            assertEquals(-1, result.output.indexOf("compiler-call-positions: saved graph has no captured"))
        }

        val uncaptured = QuerySnapshot(CodeGraph(base.nodes.values + twin, base.edges), emptyList(), emptyList())
        val file = root.resolve("uncaptured.json").apply { writeText(QuerySnapshotCodec.render(uncaptured)) }
        for ((requested, expectedStatus) in listOf(target.id.value to 0, "hit" to 64, "Missing" to 64)) {
            val result = execute("query", requested, "--graph-file", file.toString())
            assertEquals(expectedStatus, result.status, result.error)
            assertContains(result.output,
                "compiler-call-positions: saved graph has no captured compiler selector positions; exact offsets and columns are unavailable")
        }
    }

    @Test
    fun `saved legacy graph declares unavailable call-site capture in either encoding`(@TempDir root: Path) {
        val target = dev.kartograph.core.GraphNode(NodeId("method:Entry#used()V"), "used", dev.kartograph.core.NodeKind.METHOD)
        val legacy = QuerySnapshot(CodeGraph(listOf(target), emptyList()), emptyList(), emptyList(),
            callSiteLinesCaptured = false)
        for (compact in listOf(false, true)) {
            val file = root.resolve("legacy-$compact.json").apply { writeText(QuerySnapshotCodec.render(legacy, compact)) }
            val result = execute("query", target.id.value, "--graph-file", file.toString())
            assertEquals(0, result.status, result.error)
            assertContains(result.output, "call-site-lines: saved graph predates direct call-site evidence")
        }
    }

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
