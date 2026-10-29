package dev.kartograph.cli

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.LanguageTraversalResult
import dev.kartograph.analysis.TraversalClassHops
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.analysis.TraversalDispatch
import dev.kartograph.analysis.TraversalEdgeTier
import dev.kartograph.analysis.TraversalEdges
import dev.kartograph.core.CodeGraph
import dev.kartograph.export.LanguageTraversalCodec
import dev.kartograph.index.ClassFileIndexer
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * kartograph 자신의 컴파일 그래프(core·index·analysis·export·cli main class, `snapshot`과 같은 indexer 경로)에서
 * `reach`·`impact --format language-traversal`의 대칭성을 표본으로 확인한다. 무작위 그래프의 전수 검증은
 * analysis `TraversalSymmetryTest`이고, 여기서는 실제 bytecode의 콜백 흐름·dispatch·class hop이 같은 결론을 따르는지 본다.
 *
 * 판정: 표본 (H, U)마다 `H ∈ impact(U) ⇔ U ∈ reach(H) ∨ (H →cb+ B, B = U 또는 U ∈ reach(B))`. 콜백 간선을 따르지 않는
 * `--dispatch direct`에서는 오른쪽이 `U ∈ reach(H)`뿐이라 정확한 전치다.
 */
class TraversalSymmetrySelfTest {
    /** 순회가 따르는 간선 등급이다(analysis `TraversalDispatch.tiers`와 같다). */
    private val tiers = mapOf(
        TraversalDispatch.DIRECT to setOf(TraversalEdgeTier.DIRECT),
        TraversalDispatch.BOUND to setOf(TraversalEdgeTier.DIRECT, TraversalEdgeTier.BOUND),
        TraversalDispatch.CANDIDATES to setOf(TraversalEdgeTier.DIRECT, TraversalEdgeTier.BOUND, TraversalEdgeTier.CANDIDATE),
        TraversalDispatch.ALL to TraversalEdgeTier.entries.toSet(),
    )

    @Test
    fun `reach and impact are symmetric up to callback context on the self graph`() {
        val graph = selfGraph()
        val edges = TraversalEdges.build(graph)
        val callbacks = edges.filter { it.terminal && it.source != it.target }
        assertTrue(callbacks.isNotEmpty(), "the self graph should carry callback value-flow edges")
        val ids = graph.nodeIds.map { it.value }
        val random = Random(20260929)
        val seeds = (ids.shuffled(random).take(32) + callbacks.shuffled(random).take(8).flatMap { listOf(it.source.value, it.target.value) }).distinct()
        var checkedPairs = 0
        var positivePairs = 0
        var callbackPairs = 0
        for (dispatch in listOf(TraversalDispatch.DIRECT, TraversalDispatch.CANDIDATES, TraversalDispatch.ALL)) {
            for (classHops in TraversalClassHops.entries) {
                val context = "dispatch ${dispatch.label} class-hops ${classHops.label}"
                val followed = callbacks.filter { it.tier in tiers.getValue(dispatch) }
                val invoked = followed.groupBy({ it.source.value }, { it.target.value })
                fun bodies(invoker: String): Set<String> {
                    val found = mutableSetOf<String>()
                    val pending = ArrayDeque(invoked[invoker].orEmpty())
                    while (pending.isNotEmpty()) { val next = pending.removeFirst(); if (found.add(next)) pending += invoked[next].orEmpty() }
                    return found
                }
                val firstReach = related(graph, seeds, TraversalDirection.DEPENDENCIES, dispatch, classHops)
                val targets = (seeds + seeds.flatMap { firstReach[it].orEmpty().shuffled(random).take(4) }).distinct()
                val impact = related(graph, targets, TraversalDirection.DEPENDENTS, dispatch, classHops)
                val sources = (seeds + targets.flatMap { impact[it].orEmpty().shuffled(random).take(3) }).distinct().take(LanguageTraversal.MAX_ROOTS)
                val reachRoots = (sources + sources.flatMap(::bodies)).distinct()
                val reach = related(graph, reachRoots, TraversalDirection.DEPENDENCIES, dispatch, classHops)
                for (h in sources) for (u in targets) {
                    if (h == u) continue
                    val forward = u in reach[h].orEmpty()
                    val viaCallback = bodies(h).any { body -> body == u || u in reach[body].orEmpty() }
                    val backward = h in impact[u].orEmpty()
                    assertEquals(forward || viaCallback, backward, "$context: $h in impact($u), reach=$forward callback=$viaCallback")
                    if (dispatch == TraversalDispatch.DIRECT) assertEquals(forward, backward, "$context: direct is an exact transpose for $h -> $u")
                    checkedPairs++
                    if (forward) positivePairs++
                    if (backward && !forward) callbackPairs++
                }
            }
        }
        println("self graph ${ids.size} vertices, ${callbacks.size} callback edges: $checkedPairs pairs, $positivePairs in reach, " +
            "$callbackPairs impact-only through callback context")
        // 표본이 양성 쌍과 콜백 문맥 쌍을 실제로 담아야 비교가 의미 있다.
        assertTrue(checkedPairs > 10_000 && positivePairs > 1_000, "too few sampled pairs: $checkedPairs ($positivePairs in reach)")
        assertTrue(callbackPairs > 0, "no sampled pair exercised the callback context")
    }

    /** 여러 root를 한 번에 순회해 root usr → 도달 정점 usr 집합을 만든다(root 자신 제외, 한 root 순회와 같은 행). */
    private fun related(
        graph: CodeGraph, roots: List<String>, direction: TraversalDirection, dispatch: TraversalDispatch, classHops: TraversalClassHops,
    ): Map<String, Set<String>> {
        val result: LanguageTraversalResult = LanguageTraversal.traverse(graph, roots, direction, dispatch, classHops = classHops)
        assertTrue(!result.depthTruncated && !result.reachedTruncated && !result.rootNotFound, "self traversal must not be truncated")
        val related = roots.associateWith { mutableSetOf<String>() }
        result.reached.forEach { row -> row.roots.forEach { index -> related.getValue(result.roots[index].id) += row.node.id.value } }
        return related
    }

    /** 이 테스트 classpath의 production class root(디렉터리 또는 JAR)로 그래프를 만든다. */
    private fun selfGraph(): CodeGraph {
        val roots = listOf(CodeGraph::class.java, ClassFileIndexer::class.java, LanguageTraversal::class.java,
            LanguageTraversalCodec::class.java, KartographCli::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct()
        return ClassFileIndexer().index(roots)
    }
}
