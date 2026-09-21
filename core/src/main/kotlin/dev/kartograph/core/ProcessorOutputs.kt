package dev.kartograph.core

/** API로 생성한 출력과 callback 범위에서 관찰한 직접 파일 변경을 구분한다. */
public data class ProcessorOutput(val path: String, val kind: String, val observation: String, val sha256: String) {
    init {
        InputFingerprint("processorOutput", path, sha256)
        require(path.split('/').none { it == "." }) { "invalid processor output path" }
        require(kind to observation in setOf("source" to "api", "class" to "api", "resource" to "api", "file" to "callback-scope")) {
            "invalid processor output observation"
        }
    }
}

/** 명시된 입력과 성공한 명령에 대응한 관찰이다. 그래프 간선이나 자동 보존을 추가하지 않는다. */
public data class ProcessorOutputs(
    val scope: String, val kind: String, val processor: String,
    val processorArtifact: String, val collectorArtifact: String,
    val outputs: List<ProcessorOutput>, val rawSha256: String,
    val compilerInputs: ProcessorCompilerInputs? = null,
    val declarations: List<ProcessorDeclaration> = emptyList(),
) {
    init {
        require(Regex("[A-Za-z0-9_.:-]{1,200}").matches(scope) && kind in setOf("javac", "kapt", "ksp")) { "invalid processor scope" }
        ProcessorGeneration(processor, processorArtifact, emptyList())
        InputFingerprint("collector", "collector.jar", collectorArtifact)
        InputFingerprint("observations", "outputs.tsv", rawSha256)
        require(outputs.size <= 100_000 && outputs.map { it.path }.distinct().size == outputs.size) { "invalid processor output inventory" }
        require(declarations.size <= 100_000 && declarations.map { it.output }.distinct().size == declarations.size) { "invalid processor declaration inventory" }
        require(declarations.all { declaration -> outputs.any { it.path == declaration.output && it.kind == "class" && it.observation == "api" } }) { "processor declarations require observed class outputs" }
        require(declarations.isEmpty() || compilerInputs != null) { "processor declarations require compiler input evidence" }
    }
}

/** 별도 runner의 완료 receipt이며 compiler 전체 입력이나 생산자 인증을 뜻하지 않는다. */
public data class ProcessorOutputReceipt(
    val scope: String, val token: String, val configurationSha256: String,
    val inputs: List<InputFingerprint>, val observation: ProcessorOutputs,
) {
    init {
        require(scope == observation.scope && inputs.isNotEmpty() && inputs.size <= 100_002) { "invalid processor receipt scope or inputs" }
        require(inputs.map { it.path }.distinct().size == inputs.size) { "duplicate processor receipt inputs" }
        InputFingerprint("token", "token", token)
        InputFingerprint("configuration", "config", configurationSha256)
    }
}

/** 읽기 전용 검증에 쓰는 로컬 설정이다. command는 fingerprint에만 쓰며 실행하지 않는다. */
public data class ProcessorOutputConfiguration(
    val project: String, val scope: String, val kind: String, val processor: String,
    val collectorJar: String, val processorJar: String,
    val inputs: List<String>, val outputRoots: List<String>, val command: List<String>,
    val token: String, val observations: String, val receipt: String,
    val compilerInputs: String? = null,
) {
    init {
        require(inputs.isNotEmpty() && outputRoots.isNotEmpty() && command.isNotEmpty() && command.all { it.isNotEmpty() }) { "processor configuration requires explicit inputs, outputs and command" }
        require(inputs.size <= 100_000 && outputRoots.size <= 100_000 && command.size <= 10_000) { "processor configuration exceeds limits" }
        require(inputs.distinct().size == inputs.size && inputs.none { it in setOf("external/collectorJar", "external/processorJar") }) { "duplicate processor configuration inputs" }
        val controls = listOfNotNull(token, observations, receipt, compilerInputs, compilerInputs?.plus(".pending"))
        require(controls.distinct().size == controls.size) { "processor control paths must be distinct" }
        (inputs + outputRoots + controls).forEach {
            InputFingerprint("path", it, "0".repeat(64))
            require(it.split('/').none { part -> part == "." }) { "invalid processor configuration path" }
        }
    }

    /** 리스트 길이를 포함해 배열 경계를 보존하는 v2 content fingerprint 입력이다. */
    public fun contractValues(): List<String> = listOf("processor-output-config-v2", scope, kind, processor) +
        listOf(inputs.size.toString()) + inputs + listOf(outputRoots.size.toString()) + outputRoots +
        listOf(command.size.toString()) + command + listOf(token, observations, receipt) +
        (compilerInputs?.let { listOf("gradle-declared-inputs-v1", it) } ?: emptyList())
}

/** 선택한 Gradle task가 선언한 파일 전체와 값 속성의 지문이다. 숨은 프로세스 IO를 뜻하지 않는다. */
public data class ProcessorCompilerInputs(
    val task: String, val files: List<InputFingerprint>, val propertiesSha256: String, val inventorySha256: String,
) {
    init {
        require(Regex("(:[A-Za-z0-9_.-]+)+").matches(task) && task.length <= 1024) { "invalid compiler task identity" }
        require(files.size <= 100_000 && files.map { it.path }.distinct().size == files.size) { "invalid compiler task input inventory" }
        require(files.all { it.role in setOf("processorCompilerInput", "file-watch") }) { "invalid compiler task input role" }
        require(files.all { it.path.split('/').none { part -> part == "." } &&
            (it.path.startsWith("project/") || Regex("external/compiler-input-[0-9]+").matches(it.path)) }) { "invalid compiler task input identity" }
        InputFingerprint("properties", "properties", propertiesSha256)
        InputFingerprint("observations", "compiler-inputs.tsv", inventorySha256)
    }
}

/** 바이트가 동일한 실제 JVM class의 선언 귀속이다. 호출·보존 관계를 추가하지 않는다. */
public data class ProcessorDeclaration(val output: String, val owner: NodeId, val symbols: List<NodeId>) {
    init {
        InputFingerprint("processorOutput", output, "0".repeat(64))
        require(symbols.isNotEmpty() && symbols.size <= 100_000 && symbols.distinct().size == symbols.size && owner in symbols) { "invalid processor declaration symbols" }
        require(owner.value.startsWith("class:") && symbols.all { it == owner ||
            it.value.startsWith("method:" + owner.value.removePrefix("class:") + "#") ||
            it.value.startsWith("field:" + owner.value.removePrefix("class:") + "#") }) { "processor symbols belong to another class" }
    }
}
