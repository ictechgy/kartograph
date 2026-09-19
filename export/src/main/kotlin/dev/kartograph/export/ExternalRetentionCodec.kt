package dev.kartograph.export

import dev.kartograph.core.ExternalBridgeCaller
import dev.kartograph.core.ExternalBridgeEvidence
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import java.time.OffsetDateTime

/** isthmus의 external-retentions v0를 JVM 식별자와 원본 호출 근거로 읽는다. */
public object ExternalRetentionCodec {
    /** 손상·미지원·불완전 입력은 일부만 보존하지 않고 문서 전체를 거부한다. */
    public fun parse(content: String): List<RetentionEvidence> {
        require(content.length <= 16 * 1024 * 1024) { "external retention input is too large; narrow the inputs" }
        try {
            val document = record(SnapshotJsonParser(content, generalNumbers = true).parse())
            require(document["format"] == "external-retentions" && document["version"] == 0L)
            val producer = record(document["producedBy"])
            require(text(producer["name"]) == "isthmus")
            text(producer["version"])
            OffsetDateTime.parse(text(document["generatedAt"]))
            document["omittedObjectiveCHandlers"]?.let { integer(it, 0) }
            val entries = document["retentions"] as? List<*> ?: invalid()
            require(entries.size <= 100_000)
            var callerCount = 0L
            return entries.map { raw ->
                val item = record(raw)
                require(item["reason"] == "bridge")
                val symbol = record(item["symbol"])
                text(symbol["qualifiedName"])
                val identifier = text(symbol["usr"])
                require(identifier.startsWith("method:") || identifier.startsWith("class:") ||
                    identifier.startsWith("field:"))
                require(identifier.substringAfter(':').isNotBlank())
                val bridge = evidence(record(item["evidence"]))
                callerCount += bridge.callers.size
                require(callerCount <= 1_000_000)
                RetentionEvidence(
                    NodeId(identifier), RetentionReason.EXTERNAL_BRIDGE,
                    SourceLocation(bridge.caller.path, bridge.caller.line), bridge,
                )
            }.distinct()
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: java.time.format.DateTimeParseException) {
            invalid()
        }
    }

    internal fun evidence(item: Map<*, *>): ExternalBridgeEvidence {
        val representative = caller(record(item["caller"]))
        val callers = if ("callers" in item) {
            val values = item["callers"] as? List<*> ?: invalid()
            require(values.isNotEmpty() && values.size <= 100)
            values.map { caller(record(it)) }.also { require(representative in it) }
        } else listOf(representative)
        return ExternalBridgeEvidence(
            text(item["channel"]), item["method"]?.let(::text), representative, callers,
            if ("callersOmitted" in item) integer(item["callersOmitted"], 0) else 0,
        )
    }

    /** 스냅샷도 같은 호출 근거를 손실 없이 왕복하게 하는 정렬된 값 표현이다. */
    internal fun evidenceValue(evidence: ExternalBridgeEvidence): Map<String, Any?> = sortedMapOf(
        "channel" to evidence.channel, "method" to evidence.method,
        "caller" to callerValue(evidence.caller), "callers" to evidence.callers.map(::callerValue),
        "callersOmitted" to evidence.callersOmitted,
    ).filterValues { it != null }

    private fun callerValue(caller: ExternalBridgeCaller): Map<String, Any> = sortedMapOf(
        "platform" to caller.platform, "path" to caller.path, "line" to caller.line,
    )

    private fun caller(item: Map<*, *>): ExternalBridgeCaller {
        val platform = text(item["platform"])
        require(platform == "dart" || platform == "js")
        val path = text(item["path"])
        require(!path.startsWith('/') && !path.startsWith('\\') &&
            !Regex("^[A-Za-z]:").containsMatchIn(path) && path.split('/', '\\').none { it == ".." })
        val line = integer(item["line"], 1)
        require(line <= Int.MAX_VALUE)
        return ExternalBridgeCaller(platform, path, line.toInt())
    }

    private fun record(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()

    private fun integer(value: Any?, minimum: Long): Long =
        (value as? Long)?.takeIf { it in minimum..9_007_199_254_740_991L } ?: invalid()

    private fun text(value: Any?): String {
        val result = value as? String ?: invalid()
        require(result.isNotEmpty())
        require(result.codePoints().allMatch { code ->
            code !in 0..31 && code !in 127..159 && code != 0x2028 && code != 0x2029 && code !in 0xD800..0xDFFF
        })
        return result
    }

    private fun invalid(): Nothing = throw IllegalArgumentException(
        "invalid external-retentions v0 document; regenerate with isthmus retentions --for kartograph using JVM identifiers",
    )
}
