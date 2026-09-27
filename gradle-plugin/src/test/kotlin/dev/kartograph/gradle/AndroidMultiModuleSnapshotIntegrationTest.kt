package dev.kartograph.gradle

import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * 실제 앱(AGP 9, 여러 모듈)에서 plugin 경로가 실패한 세 조건을 합성 fixture로 재현한다.
 *
 * - build 디렉터리를 저장소 밖으로 옮김: merged manifest·생성 resource가 project 밖에 생긴다.
 * - Java만 있는 unit test: 생성되지 않은 Kotlin unit-test 출력이 javac classpath에 들어간다.
 * - 같은 바이트의 외부 JAR 두 개(AndroidX stub JAR와 같은 모양): compiler 입력 연결이 모호했다.
 *
 * 모든 모듈 snapshot이 캡처되고, 각자의 로컬 연결로 `matched`가 되며, 절대 경로를 남기지 않아야 한다.
 */
class AndroidMultiModuleSnapshotIntegrationTest {
    @Test
    fun `relocated build directories Java-only unit tests and identical external jars capture every module`(@TempDir root: Path) {
        val repository = fixture(root)
        runner(repository, "kartographSnapshotDebug").build()
        val modules = mapOf("app" to ":app:debug", "core/network" to ":core:network:debug", "feature/alerts" to ":feature:alerts:debug")
        for ((module, scope) in modules) {
            assertFalse(repository.resolve("$module/build").exists(), "build output must stay outside the repository: $module")
            val snapshot = snapshot(root, module)
            assertEquals(scope, snapshot.scope)
            assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, repository.resolve(module), snapshot.scope,
                bindings(root, module)).status, module)
            assertFalse(Files.readString(snapshotFile(root, module)).contains(root.toString()), module)
        }
        val app = snapshot(root, "app")
        val manifest = app.retention.single { it.nodeId == NodeId("class:sample/app/MainActivity") && it.reason == RetentionReason.MANIFEST_COMPONENT }
        assertTrue(manifest.location!!.path.startsWith("build/intermediates/"), manifest.location!!.path)
        val network = snapshot(root, "core/network")
        assertTrue(NodeId("class:sample/network/ApiCheck") in network.graph.nodes)
        assertTrue(network.provenance!!.witnesses.any { it.artifact == ":core:network:compileDebugUnitTestJavaWithJavac" })
        val alerts = snapshot(root, "feature/alerts")
        assertTrue(NodeId("class:sample/alerts/AlertsModelCheck") in alerts.graph.nodes)
    }

    private fun runner(repository: Path, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(repository.toFile())
        .withArguments(arguments.toList() + listOf("--configuration-cache", "--stacktrace"))

    private fun snapshotFile(root: Path, module: String): Path = root.resolve("outside/$module/reports/kartograph/debug-snapshot.json")

    private fun snapshot(root: Path, module: String): QuerySnapshot = QuerySnapshotCodec.parse(Files.readString(snapshotFile(root, module)))

    private fun bindings(root: Path, module: String): Map<String, Path> =
        ExternalInputBindingsCodec.parse(Files.readString(root.resolve("outside/$module/kartograph/debug-input-bindings.json")))
            .mapValues { Path.of(it.value) }

    /** `repo/`에 세 모듈 Android build를, `outside/`에 build 출력을, `libs/`에 같은 바이트 JAR 두 개를 둔다. */
    private fun fixture(root: Path): Path {
        val repository = Files.createDirectories(root.resolve("repo"))
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar")).replace("\\", "\\\\").replace("'", "\\'")
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString()
        require(Files.isDirectory(Path.of(sdk))) { "Android multi-module snapshot integration requires an installed Android SDK" }
        listOf("a", "b").forEach { stubJar(root.resolve("libs/$it/stub.jar")) }
        val java = Runtime.version().feature()
        write(repository, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(repository, "gradle.properties", "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n")
        write(repository, "settings.gradle", "rootProject.name = 'multi-module'\ninclude ':app', ':core:network', ':feature:alerts'\n")
        write(repository, "build.gradle", """
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:9.3.2'
                    classpath files('$pluginJar')
                }
            }
            allprojects {
                repositories { google(); mavenCentral() }
                // 사용자 저장소를 깨끗하게 두려고 build 출력을 저장소 밖으로 옮긴다.
                layout.buildDirectory = new File(rootDir, "../outside/" + (path == ':' ? 'root' : path.substring(1).replace(':', '/')))
            }
            subprojects {
                ['com.android.application', 'com.android.library'].each { id ->
                    pluginManager.withPlugin(id) {
                        apply plugin: 'io.github.ictechgy.kartograph'
                        android {
                            compileSdk 36
                            defaultConfig { minSdk 23 }
                            compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
                        }
                        kotlin {
                            jvmToolchain($java)
                            compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
                        }
                        kartograph {
                            snapshotsEnabled = true
                            includeSourcePaths = true
                            snapshotKotlinToolchain = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of($java) }
                        }
                        tasks.withType(Test).configureEach { doFirst { throw new GradleException('snapshot must not run tests') } }
                    }
                }
            }
        """.trimIndent())
        write(repository, "core/network/build.gradle", "apply plugin: 'com.android.library'\nandroid { namespace 'sample.network' }\n")
        write(repository, "core/network/src/main/kotlin/sample/network/Api.kt", "package sample.network\nclass Api { fun load(): Int = 1 }\n")
        // Kotlin unit test가 없어 compileDebugUnitTestKotlin 출력이 생기지 않는다.
        write(repository, "core/network/src/test/java/sample/network/ApiCheck.java",
            "package sample.network; public class ApiCheck { public int check() { return new Api().load(); } }")
        write(repository, "feature/alerts/build.gradle", """
            apply plugin: 'com.android.library'
            android { namespace 'sample.alerts' }
            dependencies {
                implementation project(':core:network')
                implementation files('../../../libs/a/stub.jar', '../../../libs/b/stub.jar')
            }
        """.trimIndent())
        write(repository, "feature/alerts/src/main/kotlin/sample/alerts/AlertsModel.kt",
            "package sample.alerts\nclass AlertsModel { fun count(): Int = sample.network.Api().load() }\n")
        write(repository, "feature/alerts/src/main/res/layout/alerts.xml",
            """<sample.alerts.AlertsView xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" />""")
        write(repository, "feature/alerts/src/main/kotlin/sample/alerts/AlertsView.kt",
            "package sample.alerts\nclass AlertsView(context: android.content.Context) : android.view.View(context)\n")
        write(repository, "feature/alerts/src/test/kotlin/sample/alerts/AlertsModelCheck.kt",
            "package sample.alerts\nclass AlertsModelCheck { fun check() = AlertsModel().count() }\n")
        write(repository, "app/build.gradle", """
            apply plugin: 'com.android.application'
            android {
                namespace 'sample.app'
                defaultConfig { applicationId 'sample.app'; targetSdk 36; versionCode 1; versionName '1' }
            }
            dependencies { implementation project(':feature:alerts') }
        """.trimIndent())
        write(repository, "app/src/main/AndroidManifest.xml",
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><activity android:name=".MainActivity" android:exported="false" /></application></manifest>""")
        write(repository, "app/src/main/res/values/strings.xml", """<resources><string name="app_name">sample</string></resources>""")
        write(repository, "app/src/main/kotlin/sample/app/MainActivity.kt",
            "package sample.app\nclass MainActivity : android.app.Activity() { fun count() = sample.alerts.AlertsModel().count() }\n")
        return repository
    }

    /** 항목 하나의 결정적 JAR다. 두 위치에 같은 바이트로 만든다. */
    private fun stubJar(path: Path) {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/stub.txt").apply { time = 0 })
            jar.write("stub".toByteArray())
            jar.closeEntry()
        }
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
