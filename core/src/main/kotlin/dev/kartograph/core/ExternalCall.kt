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
    /** header로 확인한 선언이다. INTERFACE는 직접 선언, 나머지 지원 kind는 class 사슬이며 null은 미확정이다. */
    val declaration: CallDeclaration? = null,
) : Comparable<ExternalCall> {
    init {
        require(declaration == null || (declaration.ownerChain.first() == owner &&
            declaration.target.value == "method:${declaration.ownerChain.last()}#$name$descriptor" &&
            (kind in setOf(InvocationKind.VIRTUAL, InvocationKind.STATIC, InvocationKind.SPECIAL) && !name.startsWith('<') ||
                kind == InvocationKind.INTERFACE && declaration.ownerChain.size == 1 &&
                    CallDeclaration.isValidInterfaceSignature(name, descriptor)))) {
            "call declaration does not match the observed operand"
        }
    }

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

/** 확인한 JVM 선언이 프로젝트 정점인지 외부 header인지 구분한다. */
public enum class CallDeclarationScope { PROJECT, EXTERNAL }

/**
 * 선언 identity와 operand부터 첫 선언까지 확인한 순서다. 실행 body나 전체 JVM linkage 성공의 증명이 아니다.
 * 부모 ExternalCall.kind가 INTERFACE면 public instance 직접 선언의 한 owner이고, 그 외 지원 kind는 class 사슬이다.
 * 사슬 길이만으로 증명 종류를 추정하지 않는다. 외부 header의 직렬화된 주장은 classfile 인증이 아니다.
 */
public data class CallDeclaration(
    val target: NodeId,
    val scope: CallDeclarationScope,
    val ownerChain: List<String>,
) {
    init {
        require(ownerChain.size in 1..MAX_OWNER_CHAIN && ownerChain.distinct().size == ownerChain.size &&
            ownerChain.all { it.isNotBlank() && it.length <= 65535 && it.none { c -> c in ".;[\u0000#" } } &&
            target.value.startsWith("method:${ownerChain.last()}#")) { "invalid call declaration chain" }
    }

    public companion object {
        /** 손상되거나 과도한 hierarchy를 무한 탐색하거나 할당하지 않는 공개 경계다. */
        public const val MAX_OWNER_CHAIN: Int = 256

        /** 직접 interface 선언의 일반 이름과 JVM descriptor 문법·instance parameter 경계를 검사한다. header 증명은 별도다. */
        public fun isValidInterfaceSignature(name: String, descriptor: String): Boolean {
            if (name.isEmpty() || name.length > 65535 || name.any { it in ".;[/<>\u0000" } ||
                descriptor.length !in 3..65535 || descriptor[0] != '(') return false
            var index = 1
            var slots = 1 // invokeinterface 수신 객체도 JVM의 255 slot 한도에 포함한다.
            fun fieldType(): Int {
                val start = index
                while (index < descriptor.length && descriptor[index] == '[') index++
                val dimensions = index - start
                if (dimensions > 255 || index >= descriptor.length) return 0
                val kind = descriptor[index++]
                if (kind == 'L') {
                    val end = descriptor.indexOf(';', index)
                    if (end <= index) return 0
                    val owner = descriptor.substring(index, end)
                    if (owner.split('/').any { it.isEmpty() } || owner.any { it in ".;[\u0000" }) return 0
                    index = end + 1
                } else if (kind !in "BCDFIJSZ") return 0
                return if (dimensions == 0 && kind in "JD") 2 else 1
            }
            while (index < descriptor.length && descriptor[index] != ')') {
                val width = fieldType()
                if (width == 0) return false
                slots += width
                if (slots > 255) return false
            }
            if (index >= descriptor.length || descriptor[index++] != ')' || index >= descriptor.length) return false
            if (descriptor[index] == 'V') index++ else if (fieldType() == 0) return false
            return index == descriptor.length
        }
    }
}
