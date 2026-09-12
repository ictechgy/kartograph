package dev.kartograph.export

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance

/** 성공한 compiler lifecycle 증거와 snapshot 지문을 같은 스키마로 읽고 쓴다. */
public object BuildWitnessCodec {
    /** 원시 컴파일 옵션과 절대경로를 포함하지 않는 증거 문서를 생성한다. */
    public fun render(witness: BuildWitness): String = jsonValue(witnessValue(witness)) + "\n"

    /** 잘못된 버전/필드에는 미검증 성공 대신 입력 오류를 반환한다. */
    public fun parse(text: String): BuildWitness {
        require(text.length <= QuerySnapshotCodec.MAX_BYTES) { "build witness is too large" }
        return witness(SnapshotJsonParser(text).parse())
    }

    internal fun provenanceValue(value: SnapshotProvenance): Any = sortedMapOf(
        "version" to 1, "inputs" to value.inputs.map(::inputValue), "witnesses" to value.witnesses.map(::witnessValue),
    )

    internal fun provenance(value: Any): SnapshotProvenance {
        val map = obj(value)
        require(map["version"] == 1L) { "unsupported provenance version" }
        return SnapshotProvenance(list(map["inputs"]).map(::input), list(map["witnesses"]).map(::witness))
    }

    private fun inputValue(input: InputFingerprint): Any = sortedMapOf("role" to input.role, "path" to input.path, "sha256" to input.sha256)
    private fun witnessValue(witness: BuildWitness): Any = sortedMapOf(
        "format" to "kartograph-build-witness", "version" to 1, "scope" to witness.scope,
        "compiler" to witness.compiler, "artifact" to witness.artifact,
        "inputs" to witness.inputs.map(::inputValue), "outputs" to witness.outputs.map(::inputValue),
    )
    private fun witness(value: Any?): BuildWitness {
        val map = obj(value)
        require(map["format"] == "kartograph-build-witness" && map["version"] == 1L) { "unsupported build witness" }
        return BuildWitness(str(map["scope"]), str(map["compiler"]), str(map["artifact"]),
            list(map["inputs"]).map(::input), list(map["outputs"]).map(::input))
    }
    private fun input(value: Any?): InputFingerprint = obj(value).let { InputFingerprint(str(it["role"]), str(it["path"]), str(it["sha256"])) }
    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()
    private fun list(value: Any?): List<*> = value as? List<*> ?: invalid()
    private fun str(value: Any?): String = value as? String ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("invalid build provenance")
}
