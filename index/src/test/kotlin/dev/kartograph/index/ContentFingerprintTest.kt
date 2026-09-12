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
    fun `a project directory named credentials is not a credential file`(@TempDir root: Path) {
        val directory = Files.createDirectories(root.resolve("credentials/classes"))
        Files.write(directory.resolve("A.class"), byteArrayOf(1, 2, 3))
        val other = Files.createDirectories(root.resolve("ordinary/classes"))
        Files.write(other.resolve("A.class"), byteArrayOf(1, 2, 3))
        assertEquals(ContentFingerprint.hash(other), ContentFingerprint.hash(directory))
    }

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
    fun `file names do not change explicit input policy and symbolic links are rejected`(@TempDir root: Path) {
        val input = root.resolve("pin.pem")
        Files.writeString(input, "PUBLIC_CERTIFICATE_FIXTURE")
        val digest = ContentFingerprint.hash(root)
        Files.writeString(input, "CHANGED_PUBLIC_CERTIFICATE_FIXTURE")
        assertNotEquals(digest, ContentFingerprint.hash(root))
        Files.createSymbolicLink(root.resolve("link"), input)
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.hash(root.resolve("link")) }
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.hash(root) }
    }
}
