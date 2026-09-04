package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameworkAnnotationRetentionTest {
    @Test
    fun `retains DI members with their owner and serialization classes`() {
        val owner = node("class:dev/fixture/Injected", NodeKind.CLASS)
        val constructor = node(
            "method:dev/fixture/Injected#<init>()V",
            NodeKind.CONSTRUCTOR,
            annotations = setOf("javax/inject/Inject"),
            location = SourceLocation("Injected.kt", 3),
        )
        val serializable = node(
            "class:dev/fixture/Payload",
            NodeKind.CLASS,
            annotations = setOf("kotlinx/serialization/Serializable"),
        )
        val ignored = node(
            "class:dev/fixture/Ignored",
            NodeKind.CLASS,
            annotations = setOf("dev/fixture/Unrelated"),
        )
        val graph = CodeGraph(
            nodes = listOf(owner, constructor, serializable, ignored),
            edges = listOf(GraphEdge(owner.id, constructor.id, EdgeKind.MEMBER)),
        )

        val evidence = FrameworkAnnotationRetention.find(graph)

        assertEquals(
            listOf(
                owner.id to RetentionReason.DEPENDENCY_INJECTION,
                serializable.id to RetentionReason.SERIALIZATION,
                constructor.id to RetentionReason.DEPENDENCY_INJECTION,
            ),
            evidence.map { it.nodeId to it.reason },
        )
        assertEquals(SourceLocation("Injected.kt", 3), evidence.first().location)
    }

    @Test
    fun `maps every supported framework annotation to a stable reason`() {
        val expectedReasons = mapOf(
            "javax/inject/Inject" to RetentionReason.DEPENDENCY_INJECTION,
            "jakarta/inject/Inject" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/Provides" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/Binds" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/Module" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/Multibinds" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/BindsOptionalOf" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/hilt/InstallIn" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/hilt/android/AndroidEntryPoint" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/hilt/android/HiltAndroidApp" to RetentionReason.DEPENDENCY_INJECTION,
            "dagger/hilt/android/lifecycle/HiltViewModel" to RetentionReason.DEPENDENCY_INJECTION,
            "kotlinx/serialization/Serializable" to RetentionReason.SERIALIZATION,
            "com/google/gson/annotations/SerializedName" to RetentionReason.SERIALIZATION,
            "com/squareup/moshi/Json" to RetentionReason.SERIALIZATION,
            "com/squareup/moshi/JsonClass" to RetentionReason.SERIALIZATION,
            "kotlinx/parcelize/Parcelize" to RetentionReason.SERIALIZATION,
            "kotlinx/android/parcel/Parcelize" to RetentionReason.SERIALIZATION,
            "androidx/room/Entity" to RetentionReason.SERIALIZATION,
            "androidx/room/Dao" to RetentionReason.SERIALIZATION,
            "androidx/room/Database" to RetentionReason.SERIALIZATION,
            "android/webkit/JavascriptInterface" to RetentionReason.RUNTIME_ENTRY_POINT,
            "androidx/compose/ui/tooling/preview/Preview" to RetentionReason.RUNTIME_ENTRY_POINT,
            "org/junit/Test" to RetentionReason.RUNTIME_ENTRY_POINT,
            "org/junit/jupiter/api/Test" to RetentionReason.RUNTIME_ENTRY_POINT,
            "org/robolectric/annotation/Config" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/DELETE" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/GET" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/HEAD" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/HTTP" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/OPTIONS" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/PATCH" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/POST" to RetentionReason.RUNTIME_ENTRY_POINT,
            "retrofit2/http/PUT" to RetentionReason.RUNTIME_ENTRY_POINT,
        )
        val graph = CodeGraph(
            nodes = expectedReasons.keys.map { annotation ->
                node(
                    id = "class:fixture/${annotation.replace('/', '_')}",
                    kind = NodeKind.CLASS,
                    annotations = setOf(annotation),
                )
            },
            edges = emptyList(),
        )

        val actualReasons = FrameworkAnnotationRetention.find(graph).associate { evidence ->
            graph.nodes.getValue(evidence.nodeId).annotations.single() to evidence.reason
        }

        assertEquals(expectedReasons, actualReasons)
    }

    private fun node(
        id: String,
        kind: NodeKind,
        annotations: Set<String> = emptySet(),
        location: SourceLocation? = null,
    ): GraphNode = GraphNode(
        id = NodeId(id),
        name = id.substringAfterLast('/'),
        kind = kind,
        annotations = annotations,
        location = location,
    )
}
