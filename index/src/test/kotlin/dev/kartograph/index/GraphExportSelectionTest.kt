package dev.kartograph.index

import dev.kartograph.core.*
import java.lang.reflect.InvocationTargetException
import kotlin.test.*

class GraphExportSelectionTest {
    @Test fun `every origin and mixed exclusions reuse only zero removal graphs`() {
        for (excluded in exclusionCases) {
            val options = GraphExportIndexOptions(excludedOrigins = excluded)
            val noRemoval = graph(EdgeOrigin.entries.filterNot(excluded::contains))
            val reused = selectGraphForExport(noRemoval, options)
            assertSame(noRemoval, reused, "$excluded must reuse")
            assertExportGraph(fullRebuildGraphExportSelection(noRemoval, options), reused)
            val removal = graph(EdgeOrigin.entries)
            val rebuilt = selectGraphForExport(removal, options)
            assertNotSame(removal, rebuilt, "$excluded must reconstruct")
            assertExportGraph(fullRebuildGraphExportSelection(removal, options), rebuilt)
            assertTrue(rebuilt.edges.none { it.origin in excluded })
        }
        val empty = CodeGraph(emptyList(), emptyList())
        assertSame(empty, selectGraphForExport(empty, GraphExportIndexOptions(setOf(EdgeOrigin.DISPATCH_MODEL))))
    }

    @Test fun `null and empty options retain identity including compiler state`() {
        val captured = graph(listOf(EdgeOrigin.BYTECODE)).withCompilerCallPositions(emptyList())
        assertSame(captured, selectGraphForExport(captured, null))
        val exclusions = TracedExclusions(empty = true)
        assertSame(captured, selectGraphForExport(captured, GraphExportIndexOptions(exclusions)))
        assertEquals(listOf("isEmpty"), exclusions.calls)
        assertExportGraph(fullRebuildGraphExportSelection(captured, GraphExportIndexOptions()), captured)
    }

    @Test fun `empty and populated compiler captures take old reconstruction path`() {
        val base = graph(listOf(EdgeOrigin.BYTECODE))
        val reference = LocatedCompilerReference(source, target, CompilerEvidenceSource("src/Caller.java", "a".repeat(64)),
            "javac-constants", "21", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 0, 3, 1, 1)
        val options = GraphExportIndexOptions(setOf(EdgeOrigin.DISPATCH_MODEL))
        val populated = base.withCompilerCallPositions(listOf(reference))
        // 현재 생성 경로에 없는 nonempty/false 조합도 두 guard를 독립적으로 확인한다.
        val nonemptyUncaptured = base.withCompilerCallPositions(listOf(reference)).also { graph ->
            val state = CodeGraph::class.java.getDeclaredField("state").apply { isAccessible = true }.get(graph)
            state.javaClass.getDeclaredField("compilerCallPositionsCaptured").apply { isAccessible = true }.setBoolean(state, false)
        }
        for (input in listOf(base.withCompilerCallPositions(emptyList()), populated, nonemptyUncaptured)) {
            val result = selectGraphForExport(input, options)
            assertNotSame(input, result)
            assertFalse(result.compilerCallPositionsCaptured)
            assertTrue(result.locatedCompilerReferences.isEmpty())
            assertExportGraph(fullRebuildGraphExportSelection(input, options), result)
        }
        assertTrue(populated.compilerCallPositionsCaptured)
        assertEquals(listOf(reference), populated.locatedCompilerReferences)
    }

    @Test fun `stateful exclusions preserve exact full membership sequence`() {
        val input = graph(EdgeOrigin.entries)
        for (mode in 0..2) {
            fun exclusions() = TracedExclusions { call, _ -> when (mode) { 0 -> false; 1 -> true; else -> call % 2 == 0 } }
            val prior = exclusions()
            val current = exclusions()
            assertExportGraph(fullRebuildGraphExportSelection(input, GraphExportIndexOptions(prior)),
                selectGraphForExport(input, GraphExportIndexOptions(current)))
            assertEquals(listOf("isEmpty") + input.edges.map { "contains:${it.origin}" }, current.calls)
            assertEquals(prior.calls, current.calls)
        }
    }

    @Test fun `throwing exclusions preserve exception and call prefix`() {
        val input = graph(EdgeOrigin.entries)
        for (throwAt in listOf(0, 1, 3, input.edgeCount)) {
            val prior = TracedExclusions(throwAt = throwAt)
            val current = TracedExclusions(throwAt = throwAt)
            val expected = assertFailsWith<MembershipFailure> { fullRebuildGraphExportSelection(input, GraphExportIndexOptions(prior)) }
            val actual = assertFailsWith<MembershipFailure> { selectGraphForExport(input, GraphExportIndexOptions(current)) }
            assertEquals(expected.message, actual.message)
            assertEquals(prior.calls, current.calls)
            assertEquals(throwAt + 1, current.calls.size)
        }
    }

    private fun graph(origins: List<EdgeOrigin>): CodeGraph {
        val local = JvmNodeId.classId("sample/Caller\$Local")
        val nodes = listOf(GraphNode(target, "hit", NodeKind.METHOD), GraphNode(local, "Local", NodeKind.CLASS),
            GraphNode(source, "use", NodeKind.METHOD))
        return CodeGraph(nodes, origins.map { GraphEdge(source, target, EdgeKind.CALL, 2, it,
            if (it == EdgeOrigin.BYTECODE) listOf(3, 7) else emptyList()) },
            externalCalls = listOf(ExternalCall(source, "outside/Api", "call", "()V", InvocationKind.STATIC,
                resolvedTargets = listOf(target))),
            serviceProviders = listOf(ServiceProviderRegistration("sample.Service", local, SourceLocation("META-INF/services/sample.Service", 1))),
            enclosures = listOf(LexicalEnclosure(local, source)),
            callbackArguments = listOf(CallbackArgument(source, target, InvocationKind.STATIC, 0, local)),
            parameterUses = listOf(ParameterUse(source, 0, ParameterUseKind.DECLARED)),
            lambdaEscapes = listOf(LambdaEscape(source, local, ParameterUseKind.RETURN)))
    }

    private val source = JvmNodeId.methodId("sample/Caller", "use", "()V")
    private val target = JvmNodeId.methodId("sample/Target", "hit", "()V")
}

internal val exclusionCases: List<Set<EdgeOrigin>> = EdgeOrigin.entries.map(::setOf) + listOf(
    setOf(EdgeOrigin.DISPATCH_MODEL, EdgeOrigin.BYTECODE),
    setOf(EdgeOrigin.KOTLIN_METADATA, EdgeOrigin.RUNTIME_MODEL, EdgeOrigin.COMPILER_REFERENCE),
    EdgeOrigin.entries.toSet(),
)

internal fun selectGraphForExport(graph: CodeGraph, options: GraphExportIndexOptions?): CodeGraph = try {
    ClassFileIndexer::class.java.getDeclaredMethod("selectForGraphExport", CodeGraph::class.java, GraphExportIndexOptions::class.java)
        .apply { isAccessible = true }.invoke(ClassFileIndexer(), graph, options) as CodeGraph
} catch (error: InvocationTargetException) { throw error.targetException }

internal class MembershipFailure(message: String) : RuntimeException(message)

internal class TracedExclusions(
    private val empty: Boolean = false,
    private val throwAt: Int? = null,
    private val answer: (Int, EdgeOrigin) -> Boolean = { _, _ -> false },
) : Set<EdgeOrigin> by emptySet() {
    val calls = mutableListOf<String>()
    private var containsCalls = 0
    override fun isEmpty(): Boolean {
        calls += "isEmpty"
        if (throwAt == 0) throw MembershipFailure("isEmpty")
        return empty
    }
    override fun contains(element: EdgeOrigin): Boolean {
        calls += "contains:$element"
        containsCalls++
        if (throwAt == containsCalls) throw MembershipFailure("contains:$containsCalls")
        return answer(containsCalls, element)
    }
}

internal fun assertExportGraph(expected: CodeGraph, actual: CodeGraph) {
    assertEquals(expected.nodes.entries.toList(), actual.nodes.entries.toList(), "nodes and insertion order")
    assertEquals(expected.nodeIds, actual.nodeIds)
    assertEquals(expected.nodeCount, actual.nodeCount)
    assertEquals(expected.edgeCount, actual.edgeCount)
    assertEquals(expected.isEmpty, actual.isEmpty)
    assertEquals(expected.edges, actual.edges, "edges, order, weights and call-site lines")
    assertEquals(expected.externalCalls, actual.externalCalls)
    assertEquals(expected.serviceProviders, actual.serviceProviders)
    assertEquals(expected.enclosures, actual.enclosures)
    assertEquals(expected.callbackArguments, actual.callbackArguments)
    assertEquals(expected.parameterUses, actual.parameterUses)
    assertEquals(expected.lambdaEscapes, actual.lambdaEscapes)
    assertEquals(expected.locatedCompilerReferences, actual.locatedCompilerReferences)
    assertEquals(expected.compilerCallPositionsCaptured, actual.compilerCallPositionsCaptured)
    for (id in expected.nodeIds + NodeId("class:missing/Node")) {
        assertEquals(expected.node(id), actual.node(id))
        assertEquals(expected.contains(id), actual.contains(id))
        assertEquals(expected.outgoingEdgesFrom(id), actual.outgoingEdgesFrom(id))
        assertEquals(expected.incomingEdgesTo(id), actual.incomingEdgesTo(id))
        assertEquals(expected.successorsOf(id), actual.successorsOf(id))
        assertEquals(expected.usageSuccessorsOf(id), actual.usageSuccessorsOf(id))
        assertEquals(expected.predecessorsOf(id), actual.predecessorsOf(id))
    }
}
