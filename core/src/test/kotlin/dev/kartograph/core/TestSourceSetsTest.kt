package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** routes와 language-traversal이 공유하는 테스트 소스 세트 경로 규칙이다. */
class TestSourceSetsTest {
    @Test
    fun `test source paths follow the routes convention`() {
        listOf("src/test/A.kt", "m/src/androidTest/A.kt", "m/src/testDebug/A.kt", "m/src/androidTestRelease/A.kt",
            "m/src/testFixtures/A.kt", "m/src/integrationTest/A.kt", "m\\src\\test\\A.kt").forEach {
            assertTrue(TestSourceSets.isTestSourcePath(it), it)
        }
        listOf("src/android/app/src/test/java/A.kt", "m/src/testDebug/kotlin/A.kt").forEach {
            assertTrue(TestSourceSets.isTestSourcePath(it), it)
        }
        // production source set을 확정하는 쌍이 하나라도 있으면 production이다. production을 테스트로 오인하면 bound가 틀린다.
        listOf("src/main/A.kt", "m/src/testing/A.kt", "m/src/latest/A.kt", "test/A.kt", "A.kt", "m/src/debug/Test.kt",
            "m/src/main/java/com/example/src/test/A.kt", "m/src/debug/kotlin/com/example/src/test/A.kt",
            "src/LoadTest/app/src/main/java/A.kt", "m/src/test/java/p/src/main/A.kt").forEach {
            assertFalse(TestSourceSets.isTestSourcePath(it), it)
        }
    }
}
