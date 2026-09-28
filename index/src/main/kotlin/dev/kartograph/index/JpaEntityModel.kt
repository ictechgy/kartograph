package dev.kartograph.index

import dev.kartograph.core.BridgeLocation

/** (테이블, 컬럼) 논리 이름 쌍이다. */
internal data class JpaColumnRef(val table: JpaTableName, val column: JpaLogicalName)

/**
 * 엔티티 속성 하나의 매핑이다. 이름은 논리 규칙으로 들고 있다가 명명 문맥이 물리 이름으로 확정한다.
 *
 * @property table 속성이 놓인 테이블이다. null이면 테이블을 확정할 수 없다(동적 `@Table` 등)
 * @property columns 속성이 소유한 컬럼(기본 컬럼, FK, join table·컬렉션 테이블 컬럼)
 * @property tables 속성이 소유한 추가 테이블(join table, 컬렉션 테이블)
 * @property nested 임베디드 값 또는 임베디드 원소 컬렉션의 하위 속성
 * @property target 연관 대상 엔티티의 단순 타입 이름
 * @property mappedBy 역방향 연관이 가리키는 대상 쪽 속성 이름
 * @property unsupported 모델링하지 않은 매핑의 원인이다 — 이름 대신 dynamic 사실로 내린다
 */
internal data class JpaAttribute(
    val name: String,
    val location: BridgeLocation,
    val table: JpaTableName?,
    val columns: List<JpaColumnRef> = emptyList(),
    val tables: List<JpaTableName> = emptyList(),
    val nested: Map<String, JpaAttribute>? = null,
    val target: String? = null,
    val mappedBy: String? = null,
    val unsupported: String? = null,
    val declaringType: String = "",
)

/**
 * JPA 엔티티 하나의 매핑이다.
 *
 * @property table 자기 테이블이다. SINGLE_TABLE 하위 엔티티와 TABLE_PER_CLASS 추상 루트는 자기 테이블이 없다
 * @property attributes 상속을 포함한 속성 전체(질의 경로 해석용)
 * @property declared 이 엔티티 선언이 방출할 속성(자기 선언 + 몫이 이 엔티티인 상위 선언)
 * @property extraColumns 판별 컬럼·JOINED PK join column처럼 속성이 아닌 컬럼과 그 위치
 * @property queryTables 이 엔티티를 질의할 때 읽는 테이블(다형 질의의 하위 테이블 포함)
 */
internal data class JpaEntity(
    val type: JpaSourceType,
    val file: JpaSourceFile,
    val entityName: String,
    val table: JpaTableName?,
    val dynamicTable: String?,
    val location: BridgeLocation,
    val attributes: Map<String, JpaAttribute>,
    val declared: List<JpaAttribute>,
    val extraColumns: List<Pair<JpaColumnRef, BridgeLocation>>,
    val queryTables: List<JpaTableName>,
    val idColumns: List<JpaLogicalName>?,
)

/**
 * 소스 파일들에서 JPA 엔티티 매핑을 만든다. 지원 범위는 명명 벡터가 실행으로 검증한 매핑이다 —
 * `@Table`·`@Column`·`@JoinColumn(s)`·`@JoinTable`·`@Embedded(Id)`/`@Embeddable`·`@AttributeOverride(s)`·
 * 상속 세 전략과 `@DiscriminatorColumn`·`@PrimaryKeyJoinColumn`·`@ElementCollection`/`@CollectionTable`/
 * `@OrderColumn`·`@Transient`·`@MappedSuperclass`·Java getter property access. 그 밖의 매핑은
 * [JpaAttribute.unsupported]로 남겨 이름을 추측하지 않는다.
 */
internal class JpaEntityModel(
    val entities: List<JpaEntity>,
    private val types: Map<String, List<Pair<JpaSourceType, JpaSourceFile>>>,
) {
    private val bySimpleName: Map<String, List<JpaEntity>> = entities.groupBy { it.type.name }
    private val byEntityName: Map<String, List<JpaEntity>> = entities.groupBy { it.entityName }

    /** 단순 타입 이름이 가리키는 엔티티가 하나일 때만 돌려준다. */
    fun entity(simpleName: String): JpaEntity? = bySimpleName[simpleName.substringAfterLast('.')]?.singleOrNull()

    /** JPQL 엔티티 이름(엔티티 이름 → 단순 이름 → FQN 끝 이름 순)으로 찾는다. */
    fun jpqlEntity(name: String): JpaEntity? =
        byEntityName[name]?.singleOrNull() ?: entity(name)

    /** 이름이 JPA 관련 타입(엔티티·임베디드·상위 매핑) 중 하나인지 본다. */
    fun isKnownType(simpleName: String): Boolean = types.containsKey(simpleName)

    companion object {
        /** JPA 게이트를 통과한 파일들로 모델을 만든다. */
        fun build(files: List<JpaSourceFile>): JpaEntityModel {
            val types = files.flatMap { file -> file.types.map { it to file } }
                .filter { (type, _) -> type.has("Entity") || type.has("Embeddable") || type.has("MappedSuperclass") }
                .groupBy { it.first.name }
            return JpaEntityModelBuilder(types).build()
        }
    }
}

/** 상속 전략이다 — 기본값은 JPA 명세대로 SINGLE_TABLE이다. */
internal enum class JpaInheritance { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }

/** [JpaEntityModel]의 구성 단계다 — 타입 이름 해석과 속성 매핑 규칙을 담는다. */
private class JpaEntityModelBuilder(private val types: Map<String, List<Pair<JpaSourceType, JpaSourceFile>>>) {

    /** 계층의 한 단계다. */
    private data class Level(val type: JpaSourceType, val file: JpaSourceFile) {
        val isEntity: Boolean get() = type.has("Entity")
        val isMappedSuperclass: Boolean get() = type.has("MappedSuperclass")
    }

    fun build(): JpaEntityModel {
        val entityLevels = types.values.flatten().filter { (type, _) -> type.has("Entity") }
        val entities = entityLevels.map { (type, file) -> entity(Level(type, file)) }
        return JpaEntityModel(entities, types)
    }

    private fun resolve(simpleName: String): Level? =
        types[simpleName.substringAfterLast('.')]?.singleOrNull()?.let { Level(it.first, it.second) }

    /** 루트에서 자기까지의 계층 사슬이다 — 모르는 상위 타입에서 멈춘다. */
    private fun chain(level: Level): List<Level> {
        val result = ArrayDeque<Level>()
        var current: Level? = level
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.type.jvmName)) {
            result.addFirst(current)
            current = current.type.supertypes.asSequence().map(::simpleTypeName).mapNotNull(::resolve)
                .firstOrNull { it.isEntity || it.isMappedSuperclass }
        }
        return result.toList()
    }

    private fun descendants(level: Level): List<Level> = types.values.flatten()
        .map { Level(it.first, it.second) }
        .filter { it.isEntity && it.type.jvmName != level.type.jvmName && chain(it).any { up -> up.type.jvmName == level.type.jvmName } }

    private fun entityName(level: Level): String =
        level.type.annotation("Entity")?.argument("name", positional = true)?.let(::literal) ?: level.type.name

    private fun strategy(root: Level): JpaInheritance {
        val raw = root.type.annotation("Inheritance")?.argument("strategy", positional = true).orEmpty()
        return when {
            raw.endsWith("JOINED") -> JpaInheritance.JOINED
            raw.endsWith("TABLE_PER_CLASS") -> JpaInheritance.TABLE_PER_CLASS
            else -> JpaInheritance.SINGLE_TABLE
        }
    }

    /** 엔티티 자기 테이블 규칙과 동적 원문이다. */
    private fun ownTable(level: Level): Pair<JpaTableName?, String?> {
        val table = level.type.annotation("Table")
        // 빈 문자열은 JPA에서 "기본값"이다 — 암묵 이름으로 되돌린다.
        val nameExpression = table?.argument("name")
        val name = nameExpression?.let { expression ->
            literal(expression)?.ifEmpty { null } ?: if (literal(expression) == null) return null to expression.trim() else null
        }
        val schemaExpression = table?.argument("schema")
        val schema = schemaExpression?.let { expression ->
            literal(expression)?.ifEmpty { null } ?: if (literal(expression) == null) return null to expression.trim() else null
        }
        val logical = JpaIdentifier.parse(name ?: entityName(level))
        return JpaTableName(JpaLogicalName.of(logical), schema?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) }) to null
    }

    private fun entity(level: Level): JpaEntity {
        val chain = chain(level)
        val entityLevels = chain.filter { it.isEntity }
        val root = entityLevels.first()
        val strategy = strategy(root)
        val hasSubclasses = descendants(root).isNotEmpty()
        val tables = entityLevels.associate { it.type.jvmName to ownTable(it) }
        val concreteTable = { candidate: Level -> tables[candidate.type.jvmName]?.first }
        val selfTable = when {
            strategy == JpaInheritance.SINGLE_TABLE && level != root -> null
            strategy == JpaInheritance.TABLE_PER_CLASS && level.type.isAbstract() -> null
            else -> concreteTable(level)
        }
        val access = accessOf(chain)
        val idColumns = idColumns(chain, access)
        val attributes = linkedMapOf<String, JpaAttribute>()
        val declared = mutableListOf<JpaAttribute>()
        chain.forEachIndexed { index, current ->
            val owner = owningEntity(chain, index, level, strategy)
            val table = when (strategy) {
                JpaInheritance.SINGLE_TABLE -> concreteTable(root)
                JpaInheritance.JOINED -> concreteTable(owner)
                JpaInheritance.TABLE_PER_CLASS -> selfTable
            }
            val context = AttributeContext(owner = owner, table = table, ownerId = idColumns, file = current.file)
            persistentMembers(current, access).forEach { member ->
                val attribute = attribute(member, context).copy(declaringType = current.type.name)
                attributes[attribute.name] = attribute
                // TABLE_PER_CLASS 추상 루트는 테이블이 없다 — 그 속성은 구체 하위 엔티티가 자기 테이블로 싣는다.
                val emits = (strategy != JpaInheritance.TABLE_PER_CLASS || selfTable != null) &&
                    owner.type.jvmName == level.type.jvmName ||
                    (strategy == JpaInheritance.TABLE_PER_CLASS && selfTable != null && index < chain.size - 1)
                if (emits) declared += attribute
            }
        }
        val location = level.file.location(level.type.annotation("Entity")?.offset ?: level.type.offset)
        return JpaEntity(
            type = level.type,
            file = level.file,
            entityName = entityName(level),
            table = selfTable ?: if (strategy == JpaInheritance.SINGLE_TABLE) concreteTable(root) else null,
            dynamicTable = tables[level.type.jvmName]?.second,
            location = location,
            attributes = attributes,
            declared = declared,
            extraColumns = extraColumns(level, root, strategy, hasSubclasses, concreteTable, idColumns),
            queryTables = queryTables(level, root, strategy, concreteTable),
            idColumns = idColumns,
        )
    }

    private fun JpaSourceType.isAbstract(): Boolean = "abstract" in modifiers || isInterface

    /** 사슬의 [index] 단계 속성을 소유하는 엔티티다 — 상위 매핑 클래스는 바로 아래 엔티티에 속한다. */
    private fun owningEntity(chain: List<Level>, index: Int, self: Level, strategy: JpaInheritance): Level {
        if (strategy == JpaInheritance.TABLE_PER_CLASS) return self
        return chain.drop(index).firstOrNull { it.isEntity } ?: self
    }

    /** 판별 컬럼과 JOINED 하위 테이블의 PK join column이다. */
    private fun extraColumns(
        level: Level,
        root: Level,
        strategy: JpaInheritance,
        hasSubclasses: Boolean,
        table: (Level) -> JpaTableName?,
        idColumns: List<JpaLogicalName>?,
    ): List<Pair<JpaColumnRef, BridgeLocation>> {
        val result = mutableListOf<Pair<JpaColumnRef, BridgeLocation>>()
        val discriminator = root.type.annotation("DiscriminatorColumn")
        val wantsDiscriminator = level == root && when (strategy) {
            JpaInheritance.SINGLE_TABLE -> hasSubclasses || discriminator != null
            JpaInheritance.JOINED -> discriminator != null
            JpaInheritance.TABLE_PER_CLASS -> false
        }
        val rootTable = table(root)
        if (wantsDiscriminator && rootTable != null) {
            val name = discriminator?.argument("name")?.let(::literal) ?: DEFAULT_DISCRIMINATOR
            val location = root.file.location(discriminator?.offset ?: root.type.offset)
            result += JpaColumnRef(rootTable, JpaLogicalName.of(JpaIdentifier.parse(name))) to location
        }
        val ownTable = table(level)
        if (strategy == JpaInheritance.JOINED && level != root && ownTable != null) {
            val explicit = level.type.annotation("PrimaryKeyJoinColumn")
            val location = level.file.location(explicit?.offset ?: level.type.offset)
            val names = explicit?.argument("name")?.let(::literal)?.let { listOf(JpaLogicalName.of(JpaIdentifier.parse(it))) }
                ?: idColumns.orEmpty()
            names.forEach { result += JpaColumnRef(ownTable, it) to location }
        }
        return result
    }

    /** 이 엔티티 질의가 읽는 테이블이다 — JOINED·TABLE_PER_CLASS는 하위 엔티티 테이블까지 다형으로 읽는다. */
    private fun queryTables(level: Level, root: Level, strategy: JpaInheritance, table: (Level) -> JpaTableName?): List<JpaTableName> =
        when (strategy) {
            JpaInheritance.SINGLE_TABLE -> listOfNotNull(table(root))
            JpaInheritance.JOINED -> (chain(level).filter { it.isEntity } + descendants(level)).mapNotNull(table)
            JpaInheritance.TABLE_PER_CLASS -> (listOf(level) + descendants(level))
                .filterNot { it.type.isAbstract() }.mapNotNull(table)
        }.distinct()

    /** 접근 방식이다 — `@Id`가 Java getter에 있거나 `@Access(PROPERTY)`면 property access다. */
    private enum class Access { FIELD, PROPERTY, UNSUPPORTED }

    private fun accessOf(chain: List<Level>): Access {
        val explicit = chain.asReversed().firstNotNullOfOrNull { it.type.annotation("Access")?.argument("value", positional = true) }
        val idMember = chain.flatMap { it.type.members }.firstOrNull { member ->
            member.annotations.any { it.name == "Id" || it.name == "EmbeddedId" }
        }
        return when {
            explicit?.endsWith("PROPERTY") == true -> Access.PROPERTY
            idMember?.kind == JpaMemberKind.GETTER -> Access.PROPERTY
            idMember?.annotations?.any { (it.name == "Id" || it.name == "EmbeddedId") && it.useSite == "get" } == true ->
                Access.UNSUPPORTED
            else -> Access.FIELD
        }
    }

    /** 접근 방식에 맞는 영속 멤버다 — `@Transient`와 정적·`transient` 멤버는 뺀다. */
    private fun persistentMembers(level: Level, access: Access): List<JpaMember> {
        val kind = if (access == Access.PROPERTY) JpaMemberKind.GETTER else JpaMemberKind.FIELD
        return level.type.members.filter { member ->
            member.kind == kind && (member.persistent || member.delegated) && !member.effective().any { it.name == "Transient" }
        }
    }

    /** 속성 매핑의 공통 입력이다. */
    private data class AttributeContext(
        val owner: Level,
        val table: JpaTableName?,
        val ownerId: List<JpaLogicalName>?,
        val file: JpaSourceFile,
        val overrides: Map<String, Pair<JpaSourceFile, JpaAnnotation>> = emptyMap(),
        val prefix: String = "",
    )

    private fun attribute(member: JpaMember, context: AttributeContext): JpaAttribute {
        val annotations = member.effective()
        val location = context.file.location(member.offset)
        val base = JpaAttribute(member.name, location, context.table)
        val names = annotations.map { it.name }.toSet()
        return when {
            member.delegated -> base.copy(unsupported = "delegated property")
            annotations.any { it.useSite == "get" } -> base.copy(unsupported = "getter-targeted mapping annotation")
            names.any { it in UNSUPPORTED_ANNOTATIONS } -> base.copy(unsupported = "unmodelled mapping annotation")
            "ManyToOne" in names || "OneToOne" in names -> toOne(member, annotations, base, context)
            "OneToMany" in names || "ManyToMany" in names -> plural(member, annotations, base, context)
            "ElementCollection" in names -> elementCollection(member, annotations, base, context)
            "Embedded" in names || "EmbeddedId" in names || embeddable(member.type) != null ->
                embedded(member, base, context)
            else -> basic(member, annotations, base, context)
        }
    }

    private fun basic(member: JpaMember, annotations: List<JpaAnnotation>, base: JpaAttribute, context: AttributeContext): JpaAttribute {
        val table = context.table ?: return base.copy(unsupported = "unresolved owner table")
        val override = context.overrides[context.prefix + member.name]
        val column = override?.let { (file, annotation) -> columnOf(file, annotation) } ?: annotations.firstOrNull { it.name == "Column" }
        if (column?.argument("table") != null) return base.copy(unsupported = "secondary table column")
        val name = column?.argument("name")
        val value = name?.let { literal(it) ?: return base.copy(unsupported = "non-literal column name") }
        val logical = value?.ifEmpty { null }?.let(JpaIdentifier::parse) ?: JpaIdentifier(member.name)
        return base.copy(columns = listOf(JpaColumnRef(table, JpaLogicalName.of(logical))))
    }

    /** `@AttributeOverride(column = @Column(..))`의 안쪽 `@Column`이다. */
    private fun columnOf(file: JpaSourceFile, override: JpaAnnotation): JpaAnnotation? =
        override.argumentRange?.let { range -> file.annotationsInside(range).firstOrNull { it.name == "Column" } }

    private fun embedded(member: JpaMember, base: JpaAttribute, context: AttributeContext): JpaAttribute {
        val level = embeddable(member.type) ?: return base.copy(unsupported = "unresolved embeddable type")
        val overrides = context.overrides + attributeOverrides(context.file, member, "${context.prefix}${member.name}.")
        val nestedContext = context.copy(file = level.file, overrides = overrides, prefix = "${context.prefix}${member.name}.")
        val nested = persistentMembers(level, Access.FIELD).associate { child ->
            child.name to attribute(child, nestedContext).copy(declaringType = level.type.name)
        }
        return base.copy(nested = nested, columns = nested.values.flatMap { it.columns })
    }

    /** 멤버의 `@AttributeOverride`(묶음 포함)를 경로 → 어노테이션으로 모은다. */
    private fun attributeOverrides(file: JpaSourceFile, member: JpaMember, prefix: String): Map<String, Pair<JpaSourceFile, JpaAnnotation>> {
        val direct = member.effective().filter { it.name == "AttributeOverride" }
        val grouped = member.effective().filter { it.name == "AttributeOverrides" }.flatMap { group ->
            group.argumentRange?.let { range -> file.annotationsInside(range).filter { it.name == "AttributeOverride" } }.orEmpty()
        }
        return (direct + grouped).mapNotNull { annotation ->
            annotation.argument("name")?.let(::literal)?.let { "$prefix$it" to (file to annotation) }
        }.toMap()
    }

    private fun toOne(member: JpaMember, annotations: List<JpaAnnotation>, base: JpaAttribute, context: AttributeContext): JpaAttribute {
        val relation = annotations.first { it.name == "ManyToOne" || it.name == "OneToOne" }
        val target = targetEntity(relation) ?: member.type?.let(::simpleTypeName)
        val withTarget = base.copy(target = target)
        relation.argument("mappedBy")?.let { return withTarget.copy(mappedBy = literal(it)) }
        if (annotations.any { it.name in setOf("PrimaryKeyJoinColumn", "MapsId", "JoinTable", "JoinFormula") }) {
            return withTarget.copy(unsupported = "shared-key or join-table to-one mapping")
        }
        val table = context.table ?: return withTarget.copy(unsupported = "unresolved owner table")
        val columns = joinColumns(context.file, annotations, member.name, target?.let(::targetId))
            ?: return withTarget.copy(unsupported = "unresolved join column")
        return withTarget.copy(columns = columns.map { JpaColumnRef(table, it) })
    }

    /**
     * `@JoinColumn(s)`의 컬럼 이름이다. 이름이 없으면 `{속성}_{참조 컬럼}` — 참조 컬럼은
     * `referencedColumnName` 또는 대상 PK(단일 컬럼일 때만)다.
     */
    private fun joinColumns(
        file: JpaSourceFile,
        annotations: List<JpaAnnotation>,
        prefix: String,
        referenced: List<JpaLogicalName>?,
    ): List<JpaLogicalName>? {
        val declared = annotations.filter { it.name == "JoinColumn" } +
            annotations.filter { it.name == "JoinColumns" }.flatMap { group ->
                group.argumentRange?.let { file.annotationsInside(it).filter { inner -> inner.name == "JoinColumn" } }.orEmpty()
            }
        return joinColumnNames(declared, prefix, referenced)
    }

    private fun joinColumnNames(declared: List<JpaAnnotation>, prefix: String, referenced: List<JpaLogicalName>?): List<JpaLogicalName>? {
        if (declared.size > 1) {
            return declared.map { annotation ->
                annotation.argument("name")?.let(::literal)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) } ?: return null
            }
        }
        val single = declared.singleOrNull()
        single?.argument("name")?.let { expression ->
            return literal(expression)?.let { listOf(JpaLogicalName.of(JpaIdentifier.parse(it))) }
        }
        val referencedName = single?.argument("referencedColumnName")?.let { expression ->
            literal(expression)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) } ?: return null
        } ?: referenced?.singleOrNull() ?: return null
        return listOf(implicitJoinColumn(prefix, referencedName))
    }

    /** `{접두어}_{참조 컬럼의 물리 이름}`이다 — Hibernate는 참조 컬럼의 물리 이름을 잇는다. */
    private fun implicitJoinColumn(prefix: String, referenced: JpaLogicalName): JpaLogicalName = JpaLogicalName { profile ->
        referenced.physical(profile)?.let { JpaIdentifier("${prefix}_$it") }
    }

    private fun plural(member: JpaMember, annotations: List<JpaAnnotation>, base: JpaAttribute, context: AttributeContext): JpaAttribute {
        val relation = annotations.first { it.name == "OneToMany" || it.name == "ManyToMany" }
        val target = targetEntity(relation) ?: member.type?.let(::elementTypeName)
        val withTarget = base.copy(target = target)
        if (member.type?.let(::isMapType) == true) return withTarget.copy(unsupported = "map-valued association")
        relation.argument("mappedBy")?.let { return withTarget.copy(mappedBy = literal(it)) }
        val ownerTable = context.table ?: return withTarget.copy(unsupported = "unresolved owner table")
        val targetEntity = target?.let(::resolve)
        val joinColumns = annotations.filter { it.name == "JoinColumn" || it.name == "JoinColumns" }
        if (relation.name == "OneToMany" && joinColumns.isNotEmpty()) {
            val targetTable = targetEntity?.let { ownTable(it).first } ?: return withTarget.copy(unsupported = "unresolved target table")
            val columns = joinColumns(context.file, annotations, member.name, context.ownerId)
                ?: return withTarget.copy(unsupported = "unresolved join column")
            return withTarget.copy(columns = columns.map { JpaColumnRef(targetTable, it) })
        }
        return joinTable(member, annotations, withTarget, context, ownerTable, targetEntity)
    }

    /** 소유 쪽 join table이다 — 이름·양쪽 join column의 명시값과 암묵 규칙을 적용한다. */
    private fun joinTable(
        member: JpaMember,
        annotations: List<JpaAnnotation>,
        base: JpaAttribute,
        context: AttributeContext,
        ownerTable: JpaTableName,
        targetEntity: Level?,
    ): JpaAttribute {
        val declaration = annotations.firstOrNull { it.name == "JoinTable" }
        val targetTable = targetEntity?.let { ownTable(it).first }
        val tableName = declaration?.argument("name")?.let { expression ->
            literal(expression)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) } ?: return base.copy(unsupported = "non-literal join table")
        } ?: JpaLogicalName { profile ->
            val owner = ownerTable.name.physical(profile) ?: return@JpaLogicalName null
            when (profile.implicit) {
                JpaImplicitStrategy.SPRING -> JpaIdentifier("${owner}_${member.name}")
                JpaImplicitStrategy.JPA_COMPLIANT -> targetTable?.name?.physical(profile)?.let { JpaIdentifier("${owner}_$it") }
            }
        }
        val schema = declaration?.argument("schema")?.let { expression ->
            literal(expression)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) } ?: return base.copy(unsupported = "non-literal join table schema")
        }
        val table = JpaTableName(tableName, schema)
        val inverse = targetEntity?.type?.members?.firstOrNull { candidate ->
            candidate.effective().any { it.argument("mappedBy")?.let(::literal) == member.name }
        }
        val ownerPrefix = inverse?.name ?: entityName(context.owner)
        val ownerColumns = joinTableColumns(context.file, declaration, "joinColumns", ownerPrefix, context.ownerId)
        val inverseColumns = joinTableColumns(context.file, declaration, "inverseJoinColumns", member.name, targetEntity?.let { targetId(it.type.name) })
        if (ownerColumns == null || inverseColumns == null) return base.copy(tables = listOf(table), unsupported = "unresolved join table column")
        return base.copy(tables = listOf(table), columns = (ownerColumns + inverseColumns).map { JpaColumnRef(table, it) })
    }

    private fun joinTableColumns(
        file: JpaSourceFile,
        declaration: JpaAnnotation?,
        argument: String,
        prefix: String,
        referenced: List<JpaLogicalName>?,
    ): List<JpaLogicalName>? {
        val declared = declaration?.argument(argument)?.let { _ ->
            declaration.argumentRange?.let { range ->
                file.annotationsInside(range).filter { it.name == "JoinColumn" && inArgument(file, declaration, argument, it) }
            }
        }.orEmpty()
        return joinColumnNames(declared, prefix, referenced)
    }

    /** 안쪽 어노테이션이 바깥 어노테이션의 특정 이름 인자 안에 있는지 본다. */
    private fun inArgument(file: JpaSourceFile, outer: JpaAnnotation, argument: String, inner: JpaAnnotation): Boolean {
        val range = outer.argumentRange ?: return false
        val text = file.masked.substring(range.first, range.last + 1)
        val match = Regex("\\b${Regex.escape(argument)}\\s*=").find(text) ?: return false
        val start = range.first + match.range.last
        val end = file.masked.substring(start).let { rest ->
            var depth = 0
            rest.indexOfFirst { c ->
                when (c) { '(', '{', '[' -> depth++; ')', '}', ']' -> depth-- }
                depth < 0 || (depth == 0 && c == ',')
            }.let { if (it < 0) rest.length else it }
        } + start
        return inner.offset in start..end
    }

    private fun elementCollection(member: JpaMember, annotations: List<JpaAnnotation>, base: JpaAttribute, context: AttributeContext): JpaAttribute {
        if (member.type?.let(::isMapType) == true) return base.copy(unsupported = "map-valued element collection")
        val declaration = annotations.firstOrNull { it.name == "CollectionTable" }
        val ownerName = entityName(context.owner)
        val tableName = declaration?.argument("name")?.let { expression ->
            literal(expression)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) } ?: return base.copy(unsupported = "non-literal collection table")
        } ?: JpaLogicalName.of(JpaIdentifier("${ownerName}_${member.name}"))
        val schema = declaration?.argument("schema")?.let { expression ->
            literal(expression)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) } ?: return base.copy(unsupported = "non-literal collection table schema")
        }
        val table = JpaTableName(tableName, schema)
        val joinDeclared = declaration?.argumentRange?.let { range ->
            context.file.annotationsInside(range).filter { it.name == "JoinColumn" }
        }.orEmpty()
        val joinColumns = joinColumnNames(joinDeclared, ownerName, context.ownerId)
            ?: return base.copy(tables = listOf(table), unsupported = "unresolved collection join column")
        val element = elementTypeName(member.type.orEmpty())
        val elementEmbeddable = element?.let(::embeddable)
        val nested = elementEmbeddable?.let { level ->
            val nestedContext = context.copy(table = table, file = level.file, overrides = attributeOverrides(context.file, member, ""), prefix = "")
            persistentMembers(level, Access.FIELD).associate { child ->
                child.name to attribute(child, nestedContext).copy(declaringType = level.type.name)
            }
        }
        val elementColumns = nested?.values?.flatMap { it.columns } ?: listOf(
            JpaColumnRef(table, columnName(annotations, member.name) ?: return base.copy(tables = listOf(table), unsupported = "non-literal element column")),
        )
        val order = annotations.firstOrNull { it.name == "OrderColumn" }?.let { order ->
            val name = order.argument("name")?.let { literal(it) ?: return base.copy(tables = listOf(table), unsupported = "non-literal order column") }
            JpaColumnRef(table, JpaLogicalName.of(name?.let(JpaIdentifier::parse) ?: JpaIdentifier("${member.name}_ORDER")))
        }
        return base.copy(
            tables = listOf(table),
            columns = joinColumns.map { JpaColumnRef(table, it) } + elementColumns + listOfNotNull(order),
            nested = nested,
        )
    }

    private fun columnName(annotations: List<JpaAnnotation>, fallback: String): JpaLogicalName? {
        val expression = annotations.firstOrNull { it.name == "Column" }?.argument("name")
            ?: return JpaLogicalName.of(JpaIdentifier(fallback))
        return literal(expression)?.let { JpaLogicalName.of(JpaIdentifier.parse(it)) }
    }

    /** 대상 엔티티의 PK 컬럼 논리 이름이다 — 모르면 null. */
    private fun targetId(simpleName: String): List<JpaLogicalName>? {
        val level = resolve(simpleName)?.takeIf { it.isEntity } ?: return null
        val chain = chain(level)
        return idColumns(chain, accessOf(chain))
    }

    /** 계층의 PK 컬럼이다 — `@Id` 하나 또는 `@EmbeddedId`의 컬럼들, 여러 `@Id`면 그 전부다. */
    private fun idColumns(chain: List<Level>, access: Access): List<JpaLogicalName>? {
        if (access == Access.UNSUPPORTED) return null
        val ids = chain.flatMap { level -> persistentMembers(level, access).map { it to level } }
            .filter { (member, _) -> member.effective().any { it.name == "Id" || it.name == "EmbeddedId" } }
        if (ids.isEmpty()) return null
        return ids.flatMap { (member, _) ->
            val embeddedId = member.effective().any { it.name == "EmbeddedId" }
            if (embeddedId) {
                val nested = embeddable(member.type) ?: return null
                persistentMembers(nested, Access.FIELD).map { child ->
                    columnName(child.effective(), child.name) ?: return null
                }
            } else {
                listOf(columnName(member.effective(), member.name) ?: return null)
            }
        }
    }

    private fun targetEntity(relation: JpaAnnotation): String? =
        relation.argument("targetEntity")?.let { CLASS_LITERAL.find(it)?.groupValues?.get(1) }?.substringAfterLast('.')

    private fun embeddable(type: String?): Level? =
        type?.let(::simpleTypeName)?.let(::resolve)?.takeIf { it.type.has("Embeddable") }

    private companion object {
        const val DEFAULT_DISCRIMINATOR = "DTYPE"
        val CLASS_LITERAL = Regex("([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:::class|\\.class)")
        val UNSUPPORTED_ANNOTATIONS = setOf("AssociationOverride", "AssociationOverrides", "Formula", "ManyToAny", "Any", "Subselect")
    }
}

/** 멤버에 실제로 적용되는 어노테이션이다 — 생성자 매개변수·setter 대상 use-site는 Hibernate가 보지 않는다. */
internal fun JpaMember.effective(): List<JpaAnnotation> =
    annotations.filter { it.useSite == null || it.useSite == "field" || it.useSite == "get" || it.useSite == "property" }

/** 타입 원문의 단순 이름이다 — `List<Job>?`는 `List`, `com.x.Job`은 `Job`이다. */
internal fun simpleTypeName(type: String): String =
    type.substringBefore('<').trim().removeSuffix("?").trim().substringAfterLast('.').removeSuffix("[]")

/** 컬렉션 타입의 원소 단순 이름이다 — Map은 값 타입(마지막 인자)이다. */
internal fun elementTypeName(type: String): String? {
    val open = type.indexOf('<')
    if (open < 0) return null
    val close = angleEnd(type, open)
    if (close < 0) return null
    return splitTopLevel(type.substring(open + 1, close)).lastOrNull()
        ?.removePrefix("out ")?.removePrefix("? extends ")?.let(::simpleTypeName)
}

private fun isMapType(type: String): Boolean = simpleTypeName(type).let { it == "Map" || it.endsWith("Map") }

/** 문자열 리터럴 값이다 — 리터럴이 아니거나 보간이 있으면 null. */
internal fun literal(expression: String): String? {
    val (value, dynamic) = literalOrDynamicChannel(expression)
    return value.takeUnless { dynamic }
}
