package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** routes가 usr를 붙이지 못한 사실을 `missing-route-usrs:`로 세고 `--project` 불일치를 밝히는지 확인한다. */
class RouteSymbolDiagnosticsTest {
    @TempDir
    lateinit var project: Path

    private val sourcePath = "app/src/main/kotlin/dev/example/net/Client.kt"

    private fun writeClient() {
        val file = project.resolve(sourcePath)
        file.parent.createDirectories()
        file.writeText(
            """
            package dev.example.net

            class Client {
                fun load() {
                    java.net.URL("https://api.example.com/api/items").openConnection()
                }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun snapshot(path: String): Path {
        val node = GraphNode(NodeId("method:dev/example/net/Client#load()V"), "load", NodeKind.METHOD,
            jvmSignature = "dev/example/net/Client#load()V", location = SourceLocation(path, 5))
        val file = Files.createTempFile(project.parent, "graph", ".json")
        Files.writeString(file, QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(node), emptyList()), emptyList(), emptyList())))
        return file
    }

    private fun limitations(vararg extra: String): Pair<List<String>, List<Map<*, *>>> {
        val output = ByteArrayOutputStream()
        val status = KartographCli.run(arrayOf("routes", "--role", "client", "--project", project.toString(), *extra),
            PrintStream(output), PrintStream(ByteArrayOutputStream()))
        assertEquals(0, status)
        val document = McpJsonCodec.parse(output.toString()) as Map<*, *>
        return (document["limitations"] as List<*>).map { it as String } to (document["facts"] as List<*>).map { it as Map<*, *> }
    }

    @Test
    fun `matching snapshot paths attach usrs without a missing limitation`() {
        writeClient()
        val (limitations, facts) = limitations("--graph-file", snapshot(sourcePath).toString())
        assertEquals("method:dev/example/net/Client#load()V", (facts.single()["symbol"] as Map<*, *>)["usr"])
        assertTrue(limitations.none { it.startsWith("missing-route-usrs:") }, limitations.toString())
    }

    @Test
    fun `a parent snapshot root is named`() {
        writeClient()
        val (limitations, _) = limitations("--graph-file", snapshot("mobile/$sourcePath").toString())
        val missing = limitations.single { it.startsWith("missing-route-usrs:") }
        assertContains(missing, "1 route-call fact(s)")
        assertContains(missing, "project-root-mismatch: snapshot source paths start with \"mobile/\"")
    }

    @Test
    fun `a subdirectory snapshot root is named`() {
        writeClient()
        val (limitations, _) = limitations("--graph-file", snapshot(sourcePath.removePrefix("app/")).toString())
        assertContains(limitations.single { it.startsWith("missing-route-usrs:") }, "omit \"app/\"")
    }

    @Test
    fun `routes without a snapshot say why identities are missing`() {
        writeClient()
        val (limitations, _) = limitations()
        assertContains(limitations.single { it.startsWith("missing-route-usrs:") }, "no --graph-file snapshot was given")
    }

    @Test
    fun `a stale snapshot is reported as stale rather than missing`() {
        // 리뷰 지적 재현 시도: stale snapshot은 신원 부착에서 빠지지만 진단에는 snapshot 그래프를 그대로 쓴다.
        writeClient()
        val classes = project.resolve("classes").createDirectories()
        classes.resolve("A.class").writeText("changed")
        val node = GraphNode(NodeId("method:dev/example/net/Client#load()V"), "load", NodeKind.METHOD,
            jvmSignature = "dev/example/net/Client#load()V", location = SourceLocation(sourcePath, 5))
        val provenance = dev.kartograph.core.SnapshotProvenance(listOf(dev.kartograph.core.InputFingerprint("classes", "classes", "0".repeat(64))), emptyList())
        val file = Files.createTempFile(project.parent, "stale", ".json")
        Files.writeString(file, QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(node), emptyList()), emptyList(), emptyList(),
            provenance = provenance)))
        val (limitations, facts) = limitations("--graph-file", file.toString())
        assertTrue(limitations.any { it.startsWith("graph-file-freshness-stale") }, limitations.toString())
        assertNull((facts.single()["symbol"] as Map<*, *>?)?.get("usr"))
        assertContains(limitations.single { it.startsWith("missing-route-usrs:") }, "snapshot is stale")
    }

    @Test
    fun `root mismatch diagnosis handles bare and unrelated paths`() {
        assertContains(RouteSymbolDiagnostics.rootMismatch(listOf("a/B.kt"), setOf("B.kt"))!!, "--include-paths")
        assertContains(RouteSymbolDiagnostics.rootMismatch(listOf("a/B.kt"), setOf("x/C.kt"))!!, "none of the route-call files")
        assertNull(RouteSymbolDiagnostics.rootMismatch(listOf("a/B.kt"), setOf("a/B.kt")))
    }

    @Test
    fun `route declarations name the handler lookup when the snapshot has no matching method`() {
        val fact = dev.kartograph.core.BridgeFact("route-decl", "/a", "GET", false, dev.kartograph.core.BridgeLocation(sourcePath, 3, 5),
            dev.kartograph.core.BridgeSymbol("dev.example.net.Client.other"), "http")
        val node = GraphNode(NodeId("method:dev/example/net/Client#load()V"), "load", NodeKind.METHOD, location = SourceLocation(sourcePath, 5))
        val message = RouteSymbolDiagnostics.missingUsrs(listOf(fact), CodeGraph(listOf(node), emptyList()), stale = false)!!
        assertContains(message, "missing-route-usrs: 1 route-decl fact(s)")
        assertContains(message, "no compiled handler method in the snapshot matched")
        assertContains(RouteSymbolDiagnostics.rootMismatch(listOf("a/B.kt"), setOf("x/C.kt"), "route-decl")!!, "none of the route-decl files")
    }
}
