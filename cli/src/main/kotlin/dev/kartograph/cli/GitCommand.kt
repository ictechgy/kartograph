package dev.kartograph.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * CLI가 git을 시작하는 공용 경계다. [GitRevision]과 [ChangedFiles]가 쓴다.
 *
 * 분석 대상 checkout은 출처를 모르는 압축본일 수 있다. 그래서 그 checkout이 고를 수 있는 실행 두 가지를 막는다.
 * - 실행 파일 이름만 넘기면 Windows CreateProcess는 PATH보다 현재 디렉터리를 먼저 찾고, POSIX의 JDK는 빈 PATH 항목을
 *   현재 디렉터리로 읽는다. 그래서 PATH의 절대경로 항목에서 직접 찾은 절대경로만 실행한다.
 * - 저장소 config의 `core.fsmonitor` 명령은 status·diff·ls-files가 index를 읽을 때 실행된다. 명령줄 `-c`가 저장소
 *   config보다 우선하므로 그것으로 끈다.
 *
 * 그 밖의 저장소 설정(filter·textconv·diff 드라이버 등)은 따른다. 이것을 막을지는 자매 도구와 함께 정할 설계 결정이다.
 */
internal object GitCommand {
    /**
     * [workingDirectory]에서 git을 시작한다. stderr는 버린다. 저장소 밖 경로 같은 원시 git 메시지를 사용자 출력에
     * 섞지 않기 위해서다.
     *
     * @throws IOException PATH에서 git을 찾지 못했거나 시작하지 못했을 때. 호출자는 기존의 "git 사용 불가" 경로를 탄다
     */
    fun start(workingDirectory: Path, arguments: List<String>): Process {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val excluded = if (windows) excludedDirectories(workingDirectory) else emptyList()
        val executable = locate(System.getenv("PATH"), windows, excluded, ::isExecutableFile)
            ?: throw IOException("git is not in an absolute PATH entry")
        return ProcessBuilder(command(executable, workingDirectory, arguments))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /** 실행할 argv다. `-c core.fsmonitor=false`가 저장소 config의 fsmonitor 명령을 덮어쓴다. */
    fun command(executable: String, workingDirectory: Path, arguments: List<String>): List<String> =
        listOf(executable, "-c", "core.fsmonitor=false", "-C", workingDirectory.toString()) + arguments

    /**
     * PATH에서 git 실행 파일의 절대경로를 찾는다. 셸처럼 앞 항목이 이긴다.
     *
     * 빈 항목·`.`·상대경로는 현재 디렉터리 기준이라 건너뛴다. Windows는 `git.exe`를 찾고, 절대경로로 적힌 항목이라도
     * 실행 위치와 분석 대상 디렉터리는 건너뛴다(대소문자와 구분자 차이는 무시한다). OS와 파일 검사를 주입받는 순수
     * 함수라 Windows 규칙도 다른 OS에서 시험할 수 있다.
     *
     * @param pathVariable PATH 값. 없으면 POSIX는 JDK 기본값(`:/bin:/usr/bin`)에서 현재 디렉터리를 뺀 `/bin:/usr/bin`을 쓰고
     *   Windows는 찾지 못한다
     * @param windows Windows 규칙(`;` 구분자, `git.exe`, 디렉터리 제외)을 쓸지
     * @param excludedDirectories Windows에서 건너뛸 디렉터리
     * @param isExecutableFile 후보 경로가 실행할 수 있는 일반 파일인지
     * @return 찾은 git의 절대경로. 없으면 null이다
     */
    fun locate(pathVariable: String?, windows: Boolean, excludedDirectories: List<String>, isExecutableFile: (String) -> Boolean): String? {
        val excluded = excludedDirectories.map(::windowsKey).toSet()
        return (pathVariable ?: if (windows) "" else "/bin:/usr/bin").split(if (windows) ';' else ':')
            .map { entry -> if (windows) entry.trim().removeSurrounding("\"") else entry }
            .filter { entry -> if (windows) WINDOWS_ABSOLUTE.containsMatchIn(entry) && windowsKey(entry) !in excluded else entry.startsWith('/') }
            .map { directory -> if (windows) directory.trimEnd('/', '\\') + "\\git.exe" else directory.trimEnd('/') + "/git" }
            .firstOrNull(isExecutableFile)
    }

    /** 드라이브 절대경로(`C:\`, `C:/`)나 UNC 경로(`\\server\share`)다. `C:dir`·`\dir`는 현재 위치 기준이라 제외한다. */
    private val WINDOWS_ABSOLUTE = Regex("^(?:[A-Za-z]:[\\\\/]|[\\\\/]{2}[^\\\\/])")

    /** Windows 경로 비교 키다. 파일 시스템처럼 대소문자와 구분자, 끝 구분자 차이를 무시한다. */
    private fun windowsKey(path: String): String = path.replace('/', '\\').trimEnd('\\').lowercase(Locale.ROOT)

    /** Windows가 건너뛸 디렉터리다. 실행 위치와 분석 대상의 절대경로·실제 경로를 모두 담는다. */
    private fun excludedDirectories(workingDirectory: Path): List<String> {
        val absolute = workingDirectory.toAbsolutePath().normalize()
        val real = try {
            absolute.toRealPath()
        } catch (_: IOException) {
            // 없는 디렉터리면 git -C가 같은 이유로 실패하므로 주어진 절대경로만 제외한다.
            absolute
        }
        return listOf(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), absolute, real).map(Path::toString)
    }

    /** PATH 후보가 실행할 수 있는 일반 파일인지다. 경로로 읽을 수 없는 PATH 항목에는 git이 있을 수 없다. */
    private fun isExecutableFile(candidate: String): Boolean = try {
        Path.of(candidate).let { Files.isRegularFile(it) && Files.isExecutable(it) }
    } catch (_: InvalidPathException) {
        false
    }
}
