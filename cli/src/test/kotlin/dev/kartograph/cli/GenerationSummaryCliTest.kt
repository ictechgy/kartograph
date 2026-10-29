package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class GenerationSummaryCliTest {
    @Test
    fun `saved CLI prepared MCP and source query retain capture observation in every selection status`(@TempDir root: Path) {
        for (compact in listOf(false, true)) {
            val snapshot = snapshot(false)
            val file = root.resolve("current-$compact.json")
            Files.writeString(file, QuerySnapshotCodec.render(snapshot, compact))
            val session = SavedSnapshotOperations.QuerySession(snapshot)
            client(file).use { client ->
                client.initialize()
                for (requested in listOf("class:demo/F0", "Foo", "Missing")) {
                    val (status, ordinary) = run("query", requested, "--graph-file", "$file", "--limit", "1")
                    assertEquals(if (requested.startsWith("class:")) 0 else 64, status)
                    val summary = ordinary["generationSummary"] as Map<*, *>
                    assertEquals("snapshotCapture", summary["observationTime"])
                    assertEquals(false, summary["liveRevalidated"])
                    assertEquals(2L, summary["totalDeclarations"])
                    assertContains(ordinary["limitations"] as List<*>, UNKNOWN)
                    assertEquals(ordinary, McpJsonCodec.parse(session.query(requested, 1, 1).json))
                    assertEquals(ordinary, document(client.call("query_symbol", mapOf("symbol" to requested, "limit" to 1))))
                    val (sourceStatus, source) = run("query", requested, "--graph-file", "$file", "--limit", "1", "--source-uses")
                    assertEquals(status, sourceStatus)
                    assertEquals(ordinary, source["query"])
                }
            }
        }
    }

    @Test
    fun `saved impact and source edits preserve separate historical and current summaries after pagination`(@TempDir root: Path) {
        val current = root.resolve("current.json").also { Files.writeString(it, QuerySnapshotCodec.render(snapshot(false))) }
        val base = root.resolve("base.json").also { Files.writeString(it, QuerySnapshotCodec.render(snapshot(true), true)) }
        val args = arrayOf("impact", "class:demo/F0", "--graph-file", "$current", "--base-graph", "$base", "--limit", "1", "--offset", "100")
        val (status, ordinary) = run(*args)
        assertEquals(0, status)
        assertEquals(emptyList<Any>(), ordinary["affected"])
        val inputs = ordinary["inputs"] as Map<*, *>
        for ((key, expected) in listOf("current" to "SUCCEEDED", "base" to "UP_TO_DATE")) {
            val summary = (inputs[key] as Map<*, *>)["generationSummary"] as Map<*, *>
            val states = summary["stateCounts"] as List<*>
            assertEquals(expected, (states.single() as Map<*, *>)["currentGenerationStatus"])
            assertEquals(2L, summary["totalDeclarations"])
        }
        val (sourceStatus, source) = run(*args, "--source-edits")
        assertEquals(status, sourceStatus)
        assertEquals(ordinary, source["graphImpact"])
        client(current, "--base-graph", "$base").use { client ->
            client.initialize()
            val actual = document(client.call("impact", mapOf("symbols" to listOf("class:demo/F0"), "limit" to 1, "offset" to 100)))
            assertEquals(inputs, actual["inputs"])
            assertContains(actual["limitations"] as List<*>, "base: $UNKNOWN")
            assertContains(actual["limitations"] as List<*>, "current: $UNKNOWN")
        }
    }

    @Test
    fun `MCP cap counts summary and fails explicitly instead of silently dropping capture evidence`(@TempDir root: Path) {
        val file = root.resolve("large.json")
        Files.writeString(file, QuerySnapshotCodec.render(snapshot(false).copy(limitations = listOf(UNKNOWN, "x".repeat(20_000)))))
        client(file).use { client ->
            client.initialize()
            val response = client.call("query_symbol", mapOf("symbol" to "class:demo/F0", "limit" to 1))
            assertEquals(true, response["isError"])
            assertFalse(response.containsKey("structuredContent"))
            assertContains(response.toString(), "full saved query with the CLI")
        }
    }

    private fun run(vararg arguments: String): Pair<Int, Map<*, *>> {
        val output = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        assertEquals("", error.toString())
        return status to (McpJsonCodec.parse(output.toString()) as Map<*, *>)
    }
    private fun client(file: Path, vararg options: String) = McpTestClient { input, output, error ->
        KartographCli.runWithInput(arrayOf("mcp", "--graph-file", "$file", *options), output, error, input)
    }
    private fun document(result: Map<*, *>): Map<*, *> {
        assertEquals(false, result["isError"], result.toString())
        val text = ((result["content"] as List<*>).single() as Map<*, *>)["text"] as String
        assertTrue(text.toByteArray().size <= McpTools.INTERACTIVE_CONTENT_BYTES)
        assertEquals(McpJsonCodec.parse(text), result["structuredContent"])
        return (result["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
    }
    companion object {
        private const val UNKNOWN = "generation-unknown: unsupported producer remains unclassified"
        private val HASH = "a".repeat(64)
        private fun snapshot(warm: Boolean): QuerySnapshot {
            fun input(role: String, path: String) = InputFingerprint(role, path, HASH)
            val provenance = SnapshotProvenance(listOf(input("classes", "build/classes")), listOf(BuildWitness("app:debug", "javac", ":app:compileDebugJavaWithJavac",
                listOf(input("sources", "build/generated"), input("buildConfig", "build.gradle"), input("compiler", "external/compiler"), input("options", "config/javac")), listOf(input("classes", "build/classes")))))
            val receipt = GenerationReceipt("app:debug", ":app:generateSafeArgsDebug", GenerationReceipt.POLICY, HASH, "build/generated",
                listOf(input("navigation", "res/navigation/main.xml"), input("generatorArtifact", "external/generator"), input("generatorActionArtifact", "external/action"), input("generatorOptions", "generation/options")),
                input("generationMapping", "build/mapping.json"), (0..1).map { input("generatedSource", "build/generated/demo/F$it.java") },
                (0..1).map { GenerationSource("res/navigation/main.xml", "build/generated/demo/F$it.java", "demo.F$it", HASH) })
            val declarations = (0..1).map { GeneratedDeclaration(receipt.scope, receipt.task, receipt.attempt, ":app:compileDebugJavaWithJavac",
                "build/generated/demo/F$it.java", HASH, NodeId("class:demo/F$it"), listOf(NodeId("class:demo/F$it")), "build/classes", "build/classes", "demo/F$it.class", HASH) }
            val context = GenerationOriginContext(GenerationDigests.receipt(receipt), receipt.scope, receipt.task, "debug", receipt.outputRoot, receipt.mapping.path,
                listOf(GenerationProviderBinding("navigationFiles", listOf("res/navigation/main.xml")), GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)), GenerationProviderBinding("incrementalFolder", listOf("build"))),
                GenerationOriginContext.ACTION_POLICY, receipt.inputs.filter { it.role == "generatorActionArtifact" })
            val observation = GenerationConsumptionObservation(context, context, HASH, if (warm) null else HASH,
                if (warm) "historical-byte-equivalence" else "current-cold-success", if (warm) "UP_TO_DATE" else "SUCCEEDED",
                GenerationDigests.inventory(receipt), GenerationDigests.declarations(declarations, provenance))
            val nodes = declarations.map { GraphNode(it.owner, "Foo", NodeKind.CLASS, synthesized = true, attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE)) }
            return QuerySnapshot(CodeGraph(nodes, listOf(GraphEdge(nodes[1].id, nodes[0].id, EdgeKind.CALL))), emptyList(), listOf(UNKNOWN),
                scope = "app:debug", revision = (if (warm) "1" else "2").repeat(40), provenance = provenance,
                generationEvidence = GenerationEvidence(listOf(receipt), declarations, listOf(observation)))
        }
    }
}
