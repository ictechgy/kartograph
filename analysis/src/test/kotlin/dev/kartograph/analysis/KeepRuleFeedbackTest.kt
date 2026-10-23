package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class KeepRuleFeedbackTest {
    private val owner = GraphNode(NodeId("class:Example"), "Example", NodeKind.CLASS)
    private val method = GraphNode(NodeId("method:Example#work()V"), "work", NodeKind.METHOD)
    private val graph = CodeGraph(listOf(owner, method), listOf(GraphEdge(owner.id, method.id, EdgeKind.MEMBER)))
    @Test fun `global disabled shrinking retains methods with distinct evidence`() {
        val rules = listOf(KeepRule(KeepDeclarationKind.CLASS, "**", location = SourceLocation("keep.pro", 1), shrinkingDisabled = true))
        val evidence = KeepRuleRetention.find(graph, rules)
        assertEquals(graph.nodeIds.toSet(), evidence.map { it.nodeId }.toSet())
        assertTrue(evidence.all { it.reason == RetentionReason.SHRINKING_DISABLED })
        assertTrue(KeepRuleRetention.unmatched(rules, evidence).isEmpty())
        assertTrue(KeepRuleRetention.find(graph, emptyList()).isEmpty())
    }
    @Test fun `unknown rule remains unknown instead of proving runtime entry`() {
        val evidence = KeepRuleRetention.find(graph, listOf(KeepRule(KeepDeclarationKind.CLASS, "**", location = SourceLocation("keep.pro", 1), unresolved = true)))
        assertEquals(graph.nodeCount, evidence.size)
        assertTrue(evidence.all { it.reason == RetentionReason.UNKNOWN_KEEP_RULE })
        val reachability = ReachabilityAnalyzer.analyze(graph, evidence)
        val result = SymbolQuery.query(graph, reachability, owner.id.value, listOf("unknown-keep-rules")).result!!
        assertEquals("unknown", result.reachability.state)
    }
    @Test fun `missing hierarchy stays unknown in lenient mode and strict fails`() {
        val inherited = CodeGraph(listOf(owner.copy(supertypes = setOf("external/Missing")), method), graph.edges)
        val rules = listOf(KeepRule(KeepDeclarationKind.CLASS, "Example", "external/Contract".replace('/', '.'), SourceLocation("keep.pro", 1)))
        assertFailsWith<IncompleteKeepRuleHierarchyException> { KeepRuleRetention.find(inherited, rules) }
        val evidence = KeepRuleRetention.find(inherited, rules, lenient = true)
        assertEquals(inherited.nodeIds.toSet(), evidence.map { it.nodeId }.toSet())
        assertTrue(evidence.all { it.reason == RetentionReason.UNKNOWN_KEEP_RULE })
    }
}
