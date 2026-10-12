package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class ExternalStubSnapshotTest {
    private val caller = GraphNode(NodeId("method:app/Caller#run()V"), "run", NodeKind.METHOD)
    private val body = GraphNode(NodeId("method:app/Implementation#m()V"), "m", NodeKind.METHOD)
    private val owner = GraphNode(NodeId("class:lib/Api"), "Api", NodeKind.CLASS,
        jvmSignature = "lib/Api", attributes = setOf(NodeAttribute.EXTERNAL_STUB))
    private val call = ExternalCall(caller.id, "lib/Api", "m", "()V", InvocationKind.VIRTUAL,
        SourceLocation("Caller.java", 7), ordinal = 3, resolvedTargets = listOf(body.id),
        resolution = CallResolution.PROJECT_CANDIDATES,
        declaration = CallDeclaration(NodeId("method:lib/Api#m()V"), CallDeclarationScope.EXTERNAL, listOf("lib/Api")))
    private val stub = GraphNode(call.target, "m", NodeKind.METHOD, attributes = setOf(NodeAttribute.EXTERNAL_STUB))

    private fun snapshot(includeStub: Boolean = true): QuerySnapshot {
        val nodes = listOf(caller, body, owner) + if (includeStub) listOf(stub) else emptyList()
        val edges = listOf(GraphEdge(caller.id, body.id, EdgeKind.CALL, origin = EdgeOrigin.DISPATCH_MODEL)) +
            if (includeStub) listOf(GraphEdge(caller.id, stub.id, EdgeKind.CALL, callSiteLines = listOf(7))) else emptyList()
        return QuerySnapshot(CodeGraph(nodes, edges, listOf(call)), emptyList(), emptyList())
    }

    @Test fun `plain snapshot preserves external stub calls`() = roundtrip(false)
    @Test fun `compact snapshot preserves external stub calls`() = roundtrip(true)

    private fun roundtrip(compact: Boolean) {
        val original = snapshot()
        val encoded = QuerySnapshotCodec.render(original, compact)
        val restored = QuerySnapshotCodec.parse(encoded)
        assertEquals(original.graph.nodes, restored.graph.nodes)
        assertEquals(original.graph.externalCalls, restored.graph.externalCalls)
        assertEquals(call.declaration, restored.graph.externalCalls.single().declaration)
        assertEquals(original.graph.edges, restored.graph.edges)
        assertEquals(encoded, QuerySnapshotCodec.render(restored, compact))
    }

    @Test
    fun `absent external operand remains valid in both formats`() {
        for (compact in listOf(false, true)) {
            val original = snapshot(includeStub = false)
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(original, compact)).graph
            assertEquals(listOf(call), restored.externalCalls)
            assertEquals(original.graph.nodes, restored.nodes)
            assertEquals(original.graph.edges, restored.edges)
        }
    }

    @Test
    fun `stub assertion does not add global node kind or signature constraints`() {
        val raw = call.copy(declaration = null)
        val minimalStub = stub.copy(kind = NodeKind.CLASS, jvmSignature = null)
        val graph = CodeGraph(listOf(caller, body, minimalStub), emptyList(), listOf(raw))
        for (compact in listOf(false, true)) {
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot().copy(graph = graph), compact)).graph
            assertEquals(graph.nodes, restored.nodes)
            assertEquals(listOf(raw), restored.externalCalls)
        }
    }

    @Test
    fun `only the operand target marker admits a present external call`() {
        // 외부 선언 side table 없이도 원시 호출 자체가 프로젝트 target과 모순되면 거부해야 한다.
        val original = snapshot().copy(graph = CodeGraph(snapshot().graph.nodes.values, emptyList(), listOf(call.copy(declaration = null))))
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(original, compact)
            for (attributes in listOf(emptyList(), listOf("generatedInput"), listOf("external-stub"))) {
                val forged = mutateNode(text, compact, stub.id) { it + ("attributes" to attributes) + ("external" to true) }
                assertFailsWith<IllegalArgumentException>("compact=$compact, attributes=$attributes") { QuerySnapshotCodec.parse(forged) }
            }
            // 다른 owner 정점의 stub 표식은 operand target의 표식을 대신하지 않는다.
            assertTrue(NodeAttribute.EXTERNAL_STUB in owner.attributes)
        }
    }

    @Test
    fun `stub calls retain caller ordinal and resolved target validation`() {
        val original = snapshot().copy(graph = CodeGraph(snapshot().graph.nodes.values, emptyList(), listOf(call.copy(declaration = null))))
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(original, compact)
            val missing: Any = if (compact) original.graph.nodeCount else "method:missing/Api#m()V"
            for ((field, value) in listOf("caller" to missing, "ordinal" to -1, "resolvedTargets" to listOf(missing))) {
                val forged = mutateCall(text, compact, field, value)
                assertFailsWith<IllegalArgumentException>("compact=$compact, field=$field") { QuerySnapshotCodec.parse(forged) }
            }
        }
    }

    @Test
    fun `stub marker does not weaken declaration identity scope or project owner checks`() {
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot(), compact)
            val document = SnapshotJsonParser(text).parse() as Map<*, *>
            val graph = document["graph"] as Map<*, *>
            val row = (graph["callDeclarations"] as List<*>).single() as Map<*, *>
            for ((field, value) in listOf("scope" to "project", "caller" to body.id.value,
                "operand" to "method:lib/Api#wrong()V", "kind" to "static", "ordinal" to 4,
                "target" to "method:lib/Api#wrong()V", "ownerChain" to listOf("lib/Wrong"))) {
                val changed = graph + ("callDeclarations" to listOf(row + (field to value)))
                assertFailsWith<IllegalArgumentException>("compact=$compact, field=$field") {
                    QuerySnapshotCodec.parse(jsonValue(document + ("graph" to changed)))
                }
            }
            val projectOwner = mutateNode(text, compact, owner.id) { it + ("attributes" to emptyList<String>()) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(projectOwner) }
        }
    }

    private fun mutateNode(text: String, compact: Boolean, id: NodeId, change: (Map<*, *>) -> Map<*, *>): String {
        val document = SnapshotJsonParser(text).parse() as Map<*, *>
        val graph = document["graph"] as Map<*, *>
        val nodes = graph["nodes"] as List<*>
        if (!compact) return jsonValue(document + ("graph" to (graph + ("nodes" to nodes.map {
            val node = it as Map<*, *>
            if (node["usr"] == id.value) change(node) else node
        }))))
        val strings = (graph["stringTable"] as List<*>).toMutableList()
        val changed = nodes.map { value ->
            val row = value as List<*>
            if (strings[(row[0] as Long).toInt()] != id.value) row else row.toMutableList().also { result ->
                val attributes = change(mapOf("attributes" to (row[9] as List<*>).map { strings[(it as Long).toInt()] }))["attributes"] as List<*>
                result[9] = attributes.map { attribute ->
                    if (attribute !in strings) strings.add(attribute)
                    strings.indexOf(attribute)
                }
            }
        }
        return jsonValue(document + ("graph" to (graph + mapOf("nodes" to changed, "stringTable" to strings))))
    }

    private fun mutateCall(text: String, compact: Boolean, field: String, value: Any): String {
        val document = SnapshotJsonParser(text).parse() as Map<*, *>
        val graph = document["graph"] as Map<*, *>
        val row = (graph["externalCalls"] as List<*>).single()
        val changed = if (compact) (row as List<*>).toMutableList().also {
            it[mapOf("caller" to 0, "ordinal" to 5, "resolvedTargets" to 7).getValue(field)] = value
        } else (row as Map<*, *>) + (field to value)
        return jsonValue(document + ("graph" to (graph + ("externalCalls" to listOf(changed)))))
    }
}
