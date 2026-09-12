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

    @Test fun `visit budget reports the revision without inventing an affected candidate`() {
        val result = ChangeImpact.analyze(input(edge("A", "C")), listOf("C"), visitLimit = 1)

        assertTrue(result.affected.isEmpty())
        assertTrue(result.truncated.budget)
        assertEquals(1, result.budgets.visited[ImpactRevision.CURRENT])
        assertEquals(setOf(ImpactRevision.CURRENT), result.budgets.visitLimitReached)
        assertTrue(result.limitations.any { it.startsWith("visit-budget:") })
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
        assertEquals(listOf("A", "B"), result.affected.map { it.node.name })
        assertEquals(ImpactPathStatus.UNAVAILABLE, result.affected.first().pathStatus)
        assertEquals(1, result.affected.first().pathOmissions.size)
        assertEquals(1, result.affected.last().paths.single().edges.size)
    }

    @Test fun `overlapping revisions retain independent paths and observed revisions`() {
        val base = input(edge("A", "C"), edge("B", "C"))
        val current = input(edge("A", "C"))
        val result = ChangeImpact.analyze(current, listOf("C"), base = base)

        assertEquals(listOf("A", "B"), result.affected.map { it.node.name })
        assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), result.affected.first().observedIn)
        assertEquals(listOf(ImpactRevision.BASE, ImpactRevision.CURRENT), result.affected.first().paths.map { it.revision })
        assertEquals(setOf(ImpactRevision.BASE), result.affected.last().observedIn)
        assertEquals(listOf(ImpactRevision.BASE), result.affected.last().paths.map { it.revision })
    }

    @Test fun `navigation filters and pages the complete observed set`() {
        val target = node("Target", "src/main/kotlin/p/Target.kt").copy(moduleName = "app")
        val direct = node("Direct", "src/main/kotlin/p/Direct.kt").copy(moduleName = "app")
        val test = node("TestCaller", "src/test/kotlin/p/TestCaller.kt").copy(moduleName = "tests")
        val structural = node("Impl", "src/main/kotlin/p/Impl.kt").copy(moduleName = "app", kind = NodeKind.OBJECT)
        val graph = CodeGraph(
            listOf(target, direct, test, structural),
            listOf(
                GraphEdge(direct.id, target.id, EdgeKind.CALL),
                GraphEdge(test.id, target.id, EdgeKind.REFERENCE),
                GraphEdge(target.id, structural.id, EdgeKind.OVERRIDE),
            ),
        )
        val result = ChangeImpact.analyze(
            ImpactInput(graph),
            symbols = listOf(target.id.value),
            offset = 1,
            limit = 1,
            filters = ImpactFilter(modules = setOf("app")),
            sort = ImpactSort.FILE,
        )

        assertEquals(3, result.navigation.observed.candidates)
        assertEquals(2, result.navigation.filtered.candidates)
        assertEquals(1, result.navigation.returned)
        assertTrue(result.truncated.results)
        assertEquals(listOf("Impl"), result.affected.map { it.node.name })
        assertEquals(1, result.navigation.observed.byTestStatus.single { it.value == "test" }.count)
        assertEquals(ImpactRelation.DIRECT, ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(testStatus = ImpactTestStatus.TEST)).affected.single().relation)

        val firstPage = ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), offset = 0, limit = 1)
        val secondPage = ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), offset = 1, limit = 1)
        assertEquals(3, firstPage.navigation.filtered.candidates)
        assertEquals(3, secondPage.navigation.observed.candidates)
        assertEquals(1, firstPage.affected.size)
        assertEquals(1, secondPage.affected.size)
        assertNotEquals(firstPage.affected.single().node.id, secondPage.affected.single().node.id)
        assertTrue(firstPage.navigation.hasNext)
        assertTrue(secondPage.navigation.hasPrevious)
    }

    @Test fun `navigation exposes every sort and focused filter without shrinking observation`() {
        val target = node("Target", "src/main/kotlin/p/Target.kt")
        val direct = node("Direct", "src/main/kotlin/p/Direct.kt")
        val test = node("TestCaller", "src/test/kotlin/p/TestCaller.kt")
        val structural = node("Impl", "src/main/kotlin/p/Impl.kt").copy(kind = NodeKind.OBJECT)
        val graph = CodeGraph(
            listOf(target, direct, test, structural),
            listOf(
                GraphEdge(direct.id, target.id, EdgeKind.CALL),
                GraphEdge(test.id, target.id, EdgeKind.REFERENCE),
                GraphEdge(target.id, structural.id, EdgeKind.OVERRIDE),
            ),
        )

        ImpactSort.entries.forEach { ordering ->
            val result = ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), sort = ordering)
            assertEquals(3, result.navigation.observed.candidates, ordering.name)
            assertEquals(3, result.navigation.filtered.candidates, ordering.name)
            assertEquals(3, result.affected.size, ordering.name)
        }
        assertEquals(listOf("TestCaller"), ChangeImpact.analyze(
            ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(affectedFiles = setOf("src/test/kotlin/p/TestCaller.kt")),
        ).affected.map { it.node.name })
        assertEquals(listOf("Impl"), ChangeImpact.analyze(
            ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(kinds = setOf(NodeKind.OBJECT), relation = ImpactRelation.STRUCTURAL),
        ).affected.map { it.node.name })
        assertEquals(listOf("Direct", "Impl", "TestCaller"), ChangeImpact.analyze(
            ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(pathStatus = ImpactPathStatus.COMPLETE),
        ).affected.map { it.node.name })
    }

    @Test fun `shared candidate reports partial path evidence instead of disappearing`() {
        val base = input(edge("A", "C"))
        val current = input(edge("A", "B"), edge("B", "C"))
        val result = ChangeImpact.analyze(current, listOf("C"), base = base, pathLimit = 1)

        val shared = result.affected.single { it.node.name == "A" }
        assertEquals(ImpactPathStatus.PARTIAL, shared.pathStatus)
        assertEquals(listOf(ImpactRevision.BASE), shared.paths.map { it.revision })
        assertEquals(listOf(ImpactRevision.CURRENT), shared.pathOmissions.map { it.revision })
        assertEquals(ImpactPathStatus.UNAVAILABLE, result.affected.single { it.node.name == "B" }.pathStatus)
        assertEquals(2, result.navigation.observed.candidates)
    }

    @Test fun `unknown source metadata remains unknown in facts and summaries`() {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS)
        val caller = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS)
        val result = ChangeImpact.analyze(
            ImpactInput(CodeGraph(listOf(target, caller), listOf(GraphEdge(caller.id, target.id, EdgeKind.CALL)))),
            listOf(target.id.value),
        )

        val affected = result.affected.single()
        assertEquals(ImpactTestStatus.UNKNOWN, affected.testStatus)
        assertEquals(ImpactTestStatus.UNKNOWN, affected.facts.single().testStatus)
        assertEquals(null, affected.facts.single().module)
        assertEquals(null, result.navigation.observed.byModule.single().value)
        assertEquals("unknown", result.navigation.observed.byTestStatus.single().value)
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
