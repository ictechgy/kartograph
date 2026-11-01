package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameworkAnnotationRetentionTest {
    @Test
    fun `direct single and repeated preview annotations preserve the same owner policy`() {
        val preview = "androidx/compose/ui/tooling/preview/Preview"
        val owner = node("class:app/ScreenKt", NodeKind.CLASS)
        val sibling = node("method:app/ScreenKt#other()V", NodeKind.FUNCTION)
        for (annotation in listOf(preview, preview + '$' + "Container")) {
            val method = node("method:app/ScreenKt#preview()V", NodeKind.FUNCTION, setOf(annotation))
            val graph = CodeGraph(listOf(owner, method, sibling), listOf(
                GraphEdge(owner.id, method.id, EdgeKind.MEMBER), GraphEdge(owner.id, sibling.id, EdgeKind.MEMBER),
                GraphEdge(method.id, owner.id, EdgeKind.REFERENCE)))
            val evidence = DefaultRetention.find(graph, emptyList(), emptyList())
            assertEquals(true, sibling.id in ReachabilityAnalyzer.analyze(graph, evidence).reachableNodeIds)
        }
    }

    @Test
    fun `project declaration shadows dependency annotation metadata`() {
        val target = node("method:app/Screen#preview()V", NodeKind.FUNCTION, setOf("lib/Devices"))
        val hierarchy = dev.kartograph.core.ClassHierarchy(emptyMap(), emptyMap(),
            mapOf("lib/Devices" to setOf("androidx/compose/ui/tooling/preview/Preview")))
        assertEquals(listOf(target.id), FrameworkAnnotationRetention.find(CodeGraph(listOf(target), emptyList()), hierarchy).map { it.nodeId })
        for (kind in listOf(NodeKind.ANNOTATION_CLASS, NodeKind.CLASS)) {
            val shadow = node("class:lib/Devices", kind)
            assertEquals(emptyList(), FrameworkAnnotationRetention.find(CodeGraph(listOf(target, shadow), emptyList()), hierarchy))
        }
    }

    @Test
    fun `retains transitive repeatable multipreview uses without treating unrelated annotations as roots`() {
        val previewContainer = "androidx/compose/ui/tooling/preview/Preview" + '$' + "Container"
        val devices = node("class:app/Devices", NodeKind.ANNOTATION_CLASS, setOf(previewContainer))
        val nested = node("class:app/NestedPreview", NodeKind.ANNOTATION_CLASS, setOf("app/Devices", "app/Cycle"))
        val cycle = node("class:app/Cycle", NodeKind.ANNOTATION_CLASS, setOf("app/NestedPreview"))
        val facade = node("class:app/ScreenKt", NodeKind.CLASS)
        val preview = node("method:app/ScreenKt#preview()V", NodeKind.FUNCTION,
            setOf("app/Cycle"), SourceLocation("Screen.kt", 7))
        val unused = node("method:app/ScreenKt#unused()V", NodeKind.FUNCTION, setOf("app/Unrelated"))
        val unrelated = node("class:app/Unrelated", NodeKind.ANNOTATION_CLASS)
        val impostor = node("class:app/NotAnAnnotation", NodeKind.CLASS, setOf(previewContainer))
        val notPreview = node("method:app/ScreenKt#notPreview()V", NodeKind.FUNCTION, setOf("app/NotAnAnnotation"))
        val graph = CodeGraph(listOf(devices, nested, cycle, facade, preview, unused, unrelated, impostor, notPreview),
            listOf(GraphEdge(facade.id, preview.id, EdgeKind.MEMBER), GraphEdge(preview.id, facade.id, EdgeKind.REFERENCE)))

        val evidence = FrameworkAnnotationRetention.find(graph)
        assertEquals(RetentionReason.RUNTIME_ENTRY_POINT, evidence.single { it.nodeId == preview.id }.reason)
        assertEquals(SourceLocation("Screen.kt", 7), evidence.single { it.nodeId == preview.id }.location)
        assertEquals(true, facade.id in ReachabilityAnalyzer.analyze(graph, evidence).reachableNodeIds)
        assertEquals(emptyList(), evidence.filter { it.nodeId == facade.id })
        assertEquals(emptyList(), evidence.filter { it.nodeId in setOf(unused.id, unrelated.id, notPreview.id) })
    }

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
        ) + ADDED_FRAMEWORK_REASONS
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

    @Test
    fun `runner lifecycle methods of a base test class keep the helpers they call reachable`() {
        // 추상 base test class에는 @Test가 없어 owner 확장이 닿지 않는다. 생명주기 annotation이 runner 진입점이다.
        for (lifecycle in listOf("org/junit/Before", "org/junit/jupiter/api/BeforeEach")) {
            val base = node("class:app/BaseTest", NodeKind.CLASS)
            val setUp = node("method:app/BaseTest#setUp()V", NodeKind.METHOD, setOf(lifecycle), SourceLocation("BaseTest.kt", 5))
            val screenTest = node("class:app/ScreenTest", NodeKind.CLASS)
            val test = node("method:app/ScreenTest#opens()V", NodeKind.METHOD, setOf("org/junit/Test"))
            val fixtures = node("class:app/Fixtures", NodeKind.CLASS)
            val create = node("method:app/Fixtures#create()V", NodeKind.METHOD)
            val graph = CodeGraph(listOf(base, setUp, screenTest, test, fixtures, create), listOf(
                GraphEdge(base.id, setUp.id, EdgeKind.MEMBER),
                GraphEdge(screenTest.id, test.id, EdgeKind.MEMBER),
                GraphEdge(screenTest.id, base.id, EdgeKind.INHERITANCE),
                GraphEdge(fixtures.id, create.id, EdgeKind.MEMBER),
                GraphEdge(setUp.id, create.id, EdgeKind.CALL),
                GraphEdge(create.id, fixtures.id, EdgeKind.REFERENCE),
            ))

            val evidence = DefaultRetention.find(graph, emptyList(), emptyList())
            val reachable = ReachabilityAnalyzer.analyze(graph, evidence).reachableNodeIds

            assertEquals(RetentionReason.RUNTIME_ENTRY_POINT, evidence.first { it.nodeId == setUp.id }.reason, lifecycle)
            assertEquals(SourceLocation("BaseTest.kt", 5), evidence.first { it.nodeId == setUp.id }.location, lifecycle)
            assertEquals(true, fixtures.id in reachable, lifecycle)
        }
    }

    @Test
    fun `member level JUnit annotations root only the annotated member while test annotations keep the owner policy`() {
        // 생명주기·rule·parameter annotation은 그 member만 runner 진입점이다. owner를 root로 만들면 형제 private helper까지 숨는다.
        val memberOnly = listOf(
            "org/junit/Before", "org/junit/After", "org/junit/BeforeClass", "org/junit/AfterClass",
            "org/junit/Rule", "org/junit/ClassRule",
            "org/junit/runners/Parameterized\$Parameters", "org/junit/runners/Parameterized\$Parameter",
            "org/junit/jupiter/api/BeforeEach", "org/junit/jupiter/api/AfterEach",
            "org/junit/jupiter/api/BeforeAll", "org/junit/jupiter/api/AfterAll",
            "org/junit/jupiter/api/extension/RegisterExtension",
        )
        val ownerPolicy = listOf(
            "org/junit/Test", "org/junit/jupiter/api/Test", "org/junit/jupiter/api/RepeatedTest",
            "org/junit/jupiter/api/TestFactory", "org/junit/jupiter/api/TestTemplate",
            "org/junit/jupiter/params/ParameterizedTest",
        )
        for (annotation in memberOnly + ownerPolicy) {
            val base = node("class:app/BaseTest", NodeKind.CLASS)
            val entry = node("method:app/BaseTest#entry()V", NodeKind.METHOD, setOf(annotation))
            val helper = GraphNode(NodeId("method:app/BaseTest#unusedHelper()V"), "unusedHelper", NodeKind.METHOD,
                visibility = Visibility.PRIVATE)
            val leak = node("class:app/Leak", NodeKind.CLASS)
            val graph = CodeGraph(listOf(base, entry, helper, leak), listOf(
                GraphEdge(base.id, entry.id, EdgeKind.MEMBER),
                GraphEdge(base.id, helper.id, EdgeKind.MEMBER),
                GraphEdge(entry.id, base.id, EdgeKind.REFERENCE),
                GraphEdge(helper.id, base.id, EdgeKind.REFERENCE),
                GraphEdge(helper.id, leak.id, EdgeKind.REFERENCE),
            ))

            val evidence = DefaultRetention.find(graph, emptyList(), emptyList(), includePrivateMembers = true)
            val result = ReachabilityAnalyzer.analyze(graph, evidence)
            val findings = DeadFindings.collect(graph, result, includePrivateMembers = true).map { it.nodeId }

            val ownerReasons = result.retentionEvidenceFor(base.id).map { it.reason }
            assertEquals(true, RetentionReason.RUNTIME_ENTRY_POINT in result.retentionEvidenceFor(entry.id).map { it.reason }, annotation)
            assertEquals(true, base.id in result.reachableNodeIds, annotation)
            if (annotation in memberOnly) {
                assertEquals(false, RetentionReason.RUNTIME_ENTRY_POINT in ownerReasons, annotation)
                assertEquals(listOf(leak.id, helper.id), findings, annotation)
            } else {
                assertEquals(true, RetentionReason.RUNTIME_ENTRY_POINT in ownerReasons, annotation)
                assertEquals(emptyList(), findings, annotation)
            }
        }
    }

    @Test
    fun `every JUnit table entry is member only unless it selects the test class`() {
        // 보존 이유와 owner 정책은 같은 표 항목에 있다. JUnit에서 class를 고르는 것은 @Test 계열뿐이므로
        // 그 밖의 JUnit annotation(생명주기·rule·parameter·extension)은 member만 root여야 한다.
        val selectors = setOf("Test", "RepeatedTest", "TestFactory", "TestTemplate", "ParameterizedTest")
        val junit = FrameworkAnnotationRetention.ROOTS.filterKeys { it.startsWith("org/junit/") }
        assertEquals(ADDED_FRAMEWORK_REASONS.keys.filter { it.startsWith("org/junit/") }.toSet() + setOf(
            "org/junit/Test", "org/junit/jupiter/api/Test"), junit.keys)
        junit.forEach { (annotation, root) ->
            val (reason, retainOwner) = root
            assertEquals(RetentionReason.RUNTIME_ENTRY_POINT, reason, annotation)
            assertEquals(annotation.substringAfterLast('/') in selectors, retainOwner, annotation)
        }
        // JUnit 밖의 지원 annotation은 기존처럼 owner 정책을 쓴다.
        assertEquals(emptyMap(), FrameworkAnnotationRetention.ROOTS.filter { (annotation, root) ->
            !annotation.startsWith("org/junit/") && !root.second
        })
    }

    @Test
    fun `assisted injection and Room converters retain their owners and callees`() {
        val worker = node("class:app/SyncWorker", NodeKind.CLASS)
        val constructor = node("method:app/SyncWorker#<init>()V", NodeKind.CONSTRUCTOR, setOf("dagger/assisted/AssistedInject"))
        val work = node("method:app/SyncWorker#doWork()V", NodeKind.METHOD)
        val converters = node("class:app/Converters", NodeKind.CLASS)
        val fromTimestamp = node("method:app/Converters#fromTimestamp(J)V", NodeKind.METHOD, setOf("androidx/room/TypeConverter"))
        val codec = node("class:app/DateCodec", NodeKind.CLASS)
        val graph = CodeGraph(listOf(worker, constructor, work, converters, fromTimestamp, codec), listOf(
            GraphEdge(worker.id, constructor.id, EdgeKind.MEMBER),
            GraphEdge(worker.id, work.id, EdgeKind.MEMBER),
            GraphEdge(converters.id, fromTimestamp.id, EdgeKind.MEMBER),
            GraphEdge(fromTimestamp.id, codec.id, EdgeKind.REFERENCE),
        ))

        val evidence = DefaultRetention.find(graph, emptyList(), emptyList())
        val reachable = ReachabilityAnalyzer.analyze(graph, evidence).reachableNodeIds

        assertEquals(setOf(RetentionReason.DEPENDENCY_INJECTION), evidence.filter { it.nodeId == worker.id }.map { it.reason }.toSet())
        assertEquals(setOf(RetentionReason.SERIALIZATION), evidence.filter { it.nodeId == converters.id }.map { it.reason }.toSet())
        assertEquals(true, work.id in reachable)
        assertEquals(true, codec.id in reachable)
    }

    private companion object {
        // 0.26.0 이후 보강한 테스트 runner·DI·직렬화 annotation이다. 기존 표와 같은 owner 정책을 따른다.
        val ADDED_FRAMEWORK_REASONS = buildMap {
            listOf(
                "dagger/assisted/AssistedInject",
                "dagger/assisted/AssistedFactory",
                "dagger/Component",
                "dagger/Component\$Builder",
                "dagger/Component\$Factory",
                "dagger/Subcomponent",
                "dagger/Subcomponent\$Builder",
                "dagger/Subcomponent\$Factory",
                "dagger/hilt/EntryPoint",
                "androidx/hilt/work/HiltWorker",
            ).forEach { put(it, RetentionReason.DEPENDENCY_INJECTION) }
            listOf(
                "com/google/gson/annotations/Expose",
                "androidx/room/TypeConverter",
            ).forEach { put(it, RetentionReason.SERIALIZATION) }
            listOf(
                "org/junit/Before",
                "org/junit/After",
                "org/junit/BeforeClass",
                "org/junit/AfterClass",
                "org/junit/Rule",
                "org/junit/ClassRule",
                "org/junit/runners/Parameterized\$Parameters",
                "org/junit/runners/Parameterized\$Parameter",
                "org/junit/jupiter/api/BeforeEach",
                "org/junit/jupiter/api/AfterEach",
                "org/junit/jupiter/api/BeforeAll",
                "org/junit/jupiter/api/AfterAll",
                "org/junit/jupiter/api/RepeatedTest",
                "org/junit/jupiter/api/TestFactory",
                "org/junit/jupiter/api/TestTemplate",
                "org/junit/jupiter/api/extension/RegisterExtension",
                "org/junit/jupiter/params/ParameterizedTest",
            ).forEach { put(it, RetentionReason.RUNTIME_ENTRY_POINT) }
        }
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
