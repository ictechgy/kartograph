package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** 수동 envelope는 importer의 적대적 입력 테스트이며 compiler producer 증명은 별도로 검증한다. */
class CompilerAuthoredCallImportTest {
    private val caller = NodeId("method:Entry#zero()Ljava/lang/String;")
    private fun b(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    @Test fun `verified complete zero stays separate and source byte swap invalidates unchanged output`(@TempDir root: Path) {
        val f = fixture(root)
        val raw = ClassFileIndexer(f.sdk).indexWithObservations(listOf(f.classes), emptyList()).graph
        val result = CompilerAuthoredCallIndexer.index(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), f.context)
        assertEquals(AuthoredCallCoverage.COMPLETE, result.result.graph.compilerAuthoredCallsFrom(caller)?.coverage)
        assertEquals(raw.nodes, result.result.graph.nodes)
        assertEquals(raw.edges, result.result.graph.edges)
        assertEquals(raw.externalCalls, result.result.graph.externalCalls)
        val before = ContentFingerprint.hashInput(f.classes, "classes")
        Files.writeString(f.source, Files.readString(f.source).replace("literal", "changed"))
        assertEquals(before, ContentFingerprint.hashInput(f.classes, "classes"))
        assertFailsWith<IllegalArgumentException> { CompilerAuthoredCallIndexer.index(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), f.context) }
    }

    @Test fun `unreceipted tampered output and SDK input omissions cannot bless coverage`(@TempDir root: Path) {
        val f = fixture(root)
        val missing = f.context.copy(provenance = f.context.provenance.copy(witnesses = emptyList()))
        assertFailsWith<IllegalArgumentException> { CompilerAuthoredCallIndexer.index(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), missing) }
        val sdkMissing = f.context.provenance.witnesses.single().let { old ->
            val inputs = old.inputs.filterNot { it.role == "compilerInput" }
            val token = CompilerEvidenceToken.create(old.scope, old.compiler, old.artifact, inputs)
            Files.writeString(f.document, Files.readString(f.document).replace(old.evidenceToken!!, token))
            old.copy(inputs = inputs, evidenceToken = token,
                compilerEvidence = listOf(ContentFingerprint.capture(root, f.document, "compilerEvidence", "evidence")))
        }
        val withoutSdk = f.context.copy(provenance = f.context.provenance.copy(witnesses = listOf(sdkMissing)))
        assertContains(assertFailsWith<IllegalArgumentException> {
            CompilerAuthoredCallIndexer.index(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), withoutSdk)
        }.message.orEmpty(), "SDK headers")
        Files.write(f.classes.resolve("Entry.class"), byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { CompilerAuthoredCallIndexer.index(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), f.context) }
    }

    @Test fun `feature absence and ordered dependency replacement reject source coverage`(@TempDir root: Path) {
        val f = fixture(root, marker = false)
        assertContains(assertFailsWith<IllegalArgumentException> {
            CompilerAuthoredCallIndexer.index(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), f.context)
        }.message.orEmpty(), "option/token")
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceReceipts.validate(root, "javac", f.context.provenance.witnesses.single().evidenceToken!!,
                f.context.provenance.witnesses.single().inputs, listOf(f.document), setOf(f.source), listOf(f.source.parent), emptyList(), "compileJava")
        }
    }

    @Test fun `missing caller stays unknown and dependency-only replacement invalidates unchanged classes`(@TempDir root: Path) {
        val f = fixture(root)
        val emptyText = Files.readString(f.document).lineSequence().filterNot { it.startsWith("caller\t") }.joinToString("\n") + "\n"
        Files.writeString(f.document, emptyText)
        val old = f.context.provenance.witnesses.single()
        val refreshed = old.copy(compilerEvidence = listOf(ContentFingerprint.capture(root, f.document, "compilerEvidence", "evidence")))
        val missingContext = f.context.copy(provenance = f.context.provenance.copy(witnesses = listOf(refreshed)))
        val missing = CompilerEvidenceIndexer.indexWithAuthoredCalls(listOf(f.classes), emptyList(), f.sdk, listOf(f.document), missingContext)
        assertNull(missing.result.graph.compilerAuthoredCallsFrom(caller))
        assertTrue(missing.result.graph.compilerAuthoredCallers.isEmpty())

        val dependency = Files.createDirectories(root.resolve("dependency"))
        val dependencySource = Files.writeString(root.resolve("Dependency.java"), "public class Dependency { public static int value() { return 1; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dependency.toString(), dependencySource.toString()))
        val depInput = ContentFingerprint.capture(root, dependency, "classpath", "dependency")
        val inputs = old.inputs + depInput
        val token = CompilerEvidenceToken.create(old.scope, old.compiler, old.artifact, inputs)
        Files.writeString(f.document, emptyText.replace(old.evidenceToken!!, token))
        val withDependency = old.copy(inputs = inputs, evidenceToken = token,
            compilerEvidence = listOf(ContentFingerprint.capture(root, f.document, "compilerEvidence", "evidence")))
        val depContext = f.context.copy(provenance = f.context.provenance.copy(witnesses = listOf(withDependency)))
        CompilerEvidenceIndexer.indexWithAuthoredCalls(listOf(f.classes), listOf(dependency), f.sdk, listOf(f.document), depContext)
        val before = ContentFingerprint.hashInput(f.classes, "classes")
        Files.writeString(dependencySource, "public class Dependency { public static int value() { return 2; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dependency.toString(), dependencySource.toString()))
        assertEquals(before, ContentFingerprint.hashInput(f.classes, "classes"))
        assertFailsWith<IllegalArgumentException> {
            CompilerEvidenceIndexer.indexWithAuthoredCalls(listOf(f.classes), listOf(dependency), f.sdk, listOf(f.document), depContext)
        }
    }

    @Test fun `selected root ownership cannot borrow coverage from a shadowed output`(@TempDir root: Path) {
        val f = fixture(root)
        val shadow = Files.createDirectories(root.resolve("shadow-classes"))
        Files.copy(f.classes.resolve("Entry.class"), shadow.resolve("Entry.class"))
        val old = f.context.provenance.witnesses.single()
        val shadowOutput = ContentFingerprint.capture(root, shadow, "classes", "shadow-output")
        val shadowWitnessFile = Files.writeString(root.resolve("shadow-witness.json"), "completed shadow fixture")
        val shadowWitness = old.copy(artifact = ":shadowCompile", outputs = listOf(shadowOutput), compilerEvidence = emptyList(), evidenceToken = null)
        val inputs = listOf(shadowOutput) + f.context.provenance.inputs + ContentFingerprint.capture(root, shadowWitnessFile, "witness", "shadow-witness")
        val context = f.context.copy(provenance = f.context.provenance.copy(inputs = inputs, witnesses = listOf(old, shadowWitness)))
        val shadowed = CompilerAuthoredCallIndexer.index(listOf(shadow, f.classes), emptyList(), f.sdk, listOf(f.document), context)
        assertNull(shadowed.result.graph.compilerAuthoredCallsFrom(caller))
        assertContains(shadowed.limitations, "compiler-authored-calls-shadowed")
        assertFailsWith<IllegalArgumentException> {
            CompilerAuthoredCallIndexer.index(listOf(f.classes, shadow), emptyList(), f.sdk, listOf(f.document), context)
        }
    }

    @Test fun `ordered dependencies are not an unordered fingerprint set`(@TempDir root: Path) {
        val f = fixture(root)
        val first = Files.createDirectories(root.resolve("first-dependency"))
        val second = Files.createDirectories(root.resolve("second-dependency"))
        val old = f.context.provenance.witnesses.single()
        val inputs = old.inputs + listOf(ContentFingerprint.capture(root, first, "classpath", "first"),
            ContentFingerprint.capture(root, second, "classpath", "second"))
        val token = CompilerEvidenceToken.create(old.scope, old.compiler, old.artifact, inputs)
        Files.writeString(f.document, Files.readString(f.document).replace(old.evidenceToken!!, token))
        val receipt = old.copy(inputs = inputs, evidenceToken = token,
            compilerEvidence = listOf(ContentFingerprint.capture(root, f.document, "compilerEvidence", "evidence")))
        val context = f.context.copy(provenance = f.context.provenance.copy(witnesses = listOf(receipt)))
        CompilerAuthoredCallIndexer.index(listOf(f.classes), listOf(first, second), f.sdk, listOf(f.document), context)
        assertContains(assertFailsWith<IllegalArgumentException> {
            CompilerAuthoredCallIndexer.index(listOf(f.classes), listOf(second, first), f.sdk, listOf(f.document), context)
        }.message.orEmpty(), "ordered classpath")
    }

    private data class Fixture(val source: Path, val classes: Path, val document: Path, val sdk: Path, val context: CompilerEvidenceContext)
    private fun fixture(root: Path, marker: Boolean = true): Fixture {
        val sdk = Path.of(System.getProperty("java.home")).toRealPath()
        val source = Files.createDirectories(root.resolve("src")).resolve("Entry.java")
        Files.writeString(source, "public class Entry { public static String zero() { return \"literal\"; } }")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-d", classes.toString(), source.toString()))
        val artifact = Files.writeString(root.resolve("collector.jar"), "test collector input")
        val build = Files.writeString(root.resolve("build.conf"), "explicit native SDK UTF-8 ordinary-body-v1")
        val witnessFile = Files.writeString(root.resolve("witness.json"), "completed importer fixture")
        val external = linkedMapOf<String, Path>()
        fun fp(path: Path, role: String, key: String): InputFingerprint = ContentFingerprint.capture(root, path, role, key).also {
            if (it.path.startsWith("external/")) external[it.path] = path
        }
        val inputs = listOf(fp(source.parent, "sources", "sources"), fp(build, "buildConfig", "build"),
            fp(artifact, "processor", "collector"), fp(sdk.resolve("lib/modules"), "compiler", "jdk-modules"),
            fp(sdk.resolve("jmods"), "compilerInput", "jdk-headers"), fp(sdk.resolve("release"), "compilerInput", "jdk-release"),
            InputFingerprint("options", "compiler-options", ContentFingerprint.values(listOf("UTF-8", "native-sdk")))) +
            if (marker) listOf(CompilerAuthoredCallOptions.enabledInput()) else emptyList()
        val token = CompilerEvidenceToken.create("fixture", "javac", ":compileJava", inputs)
        val doc = root.resolve("authored.tsv")
        Files.writeString(doc, "${CompilerAuthoredCallReader.HEADER}\ncollector\tjavac-constants\ncompiler\t${Runtime.version()}\n" +
            "token\t$token\nartifact\t${inputs.single { it.role == "processor" }.sha256}\nprofile\tordinary-body-v1\nunmapped\t0\n" +
            "source\t${b("src/Entry.java")}\t${CompilerEvidenceIndexer.sourceHash(source)}\n" +
            "caller\t${b(caller.value)}\t${b("src/Entry.java")}\t21\t69\tCOMPLETE\t2\t0\tNONE\n")
        val output = fp(classes, "classes", "classes")
        val witness = BuildWitness("fixture", "javac", ":compileJava", inputs, listOf(output), listOf(fp(doc, "compilerEvidence", "evidence")), token)
        val context = CompilerEvidenceContext(root, "fixture", SnapshotProvenance(listOf(output, fp(witnessFile, "witness", "witness")), listOf(witness)), external)
        return Fixture(source, classes, doc, sdk, context)
    }
}
