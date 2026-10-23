package dev.kartograph.core

/** 실제 classfile 어노테이션 값이다. 속성 매칭은 runtime 호출을 증명하지 않는다. */
public data class AndroidBindingAdapter(
    val method: NodeId,
    val attributes: List<String>,
    val requireAll: Boolean,
    val parameterTypes: List<String>,
    val viewType: String?,
    val supportedSignature: Boolean,
)

/** 한 layout의 한 View에서 관측한 binding expression 속성 집합이다. 다른 element와 합치지 않는다. */
public data class AndroidBindingElement(
    val location: SourceLocation,
    val resourcePath: String,
    val root: String,
    val ordinal: Int,
    val viewType: String?,
    val boundAttributes: Set<String>,
    val declaredValueTypes: Map<String, String?>,
    val sourceSha256: String,
)

/** 명시 overlay 순서도 사용자의 입력 assertion이다. 클래스 빌드와 variant의 일치를 증명하지 않는다. */
public data class AndroidBindingObservations(
    val adapters: List<AndroidBindingAdapter>,
    val elements: List<AndroidBindingElement>,
    val roots: List<String>,
    val overlayOrder: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    /** binding 표현식이 없는 override도 낮은 우선순위 정의를 가릴 수 있다. */
    val layouts: List<AndroidBindingLayout> = emptyList(),
)

public data class AndroidBindingLayout(val root: String, val resourcePath: String, val sourceSha256: String)
