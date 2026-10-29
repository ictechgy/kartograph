package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** generator lifecycle은 plugin 테스트가 담당하며 이 테스트는 실제 javac bytecode의 source/class join을 검사한다. */
class GeneratedSourceIndexerTest {
    @Test fun `실제 javac outer와 nested만 분류하고 같은 basename 수기 class 및 CALL은 보존한다`(@TempDir root: Path) {
        val fixture = fixture(root)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(fixture.classes))
        val graph = richGraph(indexed.graph, fixture)
        val result = GeneratedSourceIndexer.attribute(root, indexed, graph, listOf(fixture.classes), fixture.provenance,
            SCOPE, listOf(fixture.receipt))
        val owners = result.declarations.map { it.owner.value }.toSet()
        assertEquals(setOf("class:p/Generated", "class:p/Generated\$Nested"), owners)
        assertTrue(result.graph.nodes.getValue(JvmNodeId.classId("p/Generated")).synthesized)
        for (name in listOf("other/Generated", "p/Handwritten", "p/Generated\$Pretender")) {
            assertFalse(result.graph.nodes.getValue(JvmNodeId.classId(name)).synthesized, name)
        }
        assertEquals(graph.edges, result.graph.edges)
        assertEquals(graph.externalCalls, result.graph.externalCalls)
        assertTrue(result.graph.compilerCallPositionsCaptured)
        assertEquals(graph.locatedCompilerReferences, result.graph.locatedCompilerReferences)
        assertEquals(graph.compilerAuthoredCallers, result.graph.compilerAuthoredCallers)
        assertEquals(graph.compilerCallerInventories, result.graph.compilerCallerInventories)
        assertEquals(graph.compilerSourceUseEvidence, result.graph.compilerSourceUseEvidence)
        assertEquals(1, result.graph.locatedCompilerReferences.size)
        assertEquals(1, result.graph.compilerAuthoredCallers.single().calls.size)
        assertEquals(1, result.graph.compilerCallerInventories.size)
        assertEquals(1, result.graph.compilerSourceUseEvidence!!.uses.size)
        evidence(result, fixture.provenance).requireMatches(result.graph, fixture.provenance, SCOPE)
        assertTrue(result.limitations.any { it.contains("not runtime reachability") })
    }

    @Test fun `debug 신원 없는 class는 mapping 이름으로 분류하지 않는다`(@TempDir root: Path) {
        val fixture = fixture(root, debug = false)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(fixture.classes))
        val result = GeneratedSourceIndexer.attribute(root, indexed, indexed.graph, listOf(fixture.classes), fixture.provenance,
            SCOPE, listOf(fixture.receipt))
        assertTrue(result.declarations.isEmpty())
        assertFalse(result.graph.nodes.getValue(JvmNodeId.classId("p/Generated")).synthesized)
    }

    @Test fun `선택된 첫 root가 다른 구현이면 생성 source의 class로 분류하지 않는다`(@TempDir root: Path) {
        val fixture = fixture(root)
        val hand = root.resolve("hand/Generated.java"); Files.createDirectories(hand.parent)
        Files.writeString(hand, "package p; public class Generated { public static int run() { return 90; } }")
        val shadow = root.resolve("shadow"); Files.createDirectories(shadow)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", shadow.toString(), hand.toString()))
        val roots = listOf(shadow, fixture.classes)
        val indexed = ClassFileIndexer().indexWithObservations(roots)
        val provenance = fixture.provenance.copy(inputs = fixture.provenance.inputs + fingerprint(root, shadow, "classes"))
        val result = GeneratedSourceIndexer.attribute(root, indexed, indexed.graph, roots, provenance, SCOPE, listOf(fixture.receipt))
        assertFalse(result.graph.nodes.getValue(JvmNodeId.classId("p/Generated")).synthesized)
        assertTrue(result.declarations.none { it.owner == JvmNodeId.classId("p/Generated") })
    }

    @Test fun `mtime를 유지한 source 편집과 scope 불일치는 증거로 승격하지 않는다`(@TempDir root: Path) {
        val fixture = fixture(root)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(fixture.classes))
        assertFailsWith<IllegalArgumentException> { GeneratedSourceIndexer.attribute(root, indexed, indexed.graph,
            listOf(fixture.classes), fixture.provenance, "app:release", listOf(fixture.receipt)) }
        val source = root.resolve(fixture.receipt.sources.single().path)
        val time = Files.getLastModifiedTime(source)
        Files.writeString(source, Files.readString(source) + "\n// manually edited")
        Files.setLastModifiedTime(source, time)
        assertFailsWith<IllegalArgumentException> { GeneratedSourceIndexer.attribute(root, indexed, indexed.graph,
            listOf(fixture.classes), fixture.provenance, SCOPE, listOf(fixture.receipt)) }
    }

    @Test fun `directory sourcepath 및 모호한 compiler 소유권은 primary unit 증명이 아니다`(@TempDir root: Path) {
        val fixture = fixture(root)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(fixture.classes))
        val witness = fixture.provenance.witnesses.single()
        for (witnesses in listOf(listOf(witness, witness.copy(artifact = ":otherCompile")),
            listOf(witness.copy(inputs = witness.inputs + fingerprint(root, root.resolve("generated"), "sources"))))) {
            val result = GeneratedSourceIndexer.attribute(root, indexed, indexed.graph, listOf(fixture.classes),
                fixture.provenance.copy(witnesses = witnesses), SCOPE, listOf(fixture.receipt))
            assertTrue(result.declarations.isEmpty())
        }
    }

    private data class Fixture(val classes: Path, val provenance: SnapshotProvenance, val receipt: GenerationReceipt)
    /** 채널 보존용 합성 compiler metadata이다. 실제 collector 실행 증거와 구분한다. */
    private fun richGraph(graph: CodeGraph, fixture: Fixture): CodeGraph {
        val caller = NodeId("method:p/Generated#run()I")
        val target = NodeId("method:p/Handwritten#run()I")
        val file = CompilerEvidenceSource("generated/p/Generated.java", fixture.receipt.sources.single().sha256)
        val basis = CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
        val authored = CompilerAuthoredCaller(caller, file, 0, 50, basis, "17.0.20", 1, 1, AuthoredCallCoverage.COMPLETE,
            emptyList(), listOf(CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/p/Handwritten.java", 10, 16)), HASH, HASH, HASH)
        val inventory = CompilerCallerInventory(SCOPE, ":compileJava", "classes", listOf(file),
            listOf(CompilerSourceBody("b0", file, 0, 50, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(caller))),
            listOf(CompilerInventoryOutput("p/Generated.class", HASH, "p/Generated")),
            listOf(CompilerInventoryMethod(caller, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
            basis, "17.0.20", HASH, HASH, HASH, HASH)
        val output = fixture.provenance.inputs.single()
        val root = CompilerSourceUseRoot(0, output, listOf(caller, target))
        val unit = CompilerSourceUseUnit(InputFingerprint("compilerEvidence", "uses.tsv", HASH), "javac-constants", "17.0.20", HASH, HASH,
            listOf(CompilerSourceUseClaim(SCOPE, "javac", ":compileJava", listOf(output))),
            CompilerSourceUseProducerCounts(1, 1, 0, 0), CompilerSourceUseImportCounts(1, 0, 0, 0, 0, 0), 0)
        val use = CompilerSourceUse(unit.id, caller, target, root.id, root.id, CompilerSourceUseKind.INVOCATION_SELECTION,
            file, "javac-constants", "17.0.20", basis, 10, 16, 1, 11, false)
        return graph.withCompilerCallPositions(listOf(LocatedCompilerReference(caller, target, file, "javac-constants", "17.0.20", basis, 10, 16, 1, 11)))
            .withCompilerAuthoredCalls(listOf(authored)).withCompilerCallerInventories(listOf(inventory))
            .withCompilerSourceUses(CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(use)))
    }

    private fun evidence(result: GeneratedSourceIndexer.Result, provenance: SnapshotProvenance): GenerationEvidence {
        val observations = result.receipts.map { receipt ->
            val context = GenerationOriginContext(GenerationDigests.receipt(receipt), SCOPE, receipt.task, "debug", receipt.outputRoot,
                receipt.mapping.path, listOf(GenerationProviderBinding("navigationFiles", receipt.inputs.filter { it.role == "navigation" }.map { it.path }),
                    GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)),
                    GenerationProviderBinding("incrementalFolder", listOf(receipt.mapping.path.substringBeforeLast('/')))),
                GenerationOriginContext.ACTION_POLICY, receipt.inputs.filter { it.role == "generatorActionArtifact" })
            GenerationConsumptionObservation(context, context, HASH, receipt.attempt, "current-cold-success", "SUCCEEDED",
                GenerationDigests.inventory(receipt), GenerationDigests.declarations(result.declarations, provenance))
        }
        return GenerationEvidence(result.receipts, result.declarations, observations)
    }
    private fun fixture(root: Path, debug: Boolean = true): Fixture {
        val sources = linkedMapOf(
            "generated/p/Generated.java" to "package p; public class Generated { public static class Nested { public int run() { return Handwritten.run(); } } public static int run() { return Handwritten.run(); } } class Generated\$Pretender { public int run() { return 3; } }",
            "src/p/Handwritten.java" to "package p; public class Handwritten { public static int run() { return 1; } }",
            "src/other/Generated.java" to "package other; public class Generated { public int run() { return 2; } }",
        ).map { (path, content) -> root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, content) } }
        val classes = root.resolve("classes"); Files.createDirectories(classes)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            *(listOf(if (debug) "-g" else "-g:none", "-d", classes.toString()) + sources.map { it.toString() }).toTypedArray()))
        val config = root.resolve("build.gradle"); Files.writeString(config, "// synthetic test compiler binding")
        val output = fingerprint(root, classes, "classes")
        val witness = BuildWitness(SCOPE, "javac", ":compileJava", sources.map { fingerprint(root, it, "sources") } + listOf(
            fingerprint(root, config, "buildConfig"), InputFingerprint("compiler", "external/compiler", HASH),
            InputFingerprint("options", "options/javac", HASH)), listOf(output))
        val generated = "generated/p/Generated.java"
        val hash = CompilerEvidenceIndexer.sourceHash(root.resolve(generated))
        val receipt = GenerationReceipt(SCOPE, ":generateSafeArgsDebug", GenerationReceipt.POLICY, HASH, "generated",
            listOf(InputFingerprint("navigation", "res/navigation.xml", HASH), InputFingerprint("generatorArtifact", "external/generator", HASH),
                InputFingerprint("generatorActionArtifact", "external/action", HASH), InputFingerprint("generatorOptions", "generation/options", HASH)),
            InputFingerprint("generationMapping", "state/mapping.json", HASH), listOf(InputFingerprint("generatedSource", generated, hash)),
            listOf(GenerationSource("res/navigation.xml", generated, "p.Generated", hash)))
        return Fixture(classes, SnapshotProvenance(listOf(output), listOf(witness)), receipt)
    }
    private fun fingerprint(root: Path, path: Path, role: String) =
        InputFingerprint(role, root.relativize(path).invariantSeparatorsPathString, ContentFingerprint.hashInput(path, role))
    companion object { private const val SCOPE = "app:debug"; private val HASH = "a".repeat(64) }
}
