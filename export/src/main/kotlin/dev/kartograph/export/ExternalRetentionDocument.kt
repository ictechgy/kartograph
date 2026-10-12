package dev.kartograph.export

import dev.kartograph.core.RetentionEvidence
import java.util.Collections

/** 개별 보존 root와 문서 전체의 교차 언어 관측 문맥을 분리해 보존한다. */
public class ExternalRetentionDocument internal constructor(
    retentions: List<RetentionEvidence>,
    public val context: ExternalRetentionDocumentContext,
) {
    public val retentions: List<RetentionEvidence> = Collections.unmodifiableList(retentions.toList())
}

/** 출처 문서의 관측 상태이며 컴파일 신선도나 삭제 안전성 판정이 아니다. */
public enum class ExternalRetentionContextStatus { UNAVAILABLE, CAPTURED, UNSUPPORTED }

/**
 * 검증된 원문 관측을 불변 canonical JSON으로 보존한다. 알 수 없는 버전도 동일하게 보존하며
 * build witness와 연결하거나 원본 project/path를 snapshot scope로 다시 쓰지 않는다.
 */
public class ExternalRetentionDocumentContext internal constructor(
    public val producerVersion: String,
    public val generatedAt: String,
    public val omittedObjectiveCHandlers: Long?,
    public val provenanceJson: String?,
    public val provenanceVersion: Long?,
    public val status: ExternalRetentionContextStatus,
    private val diagnosticRecords: List<String>,
) {
    /** snapshot은 raw JSON 문자열을 저장해 숫자 토큰 검증을 generic reader보다 먼저 재실행한다. */
    public val canonicalDocument: String = jsonValue(sortedMapOf<String, Any?>(
        "format" to "external-retentions", "version" to 0,
        "producedBy" to sortedMapOf("name" to "isthmus", "version" to producerVersion),
        "generatedAt" to generatedAt, "omittedObjectiveCHandlers" to omittedObjectiveCHandlers,
        "retentions" to emptyList<Any>(),
    ).filterValues { it != null }).dropLast(1) +
        (provenanceJson?.let { ",\"provenance\":$it}" } ?: "}")

    internal fun diagnostics(): List<String> = diagnosticRecords
    override fun equals(other: Any?): Boolean = other is ExternalRetentionDocumentContext && canonicalDocument == other.canonicalDocument
    override fun hashCode(): Int = canonicalDocument.hashCode()
    override fun toString(): String = "ExternalRetentionDocumentContext(status=$status, version=$provenanceVersion)"
}

/** 전체 관측은 context에 남기고 사람용 진단은 결정적인 prefix와 정확한 생략 수로 제한한다. */
public object ExternalRetentionCaveats {
    public fun messages(contexts: Collection<ExternalRetentionDocumentContext>): List<String> {
        if (contexts.isEmpty()) return emptyList()
        val ordered = contexts.distinct().sortedBy { it.canonicalDocument }
        val records = ordered.flatMap { it.diagnostics() }
        return buildList {
            add("external-retentions: supplied capture context is not compiler freshness or deletion-safety evidence")
            ordered.groupingBy { it.status }.eachCount().toSortedMap(compareBy { it.ordinal }).forEach { (status, count) ->
                add("external-retentions: $count document(s) have ${status.name.lowercase()} capture context")
            }
            records.take(20).forEach { add(it) }
            if (records.size > 20) add("external-retentions: ${records.size - 20} additional context records omitted from display; full context is retained")
        }
    }

    internal fun safe(value: String): String = buildString {
        val codePoints = value.codePoints().toArray()
        for (code in codePoints.take(512)) {
            if (code in 0..31 || code in 127..159 || code == 0x2028 || code == 0x2029 ||
                code.toChar() in "\\%`<>[]*_#&") append("\\u").append(code.toString(16).padStart(4, '0'))
            else appendCodePoint(code)
        }
        if (codePoints.size > 512) append("… (${codePoints.size - 512} characters omitted)")
    }
}

/** wire v1의 모양만 해석하며 먼저 모든 버전의 byte/count/token 계약을 적용한다. */
internal object ExternalRetentionProvenance {
    fun context(document: Map<*, *>): ExternalRetentionDocumentContext {
        val producer = document["producedBy"] as Map<*, *>
        val value = if ("provenance" in document) document["provenance"] as? Map<*, *> ?: invalid() else null
        var version: Long? = null
        var status = ExternalRetentionContextStatus.UNAVAILABLE
        val diagnostics = mutableListOf<String>()
        val canonical = value?.let {
            // 중복 제거 전 크기·개수 검사를 하므로 중복값으로 admission 상한을 우회할 수 없다.
            RetentionCanonicalJson.render(it)
            listLimit(it["sources"], 256)
            listLimit(it["limitations"], 4096)
            version = (it["version"] as? Long)?.takeIf { number -> number > 0 } ?: invalid()
            if (version == 1L) {
                val limitations = it["limitations"] as? List<*> ?: invalid()
                limitations.forEach(::limitation)
                val sources = if ("sources" in it) (it["sources"] as? List<*> ?: invalid()) else emptyList<Any>()
                sources.forEach(::source)
                status = if (sources.isEmpty()) ExternalRetentionContextStatus.UNAVAILABLE else ExternalRetentionContextStatus.CAPTURED
                val normalized = it.entries.associate { entry -> entry.key as String to entry.value }.toMutableMap()
                if ("sources" in it) normalized["sources"] = canonicalRows(sources)
                normalized["limitations"] = canonicalRows(limitations)
                (normalized["sources"] as? List<*>)?.forEach { raw ->
                    val source = raw as Map<*, *>
                    diagnostics += "external-retentions source: " + ExternalRetentionCaveats.safe(RetentionCanonicalJson.render(source))
                }
                (normalized["limitations"] as List<*>).forEach { raw ->
                    diagnostics += "external-retentions limitation: " + ExternalRetentionCaveats.safe(RetentionCanonicalJson.render(raw))
                }
                RetentionCanonicalJson.render(normalized)
            } else {
                status = ExternalRetentionContextStatus.UNSUPPORTED
                diagnostics += "external-retentions: provenance version $version is unsupported; opaque context is retained"
                RetentionCanonicalJson.render(it)
            }
        }
        val omitted = document["omittedObjectiveCHandlers"] as? Long
        if (omitted != null && omitted > 0) diagnostics += "external-retentions: $omitted Objective-C handlers omitted by the producer"
        return ExternalRetentionDocumentContext(producer["version"] as String, document["generatedAt"] as String,
            omitted, canonical, version, status, Collections.unmodifiableList(diagnostics.toList()))
    }

    private fun canonicalRows(rows: List<*>): List<Any?> = rows.associateBy { RetentionCanonicalJson.render(it) }
        .toSortedMap().values.toList()

    private fun listLimit(value: Any?, maximum: Int) { if (value is List<*>) require(value.size <= maximum) }
    private fun record(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()
    private fun string(value: Any?): String = value as? String ?: invalid()
    private fun strings(value: Any?) { (value as? List<*> ?: invalid()).forEach(::string) }
    private fun nullableTarget(item: Map<*, *>) { require("target" in item); item["target"]?.let(::string) }
    private fun limitation(value: Any?) {
        val item = record(value)
        listOf("platform", "tool", "message").forEach { string(item[it]) }
        nullableTarget(item)
        if ("origin" in item) string(item["origin"])
        if ("channels" in item) strings(item["channels"])
        if ("routeScope" in item) {
            val scope = record(item["routeScope"])
            listOf("templates", "templatePrefixes", "templateSuffixes", "methods").forEach { if (it in scope) strings(scope[it]) }
            if ("withheldElements" in scope) require(scope["withheldElements"] is Long && (scope["withheldElements"] as Long) >= 0)
        }
    }
    private fun source(value: Any?) {
        val item = record(value)
        require(item["format"] == "bridge-facts" && item["version"] in listOf(1L, 2L))
        val tool = record(item["tool"]); string(tool["name"]); string(tool["version"])
        string(item["platform"]); string(item["project"]); nullableTarget(item)
        timestamp(item["generatedAt"])
        if ("sourceModifiedAt" in item) timestamp(item["sourceModifiedAt"])
        if (item["version"] == 2L) require(item["transport"] in setOf("basic-message-channel", "event-channel", "react-native-event"))
        else require("transport" !in item)
    }
    private fun timestamp(value: Any?) {
        val text = string(value)
        val match = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(\\.[0-9]+)?([Zz]|[+-]([0-9]{2}):([0-9]{2}))").matchEntire(text) ?: invalid()
        val fields = match.groupValues
        val year = fields[1].toInt()
        val month = fields[2].toInt()
        val day = fields[3].toInt()
        require(month in 1..12)
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val maximumDay = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
        require(day in 1..maximumDay && fields[4].toInt() in 0..23 &&
            fields[5].toInt() in 0..59 && fields[6].toInt() in 0..59)
        if (fields[9].isNotEmpty()) require(fields[9].toInt() in 0..23 && fields[10].toInt() in 0..59)
        // 계약의 offset 상한은 23:59이다. JDK의 18시간 제한이나 소수초 정규화를 적용하지 않는다.
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("invalid external retention provenance")
}

/** JSON.stringify와 같은 최소 escape/UTF8 byte 계약이며 일반 snapshot encoder와 독립적이다. */
internal object RetentionCanonicalJson {
    private const val MAXIMUM = 1_048_576
    fun render(value: Any?): String {
        var bytes = 0
        fun charge(count: Int) { require(count <= MAXIMUM - bytes) { "external retention provenance exceeds 1 MiB" }; bytes += count }
        visit(value, 0, { token -> charge(token.toByteArray(Charsets.UTF_8).size) }, { text ->
            charge(2)
            text.codePoints().forEach { code -> charge(when {
                code == 34 || code == 92 || code in listOf(8, 9, 10, 12, 13) -> 2
                code in 0..31 -> 6
                code <= 0x7f -> 1
                code <= 0x7ff -> 2
                code <= 0xffff -> 3
                else -> 4
            }) }
        })
        return buildString(bytes) { visit(value, 0, { append(it) }, { text ->
            append('"')
            text.codePoints().forEach { code ->
                when (code) {
                    34 -> append("\\\""); 92 -> append("\\\\"); 8 -> append("\\b"); 9 -> append("\\t")
                    10 -> append("\\n"); 12 -> append("\\f"); 13 -> append("\\r")
                    in 0..31 -> append("\\u").append(code.toString(16).padStart(4, '0'))
                    else -> appendCodePoint(code)
                }
            }
            append('"')
        }) }
    }
    private fun visit(value: Any?, depth: Int, token: (String) -> Unit, string: (String) -> Unit) {
        when (value) {
            null -> token("null")
            is Boolean -> token(value.toString())
            is Long -> { require(value in -9_007_199_254_740_991L..9_007_199_254_740_991L); token(value.toString()) }
            is String -> { require(value.codePoints().allMatch { it !in 0xD800..0xDFFF }); string(value) }
            is Map<*, *> -> {
                require(depth < 64)
                token("{")
                value.entries.sortedBy { it.key as? String ?: error("invalid provenance key") }.forEachIndexed { index, entry ->
                    if (index > 0) token(",")
                    visit(entry.key, depth + 1, token, string); token(":"); visit(entry.value, depth + 1, token, string)
                }
                token("}")
            }
            is List<*> -> {
                require(depth < 64); token("[")
                value.forEachIndexed { index, item -> if (index > 0) token(","); visit(item, depth + 1, token, string) }
                token("]")
            }
            else -> throw IllegalArgumentException("invalid provenance JSON value")
        }
    }
}
