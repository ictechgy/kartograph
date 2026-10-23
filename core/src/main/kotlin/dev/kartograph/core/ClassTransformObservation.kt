package dev.kartograph.core

/** 원본 compiler 출력과 선택한 AGP artifact의 검증된 바이트 관계다. transform 실행 인증서는 아니다. */
public data class ClassTransformObservation(
    val scope: String,
    val observerTask: String,
    val policy: String,
    val originalOutputs: List<InputFingerprint>,
    val selectedOutputs: List<InputFingerprint>,
    val changedClasses: List<TransformedClass>,
    val passthroughOutputs: List<InputFingerprint> = emptyList(),
) {
    init {
        require(listOf(scope, observerTask).all { it.length in 1..200 && Regex("[A-Za-z0-9_.:-]+").matches(it) }) {
            "invalid artifact observation identity"
        }
        require(policy == "hilt-superclass-v1") { "unsupported class transformation policy" }
        require(originalOutputs.isNotEmpty() && selectedOutputs.isNotEmpty() &&
            (originalOutputs + selectedOutputs + passthroughOutputs).all { it.role == "classes" }) { "invalid artifact observation roots" }
        require((originalOutputs + passthroughOutputs).map { it.path }.distinct().size == originalOutputs.size + passthroughOutputs.size &&
            selectedOutputs.map { it.path }.distinct().size == selectedOutputs.size) { "duplicate artifact observation roots" }
        require(changedClasses.size in 1..100_000 && changedClasses.map { it.name }.distinct().size == changedClasses.size) {
            "invalid changed class inventory"
        }
    }
}

/** 허용된 superclass 재작성의 양 끝 원시 classfile 해시를 남겨 일반 수정과 구분한다. */
public data class TransformedClass(val name: String, val beforeSha256: String, val afterSha256: String) {
    init {
        require(name.length in 1..1024 && !name.startsWith('/') && name.split('/').none { it.isEmpty() || it == ".." } &&
            name.none { it.code < 32 || it in "\\:.;[" }) { "invalid transformed class identity" }
        require(listOf(beforeSha256, afterSha256).all { Regex("[0-9a-f]{64}").matches(it) }) { "invalid transformed class hash" }
    }
}
