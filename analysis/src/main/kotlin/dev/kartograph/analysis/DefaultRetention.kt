package dev.kartograph.analysis

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.KeepRule
import dev.kartograph.core.RetentionEvidence

/** CLI와 Gradle plugin이 같은 보존 판정을 사용하도록 기본 정책을 한곳에서 조립한다. */
public object DefaultRetention {
    /** 입력 adapter가 찾은 근거에 keep·annotation·runtime 보존 정책을 더해 완성된 root를 반환한다. */
    public fun find(
        graph: CodeGraph,
        inputEvidence: Iterable<RetentionEvidence>,
        keepRules: Iterable<KeepRule>,
        classHierarchy: ClassHierarchy = ClassHierarchy.EMPTY,
        includePrivateMembers: Boolean = false,
    ): List<RetentionEvidence> {
        val evidence = buildList {
            addAll(inputEvidence)
            addAll(KeepRuleRetention.find(graph, keepRules, classHierarchy))
            addAll(KeepAnnotationRetention.find(graph))
            addAll(FrameworkAnnotationRetention.find(graph))
            addAll(GeneratedSiblingRetention.find(graph))
            addAll(AndroidEntryPointRetention.find(graph, classHierarchy))
            addAll(InlineConstantRetention.find(graph))
        }
        val runtimeEvidence = RuntimeRetentionExpansion.expand(graph, evidence)
        return if (includePrivateMembers) PrivateMemberRetention.expand(graph, runtimeEvidence) else runtimeEvidence
    }
}
