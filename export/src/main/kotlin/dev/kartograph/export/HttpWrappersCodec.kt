package dev.kartograph.export

import dev.kartograph.core.HttpWrapperArgument
import dev.kartograph.core.HttpWrapperDeclaration

/**
 * isthmus `http-wrappers` v1 선언 파일을 읽는다.
 *
 * 계약(isthmus `docs/HTTP-WRAPPERS.md`)대로 모르는 필드·잘못된 값은 선언 오류로 문서 전체를 거부한다.
 * 선언을 조용히 무시하면 낡은 선언이 호출 0건을 내어 "호출 없음"으로 읽히기 때문이다. 오류 문구에는
 * 선언 원문 값을 넣지 않고 위치(`wrappers[3].pathArg`)와 고칠 방향만 싣는다.
 */
public object HttpWrappersCodec {
    /** 선언 파일 하나의 최대 크기다. 사람이 쓰는 선언 목록이라 넉넉한 상한이다. */
    private const val MAX_CONTENT_LENGTH = 1024 * 1024

    /** 선언 수 상한이다. 입력 크기와 스캔 비용을 함께 묶는다. */
    private const val MAX_WRAPPERS = 1_000

    /**
     * 선언 파일 내용을 선언 목록으로 바꾼다.
     *
     * @param content 선언 파일의 UTF-8 텍스트
     * @return 파일에 적힌 순서대로의 선언 목록(언어 필터링은 호출자가 한다)
     * @throws IllegalArgumentException 형식·버전·필드가 계약과 다를 때. 메시지에 원인 위치를 싣는다
     */
    public fun parse(content: String): List<HttpWrapperDeclaration> {
        require(content.length <= MAX_CONTENT_LENGTH) { failure("the file exceeds 1 MiB") }
        val document = try {
            SnapshotJsonParser(content, generalNumbers = true).parse()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(failure("the file is not valid JSON"))
        }
        val root = document as? Map<*, *> ?: throw IllegalArgumentException(failure("the top level must be an object"))
        rejectUnknown(root, DOCUMENT_FIELDS, "the document")
        require(root["format"] == "http-wrappers") { failure("format must be \"http-wrappers\"") }
        require(root["version"] == 1L) { failure("version must be 1") }
        val entries = root["wrappers"] as? List<*> ?: throw IllegalArgumentException(failure("wrappers must be an array"))
        require(entries.size <= MAX_WRAPPERS) { failure("more than $MAX_WRAPPERS wrappers") }
        return entries.mapIndexed { index, entry -> declaration(entry, "wrappers[$index]") }
    }

    /** 선언 한 건을 검증해 값으로 만든다. */
    private fun declaration(value: Any?, where: String): HttpWrapperDeclaration {
        val entry = value as? Map<*, *> ?: throw IllegalArgumentException(failure("$where must be an object"))
        rejectUnknown(entry, WRAPPER_FIELDS, where)
        val language = choice(entry["language"], LANGUAGES, "$where.language")
        val kind = choice(entry["kind"], KINDS, "$where.kind")
        val owner = text(entry["owner"], "$where.owner")
        val name = text(entry["name"], "$where.name")
        // Kotlin 생성자의 JVM 이름은 `<init>`이다 — 다른 이름은 함수 선언과 혼동한 것이다.
        require(!(language == "kotlin" && kind == "constructor" && name != "<init>")) {
            failure("$where.name must be \"<init>\" for a kotlin constructor")
        }
        val methodArg = entry["methodArg"]?.let { argument(it, "$where.methodArg") }
        val pathArg = argument(entry["pathArg"] ?: throw IllegalArgumentException(failure("$where.pathArg is required")), "$where.pathArg")
        val defaultMethod = entry["defaultMethod"]?.let { choice(it, VERBS, "$where.defaultMethod") }
        require(methodArg != null || defaultMethod != null) {
            failure("$where needs methodArg or defaultMethod")
        }
        return HttpWrapperDeclaration(
            language = language,
            kind = kind,
            owner = owner,
            name = name,
            methodArg = methodArg,
            pathArg = pathArg,
            defaultMethod = defaultMethod,
            methodEnum = entry["methodEnum"]?.let { methodEnum(it, "$where.methodEnum") }.orEmpty(),
            pathAnchor = choice(entry["pathAnchor"], ANCHORS, "$where.pathAnchor"),
            service = entry["service"]?.let { text(it, "$where.service") },
        )
    }

    /** `{index?, label?}` 인자 지정자를 읽는다 — 둘 중 하나 이상이 있어야 한다. */
    private fun argument(value: Any, where: String): HttpWrapperArgument {
        val entry = value as? Map<*, *> ?: throw IllegalArgumentException(failure("$where must be an object"))
        rejectUnknown(entry, ARGUMENT_FIELDS, where)
        val index = entry["index"]?.let {
            (it as? Long)?.takeIf { number -> number in 0..255 }?.toInt()
                ?: throw IllegalArgumentException(failure("$where.index must be an integer from 0 to 255"))
        }
        val label = entry["label"]?.let { text(it, "$where.label") }
        require(index != null || label != null) { failure("$where requires index or label") }
        return HttpWrapperArgument(index, label)
    }

    /** enum case → 동사 매핑을 읽는다. 값은 계약 동사만 허용한다. */
    private fun methodEnum(value: Any, where: String): Map<String, String> {
        val entry = value as? Map<*, *> ?: throw IllegalArgumentException(failure("$where must be an object"))
        return entry.entries.associate { (key, verb) ->
            val name = text(key, "$where key")
            name to choice(verb, VERBS, "$where value")
        }.toSortedMap()
    }

    /** 허용 목록 밖 필드를 거부한다 — 모르는 필드는 선언 오류다. */
    private fun rejectUnknown(entry: Map<*, *>, allowed: Set<String>, where: String) {
        val unknown = entry.keys.map { it.toString() }.filterNot(allowed::contains).sorted()
        require(unknown.isEmpty()) { failure("$where has unknown field(s); allowed fields are ${allowed.sorted()}") }
    }

    private fun choice(value: Any?, allowed: Set<String>, where: String): String {
        val result = value as? String
        require(result != null && result in allowed) { failure("$where must be one of ${allowed.sorted()}") }
        return result
    }

    /** 비어 있지 않고 제어 문자가 없는 문자열이다. */
    private fun text(value: Any?, where: String): String {
        val result = value as? String
        require(result != null && result.isNotBlank() && result.length <= 512 && result.none(Char::isISOControl)) {
            failure("$where must be a non-empty string without control characters")
        }
        return result
    }

    private fun failure(reason: String): String =
        "invalid http-wrappers v1 declaration: $reason; fix the file to match the http-wrappers v1 schema"

    private val DOCUMENT_FIELDS = setOf("format", "version", "wrappers")
    private val WRAPPER_FIELDS = setOf(
        "language", "kind", "owner", "name", "methodArg", "pathArg", "defaultMethod", "methodEnum",
        "pathAnchor", "service",
    )
    private val ARGUMENT_FIELDS = setOf("index", "label")
    private val LANGUAGES = setOf("swift", "kotlin", "dart", "js")
    private val KINDS = setOf("constructor", "function")
    private val ANCHORS = setOf("root", "base")

    /** 계약 동사 집합이다. route-call에는 `ANY`가 없다. */
    private val VERBS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")
}
