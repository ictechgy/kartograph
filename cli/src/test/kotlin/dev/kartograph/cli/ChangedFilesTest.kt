package dev.kartograph.cli

import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ChangedFilesTest {
    @Test
    fun `since combines committed worktree staged and untracked paths with nul parsing`(@TempDir root: Path) {
        git(root, "init")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Test")
        root.resolve("committed.kt").writeText("base")
        root.resolve("work tree.kt").writeText("base")
        root.resolve("한글.kt").writeText("base")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")
        val base = git(root, "rev-parse", "HEAD").trim()
        root.resolve("committed.kt").writeText("branch")
        git(root, "commit", "-am", "branch")
        root.resolve("work tree.kt").writeText("changed")
        root.resolve("한글.kt").writeText("staged")
        git(root, "add", "한글.kt")
        root.resolve("nested").createDirectories().resolve("new file.kt").writeText("new")
        root.resolve("line\nbreak.kt").writeText("new")

        val changed = ChangedFiles.since(base, root.resolve("nested"))

        assertEquals(
            setOf("committed.kt", "work tree.kt", "한글.kt", "nested/new file.kt", "line\nbreak.kt")
                .map(root.toRealPath()::resolve)
                .toSet(),
            changed,
        )
    }

    @Test
    fun `git failures do not expose repository paths or raw references`(@TempDir root: Path) {
        val reference = "missing-sensitive-reference"

        val error = assertFailsWith<ChangedFilesException> { ChangedFiles.since(reference, root) }

        assertFalse(error.message.orEmpty().contains(root.toString()))
        assertFalse(error.message.orEmpty().contains(reference))
    }

    @Test
    fun `change matching is exact when possible and conservative without source location`(@TempDir root: Path) {
        val changed = setOf(root.resolve("feature/src/Foo.kt"), root.resolve("other/src/Bar.kt"))

        assertEquals(
            true,
            Finding(NodeId("class:feature/Foo"), SourceLocation("feature/src/Foo.kt")).matchesChangedFiles(changed, root),
        )
        assertEquals(
            true,
            Finding(NodeId("class:feature/Foo"), SourceLocation("Foo.kt")).matchesChangedFiles(changed, root),
        )
        assertEquals(true, Finding(NodeId("class:fixture/Unknown"), null).matchesChangedFiles(changed, root))
        assertEquals(
            false,
            Finding(NodeId("class:other/Foo"), SourceLocation("missing/src/Foo.kt")).matchesChangedFiles(changed, root),
        )
    }

    @Test
    fun `unique source basename resolves exactly and ambiguous basenames stay conservative`(@TempDir root: Path) {
        val changedModuleFile = root.resolve("moduleA/src/Helper.kt")
        val unchangedModuleFile = root.resolve("moduleB/src/Helper.kt")
        val finding = Finding(NodeId("class:m/Helper"), SourceLocation("Helper.kt"))

        val uniqueUnchanged = mapOf("Helper.kt" to setOf(unchangedModuleFile))
        assertFalse(finding.matchesChangedFiles(setOf(changedModuleFile), root, uniqueUnchanged))

        val uniqueChanged = mapOf("Helper.kt" to setOf(changedModuleFile))
        assertTrue(finding.matchesChangedFiles(setOf(changedModuleFile), root, uniqueChanged))

        val ambiguous = mapOf("Helper.kt" to setOf(changedModuleFile, unchangedModuleFile))
        assertTrue(finding.matchesChangedFiles(setOf(changedModuleFile), root, ambiguous))
    }

    @Test
    fun `source path index groups tracked source names and prunes build outputs`(@TempDir root: Path) {
        root.resolve("src/main").createDirectories().resolve("Sample.kt").writeText("class Sample")
        root.resolve("src/test").createDirectories().resolve("SampleTest.java").writeText("class SampleTest {}")
        root.resolve("build/generated").createDirectories().resolve("Sample.kt").writeText("generated")
        root.resolve("node_modules/pkg").createDirectories().resolve("Sample.kt").writeText("dependency")

        val index = SourcePaths.byFileName(root.toRealPath())

        assertEquals(setOf(root.toRealPath().resolve("src/main/Sample.kt")), index["Sample.kt"])
        assertEquals(setOf(root.toRealPath().resolve("src/test/SampleTest.java")), index["SampleTest.java"])
        assertFalse(index.containsKey("not-a-source.txt"))
    }

    @Test
    fun `nested project source paths match repository root git output`(@TempDir root: Path) {
        git(root, "init")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Test")
        val project = root.resolve("android/app").createDirectories()
        val source = project.resolve("src/main/Foo.kt")
        source.parent.createDirectories()
        source.writeText("base")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")
        val base = git(root, "rev-parse", "HEAD").trim()
        source.writeText("changed")

        val changed = ChangedFiles.since(base, project)
        val finding = Finding(NodeId("class:fixture/Foo"), SourceLocation("src/main/Foo.kt"))

        assertEquals(true, finding.matchesChangedFiles(changed, project.toRealPath()))
    }

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { output }
        return output
    }
}
