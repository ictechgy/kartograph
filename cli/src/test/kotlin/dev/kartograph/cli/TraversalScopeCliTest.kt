package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** language-traversal의 snapshot 신선도(`--input-bindings`) 계약이다. */
class TraversalScopeCliTest {
    private data class Execution(val status: Int, val output: String, val error: String)

    private fun run(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    /**
     * main 소스의 Client 구현은 RealClient 하나이고, test 소스의 FakeClient가 두 번째 구현이다. ScreenTest는 Screen.show에
     * fake를 넘긴다. main·test를 한 class root로 컴파일해 plugin의 unit-test 포함 snapshot과 같은 모양을 만든다.
     */
    private fun compile(root: Path, classes: Path) {
        mapOf(
            "src/main/java/p/Client.java" to "package p; public interface Client { void fetch(); }",
            "src/main/java/p/RealClient.java" to "package p; public class RealClient implements Client { public void fetch() { Http.get(); } }",
            "src/main/java/p/Http.java" to "package p; public class Http { static void get() {} }",
            "src/main/java/p/Screen.java" to "package p; public class Screen { void show(Client client) { client.fetch(); } }",
            "src/test/java/p/FakeClient.java" to "package p; public class FakeClient implements Client { public void fetch() { Http.get(); } }",
            "src/test/java/p/ScreenTest.java" to "package p; public class ScreenTest { void run() { new Screen().show(new FakeClient()); } }",
        ).forEach { (path, text) -> Files.createDirectories(root.resolve(path).parent); Files.writeString(root.resolve(path), text) }
        Files.createDirectories(classes)
        val sources = listOf("main", "test").flatMap { set -> root.resolve("src/$set/java/p").toFile().listFiles()!!.map { it.path } }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), *sources.toTypedArray()))
    }

    private fun capture(root: Path, vararg extra: String): Path {
        compile(root, root.resolve("classes"))
        val snapshot = run("snapshot", "--classes", root.resolve("classes").toString(), "--project", root.toString(), "--include-paths", *extra)
        assertEquals(0, snapshot.status, snapshot.error)
        return root.resolve("graph.json").also { Files.writeString(it, snapshot.output) }
    }

    /** javac witness가 class root를 증명하는 snapshot이다. 입력이 그대로면 신선도가 `matched`다. */
    private fun witnessed(root: Path): Path {
        compile(root, root.resolve("classes"))
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n")
        Files.writeString(root.resolve("compiler.jar"), "compiler artifact")
        fun fingerprint(role: String, path: String) = ContentFingerprint.capture(root, root.resolve(path), role, role)
        val witness = root.resolve("javac-witness.json")
        Files.writeString(witness, BuildWitnessCodec.render(BuildWitness("app:debug", "javac", "compileJava",
            listOf(fingerprint("sources", "src"), fingerprint("buildConfig", "build.gradle.kts"), fingerprint("compiler", "compiler.jar"),
                InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("-g")))),
            listOf(fingerprint("classes", "classes")))))
        val snapshot = run("snapshot", "--classes", root.resolve("classes").toString(), "--project", root.toString(), "--include-paths",
            "--scope", "app:debug", "--build-witness", witness.toString())
        assertEquals(0, snapshot.status, snapshot.error)
        return root.resolve("graph.json").also { Files.writeString(it, snapshot.output) }
    }

    private fun document(execution: Execution): Map<*, *> = McpJsonCodec.parse(execution.output) as Map<*, *>

    private fun evidence(execution: Execution): Map<String, Any?> = (document(execution)["reached"] as List<*>).map { it as Map<*, *> }
        .associate { (it["symbol"] as Map<*, *>)["usr"] as String to it["evidence"] }

    private fun limitations(execution: Execution): List<String> = (document(execution)["limitations"] as List<*>).map { it as String }

    private fun impact(graph: Path, root: Path, symbol: String, vararg extra: String) = run("impact", symbol, "--format", "language-traversal",
        "--graph-file", graph.toString(), "--project", root.toString(), "--generated-at", "2026-09-28T00:00:00Z", *extra)

    @Test
    fun `matched snapshots add no freshness limitation and changed inputs do`(@TempDir root: Path) {
        val graph = witnessed(root)
        val traversal = impact(graph, root, "method:p/Http#get()V")
        assertEquals(0, traversal.status, traversal.error)
        assertTrue(limitations(traversal).none { it.startsWith("graph-file-freshness") || it.startsWith("saved-graph") }, traversal.output)
        val routes = run("routes", "--role", "client", "--project", root.toString(), "--graph-file", graph.toString())
        assertEquals(0, routes.status, routes.error)
        assertFalse(routes.output.contains("graph-file-freshness"), routes.output)

        root.resolve("src/main/java/p/Http.java").toFile().appendText("\n// edited\n")
        val stale = impact(graph, root, "method:p/Http#get()V")
        assertEquals(0, stale.status, stale.error)
        assertTrue(limitations(stale).any { it.startsWith("graph-file-freshness-stale: ") && "changed-sources" in it }, stale.output)
    }

    @Test
    fun `input bindings reconnect traversal snapshot inputs outside the project`(@TempDir root: Path, @TempDir outside: Path) {
        compile(root, outside.resolve("classes"))
        val capture = run("snapshot", "--classes", outside.resolve("classes").toString(), "--project", root.toString())
        assertEquals(0, capture.status, capture.error)
        val graph = root.resolve("graph.json").also { Files.writeString(it, capture.output) }
        val slot = QuerySnapshotCodec.parse(capture.output).provenance!!.inputs.single { it.role == "classes" }.path
        val bindings = root.resolve("bindings.json").also {
            Files.writeString(it, ExternalInputBindingsCodec.render(mapOf(slot to outside.resolve("classes").toString())))
        }
        val unbound = impact(graph, root, "method:p/Http#get()V")
        assertTrue(limitations(unbound).contains("graph-file-freshness-unverified: missing-external-input"), unbound.output)
        listOf("impact", "reach").forEach { command ->
            val bound = run(command, "method:p/Http#get()V", *(if (command == "impact") arrayOf("--format", "language-traversal") else emptyArray()),
                "--graph-file", graph.toString(), "--project", root.toString(), "--input-bindings", bindings.toString())
            assertEquals(0, bound.status, bound.error)
            val freshness = limitations(bound).single { it.startsWith("graph-file-freshness") }
            assertFalse("missing-external-input" in freshness, freshness)
            assertContains(freshness, "missing-build-witness")
        }
        val missing = impact(graph, root, "method:p/Http#get()V", "--input-bindings", root.resolve("absent.json").toString())
        assertEquals(2, missing.status)
        assertContains(missing.error, "--input-bindings")
        assertEquals(64, impact(graph, root, "method:p/Http#get()V", "--input-bindings").status)
    }
}
