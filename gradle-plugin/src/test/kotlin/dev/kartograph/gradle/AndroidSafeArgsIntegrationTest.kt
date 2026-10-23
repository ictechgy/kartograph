package dev.kartograph.gradle

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.InputHints
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.InputHint
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/** 실제 생성기/컴파일러의 출처 공백 재현이다. generated를 runtime root로 가정하지 않는다. */
class AndroidSafeArgsIntegrationTest {
    @Test
    fun `SafeArgs 실제 생성물과 수기 대조군의 출처 및 bytecode 호출을 구분한다`(@TempDir root: Path) {
        SafeArgsBuildEvidence.withFinalCapture({ preserveEvidence(root) }) { observe(root) }
    }

    private fun observe(root: Path) {
        fixture(root)
        val result = SafeArgsBuildEvidence.build(root.resolve("build/safeargs-observations")) {
            GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                "observeSafeArgsDebug", "observeSafeArgsRelease", "kartographSnapshotDebug", "kartographSnapshotRelease",
                "--stacktrace", "--configuration-cache", "--no-build-cache",
            ).build()
        }
        for (variant in listOf("Debug", "Release")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":generateSafeArgs$variant")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":compile${variant}JavaWithJavac")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":observeSafeArgs$variant")?.outcome)
            assertEquals(null, result.task(":test${variant}UnitTest"))
            val snap = snapshot(root, variant.lowercase())
            val generated = listOf("p/StartFragmentArgs", "p/StartFragmentDirections",
                "p/StartFragmentDirections\$Go", "p/StartFragmentDirections\$Idle")
            val manual = listOf("p/ManualArgs", "p/ManualDirections", "other/StartFragmentArgs")
            for (name in generated + manual) {
                val node = snap.graph.nodes.getValue(NodeId("class:$name"))
                // 9b9082의 재현 기대값이다. 구현 후 생성물만 증명하는 기대값으로 바꾼다.
                assertFalse(node.synthesized, name)
                assertFalse(NodeAttribute.GENERATED_INPUT in node.attributes, name)
            }
            assertTrue(InputHint.ANDROID_GENERATED_PROVENANCE_UNVERIFIED in InputHints.detect(1, 1, 1, snap.graph))
            val entry = NodeId("method:p/MainActivity#onCreate(Landroid/os/Bundle;)V")
            val usedFactory = NodeId("method:p/StartFragmentDirections#go(I)Lp/StartFragmentDirections\$Go;")
            val unusedFactory = NodeId("method:p/StartFragmentDirections#idle(I)Lp/StartFragmentDirections\$Idle;")
            assertTrue(snap.graph.outgoingEdgesFrom(entry).any {
                it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE && it.target == usedFactory
            })
            assertFalse(snap.graph.incomingEdgesTo(unusedFactory).any {
                it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE
            })
            val reached = ReachabilityAnalyzer.analyze(snap.graph, snap.retention)
            assertTrue(usedFactory in reached.reachableNodeIds)
            val dead = DeadFindings.collect(snap.graph, reached)
            for (name in manual) {
                val id = NodeId("class:$name")
                assertFalse(id in reached.reachableNodeIds, name)
                assertTrue(dead.any { it.nodeId == id }, name)
            }
            // 미사용 생성 action은 독립 대조군이다. 구조적 과잉 보존을 false-dead로 단정하지 않는다.
            for (name in generated + manual) {
                val id = NodeId("class:$name")
                println("safe-args-native variant=$variant id=$id synthesized=${snap.graph.nodes.getValue(id).synthesized}" +
                    " reachable=${id in reached.reachableNodeIds} path=${reached.pathFromRootTo(id)}" +
                    " dead=${dead.any { it.nodeId == id }}")
            }
            val observation = Files.readString(root.resolve("build/safeargs-observations/$variant.json"))
            println("safe-args-native observation=$observation")
            assertTrue(observation.contains("StartFragmentArgs.java"))
            assertTrue(observation.contains("ManualArgs.class"))
            assertTrue(observation.contains("p/StartFragmentArgs.class"))
            assertTrue(observation.contains("other/StartFragmentArgs.class"))
            assertTrue(observation.contains("StartFragmentDirections\$Idle.class"))
            val expectedDefault = if (variant == "Debug") "11" else "22"
            assertTrue(observation.contains("\\\"seed\\\", $expectedDefault"), observation)
            println("safe-args-native complete=$variant freshness=matched controls=checked")
        }
    }

    /** 이 테스트가 만든 작은 입력/관측만 보존한다. 실패 시 원래 실패도 유지한다. */
    private fun preserveEvidence(root: Path) {
        val requested = System.getenv("KARTOGRAPH_SAFEARGS_EVIDENCE_DIR") ?: return
        val destination = Path.of(requested).toAbsolutePath().normalize()
        require(!Files.exists(destination)) { "SafeArgs evidence destination must be new" }
        Files.createDirectories(destination)
        val rows = mutableListOf<String>()
        var total = 0L
        for ((relative, prefix) in listOf("src" to "fixture/src", "build.gradle" to "fixture/build.gradle",
            "settings.gradle" to "fixture/settings.gradle", "gradle.properties" to "fixture/gradle.properties",
            "build/safeargs-observations" to "observations", "build/reports/kartograph" to "snapshots")) {
            val source = root.resolve(relative)
            if (!Files.exists(source)) continue
            Files.walk(source).use { paths -> paths.sorted().forEach { file ->
                require(!Files.isSymbolicLink(file)) { "unexpected symlink in SafeArgs fixture evidence" }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@forEach
                val suffix = source.relativize(file).toString().replace('\\', '/')
                val name = if (suffix.isEmpty()) prefix else "$prefix/$suffix"
                require(!name.contains('\t') && !name.contains('\n'))
                val size = Files.size(file)
                require(size <= 10 * 1024 * 1024 && total + size <= 50 * 1024 * 1024 && rows.size < 200) {
                    "SafeArgs fixture evidence budget exceeded"
                }
                val bytes = Files.readAllBytes(file)
                require(bytes.size.toLong() == size) { "SafeArgs evidence changed during capture" }
                total += bytes.size
                val target = destination.resolve(name)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                rows += "$hash\t${bytes.size}\t$name"
            } }
        }
        Files.writeString(destination.resolve("capture.tsv"), rows.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun snapshot(root: Path, variant: String): QuerySnapshot {
        val snap = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/$variant-snapshot.json")))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/$variant-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snap.provenance, root, snap.scope, bindings).status)
        return snap
    }

    private fun fixture(root: Path) {
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar")).replace("\\", "\\\\").replace("'", "\\'")
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString()
        require(Files.isDirectory(Path.of(sdk))) { "SafeArgs integration requires an installed Android SDK; do not substitute generated stubs" }
        write(root, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(root, "settings.gradle", "rootProject.name='android-safeargs-provenance'\n")
        write(root, "gradle.properties", "android.useAndroidX=true\n")
        write(root, "build.gradle", """
            import androidx.navigation.safeargs.gradle.ArgumentsGenerationTask
            import groovy.json.JsonOutput
            import java.security.MessageDigest
            abstract class SafeArgsObservation extends DefaultTask {
                @Input abstract Property<String> getApplicationId()
                @Input abstract Property<String> getRFilePackage()
                @Input abstract Property<Boolean> getUseAndroidX()
                @Input abstract Property<Boolean> getGenerateKotlin()
                @Input abstract Property<String> getGeneratorTask()
                @Input abstract Property<String> getCompilerTask()
                @Internal abstract DirectoryProperty getFixtureRoot()
                @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getNavigationFiles()
                @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getCompilerSources()
                @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getGeneratedSources()
                @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getCompiledClasses()
                @OutputFile abstract RegularFileProperty getObservationFile()
                static String sha(File file) { MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString() }
                static Map inventory(File root) {
                    def result = new TreeMap()
                    root.eachFileRecurse { file -> if (file.isFile()) result[root.toPath().relativize(file.toPath()).toString().replace('\\','/')] = sha(file) }
                    result
                }
                @TaskAction void observe() {
                    def base = fixtureRoot.get().asFile.toPath().toAbsolutePath().normalize()
                    def relative = { File file ->
                        def path = file.toPath().toAbsolutePath().normalize()
                        if (!path.startsWith(base)) throw new GradleException('fixture input outside project')
                        base.relativize(path).toString().replace('\\','/')
                    }
                    def descriptor = JsonOutput.toJson([applicationId:applicationId.get(), rFilePackage:rFilePackage.get(),
                        useAndroidX:useAndroidX.get(), generateKotlin:generateKotlin.get(),
                        generatorTask:generatorTask.get(), compilerTask:compilerTask.get(),
                        generatedSourceRoot:relative(generatedSources.get().asFile), compiledClassRoot:relative(compiledClasses.get().asFile)])
                    def value = [providerDescriptor:descriptor,
                        providerSha256:MessageDigest.getInstance('SHA-256').digest(descriptor.getBytes('UTF-8')).encodeHex().toString(),
                        navigation:navigationFiles.files.collectEntries { [(relative(it)):sha(it)] },
                        compilerSources:compilerSources.files.collectEntries { [(relative(it)):sha(it)] },
                        generated:inventory(generatedSources.get().asFile), compiled:inventory(compiledClasses.get().asFile)]
                    def sourceTexts = new TreeMap()
                    generatedSources.get().asFile.eachFileRecurse { file -> if (file.isFile() && file.name.endsWith('.java')) sourceTexts[file.name] = file.getText('UTF-8') }
                    value.generatedSourceTexts = sourceTexts
                    def out = observationFile.get().asFile; out.parentFile.mkdirs(); out.text = JsonOutput.toJson(value)
                }
            }
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:9.3.2'
                    classpath 'androidx.navigation:navigation-safe-args-gradle-plugin:2.10.2'
                    classpath files('$pluginJar')
                }
            }
            apply plugin: 'com.android.application'
            apply plugin: 'androidx.navigation.safeargs'
            apply plugin: 'io.github.ictechgy.kartograph'
            repositories { google(); mavenCentral() }
            dependencies { implementation 'androidx.navigation:navigation-runtime:2.10.2' }
            android {
                namespace 'p'; compileSdk 36
                defaultConfig { applicationId 'p.safeargs'; minSdk 24; targetSdk 36; versionCode 1; versionName '1' }
                compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
            }
            kotlin {
                jvmToolchain(${Runtime.version().feature()})
                compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
            }
            kartograph {
                snapshotsEnabled = true; includeSourcePaths = true
                snapshotKotlinToolchain = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(${Runtime.version().feature()}) }
            }
            androidComponents.onVariants { selected ->
                def variant = selected.name.capitalize()
                def generation = tasks.named('generateSafeArgs' + variant, ArgumentsGenerationTask)
                def observation = tasks.register('observeSafeArgs' + variant, SafeArgsObservation) {
                    fixtureRoot.set(layout.projectDirectory)
                    generatorTask.set(generation.map { it.path })
                    applicationId.set(generation.flatMap { it.applicationId })
                    rFilePackage.set(generation.flatMap { it.rFilePackage })
                    useAndroidX.set(generation.flatMap { it.useAndroidX })
                    generateKotlin.set(generation.flatMap { it.generateKotlin })
                    navigationFiles.from(generation.map { it.navigationFiles })
                    generatedSources.set(generation.flatMap { it.outputDir })
                    observationFile.set(layout.buildDirectory.file('safeargs-observations/' + variant + '.json'))
                }
                tasks.withType(JavaCompile).configureEach { compiler ->
                    if (compiler.name == 'compile' + variant + 'JavaWithJavac') {
                        observation.configure {
                            compilerTask.set(compiler.path); compilerSources.from(compiler.source)
                            compiledClasses.set(compiler.destinationDirectory); dependsOn(compiler)
                        }
                    }
                }
            }
            tasks.withType(Test).configureEach { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        write(root, "src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
                <activity android:name="p.MainActivity" android:exported="false" />
            </application></manifest>
        """.trimIndent())
        write(root, "src/main/java/p/MainActivity.java", """
            package p;
            public class MainActivity extends android.app.Activity {
                @Override protected void onCreate(android.os.Bundle state) {
                    super.onCreate(state);
                    StartFragmentArgs args = StartFragmentArgs.fromBundle(new android.os.Bundle());
                    android.os.Bundle direct = StartFragmentDirections.go(args.getSeed()).getArguments();
                    setTitle(Integer.toString(direct.getInt("value")));
                }
            }
        """.trimIndent())
        write(root, "src/main/java/p/ManualArgs.java", "package p; public final class ManualArgs implements androidx.navigation.NavArgs {}")
        write(root, "src/main/java/p/ManualDirections.java", """
            package p; public final class ManualDirections implements androidx.navigation.NavDirections {
                public int getActionId() { return 0; }
                public android.os.Bundle getArguments() { return new android.os.Bundle(); }
            }
        """.trimIndent())
        write(root, "src/main/java/other/StartFragmentArgs.java", "package other; public final class StartFragmentArgs {}")
        write(root, "src/main/java/p/StartFragment.java", "package p; public class StartFragment extends android.app.Fragment {}")
        write(root, "src/main/java/p/EndFragment.java", "package p; public class EndFragment extends android.app.Fragment {}")
        write(root, "src/main/res/navigation/graph.xml", navigation(22))
        write(root, "src/debug/res/navigation/graph.xml", navigation(11))
    }

    private fun navigation(defaultSeed: Int) = """
        <navigation xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@+id/graph" app:startDestination="@id/start">
            <fragment android:id="@+id/start" android:name="p.StartFragment">
                <argument android:name="seed" app:argType="integer" android:defaultValue="$defaultSeed" />
                <action android:id="@+id/go" app:destination="@id/end" />
                <action android:id="@+id/idle" app:destination="@id/end" />
            </fragment>
            <fragment android:id="@+id/end" android:name="p.EndFragment">
                <argument android:name="value" app:argType="integer" />
            </fragment>
        </navigation>
    """.trimIndent()

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, text)
    }
}
