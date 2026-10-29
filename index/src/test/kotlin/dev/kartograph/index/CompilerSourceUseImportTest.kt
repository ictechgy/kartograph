package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

class CompilerSourceUseImportTest {
    // 기존 위치 fixture와 같은 javac/receipt harness를 사용하며 raw-only 선택은 별도 채널로 검사한다.
    @Test
    fun `selected non CALL row is retained without changing located positions or edges`(@TempDir root: Path) {
        val fixture = fixture(root, calledMethod = "other")
        val old = CompilerEvidenceIndexer.enrichWithCallPositions(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        val result = fixture.enrich()
        assertTrue(old.result.graph.locatedCompilerReferences.isEmpty())
        assertEquals(old.limitations, result.limitations)
        assertEquals(old.result.graph.edges, result.result.graph.edges)
        val evidence = assertNotNull(result.result.graph.compilerSourceUseEvidence)
        assertEquals(fixture.calledTarget, evidence.uses.single().target)
        assertEquals(CompilerSourceUseImportCounts(1, 0, 0, 0, 0, 0), evidence.units.single().importCounts)
        assertEquals(emptyList(), evidence.uncoveredRoots)
        assertNull(old.result.graph.compilerSourceUseEvidence)
        assertNull(fixture.indexed.graph.compilerSourceUseEvidence)
        evidence.requireMatchesProvenance(fixture.context.provenance, fixture.context.scope)
        val witness = fixture.context.provenance.witnesses.single()
        assertEquals(CompilerEvidenceToken.create(witness.scope, witness.compiler, witness.artifact, witness.inputs), evidence.units.single().inputToken)
    }

    @Test
    fun `ordinary selection appears in both independent channels and repeated file is read once`(@TempDir root: Path) {
        val fixture = fixture(root)
        val result = CompilerEvidenceIndexer.enrichWithSourceUses(fixture.indexed, listOf(fixture.classes),
            listOf(fixture.document, fixture.document.toAbsolutePath()), fixture.context)
        assertEquals(1, result.result.graph.locatedCompilerReferences.size)
        assertEquals(1, result.result.graph.compilerSourceUseEvidence!!.uses.size)
        assertEquals(1, result.result.graph.compilerSourceUseEvidence!!.units.size)
        assertEquals(fixture.indexed.graph.edges, result.result.graph.edges)
    }

    @Test
    fun `real receipt zero emission differs from missing v3 and preserves producer gaps`(@TempDir root: Path) {
        val fixture = fixture(root, emitted = false)
        val evidence = fixture.enrich().result.graph.compilerSourceUseEvidence!!
        assertTrue(evidence.uses.isEmpty())
        assertEquals(CompilerSourceUseProducerCounts(1, 0, 1, 0), evidence.units.single().producer)
        assertEquals(0, evidence.units.single().viewOmitted)
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithSourceUses(fixture.indexed, listOf(fixture.classes), emptyList(), fixture.context)
        }
        val legacy = Files.readString(fixture.document).lineSequence().filterNot { it.startsWith("callStats\t") }
            .joinToString("\n").replace("evidence\t3", "evidence\t1")
        Files.writeString(fixture.document, legacy)
        assertFailsWith<IllegalArgumentException> { capture(fixture, context = refreshed(fixture)) }
    }

    @Test
    fun `missing or ambiguous caller association is measured but known contradiction fails`(@TempDir root: Path) {
        val fixture = fixture(root.resolve("plain"))
        val missing = capture(fixture, indexed = fixture.indexedWithCallerLocation(null)).result.graph.compilerSourceUseEvidence!!
        assertEquals(1, missing.units.single().importCounts.sourceAssociationUnverified)
        assertTrue(missing.uses.isEmpty())
        assertFailsWith<IllegalArgumentException> { capture(fixture, indexed = fixture.indexedWithCallerLocation("Other.java")) }
        val ambiguous = fixture(root.resolve("ambiguous"), duplicateInventoryBasename = true).enrich().result.graph.compilerSourceUseEvidence!!
        assertEquals(1, ambiguous.units.single().importCounts.sourceAssociationUnverified)
        assertTrue(ambiguous.uses.isEmpty())
    }

    @Test
    fun `generated attribution uses completed receipts rather than path spelling`(@TempDir root: Path) {
        val generated = fixture(root, generatedCaller = true).enrich().result.graph.compilerSourceUseEvidence!!
        assertTrue(generated.uses.single().generated)
    }

    @Test
    fun `differing portable slots for one physical output fail only new path including zero rows`(@TempDir root: Path) {
        for (emitted in listOf(true, false)) {
            val fixture = fixture(root.resolve(emitted.toString()), emitted = emitted)
            val witness = fixture.context.provenance.witnesses.single()
            val selected = witness.outputs.single()
            val alias = selected.copy(path = "external/alternate-output")
            val context = fixture.context.copy(provenance = fixture.context.provenance.copy(
                witnesses = listOf(witness.copy(outputs = listOf(alias)))), externalInputs = mapOf(alias.path to fixture.classes))
            assertEquals("matched", ProvenanceVerifier.verify(context.provenance, context.project, context.scope, context.externalInputs).status)
            assertNull(CompilerEvidenceIndexer.enrichWithCallPositions(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), context)
                .result.graph.compilerSourceUseEvidence)
            assertFailsWith<IllegalArgumentException> { capture(fixture, context = context) }.also {
                assertContains(it.message.orEmpty(), "differing portable identities")
            }
            assertFailsWith<IllegalArgumentException> { capture(fixture, context = context.copy(externalInputs = emptyMap())) }
        }
    }

    @Test
    fun `shadowed caller is omitted before unrelated winning SourceFile and target checks`(@TempDir root: Path) {
        val fixture = fixture(root)
        val duplicate = copyClasses(fixture.classes, root.resolve("duplicate"))
        val roots = listOf(fixture.classes, duplicate)
        val indexed = ClassFileIndexer().indexWithObservations(roots)
        val nodes = indexed.graph.nodes.values.map { if (it.id == fixture.caller) it.copy(location = SourceLocation("Other.java")) else it }
        val altered = replaceGraph(indexed, CodeGraph(nodes, indexed.graph.edges))
        val context = withOutputs(fixture, roots, evidenceOrdinal = 1)
        val result = CompilerEvidenceIndexer.enrichWithSourceUses(altered, roots, listOf(fixture.document), context)
        val evidence = result.result.graph.compilerSourceUseEvidence!!
        assertEquals(CompilerSourceUseImportCounts(0, 0, 1, 0, 0, 0), evidence.units.single().importCounts)
        assertTrue(evidence.uses.isEmpty())
        assertEquals(2, evidence.roots.size)
        assertEquals(1, evidence.uncoveredRoots.size)
        assertNotEquals(evidence.roots[0].id, evidence.roots[1].id)
        // shadow가 있어도 document/token/source gate는 우회하지 않는다.
        Files.writeString(root.resolve(fixture.callerPath), "changed")
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceIndexer.enrichWithSourceUses(altered, roots, listOf(fixture.document), context) }
    }

    @Test
    fun `target ownership dispositions follow claimed compiled declarations`(@TempDir root: Path) {
        val fixture = fixture(root)
        val shadow = Files.createDirectories(root.resolve("shadow/demo"))
        Files.copy(fixture.classes.resolve("demo/Target.class"), shadow.resolve("Target.class"))
        val roots = listOf(shadow.parent, fixture.classes)
        val context = withOutputs(fixture, roots, evidenceOrdinal = 1)
        val result = CompilerEvidenceIndexer.enrichWithSourceUses(ClassFileIndexer().indexWithObservations(roots), roots,
            listOf(fixture.document), context).result.graph.compilerSourceUseEvidence!!
        assertEquals(CompilerSourceUseImportCounts(0, 0, 0, 1, 0, 0), result.units.single().importCounts)
        Files.delete(fixture.classes.resolve("demo/Target.class"))
        val outside = CompilerEvidenceIndexer.enrichWithSourceUses(ClassFileIndexer().indexWithObservations(roots), roots,
            listOf(fixture.document), withOutputs(fixture, roots, evidenceOrdinal = 1)).result.graph.compilerSourceUseEvidence!!
        assertEquals(CompilerSourceUseImportCounts(0, 0, 0, 0, 1, 0), outside.units.single().importCounts)
    }

    @Test
    fun `caller absent from claimed output fails even when graph contains identity`(@TempDir root: Path) {
        val fixture = fixture(root)
        val broken = IndexedClasses(fixture.indexed.graph, fixture.indexed.observations, fixture.indexed.hierarchy,
            listOf(fixture.indexed.declarationsByRoot.single() - fixture.caller), fixture.indexed.selectedRootByNode, fixture.indexed.statistics)
        assertFailsWith<IllegalArgumentException> { capture(fixture, indexed = broken) }
    }

    @Test
    fun `outside target omission differs from missing method of compiled owner`(@TempDir root: Path) {
        val outside = fixture(root.resolve("outside"), targetIdentity = "method:outside/Other#absent()V").enrich()
        assertEquals(1, outside.result.graph.compilerSourceUseEvidence!!.units.single().importCounts.outsideGraph)
        val missing = fixture(root.resolve("missing"), calledMethod = "absent")
        assertFailsWith<IllegalArgumentException> { missing.enrich() }
    }

    @Test
    fun `malformed omitted target fails raw identity gate even for shadowed caller`(@TempDir root: Path) {
        for ((index, target) in listOf("method:outside/Other", "method:outside/Other#missing", "method:outside/Other#missing(").withIndex()) {
            val fixture = fixture(root.resolve("case-$index"), targetIdentity = target)
            assertFailsWith<IllegalArgumentException> { fixture.enrich() }
                .also { assertContains(it.message.orEmpty(), "exact JVM method identity") }
            val duplicate = copyClasses(fixture.classes, fixture.context.project.resolve("duplicate"))
            val roots = listOf(fixture.classes, duplicate)
            assertFailsWith<IllegalArgumentException> {
                CompilerEvidenceIndexer.enrichWithSourceUses(ClassFileIndexer().indexWithObservations(roots), roots,
                    listOf(fixture.document), withOutputs(fixture, roots, evidenceOrdinal = 1))
            }.also { assertContains(it.message.orEmpty(), "exact JVM method identity") }
        }
    }

    @Test
    fun `same selector different targets conflict and malformed or stale receipt never partially attaches`(@TempDir root: Path) {
        val fixture = fixture(root)
        val text = Files.readString(fixture.document)
        val row = text.lineSequence().single { it.startsWith("call\t") }
        val second = row.replace(encode(fixture.calledTarget.value), encode(JvmNodeId.methodId("demo/Target", "other", "()V").value))
        Files.writeString(fixture.document, text.replace("callStats\t1\t1", "callStats\t2\t2") + second + "\n")
        assertFailsWith<IllegalArgumentException> { capture(fixture, context = refreshed(fixture)) }
        Files.writeString(fixture.document, text.replace("\t${fixture.start}\t", "\t-1\t"))
        assertFailsWith<IllegalArgumentException> { capture(fixture, context = refreshed(fixture)) }
        Files.writeString(fixture.document, text.replace(fixture.context.provenance.witnesses.single().evidenceToken!!, "0".repeat(64)))
        assertFailsWith<IllegalArgumentException> { capture(fixture, context = refreshed(fixture)) }
        assertNull(fixture.indexed.graph.compilerSourceUseEvidence)
    }

    @Test
    fun `warm cache never substitutes for fresh source receipt or scope validation`(@TempDir root: Path) {
        val fixture = fixture(root)
        val cache = ClassIndexCache(root.resolve("cache"))
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(fixture.classes))
        val first = capture(fixture, indexed = cold).result.graph.compilerSourceUseEvidence
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(fixture.classes))
        assertTrue(warm.statistics.cacheHits > 0)
        assertEquals(first, capture(fixture, indexed = warm).result.graph.compilerSourceUseEvidence)
        assertNull(CompilerEvidenceIndexer.enrichWithCallPositions(warm, listOf(fixture.classes), listOf(fixture.document), fixture.context)
            .result.graph.compilerSourceUseEvidence)
        assertFailsWith<IllegalArgumentException> { capture(fixture, indexed = warm, context = fixture.context.copy(scope = "other:main")) }
        assertEquals(first, capture(fixture, indexed = warm).result.graph.compilerSourceUseEvidence)
        val source = root.resolve(fixture.callerPath)
        val mtime = Files.getLastModifiedTime(source)
        Files.writeString(source, Files.readString(source) + " ")
        Files.setLastModifiedTime(source, mtime)
        assertFailsWith<IllegalArgumentException> { capture(fixture, indexed = warm) }
        assertNull(warm.graph.compilerSourceUseEvidence)
    }

    @Test
    fun `independent simultaneous contexts do not share source evidence`(@TempDir root: Path) {
        val first = fixture(root.resolve("a"))
        val second = fixture(root.resolve("b"), calledMethod = "other")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf(java.util.concurrent.Callable { first.enrich() }, java.util.concurrent.Callable { second.enrich() }))
                .map { it.get().result.graph.compilerSourceUseEvidence!! }
            assertEquals(first.calledTarget, results[0].uses.single().target)
            assertEquals(second.calledTarget, results[1].uses.single().target)
            assertNull(first.indexed.graph.compilerSourceUseEvidence)
            assertNull(second.indexed.graph.compilerSourceUseEvidence)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `actual byte budgets reject sentinel and symlink before parsing`(@TempDir root: Path) {
        val file = Files.write(root.resolve("bytes"), byteArrayOf(65, 66, 67, 68))
        assertEquals(4, BoundedCompilerEvidenceReader.read(file, 4).byteCount)
        assertFailsWith<IllegalArgumentException> { BoundedCompilerEvidenceReader.read(file, 3) }
        assertFailsWith<IllegalArgumentException> { BoundedCompilerEvidenceReader.read(file, 0) }
        val link = Files.createSymbolicLink(root.resolve("link"), file)
        assertFailsWith<IllegalArgumentException> { BoundedCompilerEvidenceReader.read(link, 4) }
        Files.write(file, byteArrayOf(0xc0.toByte(), 0x80.toByte()))
        assertFailsWith<IllegalArgumentException> { BoundedCompilerEvidenceReader.read(file) }
    }

    @Test
    fun `all freshness inputs and receipt membership remain fail closed`(@TempDir root: Path) {
        val fixture = fixture(root)
        val paths = listOf(root.resolve("collector.jar"), root.resolve("compiler.bin"), root.resolve("build.gradle"),
            fixture.classes.resolve("demo/Caller.class"), fixture.document)
        paths.forEach { path ->
            val original = Files.readAllBytes(path)
            val modified = original + byteArrayOf(32)
            val mtime = Files.getLastModifiedTime(path)
            Files.write(path, modified); Files.setLastModifiedTime(path, mtime)
            assertFailsWith<IllegalArgumentException> { fixture.enrich() }
            Files.write(path, original); Files.setLastModifiedTime(path, mtime)
        }
        val witness = fixture.context.provenance.witnesses.single()
        val missing = fixture.context.copy(provenance = fixture.context.provenance.copy(
            witnesses = listOf(witness.copy(compilerEvidence = emptyList(), evidenceToken = null))))
        assertFailsWith<IllegalArgumentException> { capture(fixture, context = missing) }
        val options = witness.inputs.filterNot { it.path == CompilerCallPositionOptions.INPUT_PATH }
        val wrongOptions = fixture.context.copy(provenance = fixture.context.provenance.copy(witnesses = listOf(witness.copy(inputs = options))))
        assertFailsWith<IllegalArgumentException> { capture(fixture, context = wrongOptions) }
        assertNull(fixture.indexed.graph.compilerSourceUseEvidence)
        assertEquals(1, fixture.enrich().result.graph.compilerSourceUseEvidence!!.uses.size)
    }

    @Test
    fun `aggregate actual byte and distinct document budgets precede document parsing`(@TempDir root: Path) {
        val fixture = fixture(root)
        val documents = (0..256).map { Files.writeString(root.resolve("document-$it.tsv"), "") }
        assertFailsWith<IllegalArgumentException> {
            CompilerSourceUseImport(fixture.indexed, listOf(fixture.classes), documents, fixture.context)
        }.also { assertContains(it.message.orEmpty(), "document limit") }
        val block = ByteArray(1024 * 1024) { 65 }
        val full = (0..3).map { index -> root.resolve("full-$index.tsv").also { path ->
            Files.newOutputStream(path).use { stream -> repeat(16) { stream.write(block) } }
        } }
        val excess = Files.writeString(root.resolve("sentinel.tsv"), "x")
        assertFailsWith<IllegalArgumentException> {
            CompilerSourceUseImport(fixture.indexed, listOf(fixture.classes), full + listOf(excess), fixture.context)
        }.also { assertContains(it.message.orEmpty(), "byte limit") }
        Files.newOutputStream(full.first(), java.nio.file.StandardOpenOption.APPEND).use { it.write(65) }
        assertFailsWith<IllegalArgumentException> { BoundedCompilerEvidenceReader.read(full.first()) }
            .also { assertContains(it.message.orEmpty(), "byte limit") }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KARTOGRAPH_COLLECTOR_JAR", matches = ".+")
    fun `actual packaged javac collector admits method constructor and zero emission units`(@TempDir root: Path) {
        for (scenario in listOf("ordinary", "constructors", "empty")) {
            val emitted = scenario != "empty"
            val constructors = scenario == "constructors"
            val count = if (constructors) 3 else if (emitted) 1 else 0
            val project = Files.createDirectories(root.resolve(scenario))
            val collector = Path.of(requireNotNull(System.getenv("KARTOGRAPH_COLLECTOR_JAR"))).toRealPath()
            require(Files.isRegularFile(collector) && !Files.isSymbolicLink(collector))
            val javac = Path.of(System.getProperty("java.home"), "bin", "javac").toRealPath()
            val sources = Files.createDirectories(project.resolve("src/demo"))
            val callerFile = sources.resolve("Caller.java")
            val targetFile = sources.resolve("Target.java")
            val callerText = if (constructors) "package demo; public class Caller { public Caller() { Target.hit(); } " +
                "public Caller(int value) { this(); } public void use() { Target.hit(); } }\n"
                else if (emitted) "package demo; public class Caller { public void use() { Target.hit(); } }\n"
                else "package demo; public class Caller { public void use() {} }\n"
            Files.writeString(callerFile, callerText)
            Files.writeString(targetFile, "package demo; public class Target { public Target() {} " +
                "public Target(String unused) {} public static void hit() {} }\n")
            val classes = Files.createDirectories(project.resolve("classes"))
            Files.writeString(project.resolve("build.gradle"), "unit configuration")
            Files.writeString(project.resolve("witness.json"), "unit witness fixture")

            fun fp(role: String, path: Path, slot: String): InputFingerprint =
                ContentFingerprint.capture(project, path, role, slot)
            val compilerInput = fp("compiler", javac, "javac")
            val collectorInput = fp("processor", collector, "collector")
            val inputs = listOf(
                fp("sources", project.resolve("src"), "sources"),
                fp("buildConfig", project.resolve("build.gradle"), "build-config"),
                compilerInput,
                collectorInput,
                InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("-g", "-proc:none"))),
                CompilerCallPositionOptions.enabledInput(),
            )
            val token = CompilerEvidenceToken.create("sample:main", "javac", ":compileJava", inputs)
            val tokenFile = Files.writeString(project.resolve("token"), token)
            val document = project.resolve("evidence.tsv")
            val plugin = "-Xplugin:KartographEvidence collector=javac-constants root=${project.toUri()} " +
                "output=${document.toUri()} token=${tokenFile.toUri()} callPositions=true"
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-g", "-proc:none", "-processorpath", collector.toString(), plugin,
                "-d", classes.toString(), callerFile.toString(), targetFile.toString(),
            ))

            val envelope = CompilerEvidenceReader.readEnvelope(document)
            assertEquals(collectorInput.sha256, envelope.evidence.artifactSha256)
            assertEquals(count, envelope.callStats?.emitted)
            val receipts = CompilerEvidenceReceipts.validate(
                project, "javac", token, inputs, listOf(document), setOf(callerFile, targetFile),
                listOf(project.resolve("src")), emptyList(), "compileJava",
            )
            val output = fp("classes", classes, "classes")
            val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(output), receipts, token)
            val provenance = SnapshotProvenance(
                listOf(output, fp("witness", project.resolve("witness.json"), "witness")), listOf(witness),
            )
            val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
            val enrichment = CompilerEvidenceIndexer.enrichWithSourceUses(
                indexed,
                listOf(classes),
                listOf(document),
                CompilerEvidenceContext(
                    project,
                    "sample:main",
                    provenance,
                    mapOf(compilerInput.path to javac, collectorInput.path to collector),
                ),
            )

            assertEquals(indexed.graph.edges, enrichment.result.graph.edges)
            val captured = enrichment.result.graph.compilerSourceUseEvidence!!
            assertEquals(count, captured.uses.size)
            assertEquals(0, captured.units.single().viewOmitted)
            if (constructors) {
                val noArg = JvmNodeId.methodId("demo/Caller", "<init>", "()V")
                val withArg = JvmNodeId.methodId("demo/Caller", "<init>", "(I)V")
                val useMethod = JvmNodeId.methodId("demo/Caller", "use", "()V")
                val hit = JvmNodeId.methodId("demo/Target", "hit", "()V")
                val expected = setOf(noArg to hit, withArg to noArg, useMethod to hit)
                assertEquals(expected, envelope.callPositions.map { it.source to it.target }.toSet())
                assertEquals(expected, captured.uses.map { it.source to it.target }.toSet())
                assertEquals(expected, enrichment.result.graph.locatedCompilerReferences.map { it.source to it.target }.toSet())
                val unused = JvmNodeId.methodId("demo/Target", "<init>", "(Ljava/lang/String;)V")
                assertContains(captured.roots.single().capturedCallableOwners, unused)
                assertFalse(captured.uses.any { it.source == unused || it.target == unused || it.target == withArg })
                val constructorCall = captured.uses.single { it.source == withArg }
                assertEquals(callerText.indexOf("this()"), constructorCall.offsetUtf16)
                assertEquals(constructorCall.offsetUtf16 + 4, constructorCall.endOffsetUtf16)
            } else if (emitted) {
                val position = enrichment.result.graph.locatedCompilerReferences.single()
                val use = captured.uses.single()
                assertEquals(position.source, use.source)
                assertEquals(position.target, use.target)
                assertEquals(position.file, use.file)
                assertEquals(callerText.indexOf("hit"), use.offsetUtf16)
                assertEquals(position.coordinateBasis, use.coordinateBasis)
            } else assertTrue(enrichment.result.graph.locatedCompilerReferences.isEmpty())
            val stats = requireNotNull(envelope.callStats)
            val expectedLimitations = buildList {
                if (stats.unmapped > 0) add("compiler-call-positions-unmapped: ${stats.unmapped}")
                if (stats.ambiguous > 0) add("compiler-call-positions-ambiguous: ${stats.ambiguous}")
            }
            assertEquals(expectedLimitations, enrichment.limitations)
        }
    }

    @Test
    fun `new bounded reader rejects a symlink replacement at the actual open`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("observed.tsv"), "abcd")
        val other = Files.writeString(root.resolve("other.tsv"), "efgh")
        assertFailsWith<java.io.IOException> {
            BoundedCompilerEvidenceReader.readForSourceUses(file, 4) {
                Files.delete(file)
                Files.createSymbolicLink(file, other)
            }
        }
    }

    @Test
    fun `receipt growth after acquisition cannot bypass endpoint byte limits`(@TempDir root: Path) {
        val fixture = fixture(root)
        val capture = CompilerSourceUseImport(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        assertEquals("matched", capture.verifyFreshness(recheckReceipts = false).status)
        val block = ByteArray(1024 * 1024) { 65 }
        Files.newOutputStream(fixture.document).use { stream -> repeat(16) { stream.write(block) }; stream.write(65) }
        assertFailsWith<IllegalArgumentException> { capture.verifyFreshness(recheckReceipts = true) }
            .also { assertContains(it.message.orEmpty(), "byte limit") }
        assertNull(fixture.indexed.graph.compilerSourceUseEvidence)
    }

    @Test
    fun `endpoint receipt pass rechecks aggregate actual bytes including grown zero byte member`(@TempDir root: Path) {
        val block = ByteArray(1024 * 1024) { 65 }
        val paths = (0..3).map { root.resolve("full-$it.tsv").also { file ->
            Files.newOutputStream(file).use { stream -> repeat(16) { stream.write(block) } }
        } } + listOf(Files.writeString(root.resolve("empty.tsv"), ""))
        val before = CompilerSourceUseReceiptPass()
        paths.forEach(before::observe)
        Files.writeString(paths.last(), "x")
        assertFailsWith<IllegalArgumentException> { before.reobserve() }
            .also { assertContains(it.message.orEmpty(), "byte limit") }
    }

    @Test
    fun `deduplicated receipt buffers preserve every original route at the endpoint`(@TempDir root: Path) {
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))
        val original = Files.writeString(first.resolve("evidence.tsv"), "same bytes")
        Files.writeString(second.resolve("evidence.tsv"), "same bytes")
        val alias = Files.createSymbolicLink(root.resolve("alias"), first)
        val before = CompilerSourceUseReceiptPass()
        before.observe(original)
        before.observe(alias.resolve("evidence.tsv"))
        assertEquals(1, before.byPhysical.size)
        assertEquals(1, before.reobserve().byPhysical.size)
        Files.delete(alias)
        Files.createSymbolicLink(alias, second)
        assertFailsWith<IllegalArgumentException> { before.reobserve() }
            .also { assertContains(it.message.orEmpty(), "receipt changed") }
    }

    @Test
    fun `lexical symlink parent dotdot route is retained when physical buffers deduplicate`(@TempDir root: Path) {
        val first = Files.createDirectories(root.resolve("first/child"))
        val second = Files.createDirectories(root.resolve("second/child"))
        val original = Files.writeString(first.parent.resolve("evidence.tsv"), "same bytes")
        Files.writeString(second.parent.resolve("evidence.tsv"), "same bytes")
        val alias = Files.createSymbolicLink(root.resolve("alias"), first)
        val lexical = alias.resolve("../evidence.tsv")
        assertEquals(original.toRealPath(), lexical.toRealPath())
        val before = CompilerSourceUseReceiptPass()
        before.observe(original)
        before.observe(lexical)
        assertEquals(1, before.byPhysical.size)
        assertEquals(1, before.reobserve().byPhysical.size)
        Files.delete(alias)
        Files.createSymbolicLink(alias, second)
        assertFailsWith<IllegalArgumentException> { before.reobserve() }
            .also { assertContains(it.message.orEmpty(), "receipt changed") }
    }

    @Test
    fun `provenance receipt cannot hide a retargeted explicit lexical route`(@TempDir root: Path) {
        val fixture = fixture(root.resolve("first"))
        val firstChild = Files.createDirectory(fixture.context.project.resolve("child"))
        val secondChild = Files.createDirectories(root.resolve("second/child"))
        Files.copy(fixture.document, secondChild.parent.resolve("evidence.tsv"))
        // normalize(alias/../evidence.tsv) would point here, not through the actual symlink traversal.
        Files.copy(fixture.document, root.resolve("evidence.tsv"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), firstChild)
        val explicit = alias.resolve("../evidence.tsv")
        val capture = CompilerSourceUseImport(fixture.indexed, listOf(fixture.classes), listOf(explicit), fixture.context)
        assertEquals("matched", capture.verifyFreshness(recheckReceipts = false).status)
        assertEquals("matched", capture.verifyFreshness(recheckReceipts = true).status)
        Files.delete(alias)
        Files.createSymbolicLink(alias, secondChild)
        assertFailsWith<IllegalArgumentException> { capture.verifyFreshness(recheckReceipts = true) }
            .also { assertContains(it.message.orEmpty(), "receipt changed") }
    }

    @Test
    fun `legal JVM parentheses in omitted target identities remain supported`(@TempDir root: Path) {
        for ((index, target) in listOf("method:outside/C(D)#call()V", "method:outside/C#call(odd)()V",
            "method:outside/C#call(Lp/A(B);)[Lp/C(D);").withIndex()) {
            val fixture = fixture(root.resolve("case-$index"), targetIdentity = target)
            assertEquals(1, fixture.enrich().result.graph.compilerSourceUseEvidence!!.units.single().importCounts.outsideGraph)
        }
    }

    @Test
    fun `same sized changed receipt is rejected at the bounded endpoint`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("evidence.tsv"), "before")
        val before = CompilerSourceUseReceiptPass()
        before.observe(file)
        val mtime = Files.getLastModifiedTime(file)
        Files.writeString(file, "after!")
        Files.setLastModifiedTime(file, mtime)
        assertFailsWith<IllegalArgumentException> { before.reobserve() }
            .also { assertContains(it.message.orEmpty(), "receipt changed") }
    }

    @Test
    fun `distinct receipt paths preserve equal byte observations without multiplying one unit`(@TempDir root: Path) {
        val fixture = fixture(root)
        val other = Files.copy(fixture.document, root.resolve("other.tsv"))
        val context = completedDocuments(fixture, listOf(fixture.document, other))
        val result = CompilerEvidenceIndexer.enrichWithSourceUses(fixture.indexed, listOf(fixture.classes),
            listOf(fixture.document, other, fixture.document), context).result.graph.compilerSourceUseEvidence!!
        assertEquals(2, result.units.size)
        assertEquals(2, result.uses.size)
        assertEquals(2, result.units.map { it.receipt.path }.toSet().size)
        assertEquals(1, result.units.map { it.receipt.sha256 }.toSet().size)
        assertTrue(result.units.all { it.importCounts.admitted == 1 })
        assertNotEquals(result.units[0].id, result.units[1].id)
    }

    @Test
    fun `conflicting cross unit coordinates fail before ambiguous source omission`(@TempDir root: Path) {
        val fixture = fixture(root, calledMethod = "other", duplicateInventoryBasename = true)
        val original = Files.readString(fixture.document)
        val extraPath = encode("src/other/Caller.java")
        val uniqueInventory = original.lineSequence().filterNot { it.startsWith("source\t$extraPath\t") }.joinToString("\n")
        Files.writeString(fixture.document, uniqueInventory)
        val other = Files.writeString(root.resolve("ambiguous.tsv"), original)
        val documents = listOf(fixture.document, other)
        val matching = CompilerEvidenceIndexer.enrichWithSourceUses(fixture.indexed, listOf(fixture.classes), documents,
            completedDocuments(fixture, documents)).result.graph.compilerSourceUseEvidence!!
        assertEquals(listOf(0, 1), matching.units.map { it.importCounts.admitted }.sorted())
        assertEquals(1, matching.units.sumOf { it.importCounts.sourceAssociationUnverified })
        assertEquals(1, matching.uses.size)
        val row = original.lineSequence().single { it.startsWith("call\t") }
        val fields = row.split('\t').toMutableList()
        fields[6] = (fields[6].toInt() + 1).toString()
        Files.writeString(other, original.replace(row, fields.joinToString("\t")))
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithSourceUses(fixture.indexed, listOf(fixture.classes), documents,
                completedDocuments(fixture, documents))
        }.also { assertContains(it.message.orEmpty(), "conflicting compiler source use assertions") }
        assertNull(fixture.indexed.graph.compilerSourceUseEvidence)
    }

    private fun completedDocuments(fixture: Fixture, documents: List<Path>): CompilerEvidenceContext {
        val witness = fixture.context.provenance.witnesses.single()
        val root = fixture.context.project
        val sources = Files.walk(root.resolve("src")).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.toList().toSet() }
        val receipts = CompilerEvidenceReceipts.validate(root, "javac", witness.evidenceToken!!, witness.inputs,
            documents, sources, listOf(root.resolve("src")), emptyList(), "compileJava")
        return fixture.context.copy(provenance = fixture.context.provenance.copy(witnesses = listOf(witness.copy(compilerEvidence = receipts))))
    }

    private fun capture(fixture: Fixture, indexed: IndexedClasses = fixture.indexed, context: CompilerEvidenceContext = fixture.context) =
        CompilerEvidenceIndexer.enrichWithSourceUses(indexed, listOf(fixture.classes), listOf(fixture.document), context)

    private fun refreshed(fixture: Fixture): CompilerEvidenceContext {
        val witness = fixture.context.provenance.witnesses.single()
        val receipt = ContentFingerprint.capture(fixture.context.project, fixture.document, "compilerEvidence", "receipt")
        return fixture.context.copy(provenance = fixture.context.provenance.copy(witnesses = listOf(witness.copy(
            compilerEvidence = witness.compilerEvidence.filter { it.role != "compilerEvidence" } + receipt))))
    }

    private fun copyClasses(source: Path, destination: Path): Path {
        Files.walk(source).use { paths -> paths.forEach { path ->
            val target = destination.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        return destination
    }

    private fun withOutputs(fixture: Fixture, roots: List<Path>, evidenceOrdinal: Int): CompilerEvidenceContext {
        val root = fixture.context.project
        val outputs = roots.mapIndexed { i, path -> ContentFingerprint.capture(root, path, "classes", "classes-$i") }
        val witness = fixture.context.provenance.witnesses.single()
        val witnesses = outputs.mapIndexed { i, output -> if (i == evidenceOrdinal) witness.copy(outputs = listOf(output))
            else BuildWitness(witness.scope, witness.compiler, witness.artifact, witness.inputs, listOf(output)) }
        val receipts = outputs.indices.map { i ->
            val file = Files.writeString(root.resolve("witness-$i.json"), "unit output witness $i")
            ContentFingerprint.capture(root, file, "witness", "witness-$i")
        }
        return fixture.context.copy(provenance = SnapshotProvenance(outputs + receipts, witnesses))
    }

    private fun replaceGraph(indexed: IndexedClasses, graph: CodeGraph): IndexedClasses = IndexedClasses(graph,
        indexed.observations, indexed.hierarchy, indexed.declarationsByRoot, indexed.selectedRootByNode, indexed.statistics)

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
        fun enrich(): CompilerEvidenceEnrichment = CompilerEvidenceIndexer.enrichWithSourceUses(
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

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
}
