package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.qualifiedName
import java.nio.file.Path

/**
 * JPA·Spring Data persistence 사실을 만든다 — 엔티티 매핑 선언, 이름 있는 질의, 저장소 메서드 선언,
 * EntityManager 호출, 그리고 스냅샷이 있으면 저장소 호출 지점.
 *
 * **owner 규칙(스파이크 S3)**: 저장소 호출 지점 사실의 `symbol.usr`는 호출 명령을 담은 메서드의 JVM id다.
 * javac·kotlinc 모두 `repo.save(x)`를 사용자 저장소 인터페이스 owner의 `invokeinterface`로 기록하지만,
 * 상속 CRUD 메서드(`save`, `findById` …)는 프로젝트 정점이 없어 스냅샷에 외부 호출로만 남는다. 그래서
 * 핸들러의 정방향 도달 집합이 확실히 포함하는 것은 호출자 메서드뿐이다. 저장소에 선언된 메서드(파생·`@Query`)는
 * 인터페이스 메서드 정점과 CALL 간선이 있으므로 선언 위치 사실에는 그 인터페이스 메서드 id를 싣는다.
 */
internal class JpaPersistenceScanner(
    private val projectRoot: Path,
    private val files: List<JpaSourceFile>,
    private val naming: JpaNamingContext,
) {
    /** 스캔 결과다 — [consumed]는 SQL 리터럴 스캐너가 다시 읽지 않을 원문 범위다. */
    data class Result(val facts: List<BridgeFact>, val consumed: Map<String, List<IntRange>>, val limitations: List<String>)

    private class Stats {
        var namingDivergent = 0
        var unmodelled = mutableMapOf<String, Int>()
        var unresolvedQueries = 0
        var unresolvedMethods = 0
        var dynamicQueries = 0
        var missingCallLocations = 0
        var untypedCalls = 0
        var nonJpaRepositories = 0
        var untypedEntityOperations = 0
    }

    private val facts = mutableListOf<BridgeFact>()

    /** `owner#name` → 메서드 정점 색인이다 — 저장소 메서드마다 전체 정점을 훑지 않게 한 번만 만든다. */
    private var methodIndex: Map<String?, List<dev.kartograph.core.GraphNode>>? = null
    private val consumed = mutableMapOf<String, MutableList<IntRange>>()
    private val stats = Stats()

    fun scan(graph: CodeGraph?): Result {
        val persistence = files.filter { it.imports(*PERSISTENCE_PACKAGES) }
        val model = JpaEntityModel.build(persistence)
        val named = namedQueries(persistence, "NamedQuery")
        val nativeNamed = namedQueries(persistence, "NamedNativeQuery")
        val catalog = JpaRepositoryCatalog(files, model, named.associate { it.name to it.query })
        model.entities.forEach(::emitEntity)
        named.forEach { emitEffect(catalog.jpqlEffect(it.query, "named-query"), it.location, null) }
        nativeNamed.forEach { emitEffect(catalog.nativeEffect(it.query), it.location, null) }
        emitRepositories(catalog, graph)
        files.forEach { file -> scanEntityManager(file, model, catalog, named, nativeNamed, graph) }
        graph?.let { emitCallSites(catalog, it) }
        files.forEach(::consumeAnnotations)
        val hasSurface = model.entities.isNotEmpty() || catalog.repositories.any { it.entity != null } || named.isNotEmpty()
        return Result(facts.distinct(), consumed, limitations(hasSurface, catalog, graph))
    }

    // ---- 엔티티 선언 ----

    private fun emitEntity(entity: JpaEntity) {
        entity.table?.let { emitTable(it, entity.location, null) }
        entity.dynamicTable?.let { facts += dynamicFact(it, entity.location, null, null) }
        entity.declared.forEach(::emitAttribute)
        entity.extraColumns.forEach { (column, location) -> emitColumn(column, location, null) }
    }

    private fun emitAttribute(attribute: JpaAttribute) {
        attribute.unsupported?.let { reason ->
            stats.unmodelled.merge(reason, 1, Int::plus)
            val table = attribute.table?.let(naming::table)
            facts += dynamicFact(attribute.name, attribute.location, table, null)
            return
        }
        attribute.nested?.values?.forEach(::countNestedUnsupported)
        attribute.tables.forEach { emitTable(it, attribute.location, null) }
        attribute.columns.forEach { emitColumn(it, attribute.location, null) }
    }

    private fun countNestedUnsupported(attribute: JpaAttribute) {
        attribute.unsupported?.let { reason ->
            stats.unmodelled.merge(reason, 1, Int::plus)
            facts += dynamicFact(attribute.name, attribute.location, attribute.table?.let(naming::table), null)
        }
        attribute.nested?.values?.forEach(::countNestedUnsupported)
    }

    // ---- 저장소 ----

    private fun emitRepositories(catalog: JpaRepositoryCatalog, graph: CodeGraph?) {
        catalog.repositories.forEach { repository ->
            if (repository.entity == null) {
                stats.nonJpaRepositories++
                return@forEach
            }
            // 선언 없는 상속 CRUD 표면도 도메인 테이블을 읽는다 — 저장소 선언 위치에 관계 사용으로 남긴다.
            catalog.inheritedEffect(repository, "findAll")?.let { emitEffect(it, repository.file.location(repository.type.offset), null) }
        }
        catalog.methods.forEach { declared ->
            if (declared.repository.entity == null && declared.effect.kind != "native" && declared.effect.kind != "jpql") return@forEach
            when (declared.effect.kind) {
                "unresolved-method" -> stats.unresolvedMethods++
                "dynamic-query", "unresolved-query", "procedure", "unresolved-named-query" -> stats.dynamicQueries++
            }
            if (declared.effect.touch.unresolved.isNotEmpty()) stats.unresolvedQueries++
            val location = declared.repository.file.location(declared.method.offset)
            emitEffect(declared.effect, location, graph?.let { repositoryMethodSymbol(it, declared) })
        }
    }

    /** 저장소 인터페이스 메서드 정점이 이름·매개변수 수로 하나만 맞을 때 그 id다. */
    private fun repositoryMethodSymbol(graph: CodeGraph, declared: JpaRepositoryMethod): BridgeSymbol? {
        val index = methodIndex ?: graph.nodes.values.groupBy { node ->
            parseMethodId(node.id.value)?.let { (owner, name, _) -> "$owner#$name" }
        }.also { methodIndex = it }
        val candidates = index["${declared.repository.type.jvmName}#${declared.method.name}"].orEmpty()
        val exact = candidates.filter { parameterCount(it.id.value) == declared.method.parameterCount }
        // Kotlin suspend 메서드는 Continuation 매개변수가 하나 더 붙는다.
        val node = exact.singleOrNull() ?: candidates.singleOrNull() ?: return null
        return BridgeSymbol(node.qualifiedName, node.id.value)
    }

    /** 스냅샷의 CALL 간선과 외부 호출에서 저장소 호출 지점을 찾아 호출자 메서드 id로 사실을 싣는다. */
    private fun emitCallSites(catalog: JpaRepositoryCatalog, graph: CodeGraph) {
        val calls = graph.edges.filter { it.kind == EdgeKind.CALL }.mapNotNull { edge ->
            parseMethodId(edge.target.value)?.let { (owner, name, descriptor) -> CallSite(edge.source, owner, name, descriptor, null) }
        } + graph.externalCalls.map { CallSite(it.caller, it.owner, it.name, it.descriptor, it.location?.line) }
        calls.forEach { call -> emitCallSite(call, catalog, graph) }
    }

    private data class CallSite(val caller: NodeId, val owner: String, val name: String, val descriptor: String, val line: Int?)

    private fun emitCallSite(call: CallSite, catalog: JpaRepositoryCatalog, graph: CodeGraph) {
        val repository = catalog.byJvmName[call.owner]
        if (repository == null) {
            if (call.owner.startsWith(SPRING_DATA_PACKAGE) && call.owner.substringAfterLast('/') in REPOSITORY_BASE_NAMES) stats.untypedCalls++
            return
        }
        if (repository.entity == null) return
        // Kotlin suspend 메서드는 서술자에 Continuation이 더 붙으므로 매개변수 수가 안 맞으면 이름만으로 다시 찾는다.
        val declared = catalog.declared(repository, call.name, parameterCount(call.descriptor))
            .ifEmpty { catalog.declared(repository, call.name, null) }
        val effects = declared.map { it.effect }
            .ifEmpty { listOfNotNull(catalog.inheritedEffect(repository, call.name)) }
            .filterNot { it.isEmpty }
        if (effects.isEmpty()) return
        val caller = graph.node(call.caller)
        val path = caller?.location?.path?.replace('\\', '/')
        val line = call.line ?: caller?.location?.line
        if (caller == null || path == null || line == null || '/' !in path && !path.endsWith(".kt") && !path.endsWith(".java")) {
            stats.missingCallLocations++
            return
        }
        if (TEST_SOURCE.containsMatchIn(path)) return
        val location = BridgeLocation(path, line, 1)
        val symbol = BridgeSymbol(caller.qualifiedName, caller.id.value)
        effects.forEach { emitEffect(it, location, symbol) }
    }

    // ---- EntityManager ----

    private fun scanEntityManager(
        file: JpaSourceFile,
        model: JpaEntityModel,
        catalog: JpaRepositoryCatalog,
        named: List<JpaNamedQuery>,
        nativeNamed: List<JpaNamedQuery>,
        graph: CodeGraph?,
    ) {
        if (!file.imports(*PERSISTENCE_PACKAGES)) return
        val receivers = (KOTLIN_EM_RECEIVER.findAll(file.masked) + JAVA_EM_RECEIVER.findAll(file.masked))
            .map { it.groupValues[1] }.toSet()
        if (receivers.isEmpty()) return
        val pattern = Regex("\\b(?:${receivers.joinToString("|") { Regex.escape(it) }})\\s*(?:!!|\\?)?\\.\\s*($EM_METHODS)\\s*\\(")
        pattern.findAll(file.masked).forEach { match ->
            val open = match.range.last
            val close = balancedEnd(file.code, open)
            if (close <= open) return@forEach
            consumed.getOrPut(file.relative) { mutableListOf() } += open..close
            val first = callArguments(file.code, open, close).firstOrNull()?.trim().orEmpty()
            val location = file.location(match.range.first)
            val effect = entityManagerEffect(match.groupValues[1], first, model, catalog, named, nativeNamed) ?: return@forEach
            emitEffect(effect, location, graph?.let { snapshotSymbol(location, it) })
        }
    }

    private fun entityManagerEffect(
        method: String,
        argument: String,
        model: JpaEntityModel,
        catalog: JpaRepositoryCatalog,
        named: List<JpaNamedQuery>,
        nativeNamed: List<JpaNamedQuery>,
    ): JpaQueryEffect? = when (method) {
        "createQuery" -> concatenatedLiteral(argument)?.let { catalog.jpqlEffect(it, "jpql") } ?: dynamicEffect(argument)
        "createNativeQuery" -> concatenatedLiteral(argument)?.let(catalog::nativeEffect) ?: dynamicEffect(argument)
        "createNamedQuery" -> literal(argument)?.let { name ->
            named.firstOrNull { it.name == name }?.let { catalog.jpqlEffect(it.query, "named-query") }
                ?: nativeNamed.firstOrNull { it.name == name }?.let { catalog.nativeEffect(it.query) }
        } ?: dynamicEffect(argument)
        else -> {
            val typeName = CLASS_LITERAL.find(argument)?.groupValues?.get(1)
                ?: CONSTRUCTOR_CALL.matchEntire(argument)?.groupValues?.get(1)
            val entity = typeName?.let { model.entity(it) }
            if (entity != null) JpaQueryEffect(touch = JpaTouch().apply { entity(entity) }, kind = "entity-manager")
            else { stats.untypedEntityOperations++; dynamicEffect(argument) }
        }
    }

    private fun dynamicEffect(argument: String): JpaQueryEffect {
        stats.dynamicQueries++
        return JpaQueryEffect(dynamic = listOf(argument.ifBlank { "<entity-manager>" }), kind = "dynamic")
    }

    /** 소스 위치를 감싼 함수의 스냅샷 id다 — 다른 schema 사실과 같은 규칙이다. */
    private fun snapshotSymbol(location: BridgeLocation, graph: CodeGraph): BridgeSymbol? =
        attachSnapshotSymbol(placeholder(location), graph, projectRoot).symbol

    private fun placeholder(location: BridgeLocation): BridgeFact =
        BridgeFact(kind = "relation-use", channel = "", dynamic = false, location = location, target = "persistence")

    // ---- 방출 ----

    private fun emitEffect(effect: JpaQueryEffect, location: BridgeLocation, symbol: BridgeSymbol?) {
        effect.touch.tables.forEach { emitTable(it, location, symbol) }
        effect.touch.columns.forEach { emitColumn(it, location, symbol) }
        effect.touch.unresolved.forEach { facts += dynamicFact(it, location, null, symbol) }
        effect.nativeRelations.forEach { facts += relationFact(it, null, location, symbol) }
        effect.dynamic.forEach { facts += dynamicFact(it, location, null, symbol) }
    }

    private fun emitTable(table: JpaTableName, location: BridgeLocation, symbol: BridgeSymbol?) {
        val channel = naming.table(table)
        if (channel != null) {
            facts += relationFact(channel, null, location, symbol)
        } else {
            stats.namingDivergent++
            facts += dynamicFact(logicalText(table.name), location, null, symbol)
        }
    }

    private fun emitColumn(column: JpaColumnRef, location: BridgeLocation, symbol: BridgeSymbol?) {
        val table = naming.table(column.table)
        val name = naming.column(column.column)
        when {
            table != null && name != null -> facts += relationFact(table, name, location, symbol)
            else -> {
                stats.namingDivergent++
                facts += dynamicFact(logicalText(column.column), location, table, symbol)
            }
        }
    }

    /** 확정하지 못한 이름의 원문이다 — 첫 검증 조합의 논리 이름을 쓴다. */
    private fun logicalText(name: JpaLogicalName): String =
        JpaNamingProfile.VERIFIED.firstNotNullOfOrNull { name.logical(it)?.text } ?: "<jpa-name>"

    private fun relationFact(channel: String, column: String?, location: BridgeLocation, symbol: BridgeSymbol?): BridgeFact =
        BridgeFact(kind = "relation-use", channel = channel, method = column, dynamic = false, location = location, symbol = symbol, target = "persistence")

    private fun dynamicFact(expression: String, location: BridgeLocation, table: String?, symbol: BridgeSymbol?): BridgeFact =
        BridgeFact(
            kind = "relation-use",
            channel = table ?: safeJpaText(expression),
            dynamic = true,
            location = location,
            symbol = symbol,
            target = "persistence",
        )

    /** JPA·Spring Data 어노테이션 인자는 이 스캐너가 읽었다 — SQL 리터럴 스캐너가 다시 읽지 않게 한다. */
    private fun consumeAnnotations(file: JpaSourceFile) {
        if (!file.imports(*PERSISTENCE_PACKAGES, "org.springframework.data")) return
        val ranges = file.types.flatMap { type ->
            type.annotations + type.members.flatMap { it.annotations } + type.methods.flatMap { it.annotations }
        }.filter { it.name in CONSUMED_ANNOTATIONS }.mapNotNull { it.argumentRange }
        if (ranges.isNotEmpty()) consumed.getOrPut(file.relative) { mutableListOf() } += ranges
    }

    /** XML 매핑 파일(`orm.xml`의 `<entity-mappings>`)이다 — 테이블·컬럼·이름 있는 질의를 재정의할 수 있지만 읽지 않는다. */
    private fun xmlMappingFiles(): List<String> {
        val found = mutableListOf<String>()
        ProjectTraversal.walkSources(projectRoot, extensions = setOf("xml")) { path ->
            val text = ProjectTraversal.readSourceLines(projectRoot, path).joinToString("\n")
            if (XML_MAPPINGS.containsMatchIn(text)) found += projectRoot.toRealPath().relativize(path.toRealPath()).joinToString("/")
        }
        return found.sorted()
    }

    private fun limitations(hasSurface: Boolean, catalog: JpaRepositoryCatalog, graph: CodeGraph?): List<String> = buildList {
        val xml = if (hasSurface) xmlMappingFiles() else emptyList()
        if (xml.isNotEmpty()) add(
            "jpa-xml-mappings: ${xml.size} XML mapping file(s) (${xml.take(3).joinToString(", ")}) are not read; tables, " +
                "columns and named queries they define or override are not reflected",
        )
        if (hasSurface) add(
            "jpa-naming-assumed: JPA table and column names follow ${naming.evidence}; naming overrides outside the scanned " +
                "sources (environment, external configuration) are not observed",
        )
        if (stats.namingDivergent > 0) add(
            "jpa-naming-unresolved: ${stats.namingDivergent} JPA table or column name(s) differ across the candidate naming " +
                "strategies or depend on a custom strategy; kept as dynamic",
        )
        if (stats.unmodelled.isNotEmpty()) add(
            "jpa-unmodelled-mappings: ${stats.unmodelled.values.sum()} attribute(s) use mappings kartograph does not model " +
                "(${stats.unmodelled.keys.sorted().joinToString(", ")}); kept as dynamic",
        )
        if (stats.unresolvedQueries > 0) add(
            "jpa-unresolved-query-paths: ${stats.unresolvedQueries} query method(s) reference entities or properties that " +
                "could not be resolved; the unresolved parts are dynamic",
        )
        if (stats.unresolvedMethods > 0) add(
            "unresolved-repository-methods: ${stats.unresolvedMethods} repository method(s) are neither annotated, named nor " +
                "derivable queries (custom fragments or unsupported keywords); kept as dynamic",
        )
        if (stats.dynamicQueries > 0) add(
            "dynamic-jpa-queries: ${stats.dynamicQueries} query string(s) or procedure call(s) are not statically readable; kept as dynamic",
        )
        if (stats.nonJpaRepositories > 0) add(
            "non-jpa-repositories: ${stats.nonJpaRepositories} Spring Data repositor(y/ies) have a domain type that is not a " +
                "scanned JPA entity; their queries are not claimed",
        )
        if (stats.untypedEntityOperations > 0) add(
            "untyped-entity-manager-operations: ${stats.untypedEntityOperations} EntityManager operation(s) take an entity " +
                "whose type is not visible at the call; kept as dynamic",
        )
        if (graph == null && catalog.repositories.any { it.entity != null }) add(
            "repository-call-sites-need-snapshot: repository call sites (including inherited CRUD methods) are attributed " +
                "to calling methods only with --graph-file; declarations are still reported",
        )
        if (stats.missingCallLocations > 0) add(
            "missing-repository-call-locations: ${stats.missingCallLocations} repository call(s) in the snapshot have no " +
                "project source path or line; capture it with snapshot --include-paths",
        )
        if (stats.untypedCalls > 0) add(
            "untyped-repository-calls: ${stats.untypedCalls} call(s) target a Spring Data base interface directly, so " +
                "the domain entity is not visible; not claimed",
        )
    }

    private companion object {
        val PERSISTENCE_PACKAGES = arrayOf("jakarta.persistence", "javax.persistence")
        const val SPRING_DATA_PACKAGE = "org/springframework/data/"
        val REPOSITORY_BASE_NAMES = setOf(
            "Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository", "JpaSpecificationExecutor", "QueryByExampleExecutor",
        )
        val XML_MAPPINGS = Regex("<(?:[A-Za-z0-9_]+:)?entity-mappings\\b")
        val TEST_SOURCE = Regex("(^|/)src/(test|androidTest|testFixtures)/")
        val KOTLIN_EM_RECEIVER = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(?:[A-Za-z_.]*\\.)?EntityManager\\b(?!Factory)")
        val JAVA_EM_RECEIVER = Regex("\\b(?:[A-Za-z_.]*\\.)?EntityManager\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
        const val EM_METHODS = "createQuery|createNativeQuery|createNamedQuery|find|getReference|persist|merge|remove|refresh|detach|lock"
        val CLASS_LITERAL = Regex("^([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:::class(?:\\.java)?|\\.class)$")
        val CONSTRUCTOR_CALL = Regex("^(?:new\\s+)?([A-Z][A-Za-z0-9_]*)\\s*\\(.*\\)$", RegexOption.DOT_MATCHES_ALL)
        val CONSUMED_ANNOTATIONS = setOf(
            "Table", "Column", "JoinColumn", "JoinColumns", "JoinTable", "CollectionTable", "AttributeOverride",
            "AttributeOverrides", "DiscriminatorColumn", "PrimaryKeyJoinColumn", "Entity", "Query", "NamedQuery",
            "NamedQueries", "NamedNativeQuery", "NamedNativeQueries", "SecondaryTable", "OrderColumn", "Procedure",
        )
    }
}

/** `method:owner#name(desc)ret` id를 (owner, name, descriptor)로 나눈다. */
internal fun parseMethodId(id: String): Triple<String, String, String>? {
    if (!id.startsWith("method:")) return null
    val hash = id.indexOf('#').takeIf { it > 7 } ?: return null
    val open = id.indexOf('(', hash).takeIf { it > hash } ?: return null
    return Triple(id.substring(7, hash), id.substring(hash + 1, open), id.substring(open))
}

/** JVM 메서드 서술자(또는 그것을 담은 id)의 매개변수 개수다. */
internal fun parameterCount(descriptorOrId: String): Int? {
    val open = descriptorOrId.indexOf('(').takeIf { it >= 0 } ?: return null
    val close = descriptorOrId.indexOf(')', open).takeIf { it > open } ?: return null
    var count = 0
    var index = open + 1
    while (index < close) {
        when (descriptorOrId[index]) {
            '[' -> { index++; continue }
            'L' -> index = descriptorOrId.indexOf(';', index).takeIf { it in index until close } ?: return null
        }
        count++
        index++
    }
    return count
}

private const val MAX_JPA_DYNAMIC_TEXT = 160

/** 동적 채널 문자열은 계약의 안전 문자열이어야 한다 — 제어 문자를 공백으로 바꾸고 길이를 자른다. */
internal fun safeJpaText(value: String): String =
    value.replace(Regex("\\p{C}"), " ").trim().take(MAX_JPA_DYNAMIC_TEXT).ifBlank { "<dynamic>" }
