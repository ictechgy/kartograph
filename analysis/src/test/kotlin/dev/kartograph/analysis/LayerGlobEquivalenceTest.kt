package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LayerGlobEquivalenceTest {
    @Test
    fun `literal regex syntax wildcards Unicode and line endings match old translation`() {
        val patterns = listOf("", "*", "?", "**?*", "a.b", "[", "]", "(", ")", "{", "}", "\\", "\\E", "\\Q",
            "^$+|", "a\\*?", "a\nb", "a?b", "a*b", "한글*", "é?", "😀", "?😀*", "\uD800", "\uDC00")
        val names = listOf("a", "ab", "a.b", "[", "]", "(", ")", "{", "}", "\\", "\\E", "\\Q", "^$+|",
            "a\\zz", "a\nb", "a\rb", "a\r\nb", "a\u0085b", "a\u2028b", "a\u2029b", "한글값", "éx", "😀", "x😀y", "\uD800", "\uDC00")
        for (pattern in patterns) {
            val layers = listOf(LayerDefinition("selected", listOf(pattern)))
            val evaluator = LayerRuleEvaluator(layers, emptyList())
            for (name in names) {
                val node = node(name, module = "", path = "path/$name")
                assertEquals(oldAssignment(layers, node), evaluator.assignment(node), "pattern=$pattern name=$name")
            }
        }
    }

    @Test
    fun `randomized patterns preserve exact assignment evidence and priority`() {
        val random = Random(913_407)
        val alphabet = listOf("a", "b", ".", "*", "?", "[", "\\", "$", "한", "😀", "\n")
        fun text() = (0 until random.nextInt(1, 10)).joinToString("") { alphabet.random(random) }
        repeat(120) {
            val layers = List(5) { LayerDefinition("layer-$it", List(4) { text() }) }
            val evaluator = LayerRuleEvaluator(layers, emptyList())
            repeat(16) {
                val node = node("n" + text(), module = text(), path = "src/" + text())
                assertEquals(oldAssignment(layers, node), evaluator.assignment(node))
            }
        }
    }

    @Test
    fun `duplicate patterns preserve first layer pattern and candidate`() {
        val layers = listOf(LayerDefinition("first", listOf("no-match", "*", "*")), LayerDefinition("second", listOf("*")))
        val node = node("name", module = "module", path = "src/name")
        val evaluator = LayerRuleEvaluator(layers, emptyList())
        assertEquals(LayerAssignment("first", "*", "name"), evaluator.assignment(node))
        assertEquals(oldAssignment(layers, node), evaluator.assignment(node))
    }

    @Test
    fun `caller mutations stay observable without retaining later pattern keys`() {
        val patterns = mutableListOf("old*")
        val layers = mutableListOf(LayerDefinition("first", patterns))
        val rules = mutableListOf(LayerRule("boundary", "first", deny = listOf("second")))
        val evaluator = LayerRuleEvaluator(layers, rules)
        val source = node("old-source")
        val target = node("target")
        val graph = CodeGraph(listOf(source, target), listOf(GraphEdge(source.id, target.id, EdgeKind.CALL)))
        assertEquals(oldAssignment(layers, source), evaluator.assignment(source))
        patterns.clear()
        patterns += "target"
        layers += LayerDefinition("second", listOf("old*"))
        assertEquals(oldAssignment(layers, source), evaluator.assignment(source))
        assertEquals(oldAssignment(layers, target), evaluator.assignment(target))
        assertEquals(oldViolations(layers, rules, graph), evaluator.evaluate(graph))
        rules[0] = LayerRule("reverse", "second", allow = emptyList())
        assertEquals(oldViolations(layers, rules, graph), evaluator.evaluate(graph))
        layers.reverse()
        repeat(300) {
            patterns[0] = "later-$it*"
            assertEquals(oldAssignment(layers, target), evaluator.assignment(target))
        }
        val cache = evaluator.javaClass.getDeclaredField("compiledPatterns").apply { isAccessible = true }.get(evaluator) as Map<*, *>
        assertEquals(setOf("old*"), cache.keys)
        layers.clear()
        assertEquals(LayerAssignment(null, null, null), evaluator.assignment(source))
    }

    @Test
    fun `unused patterns stay lazy and repeated graph evaluations preserve all outputs`() {
        val layers = listOf(LayerDefinition("all", listOf("*", "unvisited-[\\E")))
        val evaluator = LayerRuleEvaluator(layers, listOf(LayerRule("allow none", "all", allow = emptyList())))
        val cache = evaluator.javaClass.getDeclaredField("compiledPatterns").apply { isAccessible = true }.get(evaluator) as Map<*, *>
        assertEquals(false, (cache["*"] as Lazy<*>).isInitialized())
        assertEquals(false, (cache["unvisited-[\\E"] as Lazy<*>).isInitialized())
        val source = node("source")
        val target = node("target")
        val graph = CodeGraph(listOf(source, target), listOf(GraphEdge(source.id, target.id, EdgeKind.CALL)))
        repeat(3) {
            assertEquals(emptyList(), evaluator.evaluate(graph))
            assertEquals(emptyList(), evaluator.unassignedNodes(graph))
        }
        assertEquals(true, (cache["*"] as Lazy<*>).isInitialized())
        assertEquals(false, (cache["unvisited-[\\E"] as Lazy<*>).isInitialized())
    }

    @Test
    fun `simultaneous first evaluations share only immutable regex and preserve results`() {
        val layers = listOf(LayerDefinition("from", listOf("source*")), LayerDefinition("to", listOf("target?")))
        val rules = listOf(LayerRule("deny target", "from", deny = listOf("to")))
        val nodes = listOf(node("source"), node("target1"), node("unassigned"))
        val graph = CodeGraph(nodes, listOf(GraphEdge(nodes[0].id, nodes[1].id, EdgeKind.CALL)))
        val evaluator = LayerRuleEvaluator(layers, rules)
        val expected = oldViolations(layers, rules, graph)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(24) { Callable {
                repeat(10) {
                    assertEquals(expected, evaluator.evaluate(graph))
                    assertEquals(listOf(nodes[2].id), evaluator.unassignedNodes(graph))
                    nodes.forEach { assertEquals(oldAssignment(layers, it), evaluator.assignment(it)) }
                }
            } }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun node(name: String, module: String? = null, path: String? = null) = GraphNode(
        NodeId("node:$name"), name, NodeKind.CLASS, moduleName = module, location = path?.let { SourceLocation(it) },
    )

    private fun oldAssignment(layers: List<LayerDefinition>, node: GraphNode): LayerAssignment {
        val candidates = listOfNotNull(node.name, node.moduleName, ArchitectureGraph.unitOf(node), node.location?.path).distinct()
        for (layer in layers) for (pattern in layer.patterns) {
            candidates.firstOrNull { oldGlobMatches(pattern, it) }?.let { return LayerAssignment(layer.name, pattern, it) }
        }
        return LayerAssignment(null, null, null)
    }

    // 최적화 이전 번역을 독립 oracle로 그대로 유지한다.
    private fun oldGlobMatches(pattern: String, candidate: String): Boolean {
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

    private fun oldViolations(layers: List<LayerDefinition>, rules: List<LayerRule>, graph: CodeGraph): List<LayerViolation> {
        val assignments = graph.nodes.mapValues { oldAssignment(layers, it.value).layer }
        return graph.edges.filter(ArchitectureGraph::isDependency).flatMap { edge ->
            val source = assignments[edge.source] ?: return@flatMap emptyList()
            val target = assignments[edge.target] ?: return@flatMap emptyList()
            if (source == target) return@flatMap emptyList()
            rules.filter { rule ->
                val allow = rule.allow
                rule.from == source && (rule.deny?.contains(target) == true || (allow != null && target !in allow))
            }.map { LayerViolation(it, source, target, edge, graph.node(edge.source)?.location) }
        }
    }
}
