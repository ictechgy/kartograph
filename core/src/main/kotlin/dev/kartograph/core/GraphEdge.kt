package dev.kartograph.core

/** 의존하는 정점에서 의존 대상으로 향하는 관계의 종류다. */
public enum class EdgeKind {
    CALL,
    FIELD_ACCESS,
    REFERENCE,
    INHERITANCE,
    OVERRIDE,
    MEMBER,
    ANNOTATION,
    RETENTION;

    /** 도달성 분석과 symbol query가 공통으로 따를 사용 관계인지 나타낸다. */
    public val impliesUsage: Boolean
        get() = this != MEMBER
}

/** 관측된 명령과 metadata·모델·compiler 참조 보강을 구분하는 간선 출처다. */
public enum class EdgeOrigin { BYTECODE, KOTLIN_METADATA, DISPATCH_MODEL, RUNTIME_MODEL, COMPILER_REFERENCE }

/** 동일 signature의 발생 횟수를 weight로 합치는 그래프 간선이다. */
public data class GraphEdge(
    val source: NodeId,
    val target: NodeId,
    val kind: EdgeKind,
    val weight: Int = 1,
    val origin: EdgeOrigin = EdgeOrigin.BYTECODE,
) : Comparable<GraphEdge> {
    init {
        require(weight > 0) { "edge weight must be positive" }
    }

    /** 자기 자신을 가리키는 간선인지 나타낸다. */
    public val isSelfLoop: Boolean
        get() = source == target

    override fun compareTo(other: GraphEdge): Int =
        compareValuesBy(this, other, GraphEdge::source, GraphEdge::target, GraphEdge::kind, { it.origin.name })
}
