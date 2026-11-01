package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.NodeKind
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.NodeId

/** Android DI·직렬화·테스트 runner 생태계가 runtime 또는 generated code로 소비하는 선언을 보존한다. */
public object FrameworkAnnotationRetention {
    /** 알려진 framework annotation이 붙은 선언과 소유 선언을 근거와 함께 반환한다. */
    public fun find(graph: CodeGraph): List<RetentionEvidence> = find(graph, ClassHierarchy.EMPTY)

    /** dependency에 정의된 multipreview도 선언 header를 통해 보존하되 app 정점을 늘리지 않는다. */
    public fun find(graph: CodeGraph, hierarchy: ClassHierarchy): List<RetentionEvidence> {
        // 반복 @Preview의 JVM container와 프로젝트의 중첩 multipreview만 확장한다.
        // 다른 framework annotation 전체를 전이 보존으로 넓히지 않는다.
        val previewNames = DIRECT_PREVIEWS.toMutableSet()
        val types = hierarchy.annotationTypes.filterKeys { !graph.contains(NodeId("class:$it")) } +
            graph.nodes.values.filter { it.kind == NodeKind.ANNOTATION_CLASS }
                .associate { it.id.value.removePrefix("class:") to it.annotations }
        val annotatedTypes = types.flatMap { (name, annotations) -> annotations.map { it to name } }
            .groupBy({ it.first }, { it.second })
        val pending = ArrayDeque(previewNames)
        while (pending.isNotEmpty()) {
            annotatedTypes[pending.removeFirst()].orEmpty().forEach { annotation ->
                if (previewNames.add(annotation)) pending.addLast(annotation)
            }
        }
        // 사용자 정의 multipreview는 method만 root로 만든다. 직접 Preview는 개수와 무관하게 owner 정책을 유지한다.
        // owner를 새 runtime root로 만들면 무관한 sibling method까지 확장된다.
        val previews = annotationRetentionEvidence(graph,
            (previewNames - DIRECT_PREVIEWS).associateWith { RetentionReason.RUNTIME_ENTRY_POINT }, includeOwners = false)
        return (annotationRetentionEvidence(graph, REASONS, memberOnlyAnnotations = MEMBER_ONLY) + previews)
            .distinct().sortedWith(RETENTION_ORDER)
    }

    // JUnit runner가 member 단위로 부르는 생명주기·rule·parameter annotation이다. class 선택은 @Test 계열이 맡으므로
    // owner를 runtime root로 넓히지 않는다. owner는 member의 owner 참조 간선으로 도달한다.
    private val MEMBER_ONLY = setOf(
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
        "org/junit/jupiter/api/extension/RegisterExtension",
    )

    private const val PREVIEW = "androidx/compose/ui/tooling/preview/Preview"
    private val DIRECT_PREVIEWS = setOf(PREVIEW, PREVIEW + '$' + "Container")

    private val REASONS = buildMap {
        listOf(
            "javax/inject/Inject",
            "jakarta/inject/Inject",
            "dagger/Provides",
            "dagger/Binds",
            "dagger/Module",
            "dagger/Multibinds",
            "dagger/BindsOptionalOf",
            "dagger/hilt/InstallIn",
            "dagger/hilt/android/AndroidEntryPoint",
            "dagger/hilt/android/HiltAndroidApp",
            "dagger/hilt/android/lifecycle/HiltViewModel",
            // assisted 생성자·factory와 component 계열은 Dagger가 생성한 구현이 호출한다.
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
        ).forEach { annotation -> put(annotation, RetentionReason.DEPENDENCY_INJECTION) }
        listOf(
            "kotlinx/serialization/Serializable",
            "com/google/gson/annotations/SerializedName",
            "com/squareup/moshi/Json",
            "com/squareup/moshi/JsonClass",
            "kotlinx/parcelize/Parcelize",
            "kotlinx/android/parcel/Parcelize",
            "androidx/room/Entity",
            "androidx/room/Dao",
            "androidx/room/Database",
            // Gson의 노출 field와 Room 생성 코드가 부르는 converter method다.
            "com/google/gson/annotations/Expose",
            "androidx/room/TypeConverter",
        ).forEach { annotation -> put(annotation, RetentionReason.SERIALIZATION) }
        listOf(
            "android/webkit/JavascriptInterface",
            "androidx/compose/ui/tooling/preview/Preview",
            "org/junit/Test",
            "org/junit/jupiter/api/Test",
            // JUnit runner가 reflection으로 부르는 생명주기·rule·parameter·변형 test 진입점이다.
            // @Test가 없는 base test class의 member도 이 annotation으로 root가 된다. owner 정책은 MEMBER_ONLY를 따른다.
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
            "org/robolectric/annotation/Config",
            "retrofit2/http/DELETE",
            "retrofit2/http/GET",
            "retrofit2/http/HEAD",
            "retrofit2/http/HTTP",
            "retrofit2/http/OPTIONS",
            "retrofit2/http/PATCH",
            "retrofit2/http/POST",
            "retrofit2/http/PUT",
        ).forEach { annotation -> put(annotation, RetentionReason.RUNTIME_ENTRY_POINT) }
        DIRECT_PREVIEWS.forEach { put(it, RetentionReason.RUNTIME_ENTRY_POINT) }
    }
}
