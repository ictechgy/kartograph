package dev.kartograph.gradle

import dev.kartograph.core.*
import dev.kartograph.export.*
import dev.kartograph.index.*
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/** 실제 javac class와 합성 lifecycle/compiler receipt로 live 소비 경계를 검증한다. Android 관측은 native fixture에 있다. */
class SafeArgsLiveCaptureTest {
    @Test fun `live main subset은 실제 선언만 분류하고 snapshot test root와 필터를 보존한다`(@TempDir root: Path) {
        val f = fixture(root)
        val prepared = f.capture().prepare()
        val indexed = ClassFileIndexer().indexForGraphExport(listOf(f.main), options = GraphExportIndexOptions(excludedOrigins = setOf(EdgeOrigin.BYTECODE)))
        val original = indexed.indexed.graph
        val result = prepared.attribute(indexed.indexed, original, listOf(f.main))
        assertEquals(1, result.evidence?.declarations?.size)
        assertTrue(result.graph.nodes.getValue(NodeId("class:p/MainMade")).synthesized)
        assertFalse(result.graph.nodes.getValue(NodeId("class:p/Manual")).synthesized)
        assertFalse(NodeId("class:p/TestMade") in result.graph.nodes)
        assertEquals(original.edges, result.graph.edges)
        assertTrue(result.limitations.any { it.contains("snapshot roots=2, live roots=1") })
        result.verifyBeforeWrite()
    }

    @Test fun `live가 제외한 test root 변경도 전체 proof를 stale로 만든다`(@TempDir root: Path) {
        val f = fixture(root)
        val before = f.capture().prepare()
        val indexed = ClassFileIndexer().indexWithObservations(listOf(f.main))
        Files.writeString(root.resolve("generated/p/TestMade.java"), "package p; class TestMade {} // edited")
        val result = before.attribute(indexed, indexed.graph, listOf(f.main))
        assertNull(result.evidence)
        assertEquals(indexed.graph.nodes, result.graph.nodes)
        assertTrue(result.limitations.single().startsWith("generation-live-unverified:"))
    }

    @Test fun `mtime 보존 source 수정과 미관측 live root는 승격하지 않는다`(@TempDir root: Path) {
        val f = fixture(root)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(f.main))
        val outside = root.resolve("outside"); Files.createDirectories(outside)
        assertNull(f.capture().prepare().attribute(indexed, indexed.graph, listOf(outside)).evidence)
        val source = root.resolve("generated/p/MainMade.java"); val time = Files.getLastModifiedTime(source)
        Files.writeString(source, Files.readString(source) + " // edited")
        Files.setLastModifiedTime(source, time)
        assertNull(f.capture().prepare().attribute(indexed, indexed.graph, listOf(f.main)).evidence)
    }

    @Test fun `출력 직전 context와 원본 bytes 변경은 명시적으로 거부한다`(@TempDir root: Path) {
        val f = fixture(root)
        val prepared = f.capture().prepare()
        val indexed = ClassFileIndexer().indexWithObservations(listOf(f.main))
        val result = prepared.attribute(indexed, indexed.graph, listOf(f.main))
        assertNotNull(result.evidence)
        f.current = f.current.copy(reason = "changed")
        assertFailsWith<IllegalArgumentException> { result.verifyBeforeWrite() }
        f.current = f.current.copy(reason = null)
        Files.writeString(root.resolve("generated/p/TestMade.java"), "package p; class TestMade {}")
        assertFailsWith<IllegalArgumentException> { result.verifyBeforeWrite() }
    }

    @Test fun `없는 증거와 byte 한도 초과는 일반 graph를 유지하며 unknown이다`(@TempDir root: Path) {
        val f = fixture(root)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(f.main))
        val limited = SafeArgsLiveCapture(root, SCOPE, { f.snapshot }, { f.bindings }, { 32 }, { f.current })
        val result = limited.prepare().attribute(indexed, indexed.graph, listOf(f.main))
        assertNull(result.evidence); assertEquals(indexed.graph.nodes, result.graph.nodes)
        Files.delete(f.snapshot)
        assertNull(f.capture().prepare().attribute(indexed, indexed.graph, listOf(f.main)).evidence)
    }

    private data class Fixture(val root: Path, val main: Path, val snapshot: Path, val bindings: Path,
        var current: SafeArgsGenerationLifecycle.Result) {
        fun capture() = SafeArgsLiveCapture(root, SCOPE, { snapshot }, { bindings }, { QuerySnapshotCodec.MAX_BYTES }, { current })
    }

    private fun fixture(root: Path): Fixture {
        fun write(path: String, content: String) = root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, content) }
        fun fingerprint(path: Path, role: String) = InputFingerprint(role, root.relativize(path).invariantSeparatorsPathString, ContentFingerprint.hashInput(path, role))
        val made = write("generated/p/MainMade.java", "package p; public class MainMade { public int run() { return Manual.run(); } }")
        val manual = write("src/p/Manual.java", "package p; public class Manual { public static int run() { return 1; } }")
        val test = write("generated/p/TestMade.java", "package p; public class TestMade {}")
        val main = root.resolve("classes/main"); Files.createDirectories(main)
        val extra = root.resolve("classes/test"); Files.createDirectories(extra)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", main.toString(), made.toString(), manual.toString()))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", extra.toString(), test.toString()))
        val config = write("build.gradle", "// synthetic compiler witness fixture")
        val compiler = write("compiler.bin", "synthetic compiler identity; class bytes produced by actual javac")
        val outputs = listOf(main, extra).map { fingerprint(it, "classes") }
        val witnesses = listOf(listOf(made, manual), listOf(test)).mapIndexed { i, sources ->
            BuildWitness(SCOPE, "javac", if(i == 0) ":compileJava" else ":compileTestJava",
                sources.map { fingerprint(it, "sources") } + listOf(fingerprint(config, "buildConfig"), fingerprint(compiler, "compiler"),
                    InputFingerprint("options", "options/javac", HASH)), listOf(outputs[i]))
        }
        val witnessFiles = witnesses.mapIndexed { i, witness -> write("witness/$i.json", BuildWitnessCodec.render(witness)) }
        val provenance = SnapshotProvenance(outputs + witnessFiles.map { fingerprint(it, "witness") }, witnesses)
        val sourceRows = listOf(made to "p.MainMade", test to "p.TestMade").map { (path, name) ->
            GenerationSource("res/nav.xml", root.relativize(path).invariantSeparatorsPathString, name, CompilerEvidenceIndexer.sourceHash(path))
        }
        val receipt = GenerationReceipt(SCOPE, ":generateSafeArgsDebug", GenerationReceipt.POLICY, HASH, "generated", listOf(
            InputFingerprint("navigation", "res/nav.xml", HASH), InputFingerprint("generatorArtifact", "external/generator", HASH),
            InputFingerprint("generatorActionArtifact", "external/action", HASH), InputFingerprint("generatorOptions", "generation/options", HASH)),
            InputFingerprint("generationMapping", "state/mapping.json", HASH), sourceRows.map { InputFingerprint("generatedSource", it.path, it.sha256) }, sourceRows)
        val context = GenerationOriginContext(GenerationDigests.receipt(receipt), SCOPE, receipt.task, "debug", "generated", receipt.mapping.path,
            listOf(GenerationProviderBinding("navigationFiles", listOf("res/nav.xml")), GenerationProviderBinding("outputDir", listOf("generated")),
                GenerationProviderBinding("incrementalFolder", listOf("state"))), GenerationOriginContext.ACTION_POLICY,
            receipt.inputs.filter { it.role == "generatorActionArtifact" })
        val indexed = ClassFileIndexer().indexWithObservations(listOf(main, extra))
        val attributed = GeneratedSourceIndexer.attribute(root, indexed, indexed.graph, listOf(main, extra), provenance, SCOPE, listOf(receipt))
        assertEquals(2, attributed.declarations.size)
        val observation = GenerationConsumptionObservation(context, context, HASH, HASH, "current-cold-success", "SUCCEEDED",
            GenerationDigests.inventory(receipt), GenerationDigests.declarations(attributed.declarations, provenance))
        val evidence = GenerationEvidence(listOf(receipt), attributed.declarations, listOf(observation))
        val snapshot = write("snapshot.json", QuerySnapshotCodec.render(QuerySnapshot(attributed.graph, emptyList(), emptyList(),
            scope = SCOPE, provenance = provenance, generationEvidence = evidence)))
        val bindings = write("bindings.json", ExternalInputBindingsCodec.render(emptyMap()))
        val current = SafeArgsGenerationLifecycle.Result(GenerationReceiptCodec.render(receipt), null, false,
            originContext = context, currentContext = context, invocation = HASH, currentAttempt = HASH,
            originBasis = "current-cold-success", currentGenerationStatus = "SUCCEEDED")
        return Fixture(root, main, snapshot, bindings, current)
    }
    companion object { private const val SCOPE = "app:debug"; private val HASH = "a".repeat(64) }
}
