package dev.kartograph.core

/** 순서가 의미 있는 입력 한 개의 내용 지문이다. 외부 경로는 호출자가 다시 연결하는 슬롯으로 표현한다. */
public data class InputFingerprint(val role: String, val path: String, val sha256: String) {
    init {
        require(Regex("[a-zA-Z][a-zA-Z0-9-]*").matches(role)) { "invalid fingerprint role" }
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
            path.split('/').none { it == ".." || it.isEmpty() } && path.none { it.code < 32 }) { "invalid portable input identity" }
        require(Regex("[0-9a-f]{64}").matches(sha256)) { "invalid content fingerprint" }
    }
}

/** 지원 compiler task의 성공한 입력/출력 대응 기록이다. 악의적인 생산자의 인증서는 아니다. */
public data class BuildWitness(
    val scope: String,
    val compiler: String,
    val artifact: String,
    val inputs: List<InputFingerprint>,
    val outputs: List<InputFingerprint>,
    val compilerEvidence: List<InputFingerprint> = emptyList(),
    val evidenceToken: String? = null,
) {
    init {
        require(listOf(scope, compiler, artifact).all { it.isNotEmpty() && it.length <= 200 && Regex("[A-Za-z0-9_.:-]+").matches(it) }) {
            "invalid build identity"
        }
        require(inputs.isNotEmpty() && outputs.isNotEmpty()) { "missing compiler evidence" }
        require(inputs.any { it.role == "sources" } && inputs.any { it.role == "buildConfig" } &&
            inputs.any { it.role == "compiler" } && inputs.any { it.role == "options" }) { "incomplete compiler evidence" }
        require(outputs.all { it.role == "classes" }) { "invalid compiler outputs" }
        require(compilerEvidence.all { it.role in setOf("compilerEvidence", "compilerGeneratedSource") } &&
            compilerEvidence.map { it.path }.distinct().size == compilerEvidence.size) { "invalid compiler evidence receipts" }
        require(if (compilerEvidence.isEmpty()) evidenceToken == null else
            evidenceToken != null && Regex("[0-9a-f]{64}").matches(evidenceToken)) { "compiler evidence receipts require an input token" }
    }
}

/** 그래프 생성 시 관찰한 입력과 컴파일 증거를 함께 고정한다. null provenance는 이전 미검증 문서다. */
public data class SnapshotProvenance(val inputs: List<InputFingerprint>, val witnesses: List<BuildWitness>)
