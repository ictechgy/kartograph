package dev.kartograph.gradle

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class KartographGraphTaskTest {
    @Test
    fun `writes a deterministic exchange document without source paths by default`(@TempDir projectRoot: Path) {
        val task = configuredTask(projectRoot, includeSourcePaths = false)

        task.renderGraph()
        val first = graphDocument(projectRoot)
        task.renderGraph()
        val second = graphDocument(projectRoot)

        assertContains(first, """"format": "code-graph"""")
        assertContains(first, """"version": 1""")
        assertContains(first, """"usr": "class:dev/kartograph/gradle/KartographGraphTaskTest"""")
        // 경로 해석을 요청하지 않았으므로 확정 경로도, 그에 대한 한계도 보고하지 않는다.
        assertFalse(first.contains(""""pathKind": "projectRelative""""))
        assertFalse(first.contains("unresolved-source-paths"))
        assertFalse(first.contains(projectRoot.toString()))
        assertEquals(first, second)
    }

    @Test
    fun `resolves project relative paths only when the build opts in`(@TempDir projectRoot: Path) {
        // task가 읽는 class는 이 test 산출물이므로, 같은 이름의 source를 선언 package 경로에 두면 확정 대상이 된다.
        val source = projectRoot.resolve("src/test/kotlin/dev/kartograph/gradle/KartographGraphTaskTest.kt")
        source.parent.createDirectories()
        source.writeText("class KartographGraphTaskTest")
        val task = configuredTask(projectRoot, includeSourcePaths = true)

        task.renderGraph()

        val document = graphDocument(projectRoot)
        assertContains(
            document,
            """"path": "src/test/kotlin/dev/kartograph/gradle/KartographGraphTaskTest.kt", "pathKind": "projectRelative"""",
        )
        // project 안에 source가 없는 나머지 test class는 확정하지 못하므로 계량된 한계로 보고한다.
        assertContains(document, "unresolved-source-paths: ")
        assertFalse(document.contains(projectRoot.toString()))
    }

    @Test
    fun `the extension keeps source path resolution opt-in`(@TempDir projectRoot: Path) {
        val project = ProjectBuilder.builder().withProjectDir(projectRoot.toFile()).build()
        project.pluginManager.apply(KartographPlugin::class.java)

        val extension = project.extensions.getByType(KartographExtension::class.java)

        assertFalse(extension.includeSourcePaths.get())
    }

    private fun graphDocument(projectRoot: Path): String =
        projectRoot.resolve("build/reports/kartograph/debug-graph.json").readText()

    private fun configuredTask(projectRoot: Path, includeSourcePaths: Boolean): KartographGraphTask {
        val project = ProjectBuilder.builder().withProjectDir(projectRoot.toFile()).build()
        val testClasses = Path.of(requireNotNull(javaClass.protectionDomain.codeSource).location.toURI())
        return project.tasks.register("kartographGraphDebug", KartographGraphTask::class.java).get().apply {
            projectJars.set(emptyList())
            projectDirectories.set(listOf(project.layout.dir(project.provider { testClasses.toFile() }).get()))
            variantName.set("debug")
            this.includeSourcePaths.set(includeSourcePaths)
            projectDirectory.set(project.layout.projectDirectory)
            graphFile.set(project.layout.buildDirectory.file("reports/kartograph/debug-graph.json"))
        }
    }
}
