package dev.kartograph.index

import dev.kartograph.core.BridgeLocation
import java.nio.file.Path

// JPA·Spring Data 스캔이 쓰는 경량 선언 모델이다. 완전한 Kotlin/Java 파서가 아니라
// 주석을 지우고 문자열을 가린 뷰에서 타입·멤버·어노테이션의 범위만 복원한다.
// routes 쪽 RouteSourceModel과 겹치는 부분(타입 몸체 범위, 상수 해석)은 추후 한 벌로 합칠 후보다.

/**
 * 선언에 붙은 어노테이션 하나다.
 *
 * @property name 단순 이름(`jakarta.persistence.Column`이면 `Column`)
 * @property useSite Kotlin use-site 타깃(`field`, `get` …). 없으면 null
 * @property arguments 괄호 안 최상위 인자 원문(문자열 리터럴 포함). 괄호가 없으면 빈 목록
 */
internal data class JpaAnnotation(
    val name: String,
    val useSite: String?,
    val offset: Int,
    val end: Int,
    val arguments: List<String>,
    val argumentRange: IntRange?,
) {
    /** `name = 값` 인자 값이다. [positional]이면 이름 없는 첫 인자도 받는다(`value` 관례). */
    fun argument(name: String, positional: Boolean = false): String? =
        arguments.firstNamed(name)
            ?: if (positional) arguments.firstOrNull()?.takeUnless { NAMED_ARGUMENT.containsMatchIn(it.trimStart()) } else null

    private companion object {
        val NAMED_ARGUMENT = Regex("^[A-Za-z_][A-Za-z0-9_.]*\\s*=(?!=)")
    }
}

/** 멤버 종류다 — 필드(Kotlin 프로퍼티 포함)와 Java getter를 구분한다(property access 판정). */
internal enum class JpaMemberKind { FIELD, GETTER }

/**
 * 타입 몸체나 Kotlin 주 생성자의 영속 후보 멤버다.
 *
 * @property persistent 정적·`transient`·backing field 없는 프로퍼티가 아니면 참이다
 * @property delegated Kotlin `by` 위임 프로퍼티다 — 컬럼 이름을 증명할 수 없다
 */
internal data class JpaMember(
    val name: String,
    val type: String?,
    val offset: Int,
    val annotations: List<JpaAnnotation>,
    val kind: JpaMemberKind,
    val persistent: Boolean,
    val delegated: Boolean = false,
)

/** 메서드 선언이다. [hasBody]가 거짓이면 추상(인터페이스) 메서드다. */
internal data class JpaMethod(
    val name: String,
    val offset: Int,
    val annotations: List<JpaAnnotation>,
    val parameterCount: Int,
    val hasBody: Boolean,
)

/**
 * 타입 선언 하나다.
 *
 * @property jvmName JVM 내부 이름(`com/example/Outer$Inner`) — 스냅샷 정점과 잇는 데 쓴다
 * @property supertypes 상위 타입 원문(`JpaRepository<Job, Long>`) — 생성자 호출 괄호는 뗐다
 */
internal data class JpaSourceType(
    val name: String,
    val jvmName: String,
    val keyword: String,
    val offset: Int,
    val modifiers: Set<String>,
    val annotations: List<JpaAnnotation>,
    val typeParameters: List<String>,
    val supertypes: List<String>,
    val members: List<JpaMember>,
    val methods: List<JpaMethod>,
    val bodyStart: Int,
    val bodyEnd: Int,
) {
    val isInterface: Boolean get() = keyword == "interface"

    /** 단순 이름의 어노테이션이 있는지 본다. */
    fun has(annotation: String): Boolean = annotations.any { it.name == annotation }

    fun annotation(name: String): JpaAnnotation? = annotations.firstOrNull { it.name == name }
}

/** JPA 스캔용 파일 하나다 — 원문·주석 제거 뷰·마스킹 뷰와 타입 목록을 한 번만 만든다. */
internal class JpaSourceFile(root: Path, path: Path) {
    val relative: String = root.relativize(path.toAbsolutePath().normalize()).joinToString("/")
    val isJava: Boolean = path.fileName.toString().endsWith(".java")
    val source: String = ProjectTraversal.readSourceLines(root, path).joinToString("\n")
    val code: String = stripComments(source)
    val masked: String = maskStringContents(code)
    val packageName: String = PACKAGE.find(masked)?.groupValues?.get(1).orEmpty()
    val imports: List<String> = IMPORT.findAll(masked).map { it.groupValues[1] }.toList()
    private val annotationTokens: List<JpaAnnotation> = collectAnnotations()

    /** 다른 어노테이션 인자 안에 들어 있지 않은 어노테이션이다 — 선언에 붙는 사슬은 이것들로만 잇는다. */
    private val topLevelTokens: List<JpaAnnotation> = annotationTokens.filter { token ->
        annotationTokens.none { outer -> outer !== token && outer.argumentRange?.let { token.offset in it } == true }
    }
    val types: List<JpaSourceType> = collectTypes()

    /** 원문 오프셋의 교환 계약 위치다. */
    fun location(offset: Int): BridgeLocation = sourceLocation(relative, source, offset)

    /** 파일이 해당 패키지 접두어 중 하나를 import하는지 본다. */
    fun imports(vararg prefixes: String): Boolean =
        imports.any { import -> prefixes.any { import == it || import.startsWith("$it.") } }

    private fun collectAnnotations(): List<JpaAnnotation> = ANNOTATION.findAll(masked).mapNotNull { match ->
        val name = match.groupValues[2].substringAfterLast('.')
        if (name == "interface") return@mapNotNull null
        var index = match.range.last + 1
        while (index < masked.length && masked[index].isWhitespace() && masked[index] != '\n') index++
        val open = index.takeIf { masked.getOrNull(it) == '(' }
        val close = open?.let { balancedEnd(code, it) }?.takeIf { it > open }
        JpaAnnotation(
            name = name,
            useSite = match.groupValues[1].ifEmpty { null },
            offset = match.range.first,
            end = (close ?: match.range.last) + 1,
            arguments = if (open != null && close != null) callArguments(code, open, close) else emptyList(),
            argumentRange = if (open != null && close != null) open..close else null,
        )
    }.toList()

    /** 선언 시작 직전까지 이어진 어노테이션 사슬이다 — 사이에는 공백·한정어만 올 수 있다. */
    fun annotationsBefore(declarationStart: Int, floor: Int = 0): List<JpaAnnotation> {
        val chain = ArrayDeque<JpaAnnotation>()
        var cursor = declarationStart
        for (annotation in topLevelTokens.asReversed()) {
            if (annotation.end > cursor) continue
            if (annotation.offset < floor) break
            if (!isModifierGap(masked.substring(annotation.end, cursor))) break
            chain.addFirst(annotation)
            cursor = annotation.offset
        }
        return chain.toList()
    }

    /** 어노테이션 인자 범위 안의 어노테이션이다(`@AttributeOverride(column = @Column(..))`의 안쪽). */
    fun annotationsInside(range: IntRange): List<JpaAnnotation> =
        annotationTokens.filter { it.offset > range.first && it.end <= range.last + 1 }

    private fun isModifierGap(text: String): Boolean =
        text.split(WHITESPACE).all { it.isBlank() || it in MODIFIERS }

    private fun collectTypes(): List<JpaSourceType> {
        val matches = TYPE_DECL.findAll(masked).filterNot { isCompanionOrAnonymous(it) }.toList()
        val shells = matches.mapIndexed { index, match ->
            val bound = matches.getOrNull(index + 1)?.range?.first ?: masked.length
            TypeShell(match.groupValues[2], keywordOf(match.groupValues[1]), match.range.first, match.range.last + 1, bound)
        }
        val bodies = shells.map { bodyRange(it) }
        return shells.mapIndexed { index, shell ->
            val (bodyStart, bodyEnd) = bodies[index]
            val outers = shells.indices.filter { other ->
                other != index && bodies[other].first >= 0 && shell.start in bodies[other].first..bodies[other].second
            }.sortedBy { shells[it].start }.map { shells[it].name }
            val nested = shells.indices.filter { other ->
                other != index && bodyStart >= 0 && shells[other].start in bodyStart..bodyEnd
            }.map { shells[it].start to bodies[it] }
            buildType(shell, bodyStart, bodyEnd, outers, nested)
        }
    }

    /** `companion object {`처럼 이름 없는 선언은 타입 선언 정규식이 잡지 않는다 — 여기선 `object :` 익명 식을 거른다. */
    private fun isCompanionOrAnonymous(match: MatchResult): Boolean =
        match.groupValues[1] == "object" && masked.substring(0, match.range.first).trimEnd().endsWith("=")

    private fun keywordOf(raw: String): String = when {
        raw.startsWith("enum") -> "enum"
        raw.startsWith("annotation") || raw == "@interface" -> "annotation"
        else -> raw
    }

    private data class TypeShell(val name: String, val keyword: String, val start: Int, val nameEnd: Int, val bound: Int)

    /** 몸체 `{`…`}` 범위다 — 몸체가 없으면 (-1, 머리 끝)이다. */
    private fun bodyRange(shell: TypeShell): Pair<Int, Int> {
        val open = headerEnd(shell)
        if (open < 0 || masked.getOrNull(open) != '{') return -1 to maxOf(open, shell.nameEnd)
        val close = braceEndOf(masked, open)
        return open to (if (close < 0) masked.length - 1 else close)
    }

    /** 머리가 끝나는 위치다 — 괄호 깊이 0의 `{`, 또는 이어지지 않는 줄바꿈·`;`. */
    private fun headerEnd(shell: TypeShell): Int {
        var depth = 0
        var index = shell.nameEnd
        while (index < shell.bound) {
            when (masked[index]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '{' -> if (depth <= 0) return index
                ';' -> if (depth <= 0) return index
                '\n' -> if (depth <= 0 && !isJava && !headerContinues(index)) return index
            }
            index++
        }
        return shell.bound
    }

    /** Kotlin 머리가 다음 줄로 이어지는지 본다 — `:`·`,`·`{`·`where`로 시작하거나 앞 줄이 `:`·`,`로 끝나면 이어진다. */
    private fun headerContinues(newline: Int): Boolean {
        val before = masked.substring(0, newline).trimEnd()
        if (before.endsWith(":") || before.endsWith(",") || before.endsWith("(")) return true
        val after = masked.substring(newline + 1).trimStart()
        return after.startsWith(":") || after.startsWith(",") || after.startsWith("{") ||
            after.startsWith("where ") || after.startsWith(")")
    }

    private fun buildType(
        shell: TypeShell,
        bodyStart: Int,
        bodyEnd: Int,
        outers: List<String>,
        nested: List<Pair<Int, Pair<Int, Int>>>,
    ): JpaSourceType {
        val headerEnd = if (bodyStart >= 0) bodyStart else bodyEnd
        val header = masked.substring(shell.nameEnd, headerEnd.coerceAtLeast(shell.nameEnd))
        val typeParameters = typeParameters(header)
        val constructor = if (isJava) null else primaryConstructor(shell.nameEnd, headerEnd)
        val members = mutableListOf<JpaMember>()
        constructor?.let { members += constructorProperties(it) }
        if (bodyStart >= 0) {
            val inNested = { offset: Int -> nested.any { (start, body) -> offset >= start && offset <= maxOf(body.second, start) } }
            members += bodyMembers(bodyStart, bodyEnd, inNested)
        }
        val methods = if (bodyStart >= 0) bodyMethods(bodyStart, bodyEnd, nested) else emptyList()
        val jvmName = (packageName.replace('.', '/').takeIf { it.isNotEmpty() }?.let { "$it/" } ?: "") +
            (outers + shell.name).joinToString("$")
        return JpaSourceType(
            name = shell.name,
            jvmName = jvmName,
            keyword = shell.keyword,
            offset = shell.start,
            modifiers = typeModifiers(shell.start),
            annotations = annotationsBefore(shell.start),
            typeParameters = typeParameters,
            supertypes = supertypes(header),
            members = members.sortedBy { it.offset },
            methods = methods,
            bodyStart = bodyStart,
            bodyEnd = bodyEnd,
        )
    }

    /** 키워드 앞 같은 줄의 한정어(`abstract`, `data` …)다. */
    private fun typeModifiers(keywordStart: Int): Set<String> {
        val lineStart = masked.lastIndexOf('\n', keywordStart - 1) + 1
        return ANNOTATION_TEXT.replace(masked.substring(lineStart, keywordStart), " ")
            .split(WHITESPACE).filter { it in MODIFIERS }.toSet()
    }

    private fun typeParameters(header: String): List<String> {
        val trimmed = header.trimStart()
        if (!trimmed.startsWith("<")) return emptyList()
        val close = angleEnd(trimmed, 0)
        if (close < 0) return emptyList()
        return splitTopLevel(trimmed.substring(1, close)).mapNotNull { parameter ->
            IDENTIFIER.findAll(parameter).map { it.value }.firstOrNull { it != "in" && it != "out" && it != "reified" }
        }
    }

    /** Kotlin 주 생성자 괄호 범위다 — 타입 인자·한정어·`constructor` 뒤의 첫 `(`다. */
    private fun primaryConstructor(nameEnd: Int, headerEnd: Int): IntRange? {
        var index = nameEnd
        while (index < headerEnd && masked[index].isWhitespace()) index++
        if (masked.getOrNull(index) == '<') index = angleEnd(masked, index) + 1
        if (index <= 0) return null
        val rest = masked.substring(index, headerEnd)
        val open = rest.indexOf('(').takeIf { it >= 0 } ?: return null
        // 여는 괄호 앞에는 어노테이션·한정어·`constructor`만 올 수 있다 — `:` 뒤 상위 타입 호출이 아니다.
        val prefix = ANNOTATION_TEXT.replace(rest.substring(0, open), " ")
        if (!prefix.split(WHITESPACE).all { it.isBlank() || it == "constructor" || it in MODIFIERS }) return null
        val start = index + open
        val end = balancedEnd(code, start)
        return if (end > start) start..end else null
    }

    /** 상위 타입 원문 목록이다 — 타입 인자 선언은 떼고, Kotlin 생성자 호출·`by` 위임은 벗긴다. */
    private fun supertypes(header: String): List<String> {
        val trimmed = header.trimStart()
        val afterParameters = if (trimmed.startsWith("<")) trimmed.substring(angleEnd(trimmed, 0) + 1) else trimmed
        if (isJava) {
            return JAVA_SUPER.findAll(afterParameters).flatMap { splitTopLevel(it.groupValues[2]) }
                .map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        val colon = topLevelColon(afterParameters) ?: return emptyList()
        val list = afterParameters.substring(colon + 1).split(Regex("\\bwhere\\b")).first()
        return splitTopLevel(list).map { stripCall(it.substringBefore(" by ").trim()) }.filter { it.isNotEmpty() }
    }

    /** 꺾쇠 깊이 0의 첫 `(`부터 뒤를 뗀다 — `Base<T>(arg)`는 `Base<T>`다. */
    private fun stripCall(text: String): String {
        var depth = 0
        text.forEachIndexed { index, c ->
            when (c) {
                '<' -> depth++
                '>' -> depth--
                '(' -> if (depth == 0) return text.substring(0, index).trim()
            }
        }
        return text
    }

    private fun topLevelColon(text: String): Int? {
        var depth = 0
        text.forEachIndexed { index, c ->
            when (c) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                ':' -> if (depth == 0) return index
            }
        }
        return null
    }

    /** 주 생성자의 `val`·`var` 매개변수만 프로퍼티다. */
    private fun constructorProperties(range: IntRange): List<JpaMember> =
        splitWithOffsets(range.first + 1, range.last).mapNotNull { (start, end) ->
            val text = masked.substring(start, end)
            val match = CONSTRUCTOR_PROPERTY.find(text) ?: return@mapNotNull null
            val nameOffset = start + match.groups[2]!!.range.first
            val annotations = topLevelTokens.filter { it.offset >= start && it.end <= start + match.range.first }
            JpaMember(
                name = match.groupValues[2],
                type = match.groupValues[3].trim().ifEmpty { null },
                offset = nameOffset,
                annotations = annotations,
                kind = JpaMemberKind.FIELD,
                persistent = true,
            )
        }

    /** 몸체 깊이 0의 필드·프로퍼티·getter다. */
    private fun bodyMembers(bodyStart: Int, bodyEnd: Int, inNested: (Int) -> Boolean): List<JpaMember> {
        val body = bodyStart + 1 until bodyEnd
        val depths = depthMap(bodyStart, bodyEnd)
        fun topLevel(offset: Int) = offset in body && depths(offset) == 0 && !inNested(offset)
        return if (isJava) javaFields(body, ::topLevel) + javaGetters(body, ::topLevel)
        else kotlinProperties(body, ::topLevel, bodyEnd)
    }

    /**
     * 몸체 깊이 0의 `;`로 끝나는 문장을 필드 선언으로 읽는다 — 어노테이션을 걷어 낸 뒤 초기값(`=` 뒤)을 떼고
     * 마지막 식별자를 이름으로, 그 앞을 타입으로 본다. 쉼표로 여러 이름을 선언한 문장은 읽지 않는다.
     */
    private fun javaFields(body: IntRange, topLevel: (Int) -> Boolean): List<JpaMember> {
        val members = mutableListOf<JpaMember>()
        var statementStart = body.first
        var parentheses = 0
        for (index in body) {
            val c = masked[index]
            if (c == '(') parentheses++
            if (c == ')') parentheses--
            // 어노테이션 인자 안의 배열 중괄호(`@AttributeOverrides({ … })`)는 문장 경계가 아니다.
            if (parentheses > 0) continue
            if ((c == '{' || c == '}') && topLevel(index)) statementStart = index + 1
            if (c != ';' || !topLevel(index)) continue
            javaField(statementStart, index)?.let(members::add)
            statementStart = index + 1
        }
        return members
    }

    private fun javaField(start: Int, end: Int): JpaMember? {
        val withoutAnnotations = blankAnnotations(start, end)
        val declaration = withoutAnnotations.substring(0, topLevelEquals(withoutAnnotations) ?: withoutAnnotations.length)
        if (declaration.contains('(') || declaration.contains(',') && !declaration.contains('<')) return null
        val name = TRAILING_IDENTIFIER.find(declaration)?.groups?.get(1) ?: return null
        val nameOffset = start + name.range.first
        val (type, typeStart) = javaTypeBefore(nameOffset) ?: return null
        if (type in JAVA_NON_TYPES || type in MODIFIERS) return null
        val modifiers = declaration.substring(0, typeStart - start).split(WHITESPACE).filter { it in MODIFIERS }.toSet()
        return JpaMember(
            name = name.value,
            type = type,
            offset = nameOffset,
            annotations = topLevelTokens.filter { it.offset >= start && it.end <= typeStart },
            kind = JpaMemberKind.FIELD,
            persistent = "static" !in modifiers && "transient" !in modifiers,
        )
    }

    /** `[start, end)` 마스킹 뷰에서 어노테이션 토큰(인자 포함)을 공백으로 지운다 — 중첩 깊이와 무관하다. */
    private fun blankAnnotations(start: Int, end: Int): String {
        val chars = masked.substring(start, end).toCharArray()
        topLevelTokens.filter { it.offset >= start && it.offset < end }.forEach { token ->
            for (index in token.offset until minOf(token.end, end)) chars[index - start] = ' '
        }
        return String(chars)
    }

    private fun topLevelEquals(text: String): Int? {
        var depth = 0
        text.forEachIndexed { index, c ->
            when (c) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> depth--
                '=' -> if (depth == 0) return index
            }
        }
        return null
    }

    private fun javaGetters(body: IntRange, topLevel: (Int) -> Boolean): List<JpaMember> =
        JAVA_GETTER.findAll(masked.substring(body.first, body.last + 1)).mapNotNull { match ->
            val nameOffset = body.first + match.range.first
            if (!topLevel(nameOffset)) return@mapNotNull null
            val (type, typeStart) = javaTypeBefore(nameOffset) ?: return@mapNotNull null
            if (match.groupValues[1] == "is" && type !in BOOLEAN_TYPES) return@mapNotNull null
            val modifiers = modifiersBefore(typeStart)
            JpaMember(
                name = decapitalize(match.groupValues[2]),
                type = type,
                offset = nameOffset,
                annotations = annotationsBefore(typeStart),
                kind = JpaMemberKind.GETTER,
                persistent = "static" !in modifiers,
            )
        }.toList()

    private fun kotlinProperties(body: IntRange, topLevel: (Int) -> Boolean, bodyEnd: Int): List<JpaMember> =
        KOTLIN_PROPERTY.findAll(masked.substring(body.first, body.last + 1)).mapNotNull { match ->
            val keywordOffset = body.first + match.range.first
            if (!topLevel(keywordOffset)) return@mapNotNull null
            val nameOffset = body.first + match.groups[2]!!.range.first
            val tail = propertyTail(body.first + match.range.last + 1, bodyEnd)
            JpaMember(
                name = match.groupValues[2],
                type = tail.type,
                offset = nameOffset,
                annotations = annotationsBefore(keywordOffset),
                kind = JpaMemberKind.FIELD,
                persistent = tail.backingField && !tail.delegated,
                delegated = tail.delegated,
            )
        }.toList()

    private data class PropertyTail(val type: String?, val backingField: Boolean, val delegated: Boolean)

    /**
     * 프로퍼티 이름 뒤를 읽는다 — 타입, 초기값·위임 여부, 사용자 getter만 있고 `field`를 쓰지 않는
     * 계산 프로퍼티(backing field 없음)를 가린다.
     */
    private fun propertyTail(from: Int, limit: Int): PropertyTail {
        val lineEnd = masked.indexOf('\n', from).let { if (it < 0 || it > limit) limit else it }
        val line = masked.substring(from, lineEnd)
        val type = if (line.trimStart().startsWith(":")) {
            line.trimStart().removePrefix(":").split(Regex("[=;{]|\\bby\\b|\\bget\\b")).first().trim().ifEmpty { null }
        } else null
        val inlineAccessor = Regex("\\b(?:get|set)\\s*\\(").find(line)
        val declaration = inlineAccessor?.let { line.substring(0, it.range.first) } ?: line
        val delegated = Regex("\\bby\\b").containsMatchIn(declaration)
        val initialized = declaration.contains('=')
        val next = (if (inlineAccessor != null) line.substring(inlineAccessor.range.first) else masked.substring(lineEnd, limit)).trimStart()
        val accessor = ACCESSOR.find(next)?.takeIf { it.range.first == 0 }
        if (initialized || accessor == null || accessor.groupValues[1] != "get") {
            return PropertyTail(type, backingField = true, delegated = delegated)
        }
        // 사용자 getter 몸체가 `field`를 쓰지 않으면 backing field가 없다.
        val accessorEnd = next.indexOf('\n', accessor.range.last).let { if (it < 0) next.length else it }
        val usesField = Regex("\\bfield\\b").containsMatchIn(next.substring(0, accessorEnd))
        return PropertyTail(type, backingField = usesField, delegated = delegated)
    }

    /** 몸체 깊이 0의 메서드다(Spring Data 저장소 메서드 판정용). */
    private fun bodyMethods(bodyStart: Int, bodyEnd: Int, nested: List<Pair<Int, Pair<Int, Int>>>): List<JpaMethod> {
        val depths = depthMap(bodyStart, bodyEnd)
        val inNested = { offset: Int -> nested.any { (start, body) -> offset >= start && offset <= maxOf(body.second, start) } }
        val pattern = if (isJava) JAVA_METHOD_NAME else KOTLIN_FUNCTION
        return pattern.findAll(masked, bodyStart + 1).takeWhile { it.range.first < bodyEnd }.mapNotNull { match ->
            val nameGroup = match.groups[1]!!
            if (depths(match.range.first) != 0 || inNested(match.range.first)) return@mapNotNull null
            val declarationStart = if (isJava) {
                javaTypeBefore(nameGroup.range.first)?.takeIf { it.first !in JAVA_NON_TYPES && !it.first.endsWith(".") }?.second
                    ?: return@mapNotNull null
            } else match.range.first
            // 상수 초기값 안의 호출(`String X = A.normalize("…");`)은 메서드 선언이 아니다.
            if (isJava && isInitializer(declarationStart)) return@mapNotNull null
            val open = masked.indexOf('(', nameGroup.range.last)
            val close = balancedEnd(code, open)
            if (close <= open) return@mapNotNull null
            val parameters = callArguments(code, open, close).filter { it.isNotBlank() }
            JpaMethod(
                name = nameGroup.value,
                offset = nameGroup.range.first,
                annotations = annotationsBefore(declarationStart),
                parameterCount = parameters.size,
                hasBody = hasMethodBody(close, bodyEnd),
            )
        }.toList()
    }

    /** 선언 시작이 같은 문장 안의 `=` 뒤(초기값 식)에 있는지 본다. */
    private fun isInitializer(declarationStart: Int): Boolean {
        val statementStart = maxOf(
            masked.lastIndexOf(';', declarationStart - 1),
            masked.lastIndexOf('{', declarationStart - 1),
            masked.lastIndexOf('}', declarationStart - 1),
        ) + 1
        return blankAnnotations(statementStart, declarationStart).contains('=')
    }

    /** 매개변수 괄호 뒤에 `{`·`=`(Kotlin 식 몸체)가 선언 끝보다 먼저 오면 몸체가 있다. */
    private fun hasMethodBody(close: Int, bodyEnd: Int): Boolean {
        var index = close + 1
        var depth = 0
        while (index < bodyEnd) {
            val c = masked[index]
            when {
                c == '<' || c == '(' -> depth++
                c == '>' || c == ')' -> depth--
                depth > 0 -> Unit
                c == '{' -> return true
                c == '=' -> return !isJava
                c == ';' -> return false
                c == '\n' && !isJava -> {
                    val next = masked.substring(index + 1, bodyEnd).trimStart()
                    if (!next.startsWith("{") && !next.startsWith("=") && !next.startsWith(":")) return false
                }
                c == '@' -> return false
            }
            index++
        }
        return false
    }

    /** `[start, offset)` 구간의 중괄호 깊이를 오프셋마다 계산하는 조회 함수다. */
    private fun depthMap(bodyStart: Int, bodyEnd: Int): (Int) -> Int {
        val depths = IntArray((bodyEnd - bodyStart).coerceAtLeast(1) + 1)
        var depth = 0
        var parentheses = 0
        for (index in bodyStart + 1 until bodyEnd) {
            depths[index - bodyStart] = depth
            // 괄호 안의 중괄호(어노테이션 배열 인자)는 몸체 깊이를 바꾸지 않는다.
            when (masked[index]) {
                '(' -> parentheses++
                ')' -> parentheses--
                '{' -> if (parentheses <= 0) depth++
                '}' -> if (parentheses <= 0) depth--
            }
        }
        return { offset -> if (offset in bodyStart + 1 until bodyEnd) depths[offset - bodyStart] else -1 }
    }

    /** Java 멤버 이름 앞의 타입 원문과 시작 위치다 — 제네릭 안의 공백·쉼표를 허용한다. */
    private fun javaTypeBefore(nameOffset: Int): Pair<String, Int>? {
        var index = nameOffset - 1
        while (index >= 0 && masked[index].isWhitespace()) index--
        val end = index + 1
        var angle = 0
        while (index >= 0) {
            val c = masked[index]
            when {
                c == '>' -> angle++
                c == '<' -> angle--
                c == ']' || c == '[' || c == '.' || c == '?' -> Unit
                angle > 0 && (c == ',' || c.isWhitespace() || c.isLetterOrDigit() || c == '_' || c == '@') -> Unit
                c.isLetterOrDigit() || c == '_' || c == '$' -> Unit
                else -> break
            }
            index--
        }
        val start = index + 1
        if (start >= end || angle != 0) return null
        val type = masked.substring(start, end).trim()
        if (type.isEmpty() || !type.first().isLetter()) return null
        return type to start
    }

    private fun modifiersBefore(typeStart: Int): Set<String> {
        val lineStart = maxOf(masked.lastIndexOf(';', typeStart - 1), masked.lastIndexOf('{', typeStart - 1), masked.lastIndexOf('}', typeStart - 1)) + 1
        return ANNOTATION_TEXT.replace(masked.substring(lineStart, typeStart), " ").split(WHITESPACE).filter { it in MODIFIERS }.toSet()
    }

    /** `(`…`)` 사이 최상위 쉼표로 나눈 (시작, 끝) 오프셋 목록이다. */
    private fun splitWithOffsets(from: Int, until: Int): List<Pair<Int, Int>> {
        val parts = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = from
        for (index in from until until) {
            when (masked[index]) {
                '(', '<', '[', '{' -> depth++
                ')', '>', ']', '}' -> depth--
                ',' -> if (depth == 0) { parts += start to index; start = index + 1 }
            }
        }
        if (masked.substring(start, until).isNotBlank()) parts += start to until
        return parts
    }

    private companion object {
        val PACKAGE = Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)")
        val IMPORT = Regex("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_][A-Za-z0-9_.*]*)")
        val ANNOTATION = Regex("(?<![\\w@])@(?:([A-Za-z]+)\\s*:\\s*)?([A-Za-z_][A-Za-z0-9_.]*)")
        val ANNOTATION_TEXT = Regex("@[A-Za-z_][A-Za-z0-9_.:]*\\s*(?:\\([^()]*(?:\\([^()]*\\)[^()]*)*\\))?")
        val TYPE_DECL = Regex(
            "(?<![\\w:.$])(@interface|enum\\s+class|annotation\\s+class|class|interface|object|record|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )
        val JAVA_SUPER = Regex("\\b(extends|implements)\\s+([^{]+?)(?=\\bimplements\\b|\\bpermits\\b|$)")
        val CONSTRUCTOR_PROPERTY = Regex("\\b(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([^=]+))?")
        val KOTLIN_PROPERTY = Regex("\\b(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
        val ACCESSOR = Regex("^(?:(?:private|protected|internal|public)\\s+)?(get|set)\\b")
        val TRAILING_IDENTIFIER = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$")
        val JAVA_GETTER = Regex("\\b(get|is)([A-Z][A-Za-z0-9_]*)\\s*\\(\\s*\\)\\s*(?:throws[^{;]*)?\\{")
        val JAVA_METHOD_NAME = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        val KOTLIN_FUNCTION = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>?]*\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val WHITESPACE = Regex("\\s+")
        val BOOLEAN_TYPES = setOf("boolean", "Boolean", "java.lang.Boolean")
        val JAVA_NON_TYPES = setOf("return", "throw", "new", "else", "case", "package", "import", "assert", "yield", "goto")
        val MODIFIERS = setOf(
            "data", "open", "abstract", "sealed", "inner", "enum", "value", "final", "fun",
            "public", "private", "protected", "internal", "static", "const", "expect",
            "actual", "lateinit", "override", "external", "transient", "default",
            "volatile", "synchronized", "native", "strictfp", "suspend", "inline", "operator", "infix",
            "tailrec", "annotation", "non-sealed",
        )
    }
}

/** 문자열 안의 `<`…`>` 짝의 닫는 위치다 — 없으면 -1. */
internal fun angleEnd(text: String, open: Int): Int {
    var depth = 0
    for (index in open until text.length) {
        when (text[index]) {
            '<' -> depth++
            '>' -> { depth--; if (depth == 0) return index }
        }
    }
    return -1
}

/** 괄호·꺾쇠·대괄호 깊이 0의 쉼표로 나눈다. */
internal fun splitTopLevel(text: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    text.forEachIndexed { index, c ->
        when (c) {
            '(', '<', '[', '{' -> depth++
            ')', '>', ']', '}' -> depth--
            ',' -> if (depth == 0) { parts += text.substring(start, index); start = index + 1 }
        }
    }
    parts += text.substring(start)
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/** JavaBeans `Introspector.decapitalize`와 같다 — 앞 두 글자가 대문자면 그대로 둔다. */
internal fun decapitalize(name: String): String = when {
    name.isEmpty() -> name
    name.length > 1 && name[0].isUpperCase() && name[1].isUpperCase() -> name
    else -> name[0].lowercaseChar() + name.substring(1)
}

/** `{`부터 짝이 맞는 `}` 위치다 — 마스킹 뷰라 문자열 안 중괄호는 이미 가려져 있다. */
private fun braceEndOf(masked: String, open: Int): Int {
    var depth = 0
    for (index in open until masked.length) {
        when (masked[index]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return index }
        }
    }
    return -1
}
