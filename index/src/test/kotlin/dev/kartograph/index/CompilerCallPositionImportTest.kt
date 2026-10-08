package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.core.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class CompilerCallPositionImportTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "KARTOGRAPH_COLLECTOR_JAR", matches = ".+")
    fun `packaged javac collector completes receipt and imports its exact selector`(@TempDir root: Path) {
        val collector = Path.of(requireNotNull(System.getenv("KARTOGRAPH_COLLECTOR_JAR"))).toRealPath()
        require(Files.isRegularFile(collector) && !Files.isSymbolicLink(collector))
        val javac = Path.of(System.getProperty("java.home"), "bin", "javac").toRealPath()
        val sources = Files.createDirectories(root.resolve("src/demo"))
        val callerFile = sources.resolve("Caller.java")
        val targetFile = sources.resolve("Target.java")
        val callerText = "package demo; public class Caller { public void use() { Target.hit(); } }\n"
        Files.writeString(callerFile, callerText)
        Files.writeString(targetFile, "package demo; public class Target { public static void hit() {} }\n")
        val classes = Files.createDirectories(root.resolve("classes"))
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        Files.writeString(root.resolve("witness.json"), "unit witness fixture")

        fun fp(role: String, path: Path, slot: String): InputFingerprint =
            ContentFingerprint.capture(root, path, role, slot)
        val compilerInput = fp("compiler", javac, "javac")
        val collectorInput = fp("processor", collector, "collector")
        val inputs = listOf(
            fp("sources", root.resolve("src"), "sources"),
            fp("buildConfig", root.resolve("build.gradle"), "build-config"),
            compilerInput,
            collectorInput,
            InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("-g", "-proc:none"))),
            CompilerCallPositionOptions.enabledInput(),
        )
        val token = CompilerEvidenceToken.create("sample:main", "javac", ":compileJava", inputs)
        val tokenFile = Files.writeString(root.resolve("token"), token)
        val document = root.resolve("evidence.tsv")
        val plugin = "-Xplugin:KartographEvidence collector=javac-constants root=${root.toUri()} " +
            "output=${document.toUri()} token=${tokenFile.toUri()} callPositions=true"
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-proc:none", "-processorpath", collector.toString(), plugin,
            "-d", classes.toString(), callerFile.toString(), targetFile.toString(),
        ))

        val envelope = CompilerEvidenceReader.readEnvelope(document)
        assertEquals(collectorInput.sha256, envelope.evidence.artifactSha256)
        assertEquals(1, envelope.callStats?.emitted)
        val receipts = CompilerEvidenceReceipts.validate(
            root, "javac", token, inputs, listOf(document), setOf(callerFile, targetFile),
            listOf(root.resolve("src")), emptyList(), "compileJava",
        )
        val output = fp("classes", classes, "classes")
        val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(output), receipts, token)
        val provenance = SnapshotProvenance(
            listOf(output, fp("witness", root.resolve("witness.json"), "witness")), listOf(witness),
        )
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        val enrichment = CompilerEvidenceIndexer.enrichWithCallPositions(
            indexed,
            listOf(classes),
            listOf(document),
            CompilerEvidenceContext(
                root,
                "sample:main",
                provenance,
                mapOf(compilerInput.path to javac, collectorInput.path to collector),
            ),
        )

        assertEquals(indexed.graph.edges, enrichment.result.graph.edges)
        val position = enrichment.result.graph.locatedCompilerReferences.single()
        val caller = JvmNodeId.methodId("demo/Caller", "use", "()V")
        val target = JvmNodeId.methodId("demo/Target", "hit", "()V")
        val start = callerText.indexOf("hit")
        assertEquals(caller, position.source)
        assertEquals(target, position.target)
        assertEquals("src/demo/Caller.java", position.file.path)
        assertEquals(CompilerEvidenceIndexer.sourceHash(callerFile), position.file.sha256)
        assertEquals(envelope.evidence.compilerVersion, position.compilerVersion)
        assertEquals(CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, position.coordinateBasis)
        assertEquals(start, position.offsetUtf16)
        assertEquals(start + 3, position.endOffsetUtf16)
        assertEquals(1, position.line)
        assertEquals(start + 1, position.column)
        assertEquals("javac-constants", position.collector)
        val stats = requireNotNull(envelope.callStats)
        val expectedLimitations = buildList {
            if (stats.unmapped > 0) add("compiler-call-positions-unmapped: ${stats.unmapped}")
            if (stats.ambiguous > 0) add("compiler-call-positions-ambiguous: ${stats.ambiguous}")
        }
        assertEquals(expectedLimitations, enrichment.limitations)
    }

    @Test
    fun `completed v3 receipt attaches exact positions without changing bytecode graph`(@TempDir root: Path) {
        val fixture = fixture(root)
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrich(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        }.also { assertContains(it.message.orEmpty(), "envelope") }

        val enrichment = fixture.enrich()
        val graph = enrichment.result.graph
        assertEquals(fixture.indexed.graph.edges, graph.edges)
        assertEquals(fixture.indexed.graph.edgeCount, graph.edgeCount)
        assertTrue(graph.compilerCallPositionsCaptured)
        val position = graph.locatedCompilerReferences.single()
        assertEquals(fixture.caller, position.source)
        assertEquals(fixture.calledTarget, position.target)
        assertEquals(fixture.callerPath, position.file.path)
        assertEquals(CompilerEvidenceIndexer.sourceHash(root.resolve(fixture.callerPath)), position.file.sha256)
        assertEquals("javac-constants", position.collector)
        assertEquals("17.0.20+8", position.compilerVersion)
        assertEquals(CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, position.coordinateBasis)
        assertEquals(fixture.start, position.offsetUtf16)
        assertEquals(fixture.start + 3, position.endOffsetUtf16)
        assertEquals(1, position.line)
        assertEquals(fixture.start + 1, position.column)
        assertFalse(position.generated)
        assertEquals(emptyList(), enrichment.limitations)
        assertTrue(graph.edges.any { it.source == fixture.caller && it.target == fixture.calledTarget &&
            it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE })
    }

    @Test
    fun `legacy constant enrichment preserves positions already attached to the graph`(@TempDir root: Path) {
        val fixture = fixture(root)
        val positioned = fixture.enrich().result.graph
        val legacy = Files.readString(fixture.document).lineSequence()
            .filterNot { it.startsWith("callStats\t") || it.startsWith("call\t") }
            .joinToString("\n")
            .replace("evidence\t3", "evidence\t1") +
            "\nedge\t${encode(fixture.caller.value)}\t${encode(fixture.calledTarget.value)}\tconstant\n"
        Files.writeString(fixture.document, legacy)

        val witness = fixture.context.provenance.witnesses.single()
        val receipts = CompilerEvidenceReceipts.validate(
            root, "javac", witness.evidenceToken!!, witness.inputs, listOf(fixture.document),
            setOf(root.resolve(fixture.callerPath), root.resolve("src/Target.java")),
            listOf(root.resolve("src")), emptyList(), "compileJava",
        )
        val completed = witness.copy(compilerEvidence = receipts)
        val context = fixture.context.copy(
            provenance = fixture.context.provenance.copy(witnesses = listOf(completed)),
        )
        val publicConstructorGraph = CodeGraph(
            positioned.nodes.values,
            positioned.edges,
            positioned.externalCalls,
            positioned.serviceProviders,
            positioned.enclosures,
            positioned.callbackArguments,
            positioned.parameterUses,
            positioned.lambdaEscapes,
        ).withCompilerCallPositions(positioned.locatedCompilerReferences)
        val indexed = IndexedClasses(
            publicConstructorGraph,
            fixture.indexed.observations,
            fixture.indexed.hierarchy,
            fixture.indexed.declarationsByRoot,
            fixture.indexed.selectedRootByNode,
            fixture.indexed.statistics,
        )

        val enriched = CompilerEvidenceIndexer.enrich(
            indexed, listOf(fixture.classes), listOf(fixture.document), context,
        ).graph
        val expected = publicConstructorGraph.enrichedWith(listOf(
            dev.kartograph.core.GraphEdge(
                fixture.caller,
                fixture.calledTarget,
                EdgeKind.REFERENCE,
                origin = EdgeOrigin.COMPILER_REFERENCE,
            ),
        ), publicConstructorGraph.externalCalls)
        assertTrue(enriched.compilerCallPositionsCaptured)
        assertEquals(expected.nodes, enriched.nodes)
        assertEquals(expected.edges, enriched.edges)
        assertEquals(expected.externalCalls, enriched.externalCalls)
        assertEquals(expected.serviceProviders, enriched.serviceProviders)
        assertEquals(expected.enclosures, enriched.enclosures)
        assertEquals(expected.callbackArguments, enriched.callbackArguments)
        assertEquals(expected.parameterUses, enriched.parameterUses)
        assertEquals(expected.lambdaEscapes, enriched.lambdaEscapes)
        assertEquals(expected.locatedCompilerReferences, enriched.locatedCompilerReferences)
        assertEquals(expected.outgoingEdgesFrom(fixture.caller), enriched.outgoingEdgesFrom(fixture.caller))
        assertEquals(expected.incomingEdgesTo(fixture.calledTarget), enriched.incomingEdgesTo(fixture.calledTarget))
    }

    @Test
    fun `v3 receipt requires the token-bound call positions option marker`(@TempDir root: Path) {
        val failure = assertFailsWith<IllegalArgumentException> { fixture(root, optionMarker = false) }
        assertContains(failure.message.orEmpty(), "call positions option")
    }

    @Test
    fun `v3 option marker must be the sole fingerprint for its input path`(@TempDir root: Path) {
        val enabled = CompilerCallPositionOptions.enabledInput()
        val disabled = InputFingerprint(
            "options",
            CompilerCallPositionOptions.INPUT_PATH,
            ContentFingerprint.values(listOf("callPositions=false")),
        )
        assertTrue(CompilerCallPositionOptions.isEnabled(listOf(enabled)))
        assertFalse(CompilerCallPositionOptions.isEnabled(listOf(enabled, enabled)))
        assertFalse(CompilerCallPositionOptions.isEnabled(listOf(enabled, disabled)))

        val fixture = fixture(root)
        val witness = fixture.context.provenance.witnesses.single()
        val inputs = witness.inputs + disabled
        val token = CompilerEvidenceToken.create(witness.scope, witness.compiler, witness.artifact, inputs)
        Files.writeString(
            fixture.document,
            Files.readString(fixture.document).replace(witness.evidenceToken!!, token),
        )
        val project = fixture.context.project
        val receiptFailure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceReceipts.validate(
                project,
                "javac",
                token,
                inputs,
                listOf(fixture.document),
                setOf(project.resolve(fixture.callerPath), project.resolve("src/Target.java")),
                listOf(project.resolve("src")),
                emptyList(),
                "compileJava",
            )
        }
        assertContains(receiptFailure.message.orEmpty(), "call positions option")

        val forgedReceipt = ContentFingerprint.capture(
            project, fixture.document, "compilerEvidence", "compileJava-evidence-0",
        )
        val completed = witness.copy(
            inputs = inputs,
            compilerEvidence = listOf(forgedReceipt),
            evidenceToken = token,
        )
        val context = fixture.context.copy(
            provenance = fixture.context.provenance.copy(witnesses = listOf(completed)),
        )
        val importFailure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithCallPositions(
                fixture.indexed, listOf(fixture.classes), listOf(fixture.document), context,
            )
        }
        assertContains(importFailure.message.orEmpty(), "call positions option")
    }

    @Test
    fun `unmatched outside and absent-owner targets are handled independently`(@TempDir root: Path) {
        val unmatched = fixture(root.resolve("unmatched"), calledMethod = "other")
        val unmatchedResult = unmatched.enrich()
        assertTrue(unmatchedResult.result.graph.compilerCallPositionsCaptured)
        assertEquals(emptyList(), unmatchedResult.result.graph.locatedCompilerReferences)
        assertEquals(listOf("compiler-call-positions-unmatched-bytecode: 1"), unmatchedResult.limitations)

        val defaultStub = fixture(root.resolve("default-stub"), calledMethod = "hit", bytecodeMethod = "hit\$default")
        assertEquals(listOf("compiler-call-positions-unmatched-bytecode: 1"), defaultStub.enrich().limitations)

        val outside = fixture(root.resolve("outside"), targetIdentity = "method:outside/Target#hit()V")
        assertEquals(listOf("compiler-call-positions-outside-graph: 1"), outside.enrich().limitations)

        val absentMember = fixture(root.resolve("missing"), targetIdentity = "method:demo/Target#missing()V")
        assertContains(assertFailsWith<IllegalArgumentException> { absentMember.enrich() }.message.orEmpty(), "compiled owner")
    }

    @Test
    fun `completed v3 with only omissions captures an empty position set`(@TempDir root: Path) {
        val unmapped = fixture(root.resolve("unmapped"), emitted = false).enrich()
        assertTrue(unmapped.result.graph.compilerCallPositionsCaptured)
        assertEquals(emptyList(), unmapped.result.graph.locatedCompilerReferences)
        assertEquals(listOf("compiler-call-positions-unmapped: 1"), unmapped.limitations)

        val ambiguous = fixture(root.resolve("ambiguous"), emitted = false, ambiguous = true).enrich()
        assertTrue(ambiguous.result.graph.compilerCallPositionsCaptured)
        assertEquals(emptyList(), ambiguous.result.graph.locatedCompilerReferences)
        assertEquals(listOf("compiler-call-positions-ambiguous: 1"), ambiguous.limitations)
    }

    @Test
    fun `generated flag comes only from a completed generated source receipt`(@TempDir root: Path) {
        val generated = fixture(root, generatedCaller = true)
        assertTrue(generated.enrich().result.graph.locatedCompilerReferences.single().generated)
        val witness = generated.context.provenance.witnesses.single()
        val withoutGeneratedReceipt = witness.copy(
            compilerEvidence = witness.compilerEvidence.filter { it.role != "compilerGeneratedSource" },
        )
        val context = generated.context.copy(provenance = generated.context.provenance.copy(witnesses = listOf(withoutGeneratedReceipt)))
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithCallPositions(
                generated.indexed, listOf(generated.classes), listOf(generated.document), context,
            )
        }
    }

    @Test
    fun `shadowed caller is omitted before bytecode matching`(@TempDir root: Path) {
        val fixture = fixture(root)
        val shadowSource = Files.createDirectories(root.resolve("shadow-src/demo")).resolve("Caller.java")
        Files.writeString(shadowSource, "package demo; public class Caller { public void use() {} }")
        val shadowClasses = Files.createDirectories(root.resolve("shadow-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", shadowClasses.toString(), shadowSource.toString(),
        ))
        val roots = listOf(shadowClasses, fixture.classes)
        val indexed = ClassFileIndexer().indexWithObservations(roots)
        val result = CompilerEvidenceIndexer.enrichWithCallPositions(
            indexed, roots, listOf(fixture.document), fixture.context,
        )
        assertTrue(result.result.graph.compilerCallPositionsCaptured)
        assertEquals(emptyList(), result.result.graph.locatedCompilerReferences)
        assertEquals(listOf("compiler-call-positions-shadowed: 1"), result.limitations)
    }

    @Test
    fun `changed source or raw evidence cannot reuse the completed v3 receipt`(@TempDir root: Path) {
        val changedSource = fixture(root.resolve("source"))
        Files.writeString(changedSource.context.project.resolve(changedSource.callerPath),
            "package demo; public class Caller { public void use() { Target.hit(); Target.hit(); } }")
        assertContains(assertFailsWith<IllegalArgumentException> { changedSource.enrich() }.message.orEmpty(), "matched build inputs")

        val changedRaw = fixture(root.resolve("raw"))
        Files.writeString(changedRaw.document, Files.readString(changedRaw.document).replace("\t1\t1\t0\t0", "\t2\t1\t1\t0"))
        assertContains(assertFailsWith<IllegalArgumentException> { changedRaw.enrich() }.message.orEmpty(), "matched build inputs")

        val changedGenerated = fixture(root.resolve("generated"), generatedCaller = true)
        Files.writeString(changedGenerated.context.project.resolve(changedGenerated.callerPath),
            "package demo; public class Caller { public void use() { Target.other(); } }")
        assertContains(assertFailsWith<IllegalArgumentException> { changedGenerated.enrich() }.message.orEmpty(), "matched build inputs")
    }

    @Test
    fun `receipted row cannot reassign a caller position to another inventory source`(@TempDir root: Path) {
        val fixture = fixture(root)
        val callerFile = root.resolve(fixture.callerPath)
        val targetPath = "src/Target.java"
        val targetFile = root.resolve(targetPath)
        val callerHash = CompilerEvidenceIndexer.sourceHash(callerFile)
        val targetHash = CompilerEvidenceIndexer.sourceHash(targetFile)
        val original = "\t${encode(fixture.callerPath)}\t$callerHash\t${fixture.start}\t"
        val reassigned = "\t${encode(targetPath)}\t$targetHash\t${fixture.start}\t"
        Files.writeString(fixture.document, Files.readString(fixture.document).replace(original, reassigned))

        val witness = fixture.context.provenance.witnesses.single()
        val receipts = CompilerEvidenceReceipts.validate(
            root, "javac", witness.evidenceToken!!, witness.inputs, listOf(fixture.document),
            setOf(callerFile, targetFile), listOf(root.resolve("src")), emptyList(), "compileJava",
        )
        val completed = witness.copy(compilerEvidence = receipts)
        val context = fixture.context.copy(
            provenance = fixture.context.provenance.copy(witnesses = listOf(completed)),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithCallPositions(
                fixture.indexed, listOf(fixture.classes), listOf(fixture.document), context,
            )
        }
        assertContains(failure.message.orEmpty(), "contradicts the caller bytecode source")
    }

    @Test
    fun `caller bytecode source comparison accepts a project relative location by filename`(@TempDir root: Path) {
        val fixture = fixture(root)
        val accepted = CompilerEvidenceIndexer.enrichWithCallPositions(
            fixture.indexedWithCallerLocation("src/Caller.java"),
            listOf(fixture.classes),
            listOf(fixture.document),
            fixture.context,
        )
        assertEquals(1, accepted.result.graph.locatedCompilerReferences.size)

        val failure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithCallPositions(
                fixture.indexedWithCallerLocation("src/Target.java"),
                listOf(fixture.classes),
                listOf(fixture.document),
                fixture.context,
            )
        }
        assertContains(failure.message.orEmpty(), "contradicts the caller bytecode source")
    }

    @Test
    fun `missing or duplicate bytecode source names retain producer facts with a measured limitation`(@TempDir root: Path) {
        val missing = fixture(root.resolve("missing-location"))
        val missingResult = CompilerEvidenceIndexer.enrichWithCallPositions(
            missing.indexedWithCallerLocation(null), listOf(missing.classes), listOf(missing.document), missing.context,
        )
        assertEquals(1, missingResult.result.graph.locatedCompilerReferences.size)
        assertEquals(listOf("compiler-call-positions-source-unverified: 1"), missingResult.limitations)

        val duplicate = fixture(root.resolve("duplicate-name"), duplicateInventoryBasename = true)
        val duplicateResult = duplicate.enrich()
        assertEquals(1, duplicateResult.result.graph.locatedCompilerReferences.size)
        assertEquals(listOf("compiler-call-positions-source-unverified: 1"), duplicateResult.limitations)
    }

    @Test
    fun `mixed v3 and legacy evidence reports uncovered selected caller roots`(@TempDir root: Path) {
        val mixed = multiRootFixture(root.resolve("mixed"), includeLegacyDocument = true).enrich()
        assertTrue(mixed.result.graph.compilerCallPositionsCaptured)
        assertEquals(1, mixed.result.graph.locatedCompilerReferences.size)
        assertEquals(listOf(
            "compiler-call-positions-uncovered-roots: 1",
            "compiler-call-positions-legacy-documents: 1",
        ), mixed.limitations)

        val missing = multiRootFixture(root.resolve("missing"), includeLegacyDocument = false).enrich()
        assertTrue(missing.result.graph.compilerCallPositionsCaptured)
        assertEquals(listOf("compiler-call-positions-uncovered-roots: 1"), missing.limitations)
    }

    private fun fixture(
        root: Path,
        optionMarker: Boolean = true,
        calledMethod: String = "hit",
        bytecodeMethod: String = "hit",
        targetIdentity: String? = null,
        emitted: Boolean = true,
        ambiguous: Boolean = false,
        generatedCaller: Boolean = false,
        duplicateInventoryBasename: Boolean = false,
    ): Fixture {
        Files.createDirectories(root)
        val sources = Files.createDirectories(root.resolve("src"))
        val generated = Files.createDirectories(root.resolve("generated"))
        val callerDir = if (generatedCaller) generated else sources
        val callerFile = callerDir.resolve("Caller.java")
        val targetFile = sources.resolve("Target.java")
        val duplicateCaller = if (duplicateInventoryBasename) {
            Files.createDirectories(sources.resolve("other")).resolve("Caller.java").also {
                Files.writeString(it, "package other; public class Caller {}")
            }
        } else null
        val callerText = "package demo; public class Caller { public void use() { Target.$bytecodeMethod(); } }"
        Files.writeString(callerFile, callerText)
        Files.writeString(targetFile, "package demo; public class Target { public static void hit() {} " +
            "public static void other() {} public static void hit\$default() {} }")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            *(listOf("-g", "-d", classes.toString(), callerFile.toString(), targetFile.toString()) +
                listOfNotNull(duplicateCaller?.toString())).toTypedArray()))
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        Files.writeString(root.resolve("compiler.bin"), "unit compiler identity")
        Files.writeString(root.resolve("collector.jar"), "unit collector identity")
        Files.writeString(root.resolve("witness.json"), "unit witness fixture")
        fun fp(role: String, path: String): InputFingerprint =
            ContentFingerprint.capture(root, root.resolve(path), role, role)
        val inputs = buildList {
            add(fp("sources", "src"))
            add(fp("buildConfig", "build.gradle"))
            add(fp("compiler", "compiler.bin"))
            add(fp("processor", "collector.jar"))
            add(InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("unit options"))))
            if (optionMarker) add(CompilerCallPositionOptions.enabledInput())
        }
        val token = CompilerEvidenceToken.create("sample:main", "javac", ":compileJava", inputs)
        val callerPath = root.relativize(callerFile).toString().replace('\\', '/')
        val targetPath = root.relativize(targetFile).toString().replace('\\', '/')
        val duplicateSourceRow = duplicateCaller?.let { file ->
            val path = root.relativize(file).toString().replace('\\', '/')
            "source\t${encode(path)}\t${CompilerEvidenceIndexer.sourceHash(file)}\n"
        }.orEmpty()
        val caller = JvmNodeId.methodId("demo/Caller", "use", "()V")
        val calledTarget = targetIdentity?.let { dev.kartograph.core.NodeId(it) }
            ?: JvmNodeId.methodId("demo/Target", calledMethod, "()V")
        val start = callerText.lastIndexOf(bytecodeMethod)
        val document = root.resolve("evidence.tsv")
        val callRows = if (emitted) {
            "callStats\t1\t1\t0\t0\n" +
                "call\t${encode(caller.value)}\t${encode(calledTarget.value)}\t${encode(callerPath)}\t${CompilerEvidenceIndexer.sourceHash(callerFile)}" +
                "\t$start\t${start + bytecodeMethod.length}\t1\t${start + 1}\n"
        } else {
            if (ambiguous) "callStats\t1\t0\t0\t1\n" else "callStats\t1\t0\t1\t0\n"
        }
        Files.writeString(document,
            "format\tkartograph-compiler-evidence\t3\ncollector\tjavac-constants\ncompiler\t17.0.20+8\n" +
                "token\t$token\nartifact\t${inputs.single { it.role == "processor" }.sha256}\nunmapped\t0\n" +
                "source\t${encode(callerPath)}\t${CompilerEvidenceIndexer.sourceHash(callerFile)}\n" +
                "source\t${encode(targetPath)}\t${CompilerEvidenceIndexer.sourceHash(targetFile)}\n" +
                duplicateSourceRow + callRows,
        )
        val expectedSources = (if (generatedCaller) setOf(targetFile.toRealPath())
            else setOf(callerFile.toRealPath(), targetFile.toRealPath())) + listOfNotNull(duplicateCaller?.toRealPath())
        val receipts = CompilerEvidenceReceipts.validate(
            root, "javac", token, inputs, listOf(document), expectedSources,
            listOf(sources), if (generatedCaller) listOf(generated) else emptyList(), "compileJava",
        )
        val output = fp("classes", "classes")
        val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(output), receipts, token)
        val provenance = SnapshotProvenance(listOf(output, fp("witness", "witness.json")), listOf(witness))
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        return Fixture(classes, document, indexed, CompilerEvidenceContext(root, "sample:main", provenance),
            caller, calledTarget, callerPath, start)
    }

    private data class Fixture(
        val classes: Path,
        val document: Path,
        val indexed: IndexedClasses,
        val context: CompilerEvidenceContext,
        val caller: dev.kartograph.core.NodeId,
        val calledTarget: dev.kartograph.core.NodeId,
        val callerPath: String,
        val start: Int,
    ) {
        fun enrich(): CompilerEvidenceEnrichment = CompilerEvidenceIndexer.enrichWithCallPositions(
            indexed, listOf(classes), listOf(document), context,
        )

        fun indexedWithCallerLocation(path: String?): IndexedClasses {
            val graph = indexed.graph
            val nodes = graph.nodes.values.map { node ->
                if (node.id == caller) node.copy(location = path?.let { SourceLocation(it, node.location?.line, node.location?.column) })
                else node
            }
            val relocated = CodeGraph(
                nodes,
                graph.edges,
                graph.externalCalls,
                graph.serviceProviders,
                graph.enclosures,
                graph.callbackArguments,
                graph.parameterUses,
                graph.lambdaEscapes,
            )
            return IndexedClasses(
                relocated,
                indexed.observations,
                indexed.hierarchy,
                indexed.declarationsByRoot,
                indexed.selectedRootByNode,
                indexed.statistics,
            )
        }
    }

    private fun multiRootFixture(root: Path, includeLegacyDocument: Boolean): MultiRootFixture {
        Files.createDirectories(root)
        val sourceA = Files.createDirectories(root.resolve("src/a"))
        val sourceB = Files.createDirectories(root.resolve("src/b"))
        val callerA = sourceA.resolve("CallerA.java")
        val targetA = sourceA.resolve("TargetA.java")
        val callerB = sourceB.resolve("CallerB.java")
        val targetB = sourceB.resolve("TargetB.java")
        val callerAText = "package a; public class CallerA { public void use() { TargetA.hit(); } }"
        val callerBText = "package b; public class CallerB { public void use() { TargetB.hit(); } }"
        Files.writeString(callerA, callerAText)
        Files.writeString(targetA, "package a; public class TargetA { public static void hit() {} }")
        Files.writeString(callerB, callerBText)
        Files.writeString(targetB, "package b; public class TargetB { public static void hit() {} }")
        val classesA = Files.createDirectories(root.resolve("classes-a"))
        val classesB = Files.createDirectories(root.resolve("classes-b"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classesA.toString(), callerA.toString(), targetA.toString(),
        ))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classesB.toString(), callerB.toString(), targetB.toString(),
        ))
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        Files.writeString(root.resolve("compiler.bin"), "unit compiler identity")
        Files.writeString(root.resolve("collector.jar"), "unit collector identity")
        Files.writeString(root.resolve("witness-a.json"), "witness a")
        Files.writeString(root.resolve("witness-b.json"), "witness b")
        fun fp(role: String, path: Path, slot: String): InputFingerprint = ContentFingerprint.capture(root, path, role, slot)
        fun inputs(source: Path, artifact: String, positions: Boolean): List<InputFingerprint> = buildList {
            add(fp("sources", source, "$artifact-sources"))
            add(fp("buildConfig", root.resolve("build.gradle"), "$artifact-build"))
            add(fp("compiler", root.resolve("compiler.bin"), "$artifact-compiler"))
            add(fp("processor", root.resolve("collector.jar"), "$artifact-collector"))
            add(InputFingerprint("options", "$artifact-options", ContentFingerprint.values(listOf("unit options"))))
            if (positions) add(CompilerCallPositionOptions.enabledInput())
        }
        val inputsA = inputs(sourceA, "compileA", positions = true)
        val inputsB = inputs(sourceB, "compileB", positions = false)
        val tokenA = CompilerEvidenceToken.create("sample:main", "javac", ":compileA", inputsA)
        val tokenB = CompilerEvidenceToken.create("sample:main", "javac", ":compileB", inputsB)
        val callerAId = JvmNodeId.methodId("a/CallerA", "use", "()V")
        val targetAId = JvmNodeId.methodId("a/TargetA", "hit", "()V")
        val startA = callerAText.indexOf("hit")
        val documentA = root.resolve("evidence-a.tsv")
        Files.writeString(documentA,
            "format\tkartograph-compiler-evidence\t3\ncollector\tjavac-constants\ncompiler\t17.0.20+8\n" +
                "token\t$tokenA\nartifact\t${inputsA.single { it.role == "processor" }.sha256}\nunmapped\t0\n" +
                sourceRow(root, callerA) + sourceRow(root, targetA) + "callStats\t1\t1\t0\t0\n" +
                "call\t${encode(callerAId.value)}\t${encode(targetAId.value)}\t${encode(relative(root, callerA))}\t" +
                "${CompilerEvidenceIndexer.sourceHash(callerA)}\t$startA\t${startA + 3}\t1\t${startA + 1}\n",
        )
        val documentB = root.resolve("evidence-b.tsv")
        Files.writeString(documentB,
            "format\tkartograph-compiler-evidence\t1\ncollector\tjavac-constants\ncompiler\t17.0.20+8\n" +
                "token\t$tokenB\nartifact\t${inputsB.single { it.role == "processor" }.sha256}\nunmapped\t0\n" +
                sourceRow(root, callerB) + sourceRow(root, targetB),
        )
        val receiptsA = CompilerEvidenceReceipts.validate(
            root, "javac", tokenA, inputsA, listOf(documentA), setOf(callerA, targetA), listOf(sourceA), emptyList(), "compileA",
        )
        val receiptsB = if (includeLegacyDocument) CompilerEvidenceReceipts.validate(
            root, "javac", tokenB, inputsB, listOf(documentB), setOf(callerB, targetB), listOf(sourceB), emptyList(), "compileB",
        ) else emptyList()
        val outputA = fp("classes", classesA, "classes-a")
        val outputB = fp("classes", classesB, "classes-b")
        val witnessA = BuildWitness("sample:main", "javac", ":compileA", inputsA, listOf(outputA), receiptsA, tokenA)
        val witnessB = BuildWitness("sample:main", "javac", ":compileB", inputsB, listOf(outputB), receiptsB,
            tokenB.takeIf { receiptsB.isNotEmpty() })
        val provenance = SnapshotProvenance(
            listOf(outputA, outputB, fp("witness", root.resolve("witness-a.json"), "witness-a"),
                fp("witness", root.resolve("witness-b.json"), "witness-b")),
            listOf(witnessA, witnessB),
        )
        val roots = listOf(classesA, classesB)
        return MultiRootFixture(
            roots,
            listOf(documentA) + if (includeLegacyDocument) listOf(documentB) else emptyList(),
            ClassFileIndexer().indexWithObservations(roots),
            CompilerEvidenceContext(root, "sample:main", provenance),
        )
    }

    private data class MultiRootFixture(
        val roots: List<Path>,
        val documents: List<Path>,
        val indexed: IndexedClasses,
        val context: CompilerEvidenceContext,
    ) {
        fun enrich(): CompilerEvidenceEnrichment =
            CompilerEvidenceIndexer.enrichWithCallPositions(indexed, roots, documents, context)
    }

    private fun sourceRow(root: Path, file: Path): String =
        "source\t${encode(relative(root, file))}\t${CompilerEvidenceIndexer.sourceHash(file)}\n"

    private fun relative(root: Path, file: Path): String = root.relativize(file).toString().replace('\\', '/')

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
