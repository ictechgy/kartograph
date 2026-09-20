package dev.kartograph.export

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputConfiguration
import dev.kartograph.core.ProcessorOutputReceipt
import dev.kartograph.core.ProcessorOutputs

/** standalone processor runner의 v2 receipt를 실행 없이 해석한다. */
public object ProcessorOutputCodec {
    /** 설정은 로컬 검증용이며 snapshot에 command·외부 절대경로를 저장하지 않는다. */
    public fun configuration(content: String): ProcessorOutputConfiguration {
        require(content.toByteArray().size <= 1024 * 1024) { "processor configuration exceeds limit" }
        val value = obj(SnapshotJsonParser(content).parse())
        return ProcessorOutputConfiguration(text(value["project"]), text(value["scope"]), text(value["kind"]), text(value["processor"]),
            text(value["collectorJar"]), text(value["processorJar"]), strings(value["inputs"]), strings(value["outputRoots"]),
            strings(value["command"]), text(value["token"]), text(value["observations"]), text(value["receipt"]))
    }

    /** 기존 실험용 v1 receipt는 재수집하도록 거부한다. v2는 제품과 같은 content fingerprint를 사용한다. */
    public fun receipt(content: String): ProcessorOutputReceipt {
        require(content.toByteArray().size <= 32 * 1024 * 1024) { "processor receipt exceeds limit" }
        val value = obj(SnapshotJsonParser(content).parse())
        require(value["format"] == "kartograph-processor-output-witness" && value["version"] == 2L && value["buildExit"] == 0L) {
            "processor snapshot import requires a successful v2 receipt; recapture with the current runner"
        }
        val scope = text(value["scope"])
        return ProcessorOutputReceipt(scope, text(value["token"]), text(value["configurationSha256"]),
            list(value["inputs"]).map { raw -> obj(raw).let { InputFingerprint("processorInput", text(it["path"]), text(it["sha256"])) } },
            observation(value["observation"], scope))
    }

    internal fun observation(raw: Any?, scope: String? = null): ProcessorOutputs {
        val value = obj(raw)
        return ProcessorOutputs(scope ?: text(value["scope"]), text(value["kind"]), text(value["processor"]),
            text(value["processorArtifact"]), text(value["collectorArtifact"]),
            list(value["outputs"]).map { item -> obj(item).let { ProcessorOutput(text(it["path"]), text(it["kind"]), text(it["observation"]), text(it["sha256"])) } },
            text(value["rawSha256"]))
    }

    internal fun value(item: ProcessorOutputs): Map<String, Any?> = sortedMapOf(
        "scope" to item.scope, "kind" to item.kind, "processor" to item.processor,
        "processorArtifact" to item.processorArtifact, "collectorArtifact" to item.collectorArtifact,
        "rawSha256" to item.rawSha256,
        "outputs" to item.outputs.sortedBy { it.path }.map { output -> sortedMapOf(
            "path" to output.path, "kind" to output.kind, "observation" to output.observation, "sha256" to output.sha256) },
    )

    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()
    private fun text(value: Any?): String = value as? String ?: invalid()
    private fun list(value: Any?): List<*> = value as? List<*> ?: invalid()
    private fun strings(value: Any?): List<String> = list(value).map(::text)
    private fun invalid(): Nothing = throw IllegalArgumentException("processor evidence has invalid or missing fields")
}
