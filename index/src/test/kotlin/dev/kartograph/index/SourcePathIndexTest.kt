package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SourcePathIndexTest {
    @Test
    fun `external file link cannot become a confirmed project source`(@TempDir root: Path) {
        val project = root.resolve("project").createDirectories()
        val outside = root.resolve("Outside.kt")
        outside.writeText("class Outside")
        java.nio.file.Files.createSymbolicLink(project.resolve("Outside.kt"), outside)
        assertEquals(emptyMap(), SourcePathIndex.byFileName(project))
    }

    @Test
    fun `project root alias and internal file links stay inside the root`(@TempDir root: Path) {
        val project = root.resolve("project").createDirectories()
        write(project, "src/Local.kt")
        val alias = root.resolve("alias")
        java.nio.file.Files.createSymbolicLink(alias, project)
        java.nio.file.Files.createSymbolicLink(project.resolve("Linked.kt"), project.resolve("src/Local.kt"))
        assertEquals(setOf(project.toRealPath().resolve("src/Local.kt")), SourcePathIndex.byFileName(alias)["Local.kt"])
        assertEquals(setOf(project.toRealPath().resolve("Linked.kt")), SourcePathIndex.byFileName(alias)["Linked.kt"])
    }

    @Test
    fun `confirms a path only when the declaration package matches the source directory`(@TempDir root: Path) {
        write(root, "src/main/kotlin/app/feature/Screen.kt")
        write(root, "src/main/kotlin/unrelated/Shared.kt")
        val graph = CodeGraph(
            nodes = listOf(
                node("class:app/feature/Screen", "Screen", "Screen.kt"),
                // package가 unrelated가 아니라 external인 선언은 이름만 같을 뿐이므로 확정하지 않는다.
                node("class:external/Shared", "Shared", "Shared.kt"),
            ),
            edges = emptyList(),
        )

        val resolution = SourcePathIndex.resolve(graph, root.toRealPath())

        assertEquals(
            mapOf(NodeId("class:app/feature/Screen") to "src/main/kotlin/app/feature/Screen.kt"),
            resolution.byNodeId,
        )
        assertTrue(resolution.limitations.any { it.startsWith("unresolved-source-paths: 1 of 2") })
    }

    @Test
    fun `confirms a default package declaration without a directory to compare`(@TempDir root: Path) {
        write(root, "src/Entry.kt")
        val graph = CodeGraph(nodes = listOf(node("class:Entry", "Entry", "Entry.kt")), edges = emptyList())

        val resolution = SourcePathIndex.resolve(graph, root.toRealPath())

        assertEquals(mapOf(NodeId("class:Entry") to "src/Entry.kt"), resolution.byNodeId)
        assertEquals(emptyList(), resolution.limitations)
    }

    @Test
    fun `reports nodes without a source file as a measured limitation on its own`(@TempDir root: Path) {
        val graph = CodeGraph(
            nodes = listOf(
                GraphNode(NodeId("class:a/Stripped"), "Stripped", NodeKind.CLASS),
                node("class:a/Located", "Located", "Located.kt"),
            ),
            edges = emptyList(),
        )

        // 경로 해석 없이도 개수만으로 계산되는 한계다.
        assertEquals(
            listOf("missing-source-paths: 1 of 2 node(s) have no source file in the class debug attributes"),
            SourcePathIndex.missingSourcePaths(graph),
        )
        assertTrue(SourcePathIndex.resolve(graph, root.toRealPath()).limitations.any { it.startsWith("missing-source-paths: 1 of 2") })
    }

    @Test
    fun `prunes build outputs and dependency directories from the index`(@TempDir root: Path) {
        write(root, "src/main/kotlin/Sample.kt")
        write(root, "build/generated/Sample.kt")
        write(root, "node_modules/pkg/Sample.kt")
        write(root, ".claude/skills/Sample.kt")
        write(root, ".omx/notes/Sample.kt")
        write(root, ".idea/scratch/Sample.kt")
        write(root, ".gradle/cache/Sample.kt")
        write(root, ".worktrees/copy/Sample.kt")

        val index = SourcePathIndex.byFileName(root.toRealPath())

        assertEquals(setOf(root.toRealPath().resolve("src/main/kotlin/Sample.kt")), index["Sample.kt"])
    }

    @Test
    fun `returns no index for a project root that is not a directory`(@TempDir root: Path) {
        assertEquals(emptyMap(), SourcePathIndex.byFileName(root.resolve("missing")))
    }

    private fun node(id: String, name: String, sourceFile: String) =
        GraphNode(NodeId(id), name, NodeKind.CLASS, location = SourceLocation(sourceFile))

    private fun write(root: Path, relative: String) {
        val target = root.resolve(relative)
        target.parent.createDirectories()
        target.writeText("// fixture")
    }
}
