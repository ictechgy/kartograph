package dev.kartograph.core

import java.security.MessageDigest

/** 공개 provider 속성이 가리킨 전체 portable 경로 목록이다. live 객체 검증을 대신하지 않는다. */
public data class GenerationProviderBinding(val property: String, val paths: List<String>) {
    init {
        require(property in setOf("navigationFiles", "outputDir", "incrementalFolder")) { "unsupported generation provider property" }
        require(paths.size in 1..GenerationReceipt.MAX_ROWS && paths.toSet().size == paths.size) { "invalid generation provider paths" }
        paths.forEach(::generationPath)
        require(property == "navigationFiles" || paths.size == 1) { "generation directory provider must have one binding" }
    }
}

/** 원래 성공 시 저장하는 관측 문맥이다. 과거 receipt에서 누락된 문맥을 소급 추측하지 않는다. */
public data class GenerationOriginContext(
    val receiptSha256: String, val scope: String, val task: String, val variant: String,
    val outputRoot: String, val mappingPath: String, val providerBindings: List<GenerationProviderBinding>,
    val actionPolicy: String, val actionArtifacts: List<InputFingerprint>,
) {
    init { requireValid() }

    public fun requireValid() {
        generationHash(receiptSha256); generationScope(scope); generationTask(task)
        require(variant.length in 1..200 && Regex("[A-Za-z0-9_.-]+").matches(variant)) { "invalid generation variant" }
        generationPath(outputRoot); generationPath(mappingPath)
        require(actionPolicy == ACTION_POLICY) { "unsupported generation action policy" }
        require(providerBindings.size == 3 && providerBindings.map { it.property }.toSet().size == 3) { "incomplete generation provider bindings" }
        require(providerBindings.sumOf { it.paths.size.toLong() } <= GenerationReceipt.MAX_ROWS) { "generation provider bindings exceed row limit" }
        providerBindings.forEach { it.copy() }
        val byProperty = providerBindings.associate { it.property to it.paths }
        require(byProperty["outputDir"] == listOf(outputRoot) && '/' in mappingPath &&
            byProperty["incrementalFolder"] == listOf(mappingPath.substringBeforeLast('/'))) { "generation provider roots do not match context" }
        require(actionArtifacts.size in 1..GenerationReceipt.MAX_ARTIFACTS && actionArtifacts.map { it.path }.toSet().size == actionArtifacts.size) {
            "invalid generation action artifact inventory"
        }
        actionArtifacts.forEach { require(it.role == "generatorActionArtifact") { "invalid generation action artifact role" }; generationPath(it.path); generationHash(it.sha256) }
    }

    public companion object {
        public const val ACTION_POLICY: String = "safeargs-java-2.10.2-default-owned-hooks-v1"
    }
}

/** 현재 작업 결과와 역사적 생성 출처를 분리한다. 지원 밖 상태는 adapter의 unknown 진단으로 남긴다. */
public data class GenerationConsumptionObservation(
    val originContext: GenerationOriginContext, val currentContext: GenerationOriginContext,
    val invocation: String, val currentAttempt: String?, val originBasis: String, val currentGenerationStatus: String,
    val currentInventorySha256: String, val currentDeclarationsSha256: String,
) {
    init { requireValid() }

    public fun requireValid() {
        originContext.requireValid(); currentContext.requireValid()
        generationHash(invocation); currentAttempt?.let(::generationHash)
        generationHash(currentInventorySha256); generationHash(currentDeclarationsSha256)
        require(GenerationDigests.context(originContext) == GenerationDigests.context(currentContext)) { "generation context changed" }
        require((originBasis == "current-cold-success" && currentGenerationStatus == "SUCCEEDED" && currentAttempt != null) ||
            (originBasis == "historical-byte-equivalence" && currentGenerationStatus == "UP_TO_DATE" && currentAttempt == null)) {
            "unsupported generation consumption state"
        }
    }
}

/** 버전 고정 SHA256: domain과 각 UTF-8 값의 4-byte big-endian 길이를 포함한다. JSON 문자열 hash가 아니다. */
public object GenerationDigests {
    public fun receipt(receipt: GenerationReceipt): String = digest("generation-receipt-v1") {
        text(receipt.attempt); text(inventory(receipt))
    }

    /** 현재 재검증한 전체 receipt 내용에서 역사적 attempt만 제외한 digest다. */
    public fun inventory(receipt: GenerationReceipt): String {
        receipt.requireValid()
        return digest("generation-inventory-v1") {
            text(receipt.scope); text(receipt.task); text(receipt.policy); text(receipt.outputRoot)
            fingerprints(receipt.inputs); fingerprints(listOf(receipt.mapping)); fingerprints(receipt.outputs)
            rows(receipt.sources.map { listOf(it.input, it.path, it.qualifiedName, it.sha256) })
        }
    }

    public fun context(context: GenerationOriginContext): String {
        context.requireValid()
        return digest("generation-origin-context-v1") {
            text(context.receiptSha256); text(context.scope); text(context.task); text(context.variant)
            text(context.outputRoot); text(context.mappingPath); text(context.actionPolicy)
            rows(context.providerBindings.map { listOf(it.property) + it.paths.sorted() }); fingerprints(context.actionArtifacts)
        }
    }

    /** source→class 연결과 현재 compiler/선택 artifact 증거 전체를 묶는다. live byte 대조는 adapter 책임이다. */
    public fun declarations(declarations: List<GeneratedDeclaration>, provenance: SnapshotProvenance): String = digest("generation-declarations-v1") {
        rows(declarations.map { row ->
            listOf(row.scope, row.generatorTask, row.attempt, row.compilerTask, row.source, row.sourceSha256,
                row.owner.value, row.originalRoot, row.selectedRoot, row.classPath, row.classSha256) + row.symbols.map { it.value }.sorted()
        })
        fingerprints(provenance.inputs); rows(provenance.memberScopes.map { listOf(it) })
        rows(provenance.witnesses.map { witness -> listOf(digest("generation-compiler-witness-v1") {
            text(witness.scope); text(witness.compiler); text(witness.artifact); text(witness.evidenceToken.orEmpty())
            fingerprints(witness.inputs); fingerprints(witness.outputs); fingerprints(witness.compilerEvidence)
        }) })
        rows(provenance.transformations.map { transform -> listOf(digest("generation-transform-observation-v1") {
            text(transform.scope); text(transform.observerTask); text(transform.policy)
            fingerprints(transform.originalOutputs); fingerprints(transform.selectedOutputs); fingerprints(transform.passthroughOutputs)
            rows(transform.changedClasses.map { listOf(it.name, it.beforeSha256, it.afterSha256) })
        }) })
    }

    private fun digest(domain: String, block: Sink.() -> Unit): String = Sink().apply { text(domain); block() }.finish()

    private class Sink {
        private val hash = MessageDigest.getInstance("SHA-256")
        fun text(value: String) {
            require(value.codePoints().allMatch { it !in 0xD800..0xDFFF }) { "generation digest contains invalid Unicode" }
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            hash.update(bytes)
        }
        fun fingerprints(values: List<InputFingerprint>) = rows(values.map { listOf(it.role, it.path, it.sha256) })
        fun rows(values: List<List<String>>) {
            val sorted = values.sortedWith { a, b ->
                var result = 0
                for (index in 0 until minOf(a.size, b.size)) {
                    result = a[index].compareTo(b[index])
                    if (result != 0) break
                }
                if (result == 0) a.size.compareTo(b.size) else result
            }
            text(sorted.size.toString())
            sorted.forEach { row -> text(row.size.toString()); row.forEach(::text) }
        }
        fun finish(): String = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
