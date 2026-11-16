package dev.kartograph.index

import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.Interpreter
import org.objectweb.asm.tree.analysis.Value

/**
 * kartograph가 만드는 ASM [Analyzer]의 계산 한도다.
 *
 * ASM 9.11부터 [Analyzer]는 메서드마다 기본 한도(추적 할당 400MiB, 연산 5×10¹⁰)를 두고, 넘으면 `AnalyzerException`이
 * 아닌 `LimitExceededException`(RuntimeException)을 그대로 던진다. 연산은 처리한 명령마다 그 위치의 10배씩 세므로 합류가
 * 잦은 긴 javac 메서드는 9.10.1에서 1초 남짓에 끝나도 기본 한도를 넘는다. 9.10.1과 같은 사실을 내도록 기본값은 API가 받는
 * 최대치([UNBOUNDED])다. 연산은 사실상 무제한이고, 추적 할당만 `int` 범위(약 2GiB, 메서드 하나의 분석 동안 누적)로 남는다.
 * 그 한도에 닿은 메서드는 호출한 쪽이 기존 계량 한계로 세거나, 계량 한계가 없으면 원인을 밝혀 실패한다.
 */
internal data class AnalysisLimits(val maxBytes: Int, val maxOperations: Long) {
    /** 이 한도를 적용한 새 [Analyzer]다. */
    fun <V : Value> analyzer(interpreter: Interpreter<V>): Analyzer<V> =
        Analyzer(interpreter).apply { setComputeLimits(maxBytes, maxOperations) }

    companion object {
        /** `Analyzer.setComputeLimits`가 받는 최대치다. */
        val UNBOUNDED = AnalysisLimits(Int.MAX_VALUE, Long.MAX_VALUE)
    }
}
