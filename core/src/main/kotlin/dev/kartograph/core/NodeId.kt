package dev.kartograph.core

/** JVM과 source 사실을 결합한 뒤에도 바뀌지 않는 그래프 정점 식별자다. */
@JvmInline
public value class NodeId(public val value: String) : Comparable<NodeId> {
    init {
        require(value.isNotBlank()) { "node identifier must not be blank" }
    }

    override fun compareTo(other: NodeId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}
