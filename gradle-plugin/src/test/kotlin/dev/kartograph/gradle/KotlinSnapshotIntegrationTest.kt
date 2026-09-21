package dev.kartograph.gradle

import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class KotlinSnapshotIntegrationTest {
    @Test
    fun `Kotlin project dependency permits its Java no source directory`(@TempDir root: Path) {
        fixture(root)
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='kotlin-project-classpath'\ninclude 'support'\n")
        Files.createDirectories(root.resolve("support"))
        source(root, "support/src/main/kotlin/lib/Support.kt", "package lib; class Support { fun value() = 1 }")
        source(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry { fun value() = lib.Support().value() }")
        source(root, "src/test/kotlin/p/Check.kt", "package p; class Check { fun check() = Entry().value() }")
        val build = root.resolve("build.gradle")
        Files.writeString(build, Files.readString(build) + "\n" + """
            project(':support') {
                apply plugin: 'org.jetbrains.kotlin.jvm'
                repositories { mavenCentral() }
                kotlin { jvmToolchain(${Runtime.version().feature()}) }
            }
            dependencies { implementation project(':support') }
        """.trimIndent())
        val first = runner(root).build()
        assertEquals(TaskOutcome.NO_SOURCE, first.task(":support:compileJava")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":support:compileKotlin")!!.outcome)
        val snapshot = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        val again = runner(root).build()
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileKotlin")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileTestKotlin")!!.outcome)
    }

    @Test
    fun `unrecognized Kotlin task input preserves ordinary build but cannot produce verified snapshot`(@TempDir root: Path) {
        fixture(root)
        source(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry")
        java.util.jar.JarOutputStream(Files.newOutputStream(root.resolve("extra-metadata.jar"))).use { }
        val build = root.resolve("build.gradle")
        Files.writeString(build, Files.readString(build) + "\ntasks.named('compileKotlin') { inputs.file('extra-metadata.jar') }\n")
        val result = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("compileKotlin", "--configuration-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")!!.outcome)
        assertTrue(Files.isRegularFile(root.resolve("build/classes/kotlin/main/p/Entry.class")))
        assertFalse(Files.exists(root.resolve("build/kartograph/witnesses/compileKotlin/witness.json")))
        val snapshot = runner(root).buildAndFail()
        assertTrue(snapshot.output.contains("missing compiler witness for :compileKotlin"), snapshot.output)
    }

    @Test
    fun `mixed Kotlin and Java main test sources receive actual automatic compiler evidence`(@TempDir root: Path) {
        val again = mixedSnapshot(root, inProcess = false)
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
    }

    @Test
    fun `instrumented Kotlin build validates mixed producers and missing javac evidence`(@TempDir root: Path) {
        mixedSnapshot(root, inProcess = true)
    }

    private fun mixedSnapshot(root: Path, inProcess: Boolean): BuildResult {
        fixture(root)
        source(root, "src/main/java/p/Helper.java", "package p; public class Helper { public static int value() { return 1; } }")
        source(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry { fun value() = Helper.value() }")
        source(root, "src/test/java/p/JavaCheck.java", "package p; public class JavaCheck { public int check() { return new Entry().value(); } }")
        source(root, "src/test/kotlin/p/KotlinCheck.kt", "package p; class KotlinCheck { fun check() = Entry().value() }")
        fun build() = runner(root, inProcess = inProcess).build()
        val first = build()
        assertEquals(null, first.task(":test"))
        val snapshotFile = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val text = Files.readString(snapshotFile)
        val snapshot = QuerySnapshotCodec.parse(text)
        val provenance = requireNotNull(snapshot.provenance)
        assertEquals(4, provenance.witnesses.size)
        assertEquals(mapOf("javac" to 2, "kotlin" to 2), provenance.witnesses.groupingBy { it.compiler }.eachCount())
        for (name in listOf("Helper", "Entry", "JavaCheck", "KotlinCheck")) {
            assertTrue(NodeId("class:p/$name") in snapshot.graph.nodes, name)
        }
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(provenance, root, snapshot.scope, bindings).status)
        assertFalse(text.contains(root.toString()))
        val again = build()
        for (task in listOf("compileJava", "compileKotlin", "compileTestJava", "compileTestKotlin")) {
            assertEquals(TaskOutcome.UP_TO_DATE, again.task(":$task")!!.outcome, task)
        }
        assertEquals(text, Files.readString(snapshotFile))

        // Kotlin의 Java 분석 입력만으로 누락된 javac 산출물을 대신 증명할 수 없다.
        Files.delete(root.resolve("build/classes/java/main/p/Helper.class"))
        Files.delete(root.resolve("build/kartograph/witnesses/compileJava/witness.json"))
        // 계측 모드의 KGP는 -x 생산자 값을 먼저 거부한다. onlyIf도 Java 산출물을 만들지 않는 독립적인 실패 경로다.
        val skip = if (inProcess) arrayOf("-PskipJavac=true") else arrayOf("-x", "compileJava")
        val missingJava = runner(root, *skip, "--no-build-cache", inProcess = inProcess).buildAndFail()
        assertTrue(missingJava.output.contains("missing compiler witness for :compileJava"), missingJava.output)
        return again
    }

    @Test
    fun `pure Kotlin main and test allow Java no source outputs`(@TempDir root: Path) {
        fixture(root)
        source(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry { fun value() = 1 }")
        source(root, "src/test/kotlin/p/KotlinCheck.kt", "package p; class KotlinCheck { fun check() = Entry().value() }")
        val result = runner(root).build()
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":compileJava")!!.outcome)
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":compileTestJava")!!.outcome)
        val snapshot = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        assertEquals(listOf("kotlin", "kotlin"), snapshot.provenance!!.witnesses.map { it.compiler })
        assertTrue(NodeId("class:p/Entry") in snapshot.graph.nodes)
        assertTrue(NodeId("class:p/KotlinCheck") in snapshot.graph.nodes)
        val again = runner(root).build()
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileKotlin")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":compileTestKotlin")!!.outcome)

        Files.delete(root.resolve("src/test/kotlin/p/KotlinCheck.kt"))
        runner(root).build()
        val mainOnly = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        assertTrue(NodeId("class:p/Entry") in mainOnly.graph.nodes)
        assertFalse(NodeId("class:p/KotlinCheck") in mainOnly.graph.nodes)
        assertEquals(1, mainOnly.provenance!!.witnesses.size)
    }

    @Test
    fun `Kotlin snapshot requires an explicit intended toolchain`(@TempDir root: Path) {
        fixture(root)
        source(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry")
        val build = root.resolve("build.gradle")
        Files.writeString(build, Files.readString(build).replace("snapshotKotlinToolchain = javaToolchains.launcherFor(java.toolchain)", ""))
        val result = runner(root).buildAndFail()
        assertTrue(result.output.contains("Kotlin snapshots require snapshotKotlinToolchain"), result.output)
        assertFalse(Files.exists(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
    }

    private fun fixture(root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'kotlin-snapshot'\n")
        Files.writeString(root.resolve("build.gradle"), """
            buildscript {
                repositories { mavenCentral() }
                dependencies { classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10' }
            }
            plugins { id 'io.github.ictechgy.kartograph' }
            apply plugin: 'org.jetbrains.kotlin.jvm'
            repositories { mavenCentral() }
            kotlin { jvmToolchain(${Runtime.version().feature()}) }
            kartograph {
                snapshotsEnabled = true
                includeSourcePaths = true
                snapshotKotlinToolchain = javaToolchains.launcherFor(java.toolchain)
            }
            tasks.named('test') { doFirst { throw new GradleException('snapshot must not run tests') } }
            def skipJavac = providers.gradleProperty('skipJavac').map { it.toBoolean() }.orElse(false)
            tasks.named('compileJava') { onlyIf { !skipJavac.get() } }
        """.trimIndent())
    }

    private fun source(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun runner(root: Path, vararg arguments: String, inProcess: Boolean = false): GradleRunner =
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withDebug(inProcess)
            .withArguments(listOf("kartographSnapshot", "--stacktrace") + arguments +
                if (inProcess) emptyList() else listOf("--configuration-cache"))
}
