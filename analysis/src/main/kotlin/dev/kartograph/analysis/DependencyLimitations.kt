package dev.kartograph.analysis

import dev.kartograph.core.DependencyUsage

/** 동일한 분석 한계를 CLI와 Gradle의 모든 보고 형식에 전달한다. */
public object DependencyLimitations {
    public fun describe(
        main: DependencyUsage, test: DependencyUsage?, result: DependencyAnalysisResult, resolvedProvided: Boolean,
    ): List<String> = buildList {
        add("only references from supplied compiled roots are measured; reflection strings, resources, SOURCE-retention annotations and processor-specific generated-code attribution are not resolved")
        add("API evidence uses JVM declarations and readable Kotlin metadata, including signatures and inline bodies; it is a review candidate, not an automatic dependency edit")
        addAll(main.limitations)
        addAll(test?.limitations.orEmpty())
        if (test == null) add("test class roots were not supplied; test scopes are counted but not judged")
        if (!resolvedProvided) add("resolved dependency artifacts were not supplied; undeclared transitive dependency ownership was not checked")
        if (result.skippedCount > 0) add("${result.skippedCount} declared dependencies use processor, runtime-only, or unjudged test scopes and were not judged")
        if (result.withoutClassCount > 0) add("${result.withoutClassCount} declared artifacts contained no class files and were not judged")
        if (result.ambiguousClassCount > 0) add("${result.ambiguousClassCount} class names have ambiguous dependency/project ownership; ownership-based advice was withheld")
    }.distinct().sorted()
}
