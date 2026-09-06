package dev.kartograph.analysis

import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmEntryPointTest {
    private fun node(
        name: String = "main",
        kind: NodeKind = NodeKind.METHOD,
        modifiers: Set<JvmModifier> = setOf(JvmModifier.STATIC),
        jvmVisibility: Visibility = Visibility.PUBLIC,
        synthesized: Boolean = false,
    ) = GraphNode(NodeId("method:FacadeKt#$name()V"), name, kind,
        jvmModifiers = modifiers, jvmVisibility = jvmVisibility, synthesized = synthesized)

    @Test
    fun `public static main of any descriptor is the launcher entry point`() {
        assertTrue(JvmEntryPoint.isLauncherMain(node()))
        assertTrue(JvmEntryPoint.isLauncherMain(
            GraphNode(NodeId("method:FacadeKt#main(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
                "main", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.STATIC), jvmVisibility = Visibility.PUBLIC),
        ))
    }

    @Test
    fun `non-static, non-public, synthetic, non-main and constructors are not entry points`() {
        assertFalse(JvmEntryPoint.isLauncherMain(node(modifiers = emptySet())))
        assertFalse(JvmEntryPoint.isLauncherMain(node(jvmVisibility = Visibility.PRIVATE)))
        assertFalse(JvmEntryPoint.isLauncherMain(node(synthesized = true)))
        assertFalse(JvmEntryPoint.isLauncherMain(node(name = "mainish")))
        assertFalse(JvmEntryPoint.isLauncherMain(node(kind = NodeKind.CONSTRUCTOR)))
    }
}
