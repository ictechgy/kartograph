package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** language-traversal의 테스트 소스 제외(`--include-tests`)와 snapshot 신선도(`--input-bindings`) 계약이다. */
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
    fun `test sources are excluded by default and bound dispatch counts production implementations`(@TempDir root: Path) {
        val graph = capture(root)
        val production = impact(graph, root, "method:p/Http#get()V")
        assertEquals(0, production.status, production.error)
        assertEquals(mapOf("method:p/RealClient#fetch()V" to "direct", "method:p/Client#fetch()V" to "bound",
            "method:p/Screen#show(Lp/Client;)V" to "bound"), evidence(production))
        assertTrue(limitations(production).any { it.startsWith("test-sources-excluded: 6 test-source declaration(s)") }, production.output)

        val included = impact(graph, root, "method:p/Http#get()V", "--include-tests")
        assertEquals(0, included.status, included.error)
        val all = evidence(included)
        assertEquals("candidate", all["method:p/Client#fetch()V"], "the test fake is a second implementation")
        assertTrue("method:p/ScreenTest#run()V" in all && "method:p/FakeClient#fetch()V" in all)
        assertTrue(limitations(included).none { it.startsWith("test-sources-") })

        val forward = run("reach", "method:p/Screen#show(Lp/Client;)V", "--graph-file", graph.toString(), "--project", root.toString())
        assertEquals(0, forward.status, forward.error)
        assertEquals("bound", evidence(forward)["method:p/RealClient#fetch()V"])
        assertFalse("method:p/FakeClient#fetch()V" in evidence(forward))
        assertEquals(document(production)["graphRevision"], document(forward)["graphRevision"], "both directions traverse one graph")
        assertTrue(document(production)["graphRevision"] != document(included)["graphRevision"], "including tests traverses another graph")
    }

    @Test
    fun `a test-source root traverses test sources`(@TempDir root: Path) {
        val graph = capture(root)
        val execution = impact(graph, root, "method:p/FakeClient#fetch()V")
        assertEquals(0, execution.status, execution.error)
        assertEquals("candidate", evidence(execution)["method:p/Client#fetch()V"])
        assertTrue("method:p/ScreenTest#run()V" in evidence(execution))
        assertTrue(limitations(execution).any { it.startsWith("test-sources-included:") && it.endsWith("1 root(s) are test-source declarations") })
    }

    @Test
    fun `name roots choose the program like exact roots and ambiguous or stale roots stay unresolved`(@TempDir root: Path) {
        val graph = capture(root)
        // 한정 이름으로 고른 테스트 소스 선언도 테스트 root다.
        val testRoot = impact(graph, root, "p.FakeClient.fetch")
        assertEquals(0, testRoot.status, testRoot.error)
        assertTrue(limitations(testRoot).any { it.startsWith("test-sources-included:") && it.endsWith("1 root(s) are test-source declarations") })
        assertEquals("candidate", evidence(testRoot)["method:p/Client#fetch()V"])
        // production 선언의 한정 이름은 정확한 usr와 같은 범위·결과를 낸다.
        val byName = impact(graph, root, "p.Http.get")
        assertEquals(0, byName.status, byName.error)
        assertEquals(evidence(impact(graph, root, "method:p/Http#get()V")), evidence(byName))
        assertTrue(limitations(byName).any { it.startsWith("test-sources-excluded:") })
        // 테스트·production에 함께 있는 이름과 없는 usr는 해석하지 않고 원문 그대로 root-not-found로 남긴다.
        val unresolved = impact(graph, root, "fetch", "--symbol", "method:p/Gone#run()V")
        assertEquals(64, unresolved.status)
        val roots = (document(unresolved)["roots"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("fetch", "method:p/Gone#run()V"), roots.map { it["id"] })
        assertTrue(roots.none { "symbol" in it })
        assertTrue(limitations(unresolved).any { it.startsWith("root-not-found: 2 requested root(s)") })
        assertTrue(limitations(unresolved).any { it.startsWith("test-sources-excluded:") }, "unresolved roots are not test roots")
    }

    @Test
    fun `inexact roots are resolved against one name index`(@TempDir root: Path) {
        // 리뷰 지적 재현(perf#4·sec#3): 정확한 usr가 아닌 root마다 전체 정점을 훑으며 qualifiedName을 새로 만들었고, 테스트
        // 소스가 있으면 범위 결정이 같은 스캔을 한 번 더 해 비용이 (요청 수 × 정점 수 × 2)였다. 시간 대신 스레드 할당량으로 본다.
        val threads = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        assumeTrue(threads != null, "thread allocation accounting is unavailable on this JVM")
        fun node(name: String, sourceSet: String) = GraphNode(NodeId("method:p/$name#run()V"), "run", NodeKind.METHOD,
            location = SourceLocation("app/src/$sourceSet/kotlin/p/$name.kt"))
        val nodes = List(NODES) { node("P$it", "main") } + List(NODES / 100) { node("T$it", "test") }
        val graph = root.resolve("graph.json").also {
            Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(nodes, emptyList()), emptyList(), emptyList())))
        }
        fun allocated(stale: Int): Long {
            val requested = List(stale) { "method:p/Gone$it#run()V" } + "method:p/P0#run()V"
            val roots = root.resolve("roots-$stale.json").also { Files.writeString(it, requested.joinToString(", ", "[", "]") { "\"$it\"" }) }
            val arguments = arrayOf("impact", "--format", "language-traversal", "--roots-from", roots.toString(), "--graph-file", graph.toString(),
                "--project", root.toString(), "--generated-at", "2026-10-18T00:00:00Z")
            // 첫 실행의 클래스 적재 할당을 빼려고 한 번 먼저 돌린다.
            run(*arguments)
            val before = threads!!.currentThreadAllocatedBytes
            val execution = run(*arguments)
            val bytes = threads.currentThreadAllocatedBytes - before
            assertEquals(64, execution.status, execution.error)
            assertTrue(limitations(execution).any { it.startsWith("root-not-found: $stale requested root(s)") })
            assertTrue(limitations(execution).any { it.startsWith("test-sources-excluded:") })
            return bytes
        }
        val cells = (MANY_STALE - FEW_STALE).toLong() * nodes.size
        val extra = allocated(MANY_STALE) - allocated(FEW_STALE)
        assertTrue(extra < cells * 2, "resolving ${MANY_STALE - FEW_STALE} more stale roots allocated $extra bytes over ${nodes.size} vertices")
    }

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
        val duplicate = impact(graph, root, "method:p/Http#get()V", "--include-tests", "--include-tests")
        assertEquals(64, duplicate.status)
        assertEquals(64, impact(graph, root, "method:p/Http#get()V", "--input-bindings").status)
    }

    private companion object {
        /** 할당량 비교 그래프의 production 정점 수와 두 stale root 수다. 테스트 소스 정점은 production의 1%다. */
        const val NODES = 5_000
        const val MANY_STALE = 2_000
        const val FEW_STALE = 20
    }
}
