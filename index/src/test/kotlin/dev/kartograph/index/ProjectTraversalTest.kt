package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectTraversalTest {
    @kotlin.test.Test
    fun `unresolvable source links fail rather than producing partial facts`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        java.nio.file.Files.createSymbolicLink(root.resolve("Missing.kt"), root.resolve("absent.kt"))
        kotlin.test.assertFailsWith<java.io.IOException> { ProjectTraversal.walkSources(root) {} }
        kotlin.test.assertEquals(emptyMap(), SourcePathIndex.byFileName(root))
    }

    @kotlin.test.Test
    fun `source replaced after traversal is checked again before reading`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        val project = java.nio.file.Files.createDirectory(root.resolve("project"))
        val source = java.nio.file.Files.writeString(project.resolve("Local.kt"), "local")
        val outside = java.nio.file.Files.writeString(root.resolve("Outside.kt"), "external")
        val visited = mutableListOf<java.nio.file.Path>()
        ProjectTraversal.walkSources(project) { visited.add(it) }
        java.nio.file.Files.delete(source)
        java.nio.file.Files.createSymbolicLink(source, outside)
        kotlin.test.assertFailsWith<java.io.IOException> { ProjectTraversal.readSourceLines(project, visited.single()) }
    }

    @Test
    fun `shares the truth-source canonical directories in one pruning set`() {
        // docs/DECISION-truth-source.md의 정본 6종이 공유 집합에 그대로 들어간다.
        listOf(".git", ".gradle", ".omx", ".worktrees", ".claude", "node_modules").forEach { directory ->
            assertTrue(directory in ProjectTraversal.PRUNED_DIRECTORY_NAMES, "$directory is not pruned")
        }
    }

    @Test
    fun `excludes assistant directories and test source sets from source traversal`(@TempDir root: Path) {
        root.resolve(".claude/skills").createDirectories()

        assertTrue(ProjectTraversal.isPruned(root, root.resolve(".claude/skills/Fake.kt")))
        assertTrue(ProjectTraversal.isPrunedSource(root, root.resolve(".claude/skills/Fake.kt")))
        assertTrue(ProjectTraversal.isPrunedSource(root, root.resolve("src/test/TestModule.kt")))
        assertFalse(ProjectTraversal.isPrunedSource(root, root.resolve("src/main/kotlin/app/Plugin.kt")))
    }
}
