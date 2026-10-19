package dev.kartograph.export

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.analysis.TraversalEdges
import dev.kartograph.analysis.isUnresolvedTarget
import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.CallbackArgument
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.LambdaEscape
import dev.kartograph.core.ParameterUseKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** language-traversal 문서 렌더링과 snapshot의 어휘적 소속 필드 호환성을 확인한다. */
class LanguageTraversalCodecTest {
    private val metadata = LanguageTraversalMetadata("2026-09-27T00:00:00.000Z", "/work/app", graphRevision = "ab")

    @Test
    fun `more than 64 reaching roots are capped with rootsTruncated`() {
        val sink = GraphNode(NodeId("method:p/Sink#run()V"), "run", NodeKind.METHOD)
        val sources = (0 until 70).map { GraphNode(NodeId("method:p/S$it#run()V"), "run", NodeKind.METHOD) }
        val graph = CodeGraph(sources + sink, sources.map { GraphEdge(it.id, sink.id, EdgeKind.CALL) })
        val result = LanguageTraversal.traverse(graph, sources.map { it.id.value }, TraversalDirection.DEPENDENCIES)
        val document = McpJsonCodec.parse(LanguageTraversalCodec.render(result, metadata)) as Map<*, *>
        assertEquals(true, document["rootsTruncated"])
        val row = (document["reached"] as List<*>).single() as Map<*, *>
        assertEquals((0L until 64L).toList(), row["roots"])
        assertEquals(false, document["truncated"])
        assertTrue((document["limitations"] as List<*>).any { (it as String).startsWith("roots-per-reached:") })
    }

    @Test
    fun `symbols omit unknown lines and bare file names`() {
        val located = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD, location = SourceLocation("src/p/A.kt", 3, 7))
        val bare = GraphNode(NodeId("method:p/B#run()V"), "run", NodeKind.METHOD, location = SourceLocation("B.kt"))
        val graph = CodeGraph(listOf(located, bare), listOf(GraphEdge(bare.id, located.id, EdgeKind.CALL)))
        val text = LanguageTraversalCodec.render(LanguageTraversal.traverse(graph, listOf(located.id.value), TraversalDirection.DEPENDENTS), metadata)
        assertTrue(text.contains("\"location\": {\"column\": 7, \"line\": 3, \"path\": \"src/p/A.kt\"}"))
        assertFalse(text.contains("B.kt"))
        assertTrue(text.contains("\"dispatch\": \"candidates\""))
        assertFalse(text.contains("unresolvedCalls"))
    }

    @Test
    fun `root lists come from string arrays or bridge facts`() {
        assertEquals(listOf("a", "b"), LanguageTraversalCodec.parseRoots("""["a", "b", "a"]"""))
        assertEquals(listOf("u1"), LanguageTraversalCodec.parseRoots(
            """{"format": "bridge-facts", "facts": [{"symbol": {"usr": "u1", "qualifiedName": "q"}}, {"symbol": {"qualifiedName": "q"}}]}"""))
    }

    @Test
    fun `snapshots round trip enclosures and mark legacy documents`() {
        val local = GraphNode(NodeId("class:p/A\$run\$1"), "A\$run\$1", NodeKind.CLASS)
        val owner = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD)
        val snapshot = QuerySnapshot(CodeGraph(listOf(local, owner), emptyList(), enclosures = listOf(LexicalEnclosure(local.id, owner.id))),
            emptyList(), emptyList())
        listOf(false, true).forEach { compact ->
            val parsed = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact))
            assertEquals(listOf(LexicalEnclosure(local.id, owner.id)), parsed.graph.enclosures)
            assertTrue(parsed.enclosuresCaptured)
        }
        val legacy = QuerySnapshotCodec.render(snapshot).replace(Regex(", \"enclosures\": \\[[^\\]]*\\]"), "")
        val parsed = QuerySnapshotCodec.parse(legacy)
        assertFalse(parsed.enclosuresCaptured)
        assertTrue(parsed.graph.enclosures.isEmpty())
    }

    @Test
    fun `snapshots round trip callback facts and mark legacy documents`() {
        val caller = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD)
        val button = GraphNode(NodeId("method:p/Ui#button(Lkotlin/jvm/functions/Function0;)V"), "button", NodeKind.METHOD)
        val body = GraphNode(NodeId("method:p/A#run\$lambda\$0()V"), "run\$lambda\$0", NodeKind.METHOD)
        val argument = CallbackArgument(caller.id, button.id, InvocationKind.STATIC, 0, body.id, "invoke()Ljava/lang/Object;")
        val uses = listOf(ParameterUse(button.id, 0, ParameterUseKind.DECLARED),
            ParameterUse(button.id, 0, ParameterUseKind.RECEIVER, NodeId("method:kotlin/jvm/functions/Function0#invoke()Ljava/lang/Object;"),
                InvocationKind.INTERFACE),
            ParameterUse(button.id, 0, ParameterUseKind.ARGUMENT, NodeId("method:p/Ui#keep(Lkotlin/jvm/functions/Function0;)V"), InvocationKind.STATIC, 0))
        val escape = LambdaEscape(caller.id, body.id, ParameterUseKind.FIELD)
        val snapshot = QuerySnapshot(CodeGraph(listOf(caller, button, body), emptyList(), callbackArguments = listOf(argument), parameterUses = uses,
            lambdaEscapes = listOf(escape)), emptyList(), emptyList())
        listOf(false, true).forEach { compact ->
            val parsed = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot, compact))
            assertEquals(listOf(argument), parsed.graph.callbackArguments)
            assertEquals(uses.sorted(), parsed.graph.parameterUses)
            assertEquals(listOf(escape), parsed.graph.lambdaEscapes)
            assertTrue(parsed.callbackFactsCaptured)
        }
        // 옛 snapshot은 두 키가 없다. 문서를 구조로 읽어 키만 지운다.
        val document = (McpJsonCodec.parse(QuerySnapshotCodec.render(snapshot)) as Map<*, *>).toMutableMap()
        document["graph"] = (document["graph"] as Map<*, *>).filterKeys { it !in setOf("callbackArguments", "parameterUses", "lambdaEscapes") }
        val legacy = jsonValue(document)
        val parsed = QuerySnapshotCodec.parse(legacy)
        assertFalse(parsed.callbackFactsCaptured)
        assertTrue(parsed.graph.callbackArguments.isEmpty() && parsed.graph.parameterUses.isEmpty())
        // 리뷰 지적 재현(D3): 종류에 필요한 필드가 빠진 쓰임은 순회 도중이 아니라 읽을 때 거부한다.
        val broken = QuerySnapshotCodec.render(snapshot).replace("\"position\": 0, ", "")
        kotlin.test.assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(broken) }
        val revision = LanguageTraversalCodec.graphRevision(snapshot.graph, true)
        assertNotEquals(revision, LanguageTraversalCodec.graphRevision(snapshot.graph, true, callbackFactsCaptured = false))
        assertNotEquals(revision, LanguageTraversalCodec.graphRevision(CodeGraph(listOf(caller, button, body), emptyList()), true))
    }

    /** graphRevision 대조용 작은 그래프다. B.run이 A.run을 부르고 람다 class L이 A.run 안에 있다. */
    private fun revisionGraph(
        location: SourceLocation? = null, origin: EdgeOrigin = EdgeOrigin.BYTECODE, weight: Int = 1,
        enclosing: String = "method:p/A#run()V", externalCalls: List<ExternalCall> = emptyList(),
    ): CodeGraph {
        val a = GraphNode(NodeId("method:p/A#run()V"), "run", NodeKind.METHOD, location = location)
        val b = GraphNode(NodeId("method:p/B#run()V"), "run", NodeKind.METHOD)
        val local = GraphNode(NodeId("class:p/A\$run\$1"), "A\$run\$1", NodeKind.CLASS)
        return CodeGraph(listOf(a, b, local), listOf(GraphEdge(b.id, a.id, EdgeKind.CALL, weight, origin)), externalCalls,
            enclosures = listOf(LexicalEnclosure(local.id, NodeId(enclosing))))
    }

    @Test
    fun `graph revision hashes graph content but not locations or weights`() {
        val base = LanguageTraversalCodec.graphRevision(revisionGraph(), enclosuresCaptured = true)
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(base), base)
        assertEquals(base, LanguageTraversalCodec.graphRevision(revisionGraph(location = SourceLocation("src/p/A.kt", 9, 2)), true))
        assertEquals(base, LanguageTraversalCodec.graphRevision(revisionGraph(weight = 4), true))
        val unresolved = ExternalCall(NodeId("method:p/A#run()V"), "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE,
            resolution = CallResolution.UNRESOLVED)
        listOf(
            LanguageTraversalCodec.graphRevision(revisionGraph(origin = EdgeOrigin.DISPATCH_MODEL), true),
            LanguageTraversalCodec.graphRevision(revisionGraph(enclosing = "method:p/B#run()V"), true),
            LanguageTraversalCodec.graphRevision(revisionGraph(), enclosuresCaptured = false),
            LanguageTraversalCodec.graphRevision(revisionGraph(externalCalls = listOf(unresolved)), true),
        ).forEach { assertNotEquals(base, it) }
    }

    @Test
    fun `graph revision does not depend on the order facts were captured in`() {
        val nodes = (0 until 6).map { GraphNode(NodeId("method:p/N$it#run()V"), "run", NodeKind.METHOD) }
        val locals = (0 until 2).map { GraphNode(NodeId("class:p/N$it\$1"), "N$it\$1", NodeKind.CLASS) }
        val edges = nodes.zipWithNext { a, b -> GraphEdge(a.id, b.id, EdgeKind.CALL) } + GraphEdge(nodes[5].id, nodes[0].id, EdgeKind.REFERENCE)
        val enclosures = locals.mapIndexed { index, local -> LexicalEnclosure(local.id, nodes[index].id) }
        val forward = CodeGraph(nodes + locals, edges, enclosures = enclosures)
        val shuffled = CodeGraph((nodes + locals).reversed(), edges.reversed(), enclosures = enclosures.reversed())
        assertEquals(LanguageTraversalCodec.graphRevision(forward, true), LanguageTraversalCodec.graphRevision(shuffled, true))
    }

    @Test
    fun `graph revision keeps the hash of the rendered content document`() {
        // isthmus가 비교하는 교환 값이다. 렌더링한 문서를 UTF-8로 해시하던 기존 값과 1비트도 달라지면 안 된다.
        val golden = goldenGraph()
        assertTrue(TraversalEdges.build(golden).any { it.terminal }, "the golden graph must carry a callback edge")
        val flags = listOf(true to true, true to false, false to true, false to false)
        assertEquals(GOLDEN_REVISIONS, flags.map { (enclosures, callbacks) -> LanguageTraversalCodec.graphRevision(golden, enclosures, callbacks) })
        repeat(200) { seed ->
            val graph = randomRevisionGraph(Random(70_000 + seed))
            flags.forEach { (enclosures, callbacks) ->
                val expected = renderedRevision(graph, enclosures, callbacks)
                val context = "seed $seed enclosures $enclosures callbacks $callbacks"
                assertEquals(expected, LanguageTraversalCodec.graphRevision(graph, enclosures, callbacks), context)
                // 순회가 조립한 간선을 넘겨도 같은 값이다.
                assertEquals(expected, LanguageTraversalCodec.graphRevision(graph, TraversalEdges.prepare(graph, enclosures, callbacks).edges,
                    enclosures, callbacks), context)
            }
        }
    }

    @Test
    fun `graph revision hashes without materializing the document`() {
        // 리뷰 지적 재현(perf#3): 해시 입력 JSON 전체를 String으로 만들고 UTF-8 byte 배열로 한 번 더 복사해 큰 snapshot에서
        // 문서 크기의 몇 배를 할당했고, 순회를 마친 뒤 이 단계에서만 OOM이 났다. 간선 조립 몫을 뺀 할당량이 문서보다 작아야 한다.
        val threads = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        assumeTrue(threads != null, "thread allocation accounting is unavailable on this JVM")
        // 긴 id로 문서 바이트가 정점·간선 행 객체보다 크게 한다.
        val ids = List(2_000) { NodeId("method:p/${"Segment".repeat(40)}$it#run()V") }
        val random = Random(7)
        val edges = ids.zipWithNext { a, b -> GraphEdge(a, b, EdgeKind.CALL) } + List(2_000) { GraphEdge(ids.random(random), ids.random(random), EdgeKind.REFERENCE) }
        val graph = CodeGraph(ids.map { GraphNode(it, "run", NodeKind.METHOD) }, edges)
        val document = jsonValue(renderedContent(graph, enclosuresCaptured = true, callbackFactsCaptured = true)).length.toLong()
        fun allocated(block: () -> Unit): Long {
            // 첫 실행의 클래스 적재 할당을 빼려고 한 번 먼저 돌린다.
            block()
            val before = threads!!.currentThreadAllocatedBytes
            block()
            return threads.currentThreadAllocatedBytes - before
        }
        val assembly = allocated { TraversalEdges.build(graph, enclosuresCaptured = true, callbackFactsCaptured = true) }
        val revision = allocated { LanguageTraversalCodec.graphRevision(graph, enclosuresCaptured = true, callbackFactsCaptured = true) }
        assertTrue(revision - assembly < document,
            "graph revision allocated ${revision - assembly} bytes beyond edge assembly for a $document-byte content document")
    }

    /**
     * 기존 graphRevision의 해시 입력이다. 정점(id·종류), 간선, 순회 간선 행(정렬·중복 제거), 어휘적 소속, 정점별 미해결 호출 수와
     * 두 캡처 여부를 키 순서대로 담는다. 스트리밍 구현과 대조하는 오라클이므로 의도한 형식 변경이 아니면 바꾸지 않는다.
     */
    private fun renderedContent(graph: CodeGraph, enclosuresCaptured: Boolean, callbackFactsCaptured: Boolean): Map<String, Any?> {
        val traversal = TraversalEdges.build(graph, enclosuresCaptured, callbackFactsCaptured)
            .map { listOf(it.source.value, it.target.value, it.relationship, it.tier.name) + if (it.terminal) listOf("terminal") else emptyList() }
            .distinct().sortedWith { left, right ->
                left.zip(right).firstNotNullOfOrNull { (a, b) -> a.compareTo(b).takeIf { it != 0 } } ?: left.size.compareTo(right.size)
            }
        val unresolved = graph.externalCalls.filter { it.isUnresolvedTarget() }.groupingBy { it.caller.value }.eachCount()
            .toSortedMap().map { (caller, count) -> listOf(caller, count.toString()) }
        return sortedMapOf(
            "nodes" to graph.nodeIds.map { listOf(it.value, graph.nodes.getValue(it).kind.name) },
            "edges" to graph.edges.map { listOf(it.source.value, it.target.value, it.kind.name, it.origin.name) },
            "traversalEdges" to traversal, "enclosures" to graph.enclosures.map { listOf(it.localClass.value, it.enclosing.value) },
            "enclosuresCaptured" to enclosuresCaptured, "unresolvedCalls" to unresolved, "callbackFactsCaptured" to callbackFactsCaptured,
        )
    }

    /** [renderedContent]를 문자열로 렌더링해 UTF-8 바이트로 해시한 기존 값이다. */
    private fun renderedRevision(graph: CodeGraph, enclosuresCaptured: Boolean, callbackFactsCaptured: Boolean): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(jsonValue(renderedContent(graph, enclosuresCaptured, callbackFactsCaptured))
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 골든 값 그래프다. 비ASCII·서러게이트 쌍 id, 미해결 호출, 어휘적 소속, 콜백 간선(terminal)을 모두 담는다. */
    private fun goldenGraph(): CodeGraph {
        val a = NodeId("method:p/A#run()V")
        val b = NodeId("method:p/B#run()V")
        val invoker = NodeId("method:p/G#invoke(Ljava/lang/Runnable;)V")
        val named = NodeId("method:p/한글😀#run()V")
        val local = NodeId("class:p/A\$run\$1")
        val body = NodeId("method:p/A\$run\$1#run()V")
        val nodes = listOf(GraphNode(a, "run", NodeKind.METHOD), GraphNode(b, "run", NodeKind.METHOD), GraphNode(invoker, "invoke", NodeKind.METHOD),
            GraphNode(named, "run", NodeKind.METHOD), GraphNode(local, "A\$run\$1", NodeKind.CLASS), GraphNode(body, "run", NodeKind.METHOD))
        val edges = listOf(GraphEdge(b, a, EdgeKind.CALL), GraphEdge(a, invoker, EdgeKind.CALL),
            GraphEdge(named, a, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL), GraphEdge(local, body, EdgeKind.MEMBER))
        val calls = listOf(ExternalCall(a, "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE, resolution = CallResolution.UNRESOLVED))
        return CodeGraph(nodes, edges, calls, enclosures = listOf(LexicalEnclosure(local, a)),
            callbackArguments = listOf(CallbackArgument(a, invoker, InvocationKind.STATIC, 0, body, "run()V")),
            parameterUses = listOf(ParameterUse(invoker, 0, ParameterUseKind.DECLARED),
                ParameterUse(invoker, 0, ParameterUseKind.RECEIVER, NodeId("method:java/lang/Runnable#run()V"), InvocationKind.INTERFACE)))
    }

    /** 해시 대조용 무작위 그래프다. 비ASCII id, 여러 간선 종류·출처, 멤버 간선, 미해결 호출, 어휘적 소속을 섞는다. */
    private fun randomRevisionGraph(random: Random): CodeGraph {
        val suffixes = listOf("", "", "é", "😀", "한", "\"q\\")
        val methods = List(random.nextInt(2, 16)) { NodeId("method:p/N$it${suffixes.random(random)}#run()V") }
        val classes = List(random.nextInt(0, 3)) { NodeId("class:p/N$it\$1") }
        val all = methods + classes
        val kinds = listOf(EdgeKind.CALL, EdgeKind.REFERENCE, EdgeKind.FIELD_ACCESS, EdgeKind.INHERITANCE, EdgeKind.OVERRIDE)
        val edges = List(random.nextInt(0, all.size * 3)) {
            GraphEdge(all.random(random), all.random(random), kinds.random(random), origin = listOf(EdgeOrigin.BYTECODE, EdgeOrigin.RUNTIME_MODEL).random(random))
        } + classes.flatMap { owner -> methods.filter { random.nextInt(3) == 0 }.map { GraphEdge(owner, it, EdgeKind.MEMBER) } }
        val calls = List(random.nextInt(0, 4)) {
            ExternalCall(methods.random(random), "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE, resolution = CallResolution.UNRESOLVED)
        }
        return CodeGraph(methods.map { GraphNode(it, "run", NodeKind.METHOD) } + classes.map { GraphNode(it, it.value.substringAfter('/'), NodeKind.CLASS) },
            edges, calls, enclosures = classes.map { LexicalEnclosure(it, methods.random(random)) })
    }

    @Test
    fun `exchange text rejects the isthmus control range and lone surrogates`() {
        assertTrue(LanguageTraversalCodec.isExchangeText("method:p/A#run()V"))
        assertTrue(LanguageTraversalCodec.isExchangeText("v1.2-\uD83D\uDE00"))
        listOf("", "  ", "a\u0000", "a\u001f", "a\u007f", "a\u0080", "a\u0085", "a\u009f", "a\u2028", "a\u2029", "a\uD800", "\uDC00a")
            .forEach { assertFalse(LanguageTraversalCodec.isExchangeText(it), it) }
    }

    private companion object {
        /** [goldenGraph]의 (소속 캡처, 콜백 캡처) = (참,참)·(참,거짓)·(거짓,참)·(거짓,거짓) 값이다. 스트리밍 전 구현으로 구했다. */
        val GOLDEN_REVISIONS = listOf(
            "sha256:35c43ea336e5370f6848d6c0c262ccebbcbc3356908a9249cee55c958f8e5eed",
            "sha256:6b1341b42d478c1a40aa557acfa7933b5475153191e8547d8c12856c8cb466ce",
            "sha256:bc18793e411f0ebb29fa54cf6cb97a8e90c54137ad733155edcefc97b70636dd",
            "sha256:977b1e329298ba072c0376c111199d6f9cc9cd8970f997b16890228f2763aa89",
        )
    }
}
