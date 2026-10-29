package dev.kartograph.gradle

import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidArtifactCompilersTest {
    @Test
    fun `only selected artifact dependencies contribute regardless of task name`() {
        val project = ProjectBuilder.builder().build()
        val selected = project.tasks.register("unusualGeneratedCompiler", JavaCompile::class.java) {
            it.destinationDirectory.set(project.layout.buildDirectory.dir("arbitrary/output"))
        }
        project.tasks.register("hiltJavaCompileUnselected", JavaCompile::class.java)
        val bridge = project.tasks.register("artifactTransform") { it.dependsOn(selected) }
        val artifacts = project.files(project.layout.buildDirectory.dir("selected")).builtBy(bridge)
        assertEquals(listOf(selected.get()), AndroidArtifactCompilers.contributors(project, artifacts))
        assertEquals(emptyList(), AndroidArtifactCompilers.contributors(project,
            project.files(selected.get().destinationDirectory.get().asFile)))
    }

    @Test
    fun `foreign project compilers are not witnesses for the selected project`() {
        val project = ProjectBuilder.builder().build()
        val foreign = ProjectBuilder.builder().withParent(project).withName("library").build()
        val compiler = foreign.tasks.register("foreignCompiler", JavaCompile::class.java)
        val local = project.tasks.register("localTransform") { it.dependsOn(compiler) }
        assertEquals(emptyList(), AndroidArtifactCompilers.contributors(project,
            project.files(project.layout.buildDirectory.dir("selected")).builtBy(local)))
    }

    @Test
    fun `explicit observation opt out survives later plugin convention`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.withType(KartographAndroidSnapshotTask::class.java).configureEach { it.hiltArtifactObservation.set(false) }
        val task = project.tasks.register("capture", KartographAndroidSnapshotTask::class.java)
        task.configure { it.hiltArtifactObservation.convention(true) }
        assertEquals(false, task.get().hiltArtifactObservation.get())
    }
}
