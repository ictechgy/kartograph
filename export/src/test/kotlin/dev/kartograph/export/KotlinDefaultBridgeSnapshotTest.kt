package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class KotlinDefaultBridgeSnapshotTest {
    private val target = GraphNode(NodeId("method:probe/Defaults#label(II)I"), "label", NodeKind.METHOD,
        jvmModifiers = setOf(JvmModifier.STATIC), kotlinSourceName = "label")
    private val other = GraphNode(NodeId("method:probe/Defaults#label(I)I"), "label", NodeKind.METHOD,
        jvmModifiers = setOf(JvmModifier.STATIC), kotlinSourceName = "label")
    private val bridge = GraphNode(NodeId("method:probe/Defaults#label\$default(IIILjava/lang/Object;)I"), "label\$default", NodeKind.METHOD,
        jvmModifiers = setOf(JvmModifier.STATIC, JvmModifier.SYNTHETIC),
        kotlinDefaultBridge = KotlinDefaultBridge(target.id, 2, 1))
    private val graph = CodeGraph(listOf(target, other, bridge), listOf(
        GraphEdge(bridge.id, target.id, EdgeKind.CALL), GraphEdge(bridge.id, other.id, EdgeKind.CALL)))

    @Test fun `plain compact and graph JSON preserve source association separately from both raw calls`() {
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact)
            val restored = QuerySnapshotCodec.parse(text).graph
            assertEquals(graph.nodes, restored.nodes)
            assertEquals(graph.edges, restored.edges)
            assertEquals(2, restored.outgoingEdgesFrom(bridge.id).count { it.kind == EdgeKind.CALL })
            assertContains(text, "kotlinMetadataAndBytecode")
        }
        assertContains(GraphJsonRenderer.render(graph, "test"), "\"kotlinDefaultBridge\"")
        assertContains(GraphJsonRenderer.render(graph, "test"), "\"maskParameterCount\": 1")
    }

    @Test fun `legacy absent association remains unknown`() {
        val legacy = CodeGraph(graph.nodes.values.map { it.copy(kotlinDefaultBridge = null) }, graph.edges)
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(QuerySnapshot(legacy, emptyList(), emptyList()), compact)
            assertFalse("kotlinDefaultBridges" in text)
            assertTrue(QuerySnapshotCodec.parse(text).graph.nodes.values.all { it.kotlinDefaultBridge == null })
        }
    }

    @Test fun `forged missing duplicate and inconsistent source associations fail closed`() {
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact)
            listOf(
                text.replace("kotlinMetadataAndBytecode", "nameGuess"),
                text.replace("\"maskParameterCount\": 1", "\"maskParameterCount\": 2"),
                text.replace("\"valueParameterCount\": 2", "\"valueParameterCount\": 0"),
                text.replace("\"target\": \"${target.id.value}\"", "\"target\": \"method:missing#label(II)I\""),
                text.replace("\"target\": \"${target.id.value}\"", "\"target\": \"${other.id.value}\""),
            ).forEach { invalid ->
                assertNotEquals(text, invalid)
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(invalid) }
            }
        }
        listOf(
            CodeGraph(graph.nodes.values, emptyList()),
            CodeGraph(graph.nodes.values.map { if (it.id == bridge.id) it.copy(kotlinSourceName = "authored") else it }, graph.edges),
            CodeGraph(graph.nodes.values.map { if (it.id == bridge.id) it.copy(jvmModifiers = setOf(JvmModifier.STATIC)) else it }, graph.edges),
            CodeGraph(graph.nodes.values, graph.edges.map { it.copy(origin = EdgeOrigin.DISPATCH_MODEL) }),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(invalid, emptyList(), emptyList()))) }
        }
        val duplicateDocument = SnapshotJsonParser(QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()))).parse() as Map<*, *>
        val duplicateGraph = (duplicateDocument["graph"] as Map<*, *>).toMutableMap()
        val facts = duplicateGraph["kotlinDefaultBridges"] as List<*>
        duplicateGraph["kotlinDefaultBridges"] = facts + facts
        val duplicated = duplicateDocument.toMutableMap().apply { put("graph", duplicateGraph) }
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(duplicated)) }
        assertFailsWith<IllegalArgumentException> { KotlinDefaultBridge(target.id, 33, 1) }
        assertFailsWith<IllegalArgumentException> { KotlinDefaultBridge(target.id, 0, 0) }
    }
}
