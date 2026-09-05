package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionReason
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedSiblingRetentionTest {
    @Test
    fun `Hilt component roots retain only siblings of the annotated application`() {
        val graph = CodeGraph(nodes = listOf(
            node("dev/App", annotations = setOf("dagger/hilt/android/HiltAndroidApp")),
            node("dev/Hilt_App"), node("dev/App_HiltComponents"), node("dev/DaggerApp_HiltComponents_SingletonC"),
            node("dev/DaggerUnrelated_HiltComponents_SingletonC"),
        ), edges = emptyList())
        assertEquals(listOf(
            NodeId("class:dev/App_HiltComponents"), NodeId("class:dev/DaggerApp_HiltComponents_SingletonC"),
            NodeId("class:dev/Hilt_App"),
        ), GeneratedSiblingRetention.find(graph).map { it.nodeId })
    }

    @Test
    fun `retains Moshi and Room generated siblings named from annotated declarations`() {
        val graph = CodeGraph(
            nodes = listOf(
                node("dev/model/Message", annotations = setOf("com/squareup/moshi/JsonClass")),
                node("dev/model/MessageJsonAdapter", synthesized = true),
                node("dev/model/Envelope\$Item", annotations = setOf("com/squareup/moshi/JsonClass")),
                node("dev/model/Envelope_ItemJsonAdapter", synthesized = true),
                node("dev/db/AppDatabase", annotations = setOf("androidx/room/Database")),
                node("dev/db/AppDatabase_Impl", synthesized = true),
                node("dev/model/UnrelatedJsonAdapter", synthesized = true),
            ),
            edges = emptyList(),
        )

        val evidence = GeneratedSiblingRetention.find(graph)

        assertEquals(
            listOf(
                NodeId("class:dev/db/AppDatabase_Impl"),
                NodeId("class:dev/model/Envelope_ItemJsonAdapter"),
                NodeId("class:dev/model/MessageJsonAdapter"),
            ),
            evidence.map { it.nodeId },
        )
        assertEquals(setOf(RetentionReason.GENERATED_CODE), evidence.map { it.reason }.toSet())
    }

    private fun node(
        internalName: String,
        annotations: Set<String> = emptySet(),
        synthesized: Boolean = false,
    ): GraphNode = GraphNode(
        id = NodeId("class:$internalName"),
        name = internalName.substringAfterLast('/'),
        kind = NodeKind.CLASS,
        jvmSignature = internalName,
        annotations = annotations,
        synthesized = synthesized,
    )
}
