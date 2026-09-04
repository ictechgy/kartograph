package dev.kartograph.core

/** 분석 결과의 위치와 정체성을 출력 계층에 전달하는 값이다. */
public data class Finding(
    val nodeId: NodeId,
    val location: SourceLocation?,
) : Comparable<Finding> {
    /** 줄 이동으로 기존 도입 baseline이 무효화되지 않는 안정적인 지문이다. */
    public val fingerprint: String
        get() = "dead|$nodeId|${location?.path.orEmpty()}"

    override fun compareTo(other: Finding): Int =
        compareValuesBy(this, other, { it.nodeId }, { it.location?.path }, { it.location?.line }, { it.location?.column })
}
