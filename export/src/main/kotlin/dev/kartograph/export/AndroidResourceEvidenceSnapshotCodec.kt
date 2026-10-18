package dev.kartograph.export

import dev.kartograph.core.AndroidResourceEvidence
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.AndroidResourceRoot
import dev.kartograph.core.AndroidLayoutDefinition
import dev.kartograph.core.AndroidLayoutInclude
import dev.kartograph.core.AndroidLayoutIncludeKind
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.InputFingerprint

/** 미승인 관측을 보존한다. channel 존재는 producer admission이나 영향 mapping을 뜻하지 않는다. */
internal object AndroidResourceEvidenceSnapshotCodec {
    fun value(evidence: AndroidResourceEvidence): Any = sortedMapOf(
        "version" to 1, "admission" to evidence.admission, "scope" to evidence.scope, "namespace" to evidence.namespace,
        "roots" to evidence.roots.map { sortedMapOf("id" to it.id, "input" to inputValue(it.input)) },
        "definitions" to evidence.definitions.map { sortedMapOf(
            "id" to it.id, "key" to keyValue(it.key), "rootId" to it.rootId, "path" to it.file.path,
            "sourceSha256" to it.file.sha256, "relativePath" to it.relativePath, "qualifier" to it.qualifier,
        ) },
        "includes" to evidence.includes.map { sortedMapOf(
            "id" to it.id, "sourceDefinitionId" to it.sourceDefinitionId, "ordinal" to it.ordinal,
            "kind" to it.kind.name.lowerCamel(), "target" to it.target?.let(::keyValue),
        ) },
    )

    fun parse(value: Any?): AndroidResourceEvidence {
        val document = obj(value, setOf("version", "admission", "scope", "namespace", "roots", "definitions", "includes"))
        require(integer(document["version"]) == 1 && string(document["admission"]) == "unverified") { "unsupported Android resource evidence version or admission" }
        val roots = array(document["roots"], AndroidResourceEvidence.MAX_ROOTS).map { raw ->
            val row = obj(raw, setOf("id", "input"))
            AndroidResourceRoot(input(row["input"])).also { require(string(row["id"]) == it.id) { "resource root identity mismatch" } }
        }
        val definitions = array(document["definitions"], AndroidResourceEvidence.MAX_DEFINITIONS).map { raw ->
            val row = obj(raw, setOf("id", "key", "rootId", "path", "sourceSha256", "relativePath", "qualifier"))
            AndroidLayoutDefinition(key(row["key"]), string(row["rootId"]),
                CompilerEvidenceSource(string(row["path"]), string(row["sourceSha256"])),
                string(row["relativePath"]), string(row["qualifier"])).also {
                require(string(row["id"]) == it.id) { "layout definition identity mismatch" }
            }
        }
        val includes = array(document["includes"], AndroidResourceEvidence.MAX_INCLUDES).map { raw ->
            val row = obj(raw, setOf("id", "sourceDefinitionId", "ordinal", "kind", "target"))
            val kind = AndroidLayoutIncludeKind.entries.singleOrNull { it.name.lowerCamel() == string(row["kind"]) } ?: invalid()
            AndroidLayoutInclude(string(row["sourceDefinitionId"]), integer(row["ordinal"]), kind,
                row["target"]?.let(::key)).also { require(string(row["id"]) == it.id) { "layout include identity mismatch" } }
        }
        return AndroidResourceEvidence(string(document["scope"]), string(document["namespace"]), roots, definitions, includes).also {
            require(roots == it.roots && definitions == it.definitions && includes == it.includes) { "Android resource rows are not in canonical order" }
        }
    }

    private fun keyValue(key: AndroidResourceKey): Any = sortedMapOf("namespace" to key.namespace, "type" to key.type, "name" to key.name)
    private fun key(value: Any?): AndroidResourceKey = obj(value, setOf("namespace", "type", "name")).let {
        AndroidResourceKey(string(it["namespace"]), string(it["type"]), string(it["name"]))
    }

    private fun inputValue(input: InputFingerprint): Any = sortedMapOf(
        "role" to input.role, "path" to input.path, "sha256" to input.sha256,
    )

    private fun input(value: Any?): InputFingerprint = obj(value, setOf("role", "path", "sha256")).let {
        InputFingerprint(string(it["role"]), string(it["path"]), string(it["sha256"]))
    }

    private fun obj(value: Any?, fields: Set<String>): Map<*, *> = (value as? Map<*, *> ?: invalid()).also {
        require(it.keys == fields) { "Android resources have invalid or missing fields" }
    }

    private fun array(value: Any?, maximum: Int): List<*> = (value as? List<*> ?: invalid()).also {
        require(it.size <= maximum) { "Android resource collection exceeds its limit" }
    }

    private fun integer(value: Any?): Int = (value as? Long)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: invalid()

    private fun string(value: Any?): String = (value as? String ?: invalid()).also { text ->
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            bytes += when {
                Character.isHighSurrogate(character) -> {
                    require(index < text.length && Character.isLowSurrogate(text[index])) { "Android resource string is not Unicode scalar text" }
                    index++
                    4
                }
                Character.isLowSurrogate(character) -> invalid()
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
            require(bytes <= 16_384) { "Android resource string exceeds its byte limit" }
        }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Android resources have invalid or missing fields")

}
