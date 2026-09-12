package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CompilerEvidenceImportTest {
    @Test
    fun `receipted references enrich the real compiled graph and keep unused controls`(@TempDir root: Path) {
        val fixture = fixture(root)
        val source = JvmNodeId.methodId("Entry", "read", "()I")
        val used = JvmNodeId.fieldId("Entry", "USED", "I")
        val unused = JvmNodeId.fieldId("Entry", "UNUSED", "I")
        assertFalse(fixture.indexed.graph.edges.any { it.source == source && it.target == used })
        val result = CompilerEvidenceIndexer.enrich(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        val selected = result.graph.edges.filter { it.origin == EdgeOrigin.COMPILER_REFERENCE }
        assertEquals(1, selected.size)
        assertEquals(source, selected.single().source)
        assertEquals(used, selected.single().target)
        assertFalse(selected.any { it.target == unused })
        assertEquals(0, result.unmappedReferences)
    }

    @Test
    fun `altered raw rows cannot use the previous completed receipt`(@TempDir root: Path) {
        val fixture = fixture(root)
        Files.writeString(fixture.document, Files.readString(fixture.document).replace(encode("field:Entry#USED:I"), encode("field:Entry#UNUSED:I")))
        val failure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrich(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        }
        assertContains(failure.message.orEmpty(), "matched build inputs")
    }

    @Test
    fun `matching receipt bytes do not excuse a token from a different build`(@TempDir root: Path) {
        val fixture = fixture(root, tokenOverride = "f".repeat(64))
        val failure = assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.enrich(fixture.indexed, listOf(fixture.classes), listOf(fixture.document), fixture.context)
        }
        assertContains(failure.message.orEmpty(), "token does not match")
    }

    @Test
    fun `producer refuses a partial inventory and records generated source outputs`(@TempDir root: Path) {
        val fixture = fixture(root)
        val witness = fixture.context.provenance.witnesses.single()
        val source = root.resolve("src/Entry.java")
        fun receipts(expected: Set<Path>, generated: List<Path> = emptyList()) = CompilerEvidenceReceipts.validate(
            root, "javac", witness.evidenceToken!!, witness.inputs, listOf(fixture.document), expected,
            listOf(root.resolve("src")), generated, "compileJava")
        assertEquals(witness.compilerEvidence, receipts(setOf(source)))
        val other = Files.writeString(root.resolve("src/Other.java"), "class Other {}")
        val partial = assertFailsWith<IllegalArgumentException> { receipts(setOf(source, other)) }
        assertContains(partial.message.orEmpty(), "partial")
        val generated = Files.createDirectories(root.resolve("generated")).resolve("Generated.java")
        Files.writeString(generated, "class Generated {}")
        Files.writeString(fixture.document, Files.readString(fixture.document) +
            "source\t${encode("generated/Generated.java")}\t${CompilerEvidenceIndexer.sourceHash(generated)}\n")
        val undeclared = assertFailsWith<IllegalArgumentException> { receipts(setOf(source)) }
        assertContains(undeclared.message.orEmpty(), "undeclared source")
        val accepted = receipts(setOf(source), listOf(generated.parent))
        assertEquals(listOf("compilerEvidence", "compilerGeneratedSource"), accepted.map { it.role })
    }

    @Test
    fun `a later root cannot contribute references for a shadowed source declaration`(@TempDir root: Path) {
        val fixture = fixture(root)
        val source = Files.createDirectories(root.resolve("shadow-src")).resolve("Entry.java")
        Files.writeString(source, "public class Entry { public static final int USED=7, UNUSED=7; public int read(){return UNUSED;} }")
        val classes = Files.createDirectories(root.resolve("shadow-classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val original = fixture.context.provenance.witnesses.single()
        val output = ContentFingerprint.capture(root, classes, "classes", "shadow-classes")
        val shadowInputs = original.inputs.map { if (it.role == "sources")
            ContentFingerprint.capture(root, source.parent, "sources", "shadow-sources") else it }
        val shadow = BuildWitness("sample:main", "javac", ":compileShadow", shadowInputs, listOf(output))
        val marker = Files.writeString(root.resolve("shadow-witness.json"), "unit shadow witness")
        val provenance = fixture.context.provenance.copy(inputs = listOf(output) + fixture.context.provenance.inputs +
            ContentFingerprint.capture(root, marker, "witness", "shadow-witness"), witnesses = listOf(shadow, original))
        val roots = listOf(classes, fixture.classes)
        val result = CompilerEvidenceIndexer.enrich(ClassFileIndexer().indexWithObservations(roots), roots,
            listOf(fixture.document), fixture.context.copy(provenance = provenance))
        assertEquals(1, result.shadowedReferences)
        assertFalse(result.graph.edges.any { it.origin == EdgeOrigin.COMPILER_REFERENCE })
    }

    @Test
    fun `private declarations and generated markers keep the same evidence endpoints`(@TempDir root: Path) {
        val fixture = fixture(root, privateMembers = true)
        val roots = listOf(fixture.classes)
        val indexed = ClassFileIndexer().indexWithObservations(roots, null, emptyList(), roots)
        assertEquals(1, indexed.declarationsByRoot.size)
        val result = CompilerEvidenceIndexer.enrich(indexed, roots, listOf(fixture.document), fixture.context)
        assertEquals(1, result.graph.edges.count { it.origin == EdgeOrigin.COMPILER_REFERENCE })
        assertTrue(result.graph.nodes.getValue(JvmNodeId.methodId("Entry", "read", "()I")).synthesized)
    }

    // 실제 javac 출력에 수동 증거를 조립해 소비 경계를 검사한다. 수집기 실행 증명은 별도 통합 검사가 맡는다.
    private fun fixture(root: Path, tokenOverride: String? = null, privateMembers: Boolean = false): Fixture {
        val sources = Files.createDirectories(root.resolve("src"))
        val source = sources.resolve("Entry.java")
        val visibility = if (privateMembers) "private" else "public"
        Files.writeString(source, "public class Entry { $visibility static final int USED=7, UNUSED=7; $visibility int read(){return USED;} }")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        Files.writeString(root.resolve("compiler.bin"), "unit compiler identity")
        Files.writeString(root.resolve("collector.jar"), "unit collector identity")
        Files.writeString(root.resolve("witness.json"), "unit witness fixture")
        fun fp(role: String, path: String) = ContentFingerprint.capture(root, root.resolve(path), role, role)
        val inputs = listOf(fp("sources", "src"), fp("buildConfig", "build.gradle"), fp("compiler", "compiler.bin"),
            fp("processor", "collector.jar"), InputFingerprint("options", "options", ContentFingerprint.values(listOf("unit options"))))
        val token = CompilerEvidenceToken.create("sample:main", "javac", ":compileJava", inputs)
        val document = root.resolve("evidence.tsv")
        Files.writeString(document, "format\tkartograph-compiler-evidence\t1\ncollector\tjavac-constants\ncompiler\t17\n" +
            "token\t${tokenOverride ?: token}\nartifact\t${inputs.single { it.role == "processor" }.sha256}\nunmapped\t0\n" +
            "source\t${encode("src/Entry.java")}\t${CompilerEvidenceIndexer.sourceHash(source)}\n" +
            "edge\t${encode("method:Entry#read()I")}\t${encode("field:Entry#USED:I")}\tconstant\n")
        val output = fp("classes", "classes")
        val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(output),
            listOf(fp("compilerEvidence", "evidence.tsv")), token)
        val provenance = SnapshotProvenance(listOf(output, fp("witness", "witness.json")), listOf(witness))
        return Fixture(classes, document, ClassFileIndexer().indexWithObservations(listOf(classes)),
            CompilerEvidenceContext(root, "sample:main", provenance))
    }

    private data class Fixture(val classes: Path, val document: Path, val indexed: IndexedClasses, val context: CompilerEvidenceContext)
    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
