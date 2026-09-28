package dev.kartograph.index

import java.io.StringReader
import java.util.Properties

/**
 * Spring Boot 설정 문서(`application*.properties`·`application*.yml`) 한 벌을 평탄한 키 → 값으로 읽는다.
 *
 * 라우트 접두사와 매핑 플레이스홀더에 필요한 스칼라 값만 읽는다. 목록·블록 스칼라·흐름 표기는 값으로 쓰지 않고
 * 건너뛴다. 키는 Spring relaxed binding처럼 소문자로 접고 `-`·`_`를 뺀 모양으로 비교한다([normalizeKey]).
 */
internal object SpringConfigDocuments {
    /**
     * 설정 문서 하나다.
     *
     * @property profiles 이 문서를 켜는 프로필이다. 비어 있으면 기본(프로필 무관) 문서다
     * @property values 정규화한 키 → 원문 값이다
     */
    data class Document(val profiles: List<String>, val values: Map<String, String>)

    /** relaxed binding 비교용 키다 — `server.servlet.contextPath`와 `server.servlet.context-path`를 같게 본다. */
    fun normalizeKey(key: String): String = key.trim().lowercase().replace("-", "").replace("_", "")

    /**
     * 파일 하나를 문서들로 읽는다.
     *
     * @param fileName `application-<profile>.<ext>`이면 그 프로필 문서다
     * @param text 파일 원문
     * @return 문서들. properties 이스케이프가 깨져 해석하지 못하면 null이다 — 빈 설정으로 읽으면 접두사를 잘못 확정한다
     */
    fun read(fileName: String, text: String): List<Document>? {
        val fileProfile = fileProfile(fileName)
        val raw = if (fileName.endsWith(".properties")) propertiesDocuments(text) ?: return null else yamlDocuments(text)
        return raw.map { values ->
            val activation = values[ON_PROFILE] ?: values[LEGACY_PROFILES]
            val profiles = listOfNotNull(fileProfile) + activation?.let(::activationProfiles).orEmpty()
            Document(profiles, values)
        }
    }

    private val ON_PROFILE = normalizeKey("spring.config.activate.on-profile")
    private val LEGACY_PROFILES = normalizeKey("spring.profiles")

    /** `application-prod.yml` → `prod`. 기본 파일이면 null이다. */
    private fun fileProfile(fileName: String): String? =
        fileName.substringBeforeLast('.').substringAfter("application-", "").takeIf { it.isNotEmpty() }

    /** 프로필 식이 단순 이름 목록이면 그 이름들, 아니면 식 원문 하나를 "알 수 없는 프로필"로 둔다. */
    private fun activationProfiles(expression: String): List<String> {
        val names = expression.split(',').map(String::trim).filter(String::isNotEmpty)
        return if (names.all { PROFILE_NAME.matches(it) }) names else listOf(expression.trim())
    }

    private val PROFILE_NAME = Regex("[A-Za-z0-9_.-]+")

    /** `#---`·`!---` 줄로 나눈 다중 문서 properties를 읽는다. 잘못된 `\\u` 이스케이프면 null이다. */
    private fun propertiesDocuments(text: String): List<Map<String, String>>? =
        text.split(Regex("(?m)^[#!]---\\s*$")).map { section ->
            val properties = Properties()
            try {
                properties.load(StringReader(section))
            } catch (_: IllegalArgumentException) {
                return null
            }
            properties.stringPropertyNames().associate { normalizeKey(it) to properties.getProperty(it) }
        }

    /** 최소 YAML reader다. 들여쓰기 사슬의 매핑과 스칼라만 읽는다. */
    private fun yamlDocuments(text: String): List<Map<String, String>> {
        val documents = mutableListOf<MutableMap<String, String>>(linkedMapOf())
        val stack = ArrayDeque<Pair<Int, String>>()
        var skipDeeperThan = Int.MAX_VALUE
        text.lines().forEach { rawLine ->
            val line = stripYamlComment(rawLine).trimEnd()
            if (line.trim().isEmpty()) return@forEach
            val indent = line.length - line.trimStart().length
            if (indent > skipDeeperThan) return@forEach
            skipDeeperThan = Int.MAX_VALUE
            val trimmed = line.trim()
            when {
                indent == 0 && (trimmed == "---" || trimmed.startsWith("--- ")) -> { documents += linkedMapOf(); stack.clear() }
                trimmed == "..." -> Unit
                trimmed == "-" || trimmed.startsWith("- ") -> skipDeeperThan = indent
                else -> skipDeeperThan = yamlEntry(trimmed, indent, stack, documents.last()) ?: Int.MAX_VALUE
            }
        }
        return documents
    }

    /**
     * `key: value` 한 줄을 반영한다.
     *
     * @return 이 줄 아래의 더 깊은 줄을 건너뛸 들여쓰기(블록·흐름 값), 아니면 null
     */
    private fun yamlEntry(trimmed: String, indent: Int, stack: ArrayDeque<Pair<Int, String>>, values: MutableMap<String, String>): Int? {
        val colon = keySeparator(trimmed) ?: return indent
        val key = unquote(trimmed.substring(0, colon).trim())
        val value = trimmed.substring(colon + 1).trim()
        while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
        val path = (stack.map { it.second } + key).joinToString(".")
        return when {
            value.isEmpty() -> { stack.addLast(indent to key); null }
            value.first() in "|>{[&*!" -> indent
            else -> { values[normalizeKey(path)] = unquote(value); null }
        }
    }

    /** 따옴표 밖에서 `:` 뒤가 공백이거나 줄 끝인 첫 위치다. */
    private fun keySeparator(line: String): Int? {
        var quote: Char? = null
        line.forEachIndexed { index, character ->
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == ':' && (index == line.lastIndex || line[index + 1] == ' ') -> return index
            }
        }
        return null
    }

    /** 따옴표 밖의 ` #`부터 줄 끝까지를 지운다. */
    private fun stripYamlComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { index, character ->
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '#' && (index == 0 || line[index - 1].isWhitespace()) -> return line.substring(0, index)
            }
        }
        return line
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1).replace("''", "'")
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> decodeLiteral(value.substring(1, value.length - 1))
        else -> value
    }
}
