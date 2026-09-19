package dev.kartograph.analysis

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import dev.kartograph.core.DependencyUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyConfigurationAnalysisTest {
    @Test
    fun `multiple artifacts of one component are judged together and selected versions remain declared`() {
        val first = DeclaredDependency("example:multi:1", DependencyScope.IMPLEMENTATION, "first.jar")
        val second = first.copy(artifact = "second.jar")
        val result = DependencyConfigurationAnalysis.analyze(listOf(first, second),
            DependencyUsage(setOf("lib/Used"), setOf("lib/Used")), null,
            mapOf("first.jar" to setOf("lib/NotUsed"), "second.jar" to setOf("lib/Used")))
        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.analyzedCount)
        assertEquals("second.jar", result.advice.single().artifact)
        val selected = DeclaredDependency("example:multi:2", DependencyScope.TEST_IMPLEMENTATION, "selected.jar")
        val withTests = DependencyConfigurationAnalysis.analyze(listOf(first), DependencyUsage(setOf("lib/Used")),
            DependencyUsage(setOf("lib/NewVersion")), mapOf("first.jar" to setOf("lib/Used"), "selected.jar" to setOf("lib/NewVersion")), listOf(selected))
        assertTrue(withTests.advice.isEmpty())
    }

    @Test
    fun `application mode does not infer a consumer API`() {
        val dependency = dependency("api", DependencyScope.IMPLEMENTATION)
        val usage = DependencyUsage(setOf("lib/api"), setOf("lib/api"))
        val result = DependencyConfigurationAnalysis.analyze(listOf(dependency), usage, null, classes, apiAdvice = false)
        assertTrue(result.advice.isEmpty())
        val undeclared = DependencyConfigurationAnalysis.analyze(emptyList(), usage, null, classes, listOf(dependency), apiAdvice = false)
        assertEquals(DependencyScope.IMPLEMENTATION, undeclared.advice.single().suggestedScope)
    }

    @Test
    fun `undeclared use preserves evidence without guessing an incomplete library API scope`() {
        val resolved = listOf(DeclaredDependency("example:dep:1", DependencyScope.IMPLEMENTATION, "dep.jar"))
        val result = DependencyConfigurationAnalysis.analyze(emptyList(), DependencyUsage(setOf("lib/Type"), apiComplete = false),
            null, mapOf("dep.jar" to setOf("lib/Type")), resolved)
        assertEquals(null, result.advice.single().suggestedScope)
        assertEquals(listOf("lib/Type"), result.advice.single().evidence)
    }

    private fun dependency(name: String, scope: DependencyScope) = DeclaredDependency("example:$name:1", scope, "$name.jar")
    private val classes = listOf("api", "impl", "unused", "compile", "transitive", "test").associate { "$it.jar" to setOf("lib/$it") }

    @Test
    fun `ABI exposure promotes implementation while body-only use narrows api`() {
        val result = DependencyConfigurationAnalysis.analyze(
            listOf(dependency("api", DependencyScope.IMPLEMENTATION), dependency("impl", DependencyScope.API),
                dependency("compile", DependencyScope.COMPILE_ONLY), dependency("unused", DependencyScope.API)),
            DependencyUsage(setOf("lib/api", "lib/impl", "lib/compile"), setOf("lib/api", "lib/compile")), null, classes,
        )
        assertEquals(listOf("example:unused:1"), result.findings.map { it.coordinate })
        assertEquals(listOf(DependencyScope.API, DependencyScope.IMPLEMENTATION), result.advice.map { it.suggestedScope })
        assertEquals(listOf("lib/api"), result.advice.first().evidence)
        assertEquals(4, result.analyzedCount)
    }

    @Test
    fun `compile-only declarations do not receive runtime scope changes`() {
        val result = DependencyConfigurationAnalysis.analyze(listOf(dependency("compile", DependencyScope.COMPILE_ONLY_API)),
            DependencyUsage(setOf("lib/compile")), null, classes)
        assertTrue(result.advice.isEmpty())
    }

    @Test
    fun `test-only uses do not cause removal or main API exposure advice`() {
        val declared = listOf(dependency("api", DependencyScope.API), dependency("test", DependencyScope.TEST_IMPLEMENTATION))
        val result = DependencyConfigurationAnalysis.analyze(declared, DependencyUsage(emptySet()),
            DependencyUsage(setOf("lib/api", "lib/test"), setOf("lib/api")), classes)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.advice.isEmpty())
        val noTest = DependencyConfigurationAnalysis.analyze(declared, DependencyUsage(emptySet()), null, classes)
        assertEquals(1, noTest.skippedCount)
        assertEquals(listOf("example:api:1"), noTest.findings.map { it.coordinate })
    }

    @Test
    fun `resolved class ownership reports undeclared main and test requirements once`() {
        val resolved = listOf(dependency("transitive", DependencyScope.IMPLEMENTATION), dependency("test", DependencyScope.TEST_IMPLEMENTATION))
        val result = DependencyConfigurationAnalysis.analyze(emptyList(),
            DependencyUsage(setOf("lib/transitive"), setOf("lib/transitive")),
            DependencyUsage(setOf("lib/transitive", "lib/test")), classes, resolved)
        assertEquals(2, result.advice.size)
        assertEquals(setOf(DependencyScope.API, DependencyScope.TEST_IMPLEMENTATION), result.advice.map { it.suggestedScope }.toSet())
        assertTrue(result.advice.all { it.ruleId == "undeclared-dependency" && it.scope == null })
        val declared = DependencyConfigurationAnalysis.analyze(listOf(resolved.first()),
            DependencyUsage(setOf("lib/transitive")), null, classes, resolved)
        assertTrue(declared.advice.isEmpty())
    }

    @Test
    fun `same class in multiple artifacts or project inputs does not identify one owner`() {
        val first = dependency("api", DependencyScope.IMPLEMENTATION)
        val second = dependency("impl", DependencyScope.IMPLEMENTATION)
        val duplicate = classes + ("impl.jar" to setOf("lib/api"))
        val usage = DependencyUsage(setOf("lib/api"), setOf("lib/api"))
        val ambiguous = DependencyConfigurationAnalysis.analyze(listOf(first), usage, null, duplicate, listOf(second))
        assertTrue(ambiguous.advice.isEmpty())
        assertEquals(1, ambiguous.ambiguousClassCount)
        val local = DependencyConfigurationAnalysis.analyze(emptyList(), usage.copy(declaredClasses = setOf("lib/api")), null, classes, listOf(first))
        assertTrue(local.advice.isEmpty())
        assertEquals(1, local.ambiguousClassCount)
    }

    @Test
    fun `incomplete ABI withholds absence-based advice and unresolvable scopes remain counted`() {
        val declared = listOf(dependency("api", DependencyScope.API), dependency("unused", DependencyScope.IMPLEMENTATION),
            dependency("compile", DependencyScope.KAPT), dependency("test", DependencyScope.RUNTIME_ONLY),
            dependency("empty", DependencyScope.API))
        val result = DependencyConfigurationAnalysis.analyze(declared, DependencyUsage(setOf("lib/api"), apiComplete = false), null, classes)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.advice.isEmpty())
        assertEquals(2, result.skippedCount)
        assertEquals(1, result.withoutClassCount)
    }
}
