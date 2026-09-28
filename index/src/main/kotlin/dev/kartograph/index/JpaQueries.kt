package dev.kartograph.index

/**
 * 질의 하나가 건드리는 테이블·컬럼 논리 참조와, 이름으로 해석하지 못한 조각 수다.
 * 테이블·컬럼 이름 확정은 명명 문맥이 맡는다.
 */
internal class JpaTouch {
    val tables: MutableSet<JpaTableName> = linkedSetOf()
    val columns: MutableSet<JpaColumnRef> = linkedSetOf()
    val unresolved: MutableList<String> = mutableListOf()

    /** 엔티티를 질의 대상으로 읽는다 — 다형 질의가 읽는 테이블 전부다. */
    fun entity(entity: JpaEntity) {
        if (entity.queryTables.isEmpty()) unresolved += entity.dynamicTable ?: entity.entityName
        tables += entity.queryTables
    }

    fun merge(other: JpaTouch) {
        tables += other.tables
        columns += other.columns
        unresolved += other.unresolved
    }
}

/** 속성 경로를 모델 위에서 따라가며 테이블·컬럼을 모은다 — 파생 질의와 JPQL이 공유한다. */
internal class JpaPathResolver(private val model: JpaEntityModel) {

    /** 속성 이름 → 속성 조회 범위다. 임베디드 값은 하위 속성, 연관은 대상 엔티티 속성이다. */
    fun scopeOf(attribute: JpaAttribute): Map<String, JpaAttribute>? =
        attribute.nested ?: attribute.target?.let(model::entity)?.attributes

    /**
     * 해석된 속성 사슬이 건드리는 것을 모은다. 연관을 거치면 FK·join table 컬럼과 대상 테이블을 더한다.
     * 마지막 연관의 대상 PK만 읽는 경로(`dept.id`)는 JPA 구현이 FK 컬럼으로 끝낼 수 있어도 대상 테이블을 함께 싣는다 —
     * 보수적 상한이다.
     */
    fun touch(chain: List<JpaAttribute>, touch: JpaTouch) {
        chain.forEachIndexed { index, attribute ->
            if (attribute.unsupported != null) {
                touch.unresolved += attribute.name
                return
            }
            val nestedColumns = attribute.nested?.values?.flatMap { it.columns }.orEmpty().toSet()
            // 임베디드 값은 경로 끝에서만 하위 컬럼 전부를 읽는다 — 중간이면 다음 조각이 고른 컬럼만 읽는다.
            val columns = if (index == chain.lastIndex) attribute.columns else attribute.columns.filterNot { it in nestedColumns }
            touch.columns += columns
            columns.forEach { touch.tables += it.table }
            touch.tables += attribute.tables
            attribute.target?.let { name -> model.entity(name)?.let { target -> touchTarget(attribute, target, touch) } }
        }
    }

    private fun touchTarget(attribute: JpaAttribute, target: JpaEntity, touch: JpaTouch) {
        touch.entity(target)
        val inverse = attribute.mappedBy?.let { target.attributes[it] } ?: return
        touch.columns += inverse.columns
        touch.tables += inverse.tables
    }

    /** 점으로 나뉜 명시 경로를 정확한 이름으로 해석한다 — 모르는 이름이면 null. */
    fun explicit(entity: JpaEntity, segments: List<String>): List<JpaAttribute>? {
        var scope: Map<String, JpaAttribute>? = entity.attributes
        return segments.map { segment ->
            val attribute = scope?.get(segment) ?: return null
            scope = scopeOf(attribute)
            attribute
        }
    }

    /**
     * Spring Data `PropertyPath.from` 규칙으로 파생 질의 속성 원문을 해석한다 — `_`·`.`로 먼저 나누고, 각 조각은
     * 전체 이름을 시도한 뒤 오른쪽 camelCase 혹부터 떼어 머리/꼬리로 다시 시도한다.
     */
    fun derived(entity: JpaEntity, source: String): List<JpaAttribute>? {
        val parts = SPLITTER.findAll("_$source").map { it.groupValues[1] }.toList()
        var scope: Map<String, JpaAttribute>? = entity.attributes
        val chain = mutableListOf<JpaAttribute>()
        for (part in parts) {
            val resolved = create(part, scope ?: return null, "") ?: return null
            chain += resolved
            scope = scopeOf(resolved.last())
        }
        return chain.ifEmpty { null }
    }

    private fun create(source: String, scope: Map<String, JpaAttribute>, tail: String, depth: Int = 0): List<JpaAttribute>? {
        if (depth > MAX_DEPTH) return null
        scope[decapitalize(source)]?.let { attribute ->
            if (tail.isEmpty()) return listOf(attribute)
            val next = scopeOf(attribute) ?: return null
            val rest = create(tail, next, "", depth + 1) ?: return null
            return listOf(attribute) + rest
        }
        val hump = NESTED_PROPERTY.find(source)?.takeIf { it.range.first > 0 } ?: return null
        return create(source.substring(0, hump.range.first), scope, source.substring(hump.range.first) + tail, depth + 1)
    }

    private companion object {
        const val MAX_DEPTH = 1000
        val SPLITTER = Regex("[_.]?([_.]*?[^_.]+)")
        val NESTED_PROPERTY = Regex("\\p{Lu}[\\p{Ll}\\p{Nd}]*$")
    }
}

/**
 * Spring Data 파생 질의 이름을 해석한 결과다(spring-data-commons `PartTree`의 규칙).
 *
 * @property properties 조건과 정렬에 쓰인 속성 원문(키워드·`IgnoreCase`를 뗀 것)
 */
internal data class DerivedQuery(val properties: List<String>) {
    companion object {
        /** 파생 질의 이름이 아니면 null이다 — `find…By` 같은 접두어가 없으면 Spring도 질의로 만들지 않는다. */
        fun parse(methodName: String): DerivedQuery? {
            val source = methodName.substringBefore('-')
            val prefix = PREFIX.find(source) ?: return null
            var predicate = source.substring(prefix.value.length)
            ALL_IGNORE_CASE.find(predicate)?.let { predicate = predicate.removeRange(it.range) }
            val sections = split(predicate, "OrderBy")
            if (sections.size > 2) return null
            val conditions = split(sections.getOrElse(0) { "" }, "Or").filter { it.isNotBlank() }
                .flatMap { split(it, "And") }.filter { it.isNotBlank() }
                .map(::conditionProperty)
            val ordering = sections.getOrNull(1)?.split(ORDER_BLOCK)?.filter { it.isNotBlank() }
                ?.map { decapitalize(DIRECTION.matchEntire(it)?.groupValues?.get(1) ?: it) }.orEmpty()
            return DerivedQuery(conditions + ordering)
        }

        /** 조건 조각에서 `IgnoreCase`와 비교 키워드를 떼어 속성 원문을 남긴다. */
        private fun conditionProperty(part: String): String {
            val withoutCase = IGNORE_CASE.find(part)?.let { part.removeRange(it.range) } ?: part
            val candidate = decapitalize(withoutCase)
            val keyword = KEYWORDS.firstOrNull { group -> group.any { withoutCase.endsWith(it) } }
                ?.firstOrNull { candidate.endsWith(it) }
            return if (keyword != null) candidate.removeSuffix(keyword) else candidate
        }

        /** Spring `PartTree.split`과 같다 — 키워드 뒤가 대문자나 비ASCII일 때만 나눈다. */
        private fun split(text: String, keyword: String): List<String> =
            text.split(Regex("($keyword)(?=(\\p{Lu}|\\P{InBASIC_LATIN}))"))

        val PREFIX = Regex("^(find|read|get|query|search|stream|count|exists|delete|remove)((\\p{Lu}.*?))??By")
        val ALL_IGNORE_CASE = Regex("AllIgnor(ing|e)Case")
        val IGNORE_CASE = Regex("Ignor(ing|e)Case")
        val ORDER_BLOCK = Regex("(?<=Asc|Desc)(?=\\p{Lu})")
        val DIRECTION = Regex("(.+?)(Asc|Desc)?$")

        /** `Part.Type.ALL` 순서의 키워드 묶음이다 — 순서가 판정 결과를 정한다(IsNotNull이 IsNull보다 먼저). */
        val KEYWORDS: List<List<String>> = listOf(
            listOf("IsNotNull", "NotNull"), listOf("IsNull", "Null"), listOf("IsBetween", "Between"),
            listOf("IsLessThan", "LessThan"), listOf("IsLessThanEqual", "LessThanEqual"),
            listOf("IsGreaterThan", "GreaterThan"), listOf("IsGreaterThanEqual", "GreaterThanEqual"),
            listOf("IsBefore", "Before"), listOf("IsAfter", "After"), listOf("IsNotLike", "NotLike"),
            listOf("IsLike", "Like"), listOf("IsStartingWith", "StartingWith", "StartsWith"),
            listOf("IsEndingWith", "EndingWith", "EndsWith"), listOf("IsNotEmpty", "NotEmpty"),
            listOf("IsEmpty", "Empty"), listOf("IsNotContaining", "NotContaining", "NotContains"),
            listOf("IsContaining", "Containing", "Contains"), listOf("IsNotIn", "NotIn"), listOf("IsIn", "In"),
            listOf("IsNear", "Near"), listOf("IsWithin", "Within"), listOf("MatchesRegex", "Matches", "Regex"),
            listOf("Exists"), listOf("IsTrue", "True"), listOf("IsFalse", "False"), listOf("IsNot", "Not"),
            listOf("Is", "Equals"),
        )
    }
}

/**
 * JPQL(과 HQL의 흔한 확장) 문자열에서 엔티티·속성 경로를 읽는다. 완전한 문법 파서가 아니다 —
 * 식별 변수 선언(`FROM`·`JOIN`·`UPDATE`·`DELETE FROM`·`IN (…) x`)을 모은 뒤 `별칭.경로` 참조를 해석한다.
 */
internal class JpqlResolver(private val model: JpaEntityModel, private val paths: JpaPathResolver) {

    private data class Token(val text: String, val kind: Kind)

    private enum class Kind { IDENTIFIER, PUNCTUATION, LITERAL }

    /** 질의 문자열을 해석한다. 선언된 엔티티를 모르거나 별칭 경로가 풀리지 않으면 [JpaTouch.unresolved]에 남긴다. */
    fun resolve(jpql: String): JpaTouch {
        val touch = JpaTouch()
        val tokens = tokenize(jpql)
        val aliases = mutableMapOf<String, Map<String, JpaAttribute>?>()
        val implicitRoots = mutableListOf<JpaEntity>()
        declarations(tokens, touch, aliases, implicitRoots)
        references(tokens, touch, aliases, implicitRoots)
        return touch
    }

    /** 식별 변수 선언을 읽는다 — 엔티티 범위와 연관 join 범위를 별칭에 묶는다. */
    private fun declarations(
        tokens: List<Token>,
        touch: JpaTouch,
        aliases: MutableMap<String, Map<String, JpaAttribute>?>,
        implicitRoots: MutableList<JpaEntity>,
    ) {
        var index = 0
        while (index < tokens.size) {
            val keyword = tokens[index].text.lowercase()
            val starts = keyword == "from" || keyword == "update" || keyword == "join"
            if (!starts) { index++; continue }
            index = rangeList(tokens, index + 1, keyword == "join", touch, aliases, implicitRoots)
        }
    }

    /** `FROM A a, B b`·`JOIN a.b c`·`UPDATE A a` 목록을 읽고 다음 위치를 돌려준다. */
    private fun rangeList(
        tokens: List<Token>,
        start: Int,
        join: Boolean,
        touch: JpaTouch,
        aliases: MutableMap<String, Map<String, JpaAttribute>?>,
        implicitRoots: MutableList<JpaEntity>,
    ): Int {
        var index = start
        if (tokens.getOrNull(index)?.text?.lowercase() == "fetch") index++
        while (index < tokens.size) {
            val target = tokens[index]
            // 선언 대상 자리의 이름은 예약어와 겹쳐도(`FROM Order o`) 엔티티 이름이다.
            if (target.kind != Kind.IDENTIFIER) return index
            if (target.text.lowercase() == "treat") return index + 1
            if (target.text.lowercase() == "in" && tokens.getOrNull(index + 1)?.text == "(") {
                val after = collectionMember(tokens, index + 2, touch, aliases)
                if (tokens.getOrNull(after)?.text != ",") return after
                index = after + 1
                continue
            }
            var next = index + 1
            if (tokens.getOrNull(next)?.text?.lowercase() == "as") next++
            val alias = tokens.getOrNull(next)?.takeIf { it.kind == Kind.IDENTIFIER && '.' !in it.text && it.text.lowercase() !in CLAUSE_KEYWORDS }
            val scope = bind(target.text, join, touch, aliases)
            if (alias != null) {
                aliases[alias.text.lowercase()] = scope
                next++
            } else if (!join && '.' !in target.text) {
                model.jpqlEntity(target.text.substringAfterLast('.'))?.let(implicitRoots::add)
            }
            if (tokens.getOrNull(next)?.text != ",") return next
            index = next + 1
        }
        return index
    }

    /** `IN (a.items) i` 컬렉션 멤버 선언이다. */
    private fun collectionMember(tokens: List<Token>, start: Int, touch: JpaTouch, aliases: MutableMap<String, Map<String, JpaAttribute>?>): Int {
        val path = tokens.getOrNull(start) ?: return start
        val scope = bind(path.text, join = true, touch = touch, aliases = aliases)
        var next = start + 1
        if (tokens.getOrNull(next)?.text == ")") next++
        if (tokens.getOrNull(next)?.text?.lowercase() == "as") next++
        tokens.getOrNull(next)?.takeIf { it.kind == Kind.IDENTIFIER }?.let { aliases[it.text.lowercase()] = scope; next++ }
        return next
    }

    /** 선언 대상(엔티티 이름 또는 `별칭.경로`)을 속성 범위로 묶는다. */
    private fun bind(text: String, join: Boolean, touch: JpaTouch, aliases: Map<String, Map<String, JpaAttribute>?>): Map<String, JpaAttribute>? {
        val head = text.substringBefore('.').lowercase()
        if (join && '.' in text && aliases.containsKey(head)) {
            val chain = walk(aliases[head], text.split('.').drop(1)) ?: run { touch.unresolved += text; return null }
            paths.touch(chain, touch)
            return paths.scopeOf(chain.last())
        }
        val entity = model.jpqlEntity(text.substringAfterLast('.'))
        if (entity == null) {
            touch.unresolved += text
            return null
        }
        touch.entity(entity)
        return entity.attributes
    }

    private fun walk(scope: Map<String, JpaAttribute>?, segments: List<String>): List<JpaAttribute>? {
        var current = scope ?: return null
        return segments.map { segment ->
            val attribute = current[segment] ?: return null
            current = paths.scopeOf(attribute) ?: emptyMap()
            attribute
        }
    }

    /** `별칭.경로` 참조와 암묵 별칭의 맨 속성 이름을 해석한다. */
    private fun references(
        tokens: List<Token>,
        touch: JpaTouch,
        aliases: Map<String, Map<String, JpaAttribute>?>,
        implicitRoots: List<JpaEntity>,
    ) {
        val implicit = implicitRoots.singleOrNull()?.takeIf { aliases.isEmpty() }
        tokens.forEachIndexed { index, token ->
            if (token.kind != Kind.IDENTIFIER) return@forEachIndexed
            if (tokens.getOrNull(index + 1)?.text == "(") return@forEachIndexed
            val head = token.text.substringBefore('.').lowercase()
            when {
                '.' in token.text && aliases.containsKey(head) -> {
                    val scope = aliases[head] ?: return@forEachIndexed
                    val chain = walk(scope, token.text.split('.').drop(1))
                    if (chain == null) touch.unresolved += token.text else paths.touch(chain, touch)
                }
                implicit != null && '.' !in token.text && token.text.lowercase() !in CLAUSE_KEYWORDS -> {
                    implicit.attributes[token.text]?.let { paths.touch(listOf(it), touch) }
                }
            }
        }
    }

    /** 문자열 리터럴·매개변수를 건너뛰고 점으로 이어진 경로를 한 토큰으로 묶는다. */
    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c.isWhitespace() -> index++
                c == '\'' -> {
                    var end = index + 1
                    while (end < text.length) {
                        if (text[end] == '\'' && text.getOrNull(end + 1) == '\'') end += 2
                        else if (text[end] == '\'') break
                        else end++
                    }
                    tokens += Token("''", Kind.LITERAL)
                    index = end + 1
                }
                c == ':' || c == '?' -> {
                    var end = index + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                    tokens += Token(text.substring(index, end), Kind.LITERAL)
                    index = end
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var end = index + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$' || text[end] == '.')) end++
                    tokens += Token(text.substring(index, end).trimEnd('.'), Kind.IDENTIFIER)
                    index = end
                }
                c.isDigit() -> {
                    var end = index + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '.')) end++
                    tokens += Token(text.substring(index, end), Kind.LITERAL)
                    index = end
                }
                else -> { tokens += Token(c.toString(), Kind.PUNCTUATION); index++ }
            }
        }
        return tokens
    }

    private companion object {
        /** 선언 목록을 끝내는 절 키워드와, 맨 이름을 속성으로 읽지 않을 JPQL 예약어다. */
        val CLAUSE_KEYWORDS = setOf(
            "select", "from", "where", "group", "by", "having", "order", "join", "left", "right", "inner", "outer",
            "full", "cross", "fetch", "on", "with", "set", "update", "delete", "insert", "into", "values", "and", "or",
            "not", "in", "is", "null", "like", "between", "exists", "all", "any", "some", "distinct", "new", "as",
            "asc", "desc", "case", "when", "then", "else", "end", "true", "false", "member", "of", "escape", "empty",
            "limit", "offset", "union", "intersect", "except", "nulls", "first", "last", "treat", "key", "value", "entry",
            "size", "index", "type", "count", "sum", "avg", "min", "max", "lower", "upper", "trim", "length", "concat",
            "substring", "locate", "abs", "sqrt", "mod", "coalesce", "nullif", "current_date", "current_time",
            "current_timestamp", "local", "object", "element", "elements", "indices",
        )
    }
}
