package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class DirectInterfaceDeclarationCodecTest {
    private val caller = GraphNode(NodeId("method:p/Caller#run()V"), "run", NodeKind.METHOD)
    private val declaration = CallDeclaration(NodeId("method:lib/I#m()V"), CallDeclarationScope.EXTERNAL, listOf("lib/I"))
    private fun call() = ExternalCall(caller.id, "lib/I", "m", "()V", InvocationKind.INTERFACE, declaration = declaration)
    private fun snapshot() = QuerySnapshot(CodeGraph(listOf(caller), emptyList(), listOf(call())), emptyList(), emptyList())

    @Test
    fun `plain compact JSON and NDJSON preserve direct proof without changing raw fields`() {
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot(), compact)
            assertEquals(call(), QuerySnapshotCodec.parse(text).graph.externalCalls.single())
            assertContains(text, "\"kind\": \"interface\"")
            assertFalse(text.contains("proof"))
            val old = snapshot().copy(graph = CodeGraph(listOf(caller), emptyList(), listOf(call().copy(declaration = null))))
            val legacy = QuerySnapshotCodec.render(old, compact)
            assertFalse(legacy.contains("callDeclarations"))
            assertNull(QuerySnapshotCodec.parse(legacy).graph.externalCalls.single().declaration)
            assertEquals(legacy, QuerySnapshotCodec.render(QuerySnapshotCodec.parse(legacy), compact))
        }
        val json = GraphJsonRenderer.render(snapshot().graph, "test")
        val ndjson = StringBuilder().also { GraphJsonRenderer.writeNdjson(snapshot().graph, "test", it) }.toString()
        listOf(json, ndjson).forEach { text ->
            assertContains(text, "\"kind\": \"interface\"")
            assertContains(text, "\"declaration\": {\"ownerChain\": [\"lib/I\"]")
            assertContains(text, "\"resolution\": \"unresolved\"")
            assertContains(text, "\"resolvedTargets\": []")
        }
    }

    @Test
    fun `strict readers reject inherited interface chains changed kind scope and extra proof tags`() {
        for (compact in listOf(false, true)) {
            val document = SnapshotJsonParser(QuerySnapshotCodec.render(snapshot(), compact)).parse() as Map<*, *>
            val graph = document["graph"] as Map<*, *>
            val row = (graph["callDeclarations"] as List<*>).single() as Map<*, *>
            val changes = listOf(
                row + ("ownerChain" to listOf("lib/I", "lib/Ancestor")) + ("target" to "method:lib/Ancestor#m()V"),
                row + ("scope" to "project"), row + ("kind" to "virtual"),
                row + ("proof" to "directInterfaceHeaderV1"), row + ("proof" to "classHeaderV1"),
                row + ("target" to "method:lib/I#other()V"),
            )
            changes.forEach { changed ->
                val forged = document + ("graph" to (graph + ("callDeclarations" to listOf(changed))))
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(forged)) }
            }
        }
    }

    @Test
    fun `reader rejects known project owner assertions even after caller graph filtering`() {
        for (kind in listOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.FIELD)) {
            val node = GraphNode(NodeId("class:lib/I"), "I", kind, jvmSignature = "lib/I")
            // side table을 기존 raw graph에 붙여 canonical normalization 이전의 위조도 거부한다.
            val raw = CodeGraph(listOf(caller, node), emptyList(), listOf(call().copy(declaration = null)))
            for (compact in listOf(false, true)) {
                val document = SnapshotJsonParser(QuerySnapshotCodec.render(snapshot().copy(graph = raw), compact)).parse() as Map<*, *>
                val graph = document["graph"] as Map<*, *>
                val forged = document + ("graph" to (graph + ("callDeclarations" to CallDeclarationCodec.records(listOf(call())))))
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(forged)) }
            }
        }
    }
}
