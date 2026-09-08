package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class ServiceProviderScannerTest {
    @Test
    fun `directory and jar provider declarations keep line evidence`(@TempDir root: Path) {
        val resources = Files.createDirectories(root.resolve("resources/META-INF/services"))
        Files.writeString(resources.resolve("java.lang.Runnable"), "# comment\nexample.Provider\n\nexample.Provider # duplicate\n")
        Files.writeString(resources.resolve(".DS_Store"), "unrelated")
        Files.writeString(resources.resolve("java.lang.Runnable~"), "unrelated")
        val jar = root.resolve("providers.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/.DS_Store"))
            out.write("unrelated".toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/services/java.lang.Runnable"))
            out.write("example.JarProvider\n".toByteArray())
            out.closeEntry()
        }
        val facts = ServiceProviderScanner.scan(listOf(root.resolve("resources"), jar))
        assertEquals(3, facts.size)
        assertEquals(setOf("java/lang/Runnable"), facts.map { it.service }.toSet())
        assertEquals(setOf(1, 2, 4), facts.map { it.location.line }.toSet())
        assertFalse(facts.any { it.location.path.contains(root.toString()) })
    }

    @Test
    fun `invalid names encoding oversized input and absent roots fail without raw contents`(@TempDir root: Path) {
        val folder = Files.createDirectories(root.resolve("META-INF/services"))
        val file = folder.resolve("java.lang.Runnable")
        for (bytes in listOf("secret-value-with-spaces !".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(1_048_577))) {
            Files.write(file, bytes)
            val error = assertFailsWith<ClassIndexingException> { ServiceProviderScanner.scan(listOf(root)) }
            assertFalse(error.message.orEmpty().contains("secret-value"))
        }
        assertFailsWith<ClassIndexingException> { ServiceProviderScanner.scan(listOf(root.resolve("missing"))) }
    }

    @Test
    fun `registry symlinks cannot escape their input root`(@TempDir root: Path) {
        val input = Files.createDirectories(root.resolve("input/META-INF/services"))
        val external = Files.writeString(root.resolve("external"), "example.Provider")
        Files.createSymbolicLink(input.resolve("java.lang.Runnable"), external)
        assertFailsWith<ClassIndexingException> { ServiceProviderScanner.scan(listOf(root.resolve("input"))) }
    }

    @Test
    fun `absent registry is an empty fact set`(@TempDir root: Path) {
        assertEquals(emptyList(), ServiceProviderScanner.scan(listOf(root)))
    }
}
