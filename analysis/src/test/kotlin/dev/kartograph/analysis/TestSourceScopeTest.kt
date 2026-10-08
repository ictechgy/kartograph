package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 테스트 소스를 별도 프로그램으로 빼는 범위 결정과 그 가정 검증을 확인한다. */
class TestSourceScopeTest {
    private val main = "app/src/main/kotlin/p"
    private val test = "app/src/test/kotlin/p"

    private fun type(name: String, path: String, kind: NodeKind = NodeKind.CLASS, supertypes: Set<String> = emptySet()) =
        GraphNode(NodeId("class:p/$name"), name, kind, location = SourceLocation("$path/$name.kt"), supertypes = supertypes,
            jvmModifiers = if (kind == NodeKind.INTERFACE) setOf(JvmModifier.ABSTRACT) else emptySet())

    private fun method(owner: String, name: String, path: String?, abstract: Boolean = false) =
        GraphNode(NodeId("method:p/$owner#$name()V"), name, NodeKind.METHOD, location = path?.let { SourceLocation("$it/$owner.kt") },
            jvmModifiers = if (abstract) setOf(JvmModifier.ABSTRACT) else emptySet())

    private fun edge(source: String, target: String, kind: EdgeKind = EdgeKind.CALL) =
        GraphEdge(NodeId(source), NodeId(target), kind, origin = EdgeOrigin.BYTECODE)

    /**
     * production 인터페이스 Client의 구현은 RealClient 하나다. 테스트 소스의 FakeClient도 Client를 구현하고,
     * ScreenTest가 Screen.show를 부른다. 테스트 멤버 FakeClient.fetch는 위치가 없어 소유 class 위치를 따른다.
     */
    private fun graph(extraEdges: List<GraphEdge> = emptyList()): CodeGraph = CodeGraph(
        listOf(
            type("Client", main, NodeKind.INTERFACE), method("Client", "fetch", main, abstract = true),
            type("RealClient", main, supertypes = setOf("p/Client")), method("RealClient", "fetch", main),
            type("Screen", main), method("Screen", "show", main), type("Http", main), method("Http", "get", main),
            type("FakeClient", test, supertypes = setOf("p/Client")), method("FakeClient", "fetch", null),
            type("ScreenTest", test), method("ScreenTest", "run", test),
            GraphNode(NodeId("class:p/ScreenTest\$Nested"), "Nested", NodeKind.CLASS),
        ),
        listOf(
            edge("method:p/RealClient#fetch()V", "method:p/Http#get()V"),
            edge("method:p/FakeClient#fetch()V", "method:p/Http#get()V"),
            edge("method:p/Client#fetch()V", "method:p/RealClient#fetch()V", EdgeKind.OVERRIDE),
            edge("method:p/Client#fetch()V", "method:p/FakeClient#fetch()V", EdgeKind.OVERRIDE),
            edge("method:p/Screen#show()V", "method:p/Client#fetch()V"),
            edge("method:p/ScreenTest#run()V", "method:p/Screen#show()V"),
            edge("class:p/FakeClient", "class:p/Client", EdgeKind.INHERITANCE),
        ) + extraEdges,
    )

    private fun reverse(scope: TraversalScope): Map<String, TraversalEvidence> =
        LanguageTraversal.traverse(scope.graph, listOf("method:p/Http#get()V"), TraversalDirection.DEPENDENTS).reached
            .associate { it.node.id.value to it.evidence }

    @Test
    fun `test declarations inherit their owner location`() {
        val tests = TestSourceScope.testSourceNodes(graph())
        assertEquals(setOf("class:p/FakeClient", "method:p/FakeClient#fetch()V", "class:p/ScreenTest", "method:p/ScreenTest#run()V",
            "class:p/ScreenTest\$Nested"), tests.map { it.value }.toSet())
    }

    @Test
    fun `excluding test sources restores production bound dispatch`() {
        val full = reverse(TraversalScope(graph(), 0, emptyList()))
        assertEquals(TraversalEvidence.CANDIDATE, full.getValue("method:p/Client#fetch()V"), "a test fake is a second implementation")
        assertTrue("method:p/ScreenTest#run()V" in full)

        val scope = TestSourceScope.select(graph(), listOf("method:p/Http#get()V"), includeTests = false)
        assertEquals(5, scope.excludedTestSources)
        assertTrue(scope.limitations.single().startsWith("test-sources-excluded: 5 test-source declaration(s)"))
        val production = reverse(scope)
        assertEquals(setOf("method:p/RealClient#fetch()V", "method:p/Client#fetch()V", "method:p/Screen#show()V"), production.keys)
        assertEquals(TraversalEvidence.BOUND, production.getValue("method:p/Client#fetch()V"))
        assertEquals(TraversalEvidence.BOUND, production.getValue("method:p/Screen#show()V"))

        // 정방향도 테스트 fake로 퍼지지 않는다.
        val forward = LanguageTraversal.traverse(scope.graph, listOf("method:p/Screen#show()V"), TraversalDirection.DEPENDENCIES)
            .reached.associate { it.node.id.value to it.evidence }
        assertEquals(TraversalEvidence.BOUND, forward.getValue("method:p/RealClient#fetch()V"))
        assertFalse("method:p/FakeClient#fetch()V" in forward)
    }

    @Test
    fun `excluding test nodes preserves capture state and only surviving compiler positions`() {
        val productionSource = NodeId("method:p/RealClient#fetch()V")
        val testSource = NodeId("method:p/FakeClient#fetch()V")
        val target = NodeId("method:p/Http#get()V")
        fun position(source: NodeId, path: String, offset: Int) = LocatedCompilerReference(
            source, target, CompilerEvidenceSource(path, "a".repeat(64)),
            "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
            offset, offset + 3, 1, offset + 1,
        )
        val positioned = graph().withCompilerCallPositions(listOf(
            position(productionSource, "$main/RealClient.kt", 10),
            position(testSource, "$test/FakeClient.kt", 20),
        ))

        val selected = TestSourceScope.select(positioned, listOf(target.value), includeTests = false).graph
        assertTrue(selected.compilerCallPositionsCaptured)
        assertEquals(listOf(productionSource), selected.locatedCompilerReferences.map { it.source })
        assertTrue(selected.edges.any { it.source == productionSource && it.target == target && it.kind == EdgeKind.CALL })

        val capturedEmpty = graph().withCompilerCallPositions(emptyList())
        val emptySelected = TestSourceScope.select(capturedEmpty, listOf(target.value), includeTests = false).graph
        assertTrue(emptySelected.compilerCallPositionsCaptured)
        assertEquals(emptyList(), emptySelected.locatedCompilerReferences)
    }

    @Test
    fun `include tests keeps the whole graph`() {
        val original = graph()
        val scope = TestSourceScope.select(original, listOf("method:p/Http#get()V"), includeTests = true)
        assertSame(original, scope.graph)
        assertEquals(0, scope.excludedTestSources)
        assertTrue(scope.limitations.isEmpty())
    }

    @Test
    fun `a test root selects the test program`() {
        val scope = TestSourceScope.select(graph(), listOf("method:p/FakeClient#fetch()V", "missing"), includeTests = false)
        assertEquals(0, scope.excludedTestSources)
        assertEquals(listOf("test-sources-included: test-source declarations were traversed and bound dispatch counts their " +
            "implementations because 1 root(s) are test-source declarations"), scope.limitations)
        assertEquals(TraversalEvidence.CANDIDATE, reverse(scope).getValue("method:p/Client#fetch()V"))
    }

    @Test
    fun `production references to test sources fall back to the whole graph`() {
        listOf(
            edge("method:p/Screen#show()V", "method:p/FakeClient#fetch()V"),
            edge("class:p/Screen", "class:p/ScreenTest", EdgeKind.INHERITANCE),
            GraphEdge(NodeId("method:p/Screen#show()V"), NodeId("class:p/FakeClient"), EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL),
        ).forEach { violation ->
            val scope = TestSourceScope.select(graph(listOf(violation)), listOf("method:p/Http#get()V"), includeTests = false)
            assertEquals(0, scope.excludedTestSources, violation.toString())
            assertTrue(scope.limitations.single().endsWith("because 1 edge(s) from production declarations reference test-source " +
                "declarations, contradicting the separate-program assumption"), violation.toString())
            assertEquals(TraversalEvidence.CANDIDATE, reverse(scope).getValue("method:p/Client#fetch()V"))
        }
        // 호출자 → 테스트 구현 후보(dispatch 모델) 간선은 참조가 아니라 dispatch 가능성이다.
        val modeled = GraphEdge(NodeId("method:p/Screen#show()V"), NodeId("method:p/FakeClient#fetch()V"), EdgeKind.OVERRIDE,
            origin = EdgeOrigin.DISPATCH_MODEL)
        assertEquals(5, TestSourceScope.select(graph(listOf(modeled)), emptyList(), includeTests = false).excludedTestSources)
    }

    @Test
    fun `roots resolve in the whole graph so test twins stay ambiguous`() {
        // 테스트 소스의 q/Http는 production p/Http와 이름이 같다. 이름 요청은 전체 그래프에서 모호하다.
        val full = CodeGraph(graph().nodes.values + GraphNode(NodeId("class:q/Http"), "Http", NodeKind.CLASS,
            location = SourceLocation("$test/Http.kt")), graph().edges)
        val scope = TestSourceScope.select(full, listOf("Http"), includeTests = false)
        assertEquals(6, scope.excludedTestSources, "an ambiguous request is not a test root")
        val traversal = LanguageTraversal.traverse(scope.graph, listOf("Http"), TraversalDirection.DEPENDENTS, rootGraph = full)
        assertTrue(traversal.rootNotFound, "the production twin must not be picked silently")
        assertTrue(LanguageTraversal.traverse(scope.graph, listOf("class:p/Http"), TraversalDirection.DEPENDENTS, rootGraph = full)
            .roots.single().node != null)
    }

    @Test
    fun `graphs without test sources are unchanged`() {
        val original = CodeGraph(listOf(type("Http", main), method("Http", "get", main), GraphNode(NodeId("class:p/Bare"), "Bare",
            NodeKind.CLASS)), emptyList())
        val scope = TestSourceScope.select(original, listOf("method:p/Http#get()V"), includeTests = false)
        assertSame(original, scope.graph)
        assertTrue(scope.limitations.isEmpty())
    }
}
