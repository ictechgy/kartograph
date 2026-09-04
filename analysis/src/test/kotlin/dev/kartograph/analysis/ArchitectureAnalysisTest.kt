package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchitectureAnalysisTest {
    @Test
    fun `tarjan returns deterministic components and weakest usage edge`() {
        val graph = CodeGraph(
            listOf(node("c"), node("a"), node("b"), node("outside")),
            listOf(
                edge("b", "c", weight = 3),
                edge("c", "a", EdgeKind.FIELD_ACCESS),
                edge("a", "b", weight = 5),
                edge("a", "b", EdgeKind.FIELD_ACCESS),
                edge("a", "outside"),
                edge("a", "c", EdgeKind.MEMBER),
            ),
        )

        val cycles = CycleAnalyzer.analyze(graph)

        assertEquals(listOf(NodeId("a"), NodeId("b"), NodeId("c")), cycles.single().component)
        assertEquals(listOf(NodeId("a"), NodeId("b"), NodeId("c")), cycles.single().path)
        assertEquals(edge("a", "b", EdgeKind.FIELD_ACCESS), cycles.single().weakestEdge)
    }

    @Test
    fun `tarjan reports a usage self loop as a cycle`() {
        val graph = CodeGraph(listOf(node("self")), listOf(edge("self", "self", weight = 2)))

        val cycle = CycleAnalyzer.analyze(graph).single()

        assertEquals(listOf(NodeId("self")), cycle.component)
        assertEquals(listOf(NodeId("self")), cycle.path)
        assertEquals(edge("self", "self", weight = 2), cycle.weakestEdge)
    }

    @Test
    fun `layer evaluator reports deterministic usage evidence and assignments`() {
        val source = GraphNode(
            NodeId("presentation"), "CheckoutViewModel", NodeKind.CLASS,
            moduleName = "app", location = SourceLocation("src/feature/Checkout.kt", 17),
        )
        val target = GraphNode(NodeId("data"), "OrderRepository", NodeKind.CLASS, moduleName = "data")
        val owned = GraphNode(NodeId("member"), "load", NodeKind.METHOD)
        val graph = CodeGraph(
            listOf(source, target, owned),
            listOf(
                GraphEdge(source.id, target.id, EdgeKind.CALL, 2),
                GraphEdge(source.id, owned.id, EdgeKind.MEMBER),
            ),
        )
        val evaluator = LayerRuleEvaluator(
            layers = listOf(
                LayerDefinition("Presentation", listOf("*ViewModel")),
                LayerDefinition("Data", listOf("data")),
            ),
            rules = listOf(LayerRule("no direct data", "Presentation", deny = listOf("Data"))),
        )

        val violation = evaluator.evaluate(graph).single()

        assertEquals("Presentation", evaluator.assignment(source).layer)
        assertEquals("*ViewModel", evaluator.assignment(source).pattern)
        assertEquals(SourceLocation("src/feature/Checkout.kt", 17), violation.location)
        assertEquals(GraphEdge(source.id, target.id, EdgeKind.CALL, 2), violation.edge)
        assertEquals(listOf(owned.id), evaluator.unassignedNodes(graph))
    }

    @Test
    fun `allow nothing ignores dependencies contained in the same layer`() {
        val graph = CodeGraph(
            listOf(node("first", "domain"), node("second", "domain")),
            listOf(edge("first", "second")),
        )
        val evaluator = LayerRuleEvaluator(
            listOf(LayerDefinition("Domain", listOf("domain"))),
            listOf(LayerRule("isolated domain", "Domain", allow = emptyList())),
        )

        assertEquals(emptyList(), evaluator.evaluate(graph))
    }

    @Test
    fun `architecture aggregation removes member owner cycles inside one unit`() {
        val owner = node("owner", "feature")
        val member = node("member", "feature", NodeKind.METHOD)
        val graph = CodeGraph(
            listOf(owner, member),
            listOf(
                GraphEdge(owner.id, member.id, EdgeKind.MEMBER),
                GraphEdge(member.id, owner.id, EdgeKind.REFERENCE),
            ),
        )

        val architectureGraph = ArchitectureGraph.aggregate(graph)

        assertEquals(emptyList(), architectureGraph.edges)
        assertEquals(emptyList(), CycleAnalyzer.analyze(architectureGraph))
    }

    @Test
    fun `martin metrics aggregate modules using only shared usage semantics`() {
        val graph = CodeGraph(
            listOf(
                node("api", "api", NodeKind.INTERFACE),
                node("impl", "impl", NodeKind.CLASS),
                node("client", "client", NodeKind.CLASS),
                node("owned", "impl", NodeKind.METHOD),
            ),
            listOf(
                edge("client", "api"),
                edge("impl", "api", EdgeKind.INHERITANCE),
                edge("impl", "owned", EdgeKind.MEMBER),
            ),
        )

        val metrics = MartinMetrics.calculate(graph).associateBy { it.module }

        assertEquals(MartinMetric("api", 2, 0, 1, 1), metrics.getValue("api"))
        assertEquals(0.0, metrics.getValue("api").instability)
        assertEquals(1.0, metrics.getValue("api").abstractness)
        assertEquals(1.0, metrics.getValue("client").instability)
        assertEquals(0, metrics.getValue("impl").efferentCoupling - 1)
    }

    @Test
    fun `yaml rules reject unknown keys and references instead of passing empty`() {
        assertFailsWith<LayerConfigurationException> { LayerRuleYaml.parse("\n") }
        assertFailsWith<LayerConfigurationException> {
            LayerRuleYaml.parse("layers:\n  - name: App\n    match: [app]\nrules:\n  - from: App\n    denied: [Data]\n")
        }
        assertFailsWith<LayerConfigurationException> {
            LayerRuleYaml.parse("layers:\n  - name: App\n    match: [app]\nrules:\n  - from: App\n    deny: [Missing]\n")
        }
        assertFailsWith<LayerConfigurationException> {
            LayerRuleYaml.parse("layers:\n  - name: App\n    match: [app]\nlayers:\n  - name: Data\n    match: [data]\n")
        }
        assertFailsWith<LayerConfigurationException> {
            LayerRuleYaml.parse(
                "layers:\n  - name: App\n    match: [app]\nrules:\n" +
                    "  - name: duplicate\n    from: App\n    allow: []\n" +
                    "  - name: duplicate\n    from: App\n    allow: []\n",
            )
        }
    }

    @Test
    fun `architecture graph removes implementation loops inside a module`() {
        val graph = CodeGraph(
            listOf(node("class", "app"), node("clinit", "app", NodeKind.METHOD), node("api", "api")),
            listOf(edge("class", "clinit"), edge("clinit", "class"), edge("class", "api")),
        )

        val architecture = ArchitectureGraph.aggregate(graph)

        assertEquals(listOf(NodeId("unit:api")), architecture.usageSuccessorsOf(NodeId("unit:app")))
        assertEquals(emptyList(), CycleAnalyzer.analyze(architecture))
    }

    private fun node(
        id: String,
        module: String = "app",
        kind: NodeKind = NodeKind.CLASS,
    ) = GraphNode(NodeId(id), id, kind, moduleName = module,
        jvmModifiers = if (kind == NodeKind.CLASS && id == "abstract") setOf(JvmModifier.ABSTRACT) else emptySet())

    private fun edge(
        source: String,
        target: String,
        kind: EdgeKind = EdgeKind.CALL,
        weight: Int = 1,
    ) = GraphEdge(NodeId(source), NodeId(target), kind, weight)
}
