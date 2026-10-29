package dev.kartograph.export

import dev.kartograph.core.GenerationConsumptionObservation
import dev.kartograph.core.GenerationOriginContext
import dev.kartograph.core.GenerationProviderBinding
import dev.kartograph.core.InputFingerprint

/** 최초 성공 문맥 sidecar다. 기존 receipt JSON과 버전은 바꾸지 않는다. */
public object GenerationOriginContextCodec {
    public fun render(context: GenerationOriginContext): String = boundedJsonValue(value(context), GenerationReceiptCodec.MAX_BYTES, "\n")

    public fun parse(text: String): GenerationOriginContext {
        QuerySnapshotCodec.requireMaximum(text, GenerationReceiptCodec.MAX_BYTES)
        return context(SnapshotJsonParser(text).parse())
    }

    internal fun value(context: GenerationOriginContext): Any {
        context.requireValid()
        return sortedMapOf(
            "format" to "kartograph-generation-origin-context", "version" to 1,
            "receiptSha256" to context.receiptSha256, "scope" to context.scope, "task" to context.task, "variant" to context.variant,
            "outputRoot" to context.outputRoot, "mappingPath" to context.mappingPath, "actionPolicy" to context.actionPolicy,
            "providerBindings" to context.providerBindings.map { sortedMapOf("property" to it.property, "paths" to it.paths) },
            "actionArtifacts" to context.actionArtifacts.map { sortedMapOf("role" to it.role, "path" to it.path, "sha256" to it.sha256) },
        )
    }

    internal fun context(value: Any?): GenerationOriginContext {
        val row = generationObject(value, setOf("format", "version", "receiptSha256", "scope", "task", "variant", "outputRoot", "mappingPath", "actionPolicy", "providerBindings", "actionArtifacts"))
        require(row["format"] == "kartograph-generation-origin-context" && row["version"] == 1L) { "unsupported generation origin context" }
        return GenerationOriginContext(generationString(row["receiptSha256"]), generationString(row["scope"]), generationString(row["task"]),
            generationString(row["variant"]), generationString(row["outputRoot"]), generationString(row["mappingPath"]),
            generationArray(row["providerBindings"], 3).map { raw ->
                val binding = generationObject(raw, setOf("property", "paths"))
                GenerationProviderBinding(generationString(binding["property"]), generationArray(binding["paths"]).map(::generationString))
            }, generationString(row["actionPolicy"]), generationArray(row["actionArtifacts"], 256).map { raw ->
                val artifact = generationObject(raw, setOf("role", "path", "sha256"))
                InputFingerprint(generationString(artifact["role"]), generationString(artifact["path"]), generationString(artifact["sha256"]))
            })
    }
}

/** 현재 작업 상태는 역사적 receipt와 독립 필수 필드로 보존한다. */
internal object GenerationObservationCodec {
    fun value(observation: GenerationConsumptionObservation): Any {
        observation.requireValid()
        return sortedMapOf(
            "originContext" to GenerationOriginContextCodec.value(observation.originContext),
            "currentContext" to GenerationOriginContextCodec.value(observation.currentContext),
            "invocation" to observation.invocation, "currentAttempt" to observation.currentAttempt,
            "originBasis" to observation.originBasis, "currentGenerationStatus" to observation.currentGenerationStatus,
            "currentInventorySha256" to observation.currentInventorySha256, "currentDeclarationsSha256" to observation.currentDeclarationsSha256,
        )
    }

    fun parse(value: Any?): GenerationConsumptionObservation {
        val row = generationObject(value, setOf("originContext", "currentContext", "invocation", "currentAttempt", "originBasis", "currentGenerationStatus", "currentInventorySha256", "currentDeclarationsSha256"))
        return GenerationConsumptionObservation(GenerationOriginContextCodec.context(row["originContext"]), GenerationOriginContextCodec.context(row["currentContext"]),
            generationString(row["invocation"]), row["currentAttempt"]?.let(::generationString), generationString(row["originBasis"]),
            generationString(row["currentGenerationStatus"]), generationString(row["currentInventorySha256"]), generationString(row["currentDeclarationsSha256"]))
    }
}
