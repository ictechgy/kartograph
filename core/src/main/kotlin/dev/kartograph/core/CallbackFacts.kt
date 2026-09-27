package dev.kartograph.core

/**
 * 한 메서드 안에서 만든 람다·지역 class 값이 호출 인자로 그대로 넘어간 관측 사실이다.
 *
 * bytecode의 값 흐름으로 확인한 "F가 만든 람다 L이 호출 지점 G(...)의 인자 [argument]로 전달됐다"는 사실만 담는다.
 * G가 그 인자를 실제로 호출하는지는 [ParameterUse]가 따로 기록하며, 둘을 잇는 정책은 analysis가 정한다.
 * `FunctionN.invoke` dispatch 후보처럼 모든 람다로 퍼지지 않고 호출 지점마다 전달된 람다 하나만 가리킨다.
 *
 * @property caller 람다를 만들어 넘긴 메서드 F다
 * @property callee 호출 지점에 적힌 대상 메서드(`method:owner#name(desc)ret`)다. 가상 호출이면 선언 owner 그대로이며
 *   실제 구현 해석은 analysis가 한다. [invocation]이 BOOTSTRAP이면 람다를 캡처한 invokedynamic 람다의 구현 메서드다
 * @property invocation 호출 명령 종류다. BOOTSTRAP은 invokedynamic 람다의 캡처 인자로 넘어간 경우다
 * @property argument 대상 메서드 descriptor의 0부터 센 파라미터 위치다. 수신 객체는 세지 않는다
 * @property lambda 지역·익명 class면 `class:` 정점, invokedynamic 람다면 구현 메서드 정점이다
 * @property samMethod invokedynamic 람다가 구현하는 함수형 메서드의 이름+erased descriptor다(예: `invoke()Ljava/lang/Object;`).
 *   지역 class면 null이며 호출된 메서드 이름·descriptor로 class 안의 본문을 찾는다
 */
public data class CallbackArgument(
    val caller: NodeId,
    val callee: NodeId,
    val invocation: InvocationKind,
    val argument: Int,
    val lambda: NodeId,
    val samMethod: String? = null,
) : Comparable<CallbackArgument> {
    init {
        require(argument >= 0) { "callback argument index must not be negative" }
    }

    override fun compareTo(other: CallbackArgument): Int = compareValuesBy(this, other,
        { it.caller.value }, { it.callee.value }, { it.invocation.name }, { it.argument }, { it.lambda.value }, { it.samMethod.orEmpty() })
}

/**
 * 메서드 파라미터 값이 본문에서 쓰인 방식이다.
 *
 * - [DECLARED]: 이 파라미터의 쓰임을 빠짐없이 기록했다는 표식이다. 표식이 없는 파라미터로 넘어간 값은 analysis가
 *   "쓰임을 모름"으로 다룬다. 기록이 없다는 사실을 "쓰이지 않음"으로 오해하지 않게 한다
 * - [RECEIVER]: 파라미터 값을 수신 객체로 메서드를 호출했다(`FunctionN.invoke`·SAM 호출 포함)
 * - [ARGUMENT]: 다른 호출의 인자로 그대로 넘겼다
 * - [CAPTURE]: invokedynamic 람다가 캡처했다. 대상은 람다 구현 메서드와 그 파라미터 위치다
 * - [FIELD]: 필드에 저장했다
 * - [RETURN]: 반환했다
 * - [ARRAY]: 배열 원소로 저장했다
 * - [OTHER]: 모델이 없는 invokedynamic 등 위 분류로 해석하지 못한 쓰임이다
 */
public enum class ParameterUseKind { DECLARED, RECEIVER, ARGUMENT, CAPTURE, FIELD, RETURN, ARRAY, OTHER }

/**
 * 함수형 타입처럼 콜백일 수 있는 파라미터의 관측된 쓰임 하나다.
 *
 * 값 흐름은 지역 변수 복사·`checkcast`를 따라가며, 분기에서 합쳐진 값도 이 파라미터에서 왔을 수 있으면 쓰임으로 기록한다.
 *
 * @property method 파라미터를 가진 메서드다
 * @property parameter descriptor의 0부터 센 파라미터 위치다. 수신 객체(`this`)는 세지 않는다
 * @property kind 쓰임의 종류다
 * @property target RECEIVER·ARGUMENT면 호출 지점에 적힌 메서드, CAPTURE면 람다 구현 메서드, FIELD면 필드다
 * @property invocation RECEIVER·ARGUMENT의 호출 명령 종류다
 * @property position ARGUMENT면 대상 descriptor의 파라미터 위치, CAPTURE면 구현 메서드의 파라미터 위치다
 */
public data class ParameterUse(
    val method: NodeId,
    val parameter: Int,
    val kind: ParameterUseKind,
    val target: NodeId? = null,
    val invocation: InvocationKind? = null,
    val position: Int? = null,
) : Comparable<ParameterUse> {
    init {
        require(parameter >= 0 && (position == null || position >= 0)) { "parameter positions must not be negative" }
        // 분석이 종류별로 기대는 필드를 사실 생성 시점에 강제한다. 손상된 snapshot이 순회 도중 실패하지 않게 한다.
        require(when (kind) {
            ParameterUseKind.RECEIVER -> target != null && invocation != null
            ParameterUseKind.ARGUMENT -> target != null && invocation != null && position != null
            ParameterUseKind.CAPTURE -> target != null && position != null
            ParameterUseKind.FIELD -> target != null
            else -> true
        }) { "parameter use is missing fields required by its kind" }
    }

    override fun compareTo(other: ParameterUse): Int = compareValuesBy(this, other,
        { it.method.value }, { it.parameter }, { it.kind.name }, { it.target?.value.orEmpty() }, { it.invocation?.name.orEmpty() },
        { it.position ?: -1 })
}

/**
 * 한 메서드가 만든 람다 값이 호출 인자가 아닌 방식으로 쓰인 관측 사실이다.
 *
 * 필드·배열 저장, 반환, 직접 실행(수신 객체), 모델 없는 invokedynamic 인자가 여기에 든다. 이런 쓰임이 있으면
 * 그 람다를 실행하는 곳을 호출 인자 흐름만으로 모두 찾았다고 할 수 없다. analysis는 이 사실이 있는 람다의 콜백
 * 흐름을 bound로 판정하지 않는다.
 *
 * @property caller 람다를 만든 메서드다
 * @property lambda [CallbackArgument.lambda]와 같은 람다 정점이다
 * @property kind [ParameterUseKind.FIELD]·[ParameterUseKind.RETURN]·[ParameterUseKind.ARRAY]·[ParameterUseKind.RECEIVER]·
 *   [ParameterUseKind.OTHER] 중 하나다
 */
public data class LambdaEscape(val caller: NodeId, val lambda: NodeId, val kind: ParameterUseKind) : Comparable<LambdaEscape> {
    init {
        require(kind in ESCAPE_KINDS) { "lambda escape kind must describe a non-argument use" }
    }

    override fun compareTo(other: LambdaEscape): Int = compareValuesBy(this, other, { it.caller.value }, { it.lambda.value }, { it.kind.name })

    private companion object {
        val ESCAPE_KINDS = setOf(ParameterUseKind.FIELD, ParameterUseKind.RETURN, ParameterUseKind.ARRAY, ParameterUseKind.RECEIVER,
            ParameterUseKind.OTHER)
    }
}
