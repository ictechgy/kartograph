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

/**
 * 관측된 명령과 metadata·모델·compiler 참조 보강을 구분하는 간선 출처다.
 *
 * `DISPATCH_MODEL`의 OVERRIDE 간선은 항상 **호출자 → 구현 후보** 방향이다(`ExternalDispatchIndexer`).
 * 상위 선언 → 구현 방향의 실제 override는 BYTECODE·KOTLIN_METADATA 출처만 만든다. 변경 영향의 계약 확장은
 * 이 불변식에 의존하므로, 선언 → 구현 간선을 새 출처로 추가할 때는 이 출처를 재사용하지 않는다.
 *
 * `RUNTIME_MODEL`은 framework가 실제로 부른다고 모델링한 간선이다(명시 콜백 목록·reflection 값 등). `LIBRARY_CALLBACK_MODEL`은
 * 외부 라이브러리 상위 타입의 재정의 가능 계약(`Runnable.run`, `Comparator.compare`, `Object.equals` 등)을 같은 시그니처로
 * 재정의한 메서드를 소유 class에서 잇는 간선이다. 라이브러리가 그 메서드를 부를 수 있다는 가능성일 뿐 호출 증명이 아니므로
 * 도달성(보존)에는 쓰지만 순회 근거는 candidate다. 기존 ordinal을 바꾸지 않도록 끝에 둔다.
 */
public enum class EdgeOrigin { BYTECODE, KOTLIN_METADATA, DISPATCH_MODEL, RUNTIME_MODEL, COMPILER_REFERENCE, LIBRARY_CALLBACK_MODEL }

/** 동일 signature의 발생 횟수를 weight로 합치는 그래프 간선이다. */
public data class GraphEdge(
    val source: NodeId,
    val target: NodeId,
    val kind: EdgeKind,
    val weight: Int = 1,
    val origin: EdgeOrigin = EdgeOrigin.BYTECODE,
    /** 동일한 직접 bytecode 호출 간선에 대응하는 유효한 source line 집합이다. */
    val callSiteLines: List<Int> = emptyList(),
) : Comparable<GraphEdge> {
    init {
        require(weight > 0) { "edge weight must be positive" }
        var previousLine = 0
        var lineIndex = 0
        while (lineIndex < callSiteLines.size) {
            val line = callSiteLines[lineIndex]
            if (line < 1 || line > 65535 || (lineIndex > 0 && previousLine >= line)) {
                throw IllegalArgumentException("call-site lines must be sorted, distinct JVM line numbers")
            }
            previousLine = line
            lineIndex++
        }
        require(callSiteLines.isEmpty() || (kind == EdgeKind.CALL && origin == EdgeOrigin.BYTECODE)) {
            "only bytecode call edges may carry call-site lines"
        }
        require(callSiteLines.size <= weight) { "call-site lines cannot outnumber edge occurrences" }
    }

    /** 자기 자신을 가리키는 간선인지 나타낸다. */
    public val isSelfLoop: Boolean
        get() = source == target

    override fun compareTo(other: GraphEdge): Int {
        val sourceOrder = source.value.compareTo(other.source.value)
        if (sourceOrder != 0) return sourceOrder
        val targetOrder = target.value.compareTo(other.target.value)
        if (targetOrder != 0) return targetOrder
        val kindOrder = kind.compareTo(other.kind)
        if (kindOrder != 0) return kindOrder
        return origin.name.compareTo(other.origin.name)
    }
}
