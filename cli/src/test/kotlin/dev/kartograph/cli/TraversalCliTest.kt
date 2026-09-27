package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `impact --format language-traversal`과 `reach`의 교환 문서·옵션·호환 계약이다. */
class TraversalCliTest {
    private data class Execution(val status: Int, val output: String, val error: String)

    private fun run(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    /**
     * 합성 Java 프로젝트다. Http.get(root)을 RealApi.call이 부르고, 화면 Screen.render 안의 익명 Runnable이
     * Api.call(구현 하나)을 부른다. 공통 Ui.button은 Runnable.run으로 모든 익명 Runnable에 dispatch한다.
     */
    private fun capture(root: Path): Path {
        val source = Files.createDirectories(root.resolve("src/p"))
        mapOf(
            "Api.java" to "package p; interface Api { void call(); }",
            "RealApi.java" to "package p; class RealApi implements Api { public void call() { Http.get(); } }",
            "Http.java" to "package p; class Http { static void get() {} }",
            "Screen.java" to "package p; class Screen { void render(final Api api) { Runnable r = new Runnable() { public void run() { api.call(); } }; Ui.button(r); } }",
            "Ui.java" to "package p; class Ui { static void button(Runnable r) { r.run(); } }",
            "Other.java" to "package p; class Other { void show() { Ui.button(new Runnable() { public void run() {} }); } }",
            "Main.java" to "package p; public class Main { public static void main(String[] a) { new Screen().render(new RealApi()); } }",
        ).forEach { (name, text) -> source.resolve(name).toFile().writeText(text) }
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(),
            *source.toFile().listFiles()!!.map { it.path }.toTypedArray()))
        val snapshot = run("snapshot", "--classes", classes.toString(), "--project", root.toString(), "--include-paths")
        assertEquals(0, snapshot.status, snapshot.error)
        return root.resolve("graph.json").also { Files.writeString(it, snapshot.output) }
    }

    private fun document(execution: Execution): Map<*, *> = McpJsonCodec.parse(execution.output) as Map<*, *>

    private fun reached(document: Map<*, *>): Map<String, Map<*, *>> = (document["reached"] as List<*>).map { it as Map<*, *> }
        .associateBy { ((it["symbol"] as Map<*, *>)["usr"] as String) }

    @Test
    fun `reverse traversal follows lexical containment and labels bound dispatch`(@TempDir root: Path) {
        val graph = capture(root)
        val execution = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", graph.toString(),
            "--project", root.toString(), "--generated-at", "2026-09-27T00:00:00Z")
        assertEquals(0, execution.status, execution.error)
        val document = document(execution)
        assertEquals("language-traversal", document["format"])
        assertEquals("dependents", document["direction"])
        assertEquals("candidates", document["dispatch"])
        assertEquals(root.toRealPath().toString(), document["project"])
        assertEquals("2026-09-27T00:00:00.000Z", document["generatedAt"])
        val rows = reached(document)
        assertEquals("direct", rows.getValue("method:p/RealApi#call()V")["evidence"])
        assertEquals("bound", rows.getValue("method:p/Api#call()V")["evidence"])
        val render = rows.getValue("method:p/Screen#render(Lp/Api;)V")
        assertEquals(listOf("contains"), render["relationships"])
        assertEquals("bound", render["evidence"])
        assertTrue("method:p/Main#main([Ljava/lang/String;)V" in rows)
        // Ui.button은 Screen이 넘긴 익명 Runnable을 실행하므로 콜백 흐름으로 닿는다. fan-out이 아니므로 Ui.button을
        // 부르는 다른 화면(Other.show)으로는 퍼지지 않는다.
        val button = rows.getValue("method:p/Ui#button(Ljava/lang/Runnable;)V")
        assertEquals(listOf("callback"), button["relationships"])
        assertEquals("bound", button["evidence"])
        assertFalse("method:p/Other#show()V" in rows, "callback callers must not spread to their other callers")
        assertTrue((document["limitations"] as List<*>).any { (it as String).startsWith("callback-flow: 2 bound") })
        assertTrue((document["limitations"] as List<*>).any { (it as String).startsWith("lambda-dispatch-excluded:") })
        val again = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", graph.toString(),
            "--project", root.toString(), "--generated-at", "2026-09-27T00:00:00Z")
        assertEquals(execution.output, again.output)
    }

    @Test
    fun `dispatch modes nest and all follows callback fan-out`(@TempDir root: Path) {
        val graph = capture(root)
        fun count(mode: String) = reached(document(run("impact", "method:p/Http#get()V", "--format", "language-traversal",
            "--graph-file", graph.toString(), "--project", root.toString(), "--dispatch", mode))).keys
        val direct = count("direct")
        val bound = count("bound")
        val candidates = count("candidates")
        val all = count("all")
        assertTrue(direct.containsAll(listOf("method:p/RealApi#call()V")) && "method:p/Api#call()V" !in direct)
        assertTrue(bound.containsAll(direct) && candidates.containsAll(bound) && all.containsAll(candidates))
        assertTrue("method:p/Other#show()V" in all)
    }

    @Test
    fun `reach emits dependencies with multi-root attribution`(@TempDir root: Path) {
        val graph = capture(root)
        val execution = run("reach", "method:p/Main#main([Ljava/lang/String;)V", "--symbol", "method:p/Screen#render(Lp/Api;)V",
            "--graph-file", graph.toString(), "--project", root.toString())
        assertEquals(0, execution.status, execution.error)
        val document = document(execution)
        assertEquals("dependencies", document["direction"])
        val rows = reached(document)
        val render = rows.getValue("method:p/Screen#render(Lp/Api;)V")
        assertEquals(listOf(0L), render["roots"], "a root reached from another root lists only the other root")
        assertEquals(listOf(0L, 1L), rows.getValue("method:p/Http#get()V")["roots"])
    }

    @Test
    fun `roots come from bridge facts and unknown roots stay listed`(@TempDir root: Path) {
        val graph = capture(root)
        val facts = root.resolve("routes.json").also { Files.writeString(it, """{"format": "bridge-facts", "facts": [
            {"kind": "route-call", "symbol": {"qualifiedName": "p.Http.get", "usr": "method:p/Http#get()V"}},
            {"kind": "route-call", "symbol": {"qualifiedName": "p.Gone.run", "usr": "method:p/Gone#run()V"}},
            {"kind": "route-call"}]}""") }
        val execution = run("impact", "--format", "language-traversal", "--roots-from", facts.toString(), "--graph-file", graph.toString(),
            "--project", root.toString())
        assertEquals(64, execution.status)
        val document = document(execution)
        val roots = (document["roots"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("method:p/Http#get()V", "method:p/Gone#run()V"), roots.map { it["id"] })
        assertFalse("symbol" in roots[1])
        assertEquals(true, document["truncated"])
        assertEquals(listOf("root-not-found"), document["truncationReasons"])
    }

    @Test
    fun `snapshots without enclosure facts fall back to candidate lambda edges`(@TempDir root: Path) {
        val graph = capture(root)
        val legacy = root.resolve("legacy.json").also {
            Files.writeString(it, Files.readString(graph).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), ""))
        }
        assertFalse(Files.readString(legacy).contains("\"enclosures\""))
        val execution = run("impact", "method:p/Http#get()V", "--format", "language-traversal", "--graph-file", legacy.toString(),
            "--project", root.toString())
        assertEquals(0, execution.status, execution.error)
        assertContains(execution.output, "lexical-enclosures-unavailable:")
        assertTrue("method:p/Ui#button(Ljava/lang/Runnable;)V" in reached(document(execution)))
    }

    @Test
    fun `default impact output is unchanged by enclosure facts`(@TempDir root: Path) {
        val graph = capture(root)
        val legacy = root.resolve("legacy.json").also {
            Files.writeString(it, Files.readString(graph).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), ""))
        }
        val current = run("impact", "method:p/Http#get()V", "--graph-file", graph.toString(), "--all")
        val old = run("impact", "method:p/Http#get()V", "--graph-file", legacy.toString(), "--all")
        assertEquals(0, current.status, current.error)
        assertEquals(old.output, current.output)
        assertEquals(current.output, run("impact", "method:p/Http#get()V", "--graph-file", graph.toString(), "--all", "--format", "json").output)
        assertContains(current.output, "\"format\": \"kartograph-impact\"")
    }

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString(), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.com",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    private fun traverse(graph: Path, root: Path, vararg extra: String): Execution = run("impact", "method:p/Http#get()V",
        "--format", "language-traversal", "--graph-file", graph.toString(), "--project", root.toString(),
        "--generated-at", "2026-09-27T00:00:00Z", *extra)

    @Test
    fun `revision comes from the option or the snapshot label and conflicting labels fail`(@TempDir root: Path) {
        val graph = capture(root)
        assertFalse("revision" in document(traverse(graph, root)), "no repository and no label leaves revision unknown")
        val given = traverse(graph, root, "--revision", "release-1.2")
        assertEquals(0, given.status, given.error)
        assertEquals("release-1.2", document(given)["revision"])
        val label = "0123456789abcdef0123456789abcdef01234567"
        val labeled = root.resolve("labeled.json").also {
            val snapshot = run("snapshot", "--classes", root.resolve("classes").toString(), "--project", root.toString(),
                "--include-paths", "--revision", label)
            assertEquals(0, snapshot.status, snapshot.error)
            Files.writeString(it, snapshot.output)
        }
        assertEquals(label, document(traverse(labeled, root))["revision"])
        assertEquals(label, document(traverse(labeled, root, "--revision", label))["revision"])
        val conflict = traverse(labeled, root, "--revision", "release-1.2")
        assertEquals(2, conflict.status)
        assertContains(conflict.error, "does not match the snapshot's revision label")
    }

    @Test
    fun `a clean git HEAD becomes the revision and a dirty project omits it`(@TempDir root: Path) {
        val graph = capture(root)
        Files.writeString(root.resolve(".gitignore"), "classes/\n*.json\n")
        git(root, "init", "-q")
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", "fixture")
        val head = git(root, "rev-parse", "HEAD")
        val clean = traverse(graph, root)
        assertEquals(0, clean.status, clean.error)
        assertEquals(head, document(clean)["revision"])
        assertEquals("pinned", document(traverse(graph, root, "--revision", "pinned"))["revision"], "--revision wins over HEAD")
        Files.writeString(root.resolve("notes.txt"), "untracked")
        assertFalse("revision" in document(traverse(graph, root)), "an untracked file makes HEAD unreliable")
        Files.delete(root.resolve("notes.txt"))
        root.resolve("src/p/Http.java").toFile().appendText("\n// edited\n")
        assertFalse("revision" in document(traverse(graph, root)), "an uncommitted edit makes HEAD unreliable")
        git(root, "checkout", "-q", "--", "src/p/Http.java")
        assertEquals(head, document(traverse(graph, root))["revision"])
        val nested = Files.createDirectories(root.resolve("sub"))
        Files.writeString(nested.resolve("Keep.txt"), "tracked")
        git(root, "add", "sub/Keep.txt")
        git(root, "commit", "-q", "-m", "sub")
        root.resolve("src/p/Http.java").toFile().appendText("\n// outside sub\n")
        assertEquals(git(root, "rev-parse", "HEAD"), document(traverse(graph, nested))["revision"],
            "changes outside the project directory do not affect its revision")
    }

    @Test
    fun `graph revision is shared by both directions and ignores snapshot layout`(@TempDir root: Path) {
        val graph = capture(root)
        val reverse = document(traverse(graph, root))["graphRevision"] as String
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(reverse), reverse)
        val forward = run("reach", "method:p/Main#main([Ljava/lang/String;)V", "--graph-file", graph.toString(), "--project", root.toString())
        assertEquals(0, forward.status, forward.error)
        assertEquals(reverse, document(forward)["graphRevision"])
        listOf(listOf("--compact"), emptyList()).forEach { layout ->
            val other = run("snapshot", "--classes", root.resolve("classes").toString(), "--project", root.toString(), *layout.toTypedArray())
            assertEquals(0, other.status, other.error)
            val file = root.resolve("layout.json").also { Files.writeString(it, other.output) }
            assertNotEquals(Files.readString(graph), other.output)
            assertEquals(reverse, document(traverse(file, root))["graphRevision"], "layout $layout")
        }
        val legacy = root.resolve("legacy.json").also {
            Files.writeString(it, Files.readString(graph).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), ""))
        }
        assertNotEquals(reverse, document(traverse(legacy, root))["graphRevision"], "legacy snapshots traverse a different graph")
    }

    @Test
    fun `traversal usage errors return 64`(@TempDir root: Path) {
        val graph = root.resolve("graph.json").also { Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(
            CodeGraph(listOf(GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD)), emptyList()), emptyList(), emptyList()))) }
        val base = arrayOf("impact", "method:p/A#run()V", "--format", "language-traversal", "--graph-file", graph.toString())
        val project = arrayOf("--project", root.toString())
        listOf(
            arrayOf(*base) to "missing required --project",
            arrayOf(*base, *project, "--base-graph", graph.toString()) to "not supported with --format language-traversal",
            arrayOf(*base, *project, "--all") to "not supported with --format language-traversal",
            arrayOf(*base, *project, "--dispatch", "maybe") to "dispatch must be",
            arrayOf(*base, *project, "--depth", "129") to "depth must be 1..128",
            arrayOf(*base, *project, "--generated-at", "yesterday") to "--generated-at",
            arrayOf(*base, *project, "--revision", "") to "--revision must be non-empty",
            arrayOf(*base, *project, "--revision", "abc\u0001") to "--revision must be non-empty",
            arrayOf(*base, *project, "--revision", "abc\u0085") to "--revision must be non-empty",
            arrayOf(*base, *project, "--revision", "abc\u2028") to "--revision must be non-empty",
            arrayOf(*base, *project, "--symbol", "method:p/B#run()V\u2029") to "invalid symbol",
            arrayOf(*base, *project, "--symbol", "method:p/B#run()V\u007f") to "invalid symbol",
            arrayOf("impact", "method:p/A#run()V", "--format", "text", "--graph-file", graph.toString()) to "invalid impact format",
            arrayOf("reach", "method:p/A#run()V", "--format", "json", "--graph-file", graph.toString(), *project) to "supports only",
        ).forEach { (arguments, message) ->
            val execution = run(*arguments)
            assertEquals(64, execution.status, arguments.joinToString(" "))
            assertContains(execution.error, message)
        }
        assertEquals(2, run("reach", "method:p/A#run()V", "--graph-file", root.resolve("absent.json").toString(), *project).status)
        assertContains(run("reach", "--help").output, "Dispatch modes")
        assertContains(run("--help").output, "kartograph reach")
    }
}
