package dev.kartograph.core

/** compiler가 좌표를 계산한 source sequence의 UTF-16 기준이다. raw source hash의 byte 기준과 구분한다. */
public enum class CompilerSourceCoordinateBasis {
    /** javac이 보존한 decoded character sequence와 `LineMap` 기준이다. CRLF의 CR을 포함할 수 있다. */
    JAVAC_UTF16_CHAR_SEQUENCE,

    /** Kotlin 2.4.10 compiler가 BOM을 제거하고 줄바꿈을 정규화한 source sequence 기준이다. */
    KOTLIN_UTF16_NORMALIZED_SOURCE,
}

/**
 * compiler가 의미적으로 해석한 CALL selector와 실제 JVM caller/target을 연결한 비간선 사실이다.
 * offset은 [coordinateBasis]가 가리키는 compiler-decoded sequence의 UTF-16 code unit이고, [file] hash는 raw bytes다.
 */
public data class LocatedCompilerReference(
    val source: NodeId,
    val target: NodeId,
    val file: CompilerEvidenceSource,
    val collector: String,
    val compilerVersion: String,
    val coordinateBasis: CompilerSourceCoordinateBasis,
    val offsetUtf16: Int,
    val endOffsetUtf16: Int,
    val line: Int,
    val column: Int,
    val generated: Boolean = false,
) {
    init {
        require(offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16 && line > 0 && column > 0) {
            "invalid compiler call position coordinates"
        }
        require(compilerVersion.length in 1..128 && Regex("[A-Za-z0-9._+\\-]+").matches(compilerVersion)) {
            "invalid compiler call position compiler version"
        }
        require(collector in setOf("javac-constants", "kotlin-constants")) {
            "unsupported compiler call position collector"
        }
        require(
            collector == "javac-constants" && coordinateBasis == CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE ||
                collector == "kotlin-constants" && coordinateBasis == CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
        ) { "compiler call position coordinate basis does not match collector" }
    }
}
