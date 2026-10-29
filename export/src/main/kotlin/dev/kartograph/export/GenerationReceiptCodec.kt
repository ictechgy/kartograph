package dev.kartograph.export

import dev.kartograph.core.GenerationReceipt
import dev.kartograph.core.GenerationSource
import dev.kartograph.core.InputFingerprint

/** 생성 lifecycle이 남긴 구조와 지문만 읽는다. producer 진위나 현재 파일은 별도로 검증한다. */
public object GenerationReceiptCodec {
    public const val FORMAT: String = "kartograph-generation-receipt"
    public const val MAX_BYTES: Int = 16 * 1024 * 1024

    /** UTF-8 byte 상한을 출력 도중에도 적용한다. */
    public fun render(receipt: GenerationReceipt): String = boundedJsonValue(value(receipt), MAX_BYTES, "\n")

    /** JSON 객체를 만들기 전에 raw UTF-8 크기를 제한하고 알 수 없는 필드는 거부한다. */
    public fun parse(text: String): GenerationReceipt {
        QuerySnapshotCodec.requireMaximum(text, MAX_BYTES)
        return receipt(SnapshotJsonParser(text).parse())
    }

    internal fun value(receipt: GenerationReceipt): Any {
        receipt.requireValid()
        return sortedMapOf(
            "format" to FORMAT, "version" to 1, "scope" to receipt.scope, "task" to receipt.task,
            "policy" to receipt.policy, "attempt" to receipt.attempt, "outputRoot" to receipt.outputRoot,
            "inputs" to receipt.inputs.map(::inputValue), "mapping" to inputValue(receipt.mapping),
            "outputs" to receipt.outputs.map(::inputValue), "sources" to receipt.sources.map {
                sortedMapOf("input" to it.input, "path" to it.path, "qualifiedName" to it.qualifiedName, "sha256" to it.sha256)
            },
        )
    }

    internal fun receipt(value: Any?): GenerationReceipt {
        val row = generationObject(value, setOf("format", "version", "scope", "task", "policy", "attempt", "outputRoot", "inputs", "mapping", "outputs", "sources"))
        require(row["format"] == FORMAT && row["version"] == 1L) { "unsupported generation receipt" }
        return GenerationReceipt(generationString(row["scope"]), generationString(row["task"]), generationString(row["policy"]),
            generationString(row["attempt"]), generationString(row["outputRoot"]), generationArray(row["inputs"]).map(::input),
            input(row["mapping"]), generationArray(row["outputs"]).map(::input), generationArray(row["sources"]).map { raw ->
                val source = generationObject(raw, setOf("input", "path", "qualifiedName", "sha256"))
                GenerationSource(generationString(source["input"]), generationString(source["path"]),
                    generationString(source["qualifiedName"]), generationString(source["sha256"]))
            })
    }

    private fun inputValue(input: InputFingerprint): Any = sortedMapOf("role" to input.role, "path" to input.path, "sha256" to input.sha256)
    private fun input(value: Any?): InputFingerprint = generationObject(value, setOf("role", "path", "sha256")).let {
        InputFingerprint(generationString(it["role"]), generationString(it["path"]), generationString(it["sha256"]))
    }
}

internal fun generationObject(value: Any?, fields: Set<String>): Map<*, *> = (value as? Map<*, *>
    ?: throw IllegalArgumentException("generation evidence requires an object")).also {
    require(it.keys == fields) { "generation evidence has invalid or missing fields" }
}

internal fun generationArray(value: Any?, maximum: Int = GenerationReceipt.MAX_ROWS): List<*> = (value as? List<*>
    ?: throw IllegalArgumentException("generation evidence requires an array")).also {
    require(it.size <= maximum) { "generation evidence exceeds row limits" }
}

internal fun generationString(value: Any?): String = (value as? String
    ?: throw IllegalArgumentException("generation evidence requires a string")).also {
    require(it.length <= 16_384 && it.codePoints().allMatch { code -> code !in 0xD800..0xDFFF }) { "invalid bounded generation string" }
}
