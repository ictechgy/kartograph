package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.export.FindingConfidence
import kotlin.test.Test
import kotlin.test.assertEquals

class RetentionPipelineTest {
    @Test
    fun `runtime confidence resolves owners through member edges and class kind`() {
        val graph = CodeGraph(
            nodes = listOf(
                GraphNode(NodeId("class:Pro"), "Pro", NodeKind.CLASS),
                GraphNode(NodeId("class:Pro#e"), "Pro#e", NodeKind.CLASS),
                GraphNode(NodeId("method:Pro#e#run()V"), "run", NodeKind.METHOD),
            ),
            edges = listOf(
                GraphEdge(NodeId("class:Pro#e"), NodeId("method:Pro#e#run()V"), EdgeKind.MEMBER),
            ),
        )

        // JVMS상 합법인 '#' 포함 class 이름이 다른 class(Pro)의 관측으로 승격되면 안 된다.
        // 위치가 없고 관측도 없으면 기존 규칙대로 unmeasured다.
        assertEquals(
            FindingConfidence.UNMEASURED,
            RetentionPipeline.confidenceOf(Finding(NodeId("class:Pro#e"), null), graph, emptyMap(), setOf("Pro")),
        )
        assertEquals(
            FindingConfidence.RUNTIME_OBSERVED,
            RetentionPipeline.confidenceOf(Finding(NodeId("class:Pro#e"), null), graph, emptyMap(), setOf("Pro#e")),
        )
        // member finding의 owner도 MEMBER 간선에서 오므로 같은 규칙을 따른다.
        assertEquals(
            FindingConfidence.RUNTIME_OBSERVED,
            RetentionPipeline.confidenceOf(Finding(NodeId("method:Pro#e#run()V"), null), graph, emptyMap(), setOf("Pro#e")),
        )
        assertEquals(
            FindingConfidence.UNMEASURED,
            RetentionPipeline.confidenceOf(Finding(NodeId("method:Pro#e#run()V"), null), graph, emptyMap(), setOf("Pro")),
        )
    }
}
