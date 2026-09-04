package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeepAnnotationRetentionTest {
    @Test
    fun `turns Keep annotations into sorted retention evidence`() {
        val graph = CodeGraph(
            nodes = listOf(
                node("b", annotations = setOf("androidx/annotation/Keep")),
                node("ignored", annotations = setOf("dev/example/Other")),
                node(
                    "a",
                    annotations = setOf("androidx/annotation/Keep"),
                    location = SourceLocation("src/A.kt", line = 3),
                ),
            ),
            edges = emptyList(),
        )

        val evidence = KeepAnnotationRetention.find(graph)

        assertEquals(listOf(NodeId("a"), NodeId("b")), evidence.map { it.nodeId })
        assertEquals(setOf(RetentionReason.KEEP_ANNOTATION), evidence.map { it.reason }.toSet())
        assertEquals(SourceLocation("src/A.kt", line = 3), evidence.first().location)
        assertNull(evidence.last().location)
    }

    @Test
    fun `retains an owning class when Keep is declared on its member`() {
        val owner = node("class:dev/fixture/Owner", annotations = emptySet())
        val keptMethod = GraphNode(
            id = NodeId("method:dev/fixture/Owner#run()V"),
            name = "run",
            kind = NodeKind.METHOD,
            annotations = setOf("androidx/annotation/Keep"),
            location = SourceLocation("Owner.kt", line = 4),
        )
        val graph = CodeGraph(
            nodes = listOf(owner, keptMethod),
            edges = listOf(GraphEdge(owner.id, keptMethod.id, EdgeKind.MEMBER)),
        )

        val evidence = KeepAnnotationRetention.find(graph)

        assertEquals(listOf(owner.id, keptMethod.id), evidence.map { it.nodeId })
        assertEquals(RetentionReason.KEEP_ANNOTATED_MEMBER, evidence.first().reason)
        assertEquals(SourceLocation("Owner.kt", line = 4), evidence.first().location)
    }

    @Test
    fun `retains direct members when Keep is declared on their class`() {
        val keptClass = node(
            "class:dev/fixture/KeptType",
            annotations = setOf("androidx/annotation/Keep"),
        )
        val member = GraphNode(
            id = NodeId("method:dev/fixture/KeptType#entry()V"),
            name = "entry",
            kind = NodeKind.METHOD,
        )
        val graph = CodeGraph(
            nodes = listOf(keptClass, member),
            edges = listOf(GraphEdge(keptClass.id, member.id, EdgeKind.MEMBER)),
        )

        val evidence = KeepAnnotationRetention.find(graph)

        assertEquals(listOf(keptClass.id, member.id), evidence.map { it.nodeId })
        assertEquals(RetentionReason.KEEP_ANNOTATED_CLASS_MEMBER, evidence.last().reason)
    }

    private fun node(
        id: String,
        annotations: Set<String>,
        location: SourceLocation? = null,
    ): GraphNode = GraphNode(
        id = NodeId(id),
        name = id,
        kind = NodeKind.CLASS,
        annotations = annotations,
        location = location,
    )
}
