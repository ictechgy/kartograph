package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class CompactSnapshotTest {
    @Test fun `default v1 omits optional context labels for old readers`() {
        val plain = QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList()))
        assertContains(plain, "\"version\": 1")
        assertFalse(plain.contains("\"revision\":"))
        assertFalse(plain.contains("\"scope\":"))
    }

    @Test fun `compact snapshots keep all facts and materially reduce repeated edge identities`() {
        val nodes=(0..25).map { GraphNode(NodeId("class:long/package/with/repeated/names/Type$it"),"Type$it",NodeKind.CLASS) }
        val graph=CodeGraph(nodes,nodes.zipWithNext { a,b -> GraphEdge(a.id,b.id,EdgeKind.CALL,3,EdgeOrigin.BYTECODE, listOf(2,4)) })
        val original=QuerySnapshot(graph,listOf(RetentionEvidence(nodes.first().id,RetentionReason.KEEP_RULE,null)),listOf("measured: 1"),
            revision="a".repeat(40),scope="sample:debug")
        val plain=QuerySnapshotCodec.render(original)
        val compact=QuerySnapshotCodec.render(original,compact=true)
        assertTrue(compact.length < plain.length)
        val decoded=QuerySnapshotCodec.parse(compact)
        assertEquals(graph.nodes,decoded.graph.nodes)
        assertEquals(graph.edges,decoded.graph.edges)
        assertEquals(original.retention,decoded.retention)
        assertEquals(original.limitations,decoded.limitations)
        assertEquals(original.revision,decoded.revision)
        assertEquals(original.scope,decoded.scope)
        assertEquals(compact,QuerySnapshotCodec.render(decoded,compact=true))
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(compact.replace("[0, 1,", "[-1, 1,")) }
    }

    // 아래 문서는 1 MiB 문자열 하나를 2,100번 참조한다. 문서는 약 1 MiB라 바이트 상한 안이지만 펼친 문자 수는
    // 약 22억 자로 예산(2^31자)을 넘는다. 예산이 없으면 참조마다 경로 검사·식별자 연결·정렬 비교가 공유 문자열
    // 전체를 다시 훑거나 복사한다.
    @Test fun `compact node locations sharing one large path are rejected`() {
        val nodes = (0 until SHARED_REFERENCES).map { index ->
            GraphNode(NodeId("class:p/C$index"), "C$index", NodeKind.CLASS, location = SourceLocation(PLACEHOLDER))
        }
        assertExpansionRejected(amplified(snapshot(CodeGraph(nodes, emptyList())), "a".repeat(SHARED_LENGTH) + ".kt"))
    }

    @Test fun `compact method nodes sharing one large name are rejected`() {
        val nodes = (0 until SHARED_REFERENCES).map { index ->
            GraphNode(NodeId("method:p/C#m$index()V"), PLACEHOLDER, NodeKind.METHOD)
        }
        assertExpansionRejected(amplified(snapshot(CodeGraph(nodes, emptyList())), "n".repeat(SHARED_LENGTH)))
    }

    @Test fun `compact external calls sharing one large owner are rejected`() {
        val caller = GraphNode(NodeId("method:p/Caller#run()V"), "run", NodeKind.METHOD)
        val calls = (0 until SHARED_REFERENCES).map { ordinal ->
            ExternalCall(caller.id, PLACEHOLDER, "call", "()V", InvocationKind.STATIC, ordinal = ordinal)
        }
        assertExpansionRejected(amplified(snapshot(CodeGraph(listOf(caller), emptyList(), calls)), "o".repeat(SHARED_LENGTH)))
    }

    @Test fun `compact edges sharing one large node identity are rejected`() {
        // 간선은 정점 위치로 usr를 참조한다. 같은 usr 인스턴스끼리의 정렬 비교도 문자열 전체를 훑는다.
        val source = GraphNode(NodeId(PLACEHOLDER), "Source", NodeKind.CLASS)
        val targets = (0 until SHARED_REFERENCES).map { GraphNode(NodeId("class:p/T$it"), "T$it", NodeKind.CLASS) }
        val graph = CodeGraph(listOf(source) + targets, targets.map { GraphEdge(source.id, it.id, EdgeKind.REFERENCE) })
        assertExpansionRejected(amplified(snapshot(graph), "class:p/" + "u".repeat(SHARED_LENGTH)))
    }

    @Test fun `compact compiler call evidence sharing one large path is rejected`() {
        val caller = NodeId("method:demo/Caller#use()V")
        val target = NodeId("method:demo/Target#hit()V")
        val base = CodeGraph(listOf(GraphNode(caller, "use", NodeKind.METHOD), GraphNode(target, "hit", NodeKind.METHOD)),
            listOf(GraphEdge(caller, target, EdgeKind.CALL, origin = EdgeOrigin.BYTECODE)))
        val references = (0 until SHARED_REFERENCES).map { index ->
            LocatedCompilerReference(caller, target, CompilerEvidenceSource(PLACEHOLDER, "a".repeat(64)), "javac-constants",
                "17.0.20+0", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 100 + index * 4, 103 + index * 4, index + 1, 1)
        }
        assertExpansionRejected(amplified(snapshot(base.withCompilerCallPositions(references)), "s".repeat(SHARED_LENGTH) + ".java"))
    }

    @Test fun `expansion budget counts every expanded string and the codec compiler evidence columns`() {
        val text = QuerySnapshotCodec.render(snapshot(richGraph()), compact = true)
        val graph = rawDocument(text)["graph"] as Map<*, *>
        val budget = CompactSnapshotGraph.ExpansionBudget()
        val expanded = CompactSnapshotGraph.expand(graph, budget)
        val expandedStrings = listOf("nodes", "edges", "externalCalls").flatMap { strings(expanded[it]) }
        // codec이 compact 근거 행에서 실제로 펼친 문자열이다. 열 배치가 codec과 어긋나면 아래 합이 달라진다.
        val evidence = QuerySnapshotCodec.parse(text).graph.locatedCompilerReferences
        assertEquals(2, evidence.size)
        val evidenceStrings = evidence.flatMap {
            listOf(it.source.value, it.target.value, it.file.path, it.file.sha256, it.collector, it.compilerVersion,
                it.coordinateBasis.name.lowerCamel())
        }
        assertEquals((expandedStrings + evidenceStrings).sumOf { it.length.toLong() }, budget.characters)
        assertEquals((expandedStrings + evidenceStrings).size.toLong(), budget.references)

        val withoutEvidence = CompactSnapshotGraph.ExpansionBudget().also { CompactSnapshotGraph.expand(graph - "compilerCallEvidence", it) }
        assertEquals(evidenceStrings.sumOf { it.length.toLong() }, budget.characters - withoutEvidence.characters)
        assertEquals(evidenceStrings.size.toLong(), budget.references - withoutEvidence.references)
    }

    @Test fun `expansion budget accepts its exact total and rejects one character or string more`() {
        val graph = rawDocument(QuerySnapshotCodec.render(snapshot(richGraph()), compact = true))["graph"] as Map<*, *>
        val total = CompactSnapshotGraph.ExpansionBudget().also { CompactSnapshotGraph.expand(graph, it) }
        CompactSnapshotGraph.expand(graph, CompactSnapshotGraph.ExpansionBudget(total.characters, total.references))
        for (budget in listOf(CompactSnapshotGraph.ExpansionBudget(total.characters - 1, total.references),
            CompactSnapshotGraph.ExpansionBudget(total.characters, total.references - 1))) {
            val failure = assertFailsWith<IllegalArgumentException> { CompactSnapshotGraph.expand(graph, budget) }
            assertContains(failure.message.orEmpty(), "expand beyond the supported budget")
        }
    }

    @Test fun `expansion stops at the first string over the budget`() {
        // 1,000자 이름 하나를 method 100개가 공유한다. 10,000자 상한이면 남은 행을 펼치지 않고 바로 멈춰야 한다.
        val nodes = (0 until 100).map { GraphNode(NodeId("method:p/C#m$it()V"), PLACEHOLDER, NodeKind.METHOD) }
        val text = QuerySnapshotCodec.render(snapshot(CodeGraph(nodes, emptyList())), compact = true)
            .replace("\"$PLACEHOLDER\"", "\"${"n".repeat(1_000)}\"")
        val budget = CompactSnapshotGraph.ExpansionBudget(10_000, Long.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { CompactSnapshotGraph.expand(rawDocument(text)["graph"] as Map<*, *>, budget) }
        assertTrue(budget.characters in 10_001L..11_000L, "characters=${budget.characters}")
        assertTrue(budget.references < 100, "references=${budget.references}")
    }

    @Test fun `malformed compact compiler evidence rows are not counted and keep the codec message`() {
        val document = rawDocument(QuerySnapshotCodec.render(snapshot(richGraph()), compact = true))
        val graph = document["graph"] as Map<*, *>
        val row = (graph["compilerCallEvidence"] as List<*>).first() as List<*>
        val tableSize = (graph["stringTable"] as List<*>).size.toLong()
        val evidenceMessage = "query snapshot contains invalid compact compiler call evidence"
        val cases = listOf(
            "not a list" to "query snapshot has invalid or missing fields",
            listOf("not a row") to "query snapshot has invalid or missing fields",
            listOf(row.dropLast(1)) to evidenceMessage,
            listOf(row.toMutableList().also { it[0] = -1L }) to evidenceMessage,
            listOf(row.toMutableList().also { it[2] = tableSize }) to evidenceMessage,
            listOf(row.toMutableList().also { it[6] = "kotlinUtf16NormalizedSource" }) to "query snapshot has invalid or missing fields",
        )
        val baseline = CompactSnapshotGraph.ExpansionBudget().also { CompactSnapshotGraph.expand(graph - "compilerCallEvidence", it) }
        for ((rows, message) in cases) {
            val changed = graph + ("compilerCallEvidence" to rows)
            val budget = CompactSnapshotGraph.ExpansionBudget().also { CompactSnapshotGraph.expand(changed, it) }
            assertEquals(baseline.characters, budget.characters)
            assertEquals(baseline.references, budget.references)
            val failure = assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(document + ("graph" to changed))) }
            assertEquals(message, failure.message)
        }
    }

    /** 문자열 필드를 모두 채운 정점, 두 종류 간선, 후보가 있는 외부 호출, 두 compiler 근거 행을 가진 그래프다. */
    private fun richGraph(): CodeGraph {
        val caller = NodeId("method:demo/Caller#use()V")
        val target = NodeId("method:demo/Target#hit()V")
        val owner = GraphNode(NodeId("class:demo/Owner"), "Owner", NodeKind.CLASS, moduleName = "app", jvmSignature = "<T:Ljava/lang/Object;>",
            location = SourceLocation("src/main/kotlin/demo/Owner.kt", 3, 7), visibility = Visibility.PUBLIC, jvmVisibility = Visibility.PUBLIC,
            jvmModifiers = setOf(JvmModifier.FINAL, JvmModifier.ABSTRACT), attributes = setOf(NodeAttribute.DATA_CLASS),
            annotations = setOf("Lkotlin/Metadata;"), supertypes = setOf("java/lang/Object", "java/io/Serializable"),
            extensionReceiverType = "kotlin/String", synthesized = true)
        val nodes = listOf(owner, GraphNode(caller, "use", NodeKind.METHOD, location = SourceLocation("Caller.java")),
            GraphNode(target, "hit", NodeKind.METHOD))
        val edges = listOf(GraphEdge(caller, target, EdgeKind.CALL, origin = EdgeOrigin.BYTECODE),
            GraphEdge(owner.id, target, EdgeKind.REFERENCE, 2, EdgeOrigin.KOTLIN_METADATA))
        val calls = listOf(ExternalCall(caller, "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE, SourceLocation("Caller.java", 9),
            1, listOf(target), CallResolution.PROJECT_CANDIDATES, "runnable-model"))
        val references = listOf(
            LocatedCompilerReference(caller, target, CompilerEvidenceSource("src/main/java/demo/Caller.java", "a".repeat(64)),
                "javac-constants", "17.0.20+0", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 63, 66, 1, 64),
            LocatedCompilerReference(caller, target, CompilerEvidenceSource("build/generated/demo/Caller.kt", "b".repeat(64)),
                "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, 52, 58, 4, 21, generated = true),
        )
        return CodeGraph(nodes, edges, calls).withCompilerCallPositions(references)
    }

    private fun rawDocument(text: String): Map<*, *> = SnapshotJsonParser(text).parse() as Map<*, *>

    /** 펼친 값 안의 모든 문자열이다. 펼침이 만든 파생 문자열만 들어 있다. */
    private fun strings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is Map<*, *> -> value.values.flatMap(::strings)
        is List<*> -> value.flatMap(::strings)
        else -> emptyList()
    }

    private fun snapshot(graph: CodeGraph): QuerySnapshot = QuerySnapshot(graph, emptyList(), emptyList())

    /** 문자열 사전의 placeholder 항목 하나를 큰 값으로 바꾼다. 항목이 하나뿐이라 모든 참조가 그 값을 공유한다. */
    private fun amplified(snapshot: QuerySnapshot, replacement: String): String {
        val compact = QuerySnapshotCodec.render(snapshot, compact = true)
        val quoted = "\"$PLACEHOLDER\""
        val first = compact.indexOf(quoted)
        assertTrue(first >= 0 && compact.indexOf(quoted, first + 1) < 0, "placeholder must be a single string table entry")
        return compact.replace(quoted, "\"$replacement\"").also { assertTrue(it.length < 2 * SHARED_LENGTH) }
    }

    /** 거부 문구는 상한과 해결 방향만 담고 공유 문자열 같은 원시 값은 싣지 않는다. */
    private fun assertExpansionRejected(document: String) {
        val failure = assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(document) }
        assertEquals("compact query snapshot string references expand beyond the supported budget (2147483648 characters, " +
            "33554432 strings); recapture it with `kartograph snapshot`", failure.message)
    }

    private companion object {
        const val PLACEHOLDER = "SharedPlaceholder"
        const val SHARED_LENGTH = 1024 * 1024
        const val SHARED_REFERENCES = 2_100
    }
}
