package dev.kartograph.index

import dev.kartograph.core.ProcessorCompilerInputs
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputs
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProcessorOutputIndexerTest {
    @Test fun `real javac class bytes attach exact JVM declarations from directories and jars without graph edges`(@TempDir root: Path) {
        val classes = compile(root, "generated", "return 1;")
        val observed = observation(root, classes.resolve("fixture/Generated.class"))
        val jar = root.resolve("classes.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("fixture/Generated.class")); out.write(Files.readAllBytes(classes.resolve("fixture/Generated.class"))); out.closeEntry()
        }
        for (input in listOf(classes, jar)) {
            val indexed = ClassFileIndexer().indexWithObservations(listOf(input))
            val edges = indexed.graph.edges.toList()
            val result = ProcessorOutputIndexer.attribute(root, indexed, listOf(input), listOf(observed)).single()
            assertEquals(JvmNodeId.classId("fixture/Generated"), result.declarations.single().owner)
            assertTrue(result.declarations.single().symbols.any { it.value.contains("answer") })
            assertEquals(edges, indexed.graph.edges.toList())
            assertTrue(ProcessorOutputIndexer.limitations(listOf(result)).any { it.contains("source-to-class") })
            assertFailsWith<IllegalArgumentException> { ProcessorOutputIndexer.attribute(root, indexed, listOf(input), listOf(result)) }
        }
    }

    @Test fun `shadowed class and source-only receipts cannot attribute another class implementation`(@TempDir root: Path) {
        val generated = compile(root, "generated", "return 1;")
        val hand = compile(root, "handwritten", "return 2;")
        val observed = observation(root, generated.resolve("fixture/Generated.class"))
        val roots = listOf(hand, generated); val indexed = ClassFileIndexer().indexWithObservations(roots)
        val result = ProcessorOutputIndexer.attribute(root, indexed, roots, listOf(observed)).single()
        assertTrue(result.declarations.isEmpty())
        assertTrue(ProcessorOutputIndexer.limitations(listOf(result)).any { it.contains("unmapped-classes: 1") })
        val legacy = observed.copy(compilerInputs = null)
        assertEquals(listOf(legacy), ProcessorOutputIndexer.attribute(root, indexed, roots, listOf(legacy)))
        val other = compile(root, "outside", "return 3;", "Other")
        val outside = ClassFileIndexer().indexWithObservations(listOf(other))
        assertTrue(ProcessorOutputIndexer.attribute(root, outside, listOf(other), listOf(observed)).single().declarations.isEmpty())
        assertEquals(emptyList(), ProcessorOutputIndexer.limitations(emptyList()))
    }

    @Test fun `changed truncated and oversized class bytes do not produce guessed JVM origins`(@TempDir root: Path) {
        val classes = compile(root, "generated", "return 1;"); val file = classes.resolve("fixture/Generated.class")
        val observed = observation(root, file); val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        Files.writeString(file, "invalid class")
        assertFailsWith<IllegalArgumentException> { ProcessorOutputIndexer.attribute(root, indexed, listOf(classes), listOf(observed)) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputIndexer.attribute(root, indexed, listOf(classes), listOf(observation(root, file))) }
        java.io.RandomAccessFile(file.toFile(), "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputIndexer.attribute(root, indexed, listOf(classes), listOf(observed)) }
    }

    private fun compile(root: Path, directory: String, body: String, name: String = "Generated"): Path {
        val source = root.resolve("$directory-src/fixture/$name.java"); Files.createDirectories(source.parent)
        Files.writeString(source, "package fixture; public class $name { public int answer() { $body } }")
        val output = root.resolve(directory); Files.createDirectories(output)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), source.toString()))
        return output
    }
    private fun observation(root: Path, file: Path) = ProcessorOutputs("fixture:main", "javac", "fixture.Processor", "a".repeat(64), "b".repeat(64),
        listOf(ProcessorOutput(root.relativize(file).toString(), "class", "api", CompilerEvidenceIndexer.sourceHash(file))), "c".repeat(64),
        ProcessorCompilerInputs(":compileJava", emptyList(), "d".repeat(64), "e".repeat(64)))
}
