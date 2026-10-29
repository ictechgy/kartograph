package dev.kartograph.export

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputConfiguration
import dev.kartograph.core.ProcessorOutputReceipt
import dev.kartograph.core.ProcessorOutputs
import dev.kartograph.core.ProcessorCompilerInputs
import dev.kartograph.core.ProcessorDeclaration
import dev.kartograph.core.NodeId

/** standalone processor runner의 v2 receipt를 실행 없이 해석한다. */
public object ProcessorOutputCodec {
    /** 설정은 로컬 검증용이며 snapshot에 command·외부 절대경로를 저장하지 않는다. */
    public fun configuration(content: String): ProcessorOutputConfiguration {
        require(content.toByteArray().size <= 1024 * 1024) { "processor configuration exceeds limit" }
        val value = obj(SnapshotJsonParser(content).parse())
        return ProcessorOutputConfiguration(text(value["project"]), text(value["scope"]), text(value["kind"]), text(value["processor"]),
            text(value["collectorJar"]), text(value["processorJar"]), strings(value["inputs"]), strings(value["outputRoots"]),
            strings(value["command"]), text(value["token"]), text(value["observations"]), text(value["receipt"]), value["compilerInputs"]?.let(::text))
    }

    /** 기존 실험용 v1 receipt는 재수집하도록 거부한다. v2는 제품과 같은 content fingerprint를 사용한다. */
    public fun receipt(content: String): ProcessorOutputReceipt {
        require(content.toByteArray().size <= 32 * 1024 * 1024) { "processor receipt exceeds limit" }
        val value = obj(SnapshotJsonParser(content).parse())
        require(value["format"] == "kartograph-processor-output-witness" && value["version"] in setOf(2L, 3L) && value["buildExit"] == 0L) {
            "processor snapshot import requires a successful v2 or v3 receipt; recapture with the current runner"
        }
        val scope = text(value["scope"])
        val observed = observation(value["observation"], scope)
        require((value["version"] == 3L) == (observed.compilerInputs != null) && observed.declarations.isEmpty()) { "invalid processor receipt compiler input evidence" }
        return ProcessorOutputReceipt(scope, text(value["token"]), text(value["configurationSha256"]),
            list(value["inputs"]).map { raw -> obj(raw).let { InputFingerprint("processorInput", text(it["path"]), text(it["sha256"])) } },
            observed)
    }

    internal fun observation(raw: Any?, scope: String? = null): ProcessorOutputs {
        val value = obj(raw)
        return ProcessorOutputs(scope ?: text(value["scope"]), text(value["kind"]), text(value["processor"]),
            text(value["processorArtifact"]), text(value["collectorArtifact"]),
            list(value["outputs"]).map { item -> obj(item).let { ProcessorOutput(text(it["path"]), text(it["kind"]), text(it["observation"]), text(it["sha256"])) } },
            text(value["rawSha256"]), value["compilerInputs"]?.let(::compilerInputs),
            value["declarations"]?.let { items -> list(items).map { item -> obj(item).let {
                ProcessorDeclaration(text(it["output"]), NodeId(text(it["owner"])), strings(it["symbols"]).map(::NodeId))
            } } }.orEmpty())
    }

    internal fun value(item: ProcessorOutputs): Map<String, Any?> = sortedMapOf<String, Any?>(
        "scope" to item.scope, "kind" to item.kind, "processor" to item.processor,
        "processorArtifact" to item.processorArtifact, "collectorArtifact" to item.collectorArtifact,
        "rawSha256" to item.rawSha256,
        "outputs" to item.outputs.sortedBy { it.path }.map { output -> sortedMapOf(
            "path" to output.path, "kind" to output.kind, "observation" to output.observation, "sha256" to output.sha256) },
    ).apply {
        item.compilerInputs?.let { inputs -> put("compilerInputs", sortedMapOf(
            "coverage" to "gradle-declared-task-inputs", "complete" to false,
            "task" to inputs.task, "propertiesSha256" to inputs.propertiesSha256, "inventorySha256" to inputs.inventorySha256,
            "files" to inputs.files.map { sortedMapOf("role" to it.role, "path" to it.path, "sha256" to it.sha256) })) }
        if (item.declarations.isNotEmpty()) put("declarations", item.declarations.sortedBy { it.output }.map {
            sortedMapOf("output" to it.output, "owner" to it.owner.value, "symbols" to it.symbols.map(NodeId::value).sorted()) })
    }

    private fun compilerInputs(raw: Any?): ProcessorCompilerInputs = obj(raw).let { value ->
        require(value["coverage"] == "gradle-declared-task-inputs" && value["complete"] == false) { "unsupported compiler input coverage claim" }
        ProcessorCompilerInputs(text(value["task"]), list(value["files"]).map { file -> obj(file).let {
            InputFingerprint(text(it["role"]), text(it["path"]), text(it["sha256"]))
        } }, text(value["propertiesSha256"]), text(value["inventorySha256"]))
    }

    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()
    private fun text(value: Any?): String = value as? String ?: invalid()
    private fun list(value: Any?): List<*> = value as? List<*> ?: invalid()
    private fun strings(value: Any?): List<String> = list(value).map(::text)
    private fun invalid(): Nothing = throw IllegalArgumentException("processor evidence has invalid or missing fields")
}
