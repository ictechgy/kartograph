package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeOrigin
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
import kotlin.test.assertFalse
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
    fun `distinct evidence origins retain independent weights and deterministic output`() {
        val edges = listOf(
            GraphEdge(methodId, classId, EdgeKind.REFERENCE, 2),
            GraphEdge(methodId, classId, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL),
            GraphEdge(methodId, classId, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL),
        )
        val mixed = CodeGraph(graph.nodes.values, edges)
        assertEquals(2, mixed.edges.size)
        assertEquals(listOf(2, 2), mixed.edges.map { it.weight })
        val json = GraphJsonRenderer.render(mixed, "test")
        assertTrue(json.contains("\"origin\": \"runtimeModel\""))
        assertEquals(json, GraphJsonRenderer.render(CodeGraph(graph.nodes.values.reversed(), edges.reversed()), "test"))
        assertTrue(DotGraphRenderer.render(mixed).contains("tooltip=\"runtime_model\""))
    }

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
    fun `reduces an unresolved source attribute to its file name so local paths never leak`() {
        val leaking = CodeGraph(
            nodes = listOf(
                GraphNode(
                    id = NodeId("class:a/Absolute"),
                    name = "Absolute",
                    kind = NodeKind.CLASS,
                    location = SourceLocation("/Users/someone/work/secret/Absolute.kt", line = 7),
                ),
                GraphNode(
                    id = NodeId("class:a/Windows"),
                    name = "Windows",
                    kind = NodeKind.CLASS,
                    location = SourceLocation("""C:\\build\\agent\\Windows.kt"""),
                ),
                GraphNode(
                    id = NodeId("class:a/Empty"),
                    name = "Empty",
                    kind = NodeKind.CLASS,
                    location = SourceLocation("/", line = 2),
                ),
            ),
            edges = emptyList(),
        )

        val output = GraphJsonRenderer.render(leaking, "9.9.9-test")

        assertTrue(output.contains("""location": {"line": 7, "path": "Absolute.kt", "pathKind": "sourceFileName"}"""))
        assertTrue(output.contains("""location": {"path": "Windows.kt", "pathKind": "sourceFileName"}"""))
        // 남는 파일 이름이 없으면 위치를 아예 싣지 않는다.
        assertTrue(output.contains("""kind": "class", "name": "Empty"""))
        assertFalse(output.contains("/Users/"))
        assertFalse(output.contains("build"))
    }

    @Test
    fun `escapes non ascii and surrogate pairs so the document is charset independent`() {
        // U+10400은 UTF-16에서 surrogate pair로 표현되는 유효 식별자 문자다.
        val supplementary = "\uD801\uDC00Cls"
        val graph = CodeGraph(
            nodes = listOf(GraphNode(NodeId("class:$supplementary"), supplementary, NodeKind.CLASS)),
            edges = emptyList(),
        )

        val output = GraphJsonRenderer.render(graph, "9.9.9-test")

        // JSON 규격대로 surrogate pair는 두 개의 \uXXXX로 나가고, 문서 전체가 순수 ASCII가 된다.
        assertTrue(output.contains("""usr": "class:\ud801\udc00Cls"""))
        assertTrue(output.all { character -> character.code in 0x20..0x7E || character == '\n' })
    }

    @Test
    fun `exposes the exchange format identity used by consumers`() {
        assertEquals("code-graph", GraphJsonRenderer.FORMAT)
        assertEquals(1, GraphJsonRenderer.VERSION)
    }
}
