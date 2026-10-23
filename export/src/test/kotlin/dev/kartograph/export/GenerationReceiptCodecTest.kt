package dev.kartograph.export

import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerationReceiptCodecTest {
    @Test
    fun `origin context sidecar is strict and remains separate from original receipt`() {
        val context = requireNotNull(snapshot().generationEvidence).observations.single().originContext
        val text = GenerationOriginContextCodec.render(context)
        assertEquals(context, GenerationOriginContextCodec.parse(text))
        assertEquals(text, GenerationOriginContextCodec.render(GenerationOriginContextCodec.parse(text)))
        val original = document(text)
        for (bad in listOf(
            original - "variant", original + ("version" to 2), original + ("hidden" to true),
            original + ("format" to "kartograph-generation-receipt"), original + ("providerBindings" to emptyList<Any>()),
            original + ("actionArtifacts" to List(257) { null }), original + ("receiptSha256" to null),
            original + ("providerBindings" to listOf(mapOf("property" to "navigationFiles", "paths" to List(100_001) { "x" }))),
        )) assertFailsWith<IllegalArgumentException> { GenerationOriginContextCodec.parse(jsonValue(bad)) }
        assertFailsWith<QuerySnapshotSizeException> { GenerationOriginContextCodec.parse(" ".repeat(GenerationReceiptCodec.MAX_BYTES + 1)) }
        val receiptText = GenerationReceiptCodec.render(receipt())
        assertFalse(receiptText.contains("originContext"))
        assertEquals(1L, document(receiptText)["version"])
    }

    @Test
    fun `mandatory v2 rejects old cold shape and missing altered or unsupported consumption observations`() {
        for (compact in listOf(false, true)) {
            val original = document(QuerySnapshotCodec.render(snapshot(), compact))
            val e = evidence(original)
            assertEquals(2L, e["version"])
            val observation = (e["observations"] as List<*>).single() as Map<*, *>
            val context = observation["currentContext"] as Map<*, *>
            val badObservations = listOf(
                observation - "currentGenerationStatus", observation - "currentAttempt", observation + ("hidden" to true),
                observation + ("currentContext" to null), observation + ("originContext" to null),
                observation + ("currentContext" to (context + ("variant" to "release"))),
                observation + ("currentInventorySha256" to "b".repeat(64)), observation + ("currentDeclarationsSha256" to "b".repeat(64)),
                observation + ("currentAttempt" to 1), observation + ("currentAttempt" to "b".repeat(64)),
            ) + listOf("FAILED", "PENDING", "UNKNOWN", "FROM_CACHE", "UP_TO_DATE", "SKIPPED").map { observation + ("currentGenerationStatus" to it) }
            val badEvidence = listOf(e - "observations", e + ("observations" to null), e + ("observations" to emptyList<Any>()),
                e + ("observations" to listOf(observation, observation)), (e - "observations") + ("version" to 1),
                e + ("version" to 1), e + ("observations" to List(257) { null })) + badObservations.map { e + ("observations" to listOf(it)) }
            badEvidence.forEach { assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(original + ("generationEvidence" to it))) } }
        }
    }

    @Test
    fun `historical up to date roundtrip retains current status and all graph facts without rooting`() {
        val cold = snapshot()
        val e = requireNotNull(cold.generationEvidence)
        val warm = cold.copy(generationEvidence = e.copy(observations = e.observations.map {
            it.copy(originBasis = "historical-byte-equivalence", currentGenerationStatus = "UP_TO_DATE", currentAttempt = null)
        }))
        for (compact in listOf(false, true)) {
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(warm, compact))
            assertEquals(warm.generationEvidence, restored.generationEvidence)
            assertEquals(cold.graph.nodes, restored.graph.nodes)
            assertEquals(cold.graph.edges, restored.graph.edges)
            assertEquals(cold.graph.locatedCompilerReferences, restored.graph.locatedCompilerReferences)
            assertEquals(cold.retention, restored.retention)
            assertTrue(ReachabilityAnalyzer.analyze(restored.graph, emptyList()).reachableNodeIds.isEmpty())
            assertEquals("UP_TO_DATE", requireNotNull(restored.generationEvidence).observations.single().currentGenerationStatus)
        }
    }

    @Test
    fun `standalone receipt strictly roundtrips observed sources and artifact slots`() {
        val receipt = receipt()
        val encoded = GenerationReceiptCodec.render(receipt)
        assertEquals(receipt, GenerationReceiptCodec.parse(encoded))
        assertEquals(encoded, GenerationReceiptCodec.render(GenerationReceiptCodec.parse(encoded)))
        for (text in listOf(
            encoded.replace("\"version\": 1", "\"version\": 2"),
            encoded.replaceFirst("{", "{\"extra\":true,"),
            encoded.replaceFirst("{", "{\"version\":1,"),
            encoded.replace("\"scope\": \"app:debug\", ", ""),
            encoded.replace("\"role\": \"navigation\"", "\"role\": \"unknown\""),
            encoded.replace("\"path\": \"build/generated/demo/Foo.java\"", "\"path\": \"../Foo.java\""),
            encoded.replace("\"qualifiedName\": \"demo.Foo\"", "\"qualifiedName\": 7"),
            encoded.replace("\"sources\": [{", "\"sources\": [{\"unknown\": null,"),
            encoded.replace("\"inputs\": [", "\"inputs\": [null,"),
            encoded.dropLast(4), "[]", "null",
        )) assertFailsWith<IllegalArgumentException> { GenerationReceiptCodec.parse(text) }
    }

    @Test
    fun `receipt byte limit precedes parser allocation and preserves UTF8 accounting`() {
        val over = "é".repeat(GenerationReceiptCodec.MAX_BYTES / 2 + 1)
        assertFailsWith<QuerySnapshotSizeException> { GenerationReceiptCodec.parse(over) }
        assertFailsWith<QuerySnapshotSizeException> { GenerationReceiptCodec.parse(" ".repeat(GenerationReceiptCodec.MAX_BYTES + 1)) }
        val encoded = GenerationReceiptCodec.render(receipt())
        assertFailsWith<IllegalArgumentException> { GenerationReceiptCodec.parse(encoded.replace("demo.Foo", "demo.\\ud800")) }
    }

    @Test
    fun `plain3 compact4 preserve generated identity compiler positions and all graph facts`() {
        val snapshot = snapshot()
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            assertContains(text, "\"version\": ${if (compact) 4 else 3}")
            val restored = QuerySnapshotCodec.parse(text)
            assertEquals(snapshot.generationEvidence, restored.generationEvidence)
            assertEquals(snapshot.graph.nodes, restored.graph.nodes)
            assertEquals(snapshot.graph.edges, restored.graph.edges)
            assertEquals(snapshot.graph.locatedCompilerReferences, restored.graph.locatedCompilerReferences)
            assertEquals(snapshot.retention, restored.retention)
            assertEquals(snapshot.limitations, restored.limitations)
            assertEquals(snapshot.provenance, restored.provenance)
            assertEquals(text, QuerySnapshotCodec.render(restored, compact))
            assertTrue(ReachabilityAnalyzer.analyze(restored.graph, emptyList()).reachableNodeIds.isEmpty())
        }
    }

    @Test
    fun `evidence version cannot be omitted null downgraded or structurally forged`() {
        for (compact in listOf(false, true)) {
            val original = document(QuerySnapshotCodec.render(snapshot(), compact))
            val altered = listOf(
                original - "generationEvidence",
                original + ("generationEvidence" to null),
                original + ("version" to if (compact) 2 else 1),
                original + ("version" to 5),
                original + ("generationEvidence" to mapOf("version" to 1, "receipts" to emptyList<Any>(), "declarations" to emptyList<Any>())),
                original + ("generationEvidence" to (evidence(original) + ("hidden" to true))),
                original + ("generationEvidence" to (evidence(original) + ("version" to 1))),
                original + ("generationEvidence" to (evidence(original) + ("declarations" to listOf(null)))),
                original + ("generationEvidence" to (evidence(original) + ("receipts" to "wrong"))),
            )
            altered.forEach { assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(it)) } }
        }
        assertFailsWith<IllegalArgumentException> { snapshot().copy(generationEvidence = null) }
        assertFailsWith<IllegalArgumentException> { snapshot().copy(provenance = null) }
        assertFailsWith<IllegalArgumentException> { snapshot().copy(scope = null) }
    }

    @Test
    fun `ordinary snapshots retain old versions and no mandatory extension field`() {
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), toolVersion = "test")
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            assertFalse(text.contains("generationEvidence"))
            assertEquals(if (compact) 2L else 1L, document(text)["version"])
            assertEquals(text, QuerySnapshotCodec.render(QuerySnapshotCodec.parse(text), compact))
        }
    }

    @Test
    fun `subtree raw bound recognizes escaped root key but not nested fake keys`() {
        val oversize = " ".repeat(GenerationReceiptCodec.MAX_BYTES) + "{}"
        for (key in listOf("generationEvidence", "\\u0067enerationEvidence")) {
            val failure = assertFailsWith<IllegalArgumentException> { SnapshotJsonParser("{\"$key\":$oversize}").parse() }
            assertContains(failure.message.orEmpty(), "subtree is too large")
        }
        // 큰 일반 snapshot의 다른 값에는 새로운 16MiB 문서 상한을 적용하지 않는다.
        val fake = "{\"other\":{\"generationEvidence\":$oversize}}"
        assertTrue(SnapshotJsonParser(fake).parse() is Map<*, *>)
        val escaped = "{\"generationEvidence\":{\"x\":\"{[\\\"}]}\",\"y\":[null,true,1]},\"after\":1}"
        assertEquals(1L, (SnapshotJsonParser(escaped).parse() as Map<*, *>)["after"])
        for (text in listOf("{\"generationEvidence\":\"x\"}", "{\"generationEvidence\":null}",
            "{\"generationEvidence\":[1]}", "{\"generationEvidence\":\"😀\"}")) {
            assertTrue(SnapshotJsonParser(text).parse() is Map<*, *>)
        }
        for (text in listOf("{\"generationEvidence\":", "{\"generationEvidence\":{", "{\"generationEvidence\":\"\\")) {
            assertFailsWith<IllegalArgumentException> { SnapshotJsonParser(text).parse() }
        }
    }

    @Test
    fun `subtree parse rejects excessive row counts and unknown declaration fields`() {
        val original = document(QuerySnapshotCodec.render(snapshot()))
        val e = evidence(original)
        val declared = (e["declarations"] as List<*>).single() as Map<*, *>
        for (bad in listOf(
            e + ("receipts" to List(257) { null }),
            e + ("declarations" to List(100_001) { null }),
            e + ("declarations" to listOf(declared + ("hidden" to true))),
            e + ("declarations" to listOf(declared + ("symbols" to List(100_001) { "class:A" }))),
            e + ("declarations" to listOf(declared + ("owner" to "x".repeat(16_385)))),
        )) assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(original + ("generationEvidence" to bad))) }
    }

    @Test
    fun `actual supplied old CLI refuses mandatory snapshot versions`(@TempDir root: Path) {
        val oldHome = System.getenv("KARTOGRAPH_GENERATION_OLD_HOME")
        assumeTrue(!oldHome.isNullOrBlank(), "an actual old distribution is required")
        val home = Path.of(requireNotNull(oldHome)).toRealPath()
        require(Files.isDirectory(home.resolve("lib"))) { "old distribution libraries are absent" }
        val oldLibraries = Files.list(home.resolve("lib")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList().joinToString(File.pathSeparator)
        }
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val reader = root.resolve("OldGenerationReader.java")
        Files.writeString(reader, """
            import java.nio.file.*;
            public class OldGenerationReader {
                public static void main(String[] args) throws Exception {
                    try {
                        dev.kartograph.export.QuerySnapshotCodec.INSTANCE.parse(Files.readString(Path.of(args[0])));
                        throw new AssertionError("old reader accepted mandatory generation evidence");
                    } catch (IllegalArgumentException expected) {
                        if (!expected.getMessage().contains("unsupported query snapshot")) throw expected;
                        System.out.println(expected.getMessage());
                    }
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", oldLibraries,
            "-d", root.toString(), reader.toString()))
        for (compact in listOf(false, true)) {
            val file = root.resolve("generated-$compact.json")
            Files.writeString(file, QuerySnapshotCodec.render(snapshot(), compact))
            val output = root.resolve("old-$compact.txt")
            val process = ProcessBuilder(java.toString(), "-cp", oldLibraries, "dev.kartograph.cli.MainKt",
                "query", OWNER.value, "--graph-file", file.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("old reader did not exit within its test budget")
            }
            assertEquals(2, process.exitValue(), Files.readString(output))
            assertContains(Files.readString(output), "unable to read query snapshot")
            val codecOutput = root.resolve("old-codec-$compact.txt")
            val codec = ProcessBuilder(java.toString(), "-cp", root.toString() + File.pathSeparator + oldLibraries,
                "OldGenerationReader", file.toString()).redirectErrorStream(true).redirectOutput(codecOutput.toFile()).start()
            if (!codec.waitFor(30, TimeUnit.SECONDS)) {
                codec.destroyForcibly()
                error("old codec did not exit within its test budget")
            }
            assertEquals(0, codec.exitValue(), Files.readString(codecOutput))
            assertContains(Files.readString(codecOutput), "unsupported query snapshot")
            println("Old supplied distribution refused snapshot version ${if (compact) 4 else 3}: CLI exit 2, codec unsupported query snapshot")
        }
    }

    companion object {
        private val HASH = "a".repeat(64)
        private val OWNER = NodeId("class:demo/Foo")
        private val METHOD = NodeId("method:demo/Foo#run()V")
        private fun input(role: String, path: String) = InputFingerprint(role, path, HASH)
        private fun receipt() = GenerationReceipt("app:debug", ":app:generateSafeArgsDebug", GenerationReceipt.POLICY, HASH, "build/generated",
            listOf(input("navigation", "res/navigation/main.xml"), input("generatorArtifact", "external/generator-0"),
                input("generatorActionArtifact", "external/action-0"), input("generatorOptions", "config/options")),
            input("generationMapping", "build/mapping.json"), listOf(input("generatedSource", "build/generated/demo/Foo.java")),
            listOf(GenerationSource("res/navigation/main.xml", "build/generated/demo/Foo.java", "demo.Foo", HASH)))
        private fun snapshot(): QuerySnapshot {
            val graph = CodeGraph(listOf(GraphNode(OWNER, "Foo", NodeKind.CLASS, synthesized = true,
                attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE)), GraphNode(METHOD, "run", NodeKind.METHOD, synthesized = true,
                attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE))),
                listOf(GraphEdge(OWNER, METHOD, EdgeKind.MEMBER), GraphEdge(METHOD, METHOD, EdgeKind.CALL, callSiteLines = listOf(2))))
                .withCompilerCallPositions(listOf(LocatedCompilerReference(METHOD, METHOD,
                    CompilerEvidenceSource("build/generated/demo/Foo.java", HASH), "javac-constants", "17.0.20+1",
                    CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 10, 14, 2, 1, true)))
            val declaration = GeneratedDeclaration("app:debug", ":app:generateSafeArgsDebug", HASH, ":app:compileDebugJavaWithJavac",
                "build/generated/demo/Foo.java", HASH, OWNER, listOf(OWNER, METHOD), "build/classes", "build/classes", "demo/Foo.class", HASH)
            val provenance = SnapshotProvenance(listOf(input("classes", "build/classes")), listOf(BuildWitness("app:debug", "javac", ":app:compileDebugJavaWithJavac",
                listOf(input("sources", "build/generated"), input("buildConfig", "build.gradle"), input("compiler", "external/compiler"), input("options", "config/javac")),
                listOf(input("classes", "build/classes")))))
            return QuerySnapshot(graph, emptyList(), listOf("runtime-unknown"), scope = "app:debug", provenance = provenance,
                generationEvidence = generationEvidence(receipt(), declaration, provenance))
        }
        private fun generationEvidence(receipt: GenerationReceipt, declaration: GeneratedDeclaration, provenance: SnapshotProvenance): GenerationEvidence {
            val context = GenerationOriginContext(GenerationDigests.receipt(receipt), receipt.scope, receipt.task, "debug", receipt.outputRoot, receipt.mapping.path,
                listOf(GenerationProviderBinding("navigationFiles", listOf("res/navigation/main.xml")), GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)),
                    GenerationProviderBinding("incrementalFolder", listOf("build"))), GenerationOriginContext.ACTION_POLICY,
                receipt.inputs.filter { it.role == "generatorActionArtifact" })
            val observation = GenerationConsumptionObservation(context, context, HASH, receipt.attempt, "current-cold-success", "SUCCEEDED",
                GenerationDigests.inventory(receipt), GenerationDigests.declarations(listOf(declaration), provenance))
            return GenerationEvidence(listOf(receipt), listOf(declaration), listOf(observation))
        }
        private fun document(text: String): Map<*, *> = SnapshotJsonParser(text).parse() as Map<*, *>
        private fun evidence(document: Map<*, *>): Map<*, *> = document["generationEvidence"] as Map<*, *>
    }
}
