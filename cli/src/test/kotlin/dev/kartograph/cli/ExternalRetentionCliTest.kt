package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.nio.file.Files
import dev.kartograph.export.*
import org.junit.jupiter.api.io.TempDir

class ExternalRetentionCliTest {
    @Test
    fun `external caller suppresses the compiled owner finding and explains the original location`(@TempDir root: Path) {
        val arguments = fixture(root)
        assertContains(run(arguments).output, "unreachable\tclass:sample/Camera")
        val retained = run(arguments + listOf("--external-retentions", "retentions.json"))
        assertEquals(0, retained.status, retained.error)
        assertFalse(retained.output.contains("unreachable\tclass:sample/Camera"))
        assertContains(retained.output, "unreachable\tclass:sample/Unused")
        val explanation = run(arguments + listOf(
            "--external-retentions", "retentions.json", "--explain", "method:sample/Camera#handle()V",
        ))
        assertEquals(0, explanation.status, explanation.error)
        assertContains(explanation.output, "EXTERNAL_BRIDGE")
        assertContains(explanation.output, "dart:lib/camera.dart:7")
        assertContains(explanation.output, "channel camera")
        assertContains(explanation.output, "method capture")
        val privateMode = run(arguments + listOf("--include-private-members",
            "--external-retentions", "retentions.json", "--explain", "method:sample/Camera#handle()V"))
        assertEquals(0, privateMode.status, privateMode.error)
        assertContains(privateMode.output, "dart:lib/camera.dart:7")
        assertContains(privateMode.output, "EXTERNAL_BRIDGE")
        val owner = run(arguments + listOf(
            "--external-retentions", "retentions.json", "--explain", "class:sample/Camera",
        ))
        assertContains(owner.output, "EXTERNAL_BRIDGE")
        val sibling = run(arguments + listOf(
            "--external-retentions", "retentions.json", "--explain", "method:sample/Camera#unused()V",
        ))
        assertContains(sibling.output, "unreachable\tmethod:sample/Camera#unused()V")
    }

    @Test
    fun `unmatched and malformed external documents do not produce partial findings`(@TempDir root: Path) {
        val arguments = fixture(root)
        for (document in listOf(retentions.replace("#handle()V", "#absent()V"), "{}")) {
            root.resolve("retentions.json").writeText(document)
            val result = run(arguments + listOf("--external-retentions", "retentions.json"))
            assertEquals(2, result.status)
            assertEquals("", result.output)
            assertContains(result.error, "external")
        }
        assertEquals(64, run(arguments + "--external-retentions").status)
        assertEquals(2, run(arguments + listOf("--external-retentions", "absent.json")).status)
    }

    @Test
    fun `stale zero and unmatched documents retain global caveats`(@TempDir root: Path) {
        val arguments = fixture(root)
        root.resolve("retentions.json").writeText(withContext(retentions))
        for (format in ReportFormat.entries) {
            val result = run(arguments + listOf("--external-retentions", "retentions.json", "--report-format", format.option))
            assertEquals(0, result.status, result.error)
            assertContains(result.output, "index-freshness")
            assertFalse(result.output.contains("unreachable\tclass:sample/Camera"))
        }
        val explained = run(arguments + listOf("--external-retentions", "retentions.json", "--explain", "method:sample/Camera#handle()V"))
        assertContains(explained.output, "index-freshness")
        root.resolve("retentions.json").writeText(withContext(retentions.replace("#handle()V", "#absent()V")))
        val missing = run(arguments + listOf("--external-retentions", "retentions.json"))
        assertEquals(2, missing.status); assertEquals("", missing.output); assertContains(missing.error, "index-freshness")
        val zero = withContext(retentions.substringBefore("\"retentions\":") + "\"retentions\":[]}")
        root.resolve("retentions.json").writeText(zero)
        val empty = run(arguments + listOf("--external-retentions", "retentions.json"))
        assertEquals(0, empty.status, empty.error); assertContains(empty.output, "index-freshness")
        assertContains(empty.output, "unreachable\tclass:sample/Camera")
        val notFound = run(arguments + listOf("--external-retentions", "retentions.json", "--explain", "class:sample/Absent"))
        assertEquals(64, notFound.status); assertEquals("", notFound.output); assertContains(notFound.error, "index-freshness")
    }

    @Test
    fun `actual capture saved query and MCP preserve context separately from build witness`(@TempDir root: Path) {
        val arguments = fixture(root)
        root.resolve("retentions.json").writeText(withContext(retentions))
        for (compact in listOf(false, true)) {
            val extra = listOf("--external-retentions", "retentions.json")
            val captured = agent(listOf("snapshot") + arguments + extra + if (compact) listOf("--compact") else emptyList())
            assertEquals(0, captured.status, captured.error)
            val snapshot = QuerySnapshotCodec.parse(captured.output)
            assertEquals(1, snapshot.externalRetentionContexts.size)
            assertEquals(emptyList(), snapshot.provenance?.witnesses)
            assertTrue(snapshot.provenance!!.inputs.any { it.role == "external-retentions" && it.path == "retentions.json" })
            val file = root.resolve("saved-$compact.json"); file.writeText(captured.output)
            val symbol = "method:sample/Camera#handle()V"
            val live = agent(listOf("query", symbol) + arguments + extra)
            val saved = agent(listOf("query", symbol, "--graph-file", file.toString()))
            assertEquals(0, live.status, live.error); assertEquals(0, saved.status, saved.error)
            assertContains(live.output, "index-freshness"); assertContains(saved.output, "index-freshness")
            McpTestClient { input, output, error ->
                KartographCli.runWithInput(arrayOf("mcp", "--graph-file", file.toString()), output, error, input)
            }.use { client ->
                client.initialize()
                val response = client.call("query_symbol", mapOf("symbol" to symbol))
                assertEquals(false, response["isError"])
                val document = (response["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
                val cliDocument = McpJsonCodec.parse(saved.output) as Map<*, *>
                assertEquals(cliDocument["limitations"], document["limitations"])
                assertTrue(document.toString().contains("index-freshness"))
            }
            val impact = SavedSnapshotOperations.impact(snapshot, null, listOf(symbol), emptyList(),
                1, 20, 1000, 100, 0, dev.kartograph.analysis.ImpactFilter(), dev.kartograph.analysis.ImpactSort.USR)
            assertContains(impact.json, "index-freshness")
            root.resolve("retentions.json").writeText(withContext(retentions.replace("#handle()V", "#absent()V")))
            val rejected = agent(listOf("snapshot") + arguments + extra)
            assertEquals(2, rejected.status); assertEquals("", rejected.output)
            assertContains(rejected.error, "index-freshness")
            root.resolve("retentions.json").writeText(withContext(retentions))
        }
    }

    @Test
    fun `file adapter refuses invalid UTF8 oversized and nonregular inputs`(@TempDir root: Path) {
        val file = root.resolve("input.json")
        Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
        assertFailsWith<IllegalArgumentException> { ExternalRetentionFiles.read(file) }
        java.io.RandomAccessFile(file.toFile(), "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        assertFailsWith<IllegalArgumentException> { ExternalRetentionFiles.read(file) }
        assertFailsWith<IllegalArgumentException> { ExternalRetentionFiles.read(root) }
        val link = root.resolve("link.json"); Files.createSymbolicLink(link, file.fileName)
        assertFailsWith<IllegalArgumentException> { ExternalRetentionFiles.read(link) }
    }

    @Test
    fun `built Isthmus documents retain roots and context through actual consumer entry points`(@TempDir root: Path) {
        val arguments = fixture(root)
        fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream(
            "/external-retention-composition/$name")).use { it.readBytes() }
        val manifestBytes = resource("manifest.json")
        fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("d0b84063a9ffcf6e01bc14d078cd7c9c365a3806a94a38ac9f1008e8af306a2d", hash(manifestBytes))
        val manifest = McpJsonCodec.parse(manifestBytes.toString(Charsets.UTF_8)) as Map<*, *>
        val cases = (manifest["cases"] as List<*>).map { it as Map<*, *> }.filter { it["target"] == "kartograph" }
        val matched = cases.single { it["scenario"] == "stale-matched" }
        val baselineBytes = resource(matched["baselineFile"] as String)
        assertEquals(matched["baselineSha256"], hash(baselineBytes))
        val baseline = ExternalRetentionCodec.parseDocument(baselineBytes)
        for (case in cases) {
            val name = case["scenario"] as String
            val bytes = resource(case["file"] as String)
            assertEquals(case["sha256"], hash(bytes))
            val document = ExternalRetentionCodec.parseDocument(bytes)
            val zero = name == "zero-retentions"
            if (zero) assertTrue(document.retentions.isEmpty()) else assertEquals(baseline.retentions, document.retentions)
            Files.write(root.resolve("retentions.json"), bytes)
            val flags = listOf("--external-retentions", "retentions.json")
            val dead = run(arguments + flags)
            assertEquals(0, dead.status, dead.error)
            assertContains(dead.output, "unreachable\tclass:sample/Unused")
            assertEquals(zero, dead.output.contains("unreachable\tclass:sample/Camera"))
            assertContains(dead.output, if (name == "unknown-version") "unsupported" else "index-freshness")
            if (!zero) {
                val explain = run(arguments + flags + listOf("--explain", "method:sample/Camera#handle()V"))
                assertEquals(0, explain.status, explain.error)
                for (part in listOf("dart:lib/bridge.dart:4", "channel camera", "method photo")) assertContains(explain.output, part)
            }
            for (compact in listOf(false, true)) {
                val captured = agent(listOf("snapshot") + arguments + flags + if (compact) listOf("--compact") else emptyList())
                assertEquals(0, captured.status, captured.error)
                val snapshot = QuerySnapshotCodec.parse(captured.output)
                assertEquals(document.context, snapshot.externalRetentionContexts.single())
                val original = McpJsonCodec.parse(bytes.toString(Charsets.UTF_8)) as Map<*, *>
                assertEquals(original["provenance"], McpJsonCodec.parse(snapshot.externalRetentionContexts.single().provenanceJson!!))
                val file = root.resolve("composition-$name-$compact.json"); file.writeText(captured.output)
                val missing = agent(listOf("query", "method:sample/Absent#missing()V", "--graph-file", file.toString()))
                assertEquals(64, missing.status, missing.error)
                assertContains(missing.output, if (name == "unknown-version") "unsupported" else "index-freshness")
            }
        }
    }

    private fun withContext(document: String): String = document.trim().dropLast(1) + """,
        "provenance":{"version":1,"limitations":[{"platform":"swift","target":null,"tool":"cartograph",
        "message":"index-freshness: source changed after capture","origin":"opaque-future"}],
        "sources":[{"format":"bridge-facts","version":1,"tool":{"name":"cartograph","version":"test"},
        "platform":"swift","target":null,"project":"/original/swift","generatedAt":"2026-09-01T00:00:00Z"}]}}"""

    private fun agent(arguments: List<String>): Execution {
        val out = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments.toTypedArray(), PrintStream(out), PrintStream(error))
        return Execution(status, out.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private fun fixture(root: Path): List<String> {
        val sources = root.resolve("src/sample").createDirectories()
        val classes = root.resolve("classes").createDirectories()
        val camera = sources.resolve("Camera.java")
        camera.writeText("package sample; public class Camera { public void handle() {} public void unused() {} }")
        val unused = sources.resolve("Unused.java")
        unused.writeText("package sample; public class Unused {}")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), camera.toString(), unused.toString(),
        ))
        root.resolve("manifest.xml").writeText("<manifest />")
        root.resolve("res").createDirectories()
        root.resolve("retentions.json").writeText(retentions)
        return listOf("--classes", classes.toString(), "--project", root.toString(), "--manifest", "manifest.xml",
            "--resources", "res", "--namespace", "sample")
    }

    private fun run(arguments: List<String>): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = DeadCommand.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private data class Execution(val status: Int, val output: String, val error: String)

    private val retentions = """{
        "format":"external-retentions","version":0,"producedBy":{"name":"isthmus","version":"test"},
        "generatedAt":"2026-09-19T00:00:00Z","retentions":[{
        "symbol":{"qualifiedName":"Camera.handle","usr":"method:sample/Camera#handle()V"},
        "reason":"bridge","evidence":{"channel":"camera","method":"capture",
        "caller":{"platform":"dart","path":"lib/camera.dart","line":7}}}]}
    """.trimIndent()
}
