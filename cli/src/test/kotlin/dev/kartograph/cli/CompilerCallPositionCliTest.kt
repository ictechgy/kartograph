package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.CompilerCallPositionOptions
import dev.kartograph.index.CompilerEvidenceIndexer
import dev.kartograph.index.CompilerEvidenceReceipts
import dev.kartograph.index.CompilerEvidenceToken
import dev.kartograph.index.ContentFingerprint
import dev.kartograph.index.JvmNodeId
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCallPositionCliTest {
    @Test
    fun `actual javac receipt survives compact capture path relocation and saved query`(@TempDir root: Path) {
        val collectorValue = System.getenv("KARTOGRAPH_COLLECTOR_JAR")
        val oldHomeValue = System.getenv("KARTOGRAPH_019_HOME")
        assumeTrue(!collectorValue.isNullOrBlank() && !oldHomeValue.isNullOrBlank(),
            "packaged collector and actual 0.19.0 distribution are required")
        val collector = Path.of(collectorValue).toRealPath()
        assumeTrue(Files.isRegularFile(collector), "packaged collector is unavailable")
        val javac = Path.of(System.getProperty("java.home"), "bin", "javac").toRealPath()
        val sources = Files.createDirectories(root.resolve("src/demo"))
        val callerFile = sources.resolve("Caller.java")
        val targetFile = sources.resolve("Target.java")
        val callerText = "package demo; public class Caller { public void use() { Target.hit(); } }\n"
        Files.writeString(callerFile, callerText)
        Files.writeString(targetFile, "package demo; public class Target { public static void hit() {} }\n")
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        val classes = Files.createDirectories(root.resolve("classes"))
        fun fp(role: String, path: Path, slot: String): InputFingerprint = ContentFingerprint.capture(root, path, role, slot)
        val compilerInput = fp("compiler", javac, "javac")
        val collectorInput = fp("processor", collector, "collector")
        val inputs = listOf(
            fp("sources", root.resolve("src"), "sources"),
            fp("buildConfig", root.resolve("build.gradle"), "build-config"),
            compilerInput,
            collectorInput,
            InputFingerprint("options", "compileJava-options", ContentFingerprint.values(listOf("-g", "-proc:none"))),
            CompilerCallPositionOptions.enabledInput(),
        )
        val token = CompilerEvidenceToken.create("sample:main", "javac", ":compileJava", inputs)
        val tokenFile = Files.writeString(root.resolve("token"), token)
        val document = root.resolve("evidence.tsv")
        val plugin = "-Xplugin:KartographEvidence collector=javac-constants root=${root.toUri()} " +
            "output=${document.toUri()} token=${tokenFile.toUri()} callPositions=true"
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-proc:none", "-processorpath", collector.toString(), plugin,
            "-d", classes.toString(), callerFile.toString(), targetFile.toString(),
        ))
        val receipts = CompilerEvidenceReceipts.validate(
            root, "javac", token, inputs, listOf(document), setOf(callerFile, targetFile),
            listOf(root.resolve("src")), emptyList(), "compileJava",
        )
        val output = fp("classes", classes, "classes")
        val witness = BuildWitness("sample:main", "javac", ":compileJava", inputs, listOf(output), receipts, token)
        val witnessFile = Files.writeString(root.resolve("witness.json"), BuildWitnessCodec.render(witness))
        val capture = execute(
            "snapshot",
            "--classes", classes.toString(),
            "--project", root.toString(),
            "--scope", "sample:main",
            "--build-witness", witnessFile.toString(),
            "--compiler-evidence", document.toString(),
            "--source-root", "src",
            "--build-input", "build.gradle",
            "--input", "${compilerInput.path}=$javac",
            "--input", "${collectorInput.path}=$collector",
            "--include-paths",
            "--compact",
        )
        assertEquals(0, capture.status, capture.error)
        val snapshot = QuerySnapshotCodec.parse(capture.output)
        val original = ClassFileIndexer().indexWithObservations(listOf(classes)).graph
        assertEquals(original.edges, snapshot.graph.edges)
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        val caller = JvmNodeId.methodId("demo/Caller", "use", "()V")
        val target = JvmNodeId.methodId("demo/Target", "hit", "()V")
        val position = snapshot.graph.locatedCompilerReferences.single()
        assertEquals(caller, position.source)
        assertEquals(target, position.target)
        assertEquals("src/demo/Caller.java", position.file.path)
        assertEquals(CompilerEvidenceIndexer.sourceHash(callerFile), position.file.sha256)
        assertEquals(CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, position.coordinateBasis)
        assertEquals(callerText.indexOf("hit"), position.offsetUtf16)
        assertEquals(1, position.line)
        assertEquals(callerText.indexOf("hit") + 1, position.column)
        assertEquals("src/demo/Caller.java", snapshot.graph.node(caller)?.location?.path)
        assertContains(snapshot.limitations, "compiler-call-positions-unmapped: 2")

        val snapshotFile = Files.writeString(root.resolve("snapshot.json"), capture.output)
        val query = execute("query", target.value, "--graph-file", snapshotFile.toString())
        assertEquals(0, query.status, query.error)
        assertContains(query.output, "\"origin\": \"compiler\"")
        assertContains(query.output, "\"coordinateBasis\": \"javacUtf16CharSequence\"")
        assertContains(query.output, "\"sourceSha256\": \"${position.file.sha256}\"")
        assertFalse(query.output.contains("saved graph has no captured compiler selector positions"))

        val live = execute("query", target.value, "--classes", classes.toString(), "--project", root.toString())
        assertEquals(0, live.status, live.error)
        assertFalse(live.output.contains("\"origin\": \"compiler\""))
        assertFalse(live.output.contains("saved graph has no captured compiler selector positions"))
        val oldExecutable = Path.of(oldHomeValue).toRealPath().resolve("bin/kartograph")
        val old = ProcessBuilder(
            "/bin/bash", oldExecutable.toString(), "query", target.value,
            "--classes", classes.toString(), "--project", root.toString(),
        ).redirectErrorStream(true).start()
        val oldOutput = old.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, old.waitFor(), oldOutput)
        assertEquals(oldOutput, live.output)
    }

    @Test
    fun `actual Kotlin receipt survives compact capture and saved query`(@TempDir root: Path) {
        val collectorValue = System.getenv("KARTOGRAPH_COLLECTOR_JAR")
        val classpathValue = System.getenv("KARTOGRAPH_KOTLIN_CLASSPATH")
        val javaHomeValue = System.getenv("KARTOGRAPH_JDK17_HOME")
        assumeTrue(!collectorValue.isNullOrBlank() && !classpathValue.isNullOrBlank() && !javaHomeValue.isNullOrBlank(),
            "packaged collector, Kotlin 2.4.10 classpath and JDK 17 are required")
        val collector = Path.of(collectorValue).toRealPath()
        val compilerClasspath = classpathValue.split(File.pathSeparator).filter(String::isNotBlank).map { Path.of(it).toRealPath() }
        val javaHome = Path.of(javaHomeValue).toRealPath()
        assumeTrue(Files.isRegularFile(collector) && compilerClasspath.isNotEmpty() && compilerClasspath.all(Files::isRegularFile))
        val stdlib = compilerClasspath.single { it.fileName.toString().startsWith("kotlin-stdlib-2.4.10") }
        val sources = Files.createDirectories(root.resolve("src/main/kotlin/demo"))
        val users = Files.writeString(sources.resolve("KotlinUsers.kt"), KOTLIN_USERS)
        val targets = Files.writeString(sources.resolve("KotlinTargets.kt"), KOTLIN_TARGETS)
        Files.writeString(root.resolve("build.gradle"), "unit configuration")
        val classes = Files.createDirectories(root.resolve("classes"))
        val document = root.resolve("evidence.tsv")
        fun fp(role: String, path: Path, slot: String): InputFingerprint = ContentFingerprint.capture(root, path, role, slot)
        val compilerInputs = compilerClasspath.mapIndexed { index, path -> fp("compiler", path, "kotlin-compiler-$index") }
        val collectorInput = fp("processor", collector, "collector")
        val inputs = buildList {
            add(fp("sources", root.resolve("src"), "sources"))
            add(fp("buildConfig", root.resolve("build.gradle"), "build-config"))
            addAll(compilerInputs)
            add(collectorInput)
            add(InputFingerprint("options", "compileKotlin-options", ContentFingerprint.values(listOf("callPositions=true"))))
            add(CompilerCallPositionOptions.enabledInput())
        }
        val token = CompilerEvidenceToken.create("sample:main", "kotlin", ":compileKotlin", inputs)
        val tokenFile = Files.writeString(root.resolve("token"), token)
        val command = listOf(
            javaHome.resolve("bin/java").toString(), "-cp", compilerClasspath.joinToString(File.pathSeparator),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
            "-jdk-home", javaHome.toString(), "-classpath", stdlib.toString(), "-Xplugin=$collector",
            "-P", "plugin:kartograph.compiler-evidence:root=$root",
            "-P", "plugin:kartograph.compiler-evidence:output=$document",
            "-P", "plugin:kartograph.compiler-evidence:token=$tokenFile",
            "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
            "-d", classes.toString(), users.toString(), targets.toString(),
        )
        val compiler = ProcessBuilder(command).redirectErrorStream(true).start()
        val compilerOutput = compiler.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, compiler.waitFor(), compilerOutput)
        val receipts = CompilerEvidenceReceipts.validate(
            root, "kotlin", token, inputs, listOf(document), setOf(users, targets),
            listOf(root.resolve("src")), emptyList(), "compileKotlin",
        )
        val output = fp("classes", classes, "classes")
        val witness = BuildWitness("sample:main", "kotlin", ":compileKotlin", inputs, listOf(output), receipts, token)
        val witnessFile = Files.writeString(root.resolve("witness.json"), BuildWitnessCodec.render(witness))
        val bindings = compilerInputs.map { it.path }.zip(compilerClasspath) + listOf(collectorInput.path to collector)
        val arguments = buildList {
            addAll(listOf("snapshot", "--classes", classes.toString(), "--project", root.toString(),
                "--scope", "sample:main", "--build-witness", witnessFile.toString(),
                "--compiler-evidence", document.toString(), "--source-root", "src", "--build-input", "build.gradle",
                "--include-paths", "--compact"))
            bindings.forEach { (slot, path) -> addAll(listOf("--input", "$slot=$path")) }
        }
        val capture = execute(*arguments.toTypedArray())
        assertEquals(0, capture.status, capture.error)
        val snapshot = QuerySnapshotCodec.parse(capture.output)
        val original = ClassFileIndexer().indexWithObservations(listOf(classes)).graph
        assertEquals(original.edges, snapshot.graph.edges.filter { it.origin != EdgeOrigin.COMPILER_REFERENCE })
        assertTrue(snapshot.graph.compilerCallPositionsCaptured)
        val caller = JvmNodeId.methodId("demo/KotlinUsersKt", "useKotlin", "()V")
        val target = JvmNodeId.methodId("demo/KotlinTargets", "direct", "()V")
        val position = snapshot.graph.locatedCompilerReferences.single { it.source == caller && it.target == target }
        assertEquals("src/main/kotlin/demo/KotlinUsers.kt", position.file.path)
        assertEquals(CompilerEvidenceIndexer.sourceHash(users), position.file.sha256)
        assertEquals(CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, position.coordinateBasis)
        assertEquals(52, position.offsetUtf16)
        assertEquals(58, position.endOffsetUtf16)
        assertEquals(4, position.line)
        assertEquals(21, position.column)
        assertContains(snapshot.limitations, "compiler-call-positions-unmapped: 1")
        assertContains(snapshot.limitations, "compiler-call-positions-unmatched-bytecode: 1")

        val file = Files.writeString(root.resolve("snapshot.json"), capture.output)
        val query = execute("query", target.value, "--graph-file", file.toString())
        assertEquals(0, query.status, query.error)
        assertContains(query.output, "\"coordinateBasis\": \"kotlinUtf16NormalizedSource\"")
        assertContains(query.output, "\"compilerVersion\": \"2.4.10\"")
    }

    private fun execute(vararg args: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(output), PrintStream(error))
        return Result(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private data class Result(val status: Int, val output: String, val error: String)

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
