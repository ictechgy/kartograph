package dev.kartograph.index

import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class DependencyArtifactScannerTest {
    @Test
    fun `AAR class and embedded library jars are read and damaged nested jars fail closed`(@TempDir root: Path) {
        fun jar(name: String): ByteArray = ByteArrayOutputStream().also { bytes ->
            JarOutputStream(bytes).use { out -> out.putNextEntry(JarEntry(name + ".class")); out.closeEntry() }
        }.toByteArray()
        val aar = root.resolve("library.aar")
        JarOutputStream(aar.toFile().outputStream()).use { out ->
            for ((name, bytes) in listOf("classes.jar" to jar("lib/Main"), "libs/embedded.jar" to jar("lib/Embedded"), "AndroidManifest.xml" to byteArrayOf(1))) {
                out.putNextEntry(JarEntry(name)); out.write(bytes); out.closeEntry()
            }
        }
        assertEquals(setOf("lib/Main", "lib/Embedded"), DependencyArtifactScanner().scan(aar))
        JarOutputStream(aar.toFile().outputStream()).use { out ->
            out.putNextEntry(JarEntry("classes.jar")); out.write(byteArrayOf(1, 2, 3)); out.closeEntry()
        }
        assertFailsWith<ClassIndexingException> { DependencyArtifactScanner().scan(aar) }
    }

    @Test
    fun `directories list class names without technical entries`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        listOf("com/example/Foo.class", "com/example/Bar${'$'}Baz.class", "module-info.class", "com/example/package-info.class")
            .forEach { name -> classes.resolve(name).also { it.parent.createDirectories() }.writeBytes(byteArrayOf()) }
        classes.resolve("com/example/notes.txt").writeText("ignored")

        assertEquals(
            setOf("com/example/Foo", "com/example/Bar${'$'}Baz"),
            DependencyArtifactScanner().scan(classes),
        )
    }

    @Test
    fun `jars list base entries and skip multi release variants`(@TempDir root: Path) {
        val jar = root.resolve("lib.jar")
        JarOutputStream(jar.toFile().outputStream()).use { archive ->
            listOf(
                "com/example/JarClass.class",
                "com/example/package-info.class",
                "module-info.class",
                "META-INF/versions/11/com/example/JarClass.class",
                "META-INF/MANIFEST.MF",
            ).forEach { name ->
                archive.putNextEntry(JarEntry(name))
                archive.write(byteArrayOf())
                archive.closeEntry()
            }
        }

        assertEquals(setOf("com/example/JarClass"), DependencyArtifactScanner().scan(jar))
    }

    @Test
    fun `other inputs fail closed`(@TempDir root: Path) {
        val text = root.resolve("lib.pom").apply { writeText("<project/>") }
        assertFailsWith<ClassIndexingException> { DependencyArtifactScanner().scan(text) }
        assertFailsWith<ClassIndexingException> { DependencyArtifactScanner().scan(root.resolve("missing.jar")) }
    }
}
