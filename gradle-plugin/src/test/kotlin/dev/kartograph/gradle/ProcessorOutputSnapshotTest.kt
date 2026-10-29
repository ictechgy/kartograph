package dev.kartograph.gradle

import dev.kartograph.core.ProcessorOutputConfiguration
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.CompilerEvidenceIndexer
import dev.kartograph.index.ContentFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class ProcessorOutputSnapshotTest {
    @Test fun `attached output receipt survives configuration cache and stale output fails`(@TempDir root: Path) = verify(root, false)
    @Test fun `instrumented snapshot imports selected receipt and rejects changed bytes`(@TempDir root: Path) = verify(root, true)
    @Test fun `v3 compiler input changes invalidate snapshot through reused configuration cache`(@TempDir root: Path) = verify(root, false, true)
    @Test fun `instrumented snapshot imports v3 declared compiler inputs`(@TempDir root: Path) = verify(root, true, true)

    // 수동 receipt는 adapter 실패 경계의 단위 입력이다. 실제 compiler/cache는 collector integration suite가 검사한다.
    private fun verify(root: Path, instrumented: Boolean, compilerInputs: Boolean = false) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='processor-snapshot'")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph { snapshotsEnabled = true }
            afterEvaluate { tasks.named('kartographSnapshot') { processorOutputConfigs.from('processor-config.json') } }
        """.trimIndent())
        Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(root.resolve("src/main/java/Entry.java"), "public class Entry {}")
        Files.writeString(root.resolve("processor-input.txt"), "declared processor input")
        Files.writeString(root.resolve("collector.jar"), "unit collector artifact")
        Files.writeString(root.resolve("processor.jar"), "unit processor artifact")
        Files.createDirectories(root.resolve("generated"))
        Files.writeString(root.resolve("generated/proof.txt"), "resource")
        val config = ProcessorOutputConfiguration(root.toString(), "::jvm", "ksp", "fixture.Provider",
            root.resolve("collector.jar").toString(), root.resolve("processor.jar").toString(), listOf("processor-input.txt"),
            listOf("generated"), listOf("compiler"), "token", "raw.tsv", "receipt.json", if (compilerInputs) "compiler-inputs.tsv" else null)
        val collector = ContentFingerprint.hash(root.resolve("collector.jar"))
        val processor = ContentFingerprint.hash(root.resolve("processor.jar"))
        val source = ContentFingerprint.hash(root.resolve("processor-input.txt"))
        val contract = ContentFingerprint.values(config.contractValues())
        val token = ContentFingerprint.values(listOf(contract, "processor-input.txt", source, "external/collectorJar", collector, "external/processorJar", processor))
        val outputHash = CompilerEvidenceIndexer.sourceHash(root.resolve("generated/proof.txt"))
        fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        Files.writeString(root.resolve("raw.tsv"), "format\tkartograph-processor-outputs\t1\nkind\tksp\ntoken\t$token\n" +
            "processor\t${encode(config.processor)}\nprocessorArtifact\t$processor\ncollectorArtifact\t$collector\n" +
            "output\tresource\tapi\t${encode("generated/proof.txt")}\t$outputHash\n")
        val rawHash = CompilerEvidenceIndexer.sourceHash(root.resolve("raw.tsv"))
        val compilerMetadata = if (compilerInputs) {
            Files.writeString(root.resolve("compiler-only.input"), "compiler bytes")
            val hash = ContentFingerprint.hash(root.resolve("compiler-only.input"))
            Files.writeString(root.resolve("compiler-inputs.tsv"), "format\tkartograph-processor-compiler-inputs\t1\nscope\t::jvm\ntask\t:kspKotlin\ntoken\t$token\npropertiesSha256\t$source\n" +
                "file\t${encode("project/compiler-only.input")}\t${encode(root.resolve("compiler-only.input").toRealPath().toString())}\tfile\t$hash\n")
            val inventory = ContentFingerprint.values(listOf("gradle-declared-inputs-v1", "::jvm", ":kspKotlin", token, source, "1", "project/compiler-only.input", "file", hash))
            """, "compilerInputs":{"coverage":"gradle-declared-task-inputs","complete":false,"task":":kspKotlin","files":[{"role":"processorCompilerInput","path":"project/compiler-only.input","sha256":"$hash"}],"propertiesSha256":"$source","inventorySha256":"$inventory"}"""
        } else ""
        Files.writeString(root.resolve("processor-config.json"), """{
            "project":"$root","scope":"::jvm","kind":"ksp","processor":"fixture.Provider",
            "collectorJar":"${config.collectorJar}","processorJar":"${config.processorJar}",
            "inputs":["processor-input.txt"],"outputRoots":["generated"],"command":["compiler"],
            "token":"token","observations":"raw.tsv","receipt":"receipt.json"${if (compilerInputs) ",\"compilerInputs\":\"compiler-inputs.tsv\"" else ""}} """)
        Files.writeString(root.resolve("receipt.json"), """{
            "format":"kartograph-processor-output-witness","version":${if (compilerInputs) 3 else 2},"buildExit":0,"scope":"::jvm",
            "token":"$token","configurationSha256":"$contract",
            "inputs":[{"path":"processor-input.txt","sha256":"$source"},{"path":"external/collectorJar","sha256":"$collector"},{"path":"external/processorJar","sha256":"$processor"}],
            "observation":{"kind":"ksp","processor":"fixture.Provider","processorArtifact":"$processor","collectorArtifact":"$collector","rawSha256":"$rawHash",
            "outputs":[{"path":"generated/proof.txt","kind":"resource","observation":"api","sha256":"$outputHash"}]$compilerMetadata}}""")
        fun runner() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withDebug(instrumented)
            .withArguments(listOf("kartographSnapshot", "--offline", "--stacktrace") + if (instrumented) emptyList() else listOf("--configuration-cache"))
        runner().build()
        val path = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val snapshot = QuerySnapshotCodec.parse(Files.readString(path))
        assertEquals("generated/proof.txt", snapshot.processorOutputs.single().outputs.single().path)
        val again = runner().build()
        if (!instrumented) assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        if (compilerInputs) {
            assertEquals("project/compiler-only.input", snapshot.processorOutputs.single().compilerInputs!!.files.single().path)
            Files.writeString(root.resolve("compiler-only.input"), "changed compiler bytes")
            val stale = runner().buildAndFail()
            assertTrue(stale.output.contains("compiler task input bytes changed"), stale.output)
            Files.writeString(root.resolve("compiler-only.input"), "compiler bytes")
        }
        Files.writeString(root.resolve("generated/proof.txt"), "changed")
        val failed = runner().buildAndFail()
        assertTrue(failed.output.contains("processor output changed"), failed.output)
    }
}
