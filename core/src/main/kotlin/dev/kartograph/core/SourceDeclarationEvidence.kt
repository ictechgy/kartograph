package dev.kartograph.core

/**
 * 현재 source의 유일한 직접 선언 header를 JVM identity와 대조한 어휘적 위치 근거다.
 * compiler 실행 위치·빌드 신선도와 별개이며 좌표는 raw UTF-8을 decode한 UTF-16 sequence 기준이다.
 */
public data class SourceDeclarationEvidence(
    val path: String,
    val sourceSha256: String,
    val offsetUtf16: Int,
    val line: Int,
    val column: Int,
) {
    init {
        require(isPortablePath(path)) {
            "source declaration path must be project relative"
        }
        require(SHA256.matches(sourceSha256)) { "invalid source declaration hash" }
        require(offsetUtf16 >= 0 && line > 0 && column > 0) { "invalid source declaration coordinates" }
        require(line.toLong() <= offsetUtf16.toLong() + 1 && column.toLong() <= offsetUtf16.toLong() + 1) {
            "source declaration coordinates exceed the offset"
        }
    }

    public companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** 읽기 전에 선택 근거의 경로를 같은 계약으로 검사한다. 지원하지 않는 경로는 그래프에서 버리지 않는다. */
        public fun isPortablePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
            '\\' !in path && ':' !in path && path.none { it.code < 0x20 } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
