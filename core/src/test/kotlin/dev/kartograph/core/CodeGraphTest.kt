package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeGraphTest {
    @Test
    fun `duplicate edges merge their weights and keep distinct kinds`() {
        val graph = CodeGraph(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(
                edge("a", "b", EdgeKind.CALL),
                edge("a", "b", EdgeKind.CALL, weight = 2),
                edge("a", "b", EdgeKind.REFERENCE),
            ),
        )

        assertEquals(2, graph.edgeCount)
        assertEquals(3, graph.edges.single { it.kind == EdgeKind.CALL }.weight)
    }

    @Test
    fun `duplicate call edges stream-merge weights and sorted call-site lines`() {
        val graph = CodeGraph(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(
                GraphEdge(NodeId("a"), NodeId("b"), EdgeKind.CALL, weight = 1, callSiteLines = listOf(5)),
                GraphEdge(NodeId("a"), NodeId("b"), EdgeKind.CALL, weight = 2, callSiteLines = listOf(3, 5)),
            ),
        )

        assertEquals(3, graph.edges.single().weight)
        assertEquals(listOf(3, 5), graph.edges.single().callSiteLines)
    }

    @Test
    fun `many streamed duplicate edges retain weights and independent origin evidence`() {
        val a = NodeId("a")
        val b = NodeId("b")
        val rawEdges = sequence {
            repeat(50_000) { index ->
                yield(GraphEdge(a, b, EdgeKind.CALL, callSiteLines = listOf(index % 17 + 1)))
            }
            yield(GraphEdge(a, b, EdgeKind.CALL, origin = EdgeOrigin.RUNTIME_MODEL))
            yield(GraphEdge(a, NodeId("missing"), EdgeKind.CALL, callSiteLines = listOf(7)))
        }.asIterable()
        val graph = CodeGraph(listOf(node("a"), node("b")), rawEdges)
        assertEquals(2, graph.edgeCount)
        val bytecode = graph.edges.single { it.origin == EdgeOrigin.BYTECODE }
        assertEquals(50_000, bytecode.weight)
        assertEquals((1..17).toList(), bytecode.callSiteLines)
        val modeled = graph.edges.single { it.origin == EdgeOrigin.RUNTIME_MODEL }
        assertEquals(1, modeled.weight)
        assertTrue(modeled.callSiteLines.isEmpty())
    }

    @Test
    fun `edges without both endpoints are dropped`() {
        val graph = CodeGraph(
            nodes = listOf(node("a")),
            edges = listOf(
                edge("a", "missing", EdgeKind.CALL),
                edge("missing", "a", EdgeKind.CALL),
            ),
        )

        assertEquals(1, graph.nodeCount)
        assertEquals(0, graph.edgeCount)
    }

    @Test
    fun `adjacency is directional and usage traversal shares edge semantics`() {
        val graph = CodeGraph(
            nodes = listOf(node("c"), node("b"), node("a")),
            edges = listOf(
                edge("a", "b", EdgeKind.MEMBER),
                edge("a", "c", EdgeKind.CALL),
                edge("b", "c", EdgeKind.REFERENCE),
            ),
        )

        assertEquals(listOf(NodeId("b"), NodeId("c")), graph.successorsOf(NodeId("a")))
        assertEquals(listOf(NodeId("c")), graph.usageSuccessorsOf(NodeId("a")))
        assertEquals(listOf(NodeId("a"), NodeId("b")), graph.predecessorsOf(NodeId("c")))
        assertTrue(graph.successorsOf(NodeId("c")).isEmpty())
    }

    @Test
    fun `nodes and edges have deterministic order`() {
        val graph = CodeGraph(
            nodes = listOf(node("c"), node("a"), node("b")),
            edges = listOf(
                edge("b", "c", EdgeKind.CALL),
                edge("a", "c", EdgeKind.REFERENCE),
                edge("a", "b", EdgeKind.CALL),
            ),
        )

        assertEquals(listOf(NodeId("a"), NodeId("b"), NodeId("c")), graph.nodeIds)
        assertEquals(listOf("a:b:CALL", "a:c:REFERENCE", "b:c:CALL"), graph.edges.map(::edgeDescription))
    }

    @Test
    fun `first duplicate node wins and self loops are explicit`() {
        val first = node("a", name = "first")
        val duplicate = node("a", name = "duplicate")
        val graph = CodeGraph(
            nodes = listOf(first, duplicate),
            edges = listOf(edge("a", "a", EdgeKind.CALL)),
        )

        assertEquals(first, graph.node(NodeId("a")))
        assertTrue(graph.edges.single().isSelfLoop)
        assertFalse(edge("a", "b", EdgeKind.CALL).isSelfLoop)
    }

    @Test
    fun `enrichment matches constructor oracle across edge and non-edge facts`() {
        val source = NodeId("a")
        val target = NodeId("b")
        val lambda = NodeId("lambda")
        val provider = NodeId("provider")
        val method = NodeId("method:a#run()V")
        val insideMethod = NodeId("method:inside/Api#run()V")
        val nodes = listOf(
            node("0"), node("a"), node("b"), node("lambda"), node("provider"),
            node("z"), node(method.value), node(insideMethod.value),
        )
        val baseEdges = listOf(
            GraphEdge(source, target, EdgeKind.CALL, weight = 2, callSiteLines = listOf(5)),
            GraphEdge(source, target, EdgeKind.CALL, origin = EdgeOrigin.DISPATCH_MODEL),
            GraphEdge(target, source, EdgeKind.REFERENCE, origin = EdgeOrigin.KOTLIN_METADATA),
        )
        val deltaEdges = buildList {
            add(GraphEdge(source, target, EdgeKind.CALL, callSiteLines = listOf(3)))
            add(GraphEdge(source, target, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL))
            add(GraphEdge(NodeId("0"), source, EdgeKind.RETENTION))
            add(GraphEdge(target, NodeId("z"), EdgeKind.ANNOTATION, origin = EdgeOrigin.COMPILER_REFERENCE))
            add(GraphEdge(source, NodeId("missing"), EdgeKind.REFERENCE))
            EdgeKind.entries.forEach { kind ->
                EdgeOrigin.entries.forEach { origin ->
                    add(GraphEdge(target, source, kind, origin = origin))
                }
            }
        }
        val calls = listOf(
            ExternalCall(source, "outside/Api", "run", "()V", InvocationKind.VIRTUAL,
                resolvedTargets = listOf(target, NodeId("missing"), target), model = "model"),
            ExternalCall(source, "outside/Api", "run", "()V", InvocationKind.VIRTUAL,
                resolvedTargets = listOf(target), model = "model"),
            ExternalCall(source, "inside/Api", "run", "()V", InvocationKind.STATIC),
        )
        val providers = listOf(ServiceProviderRegistration("service", provider, SourceLocation("services", 1)))
        val enclosures = listOf(LexicalEnclosure(lambda, source))
        val callbacks = listOf(CallbackArgument(source, NodeId("method:outside/Api#run()V"), InvocationKind.VIRTUAL, 0, lambda))
        val parameterUses = listOf(ParameterUse(source, 0, ParameterUseKind.FIELD, target = target))
        val escapes = listOf(LambdaEscape(source, lambda, ParameterUseKind.FIELD))
        val base = CodeGraph(nodes, baseEdges, calls, providers, enclosures, callbacks, parameterUses, escapes)
        var iterations = 0
        var callIterations = 0
        val singleUseDelta = object : Iterable<GraphEdge> {
            override fun iterator(): Iterator<GraphEdge> {
                check(++iterations == 1) { "delta iterable was traversed more than once" }
                return deltaEdges.iterator()
            }
        }
        val singleUseCalls = object : Iterable<ExternalCall> {
            override fun iterator(): Iterator<ExternalCall> {
                check(++callIterations == 1) { "external-call iterable was traversed more than once" }
                return calls.iterator()
            }
        }
        val actual = base.enrichedWith(singleUseDelta, singleUseCalls, replacingOrigin = EdgeOrigin.DISPATCH_MODEL)
        val expected = CodeGraph(
            nodes,
            baseEdges.filter { it.origin != EdgeOrigin.DISPATCH_MODEL } + deltaEdges,
            calls,
            providers,
            enclosures,
            callbacks,
            parameterUses,
            escapes,
        )

        assertEquals(1, iterations)
        assertEquals(1, callIterations)
        assertGraphEquivalent(expected, actual)
        assertEquals(1, actual.externalCalls.size)
        assertTrue(actual.externalCalls.none { it.target == insideMethod })
    }

    @Test
    fun `empty and dangling enrichment reuses canonical state and adjacency`() {
        val lambda = NodeId("lambda")
        val provider = NodeId("provider")
        val calls = listOf(ExternalCall(NodeId("a"), "outside/Api", "run", "()V", InvocationKind.VIRTUAL))
        val graph = CodeGraph(
            nodes = listOf(node("a"), node("b"), node("lambda"), node("provider")),
            edges = listOf(edge("a", "b", EdgeKind.CALL)),
            externalCalls = calls,
            serviceProviders = listOf(ServiceProviderRegistration("service", provider, SourceLocation("services", 1))),
            enclosures = listOf(LexicalEnclosure(lambda, NodeId("a"))),
            callbackArguments = listOf(CallbackArgument(NodeId("a"), NodeId("method:outside/Api#run()V"),
                InvocationKind.VIRTUAL, 0, lambda)),
            parameterUses = listOf(ParameterUse(NodeId("a"), 0, ParameterUseKind.FIELD, target = NodeId("b"))),
            lambdaEscapes = listOf(LambdaEscape(NodeId("a"), lambda, ParameterUseKind.FIELD)),
        )
        val equivalentCalls = calls.toList()
        val empty = graph.enrichedWith(emptyList(), externalCalls = equivalentCalls)
        val dangling = graph.enrichedWith(listOf(edge("a", "missing", EdgeKind.CALL)))
        val replacementCalls = listOf(calls.single().copy(model = "changed"))
        val slowDelta = GraphEdge(NodeId("b"), NodeId("a"), EdgeKind.REFERENCE)
        val slow = graph.enrichedWith(slowDelta.let(::listOf), replacementCalls, replacingOrigin = EdgeOrigin.RUNTIME_MODEL)
        val slowOracle = CodeGraph(
            graph.nodes.values,
            graph.edges + slowDelta,
            replacementCalls,
            graph.serviceProviders,
            graph.enclosures,
            graph.callbackArguments,
            graph.parameterUses,
            graph.lambdaEscapes,
        )

        assertSame(graph.nodes, empty.nodes)
        assertSame(graph.nodeIds, empty.nodeIds)
        assertSame(graph.edges, empty.edges)
        assertSame(graph.externalCalls, empty.externalCalls)
        assertSame(graph.serviceProviders, empty.serviceProviders)
        assertSame(graph.enclosures, empty.enclosures)
        assertSame(graph.callbackArguments, empty.callbackArguments)
        assertSame(graph.parameterUses, empty.parameterUses)
        assertSame(graph.lambdaEscapes, empty.lambdaEscapes)
        assertSame(graph.outgoingEdgesFrom(NodeId("a")), empty.outgoingEdgesFrom(NodeId("a")))
        assertSame(graph.incomingEdgesTo(NodeId("b")), empty.incomingEdgesTo(NodeId("b")))
        assertSame(graph.edges, dangling.edges)
        assertSame(graph.outgoingEdgesFrom(NodeId("a")), dangling.outgoingEdgesFrom(NodeId("a")))
        assertGraphEquivalent(slowOracle, slow)
        assertSame(graph.nodes, slow.nodes)
        assertSame(graph.nodeIds, slow.nodeIds)
        assertSame(graph.serviceProviders, slow.serviceProviders)
        assertSame(graph.enclosures, slow.enclosures)
        assertSame(graph.callbackArguments, slow.callbackArguments)
        assertSame(graph.parameterUses, slow.parameterUses)
        assertSame(graph.lambdaEscapes, slow.lambdaEscapes)
        assertSame(graph.edges.single(), slow.edges.single { it.source == NodeId("a") && it.target == NodeId("b") })
    }

    @Test
    fun `enrichment preserves constructor overflow and call-line boundaries`() {
        val source = NodeId("a")
        val target = NodeId("b")
        val nodes = listOf(node("a"), node("b"))
        fun oracle(edges: List<GraphEdge>): CodeGraph = CodeGraph(nodes, edges)
        fun assertSameFailure(base: CodeGraph, delta: List<GraphEdge>, expectedMessage: String) {
            val enrichedError = assertFailsWith<IllegalArgumentException> { base.enrichedWith(delta) }
            val oracleError = assertFailsWith<IllegalArgumentException> { oracle(base.edges + delta) }
            assertEquals(expectedMessage, enrichedError.message)
            assertEquals(oracleError.message, enrichedError.message)
        }

        val standaloneInvalid = listOf(
            GraphEdge(source, target, EdgeKind.REFERENCE, weight = Int.MAX_VALUE),
            GraphEdge(source, target, EdgeKind.REFERENCE),
        )
        assertSameFailure(CodeGraph(nodes, emptyList()), standaloneInvalid, "edge weight must be positive")

        val positiveBase = CodeGraph(nodes, listOf(GraphEdge(source, target, EdgeKind.REFERENCE, weight = 3)))
        val standaloneInvalidButCombinedValid = List(2) { GraphEdge(source, target, EdgeKind.REFERENCE, weight = Int.MAX_VALUE) }
        assertEquals(1, positiveBase.enrichedWith(standaloneInvalidButCombinedValid).edges.single().weight)
        assertGraphEquivalent(oracle(positiveBase.edges + standaloneInvalidButCombinedValid),
            positiveBase.enrichedWith(standaloneInvalidButCombinedValid))

        val combinedInvalid = listOf(
            GraphEdge(source, target, EdgeKind.REFERENCE, weight = Int.MAX_VALUE),
            GraphEdge(source, target, EdgeKind.REFERENCE),
        )
        assertSameFailure(CodeGraph(nodes, listOf(GraphEdge(source, target, EdgeKind.REFERENCE))), combinedInvalid,
            "edge weight must be positive")

        val lineOverflow = listOf(
            GraphEdge(source, target, EdgeKind.CALL, weight = Int.MAX_VALUE, callSiteLines = listOf(1)),
            GraphEdge(source, target, EdgeKind.CALL, weight = Int.MAX_VALUE, callSiteLines = listOf(2)),
            GraphEdge(source, target, EdgeKind.CALL, weight = 4, callSiteLines = listOf(3)),
        )
        assertSameFailure(CodeGraph(nodes, emptyList()), lineOverflow,
            "call-site lines cannot outnumber edge occurrences")
        val mergedLineOverflow = listOf(
            GraphEdge(source, target, EdgeKind.CALL, weight = Int.MAX_VALUE, callSiteLines = listOf(2)),
            GraphEdge(source, target, EdgeKind.CALL, weight = Int.MAX_VALUE, callSiteLines = listOf(3)),
        )
        assertSameFailure(
            CodeGraph(nodes, listOf(GraphEdge(source, target, EdgeKind.CALL, weight = 3, callSiteLines = listOf(1)))),
            mergedLineOverflow,
            "call-site lines cannot outnumber edge occurrences",
        )

        val positiveWrap = List(3) { GraphEdge(source, target, EdgeKind.REFERENCE, weight = Int.MAX_VALUE) }
        val wrapped = CodeGraph(nodes, emptyList()).enrichedWith(positiveWrap)
        assertEquals(2_147_483_645, wrapped.edges.single().weight)
        assertGraphEquivalent(oracle(positiveWrap), wrapped)
    }

    private fun assertGraphEquivalent(expected: CodeGraph, actual: CodeGraph) {
        assertEquals(expected.nodes, actual.nodes)
        assertEquals(expected.edges, actual.edges)
        assertEquals(expected.externalCalls, actual.externalCalls)
        assertEquals(expected.serviceProviders, actual.serviceProviders)
        assertEquals(expected.enclosures, actual.enclosures)
        assertEquals(expected.callbackArguments, actual.callbackArguments)
        assertEquals(expected.parameterUses, actual.parameterUses)
        assertEquals(expected.lambdaEscapes, actual.lambdaEscapes)
        assertEquals(expected.locatedCompilerReferences, actual.locatedCompilerReferences)
        assertEquals(expected.compilerCallPositionsCaptured, actual.compilerCallPositionsCaptured)
        assertEquals(expected.nodeIds, actual.nodeIds)
        assertEquals(expected.nodeCount, actual.nodeCount)
        assertEquals(expected.edgeCount, actual.edgeCount)
        assertEquals(expected.isEmpty, actual.isEmpty)
        expected.nodeIds.forEach { id ->
            assertEquals(expected.outgoingEdgesFrom(id), actual.outgoingEdgesFrom(id))
            assertEquals(expected.incomingEdgesTo(id), actual.incomingEdgesTo(id))
        }
    }

    private fun node(id: String, name: String = id): GraphNode =
        GraphNode(NodeId(id), name, NodeKind.CLASS)

    private fun edge(source: String, target: String, kind: EdgeKind, weight: Int = 1): GraphEdge =
        GraphEdge(NodeId(source), NodeId(target), kind, weight)

    private fun edgeDescription(edge: GraphEdge): String =
        "${edge.source.value}:${edge.target.value}:${edge.kind}"
}
