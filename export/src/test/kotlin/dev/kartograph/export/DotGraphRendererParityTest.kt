package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DotGraphRendererParityTest {
    private val escape = DotGraphRenderer::class.java.getDeclaredMethod("escape", String::class.java)
        .apply { isAccessible = true }

    private fun assertEscape(value: String) {
        val expected = PreviousDotGraphRenderer.escape(value)
        val actual = escape.invoke(DotGraphRenderer, value) as String
        assertEquals(expected, actual)
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `preserves empty ASCII quotes slashes and controls`() {
        listOf("", "ordinary ASCII", "\\\"\n\r\t", "\u0000\b\u000c\u001f\u007f", "mixed \\path\"\tline\n")
            .forEach(::assertEscape)
        assertEquals("\\\\\\\"\\n\\r\\t", escape.invoke(DotGraphRenderer, "\\\"\n\r\t"))
    }

    @Test
    fun `preserves every UTF16 code unit before encoding`() {
        assertEscape(CharArray(65536) { it.toChar() }.concatToString())
    }

    @Test
    fun `preserves Unicode and malformed surrogate sequences`() {
        listOf("한글 café e\u0301 😀", "\ud800", "\udfff", "\ud800x\udfff", "\ud800\ud800\udfff\udfff",
            "\ud83d\ude00\n\ud83d\\\ude00").forEach(::assertEscape)
    }

    @Test
    fun `preserves fixed seed mixed strings`() {
        val random = Random(41987)
        repeat(256) { assertEscape(CharArray(random.nextInt(0, 256)) { random.nextInt(65536).toChar() }.concatToString()) }
    }

    @Test
    fun `preserves empty full graph bytes`() {
        assertGraph(CodeGraph(emptyList(), emptyList()))
    }

    @Test
    fun `preserves all node shapes origins and weighted full graph bytes`() {
        val nodes = NodeKind.entries.mapIndexed { index, kind ->
            GraphNode(NodeId("node:$index\\\"\n\r\t한글"), "name:$index\\\"\n\r\t😀", kind, synthesized = index % 2 == 0)
        }
        val edges = EdgeOrigin.entries.flatMapIndexed { index, origin ->
            EdgeKind.entries.map { kind -> GraphEdge(nodes[index].id, nodes[(index + 1) % nodes.size].id,
                kind, weight = index + 1, origin = origin) }
        }
        assertGraph(CodeGraph(nodes.reversed(), edges.reversed()))
    }

    @Test
    fun `preserves full graph containing malformed surrogate identifiers`() {
        val nodes = listOf(GraphNode(NodeId("node:\ud800"), "name:\udfff", NodeKind.CLASS),
            GraphNode(NodeId("node:\udfff"), "name:\ud800", NodeKind.METHOD))
        assertGraph(CodeGraph(nodes, listOf(GraphEdge(nodes[0].id, nodes[1].id, EdgeKind.CALL))))
    }

    private fun assertGraph(graph: CodeGraph) {
        val expected = PreviousDotGraphRenderer.render(graph)
        val actual = DotGraphRenderer.render(graph)
        assertEquals(expected, actual)
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }
}

// ab056981의 renderer를 이름과 escape 가시성만 바꿔 보존한 독립 오라클이다.
/** 로컬 source path를 노출하지 않고 코드 그래프를 결정적인 Graphviz DOT로 렌더링한다. */
private object PreviousDotGraphRenderer {
    /** 정렬된 정점과 간선을 하나의 directed graph 문서로 만든다. */
    public fun render(graph: CodeGraph): String = buildString {
        appendLine("digraph kartograph {")
        appendLine("  rankdir=LR;")
        graph.nodeIds.forEach { nodeId -> appendLine("  ${renderNode(graph.nodes.getValue(nodeId))}") }
        graph.edges.forEach { edge -> appendLine("  ${renderEdge(edge)}") }
        appendLine("}")
    }

    private fun renderNode(node: GraphNode): String {
        val attributes = mutableListOf(
            "label=\"${escape(node.name)}\"",
            "shape=${node.kind.previousShape}",
        )
        if (node.synthesized) attributes += "style=dashed"
        return "\"${escape(node.id.value)}\" [${attributes.joinToString()}];"
    }

    private fun renderEdge(edge: GraphEdge): String {
        val attributes = mutableListOf("label=\"${edge.kind.name.lowercase()}\"")
        if (edge.origin != EdgeOrigin.BYTECODE) attributes += "tooltip=\"${edge.origin.name.lowercase()}\""
        if (edge.weight > 1) attributes += "weight=${edge.weight}"
        return "\"${escape(edge.source.value)}\" -> \"${escape(edge.target.value)}\" " +
            "[${attributes.joinToString()}];"
    }

    fun escape(value: String): String = buildString {
        value.forEach { character ->
            append(
                when (character) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> character
                },
            )
        }
    }
}

private val NodeKind.previousShape: String
    get() = when (this) {
        NodeKind.CLASS,
        NodeKind.INTERFACE,
        NodeKind.OBJECT,
        NodeKind.ENUM,
        NodeKind.ANNOTATION_CLASS,
        -> "box"
        NodeKind.FUNCTION,
        NodeKind.METHOD,
        NodeKind.CONSTRUCTOR,
        -> "ellipse"
        NodeKind.PROPERTY,
        NodeKind.FIELD,
        -> "note"
    }
