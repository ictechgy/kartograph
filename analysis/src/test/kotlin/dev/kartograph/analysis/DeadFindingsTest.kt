package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadFindingsTest {
    @Test
    fun `member scope excludes runtime generated native public and dead owner noise`() {
        val owner = GraphNode(NodeId("class:Owner"), "Owner", NodeKind.CLASS)
        val dead = owner.copy(id = NodeId("class:Dead"), name = "Dead")
        val generated = owner.copy(id = NodeId("class:Generated"), name = "Generated", synthesized = true)
        fun member(name: String, kind: NodeKind = NodeKind.METHOD) = GraphNode(
            NodeId("method:Owner#$name"), name, kind, visibility = Visibility.PRIVATE,
        )
        val wanted = member("wanted")
        val property = member("property", NodeKind.PROPERTY)
        val excluded = listOf(
            member("public").copy(visibility = Visibility.PUBLIC),
            member("jvmPublic").copy(jvmVisibility = Visibility.PUBLIC),
            member("native").copy(jvmModifiers = setOf(JvmModifier.NATIVE)),
            member("constructor", NodeKind.CONSTRUCTOR),
            member("synthetic").copy(synthesized = true),
            member("constant", NodeKind.FIELD).copy(attributes = setOf(NodeAttribute.COMPILE_TIME_CONSTANT)),
            member("used"),
        )
        val inline = member("inlined").copy(attributes = setOf(NodeAttribute.INLINE_FUNCTION))
        val serialization = member("readObject")
        val privateThis = member("privateThis", NodeKind.FUNCTION).copy(visibility = Visibility.PRIVATE_TO_THIS)
        val ambiguous = member("ambiguous")
        val orphan = member("orphan")
        val deadMember = member("deadOwner")
        val generatedMember = member("generatedOwner")
        val owned = listOf(wanted, property, inline, serialization, privateThis, ambiguous) + excluded
        val graph = CodeGraph(listOf(owner, dead, generated, orphan, deadMember, generatedMember) + owned,
            owned.map { GraphEdge(owner.id, it.id, EdgeKind.MEMBER) } + listOf(
                GraphEdge(dead.id, deadMember.id, EdgeKind.MEMBER),
                GraphEdge(generated.id, generatedMember.id, EdgeKind.MEMBER),
                GraphEdge(dead.id, ambiguous.id, EdgeKind.MEMBER),
            ))
        val result = ReachabilityAnalyzer.analyze(graph, listOf(owner, generated, excluded.last()).map {
            RetentionEvidence(it.id, RetentionReason.KEEP_RULE, null)
        })
        assertEquals(listOf(dead.id), DeadFindings.collect(graph, result).map { it.nodeId })
        assertEquals(listOf(dead.id, property.id, wanted.id, privateThis.id).sorted(),
            DeadFindings.collect(graph, result, true).map { it.nodeId })
    }
}
