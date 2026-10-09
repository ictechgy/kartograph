package dev.kartograph.core

/** 그래프 정점이 나타내는 JVM/Kotlin 선언의 종류다. */
public enum class NodeKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    ANNOTATION_CLASS,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    PROPERTY,
    FIELD,
}

/** Kotlin과 Java 선언의 source visibility를 공통 값으로 보관한다. */
public enum class Visibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PACKAGE_PRIVATE,
    PRIVATE,
    PRIVATE_TO_THIS,
    LOCAL,
    UNKNOWN,
}

/** JVM access flag만으로 복원할 수 없는 선언 특징과 명시적 입력 출처다. */
public enum class NodeAttribute {
    DATA_CLASS,
    EXTENSION_FUNCTION,
    COMPILE_TIME_CONSTANT,
    INLINE_FUNCTION,
    FILE_FACADE,
    PROPERTY_ACCESSOR,
    GENERATED_INPUT,
    /** 그래프 내보내기를 위해 참조 identity만 붙인 외부 선언이다. 프로젝트 구현으로 취급하지 않는다. */
    EXTERNAL_STUB,
    /** compiler line이 없을 때 현재 project source header에서 유일하게 보강한 선언 위치다. */
    SOURCE_DECLARATION_LOCATION,
}

/** ProGuard/R8 class specification과 직접 비교하는 JVM access flag다. */
public enum class JvmModifier {
    FINAL,
    ABSTRACT,
    SYNTHETIC,
    NATIVE,
    STATIC,
}

/** 원본 파일을 확정할 수 있을 때만 존재하는 source 위치다. */
public data class SourceLocation(
    val path: String,
    val line: Int? = null,
    val column: Int? = null,
) {
    init {
        require(path.isNotBlank()) { "source path must not be blank" }
        require(line == null || line > 0) { "source line must be positive" }
        require(column == null || column > 0) { "source column must be positive" }
    }
}

/**
 * JVM identity를 정본으로 유지하면서 선택적인 Kotlin source 사실을 함께 보관하는
 * 그래프 정점이다.
 * 위치를 복원하지 못한 경우에도 정점을 버리지 않는다.
 */
public data class GraphNode(
    val id: NodeId,
    val name: String,
    val kind: NodeKind,
    val moduleName: String? = null,
    val jvmSignature: String? = null,
    val location: SourceLocation? = null,
    val visibility: Visibility = Visibility.UNKNOWN,
    val jvmVisibility: Visibility = visibility,
    val jvmModifiers: Set<JvmModifier> = emptySet(),
    val attributes: Set<NodeAttribute> = emptySet(),
    val annotations: Set<String> = emptySet(),
    val supertypes: Set<String> = emptySet(),
    val extensionReceiverType: String? = null,
    val synthesized: Boolean = false,
    /** 현재 source header의 독립 위치다. 기존 compiler 위치와 신선도 의미를 바꾸지 않는다. */
    val sourceDeclaration: SourceDeclarationEvidence? = null,
    /** Kotlin metadata가 JVM signature에 대응시킨 원래 함수 이름이다. 바이트코드 이름과 구분한다. */
    val kotlinSourceName: String? = null,
) {
    init {
        require(name.isNotBlank()) { "node name must not be blank" }
        require(kotlinSourceName == null || kotlinSourceName.isNotBlank()) { "Kotlin source name must not be blank" }
    }
}
