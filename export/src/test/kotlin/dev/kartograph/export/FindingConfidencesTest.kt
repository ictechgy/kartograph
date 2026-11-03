package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/** CLI와 Gradle plugin이 공유하는 dead finding 등급 규칙이다. plugin이 binding 후보만 등급화하던 회귀를 막는다. */
class FindingConfidencesTest {
    @Test
    fun `every finding is graded from per-source runtime channels, binding review and runtime observations`() {
        val clean = GraphNode(NodeId("class:app/Clean"), "Clean", NodeKind.CLASS)
        val noisy = GraphNode(NodeId("class:app/Noisy"), "Noisy", NodeKind.CLASS)
        val unknown = GraphNode(NodeId("class:app/Unknown"), "Unknown", NodeKind.CLASS)
        val binding = GraphNode(NodeId("class:app/Binding"), "Binding", NodeKind.CLASS)
        val observedOwner = GraphNode(NodeId("class:app/Observed"), "Observed", NodeKind.CLASS)
        val observedMethod = GraphNode(NodeId("method:app/Observed#run()V"), "run", NodeKind.METHOD)
        val graph = CodeGraph(listOf(clean, noisy, unknown, binding, observedOwner, observedMethod),
            listOf(GraphEdge(observedOwner.id, observedMethod.id, EdgeKind.MEMBER)))
        val findings = listOf(
            Finding(clean.id, SourceLocation("src/Clean.kt", 1)),
            Finding(noisy.id, SourceLocation("src/Noisy.kt", 1)),
            Finding(unknown.id, null),
            Finding(binding.id, SourceLocation("src/Clean.kt", 2)),
            Finding(observedMethod.id, SourceLocation("src/Noisy.kt", 3)),
        )
        val graded = FindingConfidences.forFindings(findings, graph, mapOf("Clean.kt" to 0, "Noisy.kt" to 2), setOf("app/Observed")) {
            it == binding.id || it == observedMethod.id
        }
        assertEquals(mapOf(
            clean.id to FindingConfidence.STATIC,
            noisy.id to FindingConfidence.REVIEW,
            unknown.id to FindingConfidence.UNMEASURED,
            binding.id to FindingConfidence.REVIEW,
            observedMethod.id to FindingConfidence.RUNTIME_OBSERVED,
        ), graded)
    }
}
