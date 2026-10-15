package dev.kartograph.analysis

import dev.kartograph.core.*
import dev.kartograph.analysis.SourceUseAnalysisFixture.a
import dev.kartograph.analysis.SourceUseAnalysisFixture.b
import dev.kartograph.analysis.SourceUseAnalysisFixture.c
import dev.kartograph.analysis.SourceUseAnalysisFixture.d
import dev.kartograph.analysis.SourceUseAnalysisFixture.x
import dev.kartograph.analysis.SourceUseAnalysisFixture.defaultIds
import dev.kartograph.analysis.SourceUseAnalysisFixture.graph
import dev.kartograph.analysis.SourceUseAnalysisFixture.id
import dev.kartograph.analysis.SourceUseAnalysisFixture.node
import kotlin.test.*

class SourceUseQueryTest {
    @Test fun `incoming outgoing self and unrelated rows are selected exactly once without traversal`() {
        val graph = graph(listOf(a to b, b to c, b to b, d to c))
        val incoming = SourceUseQuery.inspect(graph, b).page!!
        assertEquals(listOf(a to b, b to b, b to c), incoming.observations.map { it.use.source to it.use.target })
        assertEquals(listOf(listOf("incoming"), listOf("incoming", "outgoing"), listOf("outgoing")),
            incoming.observations.map { it.directions })
        assertEquals(3, incoming.observedUses)
        assertEquals(2, incoming.observedDistinctCallers)
        assertEquals(listOf(a to b), SourceUseQuery.inspect(graph, a).page!!.observations.map { it.use.source to it.use.target })
    }

    @Test fun `only exact BYTECODE CALL pair correlates with a selection`() {
        val pairs = listOf(a to c, b to c, d to c, x to c)
        val graph = graph(pairs, listOf(
            GraphEdge(a, c, EdgeKind.CALL, weight = 7, callSiteLines = listOf(1, 3)),
            GraphEdge(b, c, EdgeKind.CALL, origin = EdgeOrigin.RUNTIME_MODEL),
            GraphEdge(d, c, EdgeKind.REFERENCE),
            GraphEdge(c, x, EdgeKind.CALL),
        ))
        assertEquals(mapOf(a to true, b to false, d to false, x to false),
            SourceUseQuery.inspect(graph, c).page!!.observations.associate { it.use.source to it.matchingBytecodeCall })
    }

    @Test fun `removing bytecode changes correlation without dropping source identities`() {
        val graph = graph(listOf(a to c), listOf(GraphEdge(a, c, EdgeKind.CALL)))
        val before = SourceUseQuery.inspect(graph, c)
        val without = graph.enrichedWith(emptyList(), replacingOrigin = EdgeOrigin.BYTECODE)
        val after = SourceUseQuery.inspect(without, c)
        assertEquals(before.page!!.observations.single().use, after.page!!.observations.single().use)
        assertTrue(before.page!!.observations.single().matchingBytecodeCall)
        assertFalse(after.page!!.observations.single().matchingBytecodeCall)
        assertEquals(before.coverage, after.coverage)
    }

    @Test fun `source page bounds are independent explicit and never clamped`() {
        assertEquals(SourceUsePageRequest(100, 0), SourceUsePageRequest())
        for (limit in listOf(1, 100, 1000)) assertEquals(limit, SourceUsePageRequest(limit, 200_000).limit)
        for (limit in listOf(Int.MIN_VALUE, -1, 0, 1001, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SourceUsePageRequest(limit) }
        }
        for (offset in listOf(Int.MIN_VALUE, -1, 200_001, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SourceUsePageRequest(offset = offset) }
        }
    }

    @Test fun `pagination retains repeated selectors and global caller count without repeating bindings`() {
        val graph = graph(listOf(a to c, a to c, b to c))
        val full = SourceUseQuery.inspect(graph, c).page!!
        val pages = (0..2).map { SourceUseQuery.inspect(graph, c, SourceUsePageRequest(1, it)).page!! }
        assertEquals(3, full.observedUses)
        assertEquals(2, full.observedDistinctCallers)
        assertEquals(3, full.observations.map { it.use.id }.toSet().size)
        assertEquals(full.observations, pages.flatMap { it.observations })
        pages.forEachIndexed { index, page ->
            assertEquals(3, page.observedUses)
            assertEquals(2, page.observedDistinctCallers)
            assertEquals(1, page.returnedUses)
            assertEquals(1, page.returnedDistinctCallers)
            assertEquals(index, page.omittedBefore)
            assertEquals(2 - index, page.omittedAfter)
            assertEquals(index > 0, page.hasPrevious)
            assertEquals(index < 2, page.hasNext)
            assertEquals(1, page.bindings.size)
        }
        val beyond = SourceUseQuery.inspect(graph, c, SourceUsePageRequest(1, 200_000)).page!!
        assertEquals(3, beyond.observedUses)
        assertEquals(2, beyond.observedDistinctCallers)
        assertEquals(3, beyond.omittedBefore)
        assertEquals(0, beyond.omittedAfter)
        assertEquals(0, beyond.returnedUses)
        assertEquals(0, beyond.returnedDistinctCallers)
        assertEquals(200_000, beyond.offset)
        assertTrue(beyond.hasPrevious)
        assertFalse(beyond.hasNext)
        assertTrue(beyond.bindings.isEmpty())
    }

    @Test fun `unavailable captured empty and unrelated existing class have distinct source states`() {
        val bare = CodeGraph(defaultIds.map(::node), emptyList())
        assertNull(SourceUseQuery.inspect(bare, c).page)
        val empty = SourceUseQuery.inspect(graph(), c)
        assertNotNull(empty.page)
        assertEquals(0, empty.page!!.observedUses)
        assertFalse(empty.page!!.hasPrevious)
        assertFalse(empty.page!!.hasNext)
        val populated = graph(listOf(a to c))
        val cls = GraphNode(NodeId("class:p/C"), "C", NodeKind.CLASS)
        val withOwner = CodeGraph(populated.nodes.values + cls,
            listOf(GraphEdge(cls.id, c, EdgeKind.MEMBER))).preserveCompilerSourceUsesFrom(populated)
        assertEquals(0, SourceUseQuery.inspect(withOwner, cls.id).page!!.observedUses)
        assertEquals(1, SourceUseQuery.inspect(withOwner, c).page!!.observedUses)
    }

    @Test fun `invalid exact IDs fail even with unavailable channel`() {
        for (graph in listOf(graph(), CodeGraph(defaultIds.map(::node), emptyList()))) {
            assertFailsWith<IllegalArgumentException> { SourceUseQuery.inspect(graph, id("Missing")) }
            assertFailsWith<IllegalArgumentException> { SourceEditImpact.analyze(graph, setOf(c, id("Missing"))) }
        }
    }

    @Test fun `same IDs in another graph cannot inherit source capture or correlation`() {
        val captured = graph(listOf(a to c), listOf(GraphEdge(a, c, EdgeKind.CALL)))
        val bare = CodeGraph(captured.nodes.values, emptyList())
        assertTrue(SourceUseQuery.inspect(captured, c).page!!.observations.single().matchingBytecodeCall)
        assertNull(SourceUseQuery.inspect(bare, c).page)
        assertEquals("unavailable", SourceUseCoverage.from(bare).availability)
        assertFalse(SourceUseQuery.inspect(graph(listOf(a to c)), c).page!!.observations.single().matchingBytecodeCall)
        assertTrue(SourceUseQuery.inspect(captured, c).page!!.observations.single().matchingBytecodeCall)
    }

    @Test fun `javac method and constructor selections keep their exact coordinate basis`() {
        val constructor = NodeId("method:p/C#<init>()V")
        val captured = graph(listOf(a to c, a to constructor), ids = listOf(a, c, constructor), collector = "javac-constants")
        val graph = CodeGraph(captured.nodes.values.map {
            if (it.id == constructor) it.copy(kind = NodeKind.CONSTRUCTOR) else it
        }, captured.edges).preserveCompilerSourceUsesFrom(captured)
        val rows = SourceUseQuery.inspect(graph, a).page!!.observations
        assertEquals(2, rows.size)
        assertEquals(setOf(c, constructor), rows.map { it.target.usr }.toSet())
        assertTrue(rows.all { it.use.collector == "javac-constants" &&
            it.use.coordinateBasis == CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE })
        assertEquals(listOf(a), SourceEditImpact.analyze(graph, setOf(constructor)).page!!.observations.map { it.caller.usr })
        assertEquals("unknown", SourceUseCoverage.from(SourceUseAnalysisFixture.graph(collector = "javac-constants")).coverage)
    }

    @Test fun `declaration view facts change while original selector hashes ranges and IDs stay exact`() {
        val graph = graph(listOf(a to c), uncovered = listOf(d))
        val original = SourceUseQuery.inspect(graph, c)
        val relabeled = CodeGraph(graph.nodes.values.map {
            if (it.id == a) it.copy(moduleName = "renamed", location = SourceLocation("other/Declaration.kt", 99, 2)) else it
        }, graph.edges).preserveCompilerSourceUsesFrom(graph)
        val row = SourceUseQuery.inspect(relabeled, c).page!!.observations.single()
        assertSame(original.page!!.observations.single().use, row.use)
        assertEquals("renamed", row.caller.module)
        assertEquals(SourceLocation("other/Declaration.kt", 99, 2), row.caller.location)
        assertNull(row.target.module)
        assertNull(row.target.location)
        assertEquals("src/p/F.kt", row.use.file.path)
        assertEquals(original.coverage, SourceUseCoverage.from(relabeled))
    }

    @Test fun `ordinary graph facts and graph impact remain unchanged`() {
        val graph = graph(listOf(a to c), listOf(GraphEdge(b, c, EdgeKind.CALL)))
        val beforeEdges = graph.edges.toList()
        val beforeNodes = graph.nodes.toMap()
        val before = ChangeImpact.analyze(ImpactInput(graph), listOf(c.value))
        SourceUseQuery.inspect(graph, c)
        SourceEditImpact.analyze(graph, setOf(c))
        assertEquals(beforeEdges, graph.edges)
        assertEquals(beforeNodes, graph.nodes)
        assertEquals(before, ChangeImpact.analyze(ImpactInput(graph), listOf(c.value)))
        assertEquals(listOf(b), before.affected.map { it.node.id })
    }
}
