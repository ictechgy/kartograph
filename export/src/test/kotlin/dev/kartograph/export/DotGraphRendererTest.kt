package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotGraphRendererTest {
    @Test
    fun `renders nodes and weighted edges in deterministic order`() {
        val graph = CodeGraph(
            nodes = listOf(
                GraphNode(NodeId("class:b"), "B", NodeKind.CLASS, synthesized = true),
                GraphNode(NodeId("method:a#run()V"), "run", NodeKind.METHOD),
                GraphNode(NodeId("class:a"), "A", NodeKind.CLASS),
            ),
            edges = listOf(
                GraphEdge(NodeId("method:a#run()V"), NodeId("class:b"), EdgeKind.CALL, weight = 2),
                GraphEdge(NodeId("class:a"), NodeId("method:a#run()V"), EdgeKind.MEMBER),
            ),
        )

        assertEquals(
            """
                digraph kartograph {
                  rankdir=LR;
                  "class:a" [label="A", shape=box];
                  "class:b" [label="B", shape=box, style=dashed];
                  "method:a#run()V" [label="run", shape=ellipse];
                  "class:a" -> "method:a#run()V" [label="member"];
                  "method:a#run()V" -> "class:b" [label="call", weight=2];
                }

            """.trimIndent(),
            DotGraphRenderer.render(graph),
        )
    }

    @Test
    fun `escapes identifiers and labels without exposing source paths`() {
        val graph = CodeGraph(
            nodes = listOf(
                GraphNode(
                    id = NodeId("class:a\"b"),
                    name = "A\\B\nquoted \"name\"",
                    kind = NodeKind.PROPERTY,
                ),
            ),
            edges = emptyList(),
        )

        val output = DotGraphRenderer.render(graph)

        assertTrue(output.contains("\"class:a\\\"b\""))
        assertTrue(output.contains("label=\"A\\\\B\\nquoted \\\"name\\\"\""))
        assertTrue(output.contains("shape=note"))
    }
}
