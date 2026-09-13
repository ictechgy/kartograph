package dev.kartograph.gradle

import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class JvmSnapshotBuildInputsIntegrationTest {
    @Test
    fun `explicit external configuration input preserves its freshness boundary`(@TempDir workspace: Path) {
        val root = Files.createDirectories(workspace.resolve("consumer"))
        val shared = Files.createDirectories(workspace.resolve("shared-build-logic"))
        val convention = shared.resolve("convention.gradle")
        Files.writeString(convention, "kartograph { includePrivateMembers = false }\n")
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='external-convention'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            apply from: '../shared-build-logic/convention.gradle'
            kartograph {
                snapshotsEnabled = true
                snapshotBuildInputs.from('../shared-build-logic')
            }
        """.trimIndent())
        val sources = Files.createDirectories(root.resolve("src/main/java"))
        Files.writeString(sources.resolve("Entry.java"), "public class Entry { private int value() { return 1; } }")
        fun capture() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace").build()
        capture()
        val graph = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val snapshot = QuerySnapshotCodec.parse(Files.readString(graph))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        Files.writeString(convention, "kartograph { includePrivateMembers = true }\n")
        assertEquals("stale", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        capture()
        assertTrue(QuerySnapshotCodec.parse(Files.readString(graph)).includePrivateMembers)
    }

    @Test
    fun `included convention subproject sources invalidate captured options`(@TempDir root: Path) {
        fun write(path: String, content: String) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }
        write("settings.gradle", "pluginManagement { includeBuild('build-logic') }\nrootProject.name='nested-conventions'\n")
        write("build-logic/settings.gradle", "rootProject.name='build-logic'\ninclude 'convention'\n")
        write("build-logic/convention/build.gradle", """
            plugins { id 'java-gradle-plugin' }
            gradlePlugin { plugins { fixture { id='sample.convention'; implementationClass='sample.Convention' } } }
        """.trimIndent())
        val convention = "build-logic/convention/src/main/java/sample/Convention.java"
        write(convention, """
            package sample;
            public class Convention implements org.gradle.api.Plugin<org.gradle.api.Project> {
                public void apply(org.gradle.api.Project project) {
                    Object extension = project.getExtensions().getByName("kartograph");
                    try {
                        org.gradle.api.provider.Property<Boolean> property = (org.gradle.api.provider.Property<Boolean>) extension.getClass().getMethod("getIncludePrivateMembers").invoke(extension);
                        property.set(false);
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                }
            }
        """.trimIndent())
        write("build.gradle", """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph'; id 'sample.convention' }
            kartograph { snapshotsEnabled=true }
        """.trimIndent())
        write("src/main/java/p/Entry.java", "package p; public class Entry { private void hidden() {} }")
        fun capture() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace").build()
        capture()
        val graph = root.resolve("build/reports/kartograph/jvm-snapshot.json")
        val snapshot = QuerySnapshotCodec.parse(Files.readString(graph))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        write(convention, Files.readString(root.resolve(convention)).replace("property.set(false)", "property.set(true)"))
        assertEquals("stale", ProvenanceVerifier.verify(snapshot.provenance, root, snapshot.scope, bindings).status)
        capture()
        assertTrue(QuerySnapshotCodec.parse(Files.readString(graph)).includePrivateMembers)
    }

    @Test
    fun `subproject snapshot binds root configuration and detects its change`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'snapshot-build-inputs'\ninclude 'app'\n")
        val build = root.resolve("build.gradle")
        Files.writeString(build, """
            plugins { id 'io.github.ictechgy.kartograph' apply false }
            subprojects {
                plugins.withId('io.github.ictechgy.kartograph') {
                    kartograph { includePrivateMembers = false }
                }
            }
        """.trimIndent())
        val app = Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("gradle.properties"), "fixtureValue=1\n")
        Files.writeString(app.resolve("gradle.properties"), "fixtureValue=1\n")
        Files.writeString(app.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph { snapshotsEnabled = true; includeSourcePaths = true }
        """.trimIndent())
        val sources = Files.createDirectories(app.resolve("src/main/java/p"))
        Files.writeString(sources.resolve("Entry.java"), "package p; public class Entry { private int value() { return 1; } }")
        fun capture() = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments(":app:kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace").build()
        capture()
        val snapshot = QuerySnapshotCodec.parse(Files.readString(app.resolve("build/reports/kartograph/jvm-snapshot.json")))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(app.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertTrue(NodeId("class:p/Entry") in snapshot.graph.nodes)
        assertEquals("matched", ProvenanceVerifier.verify(snapshot.provenance, app, snapshot.scope, bindings).status)
        Files.writeString(build, Files.readString(build).replace("includePrivateMembers = false", "includePrivateMembers = true"))
        assertEquals("stale", ProvenanceVerifier.verify(snapshot.provenance, app, snapshot.scope, bindings).status)
        capture()
        val updated = QuerySnapshotCodec.parse(Files.readString(app.resolve("build/reports/kartograph/jvm-snapshot.json")))
        assertTrue(updated.includePrivateMembers)
    }

    @Test
    fun `new configuration files and catalog source changes invalidate a snapshot`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'snapshot-config-watch'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'io.github.ictechgy.kartograph' }
            kartograph {
                snapshotsEnabled = true
                includePrivateMembers = providers.gradleProperty('privateAnalysis').map { it.toBoolean() }.orElse(false)
            }
        """.trimIndent())
        val sources = Files.createDirectories(root.resolve("src/main/java/p"))
        Files.writeString(sources.resolve("Entry.java"), "package p; public class Entry { private int value() { return 1; } }")
        fun capture() {
            GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace").build()
        }
        fun snapshot() = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/jvm-snapshot.json")))
        fun bindings() = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/jvm-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        capture()
        val initial = snapshot()
        val initialBindings = bindings()
        Files.writeString(root.resolve("gradle.properties"), "privateAnalysis=true\n")
        assertEquals("stale", ProvenanceVerifier.verify(initial.provenance, root, initial.scope, initialBindings).status)
        capture()
        val configured = snapshot()
        assertTrue(configured.includePrivateMembers)
        val configuredBindings = bindings()
        val gradle = Files.createDirectories(root.resolve("gradle"))
        Files.writeString(gradle.resolve("libs.versions.toml"), "[versions]\nfixture = \"1\"\n")
        assertEquals("stale", ProvenanceVerifier.verify(configured.provenance, root, configured.scope, configuredBindings).status)
        capture()
        val catalog = snapshot()
        val catalogBindings = bindings()
        val logic = Files.createDirectories(root.resolve("buildSrc/src/main/kotlin"))
        Files.writeString(logic.resolve("Convention.kt"), "class Convention\n")
        assertEquals("stale", ProvenanceVerifier.verify(catalog.provenance, root, catalog.scope, catalogBindings).status)
    }
}
