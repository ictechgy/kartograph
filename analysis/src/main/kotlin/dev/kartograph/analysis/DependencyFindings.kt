package dev.kartograph.analysis

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope

/** 선언은 됐지만 색인된 bytecode가 참조하지 않는 dependency 하나다. */
public data class UnusedDependency(
    val coordinate: String,
    val scope: DependencyScope,
    val artifact: String,
)

/** unused 판정과 판정하지 못한 입력 수를 함께 운반한다. */
public data class DependencyAnalysisResult(
    val findings: List<UnusedDependency>,
    val analyzedCount: Int,
    val skippedCount: Int,
    val withoutClassCount: Int,
    val advice: List<DependencyAdvice> = emptyList(),
    val ambiguousClassCount: Int = 0,
)

/** 선언 의존성과 classfile 참조를 대조해 unused 후보를 파생한다. */
public object DependencyFindings {
    /** compile classpath에서 bytecode 참조로 판정 가능한 scope다. */
    public val ANALYZED_SCOPES: Set<DependencyScope> = setOf(
        DependencyScope.API,
        DependencyScope.IMPLEMENTATION,
        DependencyScope.COMPILE_ONLY,
        DependencyScope.COMPILE_ONLY_API,
        DependencyScope.TEST_IMPLEMENTATION,
        DependencyScope.TEST_COMPILE_ONLY,
    )

    /** test class root를 주지 않으면 test scope를 판정하지 않는다(보수적 skip). */
    public fun judgedScopes(includeTestScopes: Boolean): Set<DependencyScope> =
        if (includeTestScopes) ANALYZED_SCOPES else ANALYZED_SCOPES - TEST_SCOPES

    private val TEST_SCOPES = setOf(DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY)

    /** artifact의 class 중 참조가 하나도 없으면 unused로 보고하고, 판정 불가 입력은 따로 센다. */
    public fun find(
        declared: Iterable<DeclaredDependency>,
        referencedClasses: Set<String>,
        artifactClasses: Map<String, Set<String>>,
        includeTestScopes: Boolean = true,
    ): DependencyAnalysisResult {
        val judged = judgedScopes(includeTestScopes)
        var analyzed = 0
        var skipped = 0
        var withoutClasses = 0
        val findings = mutableListOf<UnusedDependency>()
        declared.distinct().forEach { dependency ->
            if (dependency.scope !in judged) {
                skipped++
                return@forEach
            }
            val classes = artifactClasses[dependency.artifact]
            if (classes.isNullOrEmpty()) {
                withoutClasses++
                return@forEach
            }
            analyzed++
            if (classes.none(referencedClasses::contains)) {
                findings += UnusedDependency(dependency.coordinate, dependency.scope, dependency.artifact)
            }
        }
        return DependencyAnalysisResult(
            findings.sortedWith(compareBy({ it.coordinate }, { it.scope.option }, { it.artifact })),
            analyzed,
            skipped,
            withoutClasses,
        )
    }
}
