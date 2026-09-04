package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** Android DI와 직렬화 생태계가 runtime 또는 generated code로 소비하는 선언을 보존한다. */
public object FrameworkAnnotationRetention {
    /** 알려진 framework annotation이 붙은 선언과 소유 선언을 근거와 함께 반환한다. */
    public fun find(graph: CodeGraph): List<RetentionEvidence> = annotationRetentionEvidence(graph, REASONS)

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
        ).forEach { annotation -> put(annotation, RetentionReason.SERIALIZATION) }
        listOf(
            "android/webkit/JavascriptInterface",
            "androidx/compose/ui/tooling/preview/Preview",
            "org/junit/Test",
            "org/junit/jupiter/api/Test",
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
    }
}
