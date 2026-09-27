package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.TestSourceSets

/**
 * language-traversal이 순회할 프로그램 범위다.
 *
 * @property graph 순회할 그래프다. 테스트 소스를 뺐으면 production 정점만 남은 부분 그래프다
 * @property excludedTestSources 순회에서 뺀 테스트 소스 정점 수다. 빼지 않았으면 0이다
 * @property limitations 범위 결정을 알리는 한계 문구다
 */
public data class TraversalScope(val graph: CodeGraph, val excludedTestSources: Int, val limitations: List<String>)

/**
 * 테스트 소스 선언을 별도 프로그램으로 보고 production 순회에서 빼는 범위 정책이다.
 *
 * 가정: 테스트 소스 세트는 production 코드와 따로 컴파일되는 별도 프로그램이고, production 코드는 테스트 class를
 * 참조·생성하지 않는다(main 컴파일 classpath에 test 출력이 없다). 그래서 production 호출 지점의 dispatch 대상에
 * 테스트 fake(`FakeClient : Client`)는 들어올 수 없고, 테스트 정점을 거치는 경로는 production 실행 경로가 아니다.
 * 테스트 정점을 그래프에서 빼면 bound 판정의 닫힌 세계도 production 구현만 세게 된다.
 *
 * 가정은 그래프로 검증한다. production 정점에서 테스트 정점으로 가는 dispatch가 아닌 간선(호출·참조·필드 접근·상속·
 * 어노테이션·런타임 모델)이 하나라도 있거나 root가 테스트 정점이면 빼지 않고 전체 그래프를 순회한다(테스트 구현도
 * 세는 보수적 bound). override 간선(상위 선언 → 테스트 구현, 호출자 → 테스트 구현 후보)은 dispatch 가능성일 뿐
 * production이 테스트를 참조한다는 증거가 아니므로 위반으로 세지 않는다.
 *
 * 테스트 정점은 소스 위치의 경로 규칙([TestSourceSets])으로 가린다. `routes`의 기본 테스트 제외와 같은 경계다.
 * 위치가 없는 멤버는 소유 class, 중첩 class는 바깥 class의 위치를 따른다. 끝내 위치가 없으면 production으로 둔다 —
 * 테스트 class를 production으로 잘못 두면 bound가 약해지고 목록이 늘 뿐 도달을 잃지 않는다.
 */
public object TestSourceScope {
    /**
     * @param graph snapshot 전체 그래프다
     * @param requested root 요청이다. 전체 그래프에서 테스트 정점으로 해석되는 요청이 있으면 테스트를 뺄 수 없다
     * @param includeTests 참이면 테스트 소스 정점도 순회한다
     */
    public fun select(graph: CodeGraph, requested: List<String>, includeTests: Boolean): TraversalScope {
        if (includeTests) return TraversalScope(graph, 0, emptyList())
        val tests = testSourceNodes(graph)
        if (tests.isEmpty()) return TraversalScope(graph, 0, emptyList())
        val testRoots = requested.mapNotNull { LanguageTraversal.resolveRootNode(graph, it)?.id }.distinct().count { it in tests }
        if (testRoots > 0) return included(graph, "$testRoots root(s) are test-source declarations")
        val references = productionReferences(graph, tests)
        if (references > 0) return included(graph, "$references edge(s) from production declarations reference test-source " +
            "declarations, contradicting the separate-program assumption")
        return TraversalScope(withoutNodes(graph, tests), tests.size, listOf("test-sources-excluded: ${tests.size} test-source " +
            "declaration(s) (src/test, src/androidTest, src/test<Variant>, src/*Test and src/testFixtures paths) were not traversed; " +
            "test sources are a separate program, so bound dispatch counts production implementations only; use --include-tests " +
            "to traverse them"))
    }

    /** 소스 위치가 테스트 소스 세트 아래인 정점이다. 위치가 없으면 소유 class·바깥 class의 위치를 따른다. */
    public fun testSourceNodes(graph: CodeGraph): Set<NodeId> =
        graph.nodes.keys.filterTo(mutableSetOf()) { id -> locationPath(graph, id)?.let(TestSourceSets::isTestSourcePath) == true }

    /** 정점의 소스 경로다. 없으면 소유 class, 그다음 바깥 class 순서로 찾는다. */
    private fun locationPath(graph: CodeGraph, id: NodeId): String? {
        var current: NodeId? = id
        val seen = mutableSetOf<NodeId>()
        while (current != null && seen.add(current)) {
            graph.node(current)?.location?.path?.let { return it }
            current = enclosingClass(current)
        }
        return null
    }

    /** 멤버는 소유 class, 중첩 class(`Outer$Inner`)는 바깥 class다. 더 없으면 null이다. */
    private fun enclosingClass(id: NodeId): NodeId? {
        splitOwner(id)?.let { return NodeId("class:$it") }
        val name = id.value.removePrefix("class:").takeIf { id.value.startsWith("class:") } ?: return null
        return name.substringBeforeLast('$', "").takeIf { it.isNotEmpty() && !it.endsWith('/') }?.let { NodeId("class:$it") }
    }

    /** `method:owner#…`·`field:owner#…`의 owner다. */
    private fun splitOwner(id: NodeId): String? {
        val prefix = MEMBER_PREFIXES.firstOrNull { id.value.startsWith(it) } ?: return null
        return id.value.removePrefix(prefix).substringBefore('#', "").takeIf { it.isNotEmpty() }
    }

    /**
     * production 정점에서 테스트 정점으로 가는 dispatch가 아닌 간선 수다. 0이 아니면 "production은 테스트를 참조하지
     * 않는다"는 가정이 이 그래프에서 깨졌다(관례 밖 source set 배치나 reflection).
     */
    internal fun productionReferences(graph: CodeGraph, tests: Set<NodeId>): Int =
        graph.edges.count { it.source !in tests && it.target in tests && it.kind != EdgeKind.OVERRIDE }

    private fun included(graph: CodeGraph, reason: String): TraversalScope = TraversalScope(graph, 0, listOf("test-sources-included: " +
        "test-source declarations were traversed and bound dispatch counts their implementations because $reason"))

    /** 정점을 뺀 부분 그래프다. 간선·호출·콜백 사실은 [CodeGraph]가 남은 정점 기준으로 다시 거른다. */
    private fun withoutNodes(graph: CodeGraph, removed: Set<NodeId>): CodeGraph = CodeGraph(
        nodes = graph.nodes.values.filter { it.id !in removed }, edges = graph.edges, externalCalls = graph.externalCalls,
        serviceProviders = graph.serviceProviders, enclosures = graph.enclosures, callbackArguments = graph.callbackArguments,
        parameterUses = graph.parameterUses, lambdaEscapes = graph.lambdaEscapes,
    )

    private val MEMBER_PREFIXES = listOf("method:", "field:")
}
