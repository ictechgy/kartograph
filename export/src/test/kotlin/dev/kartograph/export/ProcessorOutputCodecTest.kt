package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputs
import dev.kartograph.core.ProcessorCompilerInputs
import dev.kartograph.core.ProcessorDeclaration
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProcessorOutputCodecTest {
    private val sha = "a".repeat(64)
    private val observations = ProcessorOutputs("app:main", "ksp", "fixture.Provider", sha, sha,
        listOf(ProcessorOutput("generated/A.kt", "source", "api", sha), ProcessorOutput("direct/file", "file", "callback-scope", sha)), sha)

    @Test fun `both snapshot encodings retain observations and scope without changing graph`() {
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), scope = "app:main", processorOutputs = listOf(observations))
        for (compact in listOf(false, true)) {
            val encoded = QuerySnapshotCodec.render(snapshot, compact)
            val decoded = QuerySnapshotCodec.parse(encoded)
            assertEquals(listOf(observations.copy(outputs = observations.outputs.sortedBy { it.path })), decoded.processorOutputs)
            assertEquals(snapshot.graph.nodes, decoded.graph.nodes)
            assertEquals(snapshot.graph.edges, decoded.graph.edges)
        }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(scope = "app:test") }
        val empty = snapshot.copy(processorOutputs = emptyList())
        assertEquals(emptyList(), QuerySnapshotCodec.parse(QuerySnapshotCodec.render(empty).replace("\"processorOutputs\": [],", "")).processorOutputs)
    }

    @Test fun `v1 failed duplicate malformed and unsafe receipts are rejected`() {
        val valid = receipt()
        assertEquals(observations.copy(outputs = observations.outputs.sortedBy { it.path }), ProcessorOutputCodec.receipt(valid).observation)
        for (invalid in listOf("{}", valid.replace("\"version\": 2", "\"version\": 1"),
            valid.replace("\"buildExit\": 0", "\"buildExit\": 1"), valid.replace("\"buildExit\": 0", "\"buildExit\": false"),
            valid.replace("\"version\": 2", "\"version\": 2, \"version\": 2"), valid.replace("generated/A.kt", "../A.kt"),
            valid.replace("callback-scope", "api"), valid.replace("\"kind\": \"ksp\"", "\"kind\": \"unknown\""))) {
            assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.receipt(invalid) }
        }
    }

    @Test fun `configuration is typed and command remains inert`() {
        val value = sortedMapOf<String, Any?>("project" to "/fixture", "scope" to "app:main", "kind" to "ksp", "processor" to "fixture.Provider",
            "collectorJar" to "/artifacts/collector.jar", "processorJar" to "/artifacts/processor.jar", "inputs" to listOf("src"),
            "outputRoots" to listOf("generated"), "command" to listOf("does-not-exist", "--never-execute"),
            "token" to ".evidence/token", "observations" to ".evidence/raw.tsv", "receipt" to ".evidence/receipt.json")
        val config = ProcessorOutputCodec.configuration(jsonValue(value))
        assertEquals(listOf("does-not-exist", "--never-execute"), config.command)
        assertEquals("processor-output-config-v2", config.contractValues().first())
        val withCompiler = ProcessorOutputCodec.configuration(jsonValue(value + ("compilerInputs" to ".evidence/compiler.tsv")))
        assertEquals(listOf("gradle-declared-inputs-v1", ".evidence/compiler.tsv"), withCompiler.contractValues().takeLast(2))
        assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.configuration(jsonValue(value + ("compilerInputs" to ".evidence/raw.tsv"))) }
        for (invalid in listOf("../outside", ".evidence/./inputs.tsv")) {
            assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.configuration(jsonValue(value + ("compilerInputs" to invalid))) }
            assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.configuration(jsonValue(value + ("receipt" to invalid))) }
        }
        for (invalid in listOf("project/a/../b", "project/")) {
            assertFailsWith<IllegalArgumentException> { InputFingerprint("processorCompilerInput", invalid, sha) }
        }
        for (change in listOf(value + ("inputs" to emptyList<String>()), value + ("inputs" to listOf("src", "src")),
            value + ("command" to listOf("")), value + ("token" to ".evidence/raw.tsv"), value + ("outputRoots" to listOf("../outside")))) {
            assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.configuration(jsonValue(change)) }
        }
    }

    @Test fun `v3 inventory and exact class members roundtrip while forged declaration ownership is rejected`() {
        val owner = NodeId("class:fixture/A"); val method = NodeId("method:fixture/A#run()V")
        val graph = CodeGraph(listOf(GraphNode(owner, "fixture.A", NodeKind.CLASS), GraphNode(method, "run", NodeKind.METHOD)),
            listOf(GraphEdge(owner, method, EdgeKind.MEMBER)))
        val input = ProcessorCompilerInputs(":compileJava", listOf(InputFingerprint("processorCompilerInput", "project/classpath.jar", sha)), sha, sha)
        val observed = observations.copy(outputs = listOf(ProcessorOutput("generated/A.class", "class", "api", sha)), compilerInputs = input)
        assertEquals(observed, ProcessorOutputCodec.receipt(receipt(observed, 3)).observation)
        assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.receipt(receipt(observed, 2)) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.receipt(receipt(observations, 3)) }
        val attributed = observed.copy(declarations = listOf(ProcessorDeclaration("generated/A.class", owner, listOf(owner, method))))
        val snapshot = QuerySnapshot(graph, emptyList(), emptyList(), scope = "app:main", processorOutputs = listOf(attributed))
        for (compact in listOf(false, true)) assertEquals(listOf(attributed), QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact)).processorOutputs)
        assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.receipt(receipt(attributed, 3)) }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(graph = CodeGraph(graph.nodes.values, emptyList())) }
        assertFailsWith<IllegalArgumentException> { input.copy(task = "bad task") }
        assertFailsWith<IllegalArgumentException> { input.copy(files = input.files + input.files) }
        assertFailsWith<IllegalArgumentException> { input.copy(files = listOf(InputFingerprint("sources", "classpath.jar", sha))) }
    }

    private fun receipt(observed: ProcessorOutputs = observations, version: Int = 2): String = jsonValue(sortedMapOf("format" to "kartograph-processor-output-witness", "version" to version,
        "buildExit" to 0, "scope" to "app:main", "token" to sha, "configurationSha256" to sha,
        "inputs" to listOf(mapOf("path" to "src", "sha256" to sha)), "observation" to ProcessorOutputCodec.value(observed)))
}
