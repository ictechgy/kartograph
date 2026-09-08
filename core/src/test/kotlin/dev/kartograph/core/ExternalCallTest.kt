package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalCallTest {
    @Test
    fun `keeps distinct external call sites without adding dependency nodes`() {
        val source = NodeId("method:a/A#run()V")
        val target = NodeId("method:a/A#local()V")
        val nodes = listOf(GraphNode(source, "run", NodeKind.METHOD), GraphNode(target, "local", NodeKind.METHOD))
        val first = ExternalCall(source, "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE)
        val second = first.copy(ordinal = 1)
        val local = ExternalCall(source, "a/A", "local", "()V", InvocationKind.VIRTUAL)
        val unknownSource = first.copy(caller = NodeId("method:missing/M#run()V"))
        val graph = CodeGraph(nodes, emptyList(), listOf(second, first, first, local, unknownSource))
        assertEquals(2, graph.nodeCount)
        assertEquals(0, graph.edgeCount)
        assertEquals(listOf(first, second), graph.externalCalls)
    }

    @Test
    fun `copies and sorts resolved project targets`() {
        val caller = NodeId("method:a/A#run()V")
        val targets = mutableListOf(caller, caller, NodeId("method:missing/M#run()V"))
        val call = ExternalCall(caller, "java/lang/Runnable", "run", "()V", InvocationKind.INTERFACE, resolvedTargets = targets)
        val graph = CodeGraph(listOf(GraphNode(caller, "run", NodeKind.METHOD)), emptyList(), listOf(call))
        targets.clear()
        assertEquals(listOf(caller), graph.externalCalls.single().resolvedTargets)
    }
}
