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
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class AndroidSnapshotIntegrationTest {
    @Test
    fun `Android variant captures mixed main unit test compilers and runtime entry inputs`(@TempDir root: Path) {
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar"))
            .replace("\\", "\\\\").replace("'", "\\'")
        write(root, "settings.gradle", "rootProject.name = 'android-snapshot'\n")
        write(root, "build.gradle", """
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:9.3.2'
                    classpath files('$pluginJar')
                }
            }
            apply plugin: 'com.android.library'
            apply plugin: 'io.github.ictechgy.kartograph'
            repositories { google(); mavenCentral() }
            android {
                namespace 'p'
                compileSdk 36
                defaultConfig { minSdk 23 }
                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }
            }
            kotlin { jvmToolchain(${Runtime.version().feature()}) }
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
        require(Files.isDirectory(Path.of(sdk))) { "Android snapshot integration requires an installed Android SDK" }
        write(root, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(root, "src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name="p.MainActivity" /></application></manifest>""")
        write(root, "src/main/java/p/MainActivity.java", "package p; public class MainActivity extends android.app.Activity { public int value() { return 1; } }")
        write(root, "src/main/java/p/CustomView.java", "package p; public class CustomView extends android.view.View { public CustomView(android.content.Context c) { super(c); } }")
        write(root, "src/main/kotlin/p/Entry.kt", "package p; class Entry { fun value() = MainActivity().value() }")
        write(root, "src/main/res/layout/sample.xml", """<p.CustomView xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" />""")
        write(root, "src/test/java/p/JavaCheck.java", "package p; public class JavaCheck { public int check() { return new Entry().value(); } }")
        write(root, "src/test/kotlin/p/KotlinCheck.kt", "package p; class KotlinCheck { fun check() = Entry().value() }")
        fun build() = GradleRunner.create().withProjectDir(root.toFile())
            .withArguments("kartographSnapshotDebug", "--configuration-cache", "--stacktrace").build()
        val result = build()
        assertEquals(null, result.task(":testDebugUnitTest"))
        val file = root.resolve("build/reports/kartograph/debug-snapshot.json")
        val text = Files.readString(file)
        val snapshot = QuerySnapshotCodec.parse(text)
        for (name in listOf("MainActivity", "CustomView", "Entry", "JavaCheck", "KotlinCheck")) {
            assertTrue(NodeId("class:p/$name") in snapshot.graph.nodes, name)
        }
        assertEquals(mapOf("javac" to 2, "kotlin" to 2), snapshot.provenance!!.witnesses.groupingBy { it.compiler }.eachCount())
        assertTrue(snapshot.retention.any { it.nodeId == NodeId("class:p/MainActivity") && it.reason == RetentionReason.MANIFEST_COMPONENT })
        assertTrue(snapshot.retention.any { it.nodeId == NodeId("class:p/CustomView") && it.reason == RetentionReason.XML_LAYOUT })
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/debug-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        assertFalse(text.contains(root.toString()))
        val again = build()
        assertTrue(again.output.contains("Reusing configuration cache"), again.output)
        for (task in listOf("compileDebugJavaWithJavac", "compileDebugKotlin", "compileDebugUnitTestJavaWithJavac", "compileDebugUnitTestKotlin")) {
            assertEquals(TaskOutcome.UP_TO_DATE, again.task(":$task")!!.outcome, task)
        }
        assertEquals(text, Files.readString(file))

        val activity = root.resolve("src/main/java/p/MainActivity.java")
        val stamp = Files.getLastModifiedTime(activity)
        Files.writeString(activity, Files.readString(activity).replace("return 1;", "return 2;"))
        Files.setLastModifiedTime(activity, stamp)
        assertEquals("stale", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        val changed = build()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":compileDebugJavaWithJavac")!!.outcome)
        val refreshed = QuerySnapshotCodec.parse(Files.readString(file))
        val newBindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/debug-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(refreshed.provenance, root, refreshed.scope, newBindings).status)

        Files.delete(root.resolve("src/test/java/p/JavaCheck.java"))
        build()
        val withoutJavaTest = QuerySnapshotCodec.parse(Files.readString(file))
        assertFalse(NodeId("class:p/JavaCheck") in withoutJavaTest.graph.nodes)
        assertTrue(NodeId("class:p/KotlinCheck") in withoutJavaTest.graph.nodes)
        assertEquals(3, withoutJavaTest.provenance!!.witnesses.size)
        assertFalse(withoutJavaTest.provenance!!.witnesses.any { it.artifact == ":compileDebugUnitTestJavaWithJavac" })
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
