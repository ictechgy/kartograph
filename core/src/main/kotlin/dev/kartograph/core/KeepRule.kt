package dev.kartograph.core

/** ProGuard/R8 class specification이 제한하는 JVM 선언 종류다. */
public enum class KeepDeclarationKind {
    CLASS,
    INTERFACE,
    ENUM,
}

/** 조건부 keep class rule이 요구하는 member 선언 종류다. */
public enum class KeepMemberKind {
    METHODS,
    FIELDS,
    CONSTRUCTORS,
}

/** class를 root로 만들기 위해 존재해야 하는 member specification이다. */
public data class KeepMemberCondition(
    val kind: KeepMemberKind,
    val requiredAnnotationPattern: String? = null,
    val requiredJvmVisibilities: Set<Visibility> = emptySet(),
    val forbiddenJvmVisibilities: Set<Visibility> = emptySet(),
    val requiredJvmModifiers: Set<JvmModifier> = emptySet(),
    val forbiddenJvmModifiers: Set<JvmModifier> = emptySet(),
    val namePattern: String? = null,
    val jvmDescriptor: String? = null,
)

/** 파일 파싱과 그래프 적용을 분리하기 위한 keep class specification 값이다. */
public data class KeepRule(
    val declarationKind: KeepDeclarationKind,
    val classNamePattern: String,
    val extendsPattern: String? = null,
    val location: SourceLocation,
    val requiredJvmVisibilities: Set<Visibility> = emptySet(),
    val forbiddenJvmVisibilities: Set<Visibility> = emptySet(),
    val requiredJvmModifiers: Set<JvmModifier> = emptySet(),
    val forbiddenJvmModifiers: Set<JvmModifier> = emptySet(),
    val requiredAnnotationPattern: String? = null,
    val memberConditions: List<KeepMemberCondition> = emptyList(),
    val keptMembers: List<KeepMemberCondition> = emptyList(),
    val keepAllMembers: Boolean = false,
)
