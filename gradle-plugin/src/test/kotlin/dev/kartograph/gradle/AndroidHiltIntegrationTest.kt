package dev.kartograph.gradle

import dev.kartograph.analysis.InputHints
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.InputHint
import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ProvenanceVerifier
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.spi.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.io.TempDir

/**
 * 실제 Hilt 2.60.1 processor/plugin과 AGP 9.3.2의 Java Application/Service 변환을 검사한다.
 * javac와 PROJECT artifact provider가 알려 준 입력만 비교한다. Kotlin/kapt/KSP, 앱 실행,
 * 모든 Hilt 생성물의 도달성 또는 사내 앱 정확도를 검증하는 fixture는 아니다.
 */
class AndroidHiltIntegrationTest {
    @Test
    fun `actual Hilt transform preserves distinct compiler and variant inputs`(@TempDir root: Path) {
        fixture(root)
        val built = runner(root, "captureHiltInputs").build()
        for (task in listOf("compileDebugJavaWithJavac", "kartographGraphDebug", "kartographDeadDebug", "captureHiltInputs")) {
            assertEquals(TaskOutcome.SUCCESS, built.task(":$task")?.outcome, task)
        }
        assertEquals(null, built.task(":testDebugUnitTest"))

        val inputs = document(root.resolve("build/reports/hilt-inputs.json"))
        val raw = paths(inputs, "raw")
        val graphRoots = paths(inputs, "graph")
        val deadRoots = paths(inputs, "dead")
        assertEquals(graphRoots.toSet(), deadRoots.toSet(), "graph and dead must consume the same selected variant artifacts")
        assertTrue(raw.isNotEmpty())
        assertTrue(graphRoots.isNotEmpty())
        val before = ClassFileIndexer(callbackFacts = false).index(raw)
        val after = ClassFileIndexer(callbackFacts = false).index(graphRoots)
        for ((name, originalParent) in mapOf("App" to "android/app/Application", "ProbeService" to "android/app/Service")) {
            val id = NodeId("class:p/$name")
            val generated = "p/Hilt_$name"
            val rawBytes = classBytes(raw, "p/$name")
            val transformedBytes = classBytes(graphRoots, "p/$name")
            // JDK javap가 읽은 실제 superclass를 엔진의 정점 신원과 별개로 대조한다.
            assertEquals(originalParent, superName(root, "before-$name", rawBytes))
            assertEquals(generated, superName(root, "after-$name", transformedBytes))
            assertNotEquals(digest(rawBytes), digest(transformedBytes))
            assertTrue(originalParent in before.nodes.getValue(id).supertypes)
            assertFalse(generated in before.nodes.getValue(id).supertypes)
            assertTrue(generated in after.nodes.getValue(id).supertypes)
            assertTrue(after.nodes.getValue(NodeId("class:$generated")).synthesized)
            println("hilt-native class=p/$name beforeParent=$originalParent afterParent=$generated " +
                "beforeSha256=${digest(rawBytes)} afterSha256=${digest(transformedBytes)}")
        }
        assertTrue(InputHint.HILT_TRANSFORM_NOT_OBSERVED in InputHints.detect(0, 0, 0, before))
        assertFalse(InputHint.HILT_TRANSFORM_NOT_OBSERVED in InputHints.detect(0, 0, 0, after))
        assertFalse(before.nodes.getValue(unrelated).synthesized)
        assertFalse(after.nodes.getValue(unrelated).synthesized, "a handwritten Hilt_ prefix is not generated evidence")
        val manifestRoots = AndroidManifestScanner(root).scan(root.resolve("src/main/AndroidManifest.xml"), "p")
        assertTrue(manifestRoots.any { it.nodeId == service && it.location != null })
        val reached = ReachabilityAnalyzer.analyze(after, manifestRoots)
        assertTrue(NodeId("class:p/Hilt_ProbeService") in reached.reachableNodeIds)
        assertFalse(unrelated in reached.reachableNodeIds)

        val exported = document(root.resolve("build/reports/kartograph/debug-graph.json"))
        val nodes = rows(exported, "nodes").associateBy { it["usr"] }
        for (name in listOf("App", "ProbeService", "Hilt_App", "Hilt_ProbeService", "Hilt_Unrelated")) {
            assertTrue("class:p/$name" in nodes, name)
        }
        assertEquals(false, nodes.getValue(unrelated.value)["synthesized"])
        assertEquals(true, nodes.getValue("class:p/Hilt_ProbeService")["synthesized"])
        assertTrue(rows(exported, "edges").any {
            it["source"] == service.value && it["target"] == "class:p/Hilt_ProbeService" && it["kind"] == "inheritance"
        }, "the shipped graph task must export the transformed superclass edge")
        val source = nodes.getValue(service.value)["location"] as Map<*, *>
        assertEquals("src/main/java/p/ProbeService.java", source["path"])
        assertEquals("projectRelative", source["pathKind"])

        val dead = document(root.resolve("build/reports/kartograph/debug.txt"))
        assertFalse(rows(dead, "inputHints").any { it["id"] == InputHint.HILT_TRANSFORM_NOT_OBSERVED.id })
        assertTrue(rows(dead, "diagnostics").any { it["nodeId"] == unrelated.value && it["state"] == "unreachable" })
        assertFalse(rows(dead, "diagnostics").any { it["nodeId"] == service.value })
        assertFalse((dead["limitations"] as List<*>).isEmpty(), "native compilation does not remove analysis limitations")

        assertSnapshotBoundary(root, raw, graphRoots)
    }

    /** 변환 witness 미지원 시 원래 javac와 변환 artifact를 섞지 않고 구체적인 입력 불일치로 거부한다. */
    private fun assertSnapshotBoundary(root: Path, raw: List<Path>, graphRoots: List<Path>) {
        val snapshotFile = root.resolve("build/reports/kartograph/debug-snapshot.json")
        assertFalse(Files.exists(snapshotFile))
        val failure = try {
            runner(root, "kartographSnapshotDebug").build()
            null
        } catch (error: UnexpectedBuildFailure) {
            error.buildResult
        }
        if (failure != null) {
            assertEquals(TaskOutcome.FAILED, failure.task(":kartographSnapshotDebug")?.outcome)
            assertTrue(failure.output.contains("missing compiler output for :compileDebugJavaWithJavac"), failure.output)
            assertFalse(Files.exists(snapshotFile), "a rejected transform capture must not publish a usable snapshot")
            println("hilt-native snapshot=refused reason=compiler-output-not-in-selected-variant-artifacts")
            return
        }
        // 향후 별도 transform witness가 지원되어도 원래 javac 그래프로 조용히 돌아가는 성공은 허용하지 않는다.
        val text = Files.readString(snapshotFile)
        val snapshot = QuerySnapshotCodec.parse(text)
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/debug-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        val provenance = requireNotNull(snapshot.provenance)
        assertEquals("matched", ProvenanceVerifier.verify(provenance, root, snapshot.scope, bindings).status)
        fun recordedPath(path: String) = (bindings[path] ?: root.resolve(path)).toRealPath()
        val capturedRoots = provenance.inputs.filter { it.role == "classes" }.map { recordedPath(it.path) }.toSet()
        assertEquals(graphRoots.filter { Files.exists(it) }.map { it.toRealPath() }.toSet(), capturedRoots,
            "capture must fingerprint the exact selected variant roots, not silently substitute raw javac outputs")
        val javacOutputs = provenance.witnesses.filter { it.compiler == "javac" }.flatMap { it.outputs }
            .filter { it.role == "classes" }.map { recordedPath(it.path) }.toSet()
        assertTrue(javacOutputs.containsAll(raw.map { it.toRealPath() }), "the original compiler output witness must retain its identity")
        assertTrue("p/Hilt_ProbeService" in snapshot.graph.nodes.getValue(service).supertypes)
        assertFalse(InputHint.HILT_TRANSFORM_NOT_OBSERVED in InputHints.detect(0, 0, 0, snapshot.graph))
        assertFalse(text.contains(root.toString()))
        println("hilt-native snapshot=matched transformed-superclass-preserved=true")
    }

    private fun fixture(root: Path) {
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar"))
            .replace("\\", "\\\\").replace("'", "\\'")
        write(root, "settings.gradle", "rootProject.name = 'android-hilt-transform-feedback'\n")
        write(root, "gradle.properties", "android.useAndroidX=true\n")
        write(root, "build.gradle", """
            import groovy.json.JsonOutput
            import org.gradle.api.tasks.*
            import org.gradle.api.file.*

            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:9.3.2'
                    classpath 'com.google.dagger:hilt-android-gradle-plugin:2.60.1'
                    classpath files('$pluginJar')
                }
            }
            apply plugin: 'com.android.application'
            apply plugin: 'com.google.dagger.hilt.android'
            apply plugin: 'io.github.ictechgy.kartograph'
            repositories { google(); mavenCentral() }
            dependencies {
                implementation 'com.google.dagger:hilt-android:2.60.1'
                annotationProcessor 'com.google.dagger:hilt-compiler:2.60.1'
            }
            android {
                namespace 'p'
                compileSdk 36
                defaultConfig { applicationId 'p.hiltprobe'; minSdk 23; targetSdk 36; versionCode 1; versionName '1' }
                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }
            }
            kotlin {
                jvmToolchain(${Runtime.version().feature()})
                compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
            }
            kartograph {
                snapshotsEnabled = true
                includeSourcePaths = true
                includePrivateMembers = false
                reportFormat = 'json'
                snapshotKotlinToolchain = javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()})
                }
            }
            tasks.withType(Test).configureEach { doFirst { throw new GradleException('capture must not run app tests') } }

            abstract class CaptureHiltInputs extends DefaultTask {
                @Classpath abstract ConfigurableFileCollection getRawRoots()
                @Classpath abstract ConfigurableFileCollection getGraphRoots()
                @Classpath abstract ConfigurableFileCollection getDeadRoots()
                @OutputFile abstract RegularFileProperty getEvidenceFile()
                @TaskAction void capture() {
                    def target = evidenceFile.get().asFile
                    target.parentFile.mkdirs()
                    target.setText(JsonOutput.toJson([
                        raw: rawRoots.files.collect { it.canonicalPath }.sort(),
                        graph: graphRoots.files.collect { it.canonicalPath }.sort(),
                        dead: deadRoots.files.collect { it.canonicalPath }.sort()
                    ]), 'UTF-8')
                }
            }
            def probe = tasks.register('captureHiltInputs', CaptureHiltInputs) {
                evidenceFile.set(layout.buildDirectory.file('reports/hilt-inputs.json'))
            }
            androidComponents.onVariants(androidComponents.selector().withBuildType('debug')) { variant ->
                // AGP 9 공개 compiler callback이며 임시 javac/ASM 출력 경로를 만들지 않는다.
                variant.configureJavaCompileTask { compiler ->
                    probe.configure { rawRoots.from(compiler.destinationDirectory); dependsOn(compiler) }
                }
                def graphTask = tasks.named('kartographGraphDebug')
                def deadTask = tasks.named('kartographDeadDebug')
                probe.configure {
                    dependsOn(graphTask, deadTask)
                    graphRoots.from(graphTask.flatMap { it.projectJars }, graphTask.flatMap { it.projectDirectories })
                    deadRoots.from(deadTask.flatMap { it.projectJars }, deadTask.flatMap { it.projectDirectories })
                }
            }
        """.trimIndent())
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString()
        require(Files.isDirectory(Path.of(sdk))) { "Hilt integration requires an installed Android SDK" }
        write(root, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(root, "src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:name="p.App" android:label="@string/app_name">
                    <service android:name="p.ProbeService" android:exported="false" />
                </application>
            </manifest>
        """.trimIndent())
        write(root, "src/main/res/values/strings.xml", "<resources><string name=\"app_name\">Hilt fixture</string></resources>")
        write(root, "src/main/java/p/App.java", """
            package p;
            @dagger.hilt.android.HiltAndroidApp
            public final class App extends android.app.Application {}
        """.trimIndent())
        write(root, "src/main/java/p/ProbeService.java", """
            package p;
            @dagger.hilt.android.AndroidEntryPoint
            public final class ProbeService extends android.app.Service {
                @javax.inject.Inject Dependency dependency;
                @Override public void onCreate() { super.onCreate(); dependency.value(); }
                @Override public android.os.IBinder onBind(android.content.Intent intent) { return null; }
            }
        """.trimIndent())
        write(root, "src/main/java/p/Dependency.java", """
            package p;
            public final class Dependency {
                @javax.inject.Inject public Dependency() {}
                public int value() { return 7; }
            }
        """.trimIndent())
        write(root, "src/main/java/p/Hilt_Unrelated.java", "package p; public final class Hilt_Unrelated {}")
    }

    private fun runner(root: Path, vararg tasks: String) = GradleRunner.create().withProjectDir(root.toFile())
        .withArguments(tasks.toList() + listOf("--stacktrace", "--configuration-cache"))

    private fun document(path: Path) = McpJsonCodec.parse(Files.readString(path)) as Map<*, *>
    private fun rows(document: Map<*, *>, field: String) = (document[field] as List<*>).map { it as Map<*, *> }
    private fun paths(document: Map<*, *>, field: String) = (document[field] as List<*>).map { Path.of(it as String) }

    private fun classBytes(roots: List<Path>, name: String): ByteArray {
        val found = roots.mapNotNull { root ->
            if (Files.isDirectory(root)) root.resolve("$name.class").takeIf { Files.isRegularFile(it) }?.let(Files::readAllBytes)
            else JarFile(root.toFile()).use { jar -> jar.getJarEntry("$name.class")?.let { jar.getInputStream(it).use { input -> input.readBytes() } } }
        }
        assertEquals(1, found.size, "expected one selected definition of $name; raw and transformed roots must not be merged")
        return found.single()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun superName(root: Path, label: String, bytes: ByteArray): String {
        val file = root.resolve("build/reports/hilt-bytecode/$label.class")
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        val javap = ToolProvider.findFirst("javap").orElseThrow { IllegalStateException("Hilt integration requires the JDK javap tool") }
        val output = StringWriter()
        val errors = StringWriter()
        val exit = javap.run(PrintWriter(output), PrintWriter(errors), "-verbose", file.toString())
        assertEquals(0, exit, errors.toString())
        return requireNotNull(Regex("(?m)^\\s*super_class:\\s+#\\d+\\s+//\\s+(\\S+)\\s*$").find(output.toString())) {
            "javap did not report the expected classfile superclass for $label"
        }.groupValues[1]
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        val service = NodeId("class:p/ProbeService")
        val unrelated = NodeId("class:p/Hilt_Unrelated")
    }
}
