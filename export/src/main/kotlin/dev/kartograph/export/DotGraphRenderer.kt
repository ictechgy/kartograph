package dev.kartograph.export

import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeKind

/** 로컬 source path를 노출하지 않고 코드 그래프를 결정적인 Graphviz DOT로 렌더링한다. */
public object DotGraphRenderer {
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
            "shape=${node.kind.shape}",
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

    private fun escape(value: String): String = buildString {
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

private val NodeKind.shape: String
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
