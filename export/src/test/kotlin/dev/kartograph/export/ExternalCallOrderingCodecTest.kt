package dev.kartograph.export

import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** target getter 구현과 독립적인 wire identity·필드 oracle를 유지한다. */
class ExternalCallOrderingCodecTest {
    @Test
    fun `copied identities retain legacy JSON NDJSON and snapshot bytes`() {
        val caller = GraphNode(NodeId("method:app/Caller#call()V"), "call", NodeKind.METHOD)
        val original = ExternalCall(caller.id, "lib/Owner", "run", "()V", InvocationKind.VIRTUAL)
        val inputs = listOf(original.copy(owner = "lib/Other"), original.copy(name = "renamed"),
            original.copy(descriptor = "(I)V"), original,
            original.copy(model = "jdk.example.v1", resolution = CallResolution.RUNTIME_MODEL))
        val legacyOrder = Comparator<ExternalCall> { first, second ->
            compareValuesBy(first, second,
                { it.caller.value }, { "method:${it.owner}#${it.name}${it.descriptor}" }, { it.kind.name },
                { it.ordinal }, { it.location?.path.orEmpty() }, { it.location?.line ?: 0 })
        }
        val expected = inputs.distinct().sortedWith(legacyOrder)
        val graph = CodeGraph(listOf(caller), emptyList(), inputs)
        assertEquals(expected, graph.externalCalls)
        val rows = expected.map { call ->
            sortedMapOf<String, Any?>(
                "caller" to "method:app/Caller#call()V",
                "target" to "method:${call.owner}#${call.name}${call.descriptor}",
                "kind" to "virtual",
                "ordinal" to 0,
                "resolvedTargets" to emptyList<String>(),
                "resolution" to if (call.resolution == CallResolution.RUNTIME_MODEL) "runtimeModel" else "unresolved",
            ).apply { call.model?.let { put("model", it) } }
        }
        assertContains(GraphJsonRenderer.render(graph, "test"), "\"externalCalls\": " + jsonValue(rows))
        val ndjson = StringBuilder().also { GraphJsonRenderer.writeNdjson(graph, "test", it) }.toString()
        val expectedRecords = rows.map { row -> jsonValue(sortedMapOf("externalCall" to row, "record" to "externalCall")) }
        assertEquals(expectedRecords, ndjson.lineSequence().filter { it.contains("\"record\": \"externalCall\"") }.toList())

        val snapshot = QuerySnapshot(graph, emptyList(), emptyList(), toolVersion = "test")
        for (compact in listOf(false, true)) {
            val bytes = QuerySnapshotCodec.render(snapshot, compact)
            val decoded = QuerySnapshotCodec.parse(bytes)
            assertEquals(expected, decoded.graph.externalCalls)
            assertEquals(bytes, QuerySnapshotCodec.render(decoded, compact))
            decoded.graph.externalCalls.zip(expected).forEach { (actual, reference) ->
                assertEquals(NodeId("method:${reference.owner}#${reference.name}${reference.descriptor}"), actual.target)
            }
        }
    }
}
