package dev.kartograph.gradle

import dev.kartograph.core.*
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.*
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class CompilerAuthoredCallLifecycleTest {
    @Test fun `real javac coverage requires successful receipt including cache and failure lifecycle`(@TempDir root: Path) {
        val configured = System.getenv("KARTOGRAPH_COLLECTOR_JAR")
        assumeTrue(!configured.isNullOrBlank(), "packaged compiler collector is required")
        val collector = Path.of(configured).toRealPath()
        val sdk = Path.of(System.getProperty("java.home")).toRealPath()
        val source = Files.createDirectories(root.resolve("src/main/java/demo")).resolve("Calls.java")
        Files.writeString(source, "package demo; public class Calls { public static String zero() { return \"literal\"; } public static String explicit(Object value) { return value.toString(); } }")
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='authored-java'\nbuildCache { local { directory=file('.fixture-cache') } }")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            def compiler = tasks.named('compileJava', JavaCompile)
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def output = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
            compiler.configure {
                options.encoding = 'UTF-8'
                options.annotationProcessorPath = files('${q(collector)}')
                options.compilerArgs.add('-proc:none')
                options.compilerArgs.add('-Xplugin:KartographEvidence root=${root.toUri()}' +
                    ' output=' + output.get().asFile.toPath().toUri().toASCIIString() +
                    ' token=' + token.get().asFile.toPath().toUri().toASCIIString() + ' authoredCalls=true')
            }
            dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, compiler, 'sample:main',
                files('src/main/java'), files('build.gradle','settings.gradle'),
                files('${q(sdk.resolve("jmods"))}', '${q(sdk.resolve("release"))}'), true)
        """.trimIndent())
        fun run(vararg arguments: String, fails: Boolean = false): org.gradle.testkit.runner.BuildResult {
            val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(arguments.toList() +
                listOf("--offline", "--configuration-cache", "--build-cache", "--stacktrace"))
            return if (fails) runner.buildAndFail() else runner.build()
        }
        val witnessPath = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        val classes = root.resolve("build/classes/java/main")
        fun load(): CompilerEvidenceEnrichment {
            val witness = BuildWitnessCodec.parse(Files.readString(witnessPath))
            assertTrue(CompilerAuthoredCallOptions.isEnabled(witness.inputs))
            val candidates = listOf(collector, sdk.resolve("lib/modules"), sdk.resolve("jmods"), sdk.resolve("release"))
            val external = witness.inputs.filter { it.path.startsWith("external/") }.associate { input ->
                input.path to candidates.single { ContentFingerprint.hashInput(it, input.role) == input.sha256 }
            }
            val proof = SnapshotProvenance(witness.outputs + ContentFingerprint.capture(root, witnessPath, "witness", "compileJava"), listOf(witness))
            val context = CompilerEvidenceContext(root, "sample:main", proof, external)
            val docs = witness.compilerEvidence.filter { it.role == "compilerEvidence" }.map { root.resolve(it.path) }
            assertEquals(2, docs.size)
            return CompilerEvidenceIndexer.indexWithAuthoredCalls(listOf(classes), emptyList(), sdk, docs, context)
        }
        assertEquals(TaskOutcome.SUCCESS, run("compileJava").task(":compileJava")?.outcome)
        val recorded = Files.readString(witnessPath)
        val first = load()
        val zero = first.result.graph.compilerAuthoredCallsFrom(NodeId("method:demo/Calls#zero()Ljava/lang/String;"))!!
        assertEquals(AuthoredCallCoverage.COMPLETE, zero.coverage)
        assertTrue(zero.calls.isEmpty())
        val explicit = first.result.graph.compilerAuthoredCallsFrom(NodeId("method:demo/Calls#explicit(Ljava/lang/Object;)Ljava/lang/String;"))!!
        assertEquals(AuthoredCallCoverage.COMPLETE, explicit.coverage)
        assertEquals(listOf(NodeId("method:java/lang/Object#toString()Ljava/lang/String;")), explicit.calls.map { it.target })
        assertEquals(AuthoredCallOrigin.BINARY, explicit.calls.single().origin)
        assertEquals(TaskOutcome.UP_TO_DATE, run("compileJava").task(":compileJava")?.outcome)
        run("clean")
        assertEquals(TaskOutcome.FROM_CACHE, run("compileJava").task(":compileJava")?.outcome)
        assertEquals(recorded, Files.readString(witnessPath))
        assertEquals(first.result.graph.compilerAuthoredCallers, load().result.graph.compilerAuthoredCallers)
        Files.writeString(source, Files.readString(source) + "\n// source-only edit\n")
        assertFailsWith<IllegalArgumentException> { load() }
        assertEquals(TaskOutcome.SUCCESS, run("compileJava").task(":compileJava")?.outcome)
        load()
        Files.writeString(source, "package demo; public class Calls { unknown broken; }")
        run("compileJava", fails = true)
        assertFalse(Files.exists(witnessPath))
    }
    private fun q(path: Path): String = path.toString().replace("\\", "\\\\").replace("'", "\\'")
}
