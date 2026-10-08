package dev.kartograph.export

/**
 * export가 만드는 machine 문서가 공유하는 최소 JSON 직렬화다.
 * 키 순서는 호출부가 넘긴 정렬된 map이 정하므로 모든 문서가 결정적으로 렌더링된다.
 */
internal fun jsonValue(value: Any?): String = buildString { appendJsonValue(value) }

/** 선택한 UTF-8 상한을 넘기기 전에 JSON을 멈추며, suffix도 같은 상한으로 기록한다. */
internal fun boundedJsonValue(value: Any?, maximumBytes: Int, suffix: CharSequence = ""): String {
    require(maximumBytes > 0) { "JSON byte maximum must be positive" }
    return BoundedJsonAppendable(maximumBytes).apply {
        appendJsonValue(value)
        append(suffix)
    }.toString()
}

/** 하위 문서마다 임시 문자열을 만들지 않고 같은 버퍼에 순서대로 기록한다. */
internal fun Appendable.appendJsonValue(value: Any?) {
    when (value) {
        null -> append("null")
        is String -> { append('"'); appendEscapedJson(value); append('"') }
        is Boolean, is Number -> append(value.toString())
        is Map<*, *> -> {
            append('{')
            var separator = ""
            val iterator = value.entries.iterator()
            while (true) {
                (this as? JsonByteBudget)?.ensureJsonSpace(1)
                if (!iterator.hasNext()) break
                (this as? JsonByteBudget)?.ensureJsonSpace(if (separator.isEmpty()) 6 else 8)
                val (key, item) = iterator.next()
                append(separator)
                append('"'); appendEscapedJson(key.toString()); append("\": ")
                appendJsonValue(item)
                separator = ", "
            }
            append('}')
        }
        is Iterable<*> -> {
            append('[')
            var separator = ""
            val iterator = value.iterator()
            while (true) {
                (this as? JsonByteBudget)?.ensureJsonSpace(1)
                if (!iterator.hasNext()) break
                (this as? JsonByteBudget)?.ensureJsonSpace(if (separator.isEmpty()) 2 else 4)
                val item = iterator.next()
                append(separator)
                appendJsonValue(item)
                separator = ", "
            }
            append(']')
        }
        else -> error("unsupported JSON value: ${value::class.simpleName}")
    }
}

/**
 * 제어문자와 비ASCII를 모두 escape해 어떤 식별자나 경로도 JSON 문자열을 깨뜨리지 않게 한다.
 *
 * 비ASCII까지 `\uXXXX`로 쓰는 이유는 stdout charset과 무관하게 같은 바이트가 나오게 하기 위해서다.
 * 비UTF-8 로케일에서 한글 같은 식별자가 `?`로 뭉개지면 서로 다른 선언이 같은 `usr`로 붕괴해
 * 교환 문서의 join key가 조용히 충돌한다.
 */
private fun Appendable.appendEscapedJson(value: String) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code in 0x20..0x7E) append(character) else {
                append("\\u")
                for (shift in 12 downTo 0 step 4) append(HEX_DIGITS[(character.code shr shift) and 0xf])
            }
        }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/** 배열·객체의 다음 원소를 꺼내기 전에 닫힘과 최소 JSON 토큰의 공간을 확인한다. */
private interface JsonByteBudget {
    fun ensureJsonSpace(bytes: Int)
}

/** JSON이 실제로 추가한 UTF-8 바이트를 세며, 초과 append는 버퍼에 반영하지 않는다. */
private class BoundedJsonAppendable(private val maximumBytes: Int) : Appendable, JsonByteBudget {
    private val output = StringBuilder()
    private var bytes = 0L
    private var pendingHighSurrogate = false

    override fun append(csq: CharSequence?): Appendable {
        val value = csq ?: "null"
        return append(value, 0, value.length)
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
        val value = csq ?: "null"
        require(start >= 0 && start <= end && end <= value.length) { "invalid append range" }
        var pendingHigh = pendingHighSurrogate
        var additional = 0L
        for (index in start until end) {
            val character = value[index]
            if (pendingHigh) {
                if (Character.isLowSurrogate(character)) {
                    additional += 3
                    pendingHigh = false
                    continue
                }
                pendingHigh = false
            }
            when {
                Character.isHighSurrogate(character) -> {
                    additional++
                    pendingHigh = true
                }
                Character.isSurrogate(character) -> additional++
                character.code <= 0x7f -> additional++
                character.code <= 0x7ff -> additional += 2
                else -> additional += 3
            }
        }
        if (bytes + additional > maximumBytes.toLong()) throw QuerySnapshotSizeException()
        output.append(value, start, end)
        bytes += additional
        pendingHighSurrogate = pendingHigh
        return this
    }

    override fun append(c: Char): Appendable {
        var pendingHigh = pendingHighSurrogate
        val additional = if (pendingHigh && Character.isLowSurrogate(c)) {
            pendingHigh = false
            3
        } else {
            pendingHigh = false
            when {
                Character.isHighSurrogate(c) -> {
                    pendingHigh = true
                    1
                }
                Character.isSurrogate(c) -> 1
                c.code <= 0x7f -> 1
                c.code <= 0x7ff -> 2
                else -> 3
            }
        }
        if (bytes + additional.toLong() > maximumBytes.toLong()) throw QuerySnapshotSizeException()
        output.append(c)
        bytes += additional
        pendingHighSurrogate = pendingHigh
        return this
    }

    override fun ensureJsonSpace(bytes: Int) {
        require(bytes >= 0) { "JSON byte budget must be non-negative" }
        if (this.bytes + bytes.toLong() > maximumBytes.toLong()) throw QuerySnapshotSizeException()
    }

    override fun toString(): String = output.toString()
}

/** SCREAMING_SNAKE enum 이름을 machine 문서가 공통으로 쓰는 lowerCamel 값으로 바꾼다. */
internal fun String.lowerCamel(): String = lowercase().split('_').let { words ->
    words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
}
