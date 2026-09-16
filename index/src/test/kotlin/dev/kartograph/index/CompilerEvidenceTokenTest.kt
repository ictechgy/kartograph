package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import kotlin.test.Test
import kotlin.test.assertNotEquals

class CompilerEvidenceTokenTest {
    @Test
    fun `scope compiler task input role content and order each bind the request`() {
        val a = InputFingerprint("sources", "src", "a".repeat(64))
        val b = InputFingerprint("compiler", "external/compiler", "b".repeat(64))
        val original = CompilerEvidenceToken.create("app:main", "javac", ":app:compileJava", listOf(a, b))
        val alternatives = listOf(
            CompilerEvidenceToken.create("app:test", "javac", ":app:compileJava", listOf(a, b)),
            CompilerEvidenceToken.create("app:main", "kotlin", ":app:compileJava", listOf(a, b)),
            CompilerEvidenceToken.create("app:main", "javac", ":other:compileJava", listOf(a, b)),
            CompilerEvidenceToken.create("app:main", "javac", ":app:compileJava", listOf(b, a)),
            CompilerEvidenceToken.create("app:main", "javac", ":app:compileJava", listOf(a.copy(role = "buildConfig"), b)),
            CompilerEvidenceToken.create("app:main", "javac", ":app:compileJava", listOf(a.copy(sha256 = "c".repeat(64)), b)),
        )
        alternatives.forEach { assertNotEquals(original, it) }
    }
}
