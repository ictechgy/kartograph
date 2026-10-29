package dev.kartograph.gradle

import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.ReachabilityResult
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/** 실제 AGP processor의 선택과 정적 도달성을 검사한다. Android 실행이나 XML 대응 인증은 아니다. */
class AndroidDataBindingIntegrationTest {
    @Test
    fun `실제 DataBinding 생성 호출과 보존 경로를 Activity 진입 경로와 구분한다`(@TempDir root: Path) {
        fixture(root)
        val compiled = build(root, "kartographSnapshotDebug", "kartographSnapshotRelease")
        for (variant in listOf("Debug", "Release")) {
            assertEquals(TaskOutcome.SUCCESS, compiled.task(":compile${variant}JavaWithJavac")?.outcome)
            assertEquals(null, compiled.task(":test${variant}UnitTest"))
        }
        val debug = snapshot(root, "debug")
        val release = snapshot(root, "release")
        assertGeneratedCalls(debug, "debugValue")
        assertGeneratedCalls(release, "releaseValue")
        assertReachedMapperChain(debug)
        assertReachedMapperChain(release)
        assertTrue(debug.limitations.any { it.startsWith("data-binding-scope:") }, debug.limitations.toString())

        // 같은 이름의 main/debug layout이 실제로 다른 adapter CALL을 생성해야 한다.
        // XML 후보 합집합을 실제 variant winner나 runtime 실행으로 승격시키지 않는다.
        assertFalse(adapter("releaseValue") in calledAdapters(debug))
        assertFalse(adapter("debugValue") in calledAdapters(release))
        println("data-binding-native debugCalls=${calledAdapters(debug).sorted()}")
        println("data-binding-native releaseCalls=${calledAdapters(release).sorted()}")

        // 생성물을 직접 지우거나 graph를 잘라내지 않고 진짜 진입 호출만 제거하여 재컴파일한다.
        // 전체 보존은 mapper의 상수도 root로 삼으므로 Activity 진입 경로와 따로 검사한다.
        write(root, "src/main/java/p/MainActivity.java", activity(inflate = false))
        val uncalledBuild = build(root, "kartographSnapshotDebug")
        assertEquals(TaskOutcome.SUCCESS, uncalledBuild.task(":compileDebugJavaWithJavac")?.outcome)
        val uncalled = snapshot(root, "debug")
        assertGeneratedCalls(uncalled, "debugValue")
        assertEquals(calledAdapters(debug), calledAdapters(uncalled))
        val reachability = ReachabilityAnalyzer.analyze(uncalled.graph, uncalled.retention)
        assertTrue(NodeId("class:p/MainActivity") in reachability.reachableNodeIds)
        assertTrue(bindingOwner in uncalled.graph.nodes)
        assertTrue(bindingExecute in uncalled.graph.nodes)
        assertTrue(debug.graph.externalCalls.any { it.caller == entry && it.owner == "androidx/databinding/DataBindingUtil" })
        assertFalse(uncalled.graph.externalCalls.any { it.caller == entry && it.owner == "androidx/databinding/DataBindingUtil" })
        val ownerPath = reachability.pathFromRootTo(bindingOwner)
        val rootEvidence = ownerPath?.firstOrNull()?.let { id -> uncalled.retention.filter { it.nodeId == id } }.orEmpty()
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("src/main/java/p/MainActivity.java")))
            .joinToString("") { "%02x".format(it) }
        println("data-binding-native noInflation sourceSha256=$sourceHash snapshotFreshness=matched entryCallAbsent=true")
        println("data-binding-native noInflation bindingOwnerPath=$ownerPath rootEvidence=$rootEvidence")
        for ((source, target) in ownerPath.orEmpty().zipWithNext()) {
            println("data-binding-native noInflation pathEdges=" + uncalled.graph.outgoingEdgesFrom(source).filter { it.target == target })
        }
        assertEquals(listOf(NodeId("class:p/DataBinderMapperImpl"), NodeId("method:p/DataBinderMapperImpl#getDataBinder(Landroidx/databinding/DataBindingComponent;Landroid/view/View;I)Landroidx/databinding/ViewDataBinding;"), bindingOwner), ownerPath)
        assertTrue(rootEvidence.any { it.reason == RetentionReason.INLINE_CONSTANT }, rootEvidence.toString())
        assertTrue(bindingExecute in reachability.reachableNodeIds)
        val entryReachability = activityClosure(uncalled)
        assertFalse(bindingOwner in entryReachability.reachableNodeIds)
        assertFalse(bindingExecute in entryReachability.reachableNodeIds)
        for (id in calledAdapters(uncalled)) {
            assertTrue(id in reachability.reachableNodeIds, id.value)
            assertFalse(id in entryReachability.reachableNodeIds, id.value)
        }
        println("data-binding-native defaultRetention owner=${bindingOwner.value} reachable=true reason=INLINE_CONSTANT")
        println("data-binding-native uncalledOwner=${bindingOwner.value} scope=activity-entry reachable=false compiledCallsPreserved=true")
    }

    private fun fixture(root: Path) {
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar"))
            .replace("\\", "\\\\").replace("'", "\\'")
        write(root, "settings.gradle", "rootProject.name = 'android-databinding-feedback'\n")
        write(root, "gradle.properties", "android.useAndroidX=true\n")
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
                namespace 'p'
                compileSdk 36
                defaultConfig { applicationId 'p.binding'; minSdk 23; targetSdk 36; versionCode 1; versionName '1' }
                buildFeatures { dataBinding true }
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
                snapshotKotlinToolchain = javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()})
                }
            }
            tasks.withType(Test).configureEach { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString()
        require(Files.isDirectory(Path.of(sdk))) { "DataBinding integration requires an installed Android SDK" }
        write(root, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(root, "src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application><activity android:name="p.MainActivity" android:exported="false" /></application>
            </manifest>
        """.trimIndent())
        write(root, "src/main/java/p/MainActivity.java", activity(inflate = true))
        write(root, "src/main/java/p/Adapters.java", """
            package p;
            import android.view.View;
            import androidx.databinding.BindingAdapter;
            public final class Adapters {
                @BindingAdapter({"bothFirst", "bothSecond"})
                public static void both(View view, int first, int second) { view.setTag(first + second); }
                @BindingAdapter(value={"anyFirst", "anySecond"}, requireAll=false)
                public static void optional(View view, int first, int second) { view.setTag(first + second); }
                @BindingAdapter("overloaded")
                public static void overloaded(View view, int value) { view.setTag(value); }
                @BindingAdapter("overloaded")
                public static void overloaded(View view, String value) { view.setTag(value); }
                @BindingAdapter({"splitFirst", "splitSecond"})
                public static void split(View view, int first, int second) { view.setTag(first + second); }
                @BindingAdapter("splitFirst")
                public static void splitFirstOnly(View view, int value) { view.setTag(value); }
                @BindingAdapter("splitSecond")
                public static void splitSecondOnly(View view, int value) { view.setTag(value); }
                @BindingAdapter({"literalRequiredFirst", "literalRequiredSecond"})
                public static void literalRequired(View view, int first, int second) { view.setTag(first + second); }
                @BindingAdapter("literalRequiredFirst")
                public static void literalFirstOnly(View view, int value) { view.setTag(value); }
                @BindingAdapter(value={"literalOptionalFirst", "literalOptionalSecond"}, requireAll=false)
                public static void literalOptional(View view, int first, int second) { view.setTag(first + second); }
                @BindingAdapter("debugValue")
                public static void debugValue(View view, int value) { view.setTag(value); }
                @BindingAdapter("releaseValue")
                public static void releaseValue(View view, int value) { view.setTag(value); }
                @BindingAdapter("unused")
                public static void unused(View view, int value) { view.setTag(value); }
            }
        """.trimIndent())
        // Normal XML values need AAPT declarations, but are not binding expressions.
        // The required pair must use the single-expression fallback; the optional pair may be called.
        write(root, "src/main/res/values/attrs.xml", """
            <resources>
                <attr name="literalRequiredSecond" format="integer" />
                <attr name="literalOptionalSecond" format="integer" />
            </resources>
        """.trimIndent())
        write(root, "src/main/res/layout/screen.xml", layout("releaseValue"))
        write(root, "src/debug/res/layout/screen.xml", layout("debugValue"))
    }

    private fun activity(inflate: Boolean) = """
        package p;
        public class MainActivity extends android.app.Activity {
            @Override protected void onCreate(android.os.Bundle state) {
                super.onCreate(state);
                ${if (inflate) "androidx.databinding.DataBindingUtil.setContentView(this, R.layout.screen);" else ""}
            }
        }
    """.trimIndent()

    private fun layout(variantAttribute: String) = """
        <layout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto">
            <data><variable name="count" type="int" /></data>
            <LinearLayout android:layout_width="match_parent" android:layout_height="match_parent"
                          android:orientation="vertical">
                <View android:layout_width="1dp" android:layout_height="1dp"
                      app:bothFirst="@{count}" app:bothSecond="@{count}"
                      app:anyFirst="@{count}" app:overloaded="@{count}" app:$variantAttribute="@{count}" />
                <View android:layout_width="1dp" android:layout_height="1dp" app:splitFirst="@{count}" />
                <View android:layout_width="1dp" android:layout_height="1dp" app:splitSecond="@{count}" />
                <View android:layout_width="1dp" android:layout_height="1dp"
                      app:literalRequiredFirst="@{count}" app:literalRequiredSecond="3" />
                <View android:layout_width="1dp" android:layout_height="1dp"
                      app:literalOptionalFirst="@{count}" app:literalOptionalSecond="3" />
            </LinearLayout>
        </layout>
    """.trimIndent()

    private fun build(root: Path, vararg tasks: String) = GradleRunner.create().withProjectDir(root.toFile())
        .withArguments(tasks.toList() + listOf("--stacktrace", "--configuration-cache")).build()

    private fun snapshot(root: Path, variant: String): QuerySnapshot {
        val text = Files.readString(root.resolve("build/reports/kartograph/$variant-snapshot.json"))
        val snapshot = QuerySnapshotCodec.parse(text)
        val inputs = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/$variant-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        val freshness = ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, inputs)
        assertEquals("matched", freshness.status, freshness.toString())
        assertFalse(text.contains(root.toString()))
        assertFalse(snapshot.includePrivateMembers)
        return snapshot
    }

    private fun assertGeneratedCalls(snapshot: QuerySnapshot, variantAdapter: String) {
        assertTrue(bindingOwner in snapshot.graph.nodes)
        assertTrue(bindingExecute in snapshot.graph.nodes)
        val expected = setOf(adapter("both", "II"), adapter("optional", "II"), adapter("overloaded"),
            adapter("splitFirstOnly"), adapter("splitSecondOnly"), adapter("literalFirstOnly"),
            adapter("literalOptional", "II"), adapter(variantAdapter))
        // 부분 포함 검사가 아니라 실제 생성 메서드에서 앱 adapter로 가는 전체 exact CALL 집합이다.
        assertEquals(expected, calledAdapters(snapshot))
        for (id in listOf(adapter("overloaded", "Ljava/lang/String;"), adapter("split", "II"),
            adapter("literalRequired", "II"), adapter("unused"))) {
            assertTrue(id in snapshot.graph.nodes, id.value)
            assertFalse(snapshot.graph.incomingEdgesTo(id).any { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE }, id.value)
        }
    }

    private fun assertReachedMapperChain(snapshot: QuerySnapshot) {
        val reached = ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention)
        val entryReached = activityClosure(snapshot)
        assertTrue(snapshot.retention.any { it.nodeId == NodeId("class:p/MainActivity") && it.reason == RetentionReason.MANIFEST_COMPONENT })
        assertTrue(NodeId("class:androidx/databinding/DataBinderMapperImpl") in reached.reachableNodeIds)
        assertTrue(NodeId("class:p/DataBinderMapperImpl") in reached.reachableNodeIds)
        assertTrue(bindingOwner in reached.reachableNodeIds)
        assertTrue(bindingExecute in reached.reachableNodeIds)
        assertTrue(snapshot.graph.outgoingEdgesFrom(bindingOwner).any {
            it.target == bindingExecute && it.kind == EdgeKind.REFERENCE && it.origin == EdgeOrigin.RUNTIME_MODEL
        })
        for (id in calledAdapters(snapshot)) {
            val path = assertNotNull(reached.pathFromRootTo(id), id.value)
            assertTrue(bindingExecute in path, path.toString())
            assertFalse(snapshot.retention.any { it.nodeId == id }, "adapter became an unconditional root: ${id.value}")
            val entryPath = assertNotNull(entryReached.pathFromRootTo(id), id.value)
            assertEquals(entry, entryPath.first())
            assertTrue(NodeId("class:androidx/databinding/DataBinderMapperImpl") in entryPath ||
                NodeId("method:androidx/databinding/DataBinderMapperImpl#<init>()V") in entryPath, entryPath.toString())
            assertTrue(NodeId("class:p/DataBinderMapperImpl") in entryPath, entryPath.toString())
            assertTrue(bindingOwner in entryPath && bindingExecute in entryPath, entryPath.toString())
        }
        for (id in listOf(adapter("overloaded", "Ljava/lang/String;"), adapter("split", "II"),
            adapter("literalRequired", "II"), adapter("unused"))) {
            assertFalse(id in reached.reachableNodeIds, id.value)
        }
    }

    // 저장된 manifest 보존 중 실제 Activity 진입 하나만 선택한 질문이다. 제품 root는 바꾸지 않는다.
    private fun activityClosure(snapshot: QuerySnapshot): ReachabilityResult {
        val roots = snapshot.retention.filter { it.nodeId == entry && it.reason == RetentionReason.MANIFEST_COMPONENT }
        assertTrue(roots.isNotEmpty(), "manifest-expanded Activity entry must be present")
        return ReachabilityAnalyzer.analyze(snapshot.graph, roots)
    }

    private fun calledAdapters(snapshot: QuerySnapshot): Set<NodeId> = snapshot.graph.outgoingEdgesFrom(bindingExecute)
        .filter { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE && it.target.value.startsWith("method:p/Adapters#") }
        .mapTo(linkedSetOf()) { it.target }

    private fun adapter(name: String, arguments: String = "I") = NodeId("method:p/Adapters#$name(Landroid/view/View;$arguments)V")

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        val entry = NodeId("method:p/MainActivity#onCreate(Landroid/os/Bundle;)V")
        val bindingOwner = NodeId("class:p/databinding/ScreenBindingImpl")
        val bindingExecute = NodeId("method:p/databinding/ScreenBindingImpl#executeBindings()V")
    }
}
