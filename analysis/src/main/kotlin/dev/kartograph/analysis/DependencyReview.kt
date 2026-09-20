package dev.kartograph.analysis

/** artifact cache 경로나 소스 줄 이동과 무관한 정확한 dependency 관찰 지문이다. */
public object DependencyReview {
    public fun fingerprint(finding: UnusedDependency): String = fingerprint(
        "unused-dependency", finding.coordinate, finding.scope.option, null, emptyList(),
    )

    public fun fingerprint(advice: DependencyAdvice): String = fingerprint(
        advice.ruleId, advice.coordinate, advice.scope?.option, advice.suggestedScope?.option, advice.evidence,
    )

    /** 구분 문자가 들어 있는 좌표도 충돌하지 않도록 길이를 함께 기록한다. */
    public fun fingerprint(rule: String, coordinate: String, scope: String?, suggested: String?, evidence: List<String>): String =
        "dependencies-v1|" + (listOf(rule, coordinate, scope.orEmpty(), suggested.orEmpty()) + evidence.distinct().sorted())
            .joinToString("") { "${it.length}:$it" }

    public fun fingerprints(result: DependencyAnalysisResult): List<String> =
        (result.findings.map(::fingerprint) + result.advice.map(::fingerprint)).distinct().sorted()

    /** 승인된 정확한 지문만 제외한다. expiry 판정과 파일 파싱은 호출자가 담당한다. */
    public fun apply(result: DependencyAnalysisResult, baseline: Set<String>, active: Set<String>, expiredCount: Int = 0): DependencyAnalysisResult {
        val observed = fingerprints(result).toSet()
        return result.copy(
            findings = result.findings.filterNot { fingerprint(it) in baseline || fingerprint(it) in active },
            advice = result.advice.filterNot { fingerprint(it) in baseline || fingerprint(it) in active },
            baselineSuppressedCount = observed.count { it in baseline },
            suppressionCount = observed.count { it !in baseline && it in active },
            expiredSuppressionCount = expiredCount,
        )
    }
}
