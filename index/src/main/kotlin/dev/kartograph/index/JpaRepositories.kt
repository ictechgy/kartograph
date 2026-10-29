package dev.kartograph.index

import dev.kartograph.core.BridgeLocation

/**
 * 저장소 메서드 하나가 읽는 것이다.
 *
 * @property touch 엔티티 모델로 해석한 테이블·컬럼(JPQL·파생 질의·상속 CRUD)
 * @property nativeRelations native SQL에서 읽은 관계 이름 — 명명 전략을 거치지 않는 실제 이름이다
 * @property dynamic 정적으로 읽지 못한 원문(비리터럴 질의, 해석 불가 메서드 이름)
 */
internal data class JpaQueryEffect(
    val touch: JpaTouch = JpaTouch(),
    val nativeRelations: List<String> = emptyList(),
    val dynamic: List<String> = emptyList(),
    val kind: String,
) {
    val isEmpty: Boolean get() = touch.tables.isEmpty() && touch.columns.isEmpty() && touch.unresolved.isEmpty() &&
        nativeRelations.isEmpty() && dynamic.isEmpty()
}

/** 저장소 인터페이스 하나다 — [entity]가 null이면 도메인 타입을 JPA 엔티티로 해석하지 못했다. */
internal data class JpaRepository(
    val type: JpaSourceType,
    val file: JpaSourceFile,
    val domainType: String?,
    val entity: JpaEntity?,
    val superRepositories: List<String>,
)

/** 선언된 저장소 메서드와 그 효과다. */
internal data class JpaRepositoryMethod(
    val repository: JpaRepository,
    val method: JpaMethod,
    val effect: JpaQueryEffect,
)

/**
 * Spring Data 저장소 인터페이스를 찾고 메서드를 질의 효과로 해석한다.
 *
 * 우선순위는 Spring Data의 `CREATE_IF_NOT_FOUND` 조회 전략과 같다 — `@Query` > 이름 있는 질의(`@NamedQuery`의
 * `엔티티.메서드`) > 파생 질의 이름. 몸체가 있는 메서드(Kotlin·Java default)는 질의가 아니라 일반 코드다.
 */
internal class JpaRepositoryCatalog(
    files: List<JpaSourceFile>,
    private val model: JpaEntityModel,
    private val namedQueries: Map<String, String>,
) {
    private val paths = JpaPathResolver(model)
    private val jpql = JpqlResolver(model, paths)
    private val interfaces: Map<String, List<Pair<JpaSourceType, JpaSourceFile>>> = files
        .flatMap { file -> file.types.filter { it.isInterface }.map { it to file } }
        .groupBy { it.first.name }

    /** 도메인 타입을 찾은 저장소 전부다(비JPA 도메인 포함). */
    val repositories: List<JpaRepository> = interfaces.values.flatten().mapNotNull { (type, file) -> repository(type, file) }

    /** JVM 내부 이름으로 찾는 저장소다. */
    val byJvmName: Map<String, JpaRepository> = repositories.associateBy { it.type.jvmName }

    /** 선언된 질의 메서드 전부다. */
    val methods: List<JpaRepositoryMethod> = repositories.flatMap { repository ->
        repository.type.methods.filterNot { it.hasBody }.map { JpaRepositoryMethod(repository, it, effect(repository, it)) }
    }

    private fun repository(type: JpaSourceType, file: JpaSourceFile): JpaRepository? {
        val domain = domainType(type, emptyMap(), mutableSetOf()) ?: return null
        val entity = model.entity(domain)
        val supers = type.supertypes.map(::simpleTypeName).filter { interfaces.containsKey(it) }
        return JpaRepository(type, file, domain, entity, supers)
    }

    /**
     * 도메인 타입 이름이다 — Spring Data 기반 인터페이스의 첫 타입 인자, 사용자 중간 인터페이스의 타입 매개변수
     * 대입, `@RepositoryDefinition(domainClass = …)`를 따른다. 기반 인터페이스를 상속하지 않으면 null이다.
     */
    private fun domainType(type: JpaSourceType, bindings: Map<String, String>, seen: MutableSet<String>): String? {
        if (!seen.add(type.jvmName)) return null
        type.annotation("RepositoryDefinition")?.argument("domainClass")?.let { expression ->
            return CLASS_LITERAL.find(expression)?.groupValues?.get(1)?.substringAfterLast('.')
        }
        for (supertype in type.supertypes) {
            val name = simpleTypeName(supertype)
            val arguments = typeArguments(supertype).map { bindings[it] ?: it }
            if (name in SPRING_DATA_BASES) return arguments.firstOrNull()?.let(::simpleTypeName)
            val parent = interfaces[name]?.singleOrNull()?.first ?: continue
            val nested = parent.typeParameters.zip(arguments).toMap()
            domainType(parent, nested, seen)?.let { return it }
        }
        return null
    }

    private fun typeArguments(type: String): List<String> {
        val open = type.indexOf('<').takeIf { it >= 0 } ?: return emptyList()
        val close = angleEnd(type, open).takeIf { it > open } ?: return emptyList()
        return splitTopLevel(type.substring(open + 1, close)).map { simpleTypeName(it.removePrefix("out ").removePrefix("in ")) }
    }

    /** 선언 메서드 하나의 효과다. */
    private fun effect(repository: JpaRepository, method: JpaMethod): JpaQueryEffect {
        val query = method.annotations.firstOrNull { it.name == "Query" }
        if (query != null) return annotatedQuery(repository, query)
        if (method.annotations.any { it.name == "Procedure" }) {
            return JpaQueryEffect(dynamic = listOf("@Procedure ${method.name}"), kind = "procedure")
        }
        val entity = repository.entity ?: return JpaQueryEffect(dynamic = listOf(method.name), kind = "unresolved-domain")
        namedQueries["${entity.entityName}.${method.name}"]?.let { return jpqlEffect(it, "named-query", entity) }
        val derived = DerivedQuery.parse(method.name)
            ?: return inheritedEffect(repository, method.name)?.takeIf { method.name in BASE_METHODS }
                ?: JpaQueryEffect(dynamic = listOf(method.name), kind = "unresolved-method")
        val touch = JpaTouch().apply { entity(entity) }
        derived.properties.forEach { property ->
            val chain = paths.derived(entity, property)
            if (chain == null) touch.unresolved += property else paths.touch(chain, touch)
        }
        return JpaQueryEffect(touch = touch, kind = "derived")
    }

    private fun annotatedQuery(repository: JpaRepository, query: JpaAnnotation): JpaQueryEffect {
        val native = query.argument("nativeQuery")?.trim() == "true"
        query.argument("name")?.let(::literal)?.let { name ->
            return namedQueries[name]?.let { jpqlEffect(it, "named-query", repository.entity) }
                ?: JpaQueryEffect(dynamic = listOf(name), kind = "unresolved-named-query")
        }
        val expression = query.argument("value", positional = true)
            ?: return JpaQueryEffect(dynamic = listOf("@Query"), kind = "unresolved-query")
        val text = concatenatedLiteral(expression)
            ?: return JpaQueryEffect(dynamic = listOf(expression.trim()), kind = "dynamic-query")
        return if (native) nativeEffect(text) else jpqlEffect(text, "jpql", repository.entity)
    }

    /** JPQL 문자열의 효과다. */
    fun jpqlEffect(text: String, kind: String, domain: JpaEntity? = null): JpaQueryEffect {
        val expanded = domain?.let { SPEL_ENTITY_NAME.replace(text, Regex.escapeReplacement(it.entityName)) } ?: text
        val touch = jpql.resolve(expanded)
        return JpaQueryEffect(touch = touch, kind = kind)
    }

    /** native SQL 문자열의 효과다 — 기존 SQL 렉서가 관계 이름을 읽는다. */
    fun nativeEffect(sql: String): JpaQueryEffect {
        val (relations, unresolved) = sqlRelations(sql)
        return JpaQueryEffect(
            nativeRelations = relations.map { it.name },
            dynamic = if (unresolved > 0) listOf(sql) else emptyList(),
            kind = "native",
        )
    }

    /** 상속 CRUD처럼 선언이 없는 저장소 메서드의 효과다 — 도메인 엔티티의 테이블을 읽는다. */
    fun inheritedEffect(repository: JpaRepository, name: String): JpaQueryEffect? {
        if (name in NON_RELATION_METHODS) return null
        val entity = repository.entity ?: return JpaQueryEffect(dynamic = listOf(repository.domainType ?: name), kind = "unresolved-domain")
        return JpaQueryEffect(touch = JpaTouch().apply { entity(entity) }, kind = "inherited")
    }

    /** 저장소와 상위 사용자 저장소에서 이름·매개변수 수가 같은 선언 메서드를 찾는다. */
    fun declared(repository: JpaRepository, name: String, parameterCount: Int?): List<JpaRepositoryMethod> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(repository))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.type.jvmName)) continue
            val found = methods.filter { it.repository.type.jvmName == current.type.jvmName && it.method.name == name }
                .filter { parameterCount == null || it.method.parameterCount == parameterCount }
            if (found.isNotEmpty()) return if (current === repository) found else found.map { it.rebased(repository) }
            current.superRepositories.forEach { parent -> repositories.firstOrNull { it.type.name == parent }?.let(queue::add) }
        }
        return emptyList()
    }

    /** 상위 저장소에 선언된 파생 질의를 하위 저장소의 도메인으로 다시 해석한다(제네릭 기반 인터페이스). */
    private fun JpaRepositoryMethod.rebased(child: JpaRepository): JpaRepositoryMethod =
        if (repository.entity != null || child.entity == null) this
        else copy(repository = child, effect = effect(child, method))

    private companion object {
        val CLASS_LITERAL = Regex("([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:::class|\\.class)")

        /** SpEL `#{#entityName}`은 Spring Data가 저장소 도메인 엔티티 이름으로 치환한다. */
        val SPEL_ENTITY_NAME = Regex("#\\{#entityName}")

        /** 도메인 타입을 첫 타입 인자로 받는 Spring Data 기반 인터페이스(JPA·공통)다. */
        val SPRING_DATA_BASES = setOf(
            "Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository", "JpaRepositoryImplementation",
            "JpaSpecificationExecutor", "QueryByExampleExecutor", "ListQueryByExampleExecutor",
            "QuerydslPredicateExecutor", "ListQuerydslPredicateExecutor", "RevisionRepository",
            "CoroutineCrudRepository", "CoroutineSortingRepository",
        )

        /** 기반 인터페이스 메서드 이름이다 — 저장소가 반환 타입만 좁혀 다시 선언해도(`List<Job> findAll();`) 같은 CRUD다. */
        val BASE_METHODS = setOf(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "findById", "existsById", "findAll", "findAllById",
            "count", "deleteById", "delete", "deleteAllById", "deleteAll", "deleteAllInBatch", "deleteInBatch",
            "deleteAllByIdInBatch", "getOne", "getById", "getReferenceById", "findOne", "exists", "findBy",
        )

        /** 테이블을 읽지 않는 상속 메서드다. */
        val NON_RELATION_METHODS = setOf("flush", "equals", "hashCode", "toString", "getClass")
    }
}

/**
 * 문자열 리터럴 또는 리터럴끼리의 `+` 연결을 값으로 푼다 — Java 어노테이션의 여러 줄 JPQL이 흔히 쓰는 형태다.
 * 한 조각이라도 리터럴이 아니면 null이다.
 */
internal fun concatenatedLiteral(expression: String): String? {
    val pieces = mutableListOf<String>()
    var depth = 0
    var quote = false
    var raw = false
    var start = 0
    var index = 0
    val text = expression.trim()
    while (index < text.length) {
        when {
            text.startsWith("\"\"\"", index) -> { raw = !raw; index += 3; continue }
            raw -> Unit
            text[index] == '\\' && quote -> index++
            text[index] == '"' -> quote = !quote
            quote -> Unit
            text[index] == '(' -> depth++
            text[index] == ')' -> depth--
            text[index] == '+' && depth == 0 -> { pieces += text.substring(start, index); start = index + 1 }
        }
        index++
    }
    pieces += text.substring(start)
    return pieces.map { literal(it.trim().removeSurrounding("(", ")")) ?: return null }.joinToString("")
}

/** 선언 위치와 함께 모은 이름 있는 질의다(`@NamedQuery(name, query)`). */
internal data class JpaNamedQuery(val name: String, val query: String, val location: BridgeLocation)

/** 엔티티 파일의 `@NamedQuery`(묶음 포함)를 모은다. native `@NamedNativeQuery`는 SQL로 따로 싣는다. */
internal fun namedQueries(files: List<JpaSourceFile>, annotation: String): List<JpaNamedQuery> = files.flatMap { file ->
    file.types.flatMap { type ->
        val direct = type.annotations.filter { it.name == annotation }
        val grouped = type.annotations.filter { it.name == "${annotation}s" || it.name == "${annotation.removeSuffix("y")}ies" }
            .flatMap { group -> group.argumentRange?.let { file.annotationsInside(it) }.orEmpty().filter { it.name == annotation } }
        (direct + grouped).mapNotNull { query ->
            val name = query.argument("name")?.let(::literal) ?: return@mapNotNull null
            val text = query.argument("query")?.let(::concatenatedLiteral) ?: return@mapNotNull null
            JpaNamedQuery(name, text, file.location(query.offset))
        }
    }
}
