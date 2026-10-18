package dev.kartograph.core

/** JVM 호출 명령의 dispatch 종류다. 라이브러리 구현이 없어도 호출 사실은 남긴다. */
public enum class InvocationKind { STATIC, SPECIAL, VIRTUAL, INTERFACE, BOOTSTRAP }

/** 프로젝트 후보가 없는 호출도 라이브러리 동작을 모두 증명했다는 뜻은 아니다. */
public enum class CallResolution { UNRESOLVED, PROJECT_CANDIDATES, NO_PROJECT_TARGET, RUNTIME_MODEL }

/** 앱 그래프 밖의 메서드로 향하는 관측된 호출이다. 외부 선언을 앱의 dead 후보로 만들지 않는다. */
public data class ExternalCall(
    val caller: NodeId,
    val owner: String,
    val name: String,
    val descriptor: String,
    val kind: InvocationKind,
    val location: SourceLocation? = null,
    val ordinal: Int = 0,
    val resolvedTargets: List<NodeId> = emptyList(),
    val resolution: CallResolution = CallResolution.UNRESOLVED,
    /** 해당 API 호출의 값·대상 해석에 대응하는 라이브러리 모델 식별자다. 해석 완료 여부는 resolution으로 구분한다. */
    val model: String? = null,
) : Comparable<ExternalCall> {
    /** 기존 JVM identity 규칙과 동일한 외부 메서드 식별자다. 불변 생성 인자에서 한 번 계산하며 copy는 새로 계산한다. */
    public val target: NodeId = NodeId("method:$owner#$name$descriptor")

    /** 동일한 줄의 여러 호출도 명령 순서로 구분하며 비교마다 selector 배열·identity 문자열을 만들지 않는다. */
    override fun compareTo(other: ExternalCall): Int {
        var comparison = caller.value.compareTo(other.caller.value)
        if (comparison != 0) return comparison
        comparison = target.value.compareTo(other.target.value)
        if (comparison != 0) return comparison
        comparison = kind.name.compareTo(other.kind.name)
        if (comparison != 0) return comparison
        comparison = ordinal.compareTo(other.ordinal)
        if (comparison != 0) return comparison
        comparison = location?.path.orEmpty().compareTo(other.location?.path.orEmpty())
        if (comparison != 0) return comparison
        return (location?.line ?: 0).compareTo(other.location?.line ?: 0)
    }
}
