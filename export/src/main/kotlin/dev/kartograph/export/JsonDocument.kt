package dev.kartograph.export

/**
 * export가 만드는 machine 문서가 공유하는 최소 JSON 직렬화다.
 * 키 순서는 호출부가 넘긴 정렬된 map이 정하므로 모든 문서가 결정적으로 렌더링된다.
 */
internal fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${escapeJson(value)}\""
    is Boolean, is Number -> value.toString()
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ", ") { (key, item) ->
        "\"${escapeJson(key.toString())}\": ${jsonValue(item)}"
    }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ", ") { jsonValue(it) }
    else -> error("unsupported JSON value: ${value::class.simpleName}")
}

/** 제어문자까지 escape해 어떤 식별자나 경로도 JSON 문자열을 깨뜨리지 않게 한다. */
internal fun escapeJson(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

/** SCREAMING_SNAKE enum 이름을 machine 문서가 공통으로 쓰는 lowerCamel 값으로 바꾼다. */
internal fun String.lowerCamel(): String = lowercase().split('_').let { words ->
    words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
}
