package dev.kartograph.analysis

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionReason
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidEntryPointRetentionTest {
    @Test
    fun `retains native method owner and direct WorkManager subclasses`() {
        val bridge = node("class:fixture/Bridge", "Bridge")
        val nativeMethod = GraphNode(
            id = NodeId("method:fixture/Bridge#dispatch(J)I"),
            name = "dispatch",
            kind = NodeKind.METHOD,
            jvmModifiers = setOf(JvmModifier.NATIVE),
        )
        val worker = node(
            "class:fixture/UploadWorker",
            "UploadWorker",
            supertypes = setOf("androidx/work/CoroutineWorker"),
        )
        val graph = CodeGraph(
            listOf(bridge, nativeMethod, worker),
            listOf(GraphEdge(bridge.id, nativeMethod.id, EdgeKind.MEMBER)),
        )

        val evidence = AndroidEntryPointRetention.find(graph)

        assertEquals(
            listOf(bridge.id, worker.id, nativeMethod.id),
            evidence.map { it.nodeId },
        )
        assertEquals(setOf(RetentionReason.RUNTIME_ENTRY_POINT), evidence.map { it.reason }.toSet())
    }

    @Test
    fun `retains transitive WorkManager subclasses across project and dependency hierarchy`() {
        val projectBase = node(
            "class:fixture/BaseWorker",
            "BaseWorker",
            supertypes = setOf("androidx/work/Worker"),
        )
        val projectWorker = node(
            "class:fixture/ProjectWorker",
            "ProjectWorker",
            supertypes = setOf("fixture/BaseWorker"),
        )
        val dependencyWorker = node(
            "class:fixture/DependencyWorker",
            "DependencyWorker",
            supertypes = setOf("library/BaseWorker"),
        )
        val graph = CodeGraph(listOf(projectBase, projectWorker, dependencyWorker), emptyList())
        val hierarchy = ClassHierarchy(mapOf("library/BaseWorker" to setOf("androidx/work/ListenableWorker")))

        val evidence = AndroidEntryPointRetention.find(graph, hierarchy)

        assertEquals(
            listOf(projectBase.id, dependencyWorker.id, projectWorker.id),
            evidence.map { it.nodeId },
        )
    }

    private fun node(
        id: String,
        name: String,
        supertypes: Set<String> = emptySet(),
    ): GraphNode = GraphNode(
        NodeId(id),
        name,
        NodeKind.CLASS,
        jvmSignature = id.removePrefix("class:"),
        supertypes = supertypes,
    )
}
