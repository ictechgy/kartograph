package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnumInventoryTest {
    private val first = NodeId("field:probe/State#ALPHA:Lprobe/State;")
    private val second = NodeId("field:probe/State#BETA:Lprobe/State;")
    private val owner = GraphNode(NodeId("class:probe/State"), "State", NodeKind.ENUM,
        enumConstants = listOf(first, second))

    @Test
    fun `legacy and captured empty inventories remain distinct`() {
        assertNull(owner.copy(enumConstants = null).enumConstants)
        assertEquals(emptyList(), owner.copy(enumConstants = emptyList()).enumConstants)
        assertNull(GraphNode(NodeId("class:Legacy"), "Legacy", NodeKind.ENUM).enumConstants)
    }

    @Test
    fun `inventory survives graph views and ordinary node copies`() {
        val fields = listOf(first, second).map { GraphNode(it, it.value.substringAfter('#').substringBefore(':'),
            NodeKind.FIELD, attributes = setOf(NodeAttribute.ENUM_CONSTANT)) }
        val edges = fields.map { GraphEdge(owner.id, it.id, EdgeKind.MEMBER) }
        val graph = CodeGraph(listOf(owner) + fields, edges)
        val projections = listOf(
            CodeGraph(listOf(owner), graph.edges),
            CodeGraph(listOf(owner.copy(moduleName = "module")), emptyList()),
            graph.enrichedWith(emptyList(), replacingOrigin = EdgeOrigin.BYTECODE),
        )
        projections.forEach { projection ->
            assertEquals(listOf(first, second), projection.node(owner.id)!!.enumConstants)
        }
        assertEquals(0, projections.first().edgeCount)
        assertEquals(listOf(first, second), graph.node(owner.id)!!.enumConstants)
    }

    @Test
    fun `duplicate capture conflicts are order independent and never unioned`() {
        val alternatives = listOf(
            owner.copy(enumConstants = listOf(first)),
            owner.copy(enumConstants = emptyList()),
            owner.copy(enumConstants = null),
            owner.copy(moduleName = "other"),
            owner.copy(location = SourceLocation("other/State.java")),
        )
        alternatives.forEach { alternative ->
            listOf(listOf(owner, alternative), listOf(alternative, owner)).forEach { inputs ->
                val graph = CodeGraph(inputs, emptyList())
                assertNull(graph.node(owner.id)!!.enumConstants)
                assertEquals(inputs.first().moduleName, graph.node(owner.id)!!.moduleName)
            }
        }
        assertEquals(owner.enumConstants, CodeGraph(listOf(owner, owner.copy()), emptyList()).node(owner.id)!!.enumConstants)
        assertNull(CodeGraph(listOf(owner, alternatives.first(), owner), emptyList()).node(owner.id)!!.enumConstants)
    }

    @Test
    fun `inventory validates owner kind membership descriptor order and uniqueness`() {
        listOf(
            { owner.copy(kind = NodeKind.CLASS) },
            { owner.copy(id = NodeId("method:probe/State#run()V")) },
            { owner.copy(id = NodeId("class:probe//State"), enumConstants = emptyList()) },
            { owner.copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB)) },
            { owner.copy(enumConstants = listOf(first, first)) },
            { owner.copy(enumConstants = listOf(second, first)) },
            { owner.copy(enumConstants = listOf(NodeId("field:probe/Other#ALPHA:Lprobe/Other;"))) },
            { owner.copy(enumConstants = listOf(NodeId("field:probe/State#ALPHA:I"))) },
            { owner.copy(enumConstants = listOf(NodeId("field:probe/State#ALPHA:[Lprobe/State;"))) },
            { owner.copy(enumConstants = listOf(NodeId("field:probe/State#:Lprobe/State;"))) },
            { owner.copy(enumConstants = listOf(NodeId("field:probe/State#bad/name:Lprobe/State;"))) },
            { owner.copy(attributes = setOf(NodeAttribute.ENUM_CONSTANT)) },
        ).forEach { invalid -> assertFailsWith<IllegalArgumentException> { invalid() } }
    }
}
