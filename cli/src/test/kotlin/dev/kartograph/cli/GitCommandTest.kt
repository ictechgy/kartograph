package dev.kartograph.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/** git 실행 경계: PATH에서 찾은 절대경로와 저장소 설정의 명령을 끄는 argv다(설계 결정 D1). */
class GitCommandTest {
    @Test
    fun `command disables repository commands before the working directory and arguments`() {
        val directory = Path.of("/work/project")

        assertEquals(
            listOf("/usr/bin/git", "-c", "core.fsmonitor=false", "-c", "log.showSignature=false", "-C", directory.toString(),
                "status", "--porcelain"),
            GitCommand.command("/usr/bin/git", directory, emptyList(), listOf("status", "--porcelain")),
        )
        // 드라이버마다 세 명령을 빈 값으로, required를 false로 덮는다. 주어진 이름 순서를 그대로 쓴다.
        assertEquals(
            listOf("/usr/bin/git", "-c", "core.fsmonitor=false", "-c", "log.showSignature=false",
                "-c", "filter..clean=", "-c", "filter..smudge=", "-c", "filter..process=", "-c", "filter..required=false",
                "-c", "filter.Evil.Name.clean=", "-c", "filter.Evil.Name.smudge=", "-c", "filter.Evil.Name.process=",
                "-c", "filter.Evil.Name.required=false",
                "-C", directory.toString(), "-c", "diff.autoRefreshIndex=false", "diff"),
            GitCommand.command("/usr/bin/git", directory, listOf("", "Evil.Name"), listOf("-c", "diff.autoRefreshIndex=false", "diff")),
        )
    }

    @Test
    fun `configuration query uses the same isolation and lists only key names`() {
        val directory = Path.of("/work/project")

        assertEquals(
            listOf("/usr/bin/git", "-c", "core.fsmonitor=false", "-c", "log.showSignature=false", "-C", directory.toString(),
                "config", "-z", "--name-only", "--includes", "--list"),
            GitCommand.configurationQuery("/usr/bin/git", directory),
        )
    }

    @Test
    fun `filter driver names keep dots case and the empty name and skip keys without a driver command`() {
        val keys = listOf(
            "core.bare",
            "filter.Evil.Name.clean",
            "filter.up.smudge",
            // subsection이 없는 [filter] clean은 드라이버가 아니고, required만 있는 드라이버에는 실행할 명령이 없다.
            "filter.clean",
            "filter.lf.required",
            "filter..process",
            "diff.t.textconv",
            "filter.Evil.Name.process",
            "filter.a+b-c_d.clean",
        ).joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8) +
            // 다른 섹션의 깨진 UTF-8 바이트와, 끝 NUL이 빠진 마지막 레코드도 바이트 그대로 읽는다.
            byteArrayOf(0x75, 0x2e, 0xff.toByte(), 0x00) + "filter.tail.clean".toByteArray(Charsets.US_ASCII)

        assertEquals(listOf("", "Evil.Name", "a+b-c_d", "tail", "up"), GitCommand.filterDriverNames(keys))
        assertEquals(emptyList(), GitCommand.filterDriverNames(ByteArray(0)))
    }

    @Test
    fun `filter driver names that git cannot override fail closed without echoing the name`() {
        listOf(
            "filter.a=b.clean".toByteArray(Charsets.UTF_8),
            "filter.evil name.smudge".toByteArray(Charsets.UTF_8),
            "filter.a\"b.clean".toByteArray(Charsets.UTF_8),
            "filter.a\\b.process".toByteArray(Charsets.UTF_8),
            "filter.évil.clean".toByteArray(Charsets.UTF_8),
            "filter.".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xff.toByte()) + ".clean".toByteArray(Charsets.US_ASCII),
        ).forEach { key ->
            val error = assertFailsWith<GitIsolationException> {
                GitCommand.filterDriverNames("core.bare\u0000".toByteArray(Charsets.UTF_8) + key + 0.toByte())
            }
            val name = String(key, Charsets.ISO_8859_1).removePrefix("filter.").substringBeforeLast('.')
            assertContains(error.message.orEmpty(), "filter driver whose name cannot be safely overridden")
            assertFalse(error.message.orEmpty().contains(name), "the untrusted driver name stays out of the message")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the fake git is a POSIX shell script")
    fun `start overrides the drivers the lookup reported and starts nothing when the lookup fails`(@TempDir root: Path) {
        val log = root.resolve("calls.txt")
        // 호출마다 인자를 한 줄로 남긴다. 드라이버 조회(config)에는 evil 드라이버 하나를 알린다.
        val reporting = fakeGit(root.resolve("reporting-git"), log, "printf 'core.bare\\000filter.evil.clean\\000'")
        assertEquals(0, GitCommand.start(reporting.toString(), root, listOf("status", "--porcelain")).waitFor())
        val calls = Files.readAllLines(log)
        assertEquals(2, calls.size, calls.toString())
        assertContains(calls[0], "-C $root config -z --name-only --includes --list")
        assertContains(calls[1], "-c filter.evil.clean= -c filter.evil.smudge= -c filter.evil.process= " +
            "-c filter.evil.required=false -C $root status --porcelain")

        // 조회만 실패하는 git(예: --name-only를 모르는 옛 git)이면 분석용 git을 시작하지 않는다(fail-closed).
        Files.delete(log)
        val failing = fakeGit(root.resolve("failing-git"), log, "exit 129")
        val error = assertFailsWith<GitIsolationException> { GitCommand.start(failing.toString(), root, listOf("status")) }
        assertContains(error.message.orEmpty(), "unable to read the git configuration")
        assertEquals(1, Files.readAllLines(log).size, "only the lookup ran")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "the fake git is a POSIX shell script")
    fun `every git process runs with transports and lazy fetch turned off`(@TempDir root: Path) {
        val log = root.resolve("environment.txt")
        // 환경 변수가 비어 있으면 빈 칸, 없으면 unset을 남긴다. 드라이버 조회와 분석용 git 모두 같아야 한다.
        val git = root.resolve("environment-git")
        Files.writeString(git, "#!/bin/sh\nprintf '[%s] [%s] %s\\n' \"\${GIT_ALLOW_PROTOCOL-unset}\" \"\${GIT_NO_LAZY_FETCH-unset}\" " +
            "\"\$*\" >> '$log'\nexit 0\n")
        check(git.toFile().setExecutable(true)) { "cannot mark the fake git executable" }

        assertEquals(0, GitCommand.start(git.toString(), root, listOf("status", "--porcelain")).waitFor())

        val calls = Files.readAllLines(log)
        assertEquals(2, calls.size, calls.toString())
        calls.forEach { call -> assertContains(call, "[] [1] ") }
    }

    @Test
    fun `promisor remotes are recognized from the configuration key names`() {
        fun keys(vararg names: String): ByteArray = names.joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8)

        assertTrue(GitCommand.promisorRemote(keys("core.bare", "extensions.partialclone")))
        // 원격 이름(subsection)은 대소문자를 그대로 두고 나온다.
        assertTrue(GitCommand.promisorRemote(keys("remote.Upstream.promisor")))
        assertTrue(GitCommand.promisorRemote(keys("remote.origin.url", "remote.origin.partialclonefilter")))
        assertFalse(GitCommand.promisorRemote(keys("remote.origin.url", "remote.origin.fetch", "extensions.objectformat")))
        assertFalse(GitCommand.promisorRemote(ByteArray(0)))
    }

    /** 인자를 [log]에 한 줄로 남기고, `config` 하위 명령이면 [onConfig]를 실행하는 가짜 git이다. */
    private fun fakeGit(path: Path, log: Path, onConfig: String): Path {
        Files.writeString(path, "#!/bin/sh\nprintf '%s\\n' \"\$*\" >> '$log'\n" +
            "case \" \$* \" in *\" config \"*) $onConfig ;; esac\nexit 0\n")
        check(path.toFile().setExecutable(true)) { "cannot mark the fake git executable" }
        return path
    }

    @Test
    fun `posix lookup skips empty dot and relative entries and keeps PATH order`() {
        // 빈 항목·`.`·상대경로는 JDK가 현재 디렉터리로 읽는 항목이다. 거기 놓인 git은 후보가 아니다.
        val executables = setOf("/git", "./git", "bin/git", "/opt/git/bin/git", "/usr/bin/git")

        assertEquals(
            "/opt/git/bin/git",
            GitCommand.locate(":.:bin:./tools:/missing:/opt/git/bin/:/usr/bin", false, emptyList(), executables::contains),
        )
        assertNull(GitCommand.locate(":.:bin", false, emptyList(), executables::contains))
        assertNull(GitCommand.locate("", false, emptyList(), executables::contains))
        // PATH가 없으면 JDK 기본 검색(`:/bin:/usr/bin`)에서 현재 디렉터리만 뺀다.
        assertEquals("/usr/bin/git", GitCommand.locate(null, false, emptyList(), executables::contains))
        assertNull(GitCommand.locate(null, true, emptyList(), windowsFiles("C:\\Windows\\System32\\git.exe")))
    }

    @Test
    fun `windows lookup finds git exe only in absolute entries outside the working and project directories`() {
        val executables = windowsFiles(
            "C:\\repo\\git.exe",
            "D:\\launch\\git.exe",
            ".\\git.exe",
            "\\git.exe",
            "tools\\git.exe",
            "C:tools\\git.exe",
            "\\tools\\git.exe",
            "C:\\Git\\git",
            "C:\\Program Files\\Git\\cmd\\git.exe",
            "\\\\server\\share\\git\\git.exe",
        )
        // 실행 위치(D:\launch)와 분석 대상(C:\repo)은 대소문자·구분자가 달라도 같은 디렉터리다.
        val excluded = listOf("D:\\launch", "C:\\repo")
        val path = "c:/REPO/;d:\\Launch;.;;tools;C:tools;\\tools;C:\\Git;\"C:\\Program Files\\Git\\cmd\\\";\\\\server\\share\\git"

        assertEquals("C:\\Program Files\\Git\\cmd\\git.exe", GitCommand.locate(path, true, excluded, executables))
        assertEquals("\\\\server\\share\\git\\git.exe", GitCommand.locate("C:\\repo;\\\\server\\share\\git", true, excluded, executables))
        assertNull(GitCommand.locate("C:\\repo;d:/launch/;C:\\Git;C:tools", true, excluded, executables))
    }

    /** Windows 파일 시스템처럼 대소문자와 구분자를 가리지 않고 실행 파일을 찾는다. */
    private fun windowsFiles(vararg paths: String): (String) -> Boolean {
        fun key(path: String) = path.replace('/', '\\').lowercase()
        val keys = paths.map(::key).toSet()
        return { candidate -> key(candidate) in keys }
    }
}
