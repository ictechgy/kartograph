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
    fun `optional configuration file watch detects creation edits and deletion`(@TempDir root: Path) {
        val file = root.resolve("gradle.properties")
        fun watch() = ContentFingerprint.capture(root, file, "file-watch", "configuration")
        val absent = watch()
        Files.writeString(file, "")
        assertNotEquals(absent.sha256, watch().sha256)
        Files.writeString(file, "enabled=true")
        val first = watch()
        val stamp = Files.getLastModifiedTime(file)
        Files.writeString(file, "enabled=null")
        Files.setLastModifiedTime(file, stamp)
        assertNotEquals(first.sha256, watch().sha256)
        Files.delete(file)
        assertEquals(absent, watch())
        Files.createDirectories(file)
        assertFailsWith<IllegalArgumentException> { watch() }
        Files.delete(file)
        val target = root.resolve("target.properties")
        Files.writeString(target, "enabled=true")
        Files.createSymbolicLink(file, target)
        assertFailsWith<IllegalArgumentException> { watch() }
    }

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

    @Test
    fun `optional source watch observes future source files without weakening required inputs`(@TempDir root: Path) {
        val source = root.resolve("src/test/java")
        fun watch() = ContentFingerprint.capture(root, source, "source-watch", "test-sources")
        val absent = watch()
        assertEquals("src/test/java", absent.path)
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.capture(root, source, "sources", "required") }
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.capture(root, source, "classes", "required") }
        Files.createDirectories(source)
        Files.writeString(source.resolve("README.txt"), "not a compiler source")
        assertEquals(absent, watch())
        val file = source.resolve("FutureTest.java")
        Files.writeString(file, "class FutureTest {}")
        val added = watch()
        assertNotEquals(absent.sha256, added.sha256)
        val timestamp = Files.getLastModifiedTime(file)
        Files.writeString(file, "class FutureTest { int value; }")
        Files.setLastModifiedTime(file, timestamp)
        assertNotEquals(added.sha256, watch().sha256)
        Files.delete(file)
        assertEquals(absent, watch())
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.writeString(outside.resolve("Hidden.java"), "class Hidden {}")
        Files.createSymbolicLink(source.resolve("linked"), outside)
        assertFailsWith<IllegalArgumentException> { watch() }
    }

    @Test
    fun `optional resource directory watches non source additions`(@TempDir root: Path) {
        val directory = root.resolve("resources")
        val empty = ContentFingerprint.capture(root, directory, "directory-watch", "resources")
        Files.createDirectories(directory)
        assertEquals(empty, ContentFingerprint.capture(root, directory, "directory-watch", "resources"))
        Files.writeString(directory.resolve("service.txt"), "provider")
        assertNotEquals(empty.sha256, ContentFingerprint.capture(root, directory, "directory-watch", "resources").sha256)
        assertFailsWith<IllegalArgumentException> {
            ContentFingerprint.capture(root, directory.resolve("service.txt"), "directory-watch", "resources")
        }
    }
}
