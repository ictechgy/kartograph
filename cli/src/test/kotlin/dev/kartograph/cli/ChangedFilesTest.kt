package dev.kartograph.cli

import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import dev.kartograph.index.SourcePathIndex
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
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

        val index = SourcePathIndex.byFileName(root.toRealPath())

        assertEquals(setOf(root.toRealPath().resolve("src/main/Sample.kt")), index["Sample.kt"])
        assertEquals(setOf(root.toRealPath().resolve("src/test/SampleTest.java")), index["SampleTest.java"])
        assertFalse(index.containsKey("not-a-source.txt"))
    }

    @Test
    fun `since uniquely resolves basename against the real git changed set without under-reporting`(@TempDir root: Path) {
        git(root, "init")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Test")
        val source = root.resolve("src").createDirectories()
        source.resolve("Changed.kt").writeText("base")
        source.resolve("Stable.kt").writeText("base")
        git(root, "add", ".")
        git(root, "commit", "-m", "base")
        val base = git(root, "rev-parse", "HEAD").trim()
        source.resolve("Changed.kt").writeText("modified")

        val projectReal = root.toRealPath()
        val changed = ChangedFiles.since(base, root)
        val sourcePaths = SourcePathIndex.byFileName(projectReal)

        // realpath 기준이 git changed 집합과 일치해 변경된 유일 source는 포함된다(under-reporting 없음).
        assertTrue(
            Finding(NodeId("class:c"), SourceLocation("Changed.kt"))
                .matchesChangedFiles(changed, projectReal, sourcePaths),
        )
        // 변경되지 않은 유일 source는 basename이 우연히 같아도 정확히 제외된다.
        assertFalse(
            Finding(NodeId("class:s"), SourceLocation("Stable.kt"))
                .matchesChangedFiles(changed, projectReal, sourcePaths),
        )
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

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the fsmonitor marker hook is a POSIX shell script")
    fun `since keeps the changed set without running the repository fsmonitor command`(@TempDir root: Path) {
        val repository = root.resolve("repository").createDirectories()
        git(repository, "init")
        git(repository, "config", "user.email", "test@example.invalid")
        git(repository, "config", "user.name", "Test")
        repository.resolve("committed.kt").writeText("base")
        repository.resolve("edited.kt").writeText("base")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "base")
        val base = git(repository, "rev-parse", "HEAD").trim()
        repository.resolve("committed.kt").writeText("branch")
        git(repository, "commit", "-am", "branch")
        repository.resolve("edited.kt").writeText("changed")
        repository.resolve("untracked.kt").writeText("new")
        // 압축본으로 받은 저장소의 .git/config에 실린 명령을 흉내 낸다. status·diff·ls-files가 index를 읽을 때 실행된다.
        val probe = FsmonitorHookProbe(root, repository)

        val changed = ChangedFiles.since(base, repository)

        assertEquals(setOf("committed.kt", "edited.kt", "untracked.kt").map(repository.toRealPath()::resolve).toSet(), changed)
        assertFalse(probe.hookRan, "kartograph must not run the analyzed repository's core.fsmonitor command")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `since does not run filter drivers configured by the analyzed repository`(@TempDir root: Path) {
        // 커밋된 .gitattributes와 .git/info/attributes, 점이 든 이름, process 키가 모두 같은 경계를 지난다.
        listOf(
            Triple("evil", "clean", false),
            Triple("evil", "clean", true),
            Triple("evil.name", "clean", false),
            Triple("evil", "process", true),
        ).forEachIndexed { index, (name, key, infoAttributes) ->
            val repository = root.resolve("repository-$index")
            val attributes = if (infoAttributes) emptyMap() else mapOf(".gitattributes" to "* filter=$name\n")
            GitFixture.commit(repository, mapOf("src/A.kt" to "class A\n", "committed.kt" to "base", "edited.kt" to "base") + attributes)
            val base = GitFixture.git(repository, "rev-parse", "HEAD")
            repository.resolve("committed.kt").writeText("branch")
            GitFixture.git(repository, "commit", "-q", "-am", "branch")
            repository.resolve("edited.kt").writeText("changed")
            repository.resolve("untracked.kt").writeText("new")
            val probe = FilterDriverProbe(root, repository, name, key, infoAttributes, stale = listOf(repository.resolve("src/A.kt")))

            val changed = ChangedFiles.since(base, repository)

            assertFalse(probe.ran, "kartograph must not run the analyzed repository's filter driver ($name.$key)")
            // 작업 트리 diff는 stat이 낡은 파일의 내용을 다시 읽어 걸러 내지 않으므로 src/A.kt도 바뀐 경로로 남는다(넓히는 쪽).
            assertEquals(
                setOf("committed.kt", "edited.kt", "untracked.kt", "src/A.kt").map(repository.toRealPath()::resolve).toSet(),
                changed,
                "$name.$key",
            )
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `since compares racily clean entries without the repository filter driver`(@TempDir root: Path) {
        val repository = root.resolve("repository")
        GitFixture.commit(repository, mapOf("src/A.kt" to "class A\n", "edited.kt" to "base"))
        repository.resolve("edited.kt").writeText("changed")
        // index 파일이 항목보다 오래되면 git은 stat이 그대로인 항목도 내용을 다시 읽는다. 작업 트리 diff가 index를 고쳐
        // 쓰지 않아도(diff.autoRefreshIndex=false) 이 대조에서 드라이버가 돈다.
        GitFixture.racyIndex(repository)
        val probe = FilterDriverProbe(root, repository, "evil", infoAttributes = true, stale = emptyList())

        val changed = ChangedFiles.since("HEAD", repository)

        assertFalse(probe.ran, "kartograph must not run the analyzed repository's filter driver for racy entries")
        // 드라이버 없이 내용을 대조하므로 내용이 그대로인 src/A.kt는 바뀐 경로가 아니다.
        assertEquals(setOf(repository.toRealPath().resolve("edited.kt")), changed)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the index hook and the filter are POSIX shell commands")
    fun `since leaves the index alone and keeps stat-only and filter-normalized files changed`(@TempDir root: Path) {
        val repository = root.resolve("repository")
        // git-lfs처럼 저장소가 믿는 정규화 filter다. 커밋할 때 돌아서 blob은 대문자로 저장된다.
        GitFixture.commit(
            repository,
            mapOf(".gitattributes" to "*.txt filter=upper\n", "notes.txt" to "hello\n", "same.kt" to "same\n"),
            "filter.upper.clean" to "tr a-z A-Z",
        )
        val hookMarker = root.resolve("post-index-change-ran")
        val hook = repository.resolve(".git/hooks").createDirectories().resolve("post-index-change")
        hook.writeText("#!/bin/sh\n: > '$hookMarker'\n")
        check(hook.toFile().setExecutable(true)) { "cannot mark the hook executable" }
        val stale = listOf(repository.resolve("notes.txt"), repository.resolve("same.kt"))
        GitFixture.stale(stale, GitFixture.STALE_TIME)
        // 일반 git diff는 stat이 낡은 파일의 내용을 다시 읽어 걸러 낸 뒤 index를 고쳐 쓰며 훅을 실행한다(헛통과 방지 확인).
        GitFixture.git(repository, "-c", "core.hooksPath=${hook.parent}", "diff", "--name-only", "HEAD")
        check(Files.exists(hookMarker)) { "plain git diff did not run post-index-change; this test cannot observe the boundary" }
        Files.delete(hookMarker)
        GitFixture.stale(stale, GitFixture.RESTALE_TIME)

        val changed = ChangedFiles.since("HEAD", repository)

        // 내용을 다시 읽지 않으므로 stat만 바뀐 same.kt와 정규화 filter로 blob과 같아지던 notes.txt가 바뀐 경로로 남는다.
        assertEquals(setOf("notes.txt", "same.kt").map(repository.toRealPath()::resolve).toSet(), changed)
        assertFalse(Files.exists(hookMarker), "kartograph must not rewrite the analyzed repository's index or run its hooks")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `since refuses to run git when a filter driver name cannot be overridden`(@TempDir root: Path) {
        // git은 -c 값을 첫 '='에서 나누므로 "a=b"는 덮을 수 없다. 비ASCII 이름은 JVM이 argv를 로캘 인코딩으로 바꿔 다른
        // 이름을 덮게 될 수 있어 허용하지 않는다.
        val repositories = listOf("a=b", "évil").mapIndexed { index, name ->
            val repository = root.resolve("repository-$index")
            GitFixture.commit(repository, mapOf("src/A.kt" to "class A\n"))
            val probe = FilterDriverProbe(root, repository, name, infoAttributes = true, stale = listOf(repository.resolve("src/A.kt")))

            val error = assertFailsWith<ChangedFilesException> { ChangedFiles.since("HEAD", repository) }

            assertContains(error.message.orEmpty(), "filter driver whose name cannot be safely overridden")
            assertContains(error.message.orEmpty(), "--since")
            assertFalse(error.message.orEmpty().contains(name), "the untrusted driver name stays out of the message")
            assertFalse(probe.ran, "kartograph must not run git while it cannot override the driver ($name)")
            repository
        }
        // dead --since는 이 실패를 도구 실패(exit 2)로 알린다.
        val project = repositories.first()
        project.resolve("AndroidManifest.xml").writeText("<manifest />")
        project.resolve("res").createDirectories()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(
            arrayOf("dead", "--classes", cliClasses.toString(), "--project", project.toString(), "--manifest", "AndroidManifest.xml",
                "--resources", "res", "--namespace", "dev.kartograph.cli", "--since", "HEAD"),
            PrintStream(ByteArrayOutputStream()),
            PrintStream(error),
        )
        assertEquals(2, status, error.toString())
        assertContains(error.toString(), "error: git configuration defines a filter driver whose name cannot be safely overridden")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the filter driver marker is a POSIX shell script")
    fun `since does not run git inside submodules and keeps a moved submodule commit`(@TempDir root: Path) {
        val library = root.resolve("library")
        GitFixture.commit(library, mapOf("inner.txt" to "inner\n", "edited.txt" to "base\n"))
        val repository = root.resolve("repository")
        GitFixture.commit(repository, mapOf("top.kt" to "top"))
        GitFixture.git(repository, "submodule", "--quiet", "add", library.toString(), "sub")
        GitFixture.git(repository, "commit", "-q", "-m", "submodule")
        val submodule = repository.resolve("sub")
        // 서브모듈 저장소 자신의 config에 건 드라이버다. 상위 저장소의 diff가 서브모듈 안에서 git을 띄우면 실행된다.
        val probe = FilterDriverProbe(root, submodule, "evil", infoAttributes = true, stale = listOf(submodule.resolve("inner.txt")))
        submodule.resolve("edited.txt").writeText("uncommitted\n")

        // 서브모듈 안의 커밋하지 않은 변경은 보지 않는다. 그것을 보려면 서브모듈 저장소의 설정을 따르는 git을 띄워야 한다.
        assertEquals(emptySet(), ChangedFiles.since("HEAD", repository))
        assertFalse(probe.ran, "kartograph must not run git inside the submodule")

        GitFixture.git(submodule, "-c", "filter.evil.clean=", "commit", "-q", "-am", "moved")
        // 기록과 다른 커밋을 가리키는 서브모듈은 지금처럼 바뀐 경로로 남는다.
        assertEquals(setOf(repository.toRealPath().resolve("sub")), ChangedFiles.since("HEAD", repository))
        assertFalse(probe.ran, "kartograph must not run git inside the submodule")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the upload-pack marker is a POSIX shell script")
    fun `since reads a blobless partial clone without fetching objects through the repository remote`(@TempDir root: Path) {
        val source = root.resolve("source")
        val base = commitRename(source)
        val clone = root.resolve("clone")
        // 일반 git diff는 rename을 판정하려고 기준 쪽의 빠진 blob을 lazy fetch하며 clone config의 upload-pack을 실행한다.
        val probe = LazyFetchProbe(root, source, clone, "blob:none", listOf("diff", "--name-only", "$base...HEAD"))

        val changed = ChangedFiles.since(base, clone)

        assertFalse(probe.ran, "kartograph must not fetch missing objects through the analyzed repository's remote")
        // 이름만 내는 diff라 rename 판정 없이도 전체 clone과 같은 경로를 낸다.
        assertEquals(setOf("New.kt", "Keep.kt"), changed.map { clone.toRealPath().relativize(it).toString() }.toSet())
        assertEquals(setOf("New.kt", "Keep.kt"), ChangedFiles.since(base, source).map { source.toRealPath().relativize(it).toString() }.toSet())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the upload-pack marker is a POSIX shell script")
    fun `since fails closed when a partial clone lacks the objects it needs`(@TempDir root: Path) {
        val source = root.resolve("source")
        val base = commitRename(source)
        val clone = root.resolve("clone")
        // tree:0 clone에는 기준 커밋의 tree가 없어 범위 diff가 lazy fetch를 띄워야 한다.
        val probe = LazyFetchProbe(root, source, clone, "tree:0", listOf("diff", "--name-only", "--no-renames", "$base...HEAD"))

        val error = assertFailsWith<ChangedFilesException> { ChangedFiles.since(base, clone) }

        assertFalse(probe.ran, "kartograph must not fetch missing objects through the analyzed repository's remote")
        assertContains(error.message.orEmpty(), "partial clone")
        assertContains(error.message.orEmpty(), "--since")
        assertFalse(error.message.orEmpty().contains(base), "the reference stays out of the message")
    }

    /** 기준 커밋 뒤에 Old.kt를 New.kt로 옮겨 고치고 Keep.kt를 고친 저장소를 만들고 기준 커밋 id를 돌려준다. */
    private fun commitRename(source: Path): String {
        val lines = (1..200).joinToString("\n", postfix = "\n")
        GitFixture.commit(source, mapOf("Old.kt" to lines, "Keep.kt" to "keep\n"))
        val base = GitFixture.git(source, "rev-parse", "HEAD")
        GitFixture.git(source, "mv", "Old.kt", "New.kt")
        Files.writeString(source.resolve("New.kt"), lines + "tail\n")
        Files.writeString(source.resolve("Keep.kt"), "changed\n")
        GitFixture.git(source, "commit", "-q", "-am", "rename")
        return base
    }

    /** `dead`가 읽을 class root다. CLI 자신의 class 출력이다(KartographCliTest와 같은 입력). */
    private val cliClasses: Path
        get() = Path.of(requireNotNull(KartographCli::class.java.protectionDomain.codeSource).location.toURI())

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { output }
        return output
    }
}
