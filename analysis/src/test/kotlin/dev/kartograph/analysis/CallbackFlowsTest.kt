package dev.kartograph.analysis

import dev.kartograph.core.CallbackArgument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.LambdaEscape
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.ParameterUseKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 람다 전달 사실과 파라미터 쓰임을 콜백 간선으로 잇는 규칙(등급·빠져나감·깊이·dispatch)을 합성 그래프로 확인한다. */
class CallbackFlowsTest {
    private val body = NodeId("method:app/Screen#render\$lambda\$0()V")
    private val screen = NodeId("method:app/Screen#render()V")
    private val invoke = NodeId("method:kotlin/jvm/functions/Function0#invoke()Ljava/lang/Object;")
    private val sam = "invoke()Ljava/lang/Object;"

    private fun fn(name: String) = NodeId("method:app/Ui#$name(Lkotlin/jvm/functions/Function0;)V")

    private fun declared(method: NodeId, parameter: Int = 0) = ParameterUse(method, parameter, ParameterUseKind.DECLARED)
    private fun invokes(method: NodeId, parameter: Int = 0) = ParameterUse(method, parameter, ParameterUseKind.RECEIVER, invoke, InvocationKind.INTERFACE)
    private fun forwards(method: NodeId, target: NodeId, kind: InvocationKind = InvocationKind.STATIC, parameter: Int = 0, position: Int = 0) =
        ParameterUse(method, parameter, ParameterUseKind.ARGUMENT, target, kind, position)

    /** Screen.render가 람다 body를 [callee]의 인자 0으로 넘기는 그래프다. */
    private fun graph(callee: NodeId, uses: List<ParameterUse>, extraNodes: List<GraphNode> = emptyList(), edges: List<GraphEdge> = emptyList(),
        kind: InvocationKind = InvocationKind.STATIC): CodeGraph {
        val methods = (listOf(body, screen, callee) + uses.map { it.method } + uses.mapNotNull { it.target }.filter { it.value.startsWith("method:app/") })
            .distinct().map { GraphNode(it, it.value.substringAfter('#').substringBefore('('), NodeKind.METHOD) }
        return CodeGraph(methods + extraNodes, edges, callbackArguments = listOf(CallbackArgument(screen, callee, kind, 0, body, sam)),
            parameterUses = uses)
    }

    private fun flows(graph: CodeGraph): CallbackFlowResult = CallbackFlows(graph, DispatchClassifier(graph, true)).analyze()

    private fun tiers(graph: CodeGraph): Map<String, TraversalEdgeTier> =
        flows(graph).edges.associate { it.source.value.substringAfter('#').substringBefore('(') to it.tier }

    @Test
    fun `direct invocation and forwarding chains are bound`() {
        val outer = fn("outer"); val inner = fn("inner")
        val graph = graph(outer, listOf(declared(outer), forwards(outer, inner), declared(inner), invokes(inner),
            ParameterUse(inner, 0, ParameterUseKind.ARGUMENT, NodeId("method:kotlin/jvm/internal/Intrinsics#checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V"),
                InvocationKind.STATIC, 0)))
        assertEquals(mapOf("outer" to TraversalEdgeTier.BOUND, "inner" to TraversalEdgeTier.BOUND), tiers(graph))
        val result = flows(graph)
        assertTrue(result.edges.all { it.terminal && it.relationship == "callback" && it.target == body })
        assertEquals(CallbackFlowSummary(bound = 1), result.summary)
    }

    @Test
    fun `escapes lower an invoked flow to candidate`() {
        val button = fn("button")
        val escapes = listOf(
            ParameterUse(button, 0, ParameterUseKind.FIELD, NodeId("field:app/Ui#stored:Lkotlin/jvm/functions/Function0;")),
            ParameterUse(button, 0, ParameterUseKind.RETURN), ParameterUse(button, 0, ParameterUseKind.ARRAY), ParameterUse(button, 0, ParameterUseKind.OTHER),
            // 객체를 돌려주는 라이브러리 호출은 같은 값을 돌려줄 수 있어 검사 전용으로 보지 않는다.
            forwards(button, NodeId("method:java/util/Objects#requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;")),
            ParameterUse(button, 0, ParameterUseKind.RECEIVER, NodeId("method:app/Listener#fire()V"), InvocationKind.INTERFACE),
        )
        escapes.forEach { escape ->
            assertEquals(mapOf("button" to TraversalEdgeTier.CANDIDATE), tiers(graph(button, listOf(declared(button), invokes(button), escape))), escape.toString())
        }
        // Object 메서드는 람다 본문을 실행하지 않는다.
        val hash = ParameterUse(button, 0, ParameterUseKind.RECEIVER, NodeId("method:java/lang/Object#hashCode()I"), InvocationKind.VIRTUAL)
        assertEquals(mapOf("button" to TraversalEdgeTier.BOUND), tiers(graph(button, listOf(declared(button), invokes(button), hash))))
    }

    @Test
    fun `flows that escape before any invocation are unresolved`() {
        val keep = fn("keep"); val unknown = fn("unknown")
        val field = graph(keep, listOf(declared(keep), ParameterUse(keep, 0, ParameterUseKind.FIELD, NodeId("field:app/Ui#stored:Lkotlin/jvm/functions/Function0;"))))
        assertEquals(CallbackFlowSummary(unresolved = 1, unresolvedReasons = mapOf("field" to 1)), flows(field).summary)
        // 쓰임 기록(DECLARED)이 없는 파라미터는 "쓰이지 않음"이 아니라 "모름"이다.
        val unrecorded = graph(keep, listOf(declared(keep), forwards(keep, unknown)))
        assertEquals(CallbackFlowSummary(unresolved = 1, unresolvedReasons = mapOf("unanalyzed" to 1)), flows(unrecorded).summary)
        // 쓰이지 않는 콜백은 잇지도 세지도 않는다.
        assertEquals(CallbackFlowSummary(), flows(graph(keep, listOf(declared(keep)))).summary)
    }

    @Test
    fun `forwarding beyond the depth limit is not followed`() {
        val chain = (0..CallbackFlows.MAX_FORWARD_DEPTH + 1).map { fn("step$it") }
        val uses = chain.zipWithNext { from, to -> listOf(declared(from), forwards(from, to)) }.flatten() + declared(chain.last()) + invokes(chain.last())
        val result = flows(graph(chain.first(), uses))
        assertTrue(result.edges.isEmpty())
        assertEquals(mapOf("depth" to 1), result.summary.unresolvedReasons)
        val short = chain.take(4)
        val within = short.zipWithNext { from, to -> listOf(declared(from), forwards(from, to)) }.flatten() + declared(short.last()) + invokes(short.last())
        assertEquals(4, flows(graph(short.first(), within)).edges.size)
    }

    @Test
    fun `virtual callees resolve only to a single concrete project implementation`() {
        fun type(name: String, kind: NodeKind, vararg supers: String) = GraphNode(NodeId("class:app/$name"), name, kind,
            jvmSignature = "app/$name", supertypes = supers.map { "app/$it" }.toSet(), jvmModifiers = if (kind == NodeKind.INTERFACE) setOf(JvmModifier.ABSTRACT) else emptySet())
        fun impl(owner: String) = NodeId("method:app/$owner#show(Lkotlin/jvm/functions/Function0;)V")
        val declaredIn = impl("Dialog")
        val single = listOf(type("Dialog", NodeKind.INTERFACE), type("DialogImpl", NodeKind.CLASS, "Dialog"),
            GraphNode(declaredIn, "show", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.ABSTRACT)))
        val uses = listOf(declared(impl("DialogImpl")), invokes(impl("DialogImpl")))
        assertEquals(mapOf("show" to TraversalEdgeTier.BOUND), tiers(graph(declaredIn, uses, single, kind = InvocationKind.INTERFACE)))
        val multi = single + type("OtherImpl", NodeKind.CLASS, "Dialog") + GraphNode(impl("OtherImpl"), "show", NodeKind.METHOD)
        val ambiguous = flows(graph(declaredIn, uses, multi, kind = InvocationKind.INTERFACE))
        assertTrue(ambiguous.edges.isEmpty())
        assertEquals(mapOf("dispatch" to 1), ambiguous.summary.unresolvedReasons)
    }

    @Test
    fun `library hand-off links every functional body of a local class with candidate evidence`() {
        val button = fn("button")
        val local = NodeId("class:app/Screen\$render\$1")
        val invokeBody = NodeId("method:app/Screen\$render\$1#invoke()V")
        val bridge = NodeId("method:app/Screen\$render\$1#invoke()Ljava/lang/Object;")
        val constructor = NodeId("method:app/Screen\$render\$1#<init>()V")
        val nodes = listOf(GraphNode(local, "Screen\$render\$1", NodeKind.CLASS, jvmSignature = "app/Screen\$render\$1"),
            GraphNode(invokeBody, "invoke", NodeKind.METHOD), GraphNode(bridge, "invoke", NodeKind.METHOD),
            GraphNode(constructor, "<init>", NodeKind.CONSTRUCTOR), GraphNode(screen, "render", NodeKind.METHOD), GraphNode(button, "button", NodeKind.METHOD))
        val edges = listOf(invokeBody, bridge, constructor).map { GraphEdge(local, it, EdgeKind.MEMBER) }
        val uses = listOf(declared(button), forwards(button, NodeId("method:ext/Material#Button(Lkotlin/jvm/functions/Function0;)V")))
        val graph = CodeGraph(nodes, edges, callbackArguments = listOf(CallbackArgument(screen, button, InvocationKind.STATIC, 0, local)), parameterUses = uses)
        val result = flows(graph)
        assertEquals(setOf(invokeBody, bridge), result.edges.map { it.target }.toSet())
        assertTrue(result.edges.all { it.tier == TraversalEdgeTier.CANDIDATE && it.source == button })
        // 수신 호출은 이름·descriptor가 맞는 본문(bridge)으로 해석한다.
        val invoked = CodeGraph(nodes, edges, callbackArguments = listOf(CallbackArgument(screen, button, InvocationKind.STATIC, 0, local)),
            parameterUses = listOf(declared(button), invokes(button)))
        assertEquals(listOf(bridge), flows(invoked).edges.map { it.target })
    }

    @Test
    fun `captures that only feed the same function back do not escape`() {
        val section = fn("section")
        val restart = NodeId("method:app/Ui#section\$lambda\$1(Lkotlin/jvm/functions/Function0;)V")
        val later = NodeId("method:app/Ui#section\$lambda\$2(Lkotlin/jvm/functions/Function0;)V")
        val harmless = listOf(declared(section), invokes(section),
            ParameterUse(section, 0, ParameterUseKind.CAPTURE, restart, InvocationKind.BOOTSTRAP, 0), declared(restart), forwards(restart, section))
        assertEquals(TraversalEdgeTier.BOUND, tiers(graph(section, harmless)).getValue("section"))
        val adding = listOf(declared(section), ParameterUse(section, 0, ParameterUseKind.CAPTURE, later, InvocationKind.BOOTSTRAP, 0),
            declared(later), invokes(later))
        assertEquals(mapOf("section" to TraversalEdgeTier.CANDIDATE, "section\$lambda\$2" to TraversalEdgeTier.CANDIDATE), tiers(graph(section, adding)))
    }

    @Test
    fun `a lambda that also escapes at its creation site is never bound`() {
        // 리뷰 지적 재현(D1): F가 같은 람다를 필드에 저장하거나 라이브러리에 넘기면 G 밖에도 실행자가 있다.
        val button = fn("button")
        val uses = listOf(declared(button), invokes(button))
        val clean = graph(button, uses)
        assertEquals(TraversalEdgeTier.BOUND, flows(clean).edges.single().tier)
        ParameterUseKind.entries.filter { it in setOf(ParameterUseKind.FIELD, ParameterUseKind.RETURN, ParameterUseKind.RECEIVER) }.forEach { kind ->
            val escaped = CodeGraph(clean.nodes.values, clean.edges, callbackArguments = clean.callbackArguments, parameterUses = uses,
                lambdaEscapes = listOf(LambdaEscape(screen, body, kind)))
            assertEquals(TraversalEdgeTier.CANDIDATE, flows(escaped).edges.single().tier, kind.name)
            assertEquals(CallbackFlowSummary(candidate = 1), flows(escaped).summary)
        }
        val library = NodeId("method:ext/Lib#keep(Lkotlin/jvm/functions/Function0;)V")
        val handedOff = CodeGraph(clean.nodes.values, clean.edges, parameterUses = uses, callbackArguments = clean.callbackArguments +
            CallbackArgument(screen, library, InvocationKind.STATIC, 0, body, sam))
        assertEquals(TraversalEdgeTier.CANDIDATE, flows(handedOff).edges.single().tier)
        // 비교만 하는 라이브러리 호출은 실행자를 늘리지 않는다.
        val inspected = CodeGraph(clean.nodes.values, clean.edges, parameterUses = uses, callbackArguments = clean.callbackArguments +
            CallbackArgument(screen, NodeId("method:kotlin/jvm/internal/Intrinsics#checkNotNull(Ljava/lang/Object;)V"), InvocationKind.STATIC, 0, body, sam))
        assertEquals(TraversalEdgeTier.BOUND, flows(inspected).edges.single().tier)
    }

    @Test
    fun `an edge reached by an escaping flow keeps candidate evidence`() {
        // 리뷰 지적 재현(D2): 같은 람다가 필드에 저장하는 both와 깨끗한 plain을 거쳐 같은 consumer에 닿는다.
        val both = fn("both"); val plain = fn("plain"); val consumer = fn("consumer")
        val uses = listOf(declared(both), forwards(both, consumer), ParameterUse(both, 0, ParameterUseKind.FIELD, NodeId("field:app/Ui#saved:Lkotlin/jvm/functions/Function0;")),
            declared(plain), forwards(plain, consumer), declared(consumer), invokes(consumer))
        val base = graph(both, uses)
        val graph = CodeGraph(base.nodes.values + GraphNode(plain, "plain", NodeKind.METHOD), base.edges, parameterUses = uses,
            callbackArguments = base.callbackArguments + CallbackArgument(screen, plain, InvocationKind.STATIC, 0, body, sam))
        val tiers = flows(graph).edges.associate { it.source to it.tier }
        assertEquals(TraversalEdgeTier.CANDIDATE, tiers.getValue(consumer))
        assertEquals(TraversalEdgeTier.CANDIDATE, tiers.getValue(plain))
    }

    @Test
    fun `parameter uses without the fields their kind needs are rejected`() {
        listOf(
            { ParameterUse(fn("a"), 0, ParameterUseKind.ARGUMENT, invoke) },
            { ParameterUse(fn("a"), 0, ParameterUseKind.RECEIVER) },
            { ParameterUse(fn("a"), 0, ParameterUseKind.CAPTURE, invoke) },
            { ParameterUse(fn("a"), 0, ParameterUseKind.FIELD) },
            { LambdaEscape(screen, body, ParameterUseKind.ARGUMENT) },
        ).forEach { factory -> kotlin.test.assertFailsWith<IllegalArgumentException> { factory() } }
    }

    @Test
    fun `callback edges are independent of fact order`() {
        val outer = fn("outer"); val inner = fn("inner"); val side = fn("side")
        val uses = listOf(declared(outer), forwards(outer, inner), forwards(outer, side), declared(inner), invokes(inner), declared(side),
            ParameterUse(side, 0, ParameterUseKind.FIELD, NodeId("field:app/Ui#stored:Lkotlin/jvm/functions/Function0;")))
        val expected = flows(graph(outer, uses))
        repeat(20) { seed -> assertEquals(expected, flows(graph(outer, uses.shuffled(Random(seed))))) }
        assertEquals(TraversalEdgeTier.CANDIDATE, expected.edges.first().tier)
    }
}
