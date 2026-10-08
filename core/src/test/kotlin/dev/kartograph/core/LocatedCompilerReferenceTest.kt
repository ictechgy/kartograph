package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocatedCompilerReferenceTest {
    private val source = NodeId("method:demo/Caller#use()V")
    private val target = NodeId("method:demo/Target#hit()V")
    private val file = CompilerEvidenceSource("src/main/java/demo/Caller.java", "a".repeat(64))

    @Test
    fun `captured positions are separate sorted immutable graph facts`() {
        val graph = graph()
        val later = reference(offset = 20, end = 23, line = 3, column = 9)
        val earlier = reference(offset = 10, end = 13, line = 2, column = 5)
        val inputs = mutableListOf(later, earlier, earlier)
        val captured = graph.withCompilerCallPositions(inputs)
        inputs.clear()

        assertTrue(captured.compilerCallPositionsCaptured)
        assertEquals(listOf(earlier, later), captured.locatedCompilerReferences)
        assertEquals(graph.nodes, captured.nodes)
        assertEquals(graph.edges, captured.edges)
        assertEquals(graph.edgeCount, captured.edgeCount)
        assertEquals(graph.outgoingEdgesFrom(source), captured.outgoingEdgesFrom(source))
        assertEquals(graph.incomingEdgesTo(target), captured.incomingEdgesTo(target))
        assertSame(captured, captured.withCompilerCallPositions(listOf(later, earlier)))
    }

    @Test
    fun `empty capture is distinct from unavailable evidence`() {
        val graph = graph()
        assertFalse(graph.compilerCallPositionsCaptured)
        assertEquals(emptyList(), graph.locatedCompilerReferences)

        val captured = graph.withCompilerCallPositions(emptyList())
        assertTrue(captured.compilerCallPositionsCaptured)
        assertEquals(emptyList(), captured.locatedCompilerReferences)
    }

    @Test
    fun `positions require nodes and a bytecode call edge`() {
        val missingNode = CodeGraph(listOf(node(source)), emptyList())
        assertFailsWith<IllegalArgumentException> { missingNode.withCompilerCallPositions(listOf(reference())) }

        val wrongKind = CodeGraph(listOf(node(source), node(target)), listOf(GraphEdge(source, target, EdgeKind.REFERENCE)))
        assertFailsWith<IllegalArgumentException> { wrongKind.withCompilerCallPositions(listOf(reference())) }

        val wrongOrigin = CodeGraph(listOf(node(source), node(target)),
            listOf(GraphEdge(source, target, EdgeKind.CALL, origin = EdgeOrigin.KOTLIN_METADATA)))
        assertFailsWith<IllegalArgumentException> { wrongOrigin.withCompilerCallPositions(listOf(reference())) }
    }

    @Test
    fun `same position key rejects conflicting coordinates hashes and compiler basis`() {
        val graph = graph()
        val original = reference()
        val conflicts = listOf(
            original.copy(endOffsetUtf16 = original.endOffsetUtf16 + 1),
            original.copy(line = original.line + 1),
            original.copy(file = original.file.copy(sha256 = "b".repeat(64))),
            original.copy(compilerVersion = "21.0.1"),
            original.copy(collector = "kotlin-constants",
                coordinateBasis = CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE),
        )
        conflicts.forEach { conflict ->
            assertFailsWith<IllegalArgumentException> {
                graph.withCompilerCallPositions(listOf(original, conflict))
            }
        }
    }

    @Test
    fun `model rejects invalid coordinates compiler identities and basis mismatches`() {
        assertFailsWith<IllegalArgumentException> { reference(offset = -1) }
        assertFailsWith<IllegalArgumentException> { reference(offset = 10, end = 10) }
        assertFailsWith<IllegalArgumentException> { reference(line = 0) }
        assertFailsWith<IllegalArgumentException> { reference(column = 0) }
        assertFailsWith<IllegalArgumentException> { reference(collector = "dagger-bindings") }
        assertFailsWith<IllegalArgumentException> { reference(compilerVersion = "bad version") }
        assertFailsWith<IllegalArgumentException> {
            reference(coordinateBasis = CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE)
        }
    }

    @Test
    fun `enrichment preserves facts and drops them only when bytecode calls disappear`() {
        val positioned = graph().withCompilerCallPositions(listOf(reference()))
        val noOp = positioned.enrichedWith(emptyList())
        assertTrue(noOp.compilerCallPositionsCaptured)
        assertSame(positioned.locatedCompilerReferences, noOp.locatedCompilerReferences)

        val dispatch = GraphEdge(source, target, EdgeKind.CALL, origin = EdgeOrigin.DISPATCH_MODEL)
        val enriched = positioned.enrichedWith(listOf(dispatch))
        assertTrue(enriched.compilerCallPositionsCaptured)
        assertSame(positioned.locatedCompilerReferences, enriched.locatedCompilerReferences)

        val dispatchReplacement = positioned.enrichedWith(listOf(dispatch), replacingOrigin = EdgeOrigin.DISPATCH_MODEL)
        assertEquals(positioned.locatedCompilerReferences, dispatchReplacement.locatedCompilerReferences)

        val bytecodeReplacement = positioned.enrichedWith(emptyList(), replacingOrigin = EdgeOrigin.BYTECODE)
        assertTrue(bytecodeReplacement.compilerCallPositionsCaptured)
        assertEquals(emptyList(), bytecodeReplacement.locatedCompilerReferences)

        val retained = positioned.enrichedWith(
            listOf(GraphEdge(source, target, EdgeKind.CALL)), replacingOrigin = EdgeOrigin.BYTECODE)
        assertEquals(positioned.locatedCompilerReferences, retained.locatedCompilerReferences)
    }

    private fun graph(): CodeGraph = CodeGraph(
        nodes = listOf(node(source), node(target)),
        edges = listOf(GraphEdge(source, target, EdgeKind.CALL)),
    )

    private fun node(id: NodeId): GraphNode = GraphNode(id, id.value, NodeKind.METHOD)

    private fun reference(
        offset: Int = 10,
        end: Int = 13,
        line: Int = 2,
        column: Int = 5,
        collector: String = "javac-constants",
        compilerVersion: String = "17.0.20+8",
        coordinateBasis: CompilerSourceCoordinateBasis = CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
    ): LocatedCompilerReference = LocatedCompilerReference(
        source, target, file, collector, compilerVersion, coordinateBasis,
        offset, end, line, column,
    )
}
