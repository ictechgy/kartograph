package dev.kartograph.export

import dev.kartograph.core.InputFingerprint

/** 로컬 전용 외부 입력 연결이다. 값에 절대경로가 있으므로 snapshot과 함께 공개하지 않는다. */
public object ExternalInputBindingsCodec {
    private const val FORMAT = "kartograph-local-input-bindings"
    private const val MAX_CHARS = 1024 * 1024

    /** portable 슬롯과 현재 머신의 경로를 결정적인 문서로 기록한다. */
    public fun render(bindings: Map<String, String>): String {
        validate(bindings)
        return (jsonValue(sortedMapOf("format" to FORMAT, "version" to 1, "bindings" to bindings.toSortedMap())) + "\n")
            .also { require(it.length <= MAX_CHARS) { "input bindings are too large" } }
    }

    /** 명시적으로 전달된 로컬 연결만 읽으며 잘못된 슬롯·값은 거부한다. */
    public fun parse(text: String): Map<String, String> {
        require(text.length <= MAX_CHARS) { "input bindings are too large" }
        val document = SnapshotJsonParser(text).parse() as? Map<*, *> ?: invalid()
        require(document["format"] == FORMAT && document["version"] == 1L) { "unsupported input bindings" }
        val values = document["bindings"] as? Map<*, *> ?: invalid()
        val bindings = values.entries.associate { (key, value) ->
            (key as? String ?: invalid()) to (value as? String ?: invalid())
        }
        validate(bindings)
        return bindings
    }

    private fun validate(bindings: Map<String, String>) {
        bindings.forEach { (slot, path) ->
            require(slot.startsWith("external/")) { "input binding requires an external slot" }
            InputFingerprint("binding", slot, "0".repeat(64))
            require(path.isNotBlank() && path.none { it.code < 32 }) { "invalid input binding path" }
        }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid input bindings")
}
