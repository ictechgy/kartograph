package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class CompilerAuthoredCallSnapshotTest {
    private val caller = NodeId("method:Calls#zero()Ljava/lang/String;")
    private fun graph() = CodeGraph(listOf(GraphNode(caller, "zero", NodeKind.METHOD)), emptyList())
    private fun row() = CompilerAuthoredCaller(caller, CompilerEvidenceSource("src/Calls.kt", "a".repeat(64)), 0, 100,
        CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, "2.4.20", 3, 0, AuthoredCallCoverage.COMPLETE,
        emptyList(), emptyList(), "b".repeat(64), "c".repeat(64), "d".repeat(64))
    @Test fun `plain compact and legacy snapshots distinguish complete zero from unknown`() {
        for (compact in listOf(false, true)) {
            val absent = QuerySnapshotCodec.render(QuerySnapshot(graph(), emptyList(), emptyList()), compact)
            assertFalse("compilerAuthoredCalls" in absent)
            assertTrue(QuerySnapshotCodec.parse(absent).graph.compilerAuthoredCallers.isEmpty())
            val snapshot = QuerySnapshot(graph().withCompilerAuthoredCalls(listOf(row())), emptyList(), emptyList())
            val text = QuerySnapshotCodec.render(snapshot, compact)
            val restored = QuerySnapshotCodec.parse(text)
            assertEquals(snapshot.graph.compilerAuthoredCallers, restored.graph.compilerAuthoredCallers)
            assertEquals(text, QuerySnapshotCodec.render(restored, compact))
            assertEquals(snapshot.graph.edges, restored.graph.edges)
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("ordinary-body-v1", "unknown")) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("COMPLETE", "INCOMPLETE")) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("\"explicitCalls\": 0", "\"explicitCalls\": 1")) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("\"verifiedAtCapture\"", "\"freshForever\"")) }
        }
    }
    @Test fun `source-only targets origins and incomplete filtering round trip strictly`() {
        val project = NodeId("method:Value#toString()Ljava/lang/String;")
        val binary = NodeId("method:java/lang/Object#toString()Ljava/lang/String;")
        val calls = listOf(CompilerAuthoredCall(project, AuthoredCallOrigin.PROJECT, "src/Value.kt", 10, 18),
            CompilerAuthoredCall(binary, AuthoredCallOrigin.BINARY, null, 30, 38))
        val row = row().copy(explicitCalls = 2, calls = calls)
        val complete = CodeGraph(graph().nodes.values + GraphNode(project, "toString", NodeKind.FUNCTION), emptyList())
            .withCompilerAuthoredCalls(listOf(row))
        val filtered = graph().withCompilerAuthoredCalls(listOf(row))
        assertEquals(AuthoredCallCoverage.INCOMPLETE, filtered.compilerAuthoredCallsFrom(caller)?.coverage)
        for (compact in listOf(false, true)) for (current in listOf(complete, filtered)) {
            val text = QuerySnapshotCodec.render(QuerySnapshot(current, emptyList(), emptyList()), compact)
            assertEquals(current.compilerAuthoredCallers, QuerySnapshotCodec.parse(text).graph.compilerAuthoredCallers)
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("BINARY", "PROJECT")) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("java/lang/Object", "bad.owner")) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text.replace("\"end\": 38", "\"end\": 101")) }
        }
    }

    @Test fun `plain and compact external stub caller claims fail closed`() {
        val stub = CodeGraph(graph().nodes.values.map { it.copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB)) }, emptyList())
        for (compact in listOf(false, true)) {
            val valid = SnapshotJsonParser(QuerySnapshotCodec.render(QuerySnapshot(graph().withCompilerAuthoredCalls(listOf(row())),
                emptyList(), emptyList()), compact)).parse() as Map<*, *>
            val plainStub = SnapshotJsonParser(QuerySnapshotCodec.render(QuerySnapshot(stub, emptyList(), emptyList()), compact)).parse() as Map<*, *>
            val forgedGraph = (plainStub["graph"] as Map<*, *>).toMutableMap().apply {
                put("compilerAuthoredCalls", (valid["graph"] as Map<*, *>)["compilerAuthoredCalls"])
            }
            val forged = plainStub.toMutableMap().apply { put("graph", forgedGraph) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(forged)) }
        }
    }

}
