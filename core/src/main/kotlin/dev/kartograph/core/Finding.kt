package dev.kartograph.core

/** 분석 결과의 위치와 정체성을 출력 계층에 전달하는 값이다. */
public data class Finding(
    val nodeId: NodeId,
    val location: SourceLocation?,
    /** production root에서는 도달 불가하지만 test 코드에서만 도달되는 선언인지 나타낸다. */
    val testOnly: Boolean = false,
) : Comparable<Finding> {
    /** 줄 이동으로 기존 도입 baseline이 무효화되지 않는 안정적인 지문이다. testOnly는 지문에 영향을 주지 않는다. */
    public val fingerprint: String
        get() = "dead|$nodeId|${location?.path.orEmpty()}"

    override fun compareTo(other: Finding): Int =
        compareValuesBy(this, other, { it.nodeId }, { it.location?.path }, { it.location?.line }, { it.location?.column })
}
