package dev.kartograph.analysis

import dev.kartograph.core.DependencyScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DependencyReviewTest {
    private val unused = UnusedDependency("example:lib:1", DependencyScope.IMPLEMENTATION, "/cache/one/lib.jar")
    private val advice = DependencyAdvice("example:api:1", DependencyScope.IMPLEMENTATION, "api.jar",
        "dependency-scope-mismatch", DependencyScope.API, listOf("lib/B", "lib/A"))

    @Test
    fun `review fingerprints ignore cache locations but preserve changed observation identity`() {
        assertEquals(DependencyReview.fingerprint(unused), DependencyReview.fingerprint(unused.copy(artifact = "/another/cache/lib.jar")))
        assertNotEquals(DependencyReview.fingerprint(unused), DependencyReview.fingerprint(unused.copy(coordinate = "example:lib:2")))
        assertNotEquals(DependencyReview.fingerprint(unused), DependencyReview.fingerprint(unused.copy(scope = DependencyScope.API)))
        assertEquals(DependencyReview.fingerprint(advice), DependencyReview.fingerprint(advice.copy(evidence = listOf("lib/A", "lib/B", "lib/A"))))
        for (changed in listOf(advice.copy(evidence = listOf("lib/A")), advice.copy(suggestedScope = null), advice.copy(scope = null), advice.copy(ruleId = "undeclared-dependency"))) {
            assertNotEquals(DependencyReview.fingerprint(advice), DependencyReview.fingerprint(changed))
        }
        assertNotEquals(DependencyReview.fingerprint("a|b", "c", null, null, emptyList()),
            DependencyReview.fingerprint("a", "b|c", null, null, emptyList()))
    }

    @Test
    fun `baseline and temporary suppressions filter exact observations and retain counts`() {
        val observed = DependencyAnalysisResult(listOf(unused), 3, 2, 1, listOf(advice), 4)
        val result = DependencyReview.apply(observed, setOf(DependencyReview.fingerprint(unused)),
            DependencyReview.fingerprints(observed).toSet(), 2)
        assertTrue(result.findings.isEmpty() && result.advice.isEmpty())
        assertEquals(1, result.baselineSuppressedCount)
        assertEquals(1, result.suppressionCount)
        assertEquals(2, result.expiredSuppressionCount)
        assertEquals(3, result.analyzedCount)
        assertEquals(2, result.skippedCount)
        assertEquals(4, result.ambiguousClassCount)
        assertEquals(1, result.withoutClassCount)
        assertEquals(observed, DependencyReview.apply(observed, setOf("dead|class:lib/A|"), emptySet()))
    }
}
