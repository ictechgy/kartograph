package dev.kartograph.index

import dev.kartograph.core.RouteParamConstraint

/**
 * Spring `PathPattern` 문자열을 isthmus 정규 경로 템플릿으로 바꾸는 순수 규칙이다.
 *
 * 근거는 spring-web 7.0.8·6.2.10 소스다: `PathPatternParser.initFullPathPattern`(앞 `/` 보충),
 * `PathPattern.combine`·`concat`(클래스×메서드 결합), `CaptureVariablePathElement`(세그먼트 전체 변수는 빈 값 불가),
 * `WildcardTheRestPathElement`·`CaptureTheRestPathElement`(끝 `**`·`{*x}`는 0개 이상 세그먼트),
 * `WildcardPathElement`(세그먼트 전체 `*`는 한 글자 이상), `RegexPathElement`(부분 세그먼트 변수·`?`·부분 `*`).
 * 템플릿 문법으로 옮길 수 없는 모양은 추측하지 않고 [Converted.dynamicReason]으로 알린다.
 */
internal object SpringPathPatterns {
    /**
     * 변환 결과다.
     *
     * @property template 정규 템플릿이다. dynamic이면 null이다
     * @property dynamicReason 템플릿으로 옮기지 못한 이유다
     * @property constraints 정규식 경로 변수 제약이다
     * @property catchAllPrefix 끝 catch-all이면 catch-all을 뗀 접두사 템플릿이다(0세그먼트 매칭 펼침)
     */
    data class Converted(
        val template: String?,
        val dynamicReason: String? = null,
        val constraints: List<RouteParamConstraint> = emptyList(),
        val catchAllPrefix: String? = null,
    )

    /** `PathPatternParser.initFullPathPattern` — 비어 있지 않고 `/`로 시작하지 않으면 `/`를 붙인다. */
    fun initFullPathPattern(pattern: String): String =
        if (pattern.isNotEmpty() && !pattern.startsWith('/')) "/$pattern" else pattern

    /**
     * 클래스 패턴과 메서드 패턴을 `PathPattern.combine` 규칙으로 잇는다.
     *
     * 클래스 패턴에 `*`·`?` 와일드카드가 있으면 combine이 경로 매칭으로 결과를 고르므로 옮기지 않고 null을 준다.
     * 그 밖에는 한쪽이 비면 다른 쪽, 둘 다 있으면 `concat`(겹치는 `/` 하나로)이다.
     */
    fun combine(typePattern: String, methodPattern: String): String? = when {
        typePattern.isEmpty() -> methodPattern
        methodPattern.isEmpty() -> typePattern
        hasGlob(typePattern) -> null
        else -> concat(typePattern, methodPattern)
    }

    private fun concat(first: String, second: String): String {
        val firstEnds = first.endsWith('/')
        val secondStarts = second.startsWith('/')
        return when {
            firstEnds && secondStarts -> first + second.substring(1)
            firstEnds || secondStarts -> first + second
            else -> "$first/$second"
        }
    }

    /** 변수 중괄호 밖에 `*`·`?`가 있는지 본다. */
    private fun hasGlob(pattern: String): Boolean = splitVariables(pattern)?.any { it is Piece.Glob } ?: true

    /**
     * 접두사까지 붙은 전체 패턴을 템플릿으로 바꾼다. 빈 패턴은 루트(`/`)다.
     *
     * @param pattern `/`로 시작하는 전체 패턴(context-path·base-path 포함) 또는 빈 문자열
     */
    fun convert(pattern: String): Converted {
        if (pattern.isEmpty() || pattern == "/") return Converted("/")
        if (!pattern.startsWith('/')) return Converted(null, "pattern is not rooted")
        val segments = pattern.substring(1).split('/')
        val converted = mutableListOf<String>()
        val constraints = mutableListOf<RouteParamConstraint>()
        segments.forEachIndexed { index, segment ->
            if (isCatchAll(segment)) {
                if (index != segments.lastIndex) return Converted(null, "catch-all is not the last segment")
                return catchAll(converted, constraints)
            }
            val result = convertSegment(segment, index) ?: return Converted(null, "segment shape has no template form")
            converted += result.first
            result.second?.let(constraints::add)
        }
        return finish("/" + converted.joinToString("/"), constraints)
    }

    private fun isCatchAll(segment: String): Boolean =
        segment == "**" || (segment.startsWith("{*") && segment.endsWith('}') && segment.indexOf('}') == segment.lastIndex)

    private fun catchAll(converted: List<String>, constraints: List<RouteParamConstraint>): Converted {
        val prefix = "/" + converted.joinToString("/")
        val template = if (converted.isEmpty()) "/{**}" else "$prefix/{**}"
        return finish(template, constraints).copy(catchAllPrefix = prefix)
    }

    private fun finish(template: String, constraints: List<RouteParamConstraint>): Converted {
        val reason = RouteUrlRules.validateTemplate(template) ?: return Converted(template, constraints = constraints)
        return Converted(null, "template is not canonical ($reason)")
    }

    /**
     * 세그먼트 하나를 바꾼다.
     *
     * @return (템플릿 세그먼트, 제약) 쌍. 옮길 수 없으면 null이다
     */
    private fun convertSegment(segment: String, index: Int): Pair<String, RouteParamConstraint?>? {
        if (segment == "*") return "{}" to null
        val pieces = splitVariables(segment) ?: return null
        val parameters = pieces.filterNot { it is Piece.Literal }
        if (parameters.isEmpty()) return RouteUrlRules.normalizePath(segment) to null
        val variable = parameters.singleOrNull() as? Piece.Variable ?: return null
        val skeleton = pieces.joinToString("") { piece -> if (piece is Piece.Literal) RouteUrlRules.normalizePath(piece.text) else "{}" }
        return skeleton to variable.regex?.let { constraint(index, it) }
    }

    /** 세그먼트 조각이다. */
    private sealed interface Piece {
        data class Literal(val text: String) : Piece
        data class Variable(val regex: String?) : Piece
        data object Glob : Piece
    }

    /**
     * 텍스트를 리터럴·변수(`{name}`·`{name:regex}`)·글롭(`*`·`?`)으로 나눈다. 정규식 안의 중첩 중괄호(`\d{3}`)를 센다.
     *
     * @return 조각 목록. 중괄호가 짝이 맞지 않으면 null이다
     */
    private fun splitVariables(text: String): List<Piece>? {
        val pieces = mutableListOf<Piece>()
        val literal = StringBuilder()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    val close = variableEnd(text, index) ?: return null
                    flushLiteral(literal, pieces)
                    pieces += variable(text.substring(index + 1, close)) ?: return null
                    index = close + 1
                    continue
                }
                '}' -> return null
                '*', '?' -> { flushLiteral(literal, pieces); pieces += Piece.Glob }
                else -> literal.append(text[index])
            }
            index++
        }
        flushLiteral(literal, pieces)
        return pieces
    }

    private fun flushLiteral(literal: StringBuilder, pieces: MutableList<Piece>) {
        if (literal.isEmpty()) return
        pieces += Piece.Literal(literal.toString())
        literal.clear()
    }

    private fun variable(body: String): Piece.Variable? {
        val colon = body.indexOf(':')
        val name = if (colon < 0) body else body.substring(0, colon)
        if (name.isEmpty() || name.startsWith('*')) return null
        return Piece.Variable(if (colon < 0) null else body.substring(colon + 1))
    }

    /** 변수 `{`에 짝이 맞는 `}` 위치다. 역슬래시 다음 글자는 중괄호로 세지 않는다. */
    private fun variableEnd(text: String, open: Int): Int? {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return index }
            }
            index++
        }
        return null
    }

    /** 정규식 제약을 계약의 닫힌 종류로 좁힌다. 포함 관계를 증명한 모양만 닫힌 종류이고 나머지는 `regex`다. */
    fun constraint(segment: Int, regex: String): RouteParamConstraint {
        val kind = when {
            regex in INT_PATTERNS -> "int"
            isUuidPattern(regex) -> "uuid"
            isSlugPattern(regex) -> "slug"
            else -> "regex"
        }
        return RouteParamConstraint(segment, kind, regex.takeIf { kind == "regex" && it.isNotEmpty() })
    }

    /** 언어가 `[+-]?[0-9]+`에 포함되는 흔한 정수 정규식이다. 빈 매칭은 세그먼트 전체 변수가 이미 막는다. */
    private val INT_PATTERNS = setOf(
        "\\d+", "[0-9]+", "\\d*", "[0-9]*", "-?\\d+", "-?[0-9]+", "[+-]?\\d+", "[+-]?[0-9]+", "[-+]?\\d+", "[-+]?[0-9]+",
    )

    private val HEX_CLASS = Regex("\\[(?:0-9a-fA-F|0-9A-Fa-f|a-fA-F0-9|A-Fa-f0-9|0-9a-f|a-f0-9|0-9A-F|A-F0-9|\\\\da-fA-F|\\\\da-f|\\\\dA-F)]")
    private val SLUG_CLASS = Regex("\\[[-A-Za-z0-9_\\\\]*]")

    /** 하이픈 있는 8-4-4-4-12 hex 모양이다. 각 묶음이 hex 문자 클래스와 정확한 반복 횟수여야 한다. */
    private fun isUuidPattern(regex: String): Boolean {
        val groups = Regex("(?<=})-").split(regex)
        if (groups.map { it.substringAfterLast('{').removeSuffix("}") } != listOf("8", "4", "4", "4", "12")) return false
        return groups.all { group -> HEX_CLASS.matches(group.substringBeforeLast('{')) }
    }

    /** `[문자클래스]+`·`\w+` 모양이고 문자 클래스가 slug 문자(`-A-Za-z0-9_`)만 담는지 본다. */
    private fun isSlugPattern(regex: String): Boolean {
        if (regex == "\\w+" || regex == "\\w*") return true
        val body = regex.removeSuffix("+").removeSuffix("*").takeIf { it.length < regex.length } ?: return false
        if (!SLUG_CLASS.matches(body)) return false
        return slugClassMembers(body.substring(1, body.length - 1))
    }

    /** 문자 클래스 본문이 범위 `a-z`·`A-Z`·`0-9`, 리터럴 `-`·`_`, `\w`·`\d`만으로 이뤄졌는지 본다. */
    private fun slugClassMembers(body: String): Boolean {
        var rest = body
        listOf("a-z", "A-Z", "0-9", "\\w", "\\d", "\\-").forEach { rest = rest.replace(it, "") }
        return rest.all { it == '-' || it == '_' }
    }
}
