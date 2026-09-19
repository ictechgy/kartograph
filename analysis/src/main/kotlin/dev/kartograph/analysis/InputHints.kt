package dev.kartograph.analysis

import dev.kartograph.core.InputHint

/**
 * 색인된 그래프에 들어가지 않은 보존 입력을 판정한다.
 * 개수는 호출자가 실제로 전달한 입력(파일·경로·manifest 근거) 수이고, 0이면 그 입력의 보존 효과를
 * 확인하지 못했다는 뜻이다. 어느 쪽도 finding이 아니므로 호출자가 보고 대상을 결정한다.
 */
public object InputHints {
    public fun detect(
        keepRuleInputs: Int,
        classpathInputs: Int,
        manifestEvidenceCount: Int,
    ): List<InputHint> = buildList {
        if (keepRuleInputs == 0) add(InputHint.MISSING_KEEP_RULES)
        if (classpathInputs == 0) add(InputHint.MISSING_CLASSPATH)
        if (manifestEvidenceCount == 0) add(InputHint.MANIFEST_WITHOUT_COMPONENTS)
    }
}
