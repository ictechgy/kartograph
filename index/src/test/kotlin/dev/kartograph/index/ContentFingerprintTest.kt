package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class ContentFingerprintTest {
    @Test
    fun `bytes membership order and relocation are observable without timestamps`(@TempDir root: Path) {
        val a = Files.createDirectories(root.resolve("a"))
        val b = Files.createDirectories(root.resolve("b"))
        val source = a.resolve("Example.java")
        Files.writeString(source, "class A {}")
        Files.writeString(b.resolve("Example.java"), "class A {}")
        val original = ContentFingerprint.hash(a, sourcesOnly = true)
        assertEquals(original, ContentFingerprint.hash(b, sourcesOnly = true))
        val time = Files.getLastModifiedTime(source)
        Files.writeString(source, "class B {}")
        Files.setLastModifiedTime(source, time)
        assertNotEquals(original, ContentFingerprint.hash(a, sourcesOnly = true))
        Files.delete(source)
        assertNotEquals(original, ContentFingerprint.hash(a, sourcesOnly = true))
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.hash(a.resolve("missing")) }
    }

    @Test
    fun `secret and symbolic link inputs are rejected before reading`(@TempDir root: Path) {
        Files.writeString(root.resolve(".env"), "DO_NOT_READ")
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.hash(root) }
        Files.createSymbolicLink(root.resolve("link"), root.resolve(".env"))
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.hash(root.resolve("link")) }
    }
}
