package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayerRuleEvaluatorTest {
    @Test
    fun `wildcards and quoted regex syntax retain authored matches`() {
        val cases = listOf(
            Triple("Order*", "OrderRepository", true),
            Triple("Order*", "PrefixOrderRepository", false),
            Triple("**Repo**", "OrderRepository", true),
            Triple("data/*", "data/a/b", true),
            Triple("a?c", "abc", true),
            Triple("a?c", "ac", false),
            Triple("a?c", "abbc", false),
            Triple("a.b", "a.b", true),
            Triple("a.b", "axb", false),
            Triple("a[bc]", "a[bc]", true),
            Triple("a[bc]", "ab", false),
            Triple("a+b", "aaab", false),
            Triple("a|b", "a", false),
            Triple("a^b$", "a^b$", true),
            Triple("a\\*", "a\\anything", true),
            Triple("a\\?", "a\\x", true),
            Triple("*", "a\nb", false),
            Triple("a?b", "a\nb", false),
            Triple("a\nb", "a\nb", true),
            Triple("a*", "a\n", false),
            Triple("*", "a\rb", false),
            Triple("*", "a\u0085b", false),
            Triple("*", "a\u2028b", false),
            Triple("*", "a\u2029b", false),
            Triple("한글*", "한글이름", true),
            Triple("??", "한글", true),
            Triple("?", "é", true),
            Triple("é", "e\u0301", false),
            Triple("??", "e\u0301", true),
        ) + listOf("[", "(", "{", "a{2,1}", "\\", "\\E", "a+b", "a|b", ".^$[](){}+\\").map {
            Triple(it, it, true)
        }
        for ((pattern, candidate, matches) in cases) {
            val layers = listOf(LayerDefinition("Match", listOf(pattern)))
            val node = candidateNode(candidate)
            val expected = if (matches) LayerAssignment("Match", pattern, candidate) else unassigned
            assertEquals(expected, PreviousLayerRuleEvaluator(layers, emptyList()).assignment(node), "previous: $pattern / $candidate")
            assertEquals(expected, LayerRuleEvaluator(layers, emptyList()).assignment(node), "current: $pattern / $candidate")
        }
    }

    @Test
    fun `empty patterns and empty graphs preserve their results`() {
        val layers = listOf(LayerDefinition("Empty", listOf("")))
        val evaluator = LayerRuleEvaluator(layers, emptyList())
        val emptyModule = GraphNode(NodeId("empty-module"), "Name", NodeKind.CLASS, moduleName = "")
        assertEquals(LayerAssignment("Empty", "", ""), evaluator.assignment(emptyModule))
        assertEquals(unassigned, evaluator.assignment(candidateNode("Name")))
        assertParity(evaluator, PreviousLayerRuleEvaluator(layers, emptyList()), CodeGraph(listOf(emptyModule), emptyList()))
        assertParity(evaluator, PreviousLayerRuleEvaluator(layers, emptyList()), CodeGraph(emptyList(), emptyList()))
        assertParity(LayerRuleEvaluator(emptyList(), emptyList()), PreviousLayerRuleEvaluator(emptyList(), emptyList()),
            CodeGraph(listOf(emptyModule), emptyList()))
    }

    @Test
    fun `first layer pattern and distinct candidate determine assignment evidence`() {
        val node = GraphNode(NodeId("class:common/pkg/Type"), "FirstName", NodeKind.CLASS,
            moduleName = "common.module", location = SourceLocation("common/path.kt", 4))
        val cases = listOf(
            listOf(LayerDefinition("First", listOf("missing", "common/*", "FirstName")),
                LayerDefinition("Second", listOf("FirstName"))) to LayerAssignment("First", "common/*", "common/path.kt"),
            listOf(LayerDefinition("First", listOf("*", "FirstName")),
                LayerDefinition("Second", listOf("FirstName"))) to LayerAssignment("First", "*", "FirstName"),
            listOf(LayerDefinition("Empty", emptyList()), LayerDefinition("Module", listOf("common*", "common*"))) to
                LayerAssignment("Module", "common*", "common.module"),
            listOf(LayerDefinition("Missing", listOf("missing"))) to unassigned,
        )
        for ((layers, expected) in cases) {
            assertEquals(expected, LayerRuleEvaluator(layers, emptyList()).assignment(node))
            assertEquals(expected, PreviousLayerRuleEvaluator(layers, emptyList()).assignment(node))
        }
        val packageNode = node.copy(moduleName = null)
        val layers = listOf(LayerDefinition("Package", listOf("common*")))
        assertEquals(LayerAssignment("Package", "common*", "common.pkg"), LayerRuleEvaluator(layers, emptyList()).assignment(packageNode))
        assertEquals(PreviousLayerRuleEvaluator(layers, emptyList()).assignment(packageNode),
            LayerRuleEvaluator(layers, emptyList()).assignment(packageNode))
        val duplicates = node.copy(name = "common.module", location = SourceLocation("common.module"))
        assertEquals(LayerAssignment("Package", "common*", "common.module"), LayerRuleEvaluator(layers, emptyList()).assignment(duplicates))
    }

    @Test
    fun `previous evaluator oracle covers Unicode surrogate and literal pattern combinations`() {
        val fragments = listOf("", "a", ".", "[", "(", "{2,1}", "\\E", "\\", "*", "?", "**", "?*?",
            "é", "e\u0301", "한글", "\uD83D\uDE80", "\uD83D", "\uDE80", "\n", "\r\n", "\u2028")
        val patterns = fragments + fragments.map { "a${it}b" } + listOf("*Repository", "src/*", "<default>")
        val values = fragments + fragments.map { "a${it}b" } + listOf("OrderRepository", "src/a/b.kt", "<default>")
        for (pattern in patterns) {
            val layers = listOf(LayerDefinition("First", listOf(pattern)), LayerDefinition("Second", listOf(pattern)))
            val evaluator = LayerRuleEvaluator(layers, emptyList())
            val previous = PreviousLayerRuleEvaluator(layers, emptyList())
            val nodes = values.mapIndexed { index, value ->
                GraphNode(NodeId("node:$index"), value.takeUnless(String::isBlank) ?: "nonempty-name", NodeKind.CLASS,
                    moduleName = value, location = SourceLocation("src/$index.kt"))
            }
            val graph = CodeGraph(nodes, emptyList())
            assertParity(evaluator, previous, graph)
        }
    }

    @Test
    fun `graph violations keep rule order edge evidence and unassigned nodes`() {
        val source = GraphNode(NodeId("00-source"), "AppSource", NodeKind.CLASS,
            moduleName = "app", location = SourceLocation("src/App.kt", 7))
        val peer = GraphNode(NodeId("10-peer"), "AppPeer", NodeKind.CLASS, moduleName = "app")
        val data = GraphNode(NodeId("20-data"), "DataApi", NodeKind.INTERFACE, moduleName = "data")
        val infra = GraphNode(NodeId("30-infra"), "Infra", NodeKind.CLASS, moduleName = "infra")
        val other = GraphNode(NodeId("40-other"), "Uncovered", NodeKind.METHOD, moduleName = "other")
        val dataEdge = GraphEdge(source.id, data.id, EdgeKind.CALL, weight = 3, callSiteLines = listOf(7, 8))
        val infraEdge = GraphEdge(source.id, infra.id, EdgeKind.FIELD_ACCESS)
        val graph = CodeGraph(listOf(other, infra, data, peer, source), listOf(
            infraEdge, dataEdge,
            GraphEdge(source.id, peer.id, EdgeKind.REFERENCE),
            GraphEdge(source.id, source.id, EdgeKind.CALL),
            GraphEdge(source.id, other.id, EdgeKind.CALL),
            GraphEdge(other.id, data.id, EdgeKind.CALL),
            GraphEdge(source.id, data.id, EdgeKind.MEMBER),
            GraphEdge(source.id, infra.id, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL),
            GraphEdge(data.id, source.id, EdgeKind.CALL),
        ))
        val layers = listOf(LayerDefinition("App", listOf("*Source", "app")),
            LayerDefinition("Data", listOf("data", "Data*")), LayerDefinition("Infra", listOf("infra")))
        val rules = listOf(LayerRule("no data", "App", deny = listOf("Data")),
            LayerRule("only app", "App", allow = listOf("App")), LayerRule("no infra", "App", deny = listOf("Infra")))
        val evaluator = LayerRuleEvaluator(layers, rules)
        assertEquals(listOf(
            LayerViolation(rules[0], "App", "Data", dataEdge, source.location),
            LayerViolation(rules[1], "App", "Data", dataEdge, source.location),
            LayerViolation(rules[1], "App", "Infra", infraEdge, source.location),
            LayerViolation(rules[2], "App", "Infra", infraEdge, source.location),
        ), evaluator.evaluate(graph))
        assertEquals(listOf(other.id), evaluator.unassignedNodes(graph))
        assertParity(evaluator, PreviousLayerRuleEvaluator(layers, rules), graph)
    }

    @Test
    fun `mutable configuration remains observable between every operation`() {
        val patterns = mutableListOf("alpha")
        val layers = mutableListOf(LayerDefinition("First", patterns), LayerDefinition("Second", listOf("beta")))
        val denied = mutableListOf("Second")
        val rules = mutableListOf(LayerRule("boundary", "First", deny = denied))
        val evaluator = LayerRuleEvaluator(layers, rules)
        val previous = PreviousLayerRuleEvaluator(layers, rules)
        val alpha = candidateNode("alpha")
        val beta = candidateNode("beta")
        val gamma = candidateNode("gamma")
        val graph = CodeGraph(listOf(alpha, beta, gamma), listOf(
            GraphEdge(alpha.id, beta.id, EdgeKind.CALL), GraphEdge(gamma.id, beta.id, EdgeKind.CALL)))
        fun check(violationSources: List<NodeId>, unassignedIds: List<NodeId>) {
            assertParity(evaluator, previous, graph)
            assertEquals(violationSources, evaluator.evaluate(graph).map { it.edge.source })
            assertEquals(unassignedIds, evaluator.unassignedNodes(graph))
        }
        check(listOf(alpha.id), listOf(gamma.id))
        patterns[0] = "gamma"
        check(listOf(gamma.id), listOf(alpha.id))
        patterns.add(0, "alpha")
        check(listOf(alpha.id, gamma.id), emptyList())
        layers.add(0, LayerDefinition("Override", listOf("beta")))
        check(emptyList(), emptyList())
        denied[0] = "Override"
        check(listOf(alpha.id, gamma.id), emptyList())
        val allowed = mutableListOf("Override")
        rules[0] = LayerRule("allowed targets", "First", allow = allowed)
        check(emptyList(), emptyList())
        allowed.clear()
        check(listOf(alpha.id, gamma.id), emptyList())
        rules.clear()
        check(emptyList(), emptyList())
        layers.clear()
        check(emptyList(), graph.nodeIds)
    }

    @Test
    fun `unused patterns and layers remain lazy`() {
        val patterns = object : AbstractList<String>() {
            override val size = 2
            override fun get(index: Int): String = if (index == 0) "*" else error("unused pattern was read")
        }
        val layers = object : AbstractList<LayerDefinition>() {
            override val size = 2
            override fun get(index: Int): LayerDefinition =
                if (index == 0) LayerDefinition("First", patterns) else error("unused layer was read")
        }
        val evaluator = LayerRuleEvaluator(layers, emptyList())
        val previous = PreviousLayerRuleEvaluator(layers, emptyList())
        assertParity(evaluator, previous, CodeGraph(listOf(candidateNode("alpha"), candidateNode("beta")), emptyList()))
        assertParity(evaluator, previous, CodeGraph(emptyList(), emptyList()))
    }

    @Test
    fun `concurrent calls on one evaluator do not share operation state`() {
        val layers = listOf(LayerDefinition("App", listOf("*View", "app.*", "src/app/*")),
            LayerDefinition("Data", listOf("*Repository", "data.*", "src/data/*")))
        val rules = listOf(LayerRule("boundary", "App", deny = listOf("Data")))
        val evaluator = LayerRuleEvaluator(layers, rules)
        val previous = PreviousLayerRuleEvaluator(layers, rules)
        val graphs = (0 until 4).map { variant ->
            val nodes = (0 until 18).map { index ->
                val suffix = when ((index + variant) % 3) { 0 -> "View"; 1 -> "Repository"; else -> "Unknown" }
                GraphNode(NodeId("node:$index"), "Type$suffix", NodeKind.CLASS, moduleName = "module-$index")
            }
            CodeGraph(nodes, nodes.zipWithNext { source, target -> GraphEdge(source.id, target.id, EdgeKind.CALL) })
        }
        val expectedAssignments = graphs.map { graph -> graph.nodes.values.map(previous::assignment) }
        val expectedViolations = graphs.map(previous::evaluate)
        val expectedUnassigned = graphs.map(previous::unassignedNodes)
        val ready = CountDownLatch(graphs.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(graphs.size)
        try {
            val futures = graphs.mapIndexed { index, graph ->
                executor.submit {
                    ready.countDown()
                    assertTrue(start.await(30, TimeUnit.SECONDS))
                    repeat(12) {
                        assertEquals(expectedViolations[index], evaluator.evaluate(graph))
                        assertEquals(expectedUnassigned[index], evaluator.unassignedNodes(graph))
                        assertEquals(expectedAssignments[index], graph.nodes.values.map(evaluator::assignment))
                    }
                }
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun candidateNode(value: String) = GraphNode(NodeId("node:$value"), value, NodeKind.CLASS, moduleName = value)

    private fun assertParity(evaluator: LayerRuleEvaluator, previous: PreviousLayerRuleEvaluator, graph: CodeGraph) {
        for (node in graph.nodes.values) assertEquals(previous.assignment(node), evaluator.assignment(node), "assignment: ${node.id}")
        assertEquals(previous.evaluate(graph), evaluator.evaluate(graph), "violations")
        assertEquals(previous.unassignedNodes(graph), evaluator.unassignedNodes(graph), "unassigned nodes")
    }

    private val unassigned = LayerAssignment(null, null, null)
}

// 2cf3cb6058111906071084f403d73f3574db3c96의 평가기를 클래스 선언만 바꿔 보존한 비교 기준이다.
private class PreviousLayerRuleEvaluator(
    private val layers: List<LayerDefinition>,
    private val rules: List<LayerRule>,
) {
    /** 이름, 모듈, source 경로 중 실제로 먼저 맞은 값을 근거와 함께 반환한다. */
    public fun assignment(node: GraphNode): LayerAssignment {
        val candidates = listOfNotNull(node.name, node.moduleName, ArchitectureGraph.unitOf(node), node.location?.path).distinct()
        for (layer in layers) for (pattern in layer.patterns) {
            candidates.firstOrNull { globMatches(pattern, it) }?.let { candidate ->
                return LayerAssignment(layer.name, pattern, candidate)
            }
        }
        return LayerAssignment(null, null, null)
    }

    /** 선언 의존 간선에 걸린 모든 위반을 간선의 결정적 순서로 반환한다. */
    public fun evaluate(graph: CodeGraph): List<LayerViolation> {
        val assignments = graph.nodes.mapValues { assignment(it.value).layer }
        return graph.edges.filter(ArchitectureGraph::isDependency).flatMap { edge ->
            val source = assignments[edge.source] ?: return@flatMap emptyList()
            val target = assignments[edge.target] ?: return@flatMap emptyList()
            if (source == target) return@flatMap emptyList()
            rules.filter { rule -> rule.from == source && violates(rule, target) }.map { rule ->
                LayerViolation(rule, source, target, edge, graph.node(edge.source)?.location)
            }
        }
    }

    /** 설정이 덮지 못해 규칙 판정에서 제외된 선언을 숨기지 않는다. */
    public fun unassignedNodes(graph: CodeGraph) = graph.nodeIds.filter { assignment(graph.nodes.getValue(it)).layer == null }

    private fun violates(rule: LayerRule, target: String): Boolean =
        rule.deny?.contains(target) == true || (rule.allow != null && target !in rule.allow)

    private fun globMatches(pattern: String, candidate: String): Boolean {
        val regex = buildString {
            append('^')
            pattern.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(character.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(candidate)
    }
}
