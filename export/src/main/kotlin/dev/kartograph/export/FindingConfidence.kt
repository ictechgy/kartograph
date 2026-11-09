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

/**
 * CLI `dead`와 Gradle `kartographDead<Variant>`가 같은 finding에 같은 [FindingConfidence]를 매기도록 규칙을 한곳에 둔다.
 * 입력(소스 파일별 미해결 runtime 채널 수, 사용자가 준 런타임 관측 class, binding 검토 대상)은 호출자가 측정해 넘긴다.
 */
public object FindingConfidences {
    /** 위치가 있는 finding을 같은 소스 파일의 측정된 runtime 채널 관측으로 등급화한다. */
    public fun of(location: dev.kartograph.core.SourceLocation?, channelsBySource: Map<String, Int>): FindingConfidence {
        val sourceFile = location?.path?.substringAfterLast('/') ?: return FindingConfidence.UNMEASURED
        val channels = channelsBySource[sourceFile] ?: return FindingConfidence.UNMEASURED
        return if (channels > 0) FindingConfidence.REVIEW else FindingConfidence.STATIC
    }

    /**
     * 제공된 런타임 근거에서 소유 class가 관측되면 위치·runtime 채널과 무관하게 runtime-observed로 승격한다.
     * finding 자체나 strict 판정은 바꾸지 않는다.
     */
    public fun of(
        finding: dev.kartograph.core.Finding,
        graph: dev.kartograph.core.CodeGraph,
        channelsBySource: Map<String, Int>,
        observedClasses: Set<String>,
    ): FindingConfidence {
        if (ownerClassOf(finding.nodeId, graph) in observedClasses) return FindingConfidence.RUNTIME_OBSERVED
        return of(finding.location, channelsBySource)
    }

    /**
     * finding 전체의 등급이다. 런타임 관측이 아닌 finding 중 binding 검토가 필요한 것은 [FindingConfidence.REVIEW]로 둔다.
     *
     * @param requiresReview DataBinding 후보처럼 정적 그래프 밖 검토가 필요한 finding이면 참이다
     */
    public fun forFindings(
        findings: List<dev.kartograph.core.Finding>,
        graph: dev.kartograph.core.CodeGraph,
        channelsBySource: Map<String, Int>,
        observedClasses: Set<String> = emptySet(),
        requiresReview: (dev.kartograph.core.NodeId) -> Boolean = { false },
    ): Map<dev.kartograph.core.NodeId, FindingConfidence> = findings.associate { finding ->
        val observed = of(finding, graph, channelsBySource, observedClasses)
        finding.nodeId to if (observed != FindingConfidence.RUNTIME_OBSERVED && requiresReview(finding.nodeId)) FindingConfidence.REVIEW else observed
    }

    // JVMS class 이름에는 '#'이 올 수 있으므로 문자열 분해 대신 type 여부와 MEMBER 간선으로 소유 class를 찾는다.
    private fun ownerClassOf(nodeId: dev.kartograph.core.NodeId, graph: dev.kartograph.core.CodeGraph): String? {
        val node = graph.node(nodeId) ?: return null
        val ownerId = if (node.kind in TYPE_KINDS) node.id
        else graph.incomingEdgesTo(nodeId).firstOrNull { edge -> edge.kind == dev.kartograph.core.EdgeKind.MEMBER }?.source
        return ownerId?.value?.removePrefix("class:")
    }

    private val TYPE_KINDS = setOf(
        dev.kartograph.core.NodeKind.CLASS,
        dev.kartograph.core.NodeKind.INTERFACE,
        dev.kartograph.core.NodeKind.OBJECT,
        dev.kartograph.core.NodeKind.ENUM,
        dev.kartograph.core.NodeKind.ANNOTATION_CLASS,
    )
}
