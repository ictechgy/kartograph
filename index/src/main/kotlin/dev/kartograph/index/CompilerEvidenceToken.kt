package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint

/** compiler 시작 요청과 완료 증거가 같은 입력 집합과 scope를 가리키는지 확인한다. */
public object CompilerEvidenceToken {
    /** 순서가 있는 지문과 실제 Gradle task identity를 토큰에 포함한다. 원시 옵션은 저장하지 않는다. */
    public fun create(scope: String, compiler: String, artifact: String, inputs: List<InputFingerprint>): String =
        ContentFingerprint.values(listOf("kartograph-compiler-inputs-v1", scope, compiler, artifact) +
            inputs.flatMap { listOf(it.role, it.path, it.sha256) })

    /** 외부에서 읽은 증거의 토큰도 해당 문서의 입력과 대응해야 한다. */
    public fun matches(witness: BuildWitness): Boolean = witness.evidenceToken ==
        create(witness.scope, witness.compiler, witness.artifact, witness.inputs)
}
