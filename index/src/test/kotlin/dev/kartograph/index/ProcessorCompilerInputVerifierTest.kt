package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.ProcessorCompilerInputs
import dev.kartograph.core.ProcessorOutputConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class ProcessorCompilerInputVerifierTest {
    private val token = "a".repeat(64)

    @Test fun `all declared files directories absent slots and external artifacts are revalidated`(@TempDir base: Path) {
        val root = base.resolve("project"); Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.java"), "class Main {}")
        val external = base.resolve("compiler.jar"); Files.writeString(external, "compiler bytes")
        val config = config(root)
        val original = record(root, config, listOf("src" to "directory", "optional.jar" to "missing"), external)
        assertEquals(original, ProcessorCompilerInputVerifier.verify(root, config, token, original))
        assertEquals(setOf("processorCompilerInputs", "processorCompilerInput", "file-watch"), ProcessorCompilerInputVerifier.trackedFiles(root, config).map { it.first }.toSet())
        for (file in listOf(root.resolve("src/Main.java"), external)) {
            val bytes = Files.readAllBytes(file); val stamp = Files.getLastModifiedTime(file)
            Files.writeString(file, "different bytes"); Files.setLastModifiedTime(file, stamp)
            assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, original) }
            Files.write(file, bytes)
        }
        Files.writeString(root.resolve("optional.jar"), "now present")
        assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, original) }
    }

    @Test fun `task options token inventory and report mutations cannot reuse a completed receipt`(@TempDir root: Path) {
        Files.writeString(root.resolve("input.jar"), "jar")
        val config = config(root); val expected = record(root, config, listOf("input.jar" to "file"))
        val path = root.resolve(config.compilerInputs!!); val original = Files.readString(path)
        val row = original.lines().first { it.startsWith("file\t") }
        for (mutation in listOf(original.replace(token, "c".repeat(64)), original.replace(":compileJava", ":compileTestJava"),
            original.replace("b".repeat(64), "c".repeat(64)), original + row + "\n", original.replace(row + "\n", ""),
            original + "unknown\tx\n", original + "task\t:compileJava\n", original.replace("\tfile\t", "\tdevice\t"))) {
            Files.writeString(path, mutation)
            assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, expected) }
        }
        Files.writeString(path, original)
        assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, null) }
        assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config.copy(compilerInputs = null), token, expected) }
        assertEquals(null, ProcessorCompilerInputVerifier.verify(root, config.copy(compilerInputs = null), token, null))
        assertEquals(emptyList(), ProcessorCompilerInputVerifier.trackedFiles(root, config.copy(compilerInputs = null)))
    }

    @Test fun `invalid bindings encoding symbolic files and malformed report do not expose paths`(@TempDir root: Path) {
        Files.writeString(root.resolve("input.jar"), "jar")
        val config = config(root); val expected = record(root, config, listOf("input.jar" to "file"))
        val path = root.resolve(config.compilerInputs!!); val original = Files.readString(path)
        for (mutation in listOf(original.replace(encoded(root.resolve("input.jar").toRealPath().toString()), encoded("private\u0000binding")),
            original.replace(encoded("project/input.jar"), encoded("project/wrong.jar")), original.replace(encoded("project/input.jar"), encoded("external/other")),
            original.replace(encoded("project/input.jar"), encoded("project/input.jar") + "="), "format\twrong\t1\n")) {
            Files.writeString(path, mutation)
            val failure = assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, expected) }
            assertFalse(failure.message.orEmpty().contains("private")); assertEquals(null, failure.cause)
        }
        Files.write(path, byteArrayOf(0xff.toByte()))
        assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, expected) }
        Files.writeString(path, original)
        Files.move(root.resolve("input.jar"), root.resolve("real.jar"))
        Files.createSymbolicLink(root.resolve("input.jar"), Path.of("real.jar"))
        assertFailsWith<IllegalArgumentException> { ProcessorCompilerInputVerifier.verify(root, config, token, expected) }
    }

    private fun config(root: Path) = ProcessorOutputConfiguration(root.toString(), "fixture:main", "javac", "fixture.Processor",
        "collector.jar", "processor.jar", listOf("src"), listOf("generated"), listOf("compile"), ".evidence/token", ".evidence/raw.tsv", ".evidence/receipt.json", ".evidence/inputs.tsv")

    private fun record(root: Path, config: ProcessorOutputConfiguration, inputs: List<Pair<String, String>>, external: Path? = null): ProcessorCompilerInputs {
        val files = inputs.map { (name, kind) -> Triple("project/$name", root.resolve(name), kind) } +
            listOfNotNull(external?.let { Triple("external/compiler-input-0", it, "file") })
        val fingerprints = files.map { (name, path, kind) -> InputFingerprint(if (kind == "missing") "file-watch" else "processorCompilerInput", name,
            if (kind == "missing") ContentFingerprint.values(listOf("missing-file")) else ContentFingerprint.hash(path)) }
        val text = "format\tkartograph-processor-compiler-inputs\t1\nscope\tfixture:main\ntask\t:compileJava\ntoken\t$token\npropertiesSha256\t${"b".repeat(64)}\n" +
            files.zip(fingerprints).joinToString("") { (file, input) -> "file\t${encoded(file.first)}\t${encoded(file.second.toFile().canonicalPath)}\t${file.third}\t${input.sha256}\n" }
        val path = root.resolve(config.compilerInputs!!); Files.createDirectories(path.parent); Files.writeString(path, text)
        return ProcessorCompilerInputs(":compileJava", fingerprints, "b".repeat(64), ContentFingerprint.values(
            listOf("gradle-declared-inputs-v1", "fixture:main", ":compileJava", token, "b".repeat(64), files.size.toString()) +
                files.zip(fingerprints).flatMap { (file, input) -> listOf(input.path, file.third, input.sha256) }))
    }
    private fun encoded(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
