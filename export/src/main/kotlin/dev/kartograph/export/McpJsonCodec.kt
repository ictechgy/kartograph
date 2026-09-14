package dev.kartograph.export

import java.math.BigDecimal
import java.math.BigInteger

/** MCP envelope는 snapshot의 JSON 문법 구현을 재사용하되 일반 숫자와 정확한 정수 ID를 허용한다. */
public object McpJsonCodec {
    /** 중복 키와 과도한 중첩을 거부하며 JVM 객체를 생성하지 않는다. */
    public fun parse(text: String): Any? = SnapshotJsonParser(text, generalNumbers = true).parse()

    /** 제한된 JSON 값만 내보내며, 크기 상한을 넘으면 불완전한 JSON 대신 실패한다. */
    public fun render(value: Any?, maximum: Int = 1024 * 1024): String {
        var remaining = maximum.toLong()
        fun budget(size: Long) { remaining -= size; require(remaining >= 0) { "JSON response exceeds limit" } }
        fun check(item: Any?, depth: Int) {
            require(depth <= 40) { "JSON response is too deep" }
            when (item) {
                null -> budget(4)
                is String -> {
                    budget(2)
                    item.forEach { budget(when { it == '"' || it == '\\' || it in "\b\u000C\n\r\t" -> 2L; it.code in 0x20..0x7e -> 1L; else -> 6L }) }
                }
                is Boolean -> budget(if (item) 4 else 5)
                is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> budget(item.toString().length.toLong())
                is Double -> { require(item.isFinite()); budget(item.toString().length.toLong()) }
                is Float -> { require(item.isFinite()); budget(item.toString().length.toLong()) }
                is Map<*, *> -> {
                    budget(2)
                    item.entries.forEachIndexed { i, (key, child) ->
                        require(key is String)
                        if (i > 0) budget(2)
                        check(key, depth + 1); budget(2); check(child, depth + 1)
                    }
                }
                is List<*> -> {
                    budget(2)
                    item.forEachIndexed { i, child -> if (i > 0) budget(2); check(child, depth + 1) }
                }
                else -> throw IllegalArgumentException("unsupported JSON value")
            }
        }
        check(value, 0)
        return jsonValue(value)
    }
}
