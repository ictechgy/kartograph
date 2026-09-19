package dev.kartograph.export

/**
 * unreachable finding의 보고 보조 신호다. 같은 소스 파일의 컴파일물에서 측정된 미해결 runtime 채널과
 * 사용자가 제공한 런타임 근거만 뜻하며, 어떤 삭제의 승인도 아니다.
 * 전역 분석 한계는 모든 보고 형식에 계속 함께 보고된다.
 */
public enum class FindingConfidence(public val label: String) {
    /** 제공된 런타임 근거에서 이 선언의 class가 실행 또는 로드됐다. 정적 도달성과 실제 실행의 차이를 알리는 가장 강한 검토 신호다. */
    RUNTIME_OBSERVED("runtime-observed"),

    /** 같은 소스에서 미해결 runtime 채널이 관측되지 않았다. */
    STATIC("static"),

    /** 같은 소스에서 하나 이상의 미해결 runtime 채널이 측정됐다. 사람의 검토 전제가 강해진다. */
    REVIEW("needs-runtime-review"),

    /** 소스 위치가 없거나 대응하는 관측이 없어 측정하지 못했다. */
    UNMEASURED("unmeasured"),
}
