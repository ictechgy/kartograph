package dev.kartograph.index

import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.core.InputFingerprint
import java.nio.file.Path

/** 저장된 사실을 재해석하지 않고 현재 파일과 빌드 증거의 일치만 비교한다. */
public object ProvenanceVerifier {
    /** matched는 내용과 지원 lifecycle 기록의 대응이며 빌드 생산자 인증이나 런타임 완전성은 아니다. */
    public data class Result(val status: String, val reasons: List<String>)

    /** 외부 입력은 문서의 external 슬롯에 명시적으로 다시 연결해야 한다. */
    public fun verify(provenance: SnapshotProvenance?, project: Path, scope: String?, external: Map<String, Path> = emptyMap()): Result {
        if (provenance == null) return Result("unverified", listOf("legacy-snapshot"))
        val reasons = mutableListOf<String>()
        val root = project.toAbsolutePath().normalize()
        fun locate(input: InputFingerprint): Path? =
            (if (input.path.startsWith("external/")) external[input.path] else root.resolve(input.path))?.toAbsolutePath()?.normalize()
        val inputs = provenance.inputs + provenance.witnesses.flatMap { it.inputs + it.outputs }
        inputs.filter { it.role != "options" }.distinct().forEach { input ->
            val path = locate(input)
            if (path == null) reasons += "missing-external-input"
            else try {
                if (ContentFingerprint.hash(path, input.role == "sources") != input.sha256) reasons += "changed-${input.role}"
            } catch (_: java.io.IOException) { reasons += "unavailable-${input.role}" }
            catch (_: IllegalArgumentException) { reasons += "unavailable-${input.role}" }
        }
        if (reasons.isNotEmpty()) return Result("stale", reasons.distinct().sorted())
        if (provenance.witnesses.isEmpty()) reasons += "missing-build-witness"
        if (scope == null || provenance.witnesses.any { it.scope != scope }) reasons += "build-scope-mismatch"
        val classes = provenance.inputs.filter { it.role == "classes" }
        val outputs = provenance.witnesses.flatMap { it.outputs }
        if (classes.isEmpty() || classes.any { selected -> outputs.none { output ->
            selected.sha256 == output.sha256 && locate(selected) == locate(output)
        } }) reasons += "unwitnessed-class-root"
        if (provenance.inputs.count { it.role == "witness" } != provenance.witnesses.size) reasons += "missing-witness-file"
        return Result(if (reasons.isEmpty()) "matched" else "unverified", reasons.distinct().sorted())
    }
}
