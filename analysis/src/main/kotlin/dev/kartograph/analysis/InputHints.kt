package dev.kartograph.analysis

import dev.kartograph.core.InputHint
import dev.kartograph.core.CodeGraph

/**
 * 색인된 그래프에 들어가지 않은 보존 입력을 판정한다.
 * 개수는 호출자가 실제로 전달한 입력(파일·경로·manifest 근거) 수이고, 0이면 그 입력의 보존 효과를
 * 확인하지 못했다는 뜻이다. 어느 쪽도 finding이 아니므로 호출자가 보고 대상을 결정한다.
 */
public object InputHints {
    public fun detect(
        keepRuleInputs: Int,
        classpathInputs: Int,
        manifestEvidenceCount: Int,
    ): List<InputHint> = buildList {
        if (keepRuleInputs == 0) add(InputHint.MISSING_KEEP_RULES)
        if (classpathInputs == 0) add(InputHint.MISSING_CLASSPATH)
        if (manifestEvidenceCount == 0) add(InputHint.MANIFEST_WITHOUT_COMPONENTS)
    }

    /** 선택된 classfile에서 확인한 Android 변환·생성 출처의 불확실성을 추가한다. */
    public fun detect(
        keepRuleInputs: Int,
        classpathInputs: Int,
        manifestEvidenceCount: Int,
        graph: CodeGraph,
    ): List<InputHint> = buildList {
        addAll(detect(keepRuleInputs, classpathInputs, manifestEvidenceCount))
        if (graph.nodes.values.any { node ->
            node.annotations.any { it in HILT_ENTRY_ANNOTATIONS } &&
                node.jvmSignature?.takeIf { '#' !in it }?.let { name ->
                    val prefix = name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    val generated = prefix + "Hilt_" + name.substringAfterLast('/').replace('$', '_')
                    generated !in node.supertypes
                } == true
        }) add(InputHint.HILT_TRANSFORM_NOT_OBSERVED)
        if (graph.nodes.values.any { !it.synthesized && it.supertypes.any(ANDROID_GENERATED_BASES::contains) }) {
            add(InputHint.ANDROID_GENERATED_PROVENANCE_UNVERIFIED)
        }
        if (graph.nodes.values.any { "androidx/databinding/BindingAdapter" in it.annotations ||
                "android/databinding/BindingAdapter" in it.annotations || it.supertypes.any(BINDING_BASES::contains) }) {
            add(InputHint.DATA_BINDING_SELECTION_PARTIAL)
        }
    }

    private val HILT_ENTRY_ANNOTATIONS = setOf("dagger/hilt/android/AndroidEntryPoint", "dagger/hilt/android/HiltAndroidApp")
    private val BINDING_BASES = setOf("androidx/databinding/ViewDataBinding", "android/databinding/ViewDataBinding",
        "androidx/databinding/DataBinderMapper", "android/databinding/DataBinderMapper")
    private val ANDROID_GENERATED_BASES = BINDING_BASES + setOf("androidx/navigation/NavArgs", "androidx/navigation/NavDirections")
}
