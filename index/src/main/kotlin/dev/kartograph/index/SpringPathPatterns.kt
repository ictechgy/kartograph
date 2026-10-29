package dev.kartograph.index

import dev.kartograph.core.RouteParamConstraint

/**
 * Spring `PathPattern` 문자열을 isthmus 정규 경로 템플릿으로 바꾸는 순수 규칙이다.
 *
 * 근거는 spring-web 7.0.8·6.2.10 소스다: `PathPatternParser.initFullPathPattern`(앞 `/` 보충),
 * `PathPattern.combine`·`concat`(클래스×메서드 결합), `CaptureVariablePathElement`(세그먼트 전체 변수는 빈 값 불가),
 * `WildcardTheRestPathElement`·`CaptureTheRestPathElement`(끝 `**`·`{*x}`는 0개 이상 세그먼트),
 * `WildcardPathElement`(가운데 세그먼트 전체 `*`는 한 글자 이상, 마지막 요소 `*`는 빈 값도 받음), `RegexPathElement`(부분 세그먼트
 * 변수·`*`는 빈 값도 받음, `?`). 템플릿 문법으로 옮길 수 없는 모양은 추측하지 않고 [Converted.dynamicReason]으로 알린다.
 *
 * 빈 값 변형: 계약의 소비자 매칭은 decl `{}`를 빈 세그먼트와, 부분 세그먼트 `p{}s`를 가운데가 빈 값과 맞추지 않는다. 그래서 Spring이
 * 빈 값을 받는 자리(끝 `*`, 부분 세그먼트의 변수·`*`)를 빈 값으로 채운 템플릿을 [Converted.emptyValueVariants]로 함께 낸다
 * (isthmus `spring/trailing-wildcard-matches-empty`·`spring/partial-variable-allows-empty` 벡터). 원본을 포함한 변형 수가
 * [MAX_VARIANTS]를 넘으면 optional 세그먼트 펼침과 같은 상한 규칙으로 dynamic이다([Converted.expansionCapped]).
 */
internal object SpringPathPatterns {
    /**
     * 변환 결과다.
     *
     * @property template 정규 템플릿이다. dynamic이면 null이다
     * @property dynamicReason 템플릿으로 옮기지 못한 이유다
     * @property constraints 정규식 경로 변수 제약이다
     * @property catchAllPrefix 끝 catch-all이면 catch-all을 뗀 접두사 템플릿이다(0세그먼트 매칭 펼침)
     * @property emptyValueVariants 빈 값을 받는 자리를 빈 값으로 채운 변형이다(원본 제외). 각 변형도 자기 접두사·제약을 싣는다
     * @property expansionCapped 변형 수가 상한을 넘어 dynamic으로 낸다는 표시다(`route-template-expansion-capped:`)
     */
    data class Converted(
        val template: String?,
        val dynamicReason: String? = null,
        val constraints: List<RouteParamConstraint> = emptyList(),
        val catchAllPrefix: String? = null,
        val emptyValueVariants: List<Converted> = emptyList(),
        val expansionCapped: Boolean = false,
    )

    /** 원본을 포함한 빈 값 변형 템플릿 수의 상한이다(계약의 optional 세그먼트 펼침 상한과 같다). */
    const val MAX_VARIANTS: Int = 16

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
    private fun hasGlob(pattern: String): Boolean = splitVariables(pattern)?.any { it is Piece.Star || it is Piece.Question } ?: true

    /**
     * 접두사까지 붙은 전체 패턴을 템플릿으로 바꾼다. 빈 패턴은 루트(`/`)다.
     *
     * @param pattern `/`로 시작하는 전체 패턴(context-path·base-path 포함) 또는 빈 문자열
     */
    fun convert(pattern: String): Converted {
        if (pattern.isEmpty() || pattern == "/") return Converted("/")
        if (!pattern.startsWith('/')) return Converted(null, "pattern is not rooted")
        val segments = pattern.substring(1).split('/')
        val forms = mutableListOf<SegmentForm>()
        var catchAll = false
        segments.forEachIndexed { index, segment ->
            if (isCatchAll(segment)) {
                if (index != segments.lastIndex) return Converted(null, "catch-all is not the last segment")
                catchAll = true
                return@forEachIndexed
            }
            forms += convertSegment(segment, index, last = index == segments.lastIndex)
                ?: return Converted(null, "segment shape has no template form")
        }
        val optional = forms.count { it.empty != null }
        if (optional >= Int.SIZE_BITS - 1 || (1 shl optional) > MAX_VARIANTS) {
            return Converted(null, "empty-value variants exceed $MAX_VARIANTS templates", expansionCapped = true)
        }
        val variants = (0 until (1 shl optional)).map { mask -> variant(forms, mask, catchAll) }
        if (variants.any { it.template == null }) return variants.first { it.template == null }
        return variants.first().copy(emptyValueVariants = variants.drop(1).distinctBy { it.template })
    }

    /**
     * 빈 값 자리 집합 [mask]를 빈 값으로 채운 변형 하나다. 비트는 빈 값을 받는 자리의 순서다(0이면 원본 모양).
     */
    private fun variant(forms: List<SegmentForm>, mask: Int, catchAll: Boolean): Converted {
        var bit = 0
        val texts = mutableListOf<String>()
        val constraints = mutableListOf<RouteParamConstraint>()
        forms.forEach { form ->
            val filled = form.empty != null && (mask shr bit) and 1 == 1
            if (form.empty != null) bit++
            texts += if (filled) form.empty!! else form.text
            if (!filled) form.constraint?.let(constraints::add)
        }
        if (!catchAll) return finish("/" + texts.joinToString("/"), constraints)
        val prefix = "/" + texts.joinToString("/")
        val template = if (texts.isEmpty()) "/{**}" else "$prefix/{**}"
        return finish(template, constraints).copy(catchAllPrefix = prefix)
    }

    private fun isCatchAll(segment: String): Boolean =
        segment == "**" || (segment.startsWith("{*") && segment.endsWith('}') && segment.indexOf('}') == segment.lastIndex)

    private fun finish(template: String, constraints: List<RouteParamConstraint>): Converted {
        val reason = RouteUrlRules.validateTemplate(template) ?: return Converted(template, constraints = constraints)
        return Converted(null, "template is not canonical ($reason)")
    }

    /**
     * 세그먼트 하나의 템플릿 모양이다.
     *
     * @property text 원본 템플릿 세그먼트
     * @property constraint 이 세그먼트의 정규식 제약
     * @property empty 빈 값을 받는 자리를 빈 값으로 채운 세그먼트다. 빈 값을 받지 않으면 null이다
     */
    private data class SegmentForm(val text: String, val constraint: RouteParamConstraint?, val empty: String?)

    /**
     * 세그먼트 하나를 바꾼다. 세그먼트 전체 `*`는 마지막 요소일 때만, 부분 세그먼트의 `*`·변수는 정규식이 빈 값을 받을 때
     * 빈 값 변형을 둔다. 세그먼트 전체 변수는 빈 값을 받지 않는다(`CaptureVariablePathElement`).
     *
     * @return 세그먼트 모양. 옮길 수 없으면 null이다
     */
    private fun convertSegment(segment: String, index: Int, last: Boolean): SegmentForm? {
        if (segment == "*") return SegmentForm("{}", null, "".takeIf { last })
        val pieces = splitVariables(segment) ?: return null
        val parameters = pieces.filterNot { it is Piece.Literal }
        if (parameters.isEmpty()) return SegmentForm(RouteUrlRules.normalizePath(segment), null, null)
        val parameter = parameters.singleOrNull() ?: return null
        if (parameter is Piece.Question) return null
        val literal = { piece: Piece -> if (piece is Piece.Literal) RouteUrlRules.normalizePath(piece.text) else null }
        val skeleton = pieces.joinToString("") { literal(it) ?: "{}" }
        val filled = pieces.joinToString("") { literal(it).orEmpty() }
        val regex = (parameter as? Piece.Variable)?.regex
        val acceptsEmpty = pieces.size > 1 && (regex == null || matchesEmpty(regex))
        return SegmentForm(skeleton, regex?.let { constraint(index, it) }, filled.takeIf { acceptsEmpty })
    }

    /**
     * 경로 변수 정규식이 빈 값을 받을 수 있는지다. Spring `RegexPathElement`도 `java.util.regex`로 컴파일한다. 컴파일하지
     * 못하는 정규식은 받을 수 있다고 본다 — 변형을 하나 더 내는 쪽은 거짓 error를 만들지 않는다.
     */
    private fun matchesEmpty(regex: String): Boolean = try {
        Regex(regex).matches("")
    } catch (_: java.util.regex.PatternSyntaxException) {
        true
    }

    /** 세그먼트 조각이다. */
    private sealed interface Piece {
        data class Literal(val text: String) : Piece
        data class Variable(val regex: String?) : Piece
        data object Star : Piece
        data object Question : Piece
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
                '*' -> { flushLiteral(literal, pieces); pieces += Piece.Star }
                '?' -> { flushLiteral(literal, pieces); pieces += Piece.Question }
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
