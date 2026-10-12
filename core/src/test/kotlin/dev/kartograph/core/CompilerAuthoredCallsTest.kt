package dev.kartograph.core

import kotlin.test.*

class CompilerAuthoredCallsTest {
    private val caller = NodeId("method:Calls#zero()Ljava/lang/String;")
    private val target = NodeId("method:Value#toString()Ljava/lang/String;")
    private fun row(calls: List<CompilerAuthoredCall> = emptyList()) = CompilerAuthoredCaller(caller,
        CompilerEvidenceSource("src/Calls.kt", "a".repeat(64)), 0, 100, CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
        "2.4.20", 10, calls.size, AuthoredCallCoverage.COMPLETE, emptyList(), calls, "b".repeat(64), "c".repeat(64), "d".repeat(64))
    private fun node(id: NodeId) = GraphNode(id, "method", NodeKind.METHOD)

    @Test fun `complete zero is positive and source-only calls cannot alter raw graph`() {
        val graph = CodeGraph(listOf(node(caller), node(target)), emptyList())
        assertNull(graph.compilerAuthoredCallsFrom(caller))
        val zero = graph.withCompilerAuthoredCalls(listOf(row()))
        assertEquals(AuthoredCallCoverage.COMPLETE, zero.compilerAuthoredCallsFrom(caller)?.coverage)
        assertEquals(emptyList(), zero.compilerAuthoredCallsFrom(caller)?.calls)
        val call = CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/Calls.kt", 10, 18)
        val enriched = graph.withCompilerAuthoredCalls(listOf(row(listOf(call))))
        assertEquals(graph.edges, enriched.edges)
        assertEquals(graph.usageSuccessorsOf(caller), enriched.usageSuccessorsOf(caller))
        assertEquals(graph.externalCalls, enriched.externalCalls)
        assertFalse(enriched.compilerCallPositionsCaptured)
        assertEquals(enriched.compilerAuthoredCallers, enriched.enrichedWith(emptyList()).compilerAuthoredCallers)
        assertEquals(enriched.compilerAuthoredCallers, enriched.withCompilerCallPositions(emptyList()).compilerAuthoredCallers)
    }
    @Test fun `filtering source facts cannot manufacture complete empty`() {
        val call = CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/Calls.kt", 10, 18)
        val filtered = CodeGraph(listOf(node(caller)), emptyList()).withCompilerAuthoredCalls(listOf(row(listOf(call))))
        val coverage = filtered.compilerAuthoredCallsFrom(caller)!!
        assertEquals(AuthoredCallCoverage.INCOMPLETE, coverage.coverage)
        assertEquals(listOf(AuthoredCallReason.FILTERED_TARGET), coverage.reasons)
        assertEquals(emptyList(), coverage.calls)
        assertEquals(emptyList(), CodeGraph(emptyList(), emptyList()).withCompilerAuthoredCalls(listOf(row())).compilerAuthoredCallers)
    }
    @Test fun `descriptor compiler and coordinate profile are strict`() {
        for (invalid in listOf("method:Calls#zero(V)V", "method:Calls#zero()Vextra", "method:Calls#zero([V)V",
            "method:Calls#zero(Lbad.owner;)V", "method:Calls#zero(", "method:Calls#<init>()V", "method:Calls#zero()")) {
            assertFailsWith<IllegalArgumentException> { row().copy(caller = NodeId(invalid)) }
        }
        assertFailsWith<IllegalArgumentException> { row().copy(compilerVersion = "2.4.10") }
        assertFailsWith<IllegalArgumentException> { row().copy(compilerVersion = "17", coordinateBasis = CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE) }
    }
    @Test fun `duplicates counts ranges and contradictory coverage fail closed`() {
        assertFailsWith<IllegalArgumentException> { CodeGraph(listOf(node(caller)), emptyList()).withCompilerAuthoredCalls(listOf(row(), row())) }
        assertFailsWith<IllegalArgumentException> { row().copy(explicitCalls = 1) }
        assertFailsWith<IllegalArgumentException> { row().copy(coverage = AuthoredCallCoverage.INCOMPLETE) }
        assertFailsWith<IllegalArgumentException> { row().copy(reasons = listOf(AuthoredCallReason.NO_BODY)) }
        assertFailsWith<IllegalArgumentException> { row().copy(classifiedNodes = Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { row().copy(classifiedNodes = 0) }
        assertFailsWith<IllegalArgumentException> { row().copy(inputToken = "bad") }
        val binary = CompilerAuthoredCall(target, AuthoredCallOrigin.BINARY, null, 10, 18)
        assertFailsWith<IllegalArgumentException> {
            CodeGraph(listOf(node(caller), node(target)), emptyList()).withCompilerAuthoredCalls(listOf(row(listOf(binary))))
        }
    }
    @Test fun `external stub caller cannot carry project source coverage`() {
        val stub = node(caller).copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB))
        assertFailsWith<IllegalArgumentException> {
            CodeGraph(listOf(stub), emptyList()).withCompilerAuthoredCalls(listOf(row()))
        }
    }

}
