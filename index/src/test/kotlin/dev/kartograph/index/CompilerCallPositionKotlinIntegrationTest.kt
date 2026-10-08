package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCallPositionKotlinIntegrationTest {
    @Test
    fun `packaged Kotlin collector receipt imports the exact LF selector`(@TempDir root: Path) {
        val collectorValue = System.getenv("KARTOGRAPH_COLLECTOR_JAR")
        val classpathValue = System.getenv("KARTOGRAPH_KOTLIN_CLASSPATH")
        val javaHomeValue = System.getenv("KARTOGRAPH_JDK17_HOME")
        assumeTrue(!collectorValue.isNullOrBlank() && !classpathValue.isNullOrBlank() && !javaHomeValue.isNullOrBlank(),
            "pinned collector, Kotlin 2.4.10 classpath and JDK 17 are required")
        val collector = Path.of(collectorValue).toRealPath()
        val compilerClasspath = classpathValue.split(File.pathSeparator).filter(String::isNotBlank).map { Path.of(it).toRealPath() }
        val javaHome = Path.of(javaHomeValue).toRealPath()
        assumeTrue(Files.isRegularFile(collector) && compilerClasspath.isNotEmpty() && compilerClasspath.all(Files::isRegularFile) &&
            Files.isRegularFile(javaHome.resolve("bin/java")), "pinned compiler inputs are unavailable")
        val stdlib = compilerClasspath.single { it.fileName.toString().startsWith("kotlin-stdlib-2.4.10") }

        val sourceDirectory = Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        val users = sourceDirectory.resolve("KotlinUsers.kt")
        val targets = sourceDirectory.resolve("KotlinTargets.kt")
        Files.writeString(users, KOTLIN_USERS)
        Files.writeString(targets, KOTLIN_TARGETS)
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        Files.writeString(root.resolve("witness.json"), "unit witness fixture")
        val classes = Files.createDirectories(root.resolve("classes"))
        val document = root.resolve("evidence.tsv")

        fun fp(role: String, path: Path, slot: String): InputFingerprint = ContentFingerprint.capture(root, path, role, slot)
        val collectorInput = fp("processor", collector, "collector")
        val compilerInputs = compilerClasspath.mapIndexed { index, path -> fp("compiler", path, "kotlin-compiler-$index") }
        val inputs = buildList {
            add(fp("sources", root.resolve("src"), "sources"))
            add(fp("buildConfig", root.resolve("build.gradle"), "build-config"))
            addAll(compilerInputs)
            add(collectorInput)
            add(InputFingerprint("options", "compileKotlin-options", ContentFingerprint.values(listOf(
                "-no-reflect", "-jvm-target", "17", "callPositions=true",
            ))))
            add(CompilerCallPositionOptions.enabledInput())
        }
        val token = CompilerEvidenceToken.create("sample:main", "kotlin", ":compileKotlin", inputs)
        val tokenFile = Files.writeString(root.resolve("token"), token)
        val command = listOf(
            javaHome.resolve("bin/java").toString(),
            "-cp", compilerClasspath.joinToString(File.pathSeparator),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-reflect",
            "-jvm-target", "17",
            "-jdk-home", javaHome.toString(),
            "-classpath", stdlib.toString(),
            "-Xplugin=$collector",
            "-P", "plugin:kartograph.compiler-evidence:root=$root",
            "-P", "plugin:kartograph.compiler-evidence:output=$document",
            "-P", "plugin:kartograph.compiler-evidence:token=$tokenFile",
            "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
            "-d", classes.toString(),
            users.toString(), targets.toString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val compilerOutput = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), compilerOutput)

        val envelope = CompilerEvidenceReader.readEnvelope(document)
        assertEquals("kotlin-constants", envelope.evidence.collector)
        assertEquals("2.4.10", envelope.evidence.compilerVersion)
        assertEquals(collectorInput.sha256, envelope.evidence.artifactSha256)
        val receipts = CompilerEvidenceReceipts.validate(
            root, "kotlin", token, inputs, listOf(document), setOf(users, targets),
            listOf(root.resolve("src")), emptyList(), "compileKotlin",
        )
        val output = fp("classes", classes, "classes")
        val witness = BuildWitness("sample:main", "kotlin", ":compileKotlin", inputs, listOf(output), receipts, token)
        val provenance = SnapshotProvenance(
            listOf(output, fp("witness", root.resolve("witness.json"), "witness")), listOf(witness),
        )
        val bindings = (compilerInputs.map { it.path }.zip(compilerClasspath) + listOf(collectorInput.path to collector)).toMap()
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        val enrichment = CompilerEvidenceIndexer.enrichWithCallPositions(
            indexed, listOf(classes), listOf(document), CompilerEvidenceContext(root, "sample:main", provenance, bindings),
        )

        assertEquals(indexed.graph.edges, enrichment.result.graph.edges.filter { it.origin != EdgeOrigin.COMPILER_REFERENCE })
        assertEquals(1, enrichment.result.graph.edges.count { it.origin == EdgeOrigin.COMPILER_REFERENCE })
        assertTrue(enrichment.result.graph.compilerCallPositionsCaptured)
        val caller = JvmNodeId.methodId("demo/KotlinUsersKt", "useKotlin", "()V")
        val target = JvmNodeId.methodId("demo/KotlinTargets", "direct", "()V")
        val position = enrichment.result.graph.locatedCompilerReferences.single { it.source == caller && it.target == target }
        assertEquals("src/main/kotlin/demo/KotlinUsers.kt", position.file.path)
        assertEquals(CompilerEvidenceIndexer.sourceHash(users), position.file.sha256)
        assertEquals("kotlin-constants", position.collector)
        assertEquals("2.4.10", position.compilerVersion)
        assertEquals(CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, position.coordinateBasis)
        assertEquals(52, position.offsetUtf16)
        assertEquals(58, position.endOffsetUtf16)
        assertEquals(4, position.line)
        assertEquals(21, position.column)
        assertFalse(position.generated)
        assertEquals(listOf(
            "compiler-call-positions-unmapped: 1",
            "compiler-call-positions-unmatched-bytecode: 1",
        ), enrichment.limitations)
    }

    private companion object {
        val KOTLIN_USERS: String = """
            package demo

            fun useKotlin() {
                KotlinTargets().direct()
            }

            fun external() {
                java.lang.String.valueOf(1)
            }

            fun ambiguous() {
                Ambiguous().same()
            }

            fun explicitDefaultParameter() {
                Ambiguous().same(1)
            }

            fun readConstant() = CALL_CONSTANT
        """.trimIndent() + "\n"

        val KOTLIN_TARGETS: String = """
            package demo

            class KotlinTargets {
                fun direct() {}
            }

            class Ambiguous {
                @JvmOverloads
                fun same(value: Int = 0) {}
            }

            const val CALL_CONSTANT: Int = 1
        """.trimIndent() + "\n"
    }
}
