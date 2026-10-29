package dev.kartograph.core

import kotlin.test.*

class DirectInterfaceDeclarationTest {
    private val caller = GraphNode(NodeId("method:p/Caller#run()V"), "run", NodeKind.METHOD)
    private val proof = CallDeclaration(NodeId("method:lib/I#m()V"), CallDeclarationScope.EXTERNAL, listOf("lib/I"))
    private fun call() = ExternalCall(caller.id, "lib/I", "m", "()V", InvocationKind.INTERFACE, declaration = proof)

    @Test
    fun `interface opcode identifies direct proof and graph isolates mutable chain`() {
        val chain = mutableListOf("lib/I")
        val graph = CodeGraph(listOf(caller), emptyList(), listOf(call().copy(declaration = proof.copy(ownerChain = chain))))
        chain.clear()
        assertEquals(proof, graph.externalCalls.single().declaration)
        assertEquals(InvocationKind.INTERFACE, graph.externalCalls.single().kind)
        assertEquals(setOf(caller.id), graph.nodes.keys)
        assertTrue(graph.edges.isEmpty())
        assertNull(call().copy(declaration = null).declaration)
    }

    @Test
    fun `interface proofs cannot assert inherited chain or malformed method operand`() {
        assertFailsWith<IllegalArgumentException> { call().copy(declaration = proof.copy(
            target = NodeId("method:lib/Ancestor#m()V"), ownerChain = listOf("lib/I", "lib/Ancestor"))) }
        for ((name, descriptor) in listOf("<init>" to "()V", "m" to "(V)V", "m" to "()VV", "m" to "(L;)V")) {
            assertFailsWith<IllegalArgumentException> { ExternalCall(caller.id, "lib/I", name, descriptor, InvocationKind.INTERFACE,
                declaration = proof.copy(target = NodeId("method:lib/I#$name$descriptor"))) }
        }
        // 길이 하나인 기존 class proof는 여전히 virtual/static/special opcode가 의미를 구분한다.
        assertEquals(proof, call().copy(kind = InvocationKind.VIRTUAL).declaration)
    }

    @Test
    fun `selected project owner invalidates external proof and pruning never promotes scope`() {
        val type = GraphNode(NodeId("class:lib/I"), "I", NodeKind.INTERFACE, jvmSignature = "lib/I")
        for (kind in listOf(NodeKind.INTERFACE, NodeKind.CLASS)) {
            val graph = CodeGraph(listOf(caller, type.copy(kind = kind)), emptyList(), listOf(call()))
            assertNull(graph.externalCalls.single().declaration)
        }
        val stub = type.copy(kind = NodeKind.CLASS, attributes = setOf(NodeAttribute.EXTERNAL_STUB))
        assertEquals(proof, CodeGraph(listOf(caller, stub), emptyList(), listOf(call())).externalCalls.single().declaration)
        val project = call().copy(declaration = proof.copy(scope = CallDeclarationScope.PROJECT))
        assertNull(CodeGraph(listOf(caller), emptyList(), listOf(project)).externalCalls.single().declaration)
        val target = GraphNode(proof.target, "m", NodeKind.METHOD)
        assertTrue(CodeGraph(listOf(caller, type, target), emptyList(), listOf(project)).externalCalls.isEmpty())
    }
}
