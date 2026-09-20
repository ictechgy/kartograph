package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputConfiguration
import dev.kartograph.core.ProcessorOutputReceipt
import dev.kartograph.core.ProcessorOutputs
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProcessorOutputVerifierTest {
    @Test fun `configuration aliases are rejected before importing duplicate observations`() = fixture { root, _, _ ->
        val path = root.resolve("config.json"); Files.writeString(path, "{}")
        val alias = root.resolve("alias.json"); Files.createSymbolicLink(alias, path.fileName)
        assertEquals(listOf(path), ProcessorOutputVerifier.configurationPaths(listOf(path)))
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.configurationPaths(listOf(path, alias)) }
    }
    @Test fun `missing and invalid local paths do not leak their values`() = fixture { root, config, receipt ->
        for (missing in listOf(root.resolve("private-artifact.jar").toString(), "private\u0000artifact.jar")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                ProcessorOutputVerifier.verify(root, "app:main", config.copy(collectorJar = missing), receipt)
            }
            assertFalse(failure.message.orEmpty().contains("private"))
            assertEquals(null, failure.cause)
        }
    }
    @Test fun `completed observations preserve kinds without attributing graph edges`() = fixture { root, config, receipt ->
        assertEquals(receipt.observation, ProcessorOutputVerifier.verify(root, "app:main", config, receipt))
        assertEquals(setOf("source", "class", "resource", "file"), receipt.observation.outputs.map { it.kind }.toSet())
    }

    @Test fun `changed input artifact output raw and scope are rejected`() = fixture { root, config, receipt ->
        for (path in listOf("src/Input.kt", "collector.jar", "processor.jar", "generated/a.kt", "raw.tsv")) {
            val file = root.resolve(path); val old = Files.readAllBytes(file)
            Files.writeString(file, "changed")
            assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config, receipt) }
            Files.write(file, old)
        }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:test", config, receipt) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config.copy(command = listOf("other")), receipt) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config.copy(project = root.resolve("src").toString()), receipt) }
    }

    @Test fun `extra input files and symbolic outputs cannot reuse receipt`() = fixture { root, config, receipt ->
        Files.writeString(root.resolve("src/Added.kt"), "class Added")
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config, receipt) }
        Files.delete(root.resolve("src/Added.kt"))
        Files.delete(root.resolve("generated/a.kt"))
        Files.createSymbolicLink(root.resolve("generated/a.kt"), root.resolve("src/Input.kt"))
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config, receipt) }
    }

    @Test fun `duplicate unknown partial and forged raw evidence are rejected`() = fixture { root, config, receipt ->
        val raw = root.resolve("raw.tsv"); val text = Files.readString(raw)
        for (changed in listOf(text + "kind\tksp\n", text + "unknown\tx\n", text.lines().dropLast(2).joinToString("\n"),
            text.replace("kind\tksp", "kind\tkapt"), text.replace("output\tsource\tapi\t", "output\tsource\tapi\t="))) {
            Files.writeString(raw, changed)
            assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config, receipt) }
        }
        Files.writeString(raw, text)
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.verify(root, "app:main", config.copy(outputRoots = listOf("elsewhere")), receipt) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.trackedFiles(root, config.copy(receipt = "src/receipt.json")) }
        assertFailsWith<IllegalArgumentException> { ProcessorOutputVerifier.trackedFiles(root, config.copy(token = "generated/token")) }
    }

    private fun fixture(block: (Path, ProcessorOutputConfiguration, ProcessorOutputReceipt) -> Unit) {
        val root = Files.createTempDirectory("processor-receipt-test").toRealPath()
        try {
            Files.createDirectories(root.resolve("src")); Files.writeString(root.resolve("src/Input.kt"), "class Input")
            Files.writeString(root.resolve("collector.jar"), "collector"); Files.writeString(root.resolve("processor.jar"), "processor")
            Files.createDirectories(root.resolve("generated"))
            val outputs = listOf("a.kt" to "source", "b.class" to "class", "c.txt" to "resource", "d.txt" to "file").map { (file, kind) ->
                val path = root.resolve("generated/$file"); Files.writeString(path, kind)
                ProcessorOutput("generated/$file", kind, if (kind == "file") "callback-scope" else "api", CompilerEvidenceIndexer.sourceHash(path))
            }
            val config = ProcessorOutputConfiguration(root.toString(), "app:main", "ksp", "fixture.Provider", root.resolve("collector.jar").toString(),
                root.resolve("processor.jar").toString(), listOf("src"), listOf("generated"), listOf("compiler"), "token", "raw.tsv", "receipt.json")
            val inputs = listOf(InputFingerprint("processorInput", "src", ContentFingerprint.hash(root.resolve("src")))) +
                listOf("collector", "processor").map { InputFingerprint("processorInput", "external/${it}Jar", ContentFingerprint.hash(root.resolve("$it.jar"))) }
            val contract = ContentFingerprint.values(config.contractValues())
            val token = ContentFingerprint.values(listOf(contract) + inputs.flatMap { listOf(it.path, it.sha256) })
            val collector = ContentFingerprint.hash(root.resolve("collector.jar")); val processor = ContentFingerprint.hash(root.resolve("processor.jar"))
            fun encoded(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
            val raw = "format\tkartograph-processor-outputs\t1\nkind\tksp\ntoken\t$token\nprocessor\t${encoded(config.processor)}\nprocessorArtifact\t$processor\ncollectorArtifact\t$collector\n" +
                outputs.joinToString("") { "output\t${it.kind}\t${it.observation}\t${encoded(it.path)}\t${it.sha256}\n" }
            Files.writeString(root.resolve("raw.tsv"), raw)
            val observed = ProcessorOutputs(config.scope, config.kind, config.processor, processor, collector, outputs, CompilerEvidenceIndexer.sourceHash(root.resolve("raw.tsv")))
            block(root, config, ProcessorOutputReceipt(config.scope, token, contract, inputs, observed))
        } finally { root.toFile().deleteRecursively() }
    }
}
