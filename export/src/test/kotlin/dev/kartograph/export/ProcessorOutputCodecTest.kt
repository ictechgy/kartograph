package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputs
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
        for (change in listOf(value + ("inputs" to emptyList<String>()), value + ("inputs" to listOf("src", "src")),
            value + ("command" to listOf("")), value + ("token" to ".evidence/raw.tsv"), value + ("outputRoots" to listOf("../outside")))) {
            assertFailsWith<IllegalArgumentException> { ProcessorOutputCodec.configuration(jsonValue(change)) }
        }
    }

    private fun receipt(): String = jsonValue(sortedMapOf("format" to "kartograph-processor-output-witness", "version" to 2,
        "buildExit" to 0, "scope" to "app:main", "token" to sha, "configurationSha256" to sha,
        "inputs" to listOf(mapOf("path" to "src", "sha256" to sha)), "observation" to ProcessorOutputCodec.value(observations)))
}
