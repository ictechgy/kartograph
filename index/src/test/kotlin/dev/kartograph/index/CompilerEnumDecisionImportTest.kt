package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.tools.ToolProvider
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class CompilerEnumDecisionImportTest {
    @Test fun `typed enum receipt imports without changing graph edges`(@TempDir root: Path) {
        val fixture = fixture(root)
        val result = fixture.capture()
        val evidence = assertNotNull(result.enumDecisions)
        assertEquals(fixture.indexed.graph.edges, result.result.graph.edges)
        assertEquals(EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT, evidence.sites.single().attribution)
        assertEquals(listOf(NodeId("field:demo/E#A:Ldemo/E;")), evidence.sites.single().constants)
        assertEquals(CompilerEnumDecisionImportCounts(1, 0), evidence.units.single().importCounts)
        evidence.requireMatchesGraph(result.result.graph)
        evidence.requireMatchesProvenance(fixture.context.provenance, fixture.context.scope)
    }

    @Test fun `zero emissions differ from missing v4 and missing enabled option`(@TempDir root: Path) {
        val empty = fixture(root.resolve("empty"), emitted = false).capture().enumDecisions!!
        assertTrue(empty.sites.isEmpty())
        assertEquals(CompilerEnumDecisionProducerCounts(1, 0, 1, 0), empty.units.single().producer)
        val legacyFixture = fixture(root.resolve("legacy"))
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrichWithEnumDecisions(legacyFixture.indexed, listOf(legacyFixture.classes), emptyList(), legacyFixture.context)
        }
        val legacy = Files.readString(legacyFixture.document).lineSequence().filterNot {
            it.startsWith("enumStats\t") || it.startsWith("enumDecision\t")
        }.joinToString("\n").replace("evidence\t4", "evidence\t1")
        Files.writeString(legacyFixture.document, legacy)
        assertFailsWith<IllegalArgumentException> { legacyFixture.capture(context = legacyFixture.refreshed()) }
        assertFailsWith<IllegalArgumentException> { fixture(root.resolve("no-option"), option = false) }
    }

    @Test fun `raw v4 rejects malformed identity count ordering and source association`(@TempDir root: Path) {
        val fixture = fixture(root)
        val original = Files.readString(fixture.document)
        val row = original.lineSequence().single { it.startsWith("enumDecision\t") }
        val badRows = listOf(
            row.replace("\t1\t" + encode("field:demo/E#A:Ldemo/E;"), "\t2\t" + encode("field:demo/E#A:Ldemo/E;")),
            row.replace(encode("field:demo/E#A:Ldemo/E;"), encode("field:demo/Other#A:Ldemo/Other;")),
            row.replace("\tEXPLICIT_FALLBACK\t", "\tUNKNOWN\t"),
            row.replace("\tfalse\t", "\tFalse\t"),
            row.replace(encode("src/Users.java"), encode("src/Missing.java")),
        )
        badRows.forEach { bad -> assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(original.replace(row, bad)) } }
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(original + row + "\n") }
        val conflicting = original.replace("enumStats\t1\t1\t0\t0", "enumStats\t2\t2\t0\t0") +
            row.replace("\tEXPLICIT_FALLBACK\t", "\tFINITE_MEMBERSHIP\t") + "\n"
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(conflicting) }
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parseEnvelope(original.replace("enumStats\t1\t1\t0\t0", "enumStats\t2\t1\t0\t0")) }
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReader.parse(original) }
    }

    @Test fun `top level unit needs one output root and duplicate basenames remain unresolved`(@TempDir root: Path) {
        val top = fixture(root.resolve("top"), lexical = null)
        assertEquals(EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT, top.capture().enumDecisions!!.sites.single().attribution)
        val second = Files.createDirectories(top.context.project.resolve("second"))
        val roots = listOf(top.classes, second)
        val outputs = roots.mapIndexed { index, path -> ContentFingerprint.capture(top.context.project, path, "classes", "classes-$index") }
        val witness = top.context.provenance.witnesses.single().copy(outputs = outputs)
        val context = top.context.copy(provenance = SnapshotProvenance(outputs + top.context.provenance.inputs.filter { it.role != "classes" }, listOf(witness)))
        val multi = CompilerEvidenceIndexer.enrichWithEnumDecisions(ClassFileIndexer().indexWithObservations(roots), roots,
            listOf(top.document), context).enumDecisions!!
        assertEquals(EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED, multi.sites.single().attribution)
        val duplicate = fixture(root.resolve("duplicate"), duplicateBasename = true).capture().enumDecisions!!
        assertEquals(EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED, duplicate.sites.single().attribution)
    }

    @Test fun `external same named enum identity never borrows local inventory`(@TempDir root: Path) {
        val fixture = fixture(root, enumOwner = "class:external/E")
        val evidence = fixture.capture().enumDecisions!!
        assertEquals(EnumDecisionAttribution.ENUM_ROOT_UNRESOLVED, evidence.sites.single().attribution)
        assertEquals(CompilerEnumDecisionImportCounts(0, 1), evidence.units.single().importCounts)
        assertEquals(NodeId("class:external/E"), evidence.sites.single().enumOwner)
    }

    @Test fun `unavailable and outside finite constants keep explicit dispositions`(@TempDir root: Path) {
        val fixture = fixture(root)
        val graph = fixture.indexed.graph
        val missing = replaceGraph(fixture.indexed, CodeGraph(graph.nodes.values.map {
            if (it.id == NodeId("class:demo/E")) it.copy(enumConstants = null) else it
        }, graph.edges))
        assertEquals(EnumDecisionAttribution.ENUM_INVENTORY_UNAVAILABLE,
            fixture.capture(indexed = missing).enumDecisions!!.sites.single().attribution)
        val outside = Files.readString(fixture.document).replace(encode("field:demo/E#A:Ldemo/E;"), encode("field:demo/E#MISSING:Ldemo/E;"))
        Files.writeString(fixture.document, outside)
        assertEquals(EnumDecisionAttribution.CONSTANTS_OUTSIDE_INVENTORY,
            fixture.capture(context = fixture.refreshed()).enumDecisions!!.sites.single().attribution)
    }

    @Test fun `shadowed output and source hash changes cannot become resolved`(@TempDir root: Path) {
        val fixture = fixture(root)
        val duplicate = Files.createDirectories(root.resolve("shadow"))
        Files.walk(fixture.classes).use { paths -> paths.forEach { source ->
            val destination = duplicate.resolve(fixture.classes.relativize(source).toString())
            if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
        } }
        val roots = listOf(duplicate, fixture.classes)
        val outputs = roots.mapIndexed { index, path -> ContentFingerprint.capture(root, path, "classes", "classes-$index") }
        val context = fixture.context.copy(provenance = SnapshotProvenance(
            outputs + fixture.context.provenance.inputs.filter { it.role != "classes" },
            listOf(fixture.context.provenance.witnesses.single().copy(outputs = listOf(outputs[1])))))
        val evidence = CompilerEvidenceIndexer.enrichWithEnumDecisions(ClassFileIndexer().indexWithObservations(roots),
            roots, listOf(fixture.document), context).enumDecisions!!
        assertEquals(EnumDecisionAttribution.SHADOWED_SOURCE, evidence.sites.single().attribution)
        Files.writeString(root.resolve("src/Users.java"), "changed")
        assertFailsWith<IllegalArgumentException> { fixture.capture() }
    }

    @Test fun `one lexical class in two claimed roots stays unresolved`(@TempDir root: Path) {
        val fixture = fixture(root)
        val duplicate = Files.createDirectories(root.resolve("duplicate-output"))
        Files.walk(fixture.classes).use { paths -> paths.forEach { source ->
            val destination = duplicate.resolve(fixture.classes.relativize(source).toString())
            if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
        } }
        val roots = listOf(fixture.classes, duplicate)
        val outputs = roots.mapIndexed { index, path -> ContentFingerprint.capture(root, path, "classes", "classes-$index") }
        val context = fixture.context.copy(provenance = SnapshotProvenance(
            outputs + fixture.context.provenance.inputs.filter { it.role != "classes" },
            listOf(fixture.context.provenance.witnesses.single().copy(outputs = outputs))))
        val evidence = CompilerEvidenceIndexer.enrichWithEnumDecisions(ClassFileIndexer().indexWithObservations(roots),
            roots, listOf(fixture.document), context).enumDecisions!!
        assertEquals(EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED, evidence.sites.single().attribution)
        assertEquals(CompilerEnumDecisionImportCounts(0, 1), evidence.units.single().importCounts)
    }

    @Test fun `warm receipt observation is rechecked and repeated explicit route is deduplicated`(@TempDir root: Path) {
        val fixture = fixture(root)
        val evidence = CompilerEvidenceIndexer.enrichWithEnumDecisions(fixture.indexed, listOf(fixture.classes),
            listOf(fixture.document, fixture.document), fixture.context).enumDecisions!!
        assertEquals(1, evidence.units.size)
        val capture = CompilerEnumDecisionImport(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        assertEquals("matched", capture.verifyFreshness(false).status)
        Files.writeString(fixture.document, Files.readString(fixture.document).replace("EXPLICIT_FALLBACK", "FINITE_MEMBERSHIP"))
        assertFailsWith<IllegalArgumentException> { capture.verifyFreshness(true) }
    }

    private fun fixture(root: Path, emitted: Boolean = true, option: Boolean = true,
        lexical: String? = "class:demo/Users", enumOwner: String = "class:demo/E", duplicateBasename: Boolean = false): Fixture {
        val sources = Files.createDirectories(root.resolve("src"))
        val user = Files.writeString(sources.resolve("Users.java"),
            "package demo; public class Users { public int check(E value) { switch(value) { case A: return 1; default: return 0; } } }")
        val enum = Files.writeString(sources.resolve("E.java"), "package demo; public enum E { A, B; public static final E ALIAS = A; }")
        val duplicate = if (duplicateBasename) Files.writeString(Files.createDirectories(sources.resolve("other")).resolve("Users.java"),
            "package other; public class Users {}") else null
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            *(listOf("-g", "-d", classes.toString(), user.toString(), enum.toString()) + listOfNotNull(duplicate?.toString())).toTypedArray()))
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        Files.writeString(root.resolve("compiler.bin"), "unit compiler identity")
        Files.writeString(root.resolve("collector.jar"), "unit collector identity")
        Files.writeString(root.resolve("witness.json"), "unit completed receipt")
        fun fp(role: String, name: String) = ContentFingerprint.capture(root, root.resolve(name), role, role)
        val inputs = listOf(fp("sources", "src"), fp("buildConfig", "build.gradle"), fp("compiler", "compiler.bin"),
            fp("processor", "collector.jar"), InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("unit")))) +
            if (option) listOf(CompilerEnumDecisionOptions.enabledInput()) else emptyList()
        val token = CompilerEvidenceToken.create("sample:main", "javac", ":compileJava", inputs)
        val text = Files.readString(user)
        val start = text.indexOf("switch")
        val end = text.lastIndexOf(" } }") + 2
        val binary = enumOwner.removePrefix("class:")
        val sourceFiles = listOf(user, enum) + listOfNotNull(duplicate)
        val document = Files.writeString(root.resolve("evidence.tsv"), buildString {
            append("format\tkartograph-compiler-evidence\t4\ncollector\tjavac-constants\ncompiler\t17\n")
            append("token\t$token\nartifact\t").append(inputs.single { it.role == "processor" }.sha256).append("\nunmapped\t0\n")
            sourceFiles.forEach { path -> append("source\t").append(encode(root.relativize(path).toString().replace('\\', '/')))
                .append('\t').append(CompilerEvidenceIndexer.sourceHash(path)).append('\n') }
            append(if (emitted) "enumStats\t1\t1\t0\t0\n" else "enumStats\t1\t0\t1\t0\n")
            if (emitted) append(listOf("enumDecision", encode("src/Users.java"), CompilerEvidenceIndexer.sourceHash(user),
                start.toString(), end.toString(), "1", (start + 1).toString(), lexical?.let(::encode) ?: "-",
                encode(enumOwner), "EXPLICIT_FALLBACK", "false", "1", encode("field:$binary#A:L$binary;")).joinToString("\t")).append('\n')
        })
        val receipts = CompilerEvidenceReceipts.validate(root, "javac", token, inputs, listOf(document), sourceFiles.toSet(),
            listOf(sources), emptyList(), "compileJava")
        val output = fp("classes", "classes")
        val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(output), receipts, token)
        val context = CompilerEvidenceContext(root, "sample:main", SnapshotProvenance(listOf(output, fp("witness", "witness.json")), listOf(witness)))
        return Fixture(classes, document, ClassFileIndexer().indexWithObservations(listOf(classes)), context)
    }

    private data class Fixture(val classes: Path, val document: Path, val indexed: IndexedClasses, val context: CompilerEvidenceContext) {
        fun capture(indexed: IndexedClasses = this.indexed, context: CompilerEvidenceContext = this.context) =
            CompilerEvidenceIndexer.enrichWithEnumDecisions(indexed, listOf(classes), listOf(document), context)
        fun refreshed(): CompilerEvidenceContext {
            val witness = context.provenance.witnesses.single()
            val receipt = ContentFingerprint.capture(context.project, document, "compilerEvidence", "receipt")
            return context.copy(provenance = context.provenance.copy(witnesses = listOf(witness.copy(compilerEvidence = listOf(receipt)))))
        }
    }

    private fun replaceGraph(indexed: IndexedClasses, graph: CodeGraph): IndexedClasses = IndexedClasses(graph,
        indexed.observations, indexed.hierarchy, indexed.declarationsByRoot, indexed.selectedRootByNode, indexed.statistics)

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
