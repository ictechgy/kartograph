package dev.kartograph.export

import dev.kartograph.analysis.SymbolQueryDocument
import dev.kartograph.analysis.SymbolQueryNeighbor
import dev.kartograph.analysis.SymbolQueryResult
import dev.kartograph.analysis.SymbolQuerySubject
import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.SourceLocation

/** 에이전트 교환 문서를 결정적인 키 순서의 JSON으로 렌더링한다. */
public object AgentDocumentRenderer {
    /** SymbolQueryDocument의 optional 필드 생략 의미를 보존한 JSON을 만든다. */
    public fun query(document: SymbolQueryDocument): String = jsonValue(
        buildMap<String, Any?> {
            document.candidates?.let { candidates ->
                put(
                    "candidates",
                    candidates.map { candidate ->
                        buildMap<String, Any?> {
                            put("qualifiedName", candidate.qualifiedName)
                            candidate.usr?.let { put("usr", it) }
                        }.toSortedMap()
                    },
                )
            }
            put("level", document.level)
            put("limitations", document.limitations.sorted())
            put("requested", document.requested)
            document.result?.let { put("result", it.toJsonValue()) }
            put("status", document.status)
        }.toSortedMap(),
    ) + "\n"

    /** isthmus bridge-facts v1의 public 필드만 포함한 JSON을 만든다. */
    public fun bridges(document: BridgeFactsDocument): String = jsonValue(sortedMapOf(
        "facts" to document.facts.map { it.toJsonValue() },
        "format" to document.format,
        "generatedAt" to document.generatedAt,
        "limitations" to document.limitations.sorted(),
        "platform" to document.platform,
        "project" to document.project,
        "target" to document.target,
        "tool" to sortedMapOf("name" to document.tool.name, "version" to document.tool.version),
        "version" to document.version,
    )) + "\n"

    private fun SymbolQueryResult.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        declaredIn?.let { put("declaredIn", it.toJsonValue()) }
        put("dependsOn", dependsOn.map { it.toJsonValue() })
        put("members", members.map { it.toJsonValue() })
        put(
            "reachability",
            buildMap<String, Any?> {
                reachability.path?.let { put("path", it) }
                reachability.reason?.let { put("reason", it.name.lowerCamel()) }
                put("state", reachability.state)
                put("suppressedByBaseline", reachability.suppressedByBaseline)
            }.toSortedMap(),
        )
        put("subject", subject.toJsonValue())
        put(
            "truncated",
            sortedMapOf(
                "dependsOn" to truncated.dependsOn,
                "members" to truncated.members,
                "usedBy" to truncated.usedBy,
            ),
        )
        put("usedBy", usedBy.map { it.toJsonValue() })
    }.toSortedMap()

    private fun SymbolQuerySubject.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        put("accessibility", accessibility)
        put("kind", kind)
        location?.let { put("location", it.toJsonValue()) }
        module?.let { put("module", it) }
        put("name", name)
        put("qualifiedName", qualifiedName)
        usr?.let { put("usr", it) }
    }.toSortedMap()

    private fun SymbolQueryNeighbor.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        put("depth", depth)
        put("edges", edges)
        put("kind", kind)
        location?.let { put("location", it.toJsonValue()) }
        module?.let { put("module", it) }
        put("name", name)
        put("qualifiedName", qualifiedName)
        usr?.let { put("usr", it) }
    }.toSortedMap()

    private fun SourceLocation.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        column?.let { put("column", it) }
        line?.let { put("line", it) }
        put("path", path)
    }.toSortedMap()

    private fun BridgeFact.toJsonValue(): Map<String, Any?> = buildMap {
        put("channel", channel)
        put("dynamic", dynamic)
        put("kind", kind)
        put("location", sortedMapOf("column" to location.column, "line" to location.line, "path" to location.path))
        if (method != null) put("method", method)
        symbol?.let { value ->
            put(
                "symbol",
                buildMap<String, Any?> {
                    put("qualifiedName", value.qualifiedName)
                    value.usr?.let { put("usr", it) }
                }.toSortedMap(),
            )
        }
    }.toSortedMap()

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ", ") { (key, item) ->
            "\"${escape(key.toString())}\": ${jsonValue(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ", ") { jsonValue(it) }
        else -> error("unsupported JSON value: ${value::class.simpleName}")
    }

    private fun escape(value: String): String = buildString {
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

    private fun String.lowerCamel(): String = lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
    }
}
