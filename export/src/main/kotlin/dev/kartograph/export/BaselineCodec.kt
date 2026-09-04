package dev.kartograph.export

import dev.kartograph.core.Finding

/** 기존 finding 지문을 결정적인 버전 문서로 교환한다. */
public object BaselineCodec {
    /** 위치의 줄 변화에 흔들리지 않는 지문을 정렬·중복 제거해 baseline JSON으로 만든다. */
    public fun render(findings: Collection<Finding>): String {
        val values = findings.map(Finding::fingerprint).distinct().sorted()
        return buildString {
            append("{\n  \"fingerprints\": [")
            if (values.isEmpty()) {
                append("]")
            } else {
                append('\n')
                values.forEachIndexed { index, value ->
                    append("    \"").append(jsonEscape(value)).append('"')
                    if (index != values.lastIndex) append(',')
                    append('\n')
                }
                append("  ]")
            }
            append(",\n  \"generatedBy\": \"kartograph\",\n  \"version\": 1\n}\n")
        }
    }

    /** 지원 버전과 필드 타입을 검증한 뒤 억제할 지문 집합을 반환한다. */
    public fun parse(content: String): Set<String> {
        val document = BaselineJsonParser(content).parse()
        require(document.version == 1) {
            "baseline format version is not supported; regenerate it with `kartograph baseline --write`"
        }
        require(document.generatedBy == "kartograph") { "baseline generator is not supported" }
        return document.fingerprints.toSet()
    }
}

internal fun jsonEscape(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

private data class BaselineDocument(
    val fingerprints: List<String>,
    val generatedBy: String,
    val version: Int,
)

private class BaselineJsonParser(private val content: String) {
    private var index: Int = 0

    fun parse(): BaselineDocument {
        var fingerprints: List<String>? = null
        var generatedBy: String? = null
        var version: Int? = null
        val keys = mutableSetOf<String>()
        expect('{')
        while (true) {
            skipWhitespace()
            if (consume('}')) break
            val key = parseString()
            require(keys.add(key)) { "baseline contains a duplicate field" }
            expect(':')
            when (key) {
                "fingerprints" -> fingerprints = parseStringArray()
                "generatedBy" -> generatedBy = parseString()
                "version" -> version = parseInteger()
                else -> throw IllegalArgumentException("baseline contains an unsupported field")
            }
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        skipWhitespace()
        require(index == content.length) { "baseline contains trailing content" }
        return BaselineDocument(
            fingerprints ?: throw IllegalArgumentException("baseline fingerprints are missing"),
            generatedBy ?: throw IllegalArgumentException("baseline generator is missing"),
            version ?: throw IllegalArgumentException("baseline format version is missing"),
        )
    }

    private fun parseStringArray(): List<String> {
        val values = mutableListOf<String>()
        expect('[')
        while (true) {
            skipWhitespace()
            if (consume(']')) return values
            values += parseString()
            skipWhitespace()
            if (consume(']')) return values
            expect(',')
        }
    }

    private fun parseInteger(): Int {
        skipWhitespace()
        val start = index
        while (index < content.length && content[index].isDigit()) index++
        require(index > start) { "baseline version must be an integer" }
        return content.substring(start, index).toIntOrNull()
            ?: throw IllegalArgumentException("baseline version must be an integer")
    }

    private fun parseString(): String {
        skipWhitespace()
        require(index < content.length && content[index++] == '"') { "baseline value must be a string" }
        return buildString {
            while (index < content.length) {
                val character = content[index++]
                when {
                    character == '"' -> return@buildString
                    character == '\\' -> append(parseEscape())
                    character.code < 0x20 -> throw IllegalArgumentException("baseline string contains a control character")
                    else -> append(character)
                }
            }
            throw IllegalArgumentException("baseline string is not terminated")
        }
    }

    private fun parseEscape(): Char {
        require(index < content.length) { "invalid baseline string escape" }
        return when (val escaped = content[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> throw IllegalArgumentException("invalid baseline string escape")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + 4 <= content.length) { "invalid baseline unicode escape" }
        var value = 0
        repeat(4) {
            val digit = content[index++].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("invalid baseline unicode escape")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun expect(character: Char) {
        skipWhitespace()
        require(index < content.length && content[index++] == character) { "baseline JSON is malformed" }
    }

    private fun consume(character: Char): Boolean {
        if (index >= content.length || content[index] != character) return false
        index++
        return true
    }

    private fun skipWhitespace() {
        while (index < content.length && content[index] in JSON_WHITESPACE) index++
    }

    private companion object {
        val JSON_WHITESPACE = setOf(' ', '\n', '\r', '\t')
    }
}
