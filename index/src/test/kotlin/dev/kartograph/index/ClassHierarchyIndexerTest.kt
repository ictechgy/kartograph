package dev.kartograph.index

import dev.kartograph.index.fixture.Caller
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ClassHierarchyIndexerTest {
    @Test
    fun `reads class headers from directories and expands referenced JDK supertypes`() {
        val hierarchy = ClassHierarchyIndexer().index(listOf(testClassesRoot))

        assertEquals(
            setOf("dev/kartograph/index/fixture/Base"),
            hierarchy.directSupertypesOf("dev/kartograph/index/fixture/Caller"),
        )
        assertEquals(
            setOf("java/io/OutputStream"),
            hierarchy.directSupertypesOf("java/io/ByteArrayOutputStream"),
        )
    }

    @Test
    fun `uses the highest applicable multi release class including version only entries`(@TempDir directory: Path) {
        val jar = directory.resolve("dependency.jar")
        writeJar(
            jar,
            multiRelease = true,
            "dev/fixture/Versioned.class" to classBytes("dev/fixture/Versioned", "dev/fixture/Base"),
            "META-INF/versions/17/dev/fixture/Versioned.class" to
                classBytes("dev/fixture/Versioned", "dev/fixture/VersionBase"),
            "META-INF/versions/17/dev/fixture/VersionOnly.class" to
                classBytes("dev/fixture/VersionOnly", "dev/fixture/OnlyBase"),
        )

        val hierarchy = ClassHierarchyIndexer().index(listOf(jar))

        assertEquals(setOf("dev/fixture/VersionBase"), hierarchy.directSupertypesOf("dev/fixture/Versioned"))
        assertEquals(setOf("dev/fixture/OnlyBase"), hierarchy.directSupertypesOf("dev/fixture/VersionOnly"))
    }

    @Test
    fun `uses the first duplicate classpath entry deterministically`(@TempDir directory: Path) {
        val first = directory.resolve("first/dev/fixture/Duplicate.class")
        val second = directory.resolve("second/dev/fixture/Duplicate.class")
        first.parent.createDirectories()
        second.parent.createDirectories()
        first.writeBytes(classBytes("dev/fixture/Duplicate", "dev/fixture/FirstBase"))
        second.writeBytes(classBytes("dev/fixture/Duplicate", "dev/fixture/SecondBase"))

        val hierarchy = ClassHierarchyIndexer().index(listOf(directory.resolve("first"), directory.resolve("second")))

        assertEquals(setOf("dev/fixture/FirstBase"), hierarchy.directSupertypesOf("dev/fixture/Duplicate"))
    }

    @Test
    fun `rejects malformed classes without exposing their path`(@TempDir directory: Path) {
        directory.resolve("Broken.class").writeText("not bytecode")

        val error = assertFailsWith<ClassHierarchyIndexingException> {
            ClassHierarchyIndexer().index(listOf(directory))
        }

        assertEquals("invalid class file in classpath", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `wraps truncated class runtime failures as sanitized indexing errors`(@TempDir directory: Path) {
        val truncatedClass = classBytes("dev/fixture/Truncated", "java/lang/Object").copyOf(20)
        directory.resolve("Truncated.class").writeBytes(truncatedClass)

        val error = assertFailsWith<ClassHierarchyIndexingException> {
            ClassHierarchyIndexer().index(listOf(directory))
        }

        assertEquals("invalid class file in classpath", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `rejects missing classpath entries without exposing their path`(@TempDir directory: Path) {
        val error = assertFailsWith<ClassHierarchyIndexingException> {
            ClassHierarchyIndexer().index(listOf(directory.resolve("missing.jar")))
        }

        assertEquals("classpath entry must be a class directory or JAR", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    private fun classBytes(internalName: String, superName: String): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, emptyArray())
            visitEnd()
        }.toByteArray()

    private fun writeJar(jar: Path, multiRelease: Boolean, vararg entries: Pair<String, ByteArray>) {
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private val testClassesRoot: Path
        get() = Path.of(requireNotNull(Caller::class.java.protectionDomain.codeSource).location.toURI())
}
