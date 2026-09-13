package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class ProvenanceVerifierTest {
    @Test
    fun `external aliases for the same compiler output preserve correspondence`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/A.java"), "class A {}")
        val classes = Files.createDirectories(root.resolve("classes"))
        Files.write(classes.resolve("A.class"), byteArrayOf(1, 2, 3))
        Files.writeString(root.resolve("build.gradle"), "plugins {}")
        Files.writeString(root.resolve("compiler.jar"), "compiler")
        Files.writeString(root.resolve("witness.json"), "unit fixture")
        fun fp(role: String, path: String) = ContentFingerprint.capture(root, root.resolve(path), role, role)
        val output = fp("classes", "classes").copy(path = "external/compileJava-classes")
        val selected = output.copy(path = "external/classes-0")
        val witness = BuildWitness("sample:main", "javac", "compileJava", listOf(fp("sources", "src"),
            fp("buildConfig", "build.gradle"), fp("compiler", "compiler.jar"),
            InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("-g")))), listOf(output))
        val provenance = SnapshotProvenance(listOf(selected, fp("witness", "witness.json")), listOf(witness))
        val bindings = mapOf(selected.path to classes, output.path to classes)
        assertEquals("matched", ProvenanceVerifier.verify(provenance, root, "sample:main", bindings).status)
        val missing = ProvenanceVerifier.verify(provenance, root, "sample:main", bindings - selected.path)
        assertEquals("unverified", missing.status)
        assertEquals(listOf("missing-external-input"), missing.reasons)
        val stamp = Files.getLastModifiedTime(classes.resolve("A.class"))
        Files.write(classes.resolve("A.class"), byteArrayOf(4, 5, 6))
        Files.setLastModifiedTime(classes.resolve("A.class"), stamp)
        assertEquals("stale", ProvenanceVerifier.verify(provenance, root, "sample:main", bindings).status)
        assertEquals("stale", ProvenanceVerifier.verify(provenance, root, "sample:main", bindings - selected.path).status)
    }

    @Test
    fun `correspondence needs matching witness contents sources outputs and identity`(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("src"))
        Files.writeString(source.resolve("A.java"), "class A {}")
        val classes = Files.createDirectories(root.resolve("classes"))
        Files.write(classes.resolve("A.class"), byteArrayOf(1, 2, 3))
        Files.writeString(root.resolve("build.gradle"), "plugins {}")
        Files.writeString(root.resolve("compiler.jar"), "compiler artifact")
        Files.writeString(root.resolve("witness.json"), "unit fixture record")
        fun fp(role: String, path: String) = ContentFingerprint.capture(root, root.resolve(path), role, role)
        val witness = BuildWitness("sample:main", "javac", "compileJava", listOf(fp("sources", "src"),
            fp("buildConfig", "build.gradle"), fp("compiler", "compiler.jar"),
            InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("-g")))), listOf(fp("classes", "classes")))
        val provenance = SnapshotProvenance(witness.outputs + fp("witness", "witness.json"), listOf(witness))
        fun status(value: SnapshotProvenance? = provenance, scope: String = "sample:main") = ProvenanceVerifier.verify(value, root, scope).status
        assertEquals("matched", status())
        val sourceAsConfig = provenance.copy(inputs = provenance.inputs + fp("buildConfig", "src"))
        Files.writeString(source.resolve("metadata.txt"), "configuration only")
        assertEquals("stale", status(sourceAsConfig))
        assertEquals("matched", status())
        Files.delete(source.resolve("metadata.txt"))
        // 아직 없는 test source set도 나중에 소스가 생기면 현재 snapshot을 stale로 만든다.
        val optionalSources = root.resolve("tests")
        val watched = provenance.copy(inputs = provenance.inputs +
            ContentFingerprint.capture(root, optionalSources, "source-watch", "test-sources"))
        assertEquals("matched", status(watched))
        Files.createDirectories(optionalSources)
        Files.writeString(optionalSources.resolve("NewTest.java"), "class NewTest {}")
        val changedWatch = ProvenanceVerifier.verify(watched, root, "sample:main")
        assertEquals("stale", changedWatch.status)
        assertEquals(listOf("changed-source-watch"), changedWatch.reasons)
        Files.delete(optionalSources.resolve("NewTest.java"))
        assertEquals("matched", status(watched))
        // 수집기 실행 증명이 아닌 문서/지문 경계 검증용 fixture다.
        val receiptFile = root.resolve("evidence.tsv")
        Files.writeString(receiptFile, "unit collector document")
        val receipt = fp("compilerEvidence", "evidence.tsv")
        val token = CompilerEvidenceToken.create(witness.scope, witness.compiler, witness.artifact, witness.inputs)
        val collected = witness.copy(compilerEvidence = listOf(receipt), evidenceToken = token)
        val enriched = provenance.copy(witnesses = listOf(collected))
        assertEquals("matched", status(enriched))
        assertEquals("unverified", status(enriched.copy(witnesses = listOf(collected.copy(evidenceToken = "f".repeat(64))))))
        Files.writeString(receiptFile, "changed collector document")
        assertEquals("stale", status(enriched))
        assertEquals("unverified", status(null))
        assertEquals("unverified", status(scope = "sample:test"))
        assertEquals("unverified", status(provenance.copy(witnesses = emptyList())))
        assertEquals("unverified", status(provenance.copy(inputs = witness.outputs)))
        Files.writeString(root.resolve("build.gradle"), "plugins { changed }")
        assertEquals("stale", status())
        Files.writeString(root.resolve("build.gradle"), "plugins {}")
        Files.delete(source.resolve("A.java"))
        assertEquals("stale", status())
    }
}
