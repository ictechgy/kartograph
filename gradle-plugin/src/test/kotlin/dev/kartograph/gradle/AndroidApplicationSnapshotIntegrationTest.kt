package dev.kartograph.gradle

import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * AGP 최대 지원 조합(9.3.2, 저장소 wrapper)에서 Android **application** variant의 자동 캡처를 검증한다.
 *
 * library 검증([AndroidSnapshotIntegrationTest])과 다른 점은 `process<Variant>Resources`가 만드는 `R.jar`다.
 * application에서는 R.jar가 PROJECT class root에 들어오므로 resource producer witness(`agp-process-resources`)가
 * 이를 덮어야 snapshot이 `matched`가 되고, R.jar 내용이 바뀌면 `stale`로 거부해야 한다.
 * 최소 조합(AGP 8.7.3/Gradle 8.10.2)은 `Scripts/verify-agp-8-app-snapshot.sh`가 같은 계약을 검사한다.
 */
class AndroidApplicationSnapshotIntegrationTest {
    @Test
    fun `Android application captures R jar through the resource producer witness`(@TempDir root: Path) {
        val again = applicationSnapshot(root, inProcess = false)
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
    }

    @Test
    fun `instrumented Android application build refuses a tampered R jar and recovers after regeneration`(@TempDir root: Path) {
        applicationSnapshot(root, inProcess = true)
    }

    /** application 프로젝트를 만들고 캡처·재사용·R.jar 변조 거부·회복을 순서대로 검사한 뒤 두 번째 빌드 결과를 돌려준다. */
    private fun applicationSnapshot(root: Path, inProcess: Boolean): BuildResult {
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar"))
            .replace("\\", "\\\\").replace("'", "\\'")
        write(root, "settings.gradle", "rootProject.name = 'android-application-snapshot'\n")
        write(root, "build.gradle", """
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:9.3.2'
                    classpath files('$pluginJar')
                }
            }
            apply plugin: 'com.android.application'
            apply plugin: 'io.github.ictechgy.kartograph'
            repositories { google(); mavenCentral() }
            android {
                namespace 'p.app'
                compileSdk 36
                defaultConfig { applicationId 'p.app'; minSdk 23; targetSdk 36; versionCode 1; versionName '1' }
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
                snapshotKotlinToolchain = javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()})
                }
            }
            tasks.withType(Test).configureEach { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString()
        require(Files.isDirectory(Path.of(sdk))) { "Android application snapshot integration requires an installed Android SDK" }
        write(root, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(root, "src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="@string/app_name"><activity android:name="p.MainActivity" android:exported="false" /></application></manifest>""")
        // 실제 리소스를 두어 processDebugResources가 비어 있지 않은 R.jar를 만들게 한다.
        write(root, "src/main/res/values/strings.xml", """<resources><string name="app_name">probe</string></resources>""")
        write(root, "src/main/java/p/MainActivity.java", "package p; public class MainActivity extends android.app.Activity { public int value() { return 1; } }")
        write(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry { fun value() = MainActivity().value() }")
        write(root, "src/test/kotlin/p/KotlinCheck.kt", "package p; class KotlinCheck { fun check() = Entry().value() }")
        fun build() = GradleRunner.create().withProjectDir(root.toFile())
            .withDebug(inProcess)
            .withArguments(listOf("kartographSnapshotDebug", "--stacktrace") +
                if (inProcess) emptyList() else listOf("--configuration-cache")).build()

        val result = build()
        assertEquals(null, result.task(":testDebugUnitTest"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":processDebugResources")!!.outcome)
        val file = root.resolve("build/reports/kartograph/debug-snapshot.json")
        val text = Files.readString(file)
        val snapshot = QuerySnapshotCodec.parse(text)
        for (name in listOf("MainActivity", "Entry", "KotlinCheck")) {
            assertTrue(NodeId("class:p/$name") in snapshot.graph.nodes, name)
        }
        assertTrue(snapshot.retention.any { it.nodeId == NodeId("class:p/MainActivity") && it.reason == RetentionReason.MANIFEST_COMPONENT })

        // compiler witness 2종에 더해 processDebugResources의 resource producer witness가 R.jar를 classes 출력으로 덮는다.
        val witnesses = snapshot.provenance!!.witnesses
        assertEquals(mapOf("agp-process-resources" to 1, "javac" to 1, "kotlin" to 2), witnesses.groupingBy { it.compiler }.eachCount())
        val resourceWitness = witnesses.single { it.compiler == "agp-process-resources" }
        assertEquals(":processDebugResources", resourceWitness.artifact)
        val rJarOutput = resourceWitness.outputs.single()
        assertEquals("classes", rJarOutput.role)
        assertTrue(rJarOutput.path.endsWith("R.jar"), rJarOutput.path)
        val rJar = root.resolve(rJarOutput.path)
        assertTrue(Files.isRegularFile(rJar), rJar.toString())
        assertTrue(text.contains("R.jar"), "snapshot must record the R.jar class root")
        assertFalse(text.contains(root.toString()))

        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/debug-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)

        val again = build()
        for (task in listOf("processDebugResources", "compileDebugJavaWithJavac", "compileDebugKotlin", "compileDebugUnitTestKotlin")) {
            assertEquals(TaskOutcome.UP_TO_DATE, again.task(":$task")!!.outcome, task)
        }
        assertEquals(text, Files.readString(file))

        // witness가 덮은 R.jar 내용이 바뀌면 producer 증거가 더 이상 맞지 않으므로 stale이어야 한다.
        // 크기와 수정 시각을 그대로 두고 가운데 1바이트만 뒤집어, 내용 해시가 아닌 크기·mtime 비교로는 잡히지 않게 한다.
        val stamp = Files.getLastModifiedTime(rJar)
        val original = Files.readAllBytes(rJar)
        val tamperedBytes = original.copyOf().also { bytes -> bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte() }
        Files.write(rJar, tamperedBytes)
        Files.setLastModifiedTime(rJar, stamp)
        assertEquals(original.size.toLong(), Files.size(rJar))
        assertEquals(stamp, Files.getLastModifiedTime(rJar))
        val tampered = ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings)
        assertEquals("stale", tampered.status, tampered.toString())

        // Gradle의 up-to-date 판정은 크기·수정 시각이 같은 변경을 알아채지 못해 processDebugResources를 다시 실행하지 않는다.
        // 그래도 capture는 witness 해시로 변조를 잡아 fail-closed로 거부해야 하며, 낡은 snapshot을 덮어쓰지 않아야 한다.
        val rejected = GradleRunner.create().withProjectDir(root.toFile()).withDebug(inProcess)
            .withArguments(listOf("kartographSnapshotDebug", "--stacktrace") +
                if (inProcess) emptyList() else listOf("--configuration-cache")).buildAndFail()
        assertEquals(TaskOutcome.UP_TO_DATE, rejected.task(":processDebugResources")!!.outcome)
        assertEquals(TaskOutcome.FAILED, rejected.task(":kartographSnapshotDebug")!!.outcome)
        assertTrue(rejected.output.contains("snapshot compiler inputs are stale: changed-classes"), rejected.output)
        assertTrue(rejected.output.contains(rJarOutput.path), rejected.output)
        assertEquals(text, Files.readString(file), "a rejected capture must leave the previous snapshot untouched")

        // 변조된 R.jar를 지우면 Gradle이 processDebugResources를 다시 실행해 witness와 R.jar를 함께 새로 만든다.
        Files.delete(rJar)
        val recovered = build()
        assertEquals(TaskOutcome.SUCCESS, recovered.task(":processDebugResources")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, recovered.task(":kartographSnapshotDebug")!!.outcome)
        assertTrue(original.contentEquals(Files.readAllBytes(rJar)), "the regenerated R.jar must equal the original bytes")
        // 같은 입력에서 다시 만든 R.jar는 내용이 같으므로 회복된 snapshot은 처음 캡처와 바이트까지 같아야 한다.
        assertEquals(text, Files.readString(file))
        val refreshed = QuerySnapshotCodec.parse(Files.readString(file))
        val newBindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/debug-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(refreshed.provenance, root, refreshed.scope, newBindings).status)
        return again
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
