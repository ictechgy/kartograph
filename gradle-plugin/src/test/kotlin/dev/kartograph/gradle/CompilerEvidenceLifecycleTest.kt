package dev.kartograph.gradle

import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.CompilerEvidenceToken
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class CompilerEvidenceLifecycleTest {
    @Test
    fun `successful compile receipts survive cache and partial output invalidates them`(@TempDir root: Path) {
        fixture(root)
        assertEquals(TaskOutcome.SUCCESS, build(root).task(":compileJava")!!.outcome)
        val path = root.resolve("build/kartograph/witnesses/compileJava/witness.json")
        val completed = Files.readString(path)
        val witness = BuildWitnessCodec.parse(completed)
        assertTrue(CompilerEvidenceToken.matches(witness))
        assertEquals(1, witness.compilerEvidence.count { it.role == "compilerEvidence" })
        assertFalse(completed.contains(root.toString()))
        val again = build(root)
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileJava")!!.outcome)
        build(root, "clean")
        assertEquals(TaskOutcome.FROM_CACHE, build(root).task(":compileJava")!!.outcome)
        assertEquals(completed, Files.readString(path))
        val partial = build(root, "compileJava", "-Ppartial=true", fails = true)
        assertTrue(partial.output.contains("compiler evidence is partial"), partial.output)
        assertFalse(Files.exists(path))
    }

    // 실제 javac 뒤의 수집기 완료 훅을 모사해 lifecycle을 검사한다. 실제 collector 연동은 별도 검사한다.
    private fun fixture(root: Path) {
        val source = root.resolve("src/main/java/Entry.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "public class Entry {}")
        JarOutputStream(Files.newOutputStream(root.resolve("collector.jar"))).close()
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='evidence-fixture'\nbuildCache { local { directory=file('.fixture-cache') } }")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            def compiler = tasks.named('compileJava', JavaCompile)
            def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
            def evidenceDirectory = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
            def source = file('src/main/java/Entry.java')
            def collector = file('collector.jar')
            def partial = providers.gradleProperty('partial').isPresent()
            compiler.configure {
                options.annotationProcessorPath = files(collector)
                inputs.property('fixturePartial', partial)
                doLast {
                    def output = evidenceDirectory.get().file('references.tsv').asFile
                    output.parentFile.mkdirs()
                    def artifactDigest = java.security.MessageDigest.getInstance('SHA-256')
                    def rawArtifact = java.security.MessageDigest.getInstance('SHA-256').digest(collector.bytes).encodeHex().toString()
                    ['file', rawArtifact].each { part ->
                        def bytes = part.getBytes('UTF-8')
                        artifactDigest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
                        artifactDigest.update(bytes)
                    }
                    def artifact = artifactDigest.digest().encodeHex().toString()
                    def text = 'format\tkartograph-compiler-evidence\t1\ncollector\tjavac-constants\ncompiler\t17\n' +
                        'token\t' + token.get().asFile.text + '\nartifact\t' + artifact + '\nunmapped\t0\n'
                    if (!partial) {
                        def encoded = Base64.getUrlEncoder().withoutPadding().encodeToString('src/main/java/Entry.java'.getBytes('UTF-8'))
                        def hash = java.security.MessageDigest.getInstance('SHA-256').digest(source.bytes).encodeHex().toString()
                        text += 'source\t' + encoded + '\t' + hash + '\n'
                    }
                    output.text = text
                }
            }
            dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, compiler, 'sample:main',
                files('src/main/java'), files('build.gradle','settings.gradle'), files(), true)
        """.trimIndent())
    }

    private fun build(root: Path, vararg args: String, fails: Boolean = false): org.gradle.testkit.runner.BuildResult {
        val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(
            args.toList().ifEmpty { listOf("compileJava") } + listOf("--configuration-cache", "--build-cache", "--offline", "--stacktrace"))
        return if (fails) runner.buildAndFail() else runner.build()
    }
}
