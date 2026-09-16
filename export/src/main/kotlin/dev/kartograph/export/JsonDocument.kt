package dev.kartograph.export

/**
 * export가 만드는 machine 문서가 공유하는 최소 JSON 직렬화다.
 * 키 순서는 호출부가 넘긴 정렬된 map이 정하므로 모든 문서가 결정적으로 렌더링된다.
 */
internal fun jsonValue(value: Any?): String = buildString { appendJsonValue(value) }

/** 하위 문서마다 임시 문자열을 만들지 않고 같은 버퍼에 순서대로 기록한다. */
private fun StringBuilder.appendJsonValue(value: Any?) {
    when (value) {
        null -> append("null")
        is String -> { append('"'); appendEscapedJson(value); append('"') }
        is Boolean, is Number -> append(value.toString())
        is Map<*, *> -> {
            append('{')
            var separator = ""
            for ((key, item) in value) {
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
            for (item in value) {
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
private fun StringBuilder.appendEscapedJson(value: String) {
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

/** SCREAMING_SNAKE enum 이름을 machine 문서가 공통으로 쓰는 lowerCamel 값으로 바꾼다. */
internal fun String.lowerCamel(): String = lowercase().split('_').let { words ->
    words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
}
