package dev.kartograph.analysis

import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `reach`(정방향)와 `impact --format language-traversal`(역방향)의 대칭성 `U ∈ reach(H) ⇔ H ∈ impact(U)`를 검증한다
 * (API 영향 계획 Phase 4 종료 조건, 같은 스냅샷·같은 옵션).
 *
 * 결론(docs/IMPACT.md "정방향·역방향 대칭성"):
 * - 따르는 콜백 간선이 없으면(`--dispatch direct`, 콜백 사실이 없는 스냅샷, 콜백 간선이 없는 그래프) 모든 `--dispatch`·
 *   `--class-hops` 조합에서 두 관계는 정확히 전치이고, depth와 evidence도 같다. 두 방향이 같은 간선 집합을 반대로 따르고
 *   `member-only`가 막는 경로 모양(`TYPE_REFERENCE ∘ 상속* ∘ OWNER_CALLBACK`)이 방향과 무관하기 때문이다.
 * - 콜백 간선이 있으면 `impact`만 콜백 문맥을 따른다(설계). `impact(U) = reach⁻¹(U) ∪ {G | G →cb+ B, B = U 또는 U ∈ reach(B)}`
 *   이며 늘어나는 쌍은 정확히 "콜백 사슬로 람다 본문 B를 부르는 G"다. `reach`는 콜백 fan-out을 따르지 않는다.
 * - 테스트 소스 범위는 root가 정한다. `--include-tests`거나 두 선언이 모두 production이면 같은 그래프라 위 결론이 그대로다.
 */
class TraversalSymmetryTest {
    @Test
    fun `reach and impact are exact transposes when no callback edge is followed`() {
        var narrowed = 0
        repeat(300) { seed ->
            val random = Random(40_000 + seed)
            val graph = randomGraph(random)
            val assembled = TraversalEdges.assemble(graph)
            val maxDepth = if (random.nextBoolean()) LanguageTraversal.MAX_DEPTH else random.nextInt(1, 5)
            for (dispatch in TraversalDispatch.entries) {
                val byHops = TraversalClassHops.entries.associateWith { classHops ->
                    relations(graph, assembled, dispatch, classHops, maxDepth).also { relations ->
                        assertTransposed(graph, relations, "seed $seed dispatch ${dispatch.label} class-hops ${classHops.label} depth $maxDepth")
                    }
                }
                narrowed += pairs(byHops.getValue(TraversalClassHops.ALL)) - pairs(byHops.getValue(TraversalClassHops.MEMBER_ONLY))
            }
        }
        // 표본이 member-only가 실제로 좁히는 그래프를 담아야 그 조합의 대칭이 의미 있다.
        assertTrue(narrowed > 0, "random graphs never exercised member-only narrowing")
    }

    @Test
    fun `direct dispatch stays symmetric with callback edges and wider modes add exactly the callback invoker pairs`() {
        var extra = 0
        repeat(300) { seed ->
            val random = Random(50_000 + seed)
            val graph = randomGraph(random)
            val names = graph.nodeIds.map { it.value }
            val terminals = List(random.nextInt(1, 6)) {
                TraversalEdge(NodeId(names.random(random)), NodeId(names.random(random)), "callback",
                    if (random.nextBoolean()) TraversalEdgeTier.BOUND else TraversalEdgeTier.CANDIDATE, terminal = true)
            }
            val assembled = TraversalGraph(TraversalEdges.build(graph) + terminals, CallbackFlowSummary())
            for (dispatch in TraversalDispatch.entries) for (classHops in TraversalClassHops.entries) {
                val context = "seed $seed dispatch ${dispatch.label} class-hops ${classHops.label}"
                val relations = relations(graph, assembled, dispatch, classHops, LanguageTraversal.MAX_DEPTH)
                val followed = terminals.filter { it.tier in dispatch.tiers && it.source != it.target }
                if (followed.isEmpty()) {
                    assertTransposed(graph, relations, context)
                    continue
                }
                assertCallbackCharacterization(graph, relations, followed, context)
                extra += relations.impact.values.sumOf { it.size } - pairs(relations)
            }
        }
        // 콜백 문맥이 실제로 쌍을 더한 표본이 있어야 특성화가 비어 있지 않다.
        assertTrue(extra > 0, "random callback edges never added an invoker pair")
    }

    @Test
    fun `a callback invoker is in impact of the lambda body but the body is not in its reach`() {
        // screen이 람다 body를 만들어(contains) button에 넘기고, button은 받은 람다를 부른다(callback). body는 api를 부른다.
        val names = listOf("screen", "button", "body", "api", "other")
        val graph = CodeGraph(names.map(::method), listOf(
            GraphEdge(NodeId(id("screen")), NodeId(id("button")), EdgeKind.CALL),
            GraphEdge(NodeId(id("body")), NodeId(id("api")), EdgeKind.CALL),
            GraphEdge(NodeId(id("other")), NodeId(id("button")), EdgeKind.CALL),
        ))
        val assembled = TraversalGraph(TraversalEdges.build(graph) + listOf(
            TraversalEdge(NodeId(id("screen")), NodeId(id("body")), TraversalEdges.CONTAINS, TraversalEdgeTier.DIRECT),
            TraversalEdge(NodeId(id("button")), NodeId(id("body")), "callback", TraversalEdgeTier.BOUND, terminal = true),
        ), CallbackFlowSummary())
        fun run(root: String, direction: TraversalDirection, dispatch: TraversalDispatch) =
            LanguageTraversal.traverse(graph, assembled, listOf(id(root)), direction, dispatch).reached.map { it.node.name }.toSet()

        val impact = run("api", TraversalDirection.DEPENDENTS, TraversalDispatch.CANDIDATES)
        assertEquals(setOf("body", "screen", "button"), impact, "button is listed in the call context of body only")
        assertFalse("api" in run("button", TraversalDirection.DEPENDENCIES, TraversalDispatch.CANDIDATES), "reach does not fan out callbacks")
        assertTrue("api" in run("screen", TraversalDirection.DEPENDENCIES, TraversalDispatch.CANDIDATES), "the creator reaches the body")
        assertFalse("other" in impact, "the invoker's other callers pass other lambdas")
        assertEquals(setOf("body", "screen"), run("api", TraversalDirection.DEPENDENTS, TraversalDispatch.DIRECT))
    }

    @Test
    fun `test source scope is chosen by the root so only pairs across the test boundary differ by default`() {
        // p(production)는 인터페이스 호출로 Fake(test) 구현 후보에 닿고, t(test)는 p를 부른다.
        val nodes = listOf(
            GraphNode(NodeId("method:app/P#p()V"), "p", NodeKind.METHOD, location = SourceLocation("app/src/main/kotlin/P.kt")),
            GraphNode(NodeId("method:app/Api#m()V"), "m", NodeKind.METHOD, location = SourceLocation("app/src/main/kotlin/Api.kt")),
            GraphNode(NodeId("method:app/Fake#m()V"), "m", NodeKind.METHOD, location = SourceLocation("app/src/test/kotlin/Fake.kt")),
            GraphNode(NodeId("method:app/T#t()V"), "t", NodeKind.METHOD, location = SourceLocation("app/src/test/kotlin/T.kt")),
        )
        val graph = CodeGraph(nodes, listOf(
            GraphEdge(NodeId("method:app/P#p()V"), NodeId("method:app/Api#m()V"), EdgeKind.CALL),
            GraphEdge(NodeId("method:app/Api#m()V"), NodeId("method:app/Fake#m()V"), EdgeKind.OVERRIDE),
            GraphEdge(NodeId("method:app/T#t()V"), NodeId("method:app/P#p()V"), EdgeKind.CALL),
        ))
        fun run(root: String, direction: TraversalDirection, includeTests: Boolean): Set<String> {
            val scope = TestSourceScope.select(graph, listOf(root), includeTests)
            return LanguageTraversal.traverse(scope.graph, listOf(root), direction, rootGraph = graph).reached.map { it.node.id.value }.toSet()
        }
        val ids = nodes.map { it.id.value }
        val production = ids.filter { "/src/main/" in graph.node(NodeId(it))!!.location!!.path }
        // 기본: production 쌍은 대칭이다.
        for (h in production) for (u in production) if (h != u) {
            assertEquals(u in run(h, TraversalDirection.DEPENDENCIES, false), h in run(u, TraversalDirection.DEPENDENTS, false), "$h -> $u")
        }
        // 기본: test root는 전체 그래프, production root는 production 부분 그래프를 순회한다(설계된 비대칭).
        assertTrue("method:app/P#p()V" in run("method:app/T#t()V", TraversalDirection.DEPENDENCIES, false))
        assertFalse("method:app/T#t()V" in run("method:app/P#p()V", TraversalDirection.DEPENDENTS, false))
        assertTrue("method:app/P#p()V" in run("method:app/Fake#m()V", TraversalDirection.DEPENDENTS, false))
        assertFalse("method:app/Fake#m()V" in run("method:app/P#p()V", TraversalDirection.DEPENDENCIES, false))
        // --include-tests: 모든 쌍이 대칭이다.
        for (h in ids) for (u in ids) if (h != u) {
            assertEquals(u in run(h, TraversalDirection.DEPENDENCIES, true), h in run(u, TraversalDirection.DEPENDENTS, true), "include-tests $h -> $u")
        }
    }

    /** 정점마다 한 root 순회로 구한 정방향·역방향 도달 행이다. 키는 root usr, 값은 도달 정점 usr → 행이다. */
    private data class Relations(
        val reach: Map<String, Map<String, TraversalReached>>,
        val impact: Map<String, Map<String, TraversalReached>>,
    )

    private fun relations(
        graph: CodeGraph, assembled: TraversalGraph, dispatch: TraversalDispatch, classHops: TraversalClassHops, maxDepth: Int,
    ): Relations {
        fun rows(direction: TraversalDirection) = graph.nodeIds.associate { root ->
            val result = LanguageTraversal.traverse(graph, assembled, listOf(root.value), direction, dispatch, maxDepth, classHops = classHops)
            root.value to result.reached.associateBy { it.node.id.value }
        }
        return Relations(rows(TraversalDirection.DEPENDENCIES), rows(TraversalDirection.DEPENDENTS))
    }

    /** 정방향 관계의 (H, U) 쌍 수다. */
    private fun pairs(relations: Relations): Int = relations.reach.values.sumOf { it.size }

    /** 모든 (H, U)에서 `U ∈ reach(H) ⇔ H ∈ impact(U)`이고, 같은 경로 길이·근거 등급을 싣는지 본다. */
    private fun assertTransposed(graph: CodeGraph, relations: Relations, context: String) {
        val ids = graph.nodeIds.map { it.value }
        for (h in ids) for (u in ids) {
            val forward = relations.reach.getValue(h)[u]
            val backward = relations.impact.getValue(u)[h]
            assertEquals(forward != null, backward != null, "$context: $u in reach($h) vs $h in impact($u)")
            if (forward != null && backward != null) {
                assertEquals(forward.depth, backward.depth, "$context: depth $h -> $u")
                assertEquals(forward.evidence, backward.evidence, "$context: evidence $h -> $u")
            }
        }
    }

    /**
     * 콜백 간선이 있을 때 `impact(U)`가 정확히 `reach⁻¹(U) ∪ {G | G →cb+ B, B ∈ reach⁻¹(U) ∪ {U}} − {U}`인지 본다.
     * `U ∈ reach(H) ⇒ H ∈ impact(U)`는 이 등식에 포함된다.
     */
    private fun assertCallbackCharacterization(graph: CodeGraph, relations: Relations, followed: List<TraversalEdge>, context: String) {
        val ids = graph.nodeIds.map { it.value }
        val invokers = followed.groupBy({ it.target.value }, { it.source.value })
        fun ancestors(body: String): Set<String> {
            val found = mutableSetOf<String>()
            val pending = ArrayDeque(invokers[body].orEmpty())
            while (pending.isNotEmpty()) { val next = pending.removeFirst(); if (found.add(next)) pending += invokers[next].orEmpty() }
            return found
        }
        for (u in ids) {
            val reachers = ids.filter { h -> u in relations.reach.getValue(h) }.toSet()
            val expected = (reachers + (reachers + u).flatMap(::ancestors)) - u
            assertEquals(expected, relations.impact.getValue(u).keys, "$context: impact($u)")
        }
    }

    /**
     * 무작위 그래프다. class·생성자·메서드 정점 위에 호출, 이름만 적은 참조, 인스턴스 생성, 콜백 모델(class → 멤버),
     * 상속, 런타임 모델 참조, 외부 dispatch 후보(candidate·lambda), 선언 override와 어휘적 소속을 섞는다.
     */
    private fun randomGraph(random: Random): CodeGraph {
        val classes = List(random.nextInt(1, 5)) { "app/K$it" }
        val nodes = classes.flatMap { owner ->
            listOf(GraphNode(NodeId("class:$owner"), owner, NodeKind.CLASS), GraphNode(NodeId("method:$owner#<init>()V"), "<init>", NodeKind.CONSTRUCTOR)) +
                List(random.nextInt(1, 4)) { GraphNode(NodeId("method:$owner#m$it()V"), "m$it", NodeKind.METHOD) }
        }
        val members = nodes.filter { it.kind != NodeKind.CLASS }.map { it.id }
        fun member() = members.random(random)
        fun type() = classes.random(random)
        val edges = mutableListOf<GraphEdge>()
        val calls = mutableListOf<ExternalCall>()
        repeat(random.nextInt(0, members.size * 3)) {
            when (random.nextInt(11)) {
                0, 1 -> edges += GraphEdge(member(), member(), EdgeKind.CALL)
                2 -> edges += GraphEdge(member(), NodeId("class:${type()}"), EdgeKind.REFERENCE)
                3 -> type().let { owner -> members.filter { it.value.startsWith("method:$owner#") }.random(random)
                    .let { edges += GraphEdge(NodeId("class:$owner"), it, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL) } }
                4 -> edges += GraphEdge(NodeId("class:${type()}"), NodeId("class:${type()}"), EdgeKind.INHERITANCE)
                5 -> edges += GraphEdge(NodeId("class:${type()}"), NodeId("class:${type()}"), EdgeKind.REFERENCE)
                6 -> type().let { owner -> member().let { source ->
                    edges += GraphEdge(source, NodeId("method:$owner#<init>()V"), EdgeKind.CALL)
                    edges += GraphEdge(source, NodeId("class:$owner"), EdgeKind.REFERENCE)
                } }
                7, 8 -> {
                    val (source, target) = member() to member()
                    val owner = if (random.nextBoolean()) "ext/Service" else "kotlin/jvm/functions/Function0"
                    edges += GraphEdge(source, target, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)
                    calls += ExternalCall(source, owner, "m", "()V", InvocationKind.INTERFACE, resolvedTargets = listOf(target),
                        resolution = CallResolution.PROJECT_CANDIDATES)
                }
                9 -> edges += GraphEdge(member(), member(), EdgeKind.OVERRIDE)
                else -> edges += GraphEdge(member(), member(), EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL)
            }
        }
        edges += members.map { GraphEdge(NodeId("class:" + it.value.substringAfter(':').substringBefore('#')), it, EdgeKind.MEMBER) }
        val enclosures = if (random.nextInt(3) == 0) listOf(LexicalEnclosure(NodeId("class:${type()}"), member())) else emptyList()
        return CodeGraph(nodes, edges, calls, enclosures = enclosures)
    }

    private fun method(name: String) = GraphNode(NodeId(id(name)), name, NodeKind.METHOD)
    private fun id(name: String) = "method:app/$name#run()V"
}
