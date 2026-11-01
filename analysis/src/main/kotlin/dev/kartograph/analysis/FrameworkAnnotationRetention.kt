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

    private const val PREVIEW = "androidx/compose/ui/tooling/preview/Preview"
    private val DIRECT_PREVIEWS = setOf(PREVIEW, PREVIEW + '$' + "Container")
    private const val RETAIN_OWNER = true
    private const val MEMBER_ONLY_ROOT = false

    /**
     * 지원 annotation 표다. 각 항목이 보존 이유와 owner 정책을 함께 가진다. 정책이 참이면 annotation이 붙은 선언의
     * owner 사슬까지 root가 되고 runtime 확장으로 그 직접 member도 보존된다. 거짓이면 그 member만 root이고 owner는
     * member의 owner 참조 간선으로 도달한다. owner 정책을 별도 목록으로 두지 않아 새 항목이 정책 없이 들어올 수 없다.
     */
    internal val ROOTS: Map<String, Pair<RetentionReason, Boolean>> = buildMap {
        roots(
            RetentionReason.DEPENDENCY_INJECTION, RETAIN_OWNER,
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
        )
        roots(
            RetentionReason.SERIALIZATION, RETAIN_OWNER,
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
        )
        roots(
            RetentionReason.RUNTIME_ENTRY_POINT, RETAIN_OWNER,
            "android/webkit/JavascriptInterface",
            *DIRECT_PREVIEWS.toTypedArray(),
            // JUnit에서 test class를 고르는 annotation은 owner 정책을 유지한다.
            "org/junit/Test",
            "org/junit/jupiter/api/Test",
            "org/junit/jupiter/api/RepeatedTest",
            "org/junit/jupiter/api/TestFactory",
            "org/junit/jupiter/api/TestTemplate",
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
        )
        roots(
            // JUnit runner가 member 단위로 부르는 생명주기·rule·parameter·extension annotation이다.
            // @Test가 없는 base test class의 member도 root가 되지만 형제 member로 넓히지 않는다.
            RetentionReason.RUNTIME_ENTRY_POINT, MEMBER_ONLY_ROOT,
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
    }

    private val REASONS = ROOTS.mapValues { (_, root) -> root.first }
    private val MEMBER_ONLY = ROOTS.filterValues { (_, retainOwner) -> !retainOwner }.keys

    // 같은 annotation을 두 정책으로 등록하지 못하게 중복을 초기화 시점에 거부한다.
    private fun MutableMap<String, Pair<RetentionReason, Boolean>>.roots(
        reason: RetentionReason,
        retainOwner: Boolean,
        vararg annotations: String,
    ) {
        annotations.forEach { annotation ->
            require(put(annotation, reason to retainOwner) == null) { "duplicate framework annotation root: $annotation" }
        }
    }
}
