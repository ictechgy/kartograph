package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArchitectureAnalysisTest {
    @Test
    fun `bytecode overrides retain implementations without creating architectural dependencies`() {
        val lower = node("lower", "domain")
        val upper = node("upper", "app")
        val graph = CodeGraph(listOf(lower, upper), listOf(
            GraphEdge(upper.id, lower.id, EdgeKind.CALL),
            GraphEdge(lower.id, upper.id, EdgeKind.OVERRIDE, origin = EdgeOrigin.BYTECODE),
        ))
        assertEquals(listOf(upper.id), graph.usageSuccessorsOf(lower.id))
        val architecture = ArchitectureGraph.aggregate(graph)
        assertEquals(emptyList(), CycleAnalyzer.analyze(architecture))
        assertEquals(listOf(NodeId("unit:domain")), architecture.usageSuccessorsOf(NodeId("unit:app")))
        val evaluator = LayerRuleEvaluator(
            listOf(LayerDefinition("Domain", listOf("domain")), LayerDefinition("App", listOf("app"))),
            listOf(LayerRule("domain boundary", "Domain", allow = emptyList())),
        )
        assertEquals(emptyList(), evaluator.evaluate(graph))
        assertEquals(0, MartinMetrics.calculate(graph).single { it.module == "domain" }.efferentCoupling)
    }

    @Test
    fun `runtime dispatch candidates preserve reachability without reversing module dependencies`() {
        val lower = node("lower", "domain")
        val upper = node("upper", "app")
        val graph = CodeGraph(listOf(lower, upper), listOf(
            GraphEdge(upper.id, lower.id, EdgeKind.CALL),
            GraphEdge(lower.id, upper.id, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL),
        ))
        assertEquals(listOf(upper.id), graph.usageSuccessorsOf(lower.id))
        assertEquals(emptyList(), CycleAnalyzer.analyze(ArchitectureGraph.aggregate(graph)))
        val evaluator = LayerRuleEvaluator(
            listOf(LayerDefinition("Domain", listOf("domain")), LayerDefinition("App", listOf("app"))),
            listOf(LayerRule("domain boundary", "Domain", allow = emptyList())),
        )
        assertEquals(emptyList(), evaluator.evaluate(graph))
        assertEquals(0, MartinMetrics.calculate(graph).single { it.module == "domain" }.efferentCoupling)
        val actualDependency = CodeGraph(listOf(lower, upper), listOf(GraphEdge(lower.id, upper.id, EdgeKind.CALL)))
        assertEquals(1, evaluator.evaluate(actualDependency).size)
    }

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
    fun `martin abstractness counts declared types and flags undefined ratios`() {
        val facade = node("UtilKt", "util").copy(synthesized = true, attributes = setOf(NodeAttribute.FILE_FACADE))
        val lambda = node("Port\$open\$1", "port").copy(synthesized = true)
        val graph = CodeGraph(
            listOf(
                facade,
                node("helper", "util", NodeKind.FUNCTION),
                node("Port", "port", NodeKind.INTERFACE),
                lambda,
                node("Adapter", "adapter"),
                node("Lonely", "island"),
            ),
            listOf(
                edge("Adapter", "Port", EdgeKind.INHERITANCE),
                edge("Adapter", "helper"),
                edge("UtilKt", "helper", EdgeKind.MEMBER),
            ),
        )

        val metrics = MartinMetrics.calculate(graph).associateBy { it.module }

        // file facade와 합성 class는 작성된 타입이 아니므로 A의 분모·분자에서 빠진다.
        assertEquals(MartinMetric("util", 1, 0, 0, 0), metrics.getValue("util"))
        assertEquals(MartinMetric("port", 1, 0, 1, 1), metrics.getValue("port"))
        assertEquals(1.0, metrics.getValue("port").abstractness)
        assertEquals(0.0, metrics.getValue("port").distance)
        // 타입이 없는 단위는 A, 결합이 없는 단위는 I가 정의되지 않는다. 기존 Double 값은 호환성을 위해 유지한다.
        assertEquals(false, metrics.getValue("util").abstractnessDefined)
        assertEquals(true, metrics.getValue("util").instabilityDefined)
        assertEquals(false, metrics.getValue("util").distanceDefined)
        assertEquals(1.0, metrics.getValue("util").distance)
        assertEquals(true, metrics.getValue("island").abstractnessDefined)
        assertEquals(false, metrics.getValue("island").instabilityDefined)
        assertEquals(false, metrics.getValue("island").distanceDefined)
        assertEquals(1.0, metrics.getValue("island").distance)
        assertEquals(true, metrics.getValue("adapter").distanceDefined)
        assertEquals(true, metrics.getValue("port").distanceDefined)
    }

    @Test
    fun `martin abstractness can fall when a synthesized abstract base is excluded`() {
        // Hilt가 생성한 abstract base(Hilt_MainActivity)는 합성 선언이라 A에서 빠진다. 이전 집계의 0.500이 0.000이 된다.
        val generatedBase = node("Hilt_MainActivity", "ui").copy(synthesized = true, jvmModifiers = setOf(JvmModifier.ABSTRACT))
        val graph = CodeGraph(listOf(node("MainActivity", "ui"), generatedBase, node("App", "app")), listOf(
            edge("MainActivity", "Hilt_MainActivity", EdgeKind.INHERITANCE),
            edge("App", "MainActivity", EdgeKind.REFERENCE),
        ))

        val ui = MartinMetrics.calculate(graph).single { it.module == "ui" }

        assertEquals(MartinMetric("ui", 1, 0, 0, 1), ui)
        assertEquals(0.0, ui.abstractness)
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
