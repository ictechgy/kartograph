package dev.kartograph.export

import dev.kartograph.core.*

/** 저장된 대응의 구조만 복원한다. 현재 파일이나 producer 진위를 검증하지 않는다. */
internal object AndroidResourceBindingSnapshotCodec {
    fun value(evidence: AndroidResourceBindingEvidence): Any = sortedMapOf(
        "version" to 1, "scope" to evidence.scope, "namespace" to evidence.namespace,
        "resourceFactsSha256" to evidence.resourceFactsSha256,
        "producerArtifact" to evidence.producerArtifact, "producerSha256" to evidence.producerSha256,
        "namespaceOptions" to inputValue(evidence.namespaceOptions), "output" to inputValue(evidence.output),
        "mainRoots" to evidence.mainRoots.map(::inputValue),
        "outcomes" to evidence.outcomes.map { sortedMapOf(
            "key" to keyValue(it.key), "field" to it.field?.value, "unavailable" to it.unavailable?.name?.lowerCamel(),
        ) },
    )

    fun parse(value: Any?): AndroidResourceBindingEvidence {
        val document = obj(value, setOf("version", "scope", "namespace", "resourceFactsSha256", "producerArtifact",
            "producerSha256", "namespaceOptions", "output", "mainRoots", "outcomes"))
        require(integer(document["version"]) == 1) { "unsupported Android resource binding version" }
        val roots = array(document["mainRoots"], AndroidResourceEvidence.MAX_ROOTS).map(::input)
        val outcomes = array(document["outcomes"], AndroidResourceEvidence.MAX_DEFINITIONS).map { raw ->
            val row = obj(raw, setOf("key", "field", "unavailable"))
            AndroidResourceFieldOutcome(key(row["key"]), row["field"]?.let { NodeId(string(it)) },
                row["unavailable"]?.let { reason ->
                    AndroidResourceFieldUnavailable.entries.singleOrNull { it.name.lowerCamel() == string(reason) } ?: invalid()
                })
        }
        return AndroidResourceBindingEvidence(string(document["scope"]), string(document["namespace"]),
            string(document["resourceFactsSha256"]), string(document["producerArtifact"]), string(document["producerSha256"]),
            input(document["namespaceOptions"]), input(document["output"]), roots, outcomes).also {
            require(roots == it.mainRoots && outcomes == it.outcomes) { "Android resource bindings are not in canonical order" }
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
