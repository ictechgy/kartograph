package dev.kartograph.analysis

import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeKind

/**
 * 상속 Spring Data 저장소 메서드 호출 중 persistence 사실이 이미 모델링한 것을 고른다.
 *
 * `repo.save(x)`·`repo.findById(id)`처럼 `CrudRepository`·`JpaRepository`에서 상속한 메서드는 프로젝트 정점이 없어 스냅샷에
 * 외부 가상 호출로만 남고, dispatch 해석이 끝나지 않아 `unresolvedCalls`로 세어진다. 그러나 persistence 스캐너(`schema
 * --graph-file`)는 이 호출 지점을 호출자 메서드의 relation-use 사실로 이미 귀속한다(스파이크 S3의 owner 규칙). 그 사실이 있고
 * 호출이 프로젝트 코드로 이어질 수 없으면 순회가 호출 너머에서 놓치는 relation-use가 없으므로, isthmus trace의
 * `reach-possibly-incomplete`를 만드는 미해결 호출에서 뺀다.
 *
 * **건전성 조건.** 호출 하나를 빼려면 다음이 모두 참이어야 한다.
 * 1. 외부 인터페이스·가상 호출이고 dispatch 해석이 `UNRESOLVED`다(라이브러리 모델·reflection·invokedynamic은 빼지 않는다).
 * 2. 호출 owner가 스냅샷의 프로젝트 인터페이스이고, 프로젝트 상위 인터페이스를 따라가면 Spring Data 저장소 기반 인터페이스
 *    ([REPOSITORY_BASES])에 닿는다.
 * 3. 호출이 프로젝트 코드로 이어질 수 없다: owner의 프로젝트 상위 인터페이스가 같은 이름의 메서드를 선언하지 않고(fragment
 *    재정의), 스냅샷에 Spring Data 기반 타입을 구현·상속한 프로젝트 타입(class·object·enum)이 없다(사용자 base class·직접 구현).
 * 4. persistence 문서가 owner 저장소 선언 파일(owner 정점의 소스 경로)에 relation-use를 두고(상속 CRUD 표면의 도메인 테이블),
 *    같은 relation의 사실을 `symbol.usr`가 호출자 메서드 id이고 `location.line`이 그 호출 줄(줄이 없으면 호출자 선언 줄 — 스캐너와
 *    같은 규칙)인 곳에 둔다. 즉 이 호출의 relation-use(호출자 → 도메인 테이블)가 사실로 이미 있다.
 *
 * 한계: 4는 (호출자, 줄, 테이블)로 맞추므로 같은 줄에서 같은 테이블을 쓰는 다른 문장의 사실과 구분하지 않는다 — 그때도 호출자 →
 * 테이블 relation-use는 사실로 있으므로 trace가 놓치는 relation은 없다. 저장소 AOP advice처럼 프레임워크가 끼워 넣는 프로젝트
 * 코드는 선언된 저장소 메서드 호출과 마찬가지로 모델링하지 않는다.
 */
public object PersistenceModeledCalls {
    /**
     * persistence 사실 하나의 호출 지점 귀속이다.
     *
     * @property callerUsr relation-use 사실의 `symbol.usr`(호출자 메서드 id)
     * @property line 사실의 `location.line`
     * @property relation 사실의 `channel`(관계 이름)
     */
    public data class Attribution(val callerUsr: String, val line: Int, val relation: String)

    /**
     * persistence 문서에서 읽은 근거다.
     *
     * @property callSites 호출자에 귀속된 relation-use 사실이다
     * @property declarations 신원 없는 선언 위치 사실의 소스 경로 → relation 이름이다(저장소·엔티티 선언)
     */
    public data class Evidence(val callSites: Set<Attribution>, val declarations: Map<String, Set<String>>) {
        public companion object {
            /** 근거 없음이다. 아무 호출도 빼지 않는다. */
            public val NONE: Evidence = Evidence(emptySet(), emptyMap())
        }
    }

    /** Spring Data 저장소 기반 인터페이스(JVM 내부 이름)다. 사용자 저장소가 상속해 CRUD·페이징·명세 메서드를 얻는 타입이다. */
    public val REPOSITORY_BASES: Set<String> = setOf(
        "org/springframework/data/repository/Repository",
        "org/springframework/data/repository/CrudRepository",
        "org/springframework/data/repository/ListCrudRepository",
        "org/springframework/data/repository/PagingAndSortingRepository",
        "org/springframework/data/repository/ListPagingAndSortingRepository",
        "org/springframework/data/repository/query/QueryByExampleExecutor",
        "org/springframework/data/repository/query/ListQueryByExampleExecutor",
        "org/springframework/data/repository/history/RevisionRepository",
        "org/springframework/data/repository/kotlin/CoroutineCrudRepository",
        "org/springframework/data/repository/kotlin/CoroutineSortingRepository",
        "org/springframework/data/jpa/repository/JpaRepository",
        "org/springframework/data/jpa/repository/JpaSpecificationExecutor",
        "org/springframework/data/jpa/repository/support/JpaRepositoryImplementation",
        "org/springframework/data/querydsl/QuerydslPredicateExecutor",
        "org/springframework/data/querydsl/ListQuerydslPredicateExecutor",
    )

    /** Spring Data 기본 구현이다. 이것을 상속한 프로젝트 class는 사용자 base class다. */
    private val REPOSITORY_IMPLEMENTATIONS: Set<String> = setOf(
        "org/springframework/data/jpa/repository/support/SimpleJpaRepository",
        "org/springframework/data/repository/core/support/RepositoryFactorySupport",
    )

    /** 건전성 조건을 모두 만족하는 외부 호출이다. 근거가 없거나 프로젝트 저장소 구현이 있으면 비어 있다. */
    public fun select(graph: CodeGraph, evidence: Evidence): Set<ExternalCall> {
        if (evidence.callSites.isEmpty()) return emptySet()
        val types = graph.nodes.values.filter { it.kind in TYPE_KINDS && it.jvmSignature != null }.associateBy { it.jvmSignature!! }
        // 인터페이스가 아닌 모든 타입(class·Kotlin object·enum, 익명 class 포함)이 구현이 될 수 있다.
        if (types.values.any { it.kind != NodeKind.INTERFACE && ancestors(it.jvmSignature!!, types).any { name -> name in REPOSITORY_BASES || name in REPOSITORY_IMPLEMENTATIONS } }) {
            return emptySet()
        }
        val methodNames = graph.nodes.keys.mapNotNullTo(mutableSetOf()) { id ->
            id.value.takeIf { it.startsWith("method:") }?.substringAfter("method:")?.substringBefore('(')
        }
        val callers = evidence.callSites.groupBy { it.callerUsr to it.line }
        val repositories = mutableMapOf<String, Set<String>>()
        return graph.externalCalls.filterTo(mutableSetOf()) { call ->
            if (call.kind !in VIRTUAL_KINDS || call.model != null || call.resolution != CallResolution.UNRESOLVED) return@filterTo false
            val relations = repositories.getOrPut(call.owner) { repositoryRelations(call.owner, types, evidence) }
            val line = attributedLine(call, graph) ?: return@filterTo false
            relations.isNotEmpty() && !overridden(call, types, methodNames) &&
                callers[call.caller.value to line].orEmpty().any { it.relation in relations }
        }
    }

    /** 스캐너와 같은 줄 규칙이다 — 호출 줄이 없으면 호출자 선언 줄이다. */
    private fun attributedLine(call: ExternalCall, graph: CodeGraph): Int? = call.location?.line ?: graph.node(call.caller)?.location?.line

    /**
     * owner가 Spring Data 저장소인 프로젝트 인터페이스면 그 선언 파일에 persistence 문서가 둔 relation들이다. 저장소가 아니거나 선언
     * 사실이 없으면 비어 있다.
     */
    private fun repositoryRelations(owner: String, types: Map<String, GraphNode>, evidence: Evidence): Set<String> {
        val node = types[owner]?.takeIf { it.kind == NodeKind.INTERFACE } ?: return emptySet()
        if (ancestors(owner, types).none { it in REPOSITORY_BASES }) return emptySet()
        val path = node.location?.path?.let(::normalizedPath) ?: return emptySet()
        return evidence.declarations[path].orEmpty()
    }

    /** 선언 위치 사실과 정점의 소스 경로를 같은 모양(`/` 구분자)으로 맞춘다. 근거를 만드는 쪽도 이 함수를 쓴다. */
    public fun normalizedPath(path: String): String = path.replace('\\', '/')

    /** owner의 프로젝트 상위 인터페이스가 같은 이름의 메서드를 선언하는지(fragment 재정의 가능성) 본다. */
    private fun overridden(call: ExternalCall, types: Map<String, GraphNode>, methodNames: Set<String>): Boolean =
        ancestors(call.owner, types).any { type -> type != call.owner && type in types && "$type#${call.name}" in methodNames }

    /** 타입 자신과 프로젝트 상위 타입을 따라간 모든 상위 타입 이름(JVM 내부 이름)이다. 모델 밖 타입은 이름만 담고 멈춘다. */
    private fun ancestors(name: String, types: Map<String, GraphNode>): Set<String> {
        val seen = linkedSetOf<String>()
        val pending = ArrayDeque(listOf(name))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (seen.add(current)) types[current]?.supertypes?.let(pending::addAll)
        }
        return seen
    }

    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
    private val VIRTUAL_KINDS = setOf(InvocationKind.INTERFACE, InvocationKind.VIRTUAL)
}
