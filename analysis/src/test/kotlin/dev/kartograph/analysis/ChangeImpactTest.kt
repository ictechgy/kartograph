package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class ChangeImpactTest {
    private fun node(name: String, file: String = "$name.kt") = GraphNode(NodeId("class:p/$name"), name, NodeKind.CLASS,
        location = SourceLocation(file))
    private fun edge(from: String, to: String, origin: EdgeOrigin = EdgeOrigin.BYTECODE) =
        GraphEdge(NodeId("class:p/$from"), NodeId("class:p/$to"), EdgeKind.CALL, origin = origin)
    private fun input(vararg edges: GraphEdge): ImpactInput = ImpactInput(CodeGraph(
        listOf(node("A"), node("B"), node("C"), node("Unused")), edges.toList()))

    @Test fun `reverse closure carries actual dependency paths and runtime origins`() {
        val result = ChangeImpact.analyze(input(edge("A", "B"), edge("B", "C", EdgeOrigin.RUNTIME_MODEL)), listOf("C"))
        assertEquals(listOf("A", "B"), result.affected.map { it.node.name })
        val path = result.affected.first().paths.single()
        assertEquals(listOf("class:p/A", "class:p/B"), path.edges.map { it.source.value })
        assertEquals(listOf(EdgeOrigin.BYTECODE, EdgeOrigin.RUNTIME_MODEL), path.edges.map { it.origin })
        assertEquals(NodeId("class:p/C"), path.changed)
        assertTrue(result.limitations.any { it.startsWith("potential-impact:") })
    }

    @Test fun `base and current never form a path that existed in neither revision`() {
        val result = ChangeImpact.analyze(input(edge("A", "B")), listOf("C"), base = input(edge("B", "C")))
        assertEquals(listOf("B"), result.affected.map { it.node.name })
        assertEquals(ImpactRevision.BASE, result.affected.single().paths.single().revision)
    }

    @Test fun `deleted declarations and unchanged callers remain visible`() {
        val current = ImpactInput(CodeGraph(listOf(node("A")), emptyList()))
        val base = input(edge("A", "C"))
        val result = ChangeImpact.analyze(current, files = listOf("src/p/C.kt"), base = base)
        assertEquals(listOf("C"), result.changed.map { it.node.name })
        assertEquals(setOf(ImpactRevision.BASE), result.changed.single().presentIn)
        assertEquals(listOf("A"), result.affected.map { it.node.name })
        assertTrue(result.limitations.any { it.startsWith("source-file-candidates:") })
    }

    @Test fun `ambiguity unknown files and truncation cannot look like an empty successful check`() {
        val nodes = listOf(node("A"), node("B"), node("C"), node("C").copy(id = NodeId("class:other/C")))
        val input = ImpactInput(CodeGraph(nodes, listOf(edge("A", "B"), edge("B", "C"))))
        val unresolved = ChangeImpact.analyze(input, listOf("C", "Missing"), listOf("settings.gradle.kts"))
        assertEquals(3, unresolved.unresolved.size)
        assertEquals(2, unresolved.unresolved.first { it.reason == "ambiguous" }.candidates.size)
        val limited = ChangeImpact.analyze(input, listOf("class:p/C"), depth = 1)
        assertEquals(listOf("B"), limited.affected.map { it.node.name })
        assertTrue(limited.truncated.depth)
        val capped = ChangeImpact.analyze(input, listOf("class:p/C"), limit = 1)
        assertTrue(capped.truncated.results)
        assertEquals(2, capped.totalAffected)
    }

    @Test fun `cycles duplicate selectors and node order have deterministic results`() {
        val original = input(edge("A", "B"), edge("B", "A"), edge("B", "C"))
        val a = ChangeImpact.analyze(original, listOf("C", "C"))
        val b = ChangeImpact.analyze(original.copy(graph = CodeGraph(original.graph.nodes.values.reversed(), original.graph.edges.reversed())), listOf("C"))
        assertEquals(a, b)
        assertEquals(2, a.totalAffected)
        assertFalse(a.truncated.depth)
        assertTrue(ChangeImpact.analyze(original, listOf("C"), visitLimit = 1).truncated.budget)
    }

    @Test fun `configuration selection uses retention evidence but not invented runtime callers`() {
        val target = NodeId("class:p/C")
        val evidence = RetentionEvidence(target, RetentionReason.MANIFEST_COMPONENT, SourceLocation("AndroidManifest.xml", 3))
        val data = input(edge("A", "C")).copy(retention = listOf(evidence))
        val result = ChangeImpact.analyze(data, files = listOf("AndroidManifest.xml"))
        assertEquals(target, result.changed.single().node.id)
        assertEquals(listOf(ImpactRetention(ImpactRevision.CURRENT, evidence)), result.changed.single().retention)
        assertEquals(listOf("A"), result.affected.map { it.node.name })
    }

    @Test fun `changing an interface contract includes implementing declarations`() {
        val data = input(GraphEdge(NodeId("class:p/A"), NodeId("class:p/B"), EdgeKind.OVERRIDE))
        val result = ChangeImpact.analyze(data, listOf("A"))
        assertEquals(listOf("B"), result.affected.map { it.node.name })
        assertEquals(listOf(NodeId("class:p/B"), NodeId("class:p/A")), result.affected.single().paths.single().nodes)
        val siblings = input(GraphEdge(NodeId("class:p/A"), NodeId("class:p/B"), EdgeKind.OVERRIDE),
            GraphEdge(NodeId("class:p/A"), NodeId("class:p/C"), EdgeKind.OVERRIDE))
        assertFalse(ChangeImpact.analyze(siblings, listOf("B")).affected.any { it.node.name == "C" })
    }

    @Test fun `path budget preserves observed count and never returns a broken witness`() {
        val result = ChangeImpact.analyze(input(edge("A", "B"), edge("B", "C")), listOf("C"), pathLimit = 1)
        assertTrue(result.truncated.budget)
        assertEquals(2, result.totalAffected)
        assertEquals(listOf("B"), result.affected.map { it.node.name })
        assertEquals(1, result.affected.single().paths.single().edges.size)
    }

    @Test fun `source aliases in the base still select a stable JVM identity`() {
        val original = input()
        val current = ImpactInput(CodeGraph(original.graph.nodes.values.map { if (it.name == "C") it.copy(name = "Renamed") else it }, original.graph.edges))
        val result = ChangeImpact.analyze(current, listOf("C"), base = original)
        assertTrue(result.unresolved.isEmpty())
        assertEquals(NodeId("class:p/C"), result.changed.single().node.id)
        assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), result.changed.single().presentIn)
    }
}
