package dev.kartograph.export

import java.time.LocalDate

/**
 * 만료가 있는 finding 억제 항목이다. 지문은 baseline과 같은 `Finding.fingerprint`를 쓰고,
 * `expires` 달력날짜까지 억제된다. 무기한 억제는 baseline의 역할이다.
 */
public data class SuppressionEntry(
    val fingerprint: String,
    val reason: String,
    val expires: LocalDate,
)

/** 기한이 있는 finding 억제 파일을 검증해 결정적인 JSON 문서로 교환한다. */
public object SuppressCodec {
    /** 항목을 지문 순으로 정렬해 같은 입력이면 같은 바이트를 만든다. */
    public fun render(entries: Collection<SuppressionEntry>): String {
        val sorted = entries.sortedBy { entry -> entry.fingerprint }
        return buildString {
            append("{\n  \"suppressions\": [")
            if (sorted.isEmpty()) {
                append("]")
            } else {
                append('\n')
                sorted.forEachIndexed { index, entry ->
                    append("    {\n      \"expires\": \"").append(entry.expires.toString())
                    append("\",\n      \"fingerprint\": \"").append(jsonEscape(entry.fingerprint))
                    append("\",\n      \"reason\": \"").append(jsonEscape(entry.reason))
                    append("\"\n    }")
                    if (index != sorted.lastIndex) append(',')
                    append('\n')
                }
                append("  ]")
            }
            append(",\n  \"version\": 1\n}\n")
        }
    }

    /** 지원 버전·필드·날짜 형식을 검증한다. 모르는 내용은 조용히 버리지 않고 실패한다. */
    public fun parse(content: String): List<SuppressionEntry> {
        val document = SuppressJsonParser(content).parse()
        require(document.version == 1) {
            "suppress format version is not supported; use version 1"
        }
        return document.entries
    }
}

private data class SuppressDocument(val entries: List<SuppressionEntry>, val version: Int)

private class SuppressJsonParser(private val content: String) {
    private var index: Int = 0

    fun parse(): SuppressDocument {
        var entries: List<SuppressionEntry>? = null
        var version: Int? = null
        val keys = mutableSetOf<String>()
        expect('{')
        while (true) {
            skipWhitespace()
            if (consume('}')) break
            val key = parseString()
            require(keys.add(key)) { "suppress file contains a duplicate field" }
            expect(':')
            when (key) {
                "suppressions" -> entries = parseEntries()
                "version" -> version = parseInteger()
                else -> throw IllegalArgumentException("suppress file contains an unsupported field")
            }
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        skipWhitespace()
        require(index == content.length) { "suppress file contains trailing content" }
        return SuppressDocument(
            entries ?: throw IllegalArgumentException("suppress entries are missing"),
            version ?: throw IllegalArgumentException("suppress format version is missing"),
        )
    }

    private fun parseEntries(): List<SuppressionEntry> {
        val entries = mutableListOf<SuppressionEntry>()
        expect('[')
        while (true) {
            skipWhitespace()
            if (consume(']')) return entries
            entries += parseEntry()
            skipWhitespace()
            if (consume(']')) return entries
            expect(',')
        }
    }

    private fun parseEntry(): SuppressionEntry {
        var fingerprint: String? = null
        var reason: String? = null
        var expires: LocalDate? = null
        val keys = mutableSetOf<String>()
        expect('{')
        while (true) {
            skipWhitespace()
            if (consume('}')) break
            val key = parseString()
            require(keys.add(key)) { "suppress entry contains a duplicate field" }
            expect(':')
            when (key) {
                "fingerprint" -> fingerprint = parseString()
                "reason" -> reason = parseString()
                "expires" -> expires = parseDate()
                else -> throw IllegalArgumentException("suppress entry contains an unsupported field")
            }
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return SuppressionEntry(
            fingerprint?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("suppress entry fingerprint is missing"),
            reason?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("suppress entry reason is missing"),
            expires ?: throw IllegalArgumentException("suppress entry expires is missing"),
        )
    }

    private fun parseDate(): LocalDate {
        val value = parseString()
        return try {
            LocalDate.parse(value)
        } catch (_: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("suppress expires must be an ISO calendar date (YYYY-MM-DD)")
        }
    }

    private fun parseInteger(): Int {
        skipWhitespace()
        val start = index
        while (index < content.length && content[index].isDigit()) index++
        require(index > start) { "suppress version must be an integer" }
        return content.substring(start, index).toIntOrNull()
            ?: throw IllegalArgumentException("suppress version must be an integer")
    }

    private fun parseString(): String {
        skipWhitespace()
        require(index < content.length && content[index++] == '"') { "suppress value must be a string" }
        return buildString {
            while (index < content.length) {
                val character = content[index++]
                when {
                    character == '"' -> return@buildString
                    character == '\\' -> append(parseEscape())
                    character.code < 0x20 -> throw IllegalArgumentException("suppress string contains a control character")
                    else -> append(character)
                }
            }
            throw IllegalArgumentException("suppress string is not terminated")
        }
    }

    private fun parseEscape(): Char {
        require(index < content.length) { "invalid suppress string escape" }
        return when (val escaped = content[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> throw IllegalArgumentException("invalid suppress string escape")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + 4 <= content.length) { "invalid suppress unicode escape" }
        var value = 0
        repeat(4) {
            val digit = content[index++].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("invalid suppress unicode escape")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun expect(character: Char) {
        skipWhitespace()
        require(index < content.length && content[index++] == character) { "suppress JSON is malformed" }
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
