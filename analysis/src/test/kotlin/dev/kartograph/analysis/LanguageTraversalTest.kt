package dev.kartograph.analysis

import dev.kartograph.core.CallResolution
import dev.kartograph.core.CallbackArgument
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.ParameterUseKind
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 다중 root 순회가 root별 전수 BFS와 같은 결과를 내는지, 간선 등급이 설계대로 매겨지는지 확인한다. */
class LanguageTraversalTest {
    @Test
    fun `single pass matches brute force per-root traversal on random graphs`() {
        repeat(600) { seed ->
            val random = Random(seed)
            val graph = randomGraph(random)
            val ids = graph.nodeIds.map { it.value }
            val requested = List(random.nextInt(1, 7)) { if (random.nextInt(12) == 0) "missing-$it" else ids.random(random) }
            val direction = TraversalDirection.entries.random(random)
            val dispatch = TraversalDispatch.entries.random(random)
            val maxDepth = if (random.nextBoolean()) LanguageTraversal.MAX_DEPTH else random.nextInt(1, 5)
            val result = LanguageTraversal.traverse(graph, requested, direction, dispatch, maxDepth)
            assertMatchesOracle(graph, result, maxDepth, "seed $seed")
        }
    }

    @Test
    fun `callback edges reach callers only in their call context on random graphs`() {
        repeat(400) { seed ->
            val random = Random(10_000 + seed)
            val graph = randomGraph(random)
            val names = graph.nodeIds.map { it.value }
            val terminals = List(random.nextInt(0, names.size)) {
                TraversalEdge(NodeId(names.random(random)), NodeId(names.random(random)), "callback",
                    if (random.nextBoolean()) TraversalEdgeTier.BOUND else TraversalEdgeTier.CANDIDATE, terminal = true)
            }
            val assembled = TraversalGraph(TraversalEdges.build(graph) + terminals, CallbackFlowSummary())
            val requested = List(random.nextInt(1, 6)) { names.random(random) }
            val dispatch = TraversalDispatch.entries.random(random)
            val maxDepth = if (random.nextBoolean()) LanguageTraversal.MAX_DEPTH else random.nextInt(1, 5)
            val result = LanguageTraversal.traverse(graph, assembled, requested, TraversalDirection.DEPENDENTS, dispatch, maxDepth)
            assertMatchesContextOracle(assembled.edges, graph, result, maxDepth, "seed $seed")
            // 정방향은 콜백 간선을 따르지 않는다.
            val forward = LanguageTraversal.traverse(graph, assembled, requested, TraversalDirection.DEPENDENCIES, dispatch, maxDepth)
            assertEquals(LanguageTraversal.traverse(graph, requested, TraversalDirection.DEPENDENCIES, dispatch, maxDepth).reached,
                forward.reached, "seed $seed forward")
        }
    }

    @Test
    fun `a callback caller is listed without spreading to its other callers`() {
        // Screen이 만든 람다 Body를 Button이 실행한다. Other도 Button을 부르지만 다른 람다를 넘긴다.
        val graph = graphOf(listOf("Route", "Body", "Screen", "Button", "Other"),
            listOf("Body" to "Route", "Screen" to "Button", "Other" to "Button"))
        val callback = TraversalEdge(NodeId(id("Button")), NodeId(id("Body")), "callback", TraversalEdgeTier.BOUND, terminal = true)
        val assembled = TraversalGraph(TraversalEdges.build(graph) + callback, CallbackFlowSummary(bound = 1))
        val result = LanguageTraversal.traverse(graph, assembled, listOf(id("Route")), TraversalDirection.DEPENDENTS)
        val byName = result.reached.associateBy { it.node.name }
        assertEquals(setOf("Body", "Button"), byName.keys)
        assertEquals(TraversalEvidence.BOUND, byName.getValue("Button").evidence)
        assertEquals(id("Body"), byName.getValue("Button").via)
        assertEquals(listOf("callback"), byName.getValue("Button").relationships)
        assertTrue(result.limitations.any { it.startsWith("callback-flow: 1 bound") })
        val direct = LanguageTraversal.traverse(graph, assembled, listOf(id("Route")), TraversalDirection.DEPENDENTS, TraversalDispatch.DIRECT)
        assertEquals(setOf("Body"), direct.reached.map { it.node.name }.toSet())
        assertTrue(direct.limitations.any { it.startsWith("callback-excluded: 1 ") })
        assertTrue(direct.limitations.none { it.startsWith("dispatch-excluded:") })
    }

    @Test
    fun `legacy snapshots without callback facts say so`() {
        val graph = graphOf(listOf("A", "B"), listOf("B" to "A"))
        val result = LanguageTraversal.traverse(graph, listOf(id("A")), TraversalDirection.DEPENDENTS, callbackFactsCaptured = false)
        assertTrue(result.limitations.any { it.startsWith("callback-facts-unavailable:") })
        // 리뷰 지적(H4): 일부 키만 남은 snapshot의 사실로 콜백 간선을 만들지 않는다.
        val body = NodeId(id("B"))
        val partial = CodeGraph(graph.nodes.values + method("G"), graph.edges,
            callbackArguments = listOf(CallbackArgument(NodeId(id("B")), NodeId(id("G")), InvocationKind.STATIC, 0, body, "run()V")),
            parameterUses = listOf(ParameterUse(NodeId(id("G")), 0, ParameterUseKind.DECLARED),
                ParameterUse(NodeId(id("G")), 0, ParameterUseKind.RECEIVER, NodeId("method:java/lang/Runnable#run()V"), InvocationKind.INTERFACE)))
        assertTrue(TraversalEdges.build(partial).any { it.terminal })
        assertTrue(TraversalEdges.build(partial, callbackFactsCaptured = false).none { it.terminal })
        val forward = LanguageTraversal.traverse(graph, listOf(id("A")), TraversalDirection.DEPENDENCIES, callbackFactsCaptured = false)
        assertTrue(forward.limitations.none { it.startsWith("callback-") })
    }

    @Test
    fun `roots reached from other roots follow the contract example`() {
        // 계약 예시: root A(0)·R(1), 순회 방향 간선 A→W→V→R과 R→V면 V는 depth 1(via R), R 항목은 depth 3(via V)다.
        val graph = graphOf(listOf("A", "W", "V", "R"), listOf("W" to "A", "V" to "W", "R" to "V", "V" to "R"))
        val result = LanguageTraversal.traverse(graph, listOf(id("A"), id("R")), TraversalDirection.DEPENDENTS)
        val byName = result.reached.associateBy { it.node.name }
        assertEquals(1, byName.getValue("V").depth)
        assertEquals(id("R"), byName.getValue("V").via)
        assertEquals(listOf(0, 1), byName.getValue("V").roots)
        assertEquals(3, byName.getValue("R").depth)
        assertEquals(id("V"), byName.getValue("R").via)
        assertEquals(listOf(0), byName.getValue("R").roots)
    }

    @Test
    fun `lambda bodies reach their enclosing function through containment instead of invoke fan-out`() {
        val graph = lambdaGraph()
        val body = "method:app/Screen\$render\$1#invoke()V"
        val default = LanguageTraversal.traverse(graph, listOf(body), TraversalDirection.DEPENDENTS)
        assertEquals(setOf("render", "open"), default.reached.map { it.node.name }.toSet() - setOf("Screen\$render\$1"))
        assertTrue(default.limitations.any { it.startsWith("lambda-dispatch-excluded: 2 ") })
        val all = LanguageTraversal.traverse(graph, listOf(body), TraversalDirection.DEPENDENTS, TraversalDispatch.ALL)
        assertTrue(all.reached.any { it.node.name == "button" && it.evidence == TraversalEvidence.CANDIDATE })
        val legacy = LanguageTraversal.traverse(graph, listOf(body), TraversalDirection.DEPENDENTS, enclosuresCaptured = false)
        assertTrue(legacy.limitations.any { it.startsWith("lexical-enclosures-unavailable:") })
        assertTrue(legacy.reached.any { it.node.name == "button" })
    }

    @Test
    fun `override dispatch is bound only for a single concrete project implementation`() {
        val tiers = TraversalEdges.build(dispatchGraph()).filter { it.relationship == "override" }
            .associate { it.source.value to it.target.value to it.tier }
        assertEquals(TraversalEdgeTier.BOUND, tiers["method:app/Single#load()V" to "method:app/SingleImpl#load()V"])
        assertEquals(TraversalEdgeTier.CANDIDATE, tiers["method:app/Multi#load()V" to "method:app/MultiA#load()V"])
        assertEquals(TraversalEdgeTier.CANDIDATE, tiers["method:app/Multi#load()V" to "method:app/MultiB#load()V"])
        assertEquals(TraversalEdgeTier.LAMBDA, tiers["method:app/Caller#run()V" to "method:app/Caller\$run\$1#invoke()V"])
        assertEquals(TraversalEdgeTier.CANDIDATE, tiers["method:app/Caller#run()V" to "method:app/MultiA#toString()Ljava/lang/String;"])
        assertEquals(TraversalEdgeTier.BOUND, tiers["method:app/Caller#run()V" to "method:app/SingleImpl#close()V"])
    }

    @Test
    fun `bound quantifies over class nodes without jvm signatures`() {
        // 리뷰 지적 재현: jvmSignature가 빠진 하위 class가 전칭 범위에서 빠지면 단일 구현으로 잘못 판정된다.
        val base = dispatchGraph()
        val sub = GraphNode(NodeId("class:app/SingleSub"), "SingleSub", NodeKind.CLASS, supertypes = setOf("app/SingleImpl"))
        val override = GraphNode(NodeId("method:app/SingleSub#load()V"), "load", NodeKind.METHOD)
        val graph = CodeGraph(base.nodes.values + sub + override, base.edges, base.externalCalls, enclosures = base.enclosures)
        val tier = TraversalEdges.build(graph).single {
            it.source.value == "method:app/Single#load()V" && it.target.value == "method:app/SingleImpl#load()V"
        }.tier
        assertEquals(TraversalEdgeTier.CANDIDATE, tier)
    }

    @Test
    fun `dispatch model edges are dispatch tiers whatever their kind`() {
        val caller = NodeId("method:app/A#run()V")
        val target = NodeId("method:app/B#run()V")
        val graph = CodeGraph(listOf(GraphNode(caller, "run", NodeKind.METHOD), GraphNode(target, "run", NodeKind.METHOD)),
            listOf(GraphEdge(caller, target, EdgeKind.CALL, origin = EdgeOrigin.DISPATCH_MODEL)),
            listOf(ExternalCall(caller, "ext/Service", "run", "()V", InvocationKind.INTERFACE, resolvedTargets = listOf(target),
                resolution = CallResolution.PROJECT_CANDIDATES)))
        assertEquals(TraversalEdgeTier.CANDIDATE, TraversalEdges.build(graph).single().tier)
    }

    @Test
    fun `unresolved calls count unresolved dispatch reflective lookups and unmodeled bootstraps`() {
        val caller = NodeId("method:app/A#run()V")
        fun call(kind: InvocationKind, resolution: CallResolution, model: String? = null, owner: String = "ext/T") =
            ExternalCall(caller, owner, "m", "()V", kind, resolution = resolution, model = model)
        assertTrue(call(InvocationKind.INTERFACE, CallResolution.UNRESOLVED).isUnresolvedTarget())
        assertFalse(call(InvocationKind.STATIC, CallResolution.UNRESOLVED).isUnresolvedTarget())
        assertTrue(call(InvocationKind.STATIC, CallResolution.UNRESOLVED, model = "jdk.class-for-name.v1").isUnresolvedTarget())
        assertFalse(call(InvocationKind.STATIC, CallResolution.RUNTIME_MODEL, model = "jdk.class-for-name.v1").isUnresolvedTarget())
        assertFalse(call(InvocationKind.BOOTSTRAP, CallResolution.UNRESOLVED, owner = "java/lang/invoke/LambdaMetafactory").isUnresolvedTarget())
        assertTrue(call(InvocationKind.BOOTSTRAP, CallResolution.UNRESOLVED, owner = "custom/Bootstrap").isUnresolvedTarget())
    }

    @Test
    fun `unknown and ambiguous roots are kept without a symbol`() {
        val graph = graphOf(listOf("A", "B"), listOf("B" to "A"))
        val result = LanguageTraversal.traverse(graph, listOf(id("A"), "nope", id("A")), TraversalDirection.DEPENDENTS)
        assertEquals(listOf(id("A"), "nope"), result.roots.map { it.id })
        assertTrue(result.rootNotFound)
        assertTrue(result.limitations.any { it.startsWith("root-not-found: 1 ") })
    }

    @Test
    fun `reached cap keeps the depth prefix and drops dangling root witnesses`() {
        val graph = graphOf(listOf("A", "B", "C", "D"), listOf("B" to "A", "C" to "B", "D" to "C"))
        val result = LanguageTraversal.traverse(graph, listOf(id("A")), TraversalDirection.DEPENDENTS, maxReached = 2)
        assertEquals(listOf("B", "C"), result.reached.map { it.node.name })
        assertTrue(result.reachedTruncated)
    }

    @Test
    fun `member-only class hops match a state-space brute force on random graphs with classes`() {
        repeat(500) { seed ->
            val random = Random(20_000 + seed)
            val graph = classGraph(random)
            val names = graph.nodeIds.map { it.value }
            val members = names.filter { !it.startsWith("class:") }
            val terminals = List(random.nextInt(0, 4)) {
                TraversalEdge(NodeId(members.random(random)), NodeId(names.random(random)), "callback",
                    if (random.nextBoolean()) TraversalEdgeTier.BOUND else TraversalEdgeTier.CANDIDATE, terminal = true)
            }
            val assembled = TraversalGraph(TraversalEdges.build(graph) + terminals, CallbackFlowSummary())
            val requested = List(random.nextInt(1, 5)) { names.random(random) }
            val direction = TraversalDirection.entries.random(random)
            val dispatch = TraversalDispatch.entries.random(random)
            val classHops = TraversalClassHops.entries.random(random)
            val maxDepth = if (random.nextBoolean()) LanguageTraversal.MAX_DEPTH else random.nextInt(1, 5)
            val result = LanguageTraversal.traverse(graph, assembled, requested, direction, dispatch, maxDepth, classHops = classHops)
            assertMatchesClassHopOracle(assembled.edges, graph, result, classHops, maxDepth, "seed $seed")
            val wide = LanguageTraversal.traverse(graph, assembled, requested, direction, dispatch, maxDepth)
            assertTrue(wide.reached.map { it.node.id }.containsAll(result.reached.map { it.node.id }), "seed $seed narrow is a subset")
            if (classHops == TraversalClassHops.ALL) assertEquals(wide, result, "seed $seed all is the default")
        }
    }

    @Test
    fun `member-only stops a member change at its class but keeps class-level usages`() {
        val graph = viewModelGraph()
        val root = listOf("method:app/Http#get()V")
        val wide = LanguageTraversal.traverse(graph, root, TraversalDirection.DEPENDENTS).reached.map { it.node.id.value }.toSet()
        val narrow = LanguageTraversal.traverse(graph, root, TraversalDirection.DEPENDENTS, classHops = TraversalClassHops.MEMBER_ONLY)
        val kept = setOf("method:app/Vm#load()V", "method:app/Screen#render(Lapp/Vm;)V", "class:app/Vm", "method:app/Factory#create()Lapp/Vm;",
            "class:app/SpecialVm", "method:app/Reflect#lookup()V", "class:app/Web\$1", "method:app/Web\$1#onPage()V", "method:app/Web#show()V",
            "method:app/Web#open()V")
        val spill = setOf("method:app/Format#label(Lapp/Vm;)V", "field:app/Holder#vm:Lapp/Vm;", "method:app/Holder#read()V",
            "method:app/Printer#print()V", "method:app/SpecialUser#show(Lapp/SpecialVm;)V")
        assertEquals(kept + spill, wide)
        assertEquals(kept, narrow.reached.map { it.node.id.value }.toSet())
        val byId = narrow.reached.associateBy { it.node.id.value }
        assertEquals("method:app/Vm#load()V", byId.getValue("class:app/Vm").via)
        assertEquals(listOf("reference"), byId.getValue("class:app/Vm").relationships)
        assertEquals("class:app/Vm", byId.getValue("method:app/Factory#create()Lapp/Vm;").via)
        assertEquals(listOf("inheritance"), byId.getValue("class:app/SpecialVm").relationships)
        // Vm은 Screen.render·Format.label·Holder.vm, SpecialVm은 SpecialUser.show를 따르지 않는다. Web$1은 이름만 적은 사용자가 없다.
        assertTrue(narrow.limitations.any { it.startsWith("class-hops-narrowed: 4 type-reference edge(s) from 2 class vertex(es)") },
            narrow.limitations.toString())
        val default = LanguageTraversal.traverse(graph, root, TraversalDirection.DEPENDENTS)
        assertTrue(default.limitations.none { it.startsWith("class-hops-narrowed:") })
        // class 자체가 root면 좁히지 않는다. 좁힐 hop이 없어도 모드를 알리는 한계는 싣는다.
        val classRoot = LanguageTraversal.traverse(graph, listOf("class:app/Vm"), TraversalDirection.DEPENDENTS, classHops = TraversalClassHops.MEMBER_ONLY)
        assertTrue(classRoot.reached.any { it.node.id.value == "method:app/Printer#print()V" })
        assertTrue(classRoot.limitations.any { it.startsWith("class-hops-narrowed: 0 type-reference edge(s) from 0 class vertex(es)") })
        // bound를 빼면 콜백 모델 간선이 없으므로 member → class hop도 없다.
        val direct = LanguageTraversal.traverse(graph, root, TraversalDirection.DEPENDENTS, TraversalDispatch.DIRECT, classHops = TraversalClassHops.MEMBER_ONLY)
        assertTrue(direct.reached.none { it.node.id.value == "class:app/Vm" })
    }

    @Test
    fun `member-only reach does not expand a named-only class to its runtime callbacks`() {
        val graph = viewModelGraph()
        fun reach(root: String, classHops: TraversalClassHops) =
            LanguageTraversal.traverse(graph, listOf(root), TraversalDirection.DEPENDENCIES, classHops = classHops)
        val label = "method:app/Format#label(Lapp/Vm;)V"
        assertTrue(reach(label, TraversalClassHops.ALL).reached.any { it.node.id.value == "method:app/Http#get()V" })
        val narrow = reach(label, TraversalClassHops.MEMBER_ONLY)
        // 다른 class 멤버로 가는 런타임 모델 간선은 콜백 모델이 아니므로 계속 따른다.
        assertEquals(setOf("class:app/Vm", "method:app/Other#run()V"), narrow.reached.map { it.node.id.value }.toSet())
        assertTrue(narrow.limitations.any { it.startsWith("class-hops-narrowed: 3 framework-callback member edge(s) from 1 class vertex(es)") },
            narrow.limitations.toString())
        // 인스턴스를 만드는 선언은 런타임 콜백까지 닿는다. 하위 class 이름만 적은 선언은 상속 사슬을 따라가도 막힌다.
        assertTrue(reach("method:app/Factory#create()Lapp/Vm;", TraversalClassHops.MEMBER_ONLY).reached.any { it.node.id.value == "method:app/Http#get()V" })
        val special = reach("method:app/SpecialUser#show(Lapp/SpecialVm;)V", TraversalClassHops.MEMBER_ONLY).reached.map { it.node.id.value }.toSet()
        assertEquals(setOf("class:app/SpecialVm", "class:app/Vm", "method:app/Other#run()V"), special)
    }

    @Test
    fun `class roles separate instantiation runtime lookups and owner callbacks from type references`() {
        val roles = TraversalEdges.build(viewModelGraph()).filter { it.classRole != null }
            .associate { (it.source.value to it.target.value) to it.classRole }
        assertEquals(TraversalClassRole.OWNER_CALLBACK, roles["class:app/Vm" to "method:app/Vm#load()V"])
        assertEquals(TraversalClassRole.TYPE_REFERENCE, roles["method:app/Format#label(Lapp/Vm;)V" to "class:app/Vm"])
        assertEquals(TraversalClassRole.TYPE_REFERENCE, roles["field:app/Holder#vm:Lapp/Vm;" to "class:app/Vm"])
        assertFalse("method:app/Factory#create()Lapp/Vm;" to "class:app/Vm" in roles, "instantiation is not a type reference")
        assertFalse("method:app/Reflect#lookup()V" to "class:app/Vm" in roles, "runtime-modeled lookups are not type references")
        assertFalse("class:app/Vm" to "method:app/Other#run()V" in roles, "a runtime edge to another class's member is not an owner callback")
    }

    private fun assertMatchesOracle(graph: CodeGraph, result: LanguageTraversalResult, maxDepth: Int, context: String) {
        val edges = TraversalEdges.build(graph).filter { it.tier in result.dispatch.tiers }
            .map { if (result.direction == TraversalDirection.DEPENDENTS) Triple(it.target.value, it.source.value, it) else Triple(it.source.value, it.target.value, it) }
            .filter { it.first != it.second }
        val roots = result.roots.map { it.node?.id?.value }
        fun distances(start: String, maxLevel: Int): Map<String, Int> {
            val found = mutableMapOf(start to 0)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                edges.filter { it.first == current && level(it.third.tier) <= maxLevel }.forEach { (_, next, _) ->
                    if (next !in found) { found[next] = found.getValue(current) + 1; queue += next }
                }
            }
            return found
        }
        val full = roots.map { root -> root?.let { distances(it, 2) }.orEmpty() }
        val levels = (0..2).map { level -> roots.map { root -> root?.let { distances(it, level) }.orEmpty() } }
        val expected = graph.nodeIds.map { it.value }.mapNotNull { vertex ->
            val reaching = roots.indices.filter { roots[it] != null && roots[it] != vertex && vertex in full[it] }
            if (reaching.isEmpty()) return@mapNotNull null
            val depth = reaching.minOf { full[it].getValue(vertex) }
            val evidence = reaching.maxOf { root -> (0..2).first { vertex in levels[it][root] } }
            vertex to Triple(reaching, depth, evidence)
        }.toMap()
        val listed = expected.filterValues { it.second <= maxDepth }
        assertEquals(listed.keys, result.reached.map { it.node.id.value }.toSet(), context)
        assertEquals(expected.size > listed.size, result.depthTruncated, context)
        assertEquals(result.reached.sortedWith(compareBy({ it.depth }, { it.node.id.value })), result.reached, context)
        for (row in result.reached) {
            val (reaching, depth, evidence) = listed.getValue(row.node.id.value)
            assertEquals(reaching, row.roots, "$context roots ${row.node.id}")
            assertEquals(depth, row.depth, "$context depth ${row.node.id}")
            assertEquals(TraversalEvidence.entries[evidence], row.evidence, "$context evidence ${row.node.id}")
            assertValidWitness(row, roots, full, edges.map { it.first to it.second }.toSet(), context)
        }
    }

    /**
     * 콜백 간선을 문맥 상태로 푼 전수 BFS다. 상태는 (정점, 그림자 여부)이며 일반 간선은 본 정점끼리만, 콜백 간선은
     * 본 정점·그림자에서 그림자로만 잇는다. 그림자에서는 콜백 간선만 나간다.
     */
    private fun assertMatchesContextOracle(all: List<TraversalEdge>, graph: CodeGraph, result: LanguageTraversalResult, maxDepth: Int, context: String) {
        val allowed = all.filter { it.tier in result.dispatch.tiers }
        val regular = allowed.filter { !it.terminal }.map { Triple(it.target.value, it.source.value, level(it.tier)) }.filter { it.first != it.second }
        val callbacks = allowed.filter { it.terminal && it.source != it.target }.map { Triple(it.target.value, it.source.value, level(it.tier)) }
        fun next(state: Pair<String, Boolean>, maxLevel: Int): List<Pair<String, Boolean>> {
            val (vertex, shadow) = state
            val plain = if (shadow) emptyList() else regular.filter { it.first == vertex && it.third <= maxLevel }.map { it.second to false }
            return plain + callbacks.filter { it.first == vertex && it.third <= maxLevel }.map { it.second to true }
        }
        fun distances(start: String, maxLevel: Int): Map<Pair<String, Boolean>, Int> {
            val found = mutableMapOf((start to false) to 0)
            val queue = ArrayDeque(listOf(start to false))
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                next(current, maxLevel).forEach { if (it !in found) { found[it] = found.getValue(current) + 1; queue += it } }
            }
            return found
        }
        val roots = result.roots.map { it.node?.id?.value }
        val levels = (0..2).map { level -> roots.map { root -> root?.let { distances(it, level) }.orEmpty() } }
        val full = levels[2]
        fun reaches(map: Map<Pair<String, Boolean>, Int>, vertex: String) = (vertex to false) in map || (vertex to true) in map
        fun distance(map: Map<Pair<String, Boolean>, Int>, vertex: String) = listOfNotNull(map[vertex to false], map[vertex to true]).min()
        val expected = graph.nodeIds.map { it.value }.mapNotNull { vertex ->
            val reaching = roots.indices.filter { roots[it] != null && roots[it] != vertex && reaches(full[it], vertex) }
            if (reaching.isEmpty()) return@mapNotNull null
            val depth = reaching.minOf { distance(full[it], vertex) }
            val evidence = reaching.maxOf { root -> (0..2).first { reaches(levels[it][root], vertex) } }
            vertex to Triple(reaching, depth, evidence)
        }.toMap()
        val listed = expected.filterValues { it.second <= maxDepth }
        assertEquals(listed.keys, result.reached.map { it.node.id.value }.toSet(), context)
        assertEquals(expected.size > listed.size, result.depthTruncated, context)
        for (row in result.reached) {
            val vertex = row.node.id.value
            val (reaching, depth, evidence) = listed.getValue(vertex)
            assertEquals(reaching, row.roots, "$context roots $vertex")
            assertEquals(depth, row.depth, "$context depth $vertex")
            assertEquals(TraversalEvidence.entries[evidence], row.evidence, "$context evidence $vertex")
            assertTrue(row.relationships.isNotEmpty(), "$context relationships $vertex")
            if (row.via in roots && depth == 1) continue
            val witnessed = reaching.any { root ->
                listOf(false, true).any { from -> full[root][row.via to from] == depth - 1 &&
                    next(row.via to from, 2).any { it.first == vertex && full[root][it] == depth } }
            }
            assertTrue(witnessed, "$context via ${row.via} -> $vertex")
        }
    }

    private fun assertValidWitness(row: TraversalReached, roots: List<String?>, full: List<Map<String, Int>>,
        edges: Set<Pair<String, String>>, context: String) {
        val vertex = row.node.id.value
        assertTrue(row.via to vertex in edges, "$context via edge ${row.node.id}")
        assertTrue(row.relationships.isNotEmpty(), "$context relationships ${row.node.id}")
        if (row.via in roots) { assertEquals(1, row.depth, context); return }
        val viaDistances = roots.indices.filter { roots[it] != null && roots[it] != vertex }.mapNotNull { full[it][row.via] }
        assertTrue(viaDistances.any { it + 1 == row.depth }, "$context via distance ${row.node.id}")
    }

    private fun level(tier: TraversalEdgeTier): Int = when (tier) {
        TraversalEdgeTier.DIRECT -> 0
        TraversalEdgeTier.BOUND -> 1
        TraversalEdgeTier.CANDIDATE, TraversalEdgeTier.LAMBDA -> 2
    }

    /** 무작위 그래프다. 직접 호출·런타임 모델(bound)·외부 dispatch 후보(candidate)·함수 타입 후보(lambda)를 섞는다. */
    private fun randomGraph(random: Random): CodeGraph {
        val names = List(random.nextInt(4, 30)) { "N$it" }
        val edges = mutableListOf<GraphEdge>()
        val calls = mutableListOf<ExternalCall>()
        repeat(random.nextInt(0, names.size * 3)) {
            val source = NodeId(id(names.random(random)))
            val target = NodeId(id(names.random(random)))
            when (random.nextInt(4)) {
                0, 1 -> edges += GraphEdge(source, target, EdgeKind.CALL)
                2 -> edges += GraphEdge(source, target, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL)
                else -> {
                    val owner = if (random.nextBoolean()) "ext/Service" else "kotlin/jvm/functions/Function0"
                    edges += GraphEdge(source, target, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)
                    calls += ExternalCall(source, owner, "m", "()V", InvocationKind.INTERFACE, resolvedTargets = listOf(target),
                        resolution = CallResolution.PROJECT_CANDIDATES)
                }
            }
        }
        return CodeGraph(names.map { method(it) }, edges, calls)
    }

    private fun graphOf(names: List<String>, calls: List<Pair<String, String>>): CodeGraph =
        CodeGraph(names.map { method(it) }, calls.map { (source, target) -> GraphEdge(NodeId(id(source)), NodeId(id(target)), EdgeKind.CALL) })

    /** 화면 함수 render가 람다를 만들고, 공통 button 함수가 Function0.invoke로 모든 람다에 dispatch하는 모양이다. */
    private fun lambdaGraph(): CodeGraph {
        val render = NodeId("method:app/Screen#render()V")
        val open = NodeId("method:app/Nav#open()V")
        val button = NodeId("method:app/Ui#button(Lkotlin/jvm/functions/Function0;)V")
        val local = NodeId("class:app/Screen\$render\$1")
        val body = NodeId("method:app/Screen\$render\$1#invoke()V")
        val other = NodeId("method:app/Other\$show\$1#invoke()V")
        val nodes = listOf(
            GraphNode(render, "render", NodeKind.METHOD), GraphNode(open, "open", NodeKind.METHOD),
            GraphNode(button, "button", NodeKind.METHOD), GraphNode(local, "Screen\$render\$1", NodeKind.CLASS),
            GraphNode(body, "invoke", NodeKind.METHOD), GraphNode(other, "invoke", NodeKind.METHOD),
            GraphNode(NodeId("class:app/Other\$show\$1"), "Other\$show\$1", NodeKind.CLASS),
            GraphNode(NodeId("method:app/Other#show()V"), "show", NodeKind.METHOD),
        )
        val edges = listOf(
            GraphEdge(local, body, EdgeKind.MEMBER), GraphEdge(open, render, EdgeKind.CALL), GraphEdge(render, button, EdgeKind.CALL),
            GraphEdge(button, body, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL),
            GraphEdge(button, other, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL),
            GraphEdge(NodeId("class:app/Other\$show\$1"), other, EdgeKind.MEMBER),
        )
        val invoke = ExternalCall(button, "kotlin/jvm/functions/Function0", "invoke", "()Ljava/lang/Object;", InvocationKind.INTERFACE,
            resolvedTargets = listOf(body, other), resolution = CallResolution.PROJECT_CANDIDATES)
        val enclosures = listOf(LexicalEnclosure(local, render), LexicalEnclosure(NodeId("class:app/Other\$show\$1"), NodeId("method:app/Other#show()V")))
        return CodeGraph(nodes, edges, listOf(invoke), enclosures = enclosures)
    }

    /** 구현이 하나인 인터페이스, 둘인 인터페이스, 람다 후보, Object 후보, 상속 멤버 호출을 담는다. */
    private fun dispatchGraph(): CodeGraph {
        fun type(name: String, kind: NodeKind, vararg supertypes: String, abstract: Boolean = false) = GraphNode(NodeId("class:app/$name"), name, kind,
            jvmSignature = "app/$name", supertypes = supertypes.map { "app/$it" }.toSet(), jvmModifiers = if (abstract) setOf(JvmModifier.ABSTRACT) else emptySet())
        fun member(owner: String, signature: String, abstract: Boolean = false) = GraphNode(NodeId("method:app/$owner#$signature"), signature.substringBefore('('),
            NodeKind.METHOD, jvmSignature = "app/$owner#$signature", jvmModifiers = if (abstract) setOf(JvmModifier.ABSTRACT) else emptySet())
        val nodes = listOf(
            type("Single", NodeKind.INTERFACE, abstract = true), type("SingleImpl", NodeKind.CLASS, "Single"),
            type("Multi", NodeKind.INTERFACE, abstract = true), type("MultiA", NodeKind.CLASS, "Multi"), type("MultiB", NodeKind.CLASS, "Multi"),
            type("Caller", NodeKind.CLASS), type("Caller\$run\$1", NodeKind.CLASS),
            member("Single", "load()V", abstract = true), member("SingleImpl", "load()V"), member("SingleImpl", "close()V"),
            member("Multi", "load()V", abstract = true), member("MultiA", "load()V"), member("MultiB", "load()V"),
            member("MultiA", "toString()Ljava/lang/String;"), member("Caller", "run()V"), member("Caller\$run\$1", "invoke()V"),
        )
        val caller = NodeId("method:app/Caller#run()V")
        fun dispatch(target: String) = GraphEdge(caller, NodeId(target), EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)
        fun call(owner: String, name: String, descriptor: String, target: String) = ExternalCall(caller, owner, name, descriptor,
            InvocationKind.VIRTUAL, resolvedTargets = listOf(NodeId(target)), resolution = CallResolution.PROJECT_CANDIDATES)
        val edges = listOf(
            GraphEdge(NodeId("method:app/Single#load()V"), NodeId("method:app/SingleImpl#load()V"), EdgeKind.OVERRIDE),
            GraphEdge(NodeId("method:app/Multi#load()V"), NodeId("method:app/MultiA#load()V"), EdgeKind.OVERRIDE),
            GraphEdge(NodeId("method:app/Multi#load()V"), NodeId("method:app/MultiB#load()V"), EdgeKind.OVERRIDE),
            dispatch("method:app/Caller\$run\$1#invoke()V"), dispatch("method:app/MultiA#toString()Ljava/lang/String;"),
            dispatch("method:app/SingleImpl#close()V"),
        )
        val calls = listOf(
            call("kotlin/jvm/functions/Function0", "invoke", "()V", "method:app/Caller\$run\$1#invoke()V"),
            call("java/lang/Object", "toString", "()Ljava/lang/String;", "method:app/MultiA#toString()Ljava/lang/String;"),
            // 인터페이스 Single에는 close가 선언되지 않았지만 유일한 구체 구현이 해석한다(상속 멤버 호출).
            call("app/Single", "close", "()V", "method:app/SingleImpl#close()V"),
        )
        return CodeGraph(nodes, edges, calls, enclosures = listOf(LexicalEnclosure(NodeId("class:app/Caller\$run\$1"), caller)))
    }

    /**
     * `--class-hops` 상태 공간의 전수 BFS다. 상태는 (정점, 방식)이며 방식은 본(PLAIN)·콜백 그림자(CALLBACK)·class 진입(ENTERED)이다.
     * 진입 간선은 ENTERED로 가고, ENTERED는 막힌 간선을 따르지 않으며 상속 간선으로 ENTERED를 잇는다. 콜백 간선은 역방향에서만
     * 모든 방식에서 CALLBACK으로 가고, CALLBACK에서는 콜백 간선만 나간다.
     */
    private fun assertMatchesClassHopOracle(all: List<TraversalEdge>, graph: CodeGraph, result: LanguageTraversalResult,
        classHops: TraversalClassHops, maxDepth: Int, context: String) {
        val reverse = result.direction == TraversalDirection.DEPENDENTS
        val allowed = all.filter { it.tier in result.dispatch.tiers && it.source != it.target && (!it.terminal || reverse) }
        data class Hop(val from: String, val to: String, val edge: TraversalEdge)
        val hops = allowed.map { if (reverse) Hop(it.target.value, it.source.value, it) else Hop(it.source.value, it.target.value, it) }
        val entry = if (reverse) TraversalClassRole.OWNER_CALLBACK else TraversalClassRole.TYPE_REFERENCE
        val narrow = classHops == TraversalClassHops.MEMBER_ONLY
        fun next(state: Pair<String, Int>, maxLevel: Int): List<Pair<String, Int>> = hops.filter { it.from == state.first && level(it.edge.tier) <= maxLevel }
            .mapNotNull { hop ->
                val role = hop.edge.classRole?.takeIf { narrow }
                when {
                    hop.edge.terminal -> hop.to to CALLBACK
                    state.second == CALLBACK -> null
                    role == entry -> hop.to to ENTERED
                    role != null && state.second == ENTERED -> null
                    state.second == ENTERED && hop.edge.relationship == "inheritance" -> hop.to to ENTERED
                    else -> hop.to to PLAIN
                }
            }
        fun distances(start: String, maxLevel: Int): Map<Pair<String, Int>, Int> {
            val found = mutableMapOf((start to PLAIN) to 0)
            val queue = ArrayDeque(listOf(start to PLAIN))
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                next(current, maxLevel).forEach { if (it !in found) { found[it] = found.getValue(current) + 1; queue += it } }
            }
            return found
        }
        val roots = result.roots.map { it.node?.id?.value }
        val levels = (0..2).map { level -> roots.map { root -> root?.let { distances(it, level) }.orEmpty() } }
        val full = levels[2]
        val modes = listOf(PLAIN, CALLBACK, ENTERED)
        fun reaches(map: Map<Pair<String, Int>, Int>, vertex: String) = modes.any { (vertex to it) in map }
        fun distance(map: Map<Pair<String, Int>, Int>, vertex: String) = modes.mapNotNull { map[vertex to it] }.min()
        val expected = graph.nodeIds.map { it.value }.mapNotNull { vertex ->
            val reaching = roots.indices.filter { roots[it] != null && roots[it] != vertex && reaches(full[it], vertex) }
            if (reaching.isEmpty()) return@mapNotNull null
            val depth = reaching.minOf { distance(full[it], vertex) }
            val evidence = reaching.maxOf { root -> (0..2).first { reaches(levels[it][root], vertex) } }
            vertex to Triple(reaching, depth, evidence)
        }.toMap()
        val listed = expected.filterValues { it.second <= maxDepth }
        assertEquals(listed.keys, result.reached.map { it.node.id.value }.toSet(), context)
        assertEquals(expected.size > listed.size, result.depthTruncated, context)
        for (row in result.reached) {
            val vertex = row.node.id.value
            val (reaching, depth, evidence) = listed.getValue(vertex)
            assertEquals(reaching, row.roots, "$context roots $vertex")
            assertEquals(depth, row.depth, "$context depth $vertex")
            assertEquals(TraversalEvidence.entries[evidence], row.evidence, "$context evidence $vertex")
            assertTrue(row.relationships.isNotEmpty(), "$context relationships $vertex")
            if (row.via in roots && depth == 1) continue
            val witnessed = reaching.any { root ->
                modes.any { from -> full[root][row.via to from] == depth - 1 && next(row.via to from, 2).any { it.first == vertex && full[root][it] == depth } }
            }
            assertTrue(witnessed, "$context via ${row.via} -> $vertex")
        }
        val line = result.limitations.singleOrNull { it.startsWith("class-hops-narrowed:") }
        if (!narrow) { assertEquals(null, line, context); return }
        // 한계 수: 좁힌 상태로만 닿은 class에서 막힌 간선 중 같은 상태에서 다른 간선으로 잇지 않는 (class, 선언) 쌍이다.
        val blocked = graph.nodeIds.map { it.value }.associateWith { vertex ->
            val linked = next(vertex to ENTERED, 2).map { it.first }.toSet()
            hops.filter { it.from == vertex && !it.edge.terminal && it.edge.classRole != null && it.edge.classRole != entry }
                .map { it.to }.toSet() - linked
        }.filter { (vertex, targets) -> targets.isNotEmpty() && full.any { (vertex to ENTERED) in it && (vertex to PLAIN) !in it } }
        val counts = Regex("class-hops-narrowed: (\\d+) .* from (\\d+) class vertex").find(line!!)!!.destructured.toList().map(String::toInt)
        assertEquals(listOf(blocked.values.sumOf { it.size }, blocked.size), counts, "$context narrowed count")
    }

    /**
     * class와 멤버가 섞인 무작위 그래프다. 호출·이름만 적은 참조·콜백 모델·상속·바깥 class 참조·런타임 조회·생성자 호출·
     * dispatch 후보·어휘적 소속을 섞는다.
     */
    private fun classGraph(random: Random): CodeGraph {
        val classes = List(random.nextInt(1, 6)) { "app/K$it" }
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
            when (random.nextInt(9)) {
                0 -> edges += GraphEdge(member(), member(), EdgeKind.CALL)
                1 -> edges += GraphEdge(member(), NodeId("class:${type()}"), EdgeKind.REFERENCE)
                2 -> type().let { owner -> members.filter { it.value.startsWith("method:$owner#") }.random(random)
                    .let { edges += GraphEdge(NodeId("class:$owner"), it, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL) } }
                3 -> edges += GraphEdge(NodeId("class:${type()}"), NodeId("class:${type()}"), EdgeKind.INHERITANCE)
                4 -> edges += GraphEdge(NodeId("class:${type()}"), NodeId("class:${type()}"), EdgeKind.REFERENCE)
                5 -> edges += GraphEdge(member(), NodeId("class:${type()}"), EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL)
                6 -> type().let { owner -> member().let { source ->
                    edges += GraphEdge(source, NodeId("method:$owner#<init>()V"), EdgeKind.CALL)
                    edges += GraphEdge(source, NodeId("class:$owner"), EdgeKind.REFERENCE)
                } }
                7 -> {
                    val (source, target) = member() to member()
                    edges += GraphEdge(source, target, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL)
                    calls += ExternalCall(source, "ext/Service", "m", "()V", InvocationKind.INTERFACE, resolvedTargets = listOf(target),
                        resolution = CallResolution.PROJECT_CANDIDATES)
                }
                else -> edges += GraphEdge(member(), member(), EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL)
            }
        }
        edges += nodes.filter { it.kind != NodeKind.CLASS }.map { GraphEdge(NodeId("class:" + it.id.value.substringAfter(':').substringBefore('#')), it.id, EdgeKind.MEMBER) }
        val enclosures = if (random.nextInt(3) == 0) listOf(LexicalEnclosure(NodeId("class:${type()}"), member())) else emptyList()
        return CodeGraph(nodes, edges, calls, enclosures = enclosures)
    }

    /**
     * ViewModel 모양의 합성 그래프다. Vm.load가 root Http.get을 부르고, 콜백 모델이 Vm → 멤버(load·other·생성자)를 잇는다.
     * Screen.render는 load를 부르며 Vm을 시그니처에 적고, Format.label·Holder.vm은 Vm을 이름만 적는다. Factory.create는
     * Vm을 만들고, SpecialVm은 Vm을 상속하며, Reflect.lookup은 런타임 모델로 Vm을 찾는다. Web$1은 Web.show 안의 익명
     * 콜백 class다.
     */
    private fun viewModelGraph(): CodeGraph {
        fun method(id: String) = GraphNode(NodeId(id), id.substringAfter('#').substringBefore('('), if ("<init>" in id) NodeKind.CONSTRUCTOR else NodeKind.METHOD)
        fun type(name: String) = GraphNode(NodeId("class:app/$name"), name, NodeKind.CLASS)
        val nodes = listOf(type("Vm"), type("SpecialVm"), type("Web\$1"), method("method:app/Vm#load()V"), method("method:app/Vm#other()V"),
            method("method:app/Vm#<init>()V"), method("method:app/Http#get()V"), method("method:app/Screen#render(Lapp/Vm;)V"),
            method("method:app/Format#label(Lapp/Vm;)V"), method("method:app/Printer#print()V"), method("method:app/Holder#read()V"),
            GraphNode(NodeId("field:app/Holder#vm:Lapp/Vm;"), "vm", NodeKind.FIELD), method("method:app/Factory#create()Lapp/Vm;"),
            method("method:app/SpecialUser#show(Lapp/SpecialVm;)V"), method("method:app/Reflect#lookup()V"), method("method:app/Other#run()V"),
            method("method:app/Web\$1#onPage()V"), method("method:app/Web#show()V"), method("method:app/Web#open()V"))
        fun edge(source: String, target: String, kind: EdgeKind, origin: EdgeOrigin = EdgeOrigin.BYTECODE) = GraphEdge(NodeId(source), NodeId(target), kind, origin = origin)
        val edges = listOf(
            edge("method:app/Vm#load()V", "method:app/Http#get()V", EdgeKind.CALL),
            edge("class:app/Vm", "method:app/Vm#load()V", EdgeKind.REFERENCE, EdgeOrigin.RUNTIME_MODEL),
            edge("class:app/Vm", "method:app/Vm#other()V", EdgeKind.REFERENCE, EdgeOrigin.RUNTIME_MODEL),
            edge("class:app/Vm", "method:app/Vm#<init>()V", EdgeKind.REFERENCE, EdgeOrigin.RUNTIME_MODEL),
            edge("class:app/Vm", "method:app/Other#run()V", EdgeKind.REFERENCE, EdgeOrigin.RUNTIME_MODEL),
            edge("method:app/Screen#render(Lapp/Vm;)V", "method:app/Vm#load()V", EdgeKind.CALL),
            edge("method:app/Screen#render(Lapp/Vm;)V", "class:app/Vm", EdgeKind.REFERENCE),
            edge("method:app/Format#label(Lapp/Vm;)V", "class:app/Vm", EdgeKind.REFERENCE),
            edge("method:app/Printer#print()V", "method:app/Format#label(Lapp/Vm;)V", EdgeKind.CALL),
            edge("field:app/Holder#vm:Lapp/Vm;", "class:app/Vm", EdgeKind.REFERENCE),
            edge("method:app/Holder#read()V", "field:app/Holder#vm:Lapp/Vm;", EdgeKind.FIELD_ACCESS),
            edge("method:app/Factory#create()Lapp/Vm;", "method:app/Vm#<init>()V", EdgeKind.CALL),
            edge("method:app/Factory#create()Lapp/Vm;", "class:app/Vm", EdgeKind.REFERENCE),
            edge("class:app/SpecialVm", "class:app/Vm", EdgeKind.INHERITANCE),
            edge("method:app/SpecialUser#show(Lapp/SpecialVm;)V", "class:app/SpecialVm", EdgeKind.REFERENCE),
            edge("method:app/Reflect#lookup()V", "class:app/Vm", EdgeKind.REFERENCE, EdgeOrigin.RUNTIME_MODEL),
            edge("class:app/Web\$1", "method:app/Web\$1#onPage()V", EdgeKind.MEMBER),
            edge("class:app/Web\$1", "method:app/Web\$1#onPage()V", EdgeKind.REFERENCE, EdgeOrigin.RUNTIME_MODEL),
            edge("method:app/Web\$1#onPage()V", "method:app/Vm#load()V", EdgeKind.CALL),
            edge("method:app/Web#open()V", "method:app/Web#show()V", EdgeKind.CALL),
        )
        return CodeGraph(nodes, edges, enclosures = listOf(LexicalEnclosure(NodeId("class:app/Web\$1"), NodeId("method:app/Web#show()V"))))
    }

    private fun method(name: String) = GraphNode(NodeId(id(name)), name, NodeKind.METHOD)
    private fun id(name: String) = "method:app/$name#run()V"

    private companion object {
        const val PLAIN = 0
        const val CALLBACK = 1
        const val ENTERED = 2
    }
}
