package dev.kartograph.index

/**
 * 어노테이션 인자 식을 [SpringValue]로 계산한다.
 *
 * 어노테이션 값은 컴파일 시간 상수여야 하므로 모양이 좁다 — 문자열 리터럴, `+` 연결, Kotlin 템플릿, 상수 참조,
 * 배열(`{…}`·`[…]`·`arrayOf(…)`), enum 상수. 이 밖의 식은 추측하지 않고 [SpringValue.Unresolved]다.
 */
internal class SpringSourceValues(private val reader: SpringSourceReader) {
    /**
     * [expression]을 문자열 배열로, 안 되면 enum 배열로 읽는다. 둘 다 아니면 풀지 못한 값이다. 원소들은 [ExpansionBudget] 하나를
     * 함께 쓰고, 예산을 넘으면 풀지 못한 값이다 — 서로를 여러 번 잇는 상수가 펼침을 fan-out^깊이 번으로 늘리지 못하게 한다.
     */
    fun value(expression: String, file: RouteSourceFile, offset: Int): SpringValue {
        val elements = SpringSourceSyntax.arrayElements(expression).map { it.trim().removePrefix("*").trim() }
        val budget = ExpansionBudget()
        val strings = elements.map { string(it, file, offset, 0, budget) }
        if (budget.exhausted) return SpringValue.Unresolved
        if (strings.all { it != null }) return SpringValue.Strings(strings.map { it!! })
        val enums = elements.map { ENUM_CONSTANT.matchEntire(it)?.groupValues?.get(1) }
        if (enums.all { it != null } && strings.all { it == null }) return SpringValue.Enums(enums.map { it!! })
        return SpringValue.Unresolved
    }

    /** 문자열 상수 식 하나를 계산한다. 식 하나가 펼침 한 번이고, 리터럴 본문과 이어 붙인 결과는 [budget]의 문자 수로 센다. */
    fun string(expression: String, file: RouteSourceFile, offset: Int, depth: Int, budget: ExpansionBudget): String? {
        if (depth > MAX_DEPTH || !budget.step()) return null
        val text = unwrapParentheses(expression.trim())
        if (text.isEmpty()) return null
        val terms = splitConcatenation(text)
        if (terms.size > 1) return terms.map { counted(string(it, file, offset, depth + 1, budget), budget) ?: return null }.joinToString("")
        stringLiteralBody(text)?.let { (body, raw) -> return if (budget.produce(body.length)) literal(body, raw, file, offset, depth, budget) else null }
        if (!REFERENCE.matches(text)) return null
        val definition = reader.resolveConstant(text.replace(WHITESPACE, ""), file, offset) ?: return null
        return string(definition.expression, definition.file, definition.offset, depth + 1, budget)
    }

    /** 다른 결과에 이어 붙일 [value]의 길이를 [budget]에서 쓴다. 예산을 넘거나 값이 없으면 null이다. */
    private fun counted(value: String?, budget: ExpansionBudget): String? = value?.takeIf { budget.produce(it.length) }

    private fun literal(body: String, raw: Boolean, file: RouteSourceFile, offset: Int, depth: Int, budget: ExpansionBudget): String? = when {
        file.isJava -> decodeLiteral(body)
        else -> kotlinTemplate(body, raw, file, offset, depth, budget)
    }

    /** Kotlin 템플릿 본문을 계산한다. `$NAME`·`${식}`은 상수로 풀려야 한다. */
    private fun kotlinTemplate(body: String, raw: Boolean, file: RouteSourceFile, offset: Int, depth: Int, budget: ExpansionBudget): String? {
        val result = StringBuilder()
        var index = 0
        while (index < body.length) {
            val character = body[index]
            when {
                body.isDollarLiteral(index) -> { result.append('$'); index += 6 }
                !raw && character == '\\' -> {
                    val width = if (body.getOrNull(index + 1) == 'u') 6 else 2
                    result.append(decodeLiteral(body.substring(index, (index + width).coerceAtMost(body.length))))
                    index += width
                }
                character == '$' && body.getOrNull(index + 1) == '{' -> {
                    val close = templateExpressionEnd(body, index + 1)
                    result.append(counted(string(body.substring(index + 2, close), file, offset, depth + 1, budget), budget) ?: return null)
                    index = close + 1
                }
                character == '$' && body.getOrNull(index + 1)?.let { it == '_' || it.isLetter() } == true -> {
                    val name = IDENTIFIER.find(body, index + 1)!!.value
                    result.append(counted(string(name, file, offset, depth + 1, budget), budget) ?: return null)
                    index += 1 + name.length
                }
                else -> { result.append(character); index++ }
            }
        }
        return result.toString()
    }

    private companion object {
        const val MAX_DEPTH = 16
        val WHITESPACE = Regex("\\s+")
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*")
        val IDENTIFIER = Regex("\\G[A-Za-z_]\\w*")
        val ENUM_CONSTANT = Regex("(?:[A-Za-z_]\\w*\\.)*([A-Z][A-Z0-9_]*)")
    }
}
