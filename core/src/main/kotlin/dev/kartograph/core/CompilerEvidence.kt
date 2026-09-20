package dev.kartograph.core

/** compiler가 관찰한 원본 파일의 상대 경로와 원시 바이트 SHA-256이다. */
public data class CompilerEvidenceSource(val path: String, val sha256: String) {
    init { InputFingerprint("sourceFile", path, sha256) }
}

/** 실행 호출과 혼동하지 않는 compiler의 선언 참조 종류다. */
public enum class CompilerReferenceKind { CONSTANT, BINDING }

/** JVM 선언 사이에서 compiler가 선택한 의미적 참조 한 쌍이다. */
public data class CompilerReference(val source: NodeId, val target: NodeId, val kind: CompilerReferenceKind)

/** 실제 JSR-269 Filer 호출을 거친 source와 해당 processor artifact의 관찰이다. */
public data class ProcessorGeneration(
    val processor: String,
    val artifactSha256: String,
    val sources: List<CompilerEvidenceSource>,
) {
    init {
        require(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*").matches(processor)) { "invalid processor identity" }
        require(processor.length <= 1024 && Regex("[0-9a-f]{64}").matches(artifactSha256)) { "invalid processor artifact" }
        require(sources.size <= 100_000 && sources.map { it.path }.distinct().size == sources.size) { "invalid processor output inventory" }
    }
}

/** 수집기 원시 출력이다. 이 값만으로 성공한 빌드나 현재 입력과의 대응을 증명하지 않는다. */
public data class CompilerEvidence(
    val collector: String,
    val compilerVersion: String,
    val inputToken: String,
    val artifactSha256: String,
    val sources: List<CompilerEvidenceSource>,
    val unmapped: Int,
    val references: List<CompilerReference>,
    val processorGeneration: ProcessorGeneration? = null,
) {
    init {
        require(collector in setOf("javac-constants", "kotlin-constants", "dagger-bindings", "javac-processors")) { "unsupported compiler evidence collector" }
        require((collector == "javac-processors") == (processorGeneration != null)) { "processor evidence identity is missing or misplaced" }
        require(processorGeneration == null || references.isEmpty() && unmapped == 0) { "processor attribution cannot contain reference edges" }
        require(processorGeneration?.sources.orEmpty().all { it in sources }) { "processor outputs must be observed compiler sources" }
        require(compilerVersion.length in 1..128 && Regex("[A-Za-z0-9._+\\-]+").matches(compilerVersion)) { "invalid compiler version" }
        require(Regex("[0-9a-f]{64}").matches(inputToken) && Regex("[0-9a-f]{64}").matches(artifactSha256)) { "invalid compiler evidence fingerprint" }
        require(unmapped >= 0) { "invalid unmapped reference count" }
        require(sources.map { it.path }.distinct().size == sources.size) { "duplicate compiler source inventory" }
    }
}
