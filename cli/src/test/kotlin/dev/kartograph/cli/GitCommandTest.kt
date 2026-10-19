package dev.kartograph.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** git 실행 경계: PATH에서 찾은 절대경로와 fsmonitor 비활성 argv다. */
class GitCommandTest {
    @Test
    fun `command disables fsmonitor before the working directory and arguments`() {
        val directory = Path.of("/work/project")

        assertEquals(
            listOf("/usr/bin/git", "-c", "core.fsmonitor=false", "-C", directory.toString(), "status", "--porcelain"),
            GitCommand.command("/usr/bin/git", directory, listOf("status", "--porcelain")),
        )
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
