package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class SymbolDiscoveryTest {
    private fun method(owner: String, descriptor: String = "()V", path: String = "src/Value.kt") =
        GraphNode(NodeId("method:$owner#value$descriptor"), "value", NodeKind.METHOD,
            location = SourceLocation(path, 3))

    private fun facade(owner: String) = GraphNode(NodeId("class:$owner"), owner.substringAfterLast('/'),
        NodeKind.CLASS, attributes = setOf(NodeAttribute.FILE_FACADE), synthesized = true)

    @Test fun `package functions require facade evidence and preserve overloads across files`() {
        val first = method("p/CustomName", "(I)V")
        val second = method("p/OtherPart", "(D)V")
        val graph = CodeGraph(listOf(first, second, facade("p/CustomName"), facade("p/OtherPart"),
            method("p/LooksLikeKt"), method("other/CustomName"), facade("other/CustomName")), emptyList())

        for (selector in listOf("p.value", "p.value(int)")) {
            val page = SymbolDiscovery.suggest(listOf(graph), selector)
            assertEquals(listOf(first.id.value, second.id.value), page.candidates.map { it.usr })
            assertEquals("notFound", SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), selector, emptyList()).status)
        }
        assertEquals(0, SymbolDiscovery.suggest(listOf(graph), "P.value").total)
        assertEquals(0, SymbolDiscovery.suggest(listOf(graph), "wrong.value").total)
    }

    @Test fun `package discovery excludes accessors synthetic methods and non functions`() {
        val owner = facade("p/Value")
        val graph = CodeGraph(listOf(owner, method("p/Value"),
            method("p/Value", "(I)V").copy(attributes = setOf(NodeAttribute.PROPERTY_ACCESSOR)),
            method("p/Value", "(D)V").copy(synthesized = true),
            method("p/Value", "(J)V").copy(kind = NodeKind.CONSTRUCTOR)), emptyList())
        assertEquals(listOf(method("p/Value").id.value), SymbolDiscovery.suggest(listOf(graph), "p.value").candidates.map { it.usr })
        val rootPackage = CodeGraph(listOf(facade("Value"), method("Value")), emptyList())
        assertEquals(1, SymbolDiscovery.suggest(listOf(rootPackage), "value()").total)
    }

    @Test fun `file discovery prefers exact paths and paginates suffix matches without dropping ambiguity`() {
        val first = method("p/A", path = "module-a/src/Value.kt")
        val second = method("p/B", path = "module-b/src/Value.kt")
        val graph = CodeGraph(listOf(second, first, method("p/C", path = "src/Other.kt")), emptyList())
        val page = SymbolDiscovery.inFile(listOf(graph), "src/Value.kt", 1)
        assertEquals(2, page.total)
        assertTrue(page.truncated)
        assertEquals(first.id.value, page.candidates.single().usr)
        val next = SymbolDiscovery.inFile(listOf(graph), "src/Value.kt", 1, 1)
        assertEquals(second.id.value, next.candidates.single().usr)
        assertEquals(1, next.offset)
        assertTrue(next.truncated)
        assertEquals(0, SymbolDiscovery.inFile(listOf(graph), "src/Value.kt", 1, 2).returned)
        assertEquals(0, SymbolDiscovery.inFile(listOf(graph), "xsrc/Value.kt").total)
        assertEquals(0, SymbolDiscovery.inFile(listOf(graph), "src/value.kt").total)
        assertEquals(1, SymbolDiscovery.inFile(listOf(graph), "repo/module-a/src/Value.kt").total)
        val exact = method("p/D", path = "src/Value.kt")
        assertEquals(listOf(exact.id.value), SymbolDiscovery.inFile(listOf(graph,
            CodeGraph(listOf(exact), emptyList())), "src/Value.kt").candidates.map { it.usr })
    }

    @Test fun `discovery filters each revision before deduplication and retains matched locations`() {
        val current = method("p/Value", path = "src/New.kt")
        val base = current.copy(location = SourceLocation("src/Old.kt", 7))
        val graphs = listOf(CodeGraph(listOf(current), emptyList()), CodeGraph(listOf(base, facade("p/Value")), emptyList()))
        assertEquals(base.location, SymbolDiscovery.inFile(graphs, "src/Old.kt").candidates.single().location)
        assertEquals(base.id.value, SymbolDiscovery.suggest(graphs, "p.value").candidates.single().usr)
        assertEquals(current.location, SymbolDiscovery.suggest(graphs, "value").candidates.single().location)
        assertEquals(1, SymbolDiscovery.suggest(graphs, "value", 1, 1).total)
        assertTrue(SymbolDiscovery.suggest(graphs, "value", 1, 1).candidates.isEmpty())
        assertFailsWith<IllegalArgumentException> { SymbolDiscovery.suggest(graphs, "value", 0) }
        assertFailsWith<IllegalArgumentException> { SymbolDiscovery.inFile(graphs, "src/New.kt", offset = -1) }
    }
}
