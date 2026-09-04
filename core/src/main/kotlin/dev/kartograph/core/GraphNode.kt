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

/** JVM access flag만으로 복원할 수 없는 Kotlin 선언 사실이다. */
public enum class NodeAttribute {
    DATA_CLASS,
    EXTENSION_FUNCTION,
    COMPILE_TIME_CONSTANT,
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
) {
    init {
        require(name.isNotBlank()) { "node name must not be blank" }
    }
}
