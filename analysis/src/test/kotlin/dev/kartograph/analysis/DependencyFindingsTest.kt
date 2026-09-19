package dev.kartograph.analysis

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyFindingsTest {
    private val used = DeclaredDependency("com.example:used:1.0", DependencyScope.IMPLEMENTATION, "libs/used.jar")
    private val unused = DeclaredDependency("com.example:unused:1.0", DependencyScope.IMPLEMENTATION, "libs/unused.jar")
    private val artifactClasses = mapOf(
        "libs/used.jar" to setOf("com/example/used/Api"),
        "libs/unused.jar" to setOf("com/example/unused/Helper"),
        "libs/empty.jar" to emptySet<String>(),
    )

    @Test
    fun `dependencies without any referenced class are reported unused`() {
        val result = DependencyFindings.find(
            listOf(used, unused),
            referencedClasses = setOf("com/example/used/Api"),
            artifactClasses = artifactClasses,
        )

        assertEquals(
            listOf(UnusedDependency("com.example:unused:1.0", DependencyScope.IMPLEMENTATION, "libs/unused.jar")),
            result.findings,
        )
        assertEquals(2, result.analyzedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(0, result.withoutClassCount)
    }

    @Test
    fun `processor and runtime scopes are counted but not judged`() {
        val result = DependencyFindings.find(
            listOf(
                DeclaredDependency("com.example:processor:1.0", DependencyScope.KAPT, "libs/processor.jar"),
                DeclaredDependency("com.example:runtime:1.0", DependencyScope.RUNTIME_ONLY, "libs/runtime.jar"),
                used,
            ),
            referencedClasses = emptySet(),
            artifactClasses = artifactClasses,
        )

        assertEquals(
            listOf(UnusedDependency(used.coordinate, used.scope, used.artifact)),
            result.findings,
        )
        assertEquals(1, result.analyzedCount)
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `test scopes are skipped when test roots were not supplied`() {
        val testDependency = DeclaredDependency("com.example:test:1.0", DependencyScope.TEST_IMPLEMENTATION, "libs/test.jar")

        val result = DependencyFindings.find(
            listOf(testDependency),
            referencedClasses = emptySet(),
            artifactClasses = mapOf("libs/test.jar" to setOf("com/example/test/Api")),
            includeTestScopes = false,
        )

        assertEquals(emptyList(), result.findings)
        assertEquals(0, result.analyzedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, result.withoutClassCount)
    }

    @Test
    fun `artifacts without class files are counted instead of judged`() {
        val empty = DeclaredDependency("com.example:empty:1.0", DependencyScope.API, "libs/empty.jar")

        val result = DependencyFindings.find(listOf(empty), emptySet(), artifactClasses)

        assertEquals(emptyList(), result.findings)
        assertEquals(0, result.analyzedCount)
        assertEquals(1, result.withoutClassCount)
    }

    @Test
    fun `duplicate declarations are judged once and findings stay sorted`() {
        val second = DeclaredDependency("com.example:another:1.0", DependencyScope.API, "libs/another.jar")
        val result = DependencyFindings.find(
            listOf(used, unused, used, second),
            referencedClasses = setOf("com/example/used/Api"),
            artifactClasses = artifactClasses + ("libs/another.jar" to setOf("com/example/another/Api")),
        )

        assertEquals(
            listOf(
                UnusedDependency("com.example:another:1.0", DependencyScope.API, "libs/another.jar"),
                UnusedDependency("com.example:unused:1.0", DependencyScope.IMPLEMENTATION, "libs/unused.jar"),
            ),
            result.findings,
        )
        assertEquals(3, result.analyzedCount)
    }
}
