package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CallDeclarationTest {
    private val caller = GraphNode(NodeId("method:p/Caller#run()V"), "run", NodeKind.METHOD)
    private val target = GraphNode(NodeId("method:p/Base#m()V"), "m", NodeKind.METHOD)
    private val proof = CallDeclaration(target.id, CallDeclarationScope.PROJECT, listOf("lib/Boundary", "p/Base"))
    private val call = ExternalCall(caller.id, "lib/Boundary", "m", "()V", InvocationKind.VIRTUAL, declaration = proof)

    @Test
    fun `project pruning clears evidence and external evidence never imports nodes`() {
        val graph = CodeGraph(listOf(caller, target), emptyList(), listOf(call))
        assertEquals(proof, graph.externalCalls.single().declaration)
        assertNull(CodeGraph(listOf(caller), graph.edges, graph.externalCalls).externalCalls.single().declaration)
        val external = CallDeclaration(NodeId("method:lib/Boundary#m()V"), CallDeclarationScope.EXTERNAL, listOf("lib/Boundary"))
        val outside = CodeGraph(listOf(caller), emptyList(), listOf(call.copy(declaration = external)))
        assertEquals(external, outside.externalCalls.single().declaration)
        assertEquals(setOf(caller.id), outside.nodes.keys)
        assertEquals(emptyList(), outside.edges)
    }

    @Test
    fun `graph copies caller owned declaration chains`() {
        val chain = mutableListOf("lib/Boundary", "p/Base")
        val graph = CodeGraph(listOf(caller, target), emptyList(), listOf(call.copy(declaration = proof.copy(ownerChain = chain))))
        chain.clear()
        assertEquals(proof, graph.externalCalls.single().declaration)
    }

    @Test
    fun `legacy constructors are unknown and malformed declaration evidence rejects`() {
        assertNull(ExternalCall(caller.id, "lib/Boundary", "m", "()V", InvocationKind.VIRTUAL).declaration)
        assertFailsWith<IllegalArgumentException> { proof.copy(ownerChain = emptyList()) }
        assertFailsWith<IllegalArgumentException> { proof.copy(ownerChain = List(257) { "a/C$it" }) }
        assertFailsWith<IllegalArgumentException> { proof.copy(ownerChain = listOf("p/Base", "p/Base")) }
        assertFailsWith<IllegalArgumentException> { proof.copy(ownerChain = listOf("lib/Boundary", "p/Wrong")) }
        assertFailsWith<IllegalArgumentException> { call.copy(name = "other") }
        assertFailsWith<IllegalArgumentException> { call.copy(descriptor = "(I)V") }
        assertFailsWith<IllegalArgumentException> { call.copy(owner = "p/Other") }
        assertFailsWith<IllegalArgumentException> { call.copy(kind = InvocationKind.INTERFACE) }
    }
}
