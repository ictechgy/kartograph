package dev.kartograph.index

import dev.kartograph.core.CompilerEvidence
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId

/** v3 producer가 관찰한 CALL selector의 전체 분류다. */
public data class CompilerCallPositionStats(
    val observed: Int,
    val emitted: Int,
    val unmapped: Int,
    val ambiguous: Int,
) {
    init {
        require(listOf(observed, emitted, unmapped, ambiguous).all { it >= 0 }) {
            "invalid compiler call position counts"
        }
        require(observed.toLong() == emitted.toLong() + unmapped.toLong() + ambiguous.toLong()) {
            "compiler call position counts do not add up"
        }
    }
}

/**
 * 완료 receipt와 graph 범위를 검증하기 전의 raw v3 CALL selector 행이다. 행 자체는 producer나 caller-file 연결을
 * 인증하지 않으며 importer가 완료된 task 범위, 현재 source hash, 알려진 bytecode source 위치를 함께 확인한다.
 */
public data class UnverifiedCompilerCallPosition(
    val source: NodeId,
    val target: NodeId,
    val file: CompilerEvidenceSource,
    val offsetUtf16: Int,
    val endOffsetUtf16: Int,
    val line: Int,
    val column: Int,
) {
    init {
        require(offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16 && line > 0 && column > 0) {
            "invalid compiler call position coordinates"
        }
    }
}

/** released [CompilerEvidence]와 additive v3 위치 행을 ABI 변경 없이 함께 전달한다. */
public data class CompilerEvidenceEnvelope(
    val evidence: CompilerEvidence,
    val callStats: CompilerCallPositionStats?,
    val callPositions: List<UnverifiedCompilerCallPosition>,
) {
    init {
        require(callStats != null || callPositions.isEmpty()) { "legacy compiler evidence cannot contain call positions" }
        require(callStats == null || callStats.emitted == callPositions.size) {
            "emitted compiler call position count does not match rows"
        }
    }
}

/** compiler witness가 실제 `callPositions=true` 입력을 token에 포함했음을 나타내는 별도 지문이다. */
public object CompilerCallPositionOptions {
    public const val INPUT_PATH: String = "compiler-call-positions"
    private const val ENABLED_VALUE: String = "callPositions=true"

    public fun enabledInput(): InputFingerprint =
        InputFingerprint("options", INPUT_PATH, ContentFingerprint.values(listOf(ENABLED_VALUE)))

    public fun isEnabled(inputs: List<InputFingerprint>): Boolean =
        inputs.filter { it.role == "options" && it.path == INPUT_PATH }.singleOrNull() == enabledInput()
}
