package dev.kartograph.index

/** callback의 선두 문장을 소유한 완결된 if/else-if만 읽고, 알 수 없는 문장 뒤는 추측하지 않는다. */
internal fun scanMethodChannelConditions(source: String, masked: String, opening: Int): MethodConditions {
    val closing = methodBodyEnd(masked, opening)
    if (closing < 0) return MethodConditions(emptyList(), 1)
    // if가 없는 기존 when-only callback에는 새 if 경로의 한계를 덧붙이지 않는다.
    if (METHOD_IF.find(masked, opening + 1)?.range?.first?.let { it < closing } != true) {
        return MethodConditions(emptyList(), 0)
    }
    val header = METHOD_PARAMETERS.matchAt(source, opening + 1)
        ?.takeIf { it.range.last < closing }
        ?: return MethodConditions(emptyList(), 1)
    val receiver = header.groupValues[1]
    val methods = mutableListOf<MethodCondition>()
    var index = source.indexOfFirstNonWhitespace(header.range.last + 1)
    while (index < closing) {
        // 새 줄은 Kotlin 문장 경계의 증명이 아니다. 다른 문장을 건너뛰어 내부 if를 승격하지 않는다.
        if (METHOD_IF.matchAt(masked, index) == null) return MethodConditions(methods, 1)
        val seen = mutableSetOf<String>()
        while (true) {
            val open = masked.indexOf('(', index)
            val end = balancedEnd(source, open, closing)
            if (end < 0) return MethodConditions(methods, 1)
            val body = source.indexOfFirstNonWhitespace(end + 1)
            if (masked.getOrNull(body) != '{') return MethodConditions(methods, 1)
            val bodyEnd = methodBodyEnd(masked, body)
            if (bodyEnd < 0 || bodyEnd > closing) return MethodConditions(methods, 1)
            val literal = methodLiteralEquality(source.substring(open + 1, end), receiver)
                ?: return MethodConditions(methods, 1)
            if (seen.add(literal.first)) methods += MethodCondition(literal.first, open + 1 + literal.second)
            index = source.indexOfFirstNonWhitespace(bodyEnd + 1)
            val alternative = METHOD_ELSE.matchAt(masked, index) ?: break
            val next = source.indexOfFirstNonWhitespace(alternative.range.last + 1)
            if (METHOD_IF.matchAt(masked, next) != null) {
                index = next
            } else {
                if (masked.getOrNull(next) != '{') return MethodConditions(methods, 1)
                val elseEnd = methodBodyEnd(masked, next)
                if (elseEnd < 0 || elseEnd > closing) return MethodConditions(methods, 1)
                index = source.indexOfFirstNonWhitespace(elseEnd + 1)
                break
            }
        }
    }
    return MethodConditions(methods, 0)
}

private fun methodLiteralEquality(expression: String, receiver: String): Pair<String, Int>? {
    val match = METHOD_EQUALITY.matchEntire(expression) ?: return null
    val name = match.groups[1]?.value ?: match.groupValues[4]
    if (name != receiver) return null
    val literal = match.groups[2] ?: requireNotNull(match.groups[3])
    val (value, dynamic) = literalOrDynamicChannel(literal.value)
    if (dynamic || value.isNullOrEmpty()) return null
    return value to literal.range.first + 1
}

private fun methodBodyEnd(masked: String, opening: Int): Int {
    var depth = 0
    for (index in opening until masked.length) {
        when (masked[index]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return index
        }
    }
    return -1
}

internal fun String.indexOfFirstNonWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index++
    return index
}

internal data class MethodConditions(val methods: List<MethodCondition>, val unsupported: Int)
internal data class MethodCondition(val name: String, val offset: Int)

private val METHOD_PARAMETERS = Regex("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*\\s*->")
private val METHOD_IF = Regex("(?<![\\w.])if\\s*\\(")
private val METHOD_ELSE = Regex("\\belse\\b")
private const val METHOD_LITERAL = "(\"(?:\\\\.|[^\"\\\\])*\")"
private const val METHOD_RECEIVER = "([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*method"
private val METHOD_EQUALITY = Regex("\\s*(?:$METHOD_RECEIVER\\s*==\\s*$METHOD_LITERAL|$METHOD_LITERAL\\s*==\\s*$METHOD_RECEIVER)\\s*")
