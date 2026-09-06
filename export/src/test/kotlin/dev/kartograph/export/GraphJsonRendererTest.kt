package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphJsonRendererTest {
    private val classId = NodeId("class:a/B")
    private val methodId = NodeId("method:a/B#run()V")

    private val graph = CodeGraph(
        nodes = listOf(
            GraphNode(
                id = methodId,
                name = "run",
                kind = NodeKind.METHOD,
                visibility = Visibility.PRIVATE,
                synthesized = true,
            ),
            GraphNode(
                id = classId,
                name = "B",
                kind = NodeKind.CLASS,
                moduleName = "a",
                location = SourceLocation("B.kt", line = 3),
                visibility = Visibility.PUBLIC,
                attributes = setOf(NodeAttribute.DATA_CLASS),
            ),
        ),
        edges = listOf(
            GraphEdge(methodId, classId, EdgeKind.FIELD_ACCESS, weight = 2),
            GraphEdge(classId, methodId, EdgeKind.MEMBER),
        ),
    )

    @Test
    fun `renders resolved project relative paths in deterministic key order`() {
        val expected =
            """{"edges": [{"kind": "member", "source": "class:a/B", "target": "method:a/B#run()V", "weight": 1}, """ +
                """{"kind": "fieldAccess", "source": "method:a/B#run()V", "target": "class:a/B", "weight": 2}], """ +
                """"format": "code-graph", "limitations": [], "nodes": [{"accessibility": "public", """ +
                """"attributes": ["dataClass"], "kind": "class", "location": {"line": 3, "path": "app/src/B.kt", """ +
                """"pathKind": "projectRelative"}, "module": "a", "name": "B", "qualifiedName": "a.B", """ +
                """"synthesized": false, "usr": "class:a/B"}, {"accessibility": "private", "kind": "method", """ +
                """"name": "run", "qualifiedName": "a.B.run", "synthesized": true, "usr": "method:a/B#run()V"}], """ +
                """"tool": {"name": "kartograph", "version": "9.9.9-test"}, "version": 1}""" + "\n"

        assertEquals(expected, GraphJsonRenderer.render(graph, "9.9.9-test", mapOf(classId to "app/src/B.kt")))
    }

    @Test
    fun `keeps the source file name and sorts limitations when no path is resolved`() {
        val output = GraphJsonRenderer.render(
            graph,
            "9.9.9-test",
            limitations = listOf("missing-source-paths: 1 of 2", "unresolved-source-paths: 1 of 1"),
        )

        assertTrue(output.contains("""location": {"line": 3, "path": "B.kt", "pathKind": "sourceFileName"}"""))
        assertTrue(
            output.contains("""limitations": ["missing-source-paths: 1 of 2", "unresolved-source-paths: 1 of 1"]"""),
        )
        assertTrue(output.contains("""format": "code-graph"""))
        assertTrue(output.contains("""version": 1}"""))
    }

    @Test
    fun `escapes declaration names without emitting raw control characters`() {
        val escaped = CodeGraph(
            nodes = listOf(GraphNode(NodeId("class:a\"b"), "A\\B\nquoted \"name\"", NodeKind.PROPERTY)),
            edges = emptyList(),
        )

        val output = GraphJsonRenderer.render(escaped, "9.9.9-test")

        assertTrue(output.contains("""name": "A\\B\nquoted \"name\"""" + "\""))
        assertTrue(output.contains("""usr": "class:a\"b"""))
        assertEquals(1, output.count { character -> character == '\n' })
    }

    @Test
    fun `exposes the exchange format identity used by consumers`() {
        assertEquals("code-graph", GraphJsonRenderer.FORMAT)
        assertEquals(1, GraphJsonRenderer.VERSION)
    }
}
