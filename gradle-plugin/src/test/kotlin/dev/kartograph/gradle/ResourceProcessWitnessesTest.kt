package dev.kartograph.gradle

import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.index.ProvenanceVerifier
import dev.kartograph.index.ContentFingerprint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class ResourceProcessWitnessesTest {
    @Test
    fun `resource producer fingerprints XML bytes and membership while preserving legacy inputs`(@TempDir root: Path) {
        val project = ProjectBuilder.builder().withProjectDir(root.toFile()).build()
        val resources = Files.createDirectories(root.resolve("src/main/res/layout"))
        val xml = Files.writeString(resources.resolve("screen.xml"), "<TextView text=\"before\"/>")
        Files.writeString(root.resolve("AndroidManifest.xml"), "<manifest/>")
        val build = Files.writeString(root.resolve("build.gradle"), "// fixture")
        val boot = Files.writeString(root.resolve("android.jar"), "platform")
        val jar = Files.writeString(root.resolve("R.jar"), "classes")
        val resRoot = resources.parent
        val spec = ResourceProcessWitnesses.Spec(root.toFile(), ":debug", ":processDebugResources",
            project.providers.provider { "dev.example" }, project.files(resRoot),
            project.layout.projectDirectory.file("AndroidManifest.xml").let { project.providers.provider { it } },
            project.files(build), project.files(boot), project.providers.provider { jar.toFile() },
            project.layout.buildDirectory.file("witness.json"))
        val before = spec.witness()
        val resource = before.inputs.single { it.role == ResourceProcessWitnesses.RESOURCE_ROLE }
        assertEquals(ContentFingerprint.hashInput(resRoot, "android-resources"), resource.sha256)
        assertEquals("src/main/res", resource.path)
        assertTrue(before.inputs.any { it.role == "sources" && it.path == "AndroidManifest.xml" })
        assertEquals(listOf(ResourceProcessWitnesses.INVENTORY_UNVERIFIED),
            ResourceProcessWitnesses.resourceLimitations(listOf(before)))

        fun provenance() = SnapshotProvenance(before.outputs + listOf(
            ContentFingerprint.capture(root, build, "witness", "witness"),
            ContentFingerprint.capture(root, resRoot, "directory-watch", "current-resource-watch"),
        ), listOf(before))
        assertEquals("matched", ProvenanceVerifier.verify(provenance(), root, ":debug").status)
        val legacyBefore = before.copy(inputs = before.inputs.filterNot { it.role == "android-resources" })
        assertEquals("matched", ProvenanceVerifier.verify(provenance().copy(witnesses = listOf(legacyBefore)), root, ":debug").status)
        Files.writeString(xml, "<TextView text=\"after!\"/>")
        // 현재 watch를 다시 캡처해도 과거 생산자의 XML 지문이 바뀌면 거부해야 한다.
        val stale = ProvenanceVerifier.verify(provenance(), root, ":debug")
        assertEquals("stale", stale.status)
        assertEquals(listOf("changed-android-resources"), stale.reasons)
        val edited = spec.witness()
        assertEquals(before.inputs.filter { it.role == "sources" }, edited.inputs.filter { it.role == "sources" })
        assertNotEquals(resource.sha256, edited.inputs.single { it.role == "android-resources" }.sha256)
        val added = Files.writeString(resources.resolve("extra.xml"), "<TextView/>")
        val withMember = spec.witness().inputs.single { it.role == "android-resources" }
        assertNotEquals(edited.inputs.single { it.role == "android-resources" }, withMember)
        Files.delete(added)
        assertEquals(edited.inputs.single { it.role == "android-resources" },
            spec.witness().inputs.single { it.role == "android-resources" })
        Files.delete(xml)
        assertNotEquals(edited.inputs.single { it.role == "android-resources" },
            spec.witness().inputs.single { it.role == "android-resources" })

        val legacy = before.copy(inputs = before.inputs.filterNot { it.role == "android-resources" })
        assertEquals(listOf(ResourceProcessWitnesses.RESOURCE_UNAVAILABLE),
            ResourceProcessWitnesses.resourceLimitations(listOf(legacy)))
        assertEquals(emptyList(), ResourceProcessWitnesses.resourceLimitations(emptyList()))
    }

    @Test
    fun `external resource roots use distinct location identities even with identical XML`(@TempDir root: Path) {
        val projectRoot = Files.createDirectories(root.resolve("project"))
        val project = ProjectBuilder.builder().withProjectDir(projectRoot.toFile()).build()
        val roots = listOf("first", "second").map { name ->
            Files.createDirectories(root.resolve(name)).also { Files.writeString(it.resolve("screen.xml"), "<TextView/>") }
        } + listOf(root.resolve("absent"))
        Files.writeString(projectRoot.resolve("AndroidManifest.xml"), "<manifest/>")
        val build = Files.writeString(projectRoot.resolve("build.gradle"), "// fixture")
        val boot = Files.writeString(projectRoot.resolve("android.jar"), "platform")
        val jar = Files.writeString(projectRoot.resolve("R.jar"), "classes")
        val spec = ResourceProcessWitnesses.Spec(projectRoot.toFile(), ":debug", ":processDebugResources",
            project.providers.provider { "dev.example" }, project.files(roots),
            project.layout.projectDirectory.file("AndroidManifest.xml").let { project.providers.provider { it } },
            project.files(build), project.files(boot), project.providers.provider { jar.toFile() },
            project.layout.buildDirectory.file("witness.json"))
        val witness = spec.witness()
        val inputs = witness.inputs.filter { it.role == "android-resources" }
        assertEquals(3, inputs.map { it.path }.toSet().size)
        assertEquals(1, inputs.take(2).map { it.sha256 }.toSet().size)
        val binder = CompilerInputBinder(projectRoot, listOf(roots))
        inputs.zip(roots).forEach { (input, expected) ->
            assertEquals("external/" + CompilerDirectoryInput.slot(projectRoot, expected), input.path)
            val located = roots.filter { input.path == "external/" + CompilerDirectoryInput.slot(projectRoot, it) }
            assertEquals(expected, binder.bind(witness, input, located, emptySet()))
        }
        val external = inputs.zip(roots).associate { (input, path) -> input.path to path }
        fun provenance() = SnapshotProvenance(witness.outputs + listOf(
            ContentFingerprint.capture(projectRoot, build, "witness", "witness"),
            ContentFingerprint.capture(projectRoot, roots.last(), "directory-watch", "current-absent-root"),
        ), listOf(witness))
        val absentSlot = "external/current-absent-root"
        val bindings = external + (absentSlot to roots.last())
        assertEquals("matched", ProvenanceVerifier.verify(provenance(), projectRoot, ":debug", bindings).status)
        Files.createDirectories(roots.last())
        // 비어 있음과 없음은 같지만 새 XML 멤버는 이전 생산자의 입력과 다르다.
        assertEquals(inputs.last().sha256, spec.witness().inputs.last { it.role == "android-resources" }.sha256)
        Files.writeString(roots.last().resolve("added.xml"), "<TextView/>")
        val stale = ProvenanceVerifier.verify(provenance(), projectRoot, ":debug", bindings)
        assertEquals("stale", stale.status)
        assertEquals(listOf("changed-android-resources"), stale.reasons)
        val link = Files.createSymbolicLink(root.resolve("resource-link"), roots.first())
        assertFailsWith<IllegalArgumentException> { ContentFingerprint.hashInput(link, "android-resources") }
    }
}
