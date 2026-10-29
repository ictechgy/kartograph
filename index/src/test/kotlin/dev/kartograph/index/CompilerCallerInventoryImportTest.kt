package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** importer 적대적 입력용 수동 문서다. 실제 compiler 전체 accounting oracle은 standalone suite에서 검증한다. */
class CompilerCallerInventoryImportTest {
    private val zero = NodeId("method:Entry#zero()Ljava/lang/String;")
    private fun b(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private data class Fixture(val source: Path, val noClass: Path, val classes: Path, val authored: Path, val inventory: Path, val sdk: Path, val context: CompilerEvidenceContext, val dependencies: List<Path> = emptyList()) {
        fun documents() = listOf(authored, inventory)
        fun refresh(): Fixture {
            val old = context.provenance.witnesses.single()
            val receipts = documents().mapIndexed { index, path -> ContentFingerprint.capture(context.project, path, "compilerEvidence", "evidence-$index") }
            return copy(context = context.copy(provenance = context.provenance.copy(witnesses = listOf(old.copy(compilerEvidence = receipts)))))
        }
        fun index() = CompilerCallerInventoryIndexer.index(listOf(classes), dependencies, sdk, documents(), context)
    }
    @Test fun `complete-zero no-class source and unsupported constructor have explicit inventory`(@TempDir root: Path) {
        val f = fixture(root)
        val graph = f.index().result.graph
        val raw = ClassFileIndexer(f.sdk).indexWithObservations(listOf(f.classes), emptyList()).graph
        assertEquals(raw.nodes, graph.nodes); assertEquals(raw.edges, graph.edges); assertEquals(raw.externalCalls, graph.externalCalls)
        val inventory = graph.compilerCallerInventories.single()
        assertEquals(2, inventory.methods.size)
        assertEquals(0, inventory.sourceBodyCounts.getValue("src/NoClass.java"))
        assertEquals(AuthoredCallCoverage.COMPLETE, graph.compilerAuthoredCallsFrom(zero)?.coverage)
        assertTrue(inventory.bodies.any { it.kind == CompilerSourceBodyKind.IMPLICIT_CONSTRUCTOR && it.offsetUtf16 == null })
        val witness = f.context.provenance.witnesses.single()
        assertEquals(2, CompilerEvidenceReceipts.validate(root, "javac", witness.evidenceToken!!, witness.inputs, f.documents(),
            setOf(f.source, f.noClass), listOf(f.source.parent), emptyList(), "compileJava").size)
    }
    @Test fun `omitted no-call method source body and no-class source cannot certify inventory`(@TempDir root: Path) {
        val f = fixture(root)
        val original = Files.readString(f.inventory)
        val variants = listOf(
            original.lineSequence().filterNot { it.startsWith("backend\t${b(zero.value)}\t") }.joinToString("\n") + "\n",
            original.lineSequence().filterNot { it.startsWith("body\tb0\t") || it.startsWith("body-method\tb0\t") }.joinToString("\n") + "\n",
            original.lineSequence().filterNot { it.startsWith("source\t${b("src/NoClass.java")}\t") || it.startsWith("body-count\t${b("src/NoClass.java")}\t") }.joinToString("\n") + "\n",
        )
        for (text in variants) {
            Files.writeString(f.inventory, text)
            assertFailsWith<IllegalArgumentException> { f.refresh().index() }
        }
    }
    @Test fun `implicit source compilation and missing inventory document fail completion`(@TempDir root: Path) {
        val f = fixture(root)
        val witness = f.context.provenance.witnesses.single()
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReceipts.validate(root, "javac", witness.evidenceToken!!, witness.inputs,
            f.documents(), setOf(f.source), listOf(f.source.parent), emptyList(), "compileJava") }
        assertFailsWith<IllegalArgumentException> { CompilerEvidenceReceipts.validate(root, "javac", witness.evidenceToken!!, witness.inputs,
            listOf(f.authored), setOf(f.source, f.noClass), listOf(f.source.parent), emptyList(), "compileJava") }
    }
    @Test fun `stale source identical output and extra output invalidate inventory`(@TempDir root: Path) {
        val f = fixture(root)
        val source = Files.readString(f.source)
        val before = ContentFingerprint.hashInput(f.classes, "classes")
        Files.writeString(f.source, source.replace("literal", "changed"))
        assertEquals(before, ContentFingerprint.hashInput(f.classes, "classes"))
        assertFailsWith<IllegalArgumentException> { f.index() }
        Files.writeString(f.source, source)
        Files.writeString(f.classes.resolve("leftover.txt"), "stale")
        assertFailsWith<IllegalArgumentException> { f.index() }
    }
    @Test fun `selected-root shadowing cannot borrow source inventory`(@TempDir root: Path) {
        val f = fixture(root)
        val shadow = Files.createDirectories(root.resolve("shadow")); Files.copy(f.classes.resolve("Entry.class"), shadow.resolve("Entry.class"))
        val output = ContentFingerprint.capture(root, shadow, "classes", "shadow")
        val old = f.context.provenance.witnesses.single()
        val shadowReceipt = old.copy(artifact = ":shadow", outputs = listOf(output), compilerEvidence = emptyList(), evidenceToken = null)
        val context = f.context.copy(provenance = f.context.provenance.copy(inputs = listOf(output) + f.context.provenance.inputs,
            witnesses = listOf(shadowReceipt, old)))
        assertFailsWith<IllegalArgumentException> {
            CompilerCallerInventoryIndexer.index(listOf(shadow, f.classes), emptyList(), f.sdk, f.documents(), context)
        }
    }
    @Test fun `dependency replacement and ordered resolution change invalidate unchanged source outputs`(@TempDir root: Path) {
        val f = fixture(root, withDependencies = true)
        f.index()
        val before = ContentFingerprint.hashInput(f.classes, "classes")
        assertFailsWith<IllegalArgumentException> { f.copy(dependencies = f.dependencies.reversed()).index() }
        Files.writeString(f.dependencies.first().resolve("state.txt"), "replacement dependency only")
        assertEquals(before, ContentFingerprint.hashInput(f.classes, "classes"))
        assertFailsWith<IllegalArgumentException> { f.index() }
    }
    @Test fun `missing marker ambiguous receipt and mismatched task output fail closed`(@TempDir root: Path) {
        val f = fixture(root)
        val witness = f.context.provenance.witnesses.single()
        val variants = listOf(
            f.context.provenance.copy(witnesses = listOf(witness.copy(inputs = witness.inputs.filterNot { it == CompilerCallerInventoryOptions.enabledInput() }))),
            f.context.provenance.copy(witnesses = listOf(witness, witness.copy(artifact = ":other"))),
        )
        variants.forEach { proof -> assertFailsWith<IllegalArgumentException> { f.copy(context = f.context.copy(provenance = proof)).index() } }
        val text = Files.readString(f.inventory)
        Files.createDirectories(root.resolve("other-output"))
        Files.writeString(f.inventory, text.replace("output-root\t${b("classes")}", "output-root\t${b("other-output")}"))
        assertFailsWith<IllegalArgumentException> { f.refresh().index() }
    }
    private fun fixture(root: Path, withDependencies: Boolean = false): Fixture {
        val sdk = Path.of(System.getProperty("java.home")).toRealPath()
        val src = Files.createDirectories(root.resolve("src"))
        val source = Files.writeString(src.resolve("Entry.java"), "public class Entry { public static String zero() { return \"literal\"; } }")
        val noClass = Files.writeString(src.resolve("NoClass.java"), "package noClasses;\n")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-d", classes.toString(), source.toString(), noClass.toString()))
        val artifact = Files.writeString(root.resolve("collector.jar"), "test collector input")
        val build = Files.writeString(root.resolve("build.conf"), "fresh task-owned output source-backend-accounting-v1")
        val witnessFile = Files.writeString(root.resolve("witness.json"), "completed importer fixture")
        val external = linkedMapOf<String, Path>()
        fun fp(path: Path, role: String, key: String): InputFingerprint = ContentFingerprint.capture(root, path, role, key).also { if (it.path.startsWith("external/")) external[it.path] = path }
        val dependencies = if (withDependencies) (1..2).map { index ->
            Files.createDirectories(root.resolve("dependency-$index")).also { Files.writeString(it.resolve("state.txt"), "binary dependency $index") }
        } else emptyList()
        val inputs = dependencies.mapIndexed { index, dependency -> fp(dependency, "classpath", "dependency-$index") } + listOf(fp(src, "sources", "sources"), fp(build, "buildConfig", "build"), fp(artifact, "processor", "collector"),
            fp(sdk.resolve("lib/modules"), "compiler", "jdk-modules"), fp(sdk.resolve("jmods"), "compilerInput", "jdk-headers"), fp(sdk.resolve("release"), "compilerInput", "jdk-release"),
            InputFingerprint("options", "compiler-options", ContentFingerprint.values(listOf("UTF-8", "native-sdk"))), CompilerAuthoredCallOptions.enabledInput(), CompilerCallerInventoryOptions.enabledInput())
        val token = CompilerEvidenceToken.create("fixture", "javac", ":compileJava", inputs)
        val common = "collector\tjavac-constants\ncompiler\t${Runtime.version()}\ntoken\t$token\nartifact\t${inputs.single { it.role == "processor" }.sha256}\n"
        val sources = "source\t${b("src/Entry.java")}\t${CompilerEvidenceIndexer.sourceHash(source)}\nsource\t${b("src/NoClass.java")}\t${CompilerEvidenceIndexer.sourceHash(noClass)}\n"
        val authored = Files.writeString(root.resolve("authored.tsv"), "${CompilerAuthoredCallReader.HEADER}\n${common}profile\tordinary-body-v1\nunmapped\t0\n$sources" +
            "caller\t${b(zero.value)}\t${b("src/Entry.java")}\t21\t69\tCOMPLETE\t2\t0\tNONE\n")
        val constructor = "method:Entry#<init>()V"
        val inventory = Files.writeString(root.resolve("callers.tsv"), "${CompilerCallerInventoryReader.HEADER}\n${common}profile\t${CompilerCallerInventoryReader.PROFILE}\noutput-root\t${b("classes")}\n$sources" +
            "body-count\t${b("src/Entry.java")}\t2\nbody-count\t${b("src/NoClass.java")}\t0\n" +
            "body\tb0\t${b("src/Entry.java")}\t21\t69\tORDINARY_METHOD\ttrue\t${b("JAVAC_EXPLICIT")}\nbody-method\tb0\t${b(zero.value)}\n" +
            "body\tb1\t${b("src/Entry.java")}\t-1\t-1\tIMPLICIT_CONSTRUCTOR\ttrue\t${b("JAVAC_MANDATED")}\nbody-method\tb1\t${b(constructor)}\n" +
            "output\t${b("Entry.class")}\t${CompilerEvidenceIndexer.sourceHash(classes.resolve("Entry.class"))}\t${b("Entry")}\n" +
            "backend\t${b(zero.value)}\t9\ttrue\tSOURCE_BOUND\tb0\t${b("JAVAC_EXPLICIT")}\nbackend\t${b(constructor)}\t1\ttrue\tSOURCE_BOUND\tb1\t${b("JAVAC_MANDATED")}\n")
        val output = fp(classes, "classes", "classes")
        val witness = BuildWitness("fixture", "javac", ":compileJava", inputs, listOf(output), listOf(fp(authored, "compilerEvidence", "authored"), fp(inventory, "compilerEvidence", "inventory")), token)
        val context = CompilerEvidenceContext(root, "fixture", SnapshotProvenance(listOf(output, fp(witnessFile, "witness", "witness")), listOf(witness)), external)
        return Fixture(source, noClass, classes, authored, inventory, sdk, context, dependencies)
    }
}
