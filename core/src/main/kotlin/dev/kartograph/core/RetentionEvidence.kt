package dev.kartograph.core

/** 코드 밖의 채널이 선언을 보존하는 이유와 사용자에게 보여 줄 영문 설명이다. */
public enum class RetentionReason(public val description: String) {
    MANIFEST_COMPONENT("declared as an Android manifest component"),
    XML_LAYOUT("referenced by an Android XML resource"),
    KEEP_ANNOTATION("annotated with androidx.annotation.Keep"),
    KEEP_ANNOTATED_MEMBER("contains a member annotated with androidx.annotation.Keep"),
    KEEP_ANNOTATED_CLASS_MEMBER("member of a class annotated with androidx.annotation.Keep"),
    KEEP_RULE("matched a ProGuard/R8 keep rule"),
    DEPENDENCY_INJECTION("retained by a supported dependency-injection annotation model"),
    SERIALIZATION("participates in reflection or generated serialization"),
    GENERATED_CODE("generated companion of a retained framework declaration"),
    RUNTIME_ENTRY_POINT("invoked by an Android framework, test runner, or reflection-based library"),
    INLINE_CONSTANT("compile-time constant declaration or owner; inlined use sites may be absent"),
    EXTERNAL_MEMBER_ENTRY("conservatively treated as a possible entry point in private-member analysis"),
    SERVICE_PROVIDER("declared in META-INF/services for external runtime discovery"),
}

/** 보존되는 정점과 복원 가능한 경우 판정을 재현할 파일·줄 근거를 함께 운반한다. */
public data class RetentionEvidence(
    val nodeId: NodeId,
    val reason: RetentionReason,
    val location: SourceLocation?,
)
