package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.CodeGraph
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Kotlin/Java 소스에서 persistence 도메인의 relation-use 사실을 수확해
 * bridge-facts v1 문서로 만든다.
 *
 * 지원 표면은 증거가 있는 것으로 한정한다 — import로 게이트된 Room 어노테이션,
 * JDBC 호출 인자, Exposed Table 객체·DSL 수신자, jOOQ plain-SQL 메서드,
 * SQL 모양 문자열 리터럴, SQLDelight `.sq`/`.sqm` 파일. 그 밖의 프레임워크
 * (JPA Criteria·Spring Data 파생 쿼리·Ktorm·jdbi 등)는 지원한다고 주장하지
 * 않고 게이트가 관측되면 limitation으로만 센다.
 *
 * 동적이거나 미해석인 근거는 버리지 않는다 — dynamic 사실과 limitation으로
 * 남겨 소비자가 불확실성을 판단하게 한다.
 */
public class SchemaFactScanner(private val projectRoot: Path) {

    /**
     * 프로젝트를 스캔해 persistence 사실 문서를 만든다.
     * 사실이 하나도 없으면 계약대로 `target`은 null이다.
     * generatedAt은 추출 시각이며 최신 source 수정 시각은 sourceModifiedAt에 별도로 보존한다.
     */
    public fun scan(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument {
        val root = projectRoot.toRealPath()
        val files = mutableListOf<Path>()
        ProjectTraversal.walkSources(projectRoot, extensions = SCHEMA_EXTENSIONS) { files.add(it) }
        files.sort()
        val stats = ScanStats()
        val sources = files.map { SourceFile(root, it, stats) }
        // 선언 패스를 먼저 돌린다 — DAO 파라미터 타입·DSL 수신자가 선언된
        // 테이블 이름을 나중에 참조하기 때문이다.
        val entities = mutableMapOf<String, String>()
        val exposedTables = mutableMapOf<String, String>()
        sources.forEach { collectDeclarations(it, entities, exposedTables, stats) }
        val facts = mutableListOf<BridgeFact>()
        sources.forEach { scanFile(it, entities, exposedTables, facts, stats) }
        val ordered = facts.sortedWith(
            compareBy(
                { it.location.path }, { it.location.line }, { it.location.column },
                { it.channel ?: "" }, { it.method.orEmpty() },
            ),
        )
        val withSymbols = ordered.map {
            graph?.let { snapshot -> attachSnapshotSymbol(it, snapshot, projectRoot) } ?: it
        }
        return BridgeFactsDocument(
            generatedAt = bridgeTimestamp(generatedAt?.let(Instant::parse) ?: Instant.now()),
            sourceModifiedAt = files.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() }
                ?.let(::bridgeTimestamp),
            platform = "kotlin",
            target = if (withSymbols.isEmpty()) null else "persistence",
            project = root.toString().replace('\\', '/'),
            facts = withSymbols,
            limitations = limitations(stats).distinct().sorted(),
        )
    }

    /** 파일 하나의 스캔 컨텍스트다 — 원문·주석 제거 뷰·마스킹 뷰와 게이트를 한 번만 만든다. */
    private class SourceFile(root: Path, path: Path, stats: ScanStats) {
        val relative: String = root.relativize(path.toAbsolutePath().normalize()).joinToString("/")
        val isSqlDelight: Boolean = path.fileName.toString().let {
            it.endsWith(".sq") || it.endsWith(".sqm")
        }
        val isJava: Boolean = path.fileName.toString().endsWith(".java")
        val source: String = ProjectTraversal.readSourceLines(root, path).joinToString("\n")
        // .sq 파일은 SQL이라 주석 제거를 SQL 렉서가 맡는다.
        val code: String = if (isSqlDelight) source else stripComments(source)
        val masked: String = maskStringContents(code)
        val room = ROOM_GATE.containsMatchIn(masked)
        val jpa = JPA_GATE.containsMatchIn(masked)
        val jdbc = JDBC_GATE.containsMatchIn(masked)
        val exposed = EXPOSED_GATE.containsMatchIn(masked)
        val jooq = JOOQ_GATE.containsMatchIn(masked)
        val typeDecls = mutableListOf<TypeDecl>()
        val entityDecls = mutableListOf<EntityDecl>()
        val exposedDecls = mutableListOf<ExposedDecl>()

        init {
            if (jpa) stats.jpaSources++
            if (isSqlDelight) stats.sqlDelightFiles++
        }

        /** 어노테이션의 괄호 인자를 읽는다 — `(`가 없거나 닫히지 않으면 빈 목록이다. */
        fun annotationArgs(after: Int): AnnotationArgs {
            var index = after
            while (index < code.length && code[index].isWhitespace()) index++
            if (code.getOrNull(index) != '(') return AnnotationArgs(emptyList(), null)
            val end = balancedEnd(code, index)
            if (end <= index) return AnnotationArgs(emptyList(), null)
            return AnnotationArgs(callArguments(code, index, end), index..end)
        }
    }

    /** class·object 선언과 그 몸체·선언부 범위다. */
    private data class TypeDecl(
        val name: String,
        val start: Int,
        val headerEnd: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    /** Room·JPA 엔티티 선언 하나의 수확 결과다. channel이 null이면 테이블명을 정적으로 못 읽었다는 뜻이다. */
    private data class EntityDecl(
        val offset: Int,
        val typeName: String,
        val channel: String?,
        val dynamicChannel: String?,
        val decl: TypeDecl?,
    )

    /** Exposed Table-family 객체 선언 하나다. */
    private data class ExposedDecl(val offset: Int, val objectName: String, val channel: String, val decl: TypeDecl?)

    private data class AnnotationArgs(val values: List<String>, val range: IntRange?)

    private class ScanStats {
        var unjoinedDynamic = 0
        var unresolvedExposed = 0
        var unresolvedEntities = 0
        var unattributedColumns = 0
        var jpaSources = 0
        var sqlDelightFiles = 0
    }

    /** 타입 선언 목록을 채우고 Room·JPA 엔티티와 Exposed 객체의 이름→채널 바인딩을 수집한다. */
    private fun collectDeclarations(
        file: SourceFile,
        entities: MutableMap<String, String>,
        exposedTables: MutableMap<String, String>,
        stats: ScanStats,
    ) {
        if (file.isSqlDelight) return
        val declMatches = TYPE_DECL.findAll(file.masked).toList()
        declMatches.forEachIndexed { index, match ->
            val bound = declMatches.getOrNull(index + 1)?.range?.first ?: file.masked.length
            file.typeDecls += typeDecl(file, match.groupValues[1], match.range.first, match.range.last + 1, bound)
        }
        if (file.room || file.jpa) {
            ENTITY_ANNOTATION.findAll(file.masked).forEach { match ->
                val args = file.annotationArgs(match.range.last + 1)
                val tableArg = args.values.firstNamed("tableName")
                    ?: args.values.firstNamed("viewName")
                    ?: args.values.firstOrNull()?.takeUnless { it.isNamedArgument() }
                val (value, dynamic) = tableArg?.let(::literalOrDynamicChannel) ?: (null to false)
                val decl = file.typeDecls.firstOrNull { it.start > match.range.last }
                val channel = when {
                    value != null && !dynamic -> escapeName(value)
                    dynamic -> null
                    decl != null -> escapeName(decl.name) // Room·JPA 기본 테이블명은 타입 이름이다.
                    else -> null
                }
                file.entityDecls += EntityDecl(
                    offset = match.range.first,
                    typeName = decl?.name.orEmpty(),
                    channel = channel,
                    dynamicChannel = if (dynamic) tableArg?.trim() else null,
                    decl = decl,
                )
                if (decl != null && channel != null) entities[decl.name] = channel
                if (file.jpa && decl != null) {
                    // JPA는 @Table(name=..)가 테이블명의 정본이고 @Entity(name=..)는 JPQL 별칭이다.
                    tableNameFor(file, decl.start)?.let { entities[decl.name] = escapeName(it) }
                    args.values.firstNamed("name")?.let { expr ->
                        val (entityName, entityDynamic) = literalOrDynamicChannel(expr)
                        if (!entityDynamic && entityName != null && entities[decl.name] != null) {
                            entities[entityName] = entities.getValue(decl.name)
                        }
                    }
                }
            }
        }
        if (file.exposed) {
            EXPOSED_OBJECT.findAll(file.masked).forEach { match ->
                val objectName = match.groupValues[1]
                val open = match.range.last
                val args = if (file.masked[open] == '(') {
                    val end = balancedEnd(file.code, open)
                    if (end > open) callArguments(file.code, open, end) else emptyList()
                } else emptyList()
                val (value, dynamic) = args.firstOrNull()?.takeUnless { it.isNamedArgument() }
                    ?.let(::literalOrDynamicChannel) ?: (null to false)
                // Exposed는 인자 없는 Table()의 테이블명으로 object 이름을 쓴다.
                val channel = if (value != null && !dynamic) escapeName(value) else escapeName(objectName)
                val decl = file.typeDecls.firstOrNull { it.name == objectName && it.start <= match.range.first }
                file.exposedDecls += ExposedDecl(match.range.first, objectName, channel, decl)
                if (dynamic) {
                    // 이름이 동적인 Table 호출은 바인딩이 불확실하다 — 맵에 싣지 않고 근거만 센다.
                    stats.unjoinedDynamic++
                } else {
                    exposedTables[objectName] = channel
                }
            }
        }
    }

    /** 선언 머리(`class X(...)`)와 몸체(`{...}`) 범위를 계산한다 — 몸체 괄호는 괄호 깊이 0에서만 본다. */
    private fun typeDecl(file: SourceFile, name: String, start: Int, nameEnd: Int, bound: Int): TypeDecl {
        var depth = 0
        var index = nameEnd
        var bodyStart = -1
        while (index < bound) {
            when (file.masked[index]) {
                '(' -> depth++
                ')' -> depth--
                '{' -> if (depth == 0) { bodyStart = index; break }
                ';' -> break // 몸체 없이 끝난 선언이다.
            }
            index++
        }
        val bodyEnd = if (bodyStart >= 0) braceEnd(file.masked, bodyStart) else -1
        return TypeDecl(name, start, index, bodyStart, bodyEnd)
    }

    /** 사실 방출 패스다 — 선언 맵을 소비해 relation-use 사실을 만든다. */
    private fun scanFile(
        file: SourceFile,
        entities: Map<String, String>,
        exposedTables: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
    ) {
        if (file.isSqlDelight) {
            scanSqlDelight(file, facts, stats)
            return
        }
        val consumed = mutableListOf<IntRange>()
        // 선언된 엔티티 자체가 관계 사용이다 — 테이블명과 어노테이션 위치를 남긴다.
        file.entityDecls.forEach { entity ->
            facts += BridgeFact(
                kind = "relation-use",
                channel = entity.channel ?: safeDynamicChannel(entity.dynamicChannel),
                dynamic = entity.channel == null,
                location = sourceLocation(file.relative, file.source, entity.offset),
                target = "persistence",
            )
            entity.decl?.let { scanEntityColumns(file, it, entity.channel, entities, facts, stats, consumed) }
        }
        file.exposedDecls.forEach { decl ->
            facts += relationFact(decl.channel, sourceLocation(file.relative, file.source, decl.offset))
            decl.decl?.let { scanExposedColumns(file, it, decl.channel, exposedTables, facts, stats, consumed) }
        }
        if (file.room || file.jpa) {
            QUERY_ANNOTATION.findAll(file.masked).forEach { match ->
                val args = file.annotationArgs(match.range.last + 1)
                args.range?.let(consumed::add)
                val location = sourceLocation(file.relative, file.source, match.range.first)
                when (match.groupValues[1]) {
                    "Query" -> {
                        // Room·JPQL은 쿼리 안의 엔티티명을 테이블명으로 해석한다.
                        val expr = args.values.firstNamed("value")
                            ?: args.values.firstOrNull()?.takeUnless { it.isNamedArgument() }
                        emitSqlExpression(expr, location, entities, facts, stats, translateEntities = true)
                    }
                    "RawQuery" -> {
                        // 관측 쿼리는 호출 시점에 도착한다 — 항상 동적 사실이다.
                        stats.unjoinedDynamic++
                        facts += dynamicFact(args.values.firstOrNull(), location)
                    }
                    else -> emitEntityOperation(file, match, args, entities, facts, stats)
                }
            }
            COLUMN_INFO.findAll(file.masked).forEach { match ->
                // 엔티티 몸체 밖의 @ColumnInfo는 어느 테이블의 컬럼인지 귀속할 수 없다.
                val inside = file.entityDecls.any { d ->
                    d.decl?.let { match.range.first in declRange(it) } == true
                }
                if (!inside) stats.unattributedColumns++
            }
        }
        if (file.jdbc) scanSqlCalls(file, JDBC_SQL_CALL, entities, facts, stats, consumed)
        if (file.jooq) {
            scanSqlCalls(file, JOOQ_SQL_CALL, entities, facts, stats, consumed)
            JOOQ_RELATION_CALL.findAll(file.masked).forEach { match ->
                val open = match.range.last
                val end = balancedEnd(file.code, open)
                if (end <= open) return@forEach
                consumed += open..end
                // 인자가 아예 없는 호출(`selectCount()`)에는 관계 피연산자가 없다 — 동적 사실도 내지 않는다.
                val args = callArguments(file.code, open, end)
                if (args.isEmpty()) return@forEach
                val expr = args.firstOrNull()?.takeUnless { it.isNamedArgument() }
                val location = sourceLocation(file.relative, file.source, match.range.first)
                val (value, dynamic) = expr?.let(::literalOrDynamicChannel) ?: (null to true)
                if (value != null && !dynamic) {
                    facts += relationFact(escapeQualified(value), location)
                } else {
                    stats.unjoinedDynamic++
                    facts += dynamicFact(expr, location)
                }
            }
        }
        if (file.exposed) {
            scanExposedUses(file, exposedTables, facts, stats)
            scanSchemaUtils(file, exposedTables, facts, stats)
        }
        scanSqlLiterals(file, entities, facts, stats, consumed)
    }

    /** SQLDelight `.sq`/`.sqm` 파일 전체를 SQL로 읽는다 — 라벨(`name:`)은 렉서가 무시한다. */
    private fun scanSqlDelight(file: SourceFile, facts: MutableList<BridgeFact>, stats: ScanStats) {
        val (relations, unresolved) = sqlRelations(file.source)
        relations.forEach { relation ->
            facts += relationFact(
                relation.name,
                sourceLocation(file.relative, file.source, relation.keyword),
            )
        }
        if (unresolved > 0) {
            stats.unjoinedDynamic += unresolved
            facts += dynamicFact("<sql file>", sourceLocation(file.relative, file.source, 0))
        }
    }

    /** 엔티티 생성자·몸체 안의 컬럼 근거를 method 필드에 싣는다. */
    private fun scanEntityColumns(
        file: SourceFile,
        decl: TypeDecl,
        channel: String?,
        entities: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
        consumed: MutableList<IntRange>,
    ) {
        if (channel == null) return // 테이블명이 동적이면 컬럼 귀속도 불확실하다.
        // @ColumnInfo(name=..)는 다음 프로퍼티 선언에 귀속한다 — 선언 순서대로 소비한다.
        val named = mutableListOf<Pair<Int, String>>()
        COLUMN_INFO.findAll(file.masked).forEach { match ->
            if (match.range.first !in declRange(decl)) return@forEach
            val args = file.annotationArgs(match.range.last + 1)
            args.range?.let(consumed::add)
            val expr = args.values.firstNamed("name")
            val (value, dynamic) = expr?.let(::literalOrDynamicChannel) ?: (null to false)
            val location = sourceLocation(file.relative, file.source, match.range.first)
            when {
                dynamic -> {
                    stats.unjoinedDynamic++
                    facts += dynamicFact(expr, location, channel)
                }
                value != null -> named += match.range.last to value
            }
        }
        // 생성자 파라미터의 val/var와 몸체 직속 프로퍼티·필드가 컬럼이다 — 중첩
        // 블록 안의 지역 변수는 깊이로 걸러낸다.
        val columnNames = sortedMapOf<Int, String>()
        PROPERTY_DECL.findAll(file.masked).forEach { match ->
            if (memberAt(file, decl, match.range.first)) columnNames[match.range.first] = match.groupValues[1]
        }
        if (file.isJava) {
            JAVA_FIELD.findAll(file.masked).forEach { match ->
                if (memberAt(file, decl, match.range.first)) columnNames[match.range.first] = match.groupValues[1]
            }
        }
        columnNames.forEach { (offset, property) ->
            val explicit = named.lastOrNull { it.first < offset }?.also { named.remove(it) }?.second
            facts += relationFact(
                channel,
                sourceLocation(file.relative, file.source, offset),
                method = explicit ?: property,
            )
        }
        // 어느 프로퍼티에도 붙지 않은 @ColumnInfo 이름도 선언된 컬럼 근거다.
        named.forEach { (offset, name) ->
            facts += relationFact(channel, sourceLocation(file.relative, file.source, offset), method = name)
        }
        // @ForeignKey의 부모 테이블·컬럼도 관계 사용이다.
        FOREIGN_KEY.findAll(file.masked).forEach { match ->
            if (match.range.first !in declRange(decl)) return@forEach
            val args = file.annotationArgs(match.range.last + 1)
            args.range?.let(consumed::add)
            val location = sourceLocation(file.relative, file.source, match.range.first)
            val parent = entityNameArg(args.values.firstNamed("entity"))
            val parentChannel = parent?.let { entities[it] ?: entities[it.substringAfterLast('.')] }
            if (parentChannel == null) {
                if (parent != null) {
                    stats.unresolvedEntities++
                    facts += dynamicFact(parent, location)
                }
                return@forEach
            }
            facts += relationFact(parentChannel, location)
            args.values.firstNamed("parentColumns")?.let { list ->
                stringList(list).forEach { column ->
                    facts += relationFact(parentChannel, location, method = column)
                }
            }
            args.values.firstNamed("childColumns")?.let { list ->
                stringList(list).forEach { column ->
                    facts += relationFact(channel, location, method = column)
                }
            }
        }
    }

    /** 위치가 선언의 직속 멤버 구간(헤더 괄호 안·몸체 깊이 0)인지 본다 — 중첩 블록의 지역 변수와 중첩 선언의 멤버를 걸러낸다. */
    private fun memberAt(file: SourceFile, decl: TypeDecl, offset: Int): Boolean {
        // 중첩 class·object의 멤버는 바깥 선언의 컬럼이 아니다.
        if (file.typeDecls.any { other -> other !== decl && other.start > decl.start && offset in declRange(other) }) {
            return false
        }
        return when {
            offset in decl.start..decl.headerEnd -> true
            decl.bodyStart >= 0 && offset in decl.bodyStart..decl.bodyEnd ->
                braceDepth(file.masked, decl.bodyStart + 1, offset) == 0
            else -> false
        }
    }

    /** 어노테이션 귀속에 쓰는 선언 전체 범위다 — 몸체 없는 선언은 헤더 끝까지다. */
    private fun declRange(decl: TypeDecl): IntRange = decl.start..maxOf(decl.headerEnd, decl.bodyEnd)

    /** Exposed 객체 몸체 안의 컬럼 팩토리 호출을 method 사실로 읽는다. */
    private fun scanExposedColumns(
        file: SourceFile,
        decl: TypeDecl,
        channel: String,
        exposedTables: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
        consumed: MutableList<IntRange>,
    ) {
        if (decl.bodyStart < 0) return
        COLUMN_FACTORY.findAll(file.masked).forEach { match ->
            if (!memberAt(file, decl, match.range.first)) return@forEach
            val open = match.range.last
            val end = balancedEnd(file.code, open)
            if (end <= open) return@forEach
            consumed += open..end
            val args = callArguments(file.code, open, end)
            val (value, dynamic) = args.firstOrNull()?.takeUnless { it.isNamedArgument() }
                ?.let(::literalOrDynamicChannel) ?: (null to true)
            val location = sourceLocation(file.relative, file.source, match.range.first)
            if (value != null && !dynamic) {
                facts += relationFact(channel, location, method = value)
            } else {
                // 컬럼명을 못 읽어도 테이블 귀속은 확실하다 — 동적 근거로 남긴다.
                stats.unjoinedDynamic++
                facts += dynamicFact(args.firstOrNull(), location, channel)
            }
            // reference("col", OtherTable)의 테이블 인자는 부모 관계 참조다.
            if (match.groupValues[1] in REFERENCE_FACTORIES) {
                args.drop(1).forEach { arg ->
                    IDENTIFIER.find(arg.trim().substringAfterLast('='))?.groupValues?.get(1)
                        ?.let { parent ->
                            val target = exposedTables[parent]
                            if (target != null) {
                                facts += relationFact(target, location)
                            } else {
                                stats.unresolvedExposed++
                                facts += dynamicFact(parent, location)
                            }
                        }
                }
            }
        }
    }

    /** Exposed DSL 수신자 호출(`Users.select { .. }`)을 관계 사용으로 읽는다. */
    private fun scanExposedUses(
        file: SourceFile,
        exposedTables: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
    ) {
        EXPOSED_USE.findAll(file.masked).forEach { match ->
            val receiver = match.groupValues[1]
            val location = sourceLocation(file.relative, file.source, match.range.first)
            val channel = exposedTables[receiver]
            if (channel != null) {
                facts += relationFact(channel, location)
            } else {
                // 대문자 시작 수신자의 DSL 동사는 Table 객체일 가능성이 높다 —
                // 선언을 못 찾았으면 동적 사실로 남겨 근거를 버리지 않는다.
                stats.unresolvedExposed++
                facts += dynamicFact(receiver, location)
            }
            // join 계열의 인자 안 수신자도 읽는다 — `Users.innerJoin(Orders)`.
            if (match.groupValues[2] in JOIN_VERBS) {
                val open = file.masked.indexOf('(', match.range.last + 1).takeIf { it >= 0 }
                if (open != null && file.masked.substring(match.range.last + 1, open).isBlank()) {
                    val end = balancedEnd(file.code, open)
                    if (end > open) {
                        // 같은 호출 안에서 같은 수신자는 한 번만 센다 — 조건식의
                        // `Users.id` 같은 참조가 수신자 사실을 중복으로 만들지 않게 한다.
                        val seenReceivers = mutableSetOf(receiver)
                        EXPOSED_RECEIVER.findAll(file.code.substring(open + 1, end)).forEach { inner ->
                            val innerName = inner.groupValues[1]
                            if (!seenReceivers.add(innerName)) return@forEach
                            val target = exposedTables[innerName]
                            if (target != null) {
                                facts += relationFact(target, location)
                            } else {
                                // 선언을 못 찾은 대문자 수신자는 다른 모듈의 Table일 수 있다 — 근거를 남긴다.
                                stats.unresolvedExposed++
                                facts += dynamicFact(innerName, location)
                            }
                        }
                    }
                }
            }
        }
    }

    /** `SchemaUtils.create(Users)` 같은 DDL 호출의 테이블 인자를 읽는다. */
    private fun scanSchemaUtils(
        file: SourceFile,
        exposedTables: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
    ) {
        SCHEMA_UTILS.findAll(file.masked).forEach { match ->
            val open = match.range.last
            val end = balancedEnd(file.code, open)
            if (end <= open) return@forEach
            val location = sourceLocation(file.relative, file.source, match.range.first)
            val seenReceivers = mutableSetOf<String>()
            EXPOSED_RECEIVER.findAll(file.code.substring(open + 1, end)).forEach { inner ->
                val innerName = inner.groupValues[1]
                if (!seenReceivers.add(innerName)) return@forEach
                val target = exposedTables[innerName]
                if (target != null) {
                    facts += relationFact(target, location)
                } else {
                    stats.unresolvedExposed++
                    facts += dynamicFact(innerName, location)
                }
            }
        }
    }

    /** JDBC·jOOQ처럼 첫 인자가 SQL 문인 호출을 스캔한다. */
    private fun scanSqlCalls(
        file: SourceFile,
        pattern: Regex,
        entities: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
        consumed: MutableList<IntRange>,
    ) {
        pattern.findAll(file.masked).forEach { match ->
            val open = match.range.last
            if (consumed.any { open in it }) return@forEach // 다른 게이트가 먼저 읽은 호출
            val end = balancedEnd(file.code, open)
            if (end <= open) return@forEach
            consumed += open..end
            // 인자 없는 호출(`stmt.execute()`)은 이미 준비된 문을 실행하는 것 — 관계 피연산자가 없다.
            val args = callArguments(file.code, open, end)
            if (args.isEmpty()) return@forEach
            val expr = args.firstOrNull()?.takeUnless { it.isNamedArgument() }
            emitSqlExpression(expr, sourceLocation(file.relative, file.source, match.range.first), entities, facts, stats)
        }
    }

    /** SQL 문자열 표현식 하나를 관계 사실로 변환한다 — 비리터럴은 동적 사실로 남긴다. */
    private fun emitSqlExpression(
        expression: String?,
        location: BridgeLocation,
        entities: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
        translateEntities: Boolean = false,
    ) {
        val (value, dynamic) = expression?.let(::literalOrDynamicChannel) ?: (null to true)
        if (value != null && !dynamic) {
            emitSql(value, location, entities, facts, stats, translateEntities = translateEntities)
        } else {
            stats.unjoinedDynamic++
            facts += dynamicFact(expression, location)
        }
    }

    /**
     * 디코딩된 SQL 문자열에서 관계를 읽는다.
     * [translateEntities]는 Room `@Query`·JPQL처럼 엔티티명이 테이블명으로 해석되는
     * 문맥에서만 켠다 — JDBC의 `"FROM Region"`은 진짜 테이블 이름일 수 있다.
     * [strict]는 게이트 없는 리터럴용이다 — 소문자 키워드는 산문으로 보고 발화하지 않는다.
     */
    private fun emitSql(
        sql: String,
        location: BridgeLocation,
        entities: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
        strict: Boolean = false,
        translateEntities: Boolean = false,
    ) {
        val (relations, unresolved) = sqlRelations(sql, strict)
        relations.forEach { relation ->
            val name = if (translateEntities) entities[relation.name] ?: relation.name else relation.name
            facts += relationFact(name, location)
        }
        if (unresolved > 0) {
            stats.unjoinedDynamic += unresolved
            facts += dynamicFact(sql, location)
        }
    }

    /** 게이트 호출이 소비하지 않은 문자열 리터럴 전부를 SQL 모양으로 걸러 스캔한다. */
    private fun scanSqlLiterals(
        file: SourceFile,
        entities: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
        consumed: List<IntRange>,
    ) {
        eachLiteral(file.code) { start, end, raw ->
            if (consumed.any { start in it }) return@eachLiteral
            val body = if (raw) file.code.substring(start + 3, end - 3) else file.code.substring(start + 1, end - 1)
            val interpolation = interpolationIndex(body, raw)
            val location = sourceLocation(file.relative, file.source, start)
            if (interpolation >= 0) {
                val prefix = if (raw) decodeRawLiteral(body.substring(0, interpolation))
                else decodeLiteral(body.substring(0, interpolation))
                // 보간된 SQL은 관계가 문자열 바깥에 있다 — 접두어가 SQL 모양일 때만 동적 근거로 남긴다.
                // 게이트 없는 리터럴이라 strict 판정이다 — 산문 속 소문자 키워드는 발화하지 않는다.
                if (looksLikeSql(prefix, strict = true)) {
                    stats.unjoinedDynamic++
                    facts += BridgeFact(
                        kind = "relation-use",
                        channel = safeDynamicChannel(file.code.substring(start, end)),
                        dynamic = true,
                        location = location,
                        target = "persistence",
                        channelPrefix = safeText(prefix).takeIf { it.isNotEmpty() },
                    )
                }
            } else {
                val decoded = if (raw) decodeRawLiteral(body) else decodeLiteral(body)
                if (looksLikeSql(decoded, strict = true)) {
                    emitSql(
                        decoded, location, entities, facts, stats,
                        strict = true, translateEntities = file.room || file.jpa,
                    )
                }
            }
        }
    }

    /** `@Insert`·`@Update`·`@Delete`·`@Upsert`의 대상 엔티티를 관계 사용으로 읽는다. */
    private fun emitEntityOperation(
        file: SourceFile,
        match: MatchResult,
        args: AnnotationArgs,
        entities: Map<String, String>,
        facts: MutableList<BridgeFact>,
        stats: ScanStats,
    ) {
        val location = sourceLocation(file.relative, file.source, match.range.first)
        val typeName = entityNameArg(args.values.firstNamed("entity")) ?: followingParamType(file, match.range.last + 1)
        val channel = typeName?.let { entities[it] ?: entities[it.substringAfterLast('.')] }
        if (channel != null) {
            facts += relationFact(channel, location)
        } else {
            stats.unresolvedEntities++
            facts += dynamicFact(typeName ?: args.values.firstOrNull(), location)
        }
    }

    /** 어노테이션 뒤 첫 메서드의 첫 파라미터 타입 이름을 읽는다 — `List<User>`는 `User`다. */
    private fun followingParamType(file: SourceFile, from: Int): String? {
        val match = listOfNotNull(KOTLIN_FUN.find(file.masked, from), JAVA_METHOD.find(file.masked, from))
            .minByOrNull { it.range.first } ?: return null
        val open = file.masked.indexOf('(', match.range.last).takeIf { it >= 0 } ?: return null
        val end = balancedEnd(file.masked, open)
        if (end <= open) return null
        val first = callArguments(file.code, open, end).firstOrNull()?.trim() ?: return null
        // Kotlin은 `name: Type`, Java는 `Type name` 형태다.
        val type = if (first.contains(':')) first.substringAfter(':').trim()
        else first.substringBeforeLast(' ', first).trim()
        return IDENTIFIER.findAll(type).lastOrNull()?.groupValues?.get(1)
    }

    /** 같은 선언 블록의 `@Table(name=..)` 값을 읽는다 — JPA 테이블명의 정본이다. */
    private fun tableNameFor(file: SourceFile, declStart: Int): String? {
        TABLE_ANNOTATION.findAll(file.masked).forEach { match ->
            if (match.range.last > declStart) return@forEach
            val between = file.masked.substring(match.range.last + 1, declStart)
            // 어노테이션 인자와 선언 사이에는 다른 어노테이션과 한정어만 올 수 있다 —
            // 다른 선언이 끼어 있으면 그 @Table은 이 선언의 것이 아니다.
            if (!isAnnotationGap(between)) return@forEach
            val args = file.annotationArgs(match.range.last + 1)
            val expr = args.values.firstNamed("name")
                ?: args.values.firstOrNull()?.takeUnless { it.isNamedArgument() }
            val (value, dynamic) = expr?.let(::literalOrDynamicChannel) ?: (null to true)
            if (value != null && !dynamic) return value
        }
        return null
    }

    /** 어노테이션과 선언 한정어만으로 이뤄진 간극인지 본다 — `class`·`fun`이 있으면 거짓이다. */
    private fun isAnnotationGap(text: String): Boolean =
        ANNOTATION_TOKEN.replace(text, " ").split(WHITESPACE)
            .all { it.isBlank() || it in KOTLIN_MODIFIERS }

    /** `entity = X::class`·`X.class` 형태의 인자에서 타입 이름을 읽는다. */
    private fun entityNameArg(expression: String?): String? =
        expression?.trim()?.let { CLASS_LITERAL.matchEntire(it)?.groupValues?.get(1) }

    /** 인자가 `name = 값` 형태인지 본다 — 문자열 안의 `=`는 세지 않아 `"a = b"` 리터럴을 지킨다. */
    private fun String.isNamedArgument(): Boolean =
        NAMED_ARGUMENT.containsMatchIn(trimStart())

    /** `{ "a", "b" }` 또는 `[ "a", "b" ]` 인자 목록의 문자열 요소를 읽는다. */
    private fun stringList(expression: String): List<String> {
        val inner = expression.trim().removeSurrounding("{", "}").removeSurrounding("[", "]")
        return STRING_ITEM.findAll(inner).mapNotNull { match ->
            val (value, dynamic) = literalOrDynamicChannel(match.groupValues[1])
            value.takeUnless { dynamic }
        }.toList()
    }

    /** 문자열 리터럴 하나씩 (시작, 끝+1, raw 여부)로 방문한다 — `'` 문자 리터럴은 건너뛴다. */
    private fun eachLiteral(code: String, visit: (start: Int, end: Int, raw: Boolean) -> Unit) {
        var index = 0
        while (index < code.length) {
            when {
                code.startsWith("\"\"\"", index) -> {
                    val close = code.indexOf("\"\"\"", index + 3)
                    if (close < 0) return
                    visit(index, close + 3, true)
                    index = close + 3
                }
                code[index] == '"' -> {
                    val close = closingQuote(code, index)
                    if (close < 0) return
                    visit(index, close + 1, false)
                    index = close + 1
                }
                code[index] == '\'' -> {
                    // 문자 리터럴 — escape를 감안해 닫는 따옴표까지 건너뛴다.
                    var j = index + 1
                    var escaped = false
                    while (j < code.length) {
                        when {
                            escaped -> escaped = false
                            code[j] == '\\' -> escaped = true
                            code[j] == '\'' -> break
                        }
                        j++
                    }
                    index = j + 1
                }
                else -> index++
            }
        }
    }

    /** `{` 위치부터 짝이 맞는 `}`의 위치를 돌려준다 — 닫히지 않으면 -1. */
    private fun braceEnd(code: String, open: Int): Int {
        if (open < 0 || code.getOrNull(open) != '{') return -1
        var depth = 0
        var quote: Char? = null
        var rawQuote = false
        var escaped = false
        var index = open
        while (index < code.length) {
            if (rawQuote) {
                if (code.startsWith("\"\"\"", index)) { rawQuote = false; index += 3 } else index++
                continue
            }
            if (quote == null && code.startsWith("\"\"\"", index)) { rawQuote = true; index += 3; continue }
            val c = code[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = null
                }
                index++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    /** `[start, end)` 구간의 잔여 중괄호 깊이다 — 멤버와 지역 변수를 가르는 경계다. */
    private fun braceDepth(masked: String, start: Int, end: Int): Int {
        var depth = 0
        for (index in start until end.coerceAtMost(masked.length)) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    private fun relationFact(channel: String, location: BridgeLocation, method: String? = null): BridgeFact =
        BridgeFact(
            kind = "relation-use",
            channel = channel,
            method = method,
            dynamic = false,
            location = location,
            target = "persistence",
        )

    /** 읽히지 않은 관계 표현식의 동적 사실이다 — channel에는 잘린 원문을 실어 위치 근거를 남긴다. */
    private fun dynamicFact(expression: String?, location: BridgeLocation, channel: String? = null): BridgeFact =
        BridgeFact(
            kind = "relation-use",
            channel = channel ?: safeDynamicChannel(expression),
            dynamic = true,
            location = location,
            target = "persistence",
        )

    private fun limitations(stats: ScanStats): List<String> = buildList {
        if (stats.unjoinedDynamic > 0) add(
            "unjoined-dynamic-relations: ${stats.unjoinedDynamic} SQL argument(s) or relation operand(s) were not statically readable; their relations are uncounted",
        )
        if (stats.unresolvedExposed > 0) add(
            "unresolved-exposed-relations: ${stats.unresolvedExposed} exposed-DSL-shaped receiver(s) matched no declared table name; kept as dynamic",
        )
        if (stats.unresolvedEntities > 0) add(
            "unresolved-room-entities: ${stats.unresolvedEntities} entity type name(s) had no resolvable table binding; kept as dynamic",
        )
        if (stats.unattributedColumns > 0) add(
            "unattributed-column-info: ${stats.unattributedColumns} column attribute(s) had no enclosing entity declaration",
        )
        if (stats.jpaSources > 0) add(
            "jpa-persistence-sources: ${stats.jpaSources} source file(s) use JPA/Spring Data persistence; literal SQL and entity bindings are covered but named and derived queries are not",
        )
        if (stats.sqlDelightFiles > 0) add(
            "sqldelight-query-files: ${stats.sqlDelightFiles} .sq/.sqm file(s) were scanned as SQLDelight query sources",
        )
    }

    private companion object {
        /** 스캔 대상 확장자다 — `.sq`/`.sqm`은 SQLDelight 쿼리 선언 파일이다. */
        val SCHEMA_EXTENSIONS = setOf("kt", "java", "sq", "sqm")

        val ROOM_GATE = Regex("\\bimport\\s+androidx\\.room")
        val JPA_GATE = Regex("\\bimport\\s+(?:javax|jakarta)\\.persistence\\b|\\bimport\\s+org\\.springframework\\.data\\b")
        val JDBC_GATE = Regex("\\bimport\\s+(?:java|javax)\\.sql\\b")
        val EXPOSED_GATE = Regex("\\bimport\\s+org\\.jetbrains\\.exposed\\b")
        val JOOQ_GATE = Regex("\\bimport\\s+org\\.jooq\\b")

        // Kotlin use-site 타깃(`@field:Name`)도 같은 어노테이션이다 — `ident:` 접두를 허용한다.
        val USE_SITE = "(?:[A-Za-z_][A-Za-z0-9_]*\\s*:\\s*)?"
        val TYPE_DECL = Regex("\\b(?:class|object)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val ENTITY_ANNOTATION = Regex("@${USE_SITE}(Entity|DatabaseView)\\b")
        val TABLE_ANNOTATION = Regex("@${USE_SITE}Table\\b")
        val QUERY_ANNOTATION = Regex("@${USE_SITE}(Query|RawQuery|Insert|Update|Delete|Upsert)\\b")
        val COLUMN_INFO = Regex("@${USE_SITE}ColumnInfo\\b")
        val FOREIGN_KEY = Regex("@${USE_SITE}ForeignKey\\b")
        val PROPERTY_DECL = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:")
        val JAVA_FIELD = Regex("\\b[A-Za-z_][A-Za-z0-9_.<>\\[\\],?]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=[^;]*)?;")
        val KOTLIN_FUN = Regex("\\bfun\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)?[A-Za-z_][A-Za-z0-9_]*\\s*\\(")
        val JAVA_METHOD = Regex("\\b[A-Za-z_][A-Za-z0-9_.<>\\[\\]]*\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(")
        val CLASS_LITERAL = Regex("([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:::class|\\.class)")
        val IDENTIFIER = Regex("([A-Za-z_][A-Za-z0-9_]*)")
        // `=` 뒤에 `=`가 이어지면 비교 식(`flag == x`)이라 명명 인자가 아니다.
        val NAMED_ARGUMENT = Regex("^[A-Za-z_][A-Za-z0-9_.]*\\s*=(?!=)")
        val STRING_ITEM = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val ANNOTATION_TOKEN = Regex("@[A-Za-z_][A-Za-z0-9_.]*\\s*(?:\\([^()]*\\))?")
        val WHITESPACE = Regex("\\s+")
        val KOTLIN_MODIFIERS = setOf(
            "data", "open", "abstract", "sealed", "inner", "enum", "value", "final",
            "public", "private", "protected", "internal", "static", "const", "expect",
            "actual", "lateinit", "override", "external", "constructor", "transient",
            "volatile", "synchronized", "native", "strictfp", "default", "record",
        )

        val EXPOSED_OBJECT = Regex(
            "\\b(?:object|class)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:[^\\n{]*?\\b" +
                "(Table|IdTable|IntIdTable|LongIdTable|UUIDTable|ULongIdTable)\\s*(?:<[^>\\n]*>)?\\s*\\(",
        )
        val EXPOSED_USE = Regex(
            "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.\\s*" +
                "(selectAll|select|slice|insert|insertIgnore|insertAndGetId|insertReturning|update|updateAll|" +
                "updateReturning|upsert|upsertReturning|delete|deleteWhere|deleteAll|deleteReturning|replace|" +
                "batchInsert|batchInsertIgnore|batchReplace|batchUpsert|join|innerJoin|leftJoin|rightJoin|" +
                "fullJoin|crossJoin|naturalJoin|exists|count)\\b",
        )
        val EXPOSED_RECEIVER = Regex("\\b([A-Z][A-Za-z0-9_]*)\\b")
        val JOIN_VERBS = setOf("join", "innerJoin", "leftJoin", "rightJoin", "fullJoin", "crossJoin", "naturalJoin")
        val COLUMN_FACTORY = Regex(
            "\\b(integer|long|short|byte|uinteger|ubyte|ushort|ulong|float|double|bool|boolean|char|" +
                "varchar|text|mediumText|largeText|uuid|binary|decimal|date|time|datetime|timestamp|" +
                "duration|enumeration|enumerationByName|customEnumeration|array|list|json|jsonb|blob|" +
                "reference|optReference|entityId|registerColumn)\\s*(?:<[^>\\n]*>)?\\s*\\(",
        )
        val REFERENCE_FACTORIES = setOf("reference", "optReference")
        val SCHEMA_UTILS = Regex("\\bSchemaUtils\\s*\\.\\s*\\w+\\s*\\(")

        val JDBC_SQL_CALL = Regex(
            "\\b(prepareStatement|prepareCall|executeQuery|executeUpdate|executeLargeUpdate|" +
                "executeBatch|addBatch|nativeSQL|execute)\\s*\\(",
        )
        val JOOQ_SQL_CALL = Regex(
            "\\b(fetch|fetchOne|fetchAny|fetchMany|fetchSingle|fetchOptional|fetchLazy|fetchStream|" +
                "fetchMap|fetchGroups|fetchArray|resultQuery|query|execute|parseQuery)\\s*\\(",
        )
        val JOOQ_RELATION_CALL = Regex(
            "\\b(table|selectFrom|naturalSelectFrom|selectCount|selectOne|selectZero|fetchExists)\\s*\\(",
        )
    }
}

private const val MAX_DYNAMIC_CHANNEL = 160

/** 동적 채널은 계약의 안전 문자열이어야 한다 — 제어 문자를 공백으로 바꾸고 길이를 자른다. */
private fun safeDynamicChannel(expression: String?): String =
    safeText(expression ?: "").ifBlank { "<dynamic>" }

private fun safeText(value: String): String =
    value.replace(Regex("\\p{C}"), " ").trim().take(MAX_DYNAMIC_CHANNEL)
