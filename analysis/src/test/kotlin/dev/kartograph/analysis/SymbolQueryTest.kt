package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class SymbolQueryTest {
    @Test
    fun `direct bytecode call neighbors expose sorted call-site references`() {
        val caller = GraphNode(NodeId("method:app/Caller#calls()V"), "calls", NodeKind.METHOD,
            location = SourceLocation("Caller.java", 3))
        val target = GraphNode(NodeId("method:app/Target#run()V"), "run", NodeKind.METHOD,
            location = SourceLocation("Target.java", 1))
        val graph = CodeGraph(listOf(caller, target), listOf(
            GraphEdge(caller.id, target.id, EdgeKind.CALL, weight = 2, origin = EdgeOrigin.BYTECODE, callSiteLines = listOf(5, 7)),
        ))

        val neighbor = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), target.id.value, emptyList())
            .result!!.usedBy.single()

        assertEquals(listOf(5, 7), neighbor.references.map { it.location.line })
        assertEquals(listOf("Caller.java", "Caller.java"), neighbor.references.map { it.location.path })
        assertTrue(neighbor.references.all { it.kind == "call" && it.origin == "bytecode" && it.location.column == null })

        val outgoing = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), caller.id.value, emptyList())
            .result!!.dependsOn.single()
        assertEquals(listOf(5, 7), outgoing.references.map { it.location.line })
        assertEquals(listOf("Caller.java", "Caller.java"), outgoing.references.map { it.location.path })
    }

    @Test
    fun `saved graphs without call-site capture report the measured gap`() {
        val node = GraphNode(NodeId("class:app/Subject"), "Subject", NodeKind.CLASS)
        val document = SymbolQuery.query(
            CodeGraph(listOf(node), emptyList()), ReachabilityAnalyzer.analyze(CodeGraph(listOf(node), emptyList()), emptyList()),
            node.id.value, emptyList(), callSiteLinesCaptured = false,
        )

        assertTrue(document.limitations.any { it.startsWith("call-site-lines:") })
    }

    @Test
    fun `source style discovery returns every overload without resolving the query`() {
        val primitive = GraphNode(NodeId("method:com/acme/Writer#value(D)V"), "value", NodeKind.METHOD,
            jvmSignature = "com/acme/Writer#value(D)V", location = SourceLocation("src/Writer.java", 10))
        val objectValue = GraphNode(NodeId("method:com/acme/Writer#value(Ljava/lang/Object;)V"), "value", NodeKind.METHOD,
            jvmSignature = "com/acme/Writer#value(Ljava/lang/Object;)V", location = SourceLocation("src/Writer.java", 11))
        val collision = GraphNode(NodeId("method:other/Writer#value(D)V"), "value", NodeKind.METHOD,
            jvmSignature = "other/Writer#value(D)V")
        val graph = CodeGraph(listOf(primitive, objectValue, collision), emptyList())

        val page = SymbolDiscovery.suggest(listOf(graph), "com.acme.Writer.value(double)", 10)

        assertEquals(2, page.total)
        assertEquals(listOf(primitive.id.value, objectValue.id.value), page.candidates.map { it.usr })
        assertTrue(page.candidates.all { it.qualifiedName == "com.acme.Writer.value" })
        assertEquals(SourceLocation("src/Writer.java", 10), page.candidates.first().location)
        assertEquals("notFound", SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()),
            "com.acme.Writer.value(double)", emptyList()).status)
    }

    @Test
    fun `discovery is case sensitive owner qualified bounded and deterministic`() {
        val nodes = listOf("a/Writer", "b/Writer", "c/writer").map { owner ->
            GraphNode(NodeId("method:$owner#value()V"), "value", NodeKind.METHOD, jvmSignature = "$owner#value()V")
        }
        val graph = CodeGraph(nodes.reversed(), emptyList())

        val page = SymbolDiscovery.suggest(listOf(graph), "Writer.value()", 1)

        assertEquals(2, page.total)
        assertEquals(1, page.returned)
        assertTrue(page.truncated)
        assertEquals("method:a/Writer#value()V", page.candidates.single().usr)
        assertEquals(0, SymbolDiscovery.suggest(listOf(graph), "WRITER.value()", 10).total)
        assertEquals(0, SymbolDiscovery.suggest(listOf(graph), "value()", 10).total)
    }

    @Test
    fun `found query uses sibling document fields and usage edges`() {
        val root = NodeId("class:app/Root")
        val subject = NodeId("class:app/Subject")
        val member = NodeId("method:app/Subject#work()V")
        val graph = CodeGraph(
            listOf(
                GraphNode(root, "Root", NodeKind.CLASS, moduleName = "app"),
                GraphNode(subject, "Subject", NodeKind.CLASS, moduleName = "app"),
                GraphNode(member, "work", NodeKind.METHOD, moduleName = "app"),
            ),
            listOf(
                GraphEdge(root, subject, EdgeKind.CALL),
                GraphEdge(subject, member, EdgeKind.MEMBER),
            ),
        )
        val reachability = ReachabilityAnalyzer.analyze(
            graph,
            listOf(RetentionEvidence(root, RetentionReason.MANIFEST_COMPONENT, null)),
        )

        val document = SymbolQuery.query(graph, reachability, "Subject", limitations = listOf("jni-methods: 1"))

        assertEquals("found", document.status)
        assertEquals("Subject", document.requested)
        assertEquals("symbol", document.level)
        assertEquals(listOf("jni-methods: 1"), document.limitations)
        assertEquals("reachable", document.result?.reachability?.state)
        assertEquals(listOf("app.Root"), document.result?.usedBy?.map { it.qualifiedName })
        assertEquals(listOf("app.Subject.work"), document.result?.members?.map { it.qualifiedName })
        assertEquals(emptyList(), document.result?.dependsOn)
    }

    @Test
    fun `not found query still includes measured limitations`() {
        val graph = CodeGraph(emptyList(), emptyList())
        val reachability = ReachabilityAnalyzer.analyze(graph, emptyList())

        val document = SymbolQuery.query(
            graph,
            reachability,
            "Missing",
            limitations = listOf("reflection-strings: 2 Class.forName call(s) use runtime names"),
        )

        assertEquals("notFound", document.status)
        assertEquals(listOf("reflection-strings: 2 Class.forName call(s) use runtime names"), document.limitations)
        assertNull(document.result)
        assertNull(document.candidates)
    }

    @Test
    fun `reachable declarations are not marked as suppressed by an old baseline`() {
        val root = NodeId("class:app/Root")
        val subject = NodeId("class:app/Subject")
        val graph = CodeGraph(
            listOf(
                GraphNode(root, "Root", NodeKind.CLASS),
                GraphNode(subject, "Subject", NodeKind.CLASS),
            ),
            listOf(GraphEdge(root, subject, EdgeKind.REFERENCE)),
        )
        val reachability = ReachabilityAnalyzer.analyze(
            graph,
            listOf(RetentionEvidence(root, RetentionReason.MANIFEST_COMPONENT, null)),
        )

        val document = SymbolQuery.query(
            graph,
            reachability,
            "Subject",
            limitations = emptyList(),
            suppressedByBaseline = setOf(subject),
        )

        assertEquals(false, document.result?.reachability?.suppressedByBaseline)
    }

    @Test
    fun `unreachable declarations preserve baseline decisions`() {
        val subject = NodeId("class:app/Subject")
        val graph = CodeGraph(listOf(GraphNode(subject, "Subject", NodeKind.CLASS)), emptyList())

        val document = SymbolQuery.query(
            graph,
            ReachabilityAnalyzer.analyze(graph, emptyList()),
            "Subject",
            limitations = emptyList(),
            suppressedByBaseline = setOf(subject),
        )

        assertEquals(true, document.result?.reachability?.suppressedByBaseline)
    }

    @Test
    fun `member retention uses the sibling retainedByMember state`() {
        val subject = NodeId("class:app/Subject")
        val graph = CodeGraph(listOf(GraphNode(subject, "Subject", NodeKind.CLASS)), emptyList())
        val reachability = ReachabilityAnalyzer.analyze(
            graph,
            listOf(RetentionEvidence(subject, RetentionReason.KEEP_ANNOTATED_MEMBER, null)),
        )

        val document = SymbolQuery.query(graph, reachability, "Subject", limitations = emptyList())

        assertEquals("retainedByMember", document.result?.reachability?.state)
    }

    @Test
    fun `bounded neighbor pages keep the breadth-first order of the full search`() {
        // 응답 한도에서 멈추는 탐색이 기존 전체 탐색(legacyNeighbors)의 BFS 순서·깊이·간선 근거를 그대로 잘라 쓰는지,
        // 사이클·다중 간선·비사용 간선이 섞인 무작위 그래프에서 depth 1~10과 limit 1·5·10·100 조합으로 대조한다.
        var truncatedPages = 0
        var deepNeighbors = 0
        repeat(RANDOM_GRAPHS) { seed ->
            val random = Random(41_000 + seed)
            val graph = randomGraph(random)
            val reachability = ReachabilityAnalyzer.analyze(graph, emptyList())
            val subject = graph.nodeIds.random(random)
            for (depth in 1..10) {
                val full = SymbolQuery.query(graph, reachability, subject.value, emptyList(), depth = depth, limit = Int.MAX_VALUE).result!!
                for ((incoming, neighbors) in listOf(true to full.usedBy, false to full.dependsOn)) {
                    assertEquals(legacyNeighbors(graph, subject, depth, incoming), neighbors.map { Triple(it.usr, it.depth, it.edges) },
                        "seed $seed depth $depth incoming $incoming")
                    deepNeighbors += neighbors.count { it.depth > 1 }
                }
                for (limit in listOf(1, 5, 10, 100)) {
                    val page = SymbolQuery.query(graph, reachability, subject.value, emptyList(), depth = depth, limit = limit).result!!
                    val case = "seed $seed depth $depth limit $limit"
                    assertEquals(full.usedBy.take(limit), page.usedBy, case)
                    assertEquals(full.dependsOn.take(limit), page.dependsOn, case)
                    assertEquals(SymbolQueryTruncation(full.usedBy.size > limit, full.dependsOn.size > limit, full.members.size > limit), page.truncated, case)
                    if (page.truncated.usedBy || page.truncated.dependsOn) truncatedPages++
                }
            }
        }
        // 표본이 잘린 페이지와 2단계 이상 이웃을 모두 지나야 대조가 의미 있다.
        assertTrue(truncatedPages > 0 && deepNeighbors > 0, "truncated pages $truncatedPages, deep neighbors $deepNeighbors")
    }

    @Test
    fun `neighbor search stops once the response limit is exceeded`() {
        // 리뷰 지적 재현(perf#5): 응답은 BFS 순서의 앞 limit개뿐인데 depth 범위 전체를 방문하며 이웃마다 qualifiedName·간선 목록을 만들었다.
        // 시간 대신 같은 그래프에서 depth만 바꾼 스레드 할당량 차이로, 한도를 넘긴 뒤에는 더 방문하지 않는지 본다.
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "thread allocation accounting is unavailable on this JVM")
        // 정점마다 호출자가 둘인 TREE_LEVELS단 트리다(정점 i의 호출자는 2i·2i+1). 기존 탐색은 depth를 늘리면 호출자 2,046개를 모두 방문했다.
        val count = (1 shl (TREE_LEVELS + 1)) - 1
        val ids = (1..count).associateWith { NodeId("class:app/T$it") }
        val graph = CodeGraph(ids.map { (index, id) -> GraphNode(id, "T$index", NodeKind.CLASS) },
            (2..count).map { index -> GraphEdge(ids.getValue(index), ids.getValue(index / 2), EdgeKind.CALL) })
        val reachability = ReachabilityAnalyzer.analyze(graph, emptyList())
        fun allocated(depth: Int): Long {
            fun query() = SymbolQuery.query(graph, reachability, ids.getValue(1).value, emptyList(), depth = depth, limit = 1)
            // 첫 실행의 클래스 적재 할당을 빼려고 한 번 먼저 돌린다.
            query()
            val before = threads!!.currentThreadAllocatedBytes
            val result = query().result!!
            val bytes = threads.currentThreadAllocatedBytes - before
            assertEquals(listOf(ids.getValue(2).value), result.usedBy.map { it.usr })
            assertTrue(result.truncated.usedBy)
            return bytes
        }
        val extra = allocated(TREE_LEVELS) - allocated(1)
        assertTrue(extra < DEEP_SEARCH_BYTES, "a one-neighbor page allocated $extra more bytes at depth $TREE_LEVELS than at depth 1")
    }

    private fun randomGraph(random: Random): CodeGraph {
        val ids = List(RANDOM_NODES) { NodeId("class:app/N$it") }
        val nodes = ids.mapIndexed { index, id -> GraphNode(id, "N$index", NodeKind.CLASS, location = SourceLocation("N$index.kt")) }
        // MEMBER는 사용 관계가 아니라 탐색에서 빠져야 한다. 같은 쌍의 다른 종류·출처 간선과 자기 간선도 섞는다.
        val kinds = listOf(EdgeKind.CALL, EdgeKind.CALL, EdgeKind.REFERENCE, EdgeKind.FIELD_ACCESS, EdgeKind.MEMBER)
        val edges = List(random.nextInt(RANDOM_NODES, RANDOM_NODES * 3)) {
            val kind = kinds.random(random)
            val origin = if (kind == EdgeKind.CALL && random.nextInt(4) == 0) EdgeOrigin.RUNTIME_MODEL else EdgeOrigin.BYTECODE
            val lines = if (kind == EdgeKind.CALL && origin == EdgeOrigin.BYTECODE && random.nextBoolean()) listOf(random.nextInt(1, 30)) else emptyList()
            GraphEdge(ids.random(random), ids.random(random), kind, origin = origin, callSiteLines = lines)
        }
        return CodeGraph(nodes, edges)
    }

    /** 응답 한도와 상관없이 depth 범위 전체를 방문하던 기존 이웃 탐색을 옮긴 기준 구현이다. 이웃마다 (USR, 깊이, 간선 종류)를 낸다. */
    private fun legacyNeighbors(graph: CodeGraph, subject: NodeId, depth: Int, incoming: Boolean): List<Triple<String?, Int, List<String>>> {
        val seen = mutableSetOf(subject)
        val queue = ArrayDeque<Triple<NodeId, Int, List<GraphEdge>>>()
        fun enqueue(from: NodeId, nextDepth: Int) {
            val edges = if (incoming) graph.incomingEdgesTo(from) else graph.outgoingEdgesFrom(from)
            edges.filter { it.kind.impliesUsage }.groupBy { if (incoming) it.source else it.target }
                .toSortedMap().forEach { (id, grouped) -> queue += Triple(id, nextDepth, grouped) }
        }
        enqueue(subject, 1)
        val found = mutableListOf<Triple<String?, Int, List<String>>>()
        while (queue.isNotEmpty()) {
            val (id, visitDepth, edgeFacts) = queue.removeFirst()
            if (!seen.add(id)) continue
            if (graph.node(id) != null) found += Triple(id.value, visitDepth, edgeFacts.map { edgeName(it.kind) }.distinct().sorted())
            if (visitDepth < depth) enqueue(id, visitDepth + 1)
        }
        return found
    }

    /** 질의 문서의 간선 종류 표기(lowerCamel)다. */
    private fun edgeName(kind: EdgeKind): String = kind.name.lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { word -> word.replaceFirstChar { character -> character.titlecase() } }
    }

    private companion object {
        /** BFS 순서 대조에 쓰는 무작위 그래프 수와 그래프당 정점 수다. */
        const val RANDOM_GRAPHS = 150
        const val RANDOM_NODES = 12

        /** 할당량 비교 트리의 단 수와, 한도를 넘긴 뒤 더 깊은 depth가 추가로 할당해도 되는 상한이다. */
        const val TREE_LEVELS = 10
        const val DEEP_SEARCH_BYTES = 64L * 1024
    }
}
