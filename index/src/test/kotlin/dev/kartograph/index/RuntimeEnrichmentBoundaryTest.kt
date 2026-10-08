package dev.kartograph.index

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode

class RuntimeEnrichmentBoundaryTest {
    @Test
    fun `runtime enrichment validates combined base and derived edge weight`() {
        val caller = JvmNodeId.methodId("app/Caller", "load", "()V")
        val target = JvmNodeId.classId("app/Impl")
        val call = ExternalCall(
            caller,
            "java/lang/Class",
            "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            InvocationKind.STATIC,
        )
        val nodes = listOf(
            GraphNode(caller, "load", NodeKind.METHOD),
            GraphNode(target, "Impl", NodeKind.CLASS, jvmSignature = "app/Impl"),
        )
        val facts = listOf(
            ClassFacts("app/Caller", emptyList(), emptyList(), runtimeMethods = listOf(runtimeForName())),
        )
        val empty = CodeGraph(nodes, emptyList(), listOf(call))
        val (derived, _) = RuntimeValueAnalyzer.enrich(empty, facts, ClassHierarchy.EMPTY)
        assertEquals(1, derived.edges.single { it.target == target }.weight)
        assertEquals(EdgeOrigin.RUNTIME_MODEL, derived.edges.single { it.target == target }.origin)

        val seeded = CodeGraph(
            nodes,
            listOf(GraphEdge(caller, target, EdgeKind.REFERENCE, weight = Int.MAX_VALUE, origin = EdgeOrigin.RUNTIME_MODEL)),
            listOf(call),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            RuntimeValueAnalyzer.enrich(seeded, facts, ClassHierarchy.EMPTY)
        }
        assertEquals("edge weight must be positive", error.message)
    }

    private fun runtimeForName(): MethodNode = MethodNode(
        Opcodes.ASM9,
        Opcodes.ACC_STATIC,
        "load",
        "()V",
        null,
        null,
    ).apply {
        visitCode()
        visitLdcInsn("app.Impl")
        visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Class",
            "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            false,
        )
        visitInsn(Opcodes.POP)
        visitInsn(Opcodes.RETURN)
        visitMaxs(1, 0)
        visitEnd()
    }
}
