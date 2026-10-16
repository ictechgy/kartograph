package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EnumInventorySnapshotTest {
    private val first = NodeId("field:probe/State#ALPHA:Lprobe/State;")
    private val second = NodeId("field:probe/State#BETA:Lprobe/State;")
    private val alias = NodeId("field:probe/State#ALIAS:Lprobe/State;")
    private val owner = GraphNode(NodeId("class:probe/State"), "State", NodeKind.ENUM,
        enumConstants = listOf(first, second))
    private val empty = GraphNode(NodeId("class:probe/Empty"), "Empty", NodeKind.ENUM, enumConstants = emptyList())
    private val legacy = GraphNode(NodeId("class:probe/Legacy"), "Legacy", NodeKind.ENUM)
    private val ordinary = GraphNode(NodeId("class:probe/Ordinary"), "Ordinary", NodeKind.CLASS)
    private val stub = GraphNode(NodeId("class:probe/Stub"), "Stub", NodeKind.ENUM, attributes = setOf(NodeAttribute.EXTERNAL_STUB))
    private val graph = CodeGraph(listOf(owner, empty, legacy, ordinary, stub,
        field(first), field(second), field(alias, marked = false)),
        listOf(first, second, alias).map { GraphEdge(owner.id, it, EdgeKind.MEMBER) })

    @Test
    fun `plain and compact snapshots preserve complete empty and unavailable inventories`() {
        for (compact in listOf(false, true)) {
            val text = render(graph, compact)
            val restored = QuerySnapshotCodec.parse(text).graph
            assertEquals(graph.nodes, restored.nodes)
            assertEquals(graph.edges, restored.edges)
            assertEquals(emptyList(), restored.node(empty.id)!!.enumConstants)
            assertNull(restored.node(legacy.id)!!.enumConstants)
            assertNull(restored.node(stub.id)!!.enumConstants)
            assertEquals(text, render(restored, compact))
        }
        val plain = GraphJsonRenderer.render(graph, "test")
        assertContains(plain, "\"enumConstants\"")
        assertContains(plain, "\"enumConstant\"")
        val streamed = StringBuilder().also { GraphJsonRenderer.writeNdjson(graph, "test", it) }.toString()
        assertContains(streamed, "\"enumConstants\"")
    }

    @Test
    fun `pruned field nodes never shrink the captured enum inventory`() {
        val projection = CodeGraph(graph.nodes.values.filter { it.id != second && it.id != alias }, graph.edges)
        assertFalse(second in projection.nodes)
        for (compact in listOf(false, true)) {
            val restored = QuerySnapshotCodec.parse(render(projection, compact)).graph
            assertEquals(projection.nodes, restored.nodes)
            assertEquals(projection.edges, restored.edges)
            assertEquals(listOf(first, second), restored.node(owner.id)!!.enumConstants)
        }
        val classOnly = CodeGraph(listOf(owner, empty), emptyList())
        assertEquals(classOnly.nodes, QuerySnapshotCodec.parse(render(classOnly, true)).graph.nodes)
    }

    @Test
    fun `legacy absence remains unknown even when an enum has no retained fields`() {
        val oldGraph = CodeGraph(graph.nodes.values.map { it.copy(enumConstants = null,
            attributes = it.attributes - NodeAttribute.ENUM_CONSTANT) }, graph.edges)
        for (compact in listOf(false, true)) {
            val text = render(oldGraph, compact)
            assertFalse("enumInventories" in text)
            QuerySnapshotCodec.parse(text).graph.nodes.values.forEach { assertNull(it.enumConstants) }
            val absent = change(render(graph, compact)) { it.remove("enumInventories") }
            QuerySnapshotCodec.parse(absent).graph.nodes.values.forEach { assertNull(it.enumConstants) }
        }
    }

    @Test
    fun `malformed inventories reject null duplicate unknown wrong kind and inconsistent fields`() {
        for (compact in listOf(false, true)) {
            val text = render(graph, compact)
            val inventories = (document(text)["graph"] as Map<*, *>)["enumInventories"] as List<*>
            val mutations: List<(MutableMap<Any?, Any?>) -> Unit> = listOf(
                { it["enumInventories"] = null },
                { it["enumInventories"] = inventories + inventories },
                { it["enumInventories"] = listOf(mapOf("usr" to owner.id.value, "constants" to null)) },
                { it["enumInventories"] = listOf(mapOf("usr" to owner.id.value, "constants" to emptyList<String>(), "complete" to true)) },
                { it["enumInventories"] = inventory("class:missing/State", emptyList()) },
                { it["enumInventories"] = inventory(ordinary.id.value, emptyList()) },
                { it["enumInventories"] = inventory(stub.id.value, emptyList()) },
                { it["enumInventories"] = inventory(first.value, emptyList()) },
                { it["enumInventories"] = inventory(owner.id.value, listOf(first.value, first.value)) },
                { it["enumInventories"] = inventory(owner.id.value, listOf(second.value, first.value)) },
                { it["enumInventories"] = inventory(owner.id.value, listOf("field:probe/Other#ALPHA:Lprobe/Other;")) },
                { it["enumInventories"] = inventory(owner.id.value, listOf("field:probe/State#ALPHA:I")) },
                { it["enumInventories"] = inventory(owner.id.value, listOf("method:probe/State#ALPHA()V")) },
                { it["enumInventories"] = inventory(owner.id.value, listOf(alias.value)) },
                { it["enumInventories"] = inventory(owner.id.value, emptyList()) },
            )
            mutations.forEach { mutation ->
                val invalid = change(text, mutation)
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(invalid) }
            }
            // 같은 field ID여도 ACC_ENUM marker가 없는 retained 선언으로 위조할 수 없다.
            val unmarked = change(text) { raw ->
                val index = graph.nodeIds.indexOf(first)
                raw["nodes"] = (raw["nodes"] as List<*>).mapIndexed { nodeIndex, value ->
                    if (nodeIndex != index) value else if (compact) (value as List<*>).toMutableList().apply {
                        this[9] = emptyList<Any>()
                    } else (value as Map<*, *>).toMutableMap().apply { this["attributes"] = emptyList<String>() }
                }
            }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(unmarked) }
        }
    }

    @Test
    fun `tagged fields require self descriptors and any retained owner must be an enum`() {
        val unavailable = graph.nodes.values.map { it.copy(enumConstants = null) }
        val invalidNodes = listOf(
            unavailable.map { if (it.id == first) it.copy(id = NodeId("field:probe/State#ALPHA:I")) else it },
            unavailable.map { if (it.id == owner.id) it.copy(kind = NodeKind.CLASS) else it },
            unavailable.map { if (it.id == owner.id) it.copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB)) else it },
        )
        for (compact in listOf(false, true)) {
            invalidNodes.forEach { nodes ->
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(render(CodeGraph(nodes, emptyList()), compact)) }
            }
            val pruned = CodeGraph(listOf(field(first)), emptyList())
            assertEquals(pruned.nodes, QuerySnapshotCodec.parse(render(pruned, compact)).graph.nodes)
        }
    }

    @Test
    fun `retained source impact documents freeze the captured list`() {
        val constants = mutableListOf(first, second)
        val mutableOwner = owner.copy(enumConstants = constants)
        val report = dev.kartograph.analysis.ImpactReport(
            listOf(dev.kartograph.analysis.ImpactNode(mutableOwner, setOf(dev.kartograph.analysis.ImpactRevision.CURRENT))),
            emptyList(), 0, emptyList(), emptyList(), dev.kartograph.analysis.ImpactTruncation())
        val frozen = sourceFrozenImpact(report)
        constants.clear()
        val captured = frozen.changed.single().node.enumConstants!!
        assertEquals(listOf(first, second), captured)
        assertFailsWith<UnsupportedOperationException> { (captured as MutableList<NodeId>).clear() }
    }

    private fun field(id: NodeId, marked: Boolean = true): GraphNode =
        GraphNode(id, id.value.substringAfter('#').substringBefore(':'), NodeKind.FIELD,
            attributes = if (marked) setOf(NodeAttribute.ENUM_CONSTANT) else emptySet())

    private fun render(value: CodeGraph, compact: Boolean): String =
        QuerySnapshotCodec.render(QuerySnapshot(value, emptyList(), emptyList()), compact)

    private fun inventory(usr: String, constants: List<String>): List<Map<String, Any>> =
        listOf(mapOf("usr" to usr, "constants" to constants))

    private fun document(text: String): Map<*, *> = SnapshotJsonParser(text).parse() as Map<*, *>

    private fun change(text: String, mutate: (MutableMap<Any?, Any?>) -> Unit): String {
        val document = document(text).toMutableMap()
        val graph = (document["graph"] as Map<*, *>).toMutableMap()
        mutate(graph)
        document["graph"] = graph
        return jsonValue(document)
    }
}
