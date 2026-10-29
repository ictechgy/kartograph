package dev.kartograph.export

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.core.ClassTransformObservation
import dev.kartograph.core.TransformedClass

/** 성공한 compiler lifecycle 증거와 snapshot 지문을 같은 스키마로 읽고 쓴다. */
public object BuildWitnessCodec {
    /** 원시 컴파일 옵션과 절대경로를 포함하지 않는 증거 문서를 생성한다. */
    public fun render(witness: BuildWitness): String = jsonValue(witnessValue(witness)) + "\n"

    /** 잘못된 버전/필드에는 미검증 성공 대신 입력 오류를 반환한다. */
    public fun parse(text: String): BuildWitness {
        require(text.length <= QuerySnapshotCodec.MAX_BYTES) { "build witness is too large" }
        return witness(SnapshotJsonParser(text).parse())
    }

    // 단일 capture 문서는 기존과 바이트가 같도록 aggregate 구성원이 있을 때만 memberScopes를 쓴다.
    internal fun provenanceValue(value: SnapshotProvenance): Any = sortedMapOf<String, Any>(
        "version" to if (value.transformations.isEmpty()) 1 else 2,
        "inputs" to value.inputs.map(::inputValue), "witnesses" to value.witnesses.map(::witnessValue),
    ).also { map ->
        if (value.memberScopes.isNotEmpty()) map["memberScopes"] = value.memberScopes.sorted()
        if (value.transformations.isNotEmpty()) map["transformations"] = value.transformations.map(::transformationValue)
    }

    internal fun provenance(value: Any): SnapshotProvenance {
        val map = obj(value)
        require(map["version"] in listOf(1L, 2L)) { "unsupported provenance version" }
        require(map["version"] != 1L || !map.containsKey("transformations")) { "transformations require provenance version 2" }
        val transformations = if (map["version"] == 2L) list(map["transformations"]).map(::transformation) else emptyList()
        require(map["version"] != 2L || transformations.isNotEmpty()) { "missing transformation observations" }
        return SnapshotProvenance(list(map["inputs"]).map(::input), list(map["witnesses"]).map(::witness),
            map["memberScopes"]?.let { scopes -> list(scopes).map(::str) }.orEmpty(), transformations)
    }

    private fun transformationValue(value: ClassTransformObservation): Any = sortedMapOf(
        "scope" to value.scope, "observerTask" to value.observerTask, "policy" to value.policy,
        "originalOutputs" to value.originalOutputs.map(::inputValue), "selectedOutputs" to value.selectedOutputs.map(::inputValue),
        "passthroughOutputs" to value.passthroughOutputs.map(::inputValue),
        "changedClasses" to value.changedClasses.map { sortedMapOf("name" to it.name,
            "beforeSha256" to it.beforeSha256, "afterSha256" to it.afterSha256) },
    )

    private fun transformation(value: Any?): ClassTransformObservation = obj(value).let { map ->
        ClassTransformObservation(str(map["scope"]), str(map["observerTask"]), str(map["policy"]),
            list(map["originalOutputs"]).map(::input), list(map["selectedOutputs"]).map(::input),
            list(map["changedClasses"]).map { obj(it).let { row ->
                TransformedClass(str(row["name"]), str(row["beforeSha256"]), str(row["afterSha256"]))
            } }, list(map["passthroughOutputs"]).map(::input))
    }

    private fun inputValue(input: InputFingerprint): Any = sortedMapOf("role" to input.role, "path" to input.path, "sha256" to input.sha256)
    private fun witnessValue(witness: BuildWitness): Any = sortedMapOf(
        "format" to "kartograph-build-witness", "version" to if (witness.compilerEvidence.isEmpty()) 1 else 2, "scope" to witness.scope,
        "compiler" to witness.compiler, "artifact" to witness.artifact,
        "inputs" to witness.inputs.map(::inputValue), "outputs" to witness.outputs.map(::inputValue),
    ).also { value ->
        if (witness.compilerEvidence.isNotEmpty()) {
            value["compilerEvidence"] = witness.compilerEvidence.map(::inputValue)
            value["evidenceToken"] = requireNotNull(witness.evidenceToken)
        }
    }
    private fun witness(value: Any?): BuildWitness {
        val map = obj(value)
        require(map["format"] == "kartograph-build-witness" && map["version"] in listOf(1L, 2L)) { "unsupported build witness" }
        val enriched = map["version"] == 2L
        require(enriched || !map.containsKey("compilerEvidence") && !map.containsKey("evidenceToken")) { "compiler evidence requires witness version 2" }
        val receipts = if (enriched) list(map["compilerEvidence"]).map(::input) else emptyList()
        require(!enriched || receipts.isNotEmpty()) { "compiler evidence receipts are missing" }
        return BuildWitness(str(map["scope"]), str(map["compiler"]), str(map["artifact"]),
            list(map["inputs"]).map(::input), list(map["outputs"]).map(::input), receipts,
            if (enriched) str(map["evidenceToken"]) else null)
    }
    private fun input(value: Any?): InputFingerprint = obj(value).let { InputFingerprint(str(it["role"]), str(it["path"]), str(it["sha256"])) }
    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()
    private fun list(value: Any?): List<*> = value as? List<*> ?: invalid()
    private fun str(value: Any?): String = value as? String ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("invalid build provenance")
}
