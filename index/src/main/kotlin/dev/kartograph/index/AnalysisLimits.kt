package dev.kartograph.index

import java.lang.reflect.Method
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
 *
 * Gradle plugin은 host build의 ASM 위에서 돌 수 있다. Android build에서는 AGP가 부모 classloader에 올린 ASM이 먼저 쓰여,
 * 9.11 이전 ASM(한도 API와 `LimitExceededException`이 없고 한도도 없다)일 수 있다. 그래서 한도 설정은 실행 중인 ASM에 그
 * 메서드가 있을 때만 하고, 한도 초과는 class를 직접 참조하지 않고 이름으로 알아본다.
 */
internal data class AnalysisLimits(val maxBytes: Int, val maxOperations: Long) {
    /** 이 한도를 적용한 새 [Analyzer]다. 한도 API가 없는 ASM에서는 원래부터 한도가 없으므로 그대로 돌려준다. */
    fun <V : Value> analyzer(interpreter: Interpreter<V>): Analyzer<V> =
        Analyzer(interpreter).also { analyzer -> SET_COMPUTE_LIMITS?.invoke(analyzer, maxBytes, maxOperations) }

    companion object {
        /** `Analyzer.setComputeLimits`가 받는 최대치다. */
        val UNBOUNDED = AnalysisLimits(Int.MAX_VALUE, Long.MAX_VALUE)

        /** 실행 중인 ASM의 `Analyzer.setComputeLimits(int, long)`이다. 9.11 이전 ASM에서는 없다. */
        private val SET_COMPUTE_LIMITS: Method? = try {
            Analyzer::class.java.getMethod("setComputeLimits", Integer.TYPE, java.lang.Long.TYPE)
        } catch (_: NoSuchMethodException) {
            null
        }

        /** 실행 중인 ASM이 계산 한도 API를 갖는지다. */
        val computeLimitsSupported: Boolean get() = SET_COMPUTE_LIMITS != null

        /** ASM 9.11의 `LimitExceededException`인지 이름으로 본다. 이전 ASM에는 그 class가 없어 직접 참조하지 않는다. */
        fun isComputeLimit(error: Throwable): Boolean = error.javaClass.name == "org.objectweb.asm.LimitExceededException"
    }
}
