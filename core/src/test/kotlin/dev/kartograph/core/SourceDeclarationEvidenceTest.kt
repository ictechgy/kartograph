package dev.kartograph.core

import kotlin.test.*

class SourceDeclarationEvidenceTest {
    @Test fun `only relative paths valid hashes and positive point coordinates are accepted`() {
        assertEquals(0, SourceDeclarationEvidence("src/Example.kt", "a".repeat(64), 0, 1, 1).offsetUtf16)
        listOf("", "/Example.kt", "../Example.kt", "src/../Example.kt", "src\\Example.kt", "C:/Example.kt", "src//Example.kt", "src/\nExample.kt").forEach { path ->
            assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence(path, "a".repeat(64), 0, 1, 1) }
        }
        listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64)).forEach { hash ->
            assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence("Example.kt", hash, 0, 1, 1) }
        }
        assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence("Example.kt", "a".repeat(64), -1, 1, 1) }
        assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence("Example.kt", "a".repeat(64), 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence("Example.kt", "a".repeat(64), 0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence("Example.kt", "a".repeat(64), 0, 2, 1) }
        assertFailsWith<IllegalArgumentException> { SourceDeclarationEvidence("Example.kt", "a".repeat(64), 0, 1, 2) }
    }
}
