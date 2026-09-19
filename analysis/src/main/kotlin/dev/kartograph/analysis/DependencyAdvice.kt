package dev.kartograph.analysis

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import dev.kartograph.core.DependencyUsage

/** 선언 변경의 방향과 그 근거를 표현하며 빌드 파일을 자동 수정하지 않는다. */
public data class DependencyAdvice(
    val coordinate: String,
    val scope: DependencyScope?,
    val artifact: String,
    val ruleId: String,
    val suggestedScope: DependencyScope?,
    val evidence: List<String>,
)

/** 선언 scope·컴파일 classpath 소유권·실제 ABI 참조를 함께 대조한다. */
public object DependencyConfigurationAnalysis {
    private val tests = setOf(DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY)
    private val mainScopes = DependencyFindings.ANALYZED_SCOPES - tests

    /** resolved 목록이 없으면 미선언 전이 의존성을 추측하지 않고 선언된 항목만 분석한다. */
    public fun analyze(
        declared: Collection<DeclaredDependency>,
        main: DependencyUsage,
        test: DependencyUsage?,
        artifactClasses: Map<String, Set<String>>,
        resolved: Collection<DeclaredDependency>? = null,
        apiAdvice: Boolean = true,
    ): DependencyAnalysisResult {
        val declarations = declared.distinct()
        val universe = (declarations + resolved.orEmpty()).distinct()
        val owners = mutableMapOf<String, MutableSet<String>>()
        universe.filter { it.scope in DependencyFindings.ANALYZED_SCOPES }.forEach { dependency ->
            artifactClasses[dependency.artifact].orEmpty().forEach { name ->
                owners.getOrPut(name) { mutableSetOf() }.add(dependency.coordinate)
            }
        }
        val local = main.declaredClasses + test?.declaredClasses.orEmpty()
        val ambiguous = owners.filter { (name, coordinates) -> coordinates.size > 1 || name in local }.keys
        val unused = mutableListOf<UnusedDependency>()
        val advice = mutableListOf<DependencyAdvice>()
        var analyzed = 0
        var skipped = 0
        var empty = 0
        for (unit in declarations.groupBy { it.coordinate to it.scope }.values) {
            val dependency = unit.minBy { it.artifact }
            val usage = if (dependency.scope in tests) test else main
            if (usage == null || dependency.scope !in DependencyFindings.ANALYZED_SCOPES) { skipped++; continue }
            val classes = unit.flatMap { artifactClasses[it.artifact].orEmpty() }.toSet()
            if (classes.isEmpty()) { empty++; continue }
            analyzed++
            val used = classes.intersect(usage.referencedClasses)
            // test에서 확인한 사용을 삭제 후보로 바꾸지 않는다. main API 판정에는 섞지 않는다.
            if (used.isEmpty() && (dependency.scope in tests || classes.none { it in test?.referencedClasses.orEmpty() })) {
                if (usage.apiComplete && classes.none(ambiguous::contains)) {
                    unused += UnusedDependency(dependency.coordinate, dependency.scope, dependency.artifact)
                }
                continue
            }
            if (!apiAdvice || dependency.scope in tests || used.isEmpty() || classes.any(ambiguous::contains)) continue
            val exposed = classes.intersect(main.apiClasses)
            val desired = when {
                dependency.scope == DependencyScope.IMPLEMENTATION && exposed.isNotEmpty() -> DependencyScope.API
                dependency.scope == DependencyScope.API && exposed.isEmpty() && main.apiComplete -> DependencyScope.IMPLEMENTATION
                else -> null
            } ?: continue
            val evidence = exposed.ifEmpty { used }
            val evidenceArtifact = unit.filter { artifactClasses[it.artifact].orEmpty().any(evidence::contains) }.minBy { it.artifact }.artifact
            advice += DependencyAdvice(dependency.coordinate, dependency.scope, evidenceArtifact,
                "dependency-scope-mismatch", desired, evidence.sorted())
        }
        if (resolved != null) {
            undeclared(resolved, declarations, main, false, apiAdvice, artifactClasses, ambiguous, advice)
            if (test != null) undeclared(resolved, declarations, test, true, apiAdvice, artifactClasses, ambiguous, advice)
        }
        return DependencyAnalysisResult(unused.sortedWith(compareBy({ it.coordinate }, { it.scope.option }, { it.artifact })),
            analyzed, skipped, empty,
            advice.distinct().sortedWith(compareBy({ it.coordinate }, { it.ruleId }, { it.suggestedScope?.option.orEmpty() }, { it.artifact })),
            ambiguous.size)
    }

    private fun moduleKey(coordinate: String): String {
        val parts = coordinate.split(':')
        return if (!coordinate.startsWith("project:") && !coordinate.startsWith("file:") && parts.size >= 3)
            parts.take(2).joinToString(":") else coordinate
    }

    private fun undeclared(
        resolved: Collection<DeclaredDependency>, declared: List<DeclaredDependency>, usage: DependencyUsage,
        test: Boolean, apiAdvice: Boolean, classes: Map<String, Set<String>>, ambiguous: Set<String>, advice: MutableList<DependencyAdvice>,
    ) {
        val allowed = if (test) DependencyFindings.ANALYZED_SCOPES else mainScopes
        val declaredCoordinates = declared.filter { it.scope in allowed }.mapTo(mutableSetOf()) { moduleKey(it.coordinate) }
        resolved.filter { it.scope in allowed && moduleKey(it.coordinate) !in declaredCoordinates }
            .groupBy { it.coordinate }.toSortedMap().forEach { (coordinate, artifacts) ->
                if (test && advice.any { it.coordinate == coordinate && it.ruleId == "undeclared-dependency" && it.suggestedScope !in tests }) {
                    return@forEach
                }
                val observed = artifacts.flatMap { classes[it.artifact].orEmpty() }.toSet()
                    .intersect(usage.referencedClasses) - ambiguous - usage.declaredClasses
                if (observed.isEmpty()) return@forEach
                val suggested = when {
                    test -> DependencyScope.TEST_IMPLEMENTATION
                    apiAdvice && observed.any(usage.apiClasses::contains) -> DependencyScope.API
                    apiAdvice && !usage.apiComplete -> null
                    else -> DependencyScope.IMPLEMENTATION
                }
                val artifact = artifacts.filter { classes[it.artifact].orEmpty().any(observed::contains) }
                    .minBy { it.artifact }.artifact
                advice += DependencyAdvice(coordinate, null, artifact, "undeclared-dependency", suggested, observed.sorted())
            }
    }
}
