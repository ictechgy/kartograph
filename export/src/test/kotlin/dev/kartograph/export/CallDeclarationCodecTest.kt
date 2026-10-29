package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse

class CallDeclarationCodecTest {
    private val caller = GraphNode(NodeId("method:p/Caller#run()V"), "run", NodeKind.METHOD)
    private val target = GraphNode(NodeId("method:p/Base#m()V"), "m", NodeKind.METHOD, jvmVisibility = Visibility.PUBLIC)
    private val base = GraphNode(NodeId("class:p/Base"), "Base", NodeKind.CLASS, jvmSignature = "p/Base")
    private val declaration = CallDeclaration(target.id, CallDeclarationScope.PROJECT, listOf("lib/Boundary", "p/Base"))
    private val call = ExternalCall(caller.id, "lib/Boundary", "m", "()V", InvocationKind.VIRTUAL, declaration = declaration)
    private fun snapshot(call: ExternalCall = this.call) = QuerySnapshot(CodeGraph(listOf(caller, target, base), emptyList(), listOf(call)), emptyList(), emptyList())

    @Test
    fun `both snapshots roundtrip additive evidence and old snapshots remain unknown`() {
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot(), compact)
            assertContains(text, "\"callDeclarations\"")
            assertEquals(call, QuerySnapshotCodec.parse(text).graph.externalCalls.single())
            val without = QuerySnapshotCodec.render(snapshot(call.copy(declaration = null)), compact)
                .replace("\"callDeclarations\": [], ", "")
            assertNull(QuerySnapshotCodec.parse(without).graph.externalCalls.single().declaration)
            val external = CallDeclaration(NodeId("method:lib/Boundary#m()V"), CallDeclarationScope.EXTERNAL, listOf("lib/Boundary"))
            assertEquals(external, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot(call.copy(declaration = external)), compact))
                .graph.externalCalls.single().declaration)
        }
    }

    @Test
    fun `JSON and NDJSON expose declaration independently from unchanged dispatch fields`() {
        val graph = snapshot().graph
        val json = GraphJsonRenderer.render(graph, "test")
        val ndjson = StringBuilder().also { GraphJsonRenderer.writeNdjson(graph, "test", it) }.toString()
        listOf(json, ndjson).forEach {
            assertContains(it, "\"declaration\": {\"ownerChain\": [\"lib/Boundary\", \"p/Base\"]")
            assertContains(it, "\"resolvedTargets\": []")
            assertContains(it, "\"resolution\": \"unresolved\"")
        }
        assertFalse(GraphJsonRenderer.render(snapshot(call.copy(declaration = null)).graph, "test").contains("\"declaration\""))
    }

    @Test
    fun `malformed side tables endpoints scopes and graph contradictions reject in both formats`() {
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot(), compact)
            val document = SnapshotJsonParser(text).parse() as Map<*, *>
            val rawGraph = document["graph"] as Map<*, *>
            val row = (rawGraph["callDeclarations"] as List<*>).single() as Map<*, *>
            val mutations = listOf(
                null, emptyMap<String, String>(), listOf(row, row),
                listOf(row + ("call" to 1)), listOf(row + ("call" to -1)),
                listOf(row + ("caller" to "method:p/Other#run()V")),
                listOf(row + ("operand" to "method:lib/Boundary#wrong()V")),
                listOf(row + ("scope" to "external")), listOf(row + ("scope" to "unknown")),
                listOf(row + ("ownerChain" to emptyList<String>())),
                listOf(row + ("ownerChain" to listOf("lib/Boundary", "lib/Boundary", "p/Base"))),
                listOf(row + ("ownerChain" to listOf("lib/Boundary", "p/Wrong"))),
                listOf(row + ("ownerChain" to (0..256).map { "p/C$it" })),
                listOf(row + ("kind" to "static")), listOf(row + ("ordinal" to 2)),
                listOf(row + ("target" to "method:p/Base#wrong()V")),
            )
            mutations.forEach { rows ->
                val changed = jsonValue(document + ("graph" to (rawGraph + ("callDeclarations" to rows))))
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(changed) }
            }
            val withoutOwner = CodeGraph(snapshot().graph.nodes.values.filter { it.id != base.id }, emptyList(), listOf(call))
            assertEquals(declaration, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = withoutOwner), compact)).graph.externalCalls.single().declaration)
            val projected = snapshot().graph
            val filtered = CodeGraph(projected.nodes.values.filter { it.id != target.id }, projected.edges, projected.externalCalls)
            assertNull(QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = filtered), compact)).graph.externalCalls.single().declaration)
        }
    }

    @Test
    fun `known unrelated protected static caller is rejected but missing external ancestry stays asserted`() {
        val protectedTarget = target.copy(jvmVisibility = Visibility.PROTECTED, jvmModifiers = setOf(JvmModifier.STATIC))
        val crossCaller = caller.copy(id = NodeId("method:q/Caller#run()V"))
        val crossType = GraphNode(NodeId("class:q/Caller"), "Caller", NodeKind.CLASS, jvmSignature = "q/Caller")
        val crossCall = call.copy(caller = crossCaller.id, kind = InvocationKind.STATIC)
        fun graph(type: GraphNode) = CodeGraph(listOf(base, protectedTarget, crossCaller, type), emptyList(), listOf(crossCall))
        for (compact in listOf(false, true)) {
            assertFailsWith<IllegalArgumentException> {
                QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = graph(crossType)), compact))
            }
            val partial = graph(crossType.copy(supertypes = setOf("missing/Parent")))
            assertEquals(crossCall, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = partial), compact)).graph.externalCalls.single())
            val subclass = graph(crossType.copy(supertypes = setOf("p/Base")))
            assertEquals(crossCall, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = subclass), compact)).graph.externalCalls.single())
        }
    }

    @Test
    fun `special declaration survives class pruning but rejects a contradictory known caller`() {
        val special = call.copy(kind = InvocationKind.SPECIAL)
        for (compact in listOf(false, true)) {
            assertEquals(special, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot(special), compact)).graph.externalCalls.single())
            val wrongCaller = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS, jvmSignature = "p/Caller")
            val graph = CodeGraph(snapshot(special).graph.nodes.values + wrongCaller, emptyList(), listOf(special))
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = graph), compact)) }
        }
    }

    @Test
    fun `evidence cannot skip a known project declaration or class parent`() {
        val middle = GraphNode(NodeId("class:lib/Middle"), "Middle", NodeKind.CLASS, jvmSignature = "lib/Middle", supertypes = setOf("p/Base"))
        val call = call.copy(declaration = declaration.copy(ownerChain = listOf("lib/Boundary", "lib/Middle", "p/Base")))
        for (node in listOf(middle.copy(supertypes = emptySet()), middle.copy(kind = NodeKind.INTERFACE))) {
            val graph = CodeGraph(snapshot(call).graph.nodes.values + node, emptyList(), listOf(call))
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = graph))) }
        }
        val graph = CodeGraph(snapshot(call).graph.nodes.values + middle + target.copy(id = NodeId("method:lib/Middle#m()V")), emptyList(), listOf(call))
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = graph))) }
    }
}
