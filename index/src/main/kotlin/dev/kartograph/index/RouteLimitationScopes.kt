package dev.kartograph.index

import dev.kartograph.core.RouteLimitationScope

/**
 * isthmus http limitation 스코프의 생산자 쪽 규칙이다(GRAPH-EXCHANGE "http limitation 스코프", `http-limitation-scope` 벡터).
 *
 * - [problem]: 스코프 항목이 계약 모양인지 검증한다. 생산자는 잘못된 스코프를 내지 않는다 — isthmus가 문서 전체를 입력
 *   오류로 거부하기 때문이다. 해석한 설정값으로 만든 원소가 정규 문법을 어기면 호출자는 그 스코프를 생략한다(문서 전체 효과).
 * - [applies]: 스코프가 호출·선언 하나에 적용되는지(두 경로 집합이 겹칠 수 있는지)를 isthmus와 같은 보수적 근사로 계산한다.
 *   생산자 테스트가 방출한 스코프의 효과를 확인하는 데 쓴다.
 */
public object RouteLimitationScopes {
    private val PATH_FIELDS = listOf("templates", "templatePrefixes", "templateSuffixes")
    private val ALLOWED_KEYS = setOf("limitationIndex") + PATH_FIELDS + "methods"

    /** 타입이 있는 스코프를 계약 JSON 모양으로 바꿔 검증한다. 빈 목록은 생략한 필드로 본다. */
    public fun problem(scope: RouteLimitationScope): String? = problem(buildMap {
        scope.templates.takeIf { it.isNotEmpty() }?.let { put("templates", it) }
        scope.templatePrefixes.takeIf { it.isNotEmpty() }?.let { put("templatePrefixes", it) }
        scope.templateSuffixes.takeIf { it.isNotEmpty() }?.let { put("templateSuffixes", it) }
        scope.methods.takeIf { it.isNotEmpty() }?.let { put("methods", it) }
    })

    /**
     * 스코프 항목 하나(`limitationIndex` 제외 가능)를 검증한다.
     *
     * @return 위반 사유 문구(입력 원문을 담지 않는다). 통과하면 null이다
     */
    public fun problem(entry: Map<String, Any?>): String? {
        if ("channels" in entry) return "http limitation scopes use templates, templatePrefixes or templateSuffixes instead of channels"
        if (entry.keys.any { it !in ALLOWED_KEYS }) return "http limitation scopes accept only limitationIndex, path fields and methods"
        if (PATH_FIELDS.none { it in entry }) return "http limitation scopes require templates, templatePrefixes or templateSuffixes"
        PATH_FIELDS.forEach { field -> pathFieldProblem(field, entry[field])?.let { return it } }
        return methodsProblem(entry["methods"])
    }

    private fun pathFieldProblem(field: String, value: Any?): String? {
        if (value == null) return null
        val elements = value as? List<*> ?: return "$field must be a non-empty array"
        if (elements.isEmpty()) return "$field must be a non-empty array"
        elements.forEach { element ->
            val template = element as? String ?: return "$field must contain path template strings"
            RouteUrlRules.validateTemplate(template)?.let { reason -> return "$field contains a non-canonical path template ($reason)" }
            if (field == "templates") return@forEach
            if (template.split('/').any { it == "{**}" }) return "$field must not contain {**}"
            if (field == "templatePrefixes" && template != "/" && template.endsWith('/')) return "templatePrefixes must not end with / except the root prefix"
            if (field == "templateSuffixes" && template == "/") return "templateSuffixes must not be /; use templatePrefixes [\"/\"]"
        }
        return null
    }

    private fun methodsProblem(value: Any?): String? {
        if (value == null) return null
        val methods = value as? List<*>
        val valid = methods != null && methods.isNotEmpty() && methods.all { it is String && it in RouteUrlRules.VERBS } &&
            methods.toSet().size == methods.size
        return if (valid) null else "methods must be a non-empty array of distinct HTTP methods (ANY is not allowed)"
    }

    /**
     * 스코프가 사실 하나에 적용되는지다. 겹칠 수 있으면 참이다 — 겹친다고 잘못 보면 error 하나가 `-unverified`로 내려갈
     * 뿐이지만, 반대면 거짓 error가 되므로 넓게 근사한다.
     *
     * @param template 사실의 정규 템플릿
     * @param method 호출 동사(동적이면 null) 또는 선언 method(`ANY` 가능)
     * @param anchor `root` 또는 `base`. base는 알 수 없는 앞부분(0개 이상 세그먼트) 뒤의 템플릿이다
     * @param declaration 사실이 선언(decl·contract)이면 참, 호출이면 거짓이다. 측에 따라 `methods` 해석이 다르다
     */
    public fun applies(scope: RouteLimitationScope, template: String, method: String?, anchor: String, declaration: Boolean = false): Boolean {
        if (!methodApplies(scope.methods.toSet(), method, declaration)) return false
        val trimmed = trimTrailingEmpty(tokens(template))
        val lead = if (anchor == "base") listOf(Token.Star) else emptyList()
        val base = lead + trimmed
        val variants = if (base.lastOrNull() == Token.Star) listOf(base) else listOf(base, base + Token.Literal(""))
        val elements = scope.templates.map { trimTrailingEmpty(tokens(it)) } +
            scope.templatePrefixes.map { trimTrailingEmpty(tokens(it)) + Token.Star } +
            scope.templateSuffixes.map { listOf(Token.Star) + trimTrailingEmpty(tokens(it)) }
        return elements.any { element -> variants.any { overlaps(element, it) } }
    }

    /**
     * method 조건이다. 호출: 동적 동사는 항상, HEAD는 GET으로도, OPTIONS는 경로만 맞으면 적용된다. 선언: `ANY`는 항상, GET
     * 선언은 HEAD 호출로도, 모든 선언은 OPTIONS 호출로도 닿을 수 있다(조인의 head-as-get·options-any와 같은 방향).
     */
    private fun methodApplies(methods: Set<String>, method: String?, declaration: Boolean): Boolean {
        if (methods.isEmpty() || method == null || method == "ANY" || method in methods) return true
        return if (declaration) "OPTIONS" in methods || (method == "GET" && "HEAD" in methods)
        else method == "OPTIONS" || (method == "HEAD" && "GET" in methods)
    }

    /** 비교 토큰이다. [Star]는 0개 이상 세그먼트, [AnyValue]는 빈 값을 포함한 세그먼트 하나다. */
    private sealed interface Token {
        data class Literal(val value: String) : Token
        data class Partial(val prefix: String, val suffix: String) : Token
        data object AnyValue : Token
        data object Star : Token
    }

    /** 템플릿을 토큰으로 바꾼다. 리터럴은 ASCII 소문자로 접는다(정규 템플릿은 ASCII와 `%XX`만 담는다). */
    private fun tokens(template: String): List<Token> = template.substring(1).split('/').map { segment ->
        when {
            segment == "{**}" -> Token.Star
            segment == "{}" -> Token.AnyValue
            "{}" in segment -> Token.Partial(fold(segment.substringBefore("{}")), fold(segment.substringAfter("{}")))
            else -> Token.Literal(fold(segment))
        }
    }

    private fun fold(value: String): String = value.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")

    /** 끝의 빈 리터럴 세그먼트 하나를 뗀다(끝 슬래시 무관 비교). */
    private fun trimTrailingEmpty(tokens: List<Token>): List<Token> =
        if (tokens.lastOrNull() == Token.Literal("")) tokens.dropLast(1) else tokens

    /**
     * 두 토큰열이 같은 구체 경로를 하나라도 가질 수 있는지다. `Star`는 양쪽 어디에나 올 수 있다. 뒤에서부터 채우는 표로
     * 계산해 재귀 깊이를 만들지 않는다(칸 수는 두 길이의 곱 이하).
     */
    private fun overlaps(left: List<Token>, right: List<Token>): Boolean {
        val table = Array(left.size + 1) { BooleanArray(right.size + 1) }
        for (i in left.size downTo 0) {
            for (j in right.size downTo 0) {
                table[i][j] = when {
                    i < left.size && left[i] == Token.Star -> table[i + 1][j] || (j < right.size && table[i][j + 1])
                    j < right.size && right[j] == Token.Star -> table[i][j + 1] || (i < left.size && table[i + 1][j])
                    i == left.size || j == right.size -> i == left.size && j == right.size
                    else -> segmentsOverlap(left[i], right[j]) && table[i + 1][j + 1]
                }
            }
        }
        return table[0][0]
    }

    /** 세그먼트 토큰 둘이 같은 값을 가질 수 있는지다. 부분 세그먼트끼리는 항상 겹친다고 본다. */
    private fun segmentsOverlap(left: Token, right: Token): Boolean = when {
        left == Token.AnyValue || right == Token.AnyValue -> true
        left is Token.Literal && right is Token.Literal -> left.value == right.value
        left is Token.Literal && right is Token.Partial -> accepts(right, left.value)
        left is Token.Partial && right is Token.Literal -> accepts(left, right.value)
        else -> true
    }

    /** 부분 세그먼트가 리터럴 값을 받을 수 있는지다. 가운데 빈 값도 받는다고 본다. */
    private fun accepts(partial: Token.Partial, value: String): Boolean =
        value.length >= partial.prefix.length + partial.suffix.length && value.startsWith(partial.prefix) && value.endsWith(partial.suffix)
}
