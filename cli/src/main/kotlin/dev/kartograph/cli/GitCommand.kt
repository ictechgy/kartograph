package dev.kartograph.cli

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * CLI가 git을 시작하는 공용 경계다. [GitRevision]과 [ChangedFiles]가 쓴다.
 *
 * 분석 대상 checkout은 출처를 모르는 압축본일 수 있다. 그래서 그 checkout의 설정이 고른 명령을 실행하지 않는다(설계 결정 D1).
 * - 실행 파일 이름만 넘기면 Windows CreateProcess는 PATH보다 현재 디렉터리를 먼저 찾고, POSIX의 JDK는 빈 PATH 항목을
 *   현재 디렉터리로 읽는다. 그래서 PATH의 절대경로 항목에서 직접 찾은 절대경로만 실행한다.
 * - 저장소 config의 `core.fsmonitor` 명령(status·diff·ls-files가 index를 읽을 때)과 `log.showSignature`가 부르는 서명
 *   확인 명령은 명령줄 `-c`로 끈다. 명령줄 `-c`가 저장소 config보다 우선한다.
 * - filter 드라이버(clean·smudge·process)는 git이 작업 트리 파일의 내용을 다시 읽을 때 실행된다. git을 시작할 때마다
 *   바로 앞에서 설정된 드라이버 이름을 읽기 전용으로 다시 모아, 이름마다 세 명령을 빈 값으로, `required`를 false로
 *   덮는다. git은 빈 명령을 실행하지 않는다. `.gitattributes`와 `.git/info/attributes` 어느 쪽이 드라이버를 골라도
 *   막힌다(`--attr-source`는 `.git/info/attributes`를 막지 못한다).
 * - 이름을 덮을 수 없거나 조회가 실패하면 git을 시작하지 않는다(fail-closed, [GitIsolationException]).
 *
 * textconv·외부 diff·작업 트리 diff의 index 자동 갱신·서브모듈은 하위 명령마다 옵션이 달라 호출자가 붙인다.
 * 그 밖의 설정은 범위(시스템·사용자 전역·저장소)와 상관없이 따른다.
 */
internal object GitCommand {
    /**
     * [workingDirectory]에서 git을 시작한다. PATH에서 찾은 git으로 [start]를 부른다.
     *
     * @throws GitIsolationException filter 드라이버 이름을 조회하지 못했거나 덮을 수 없는 이름이 있을 때. git을 시작하지 않았다
     * @throws InterruptedIOException 드라이버 이름 조회를 기다리다 중단됐을 때. 스레드의 중단 표시는 다시 세워 둔다
     * @throws IOException PATH에서 git을 찾지 못했거나 시작하지 못했을 때. 호출자는 기존의 "git 사용 불가" 경로를 탄다
     */
    fun start(workingDirectory: Path, arguments: List<String>): Process {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val excluded = if (windows) excludedDirectories(workingDirectory) else emptyList()
        val executable = locate(System.getenv("PATH"), windows, excluded, ::isExecutableFile)
            ?: throw IOException("git is not in an absolute PATH entry")
        return start(executable, workingDirectory, arguments)
    }

    /**
     * 찾아 둔 [executable]로 filter 드라이버 이름을 조회한 뒤 그 드라이버를 덮은 git을 시작한다. 조회와 실행이 같은
     * 실행 파일·설정·디렉터리를 쓴다. stderr는 버린다. 저장소 밖 경로 같은 원시 git 메시지를 사용자 출력에 섞지 않기
     * 위해서다. 조회만 실패하는 git(예: 옛 git)을 시험이 넘길 수 있게 PATH 탐색과 나눴다.
     *
     * @throws GitIsolationException 조회가 실패했거나 덮을 수 없는 이름이 있을 때. git을 시작하지 않았다
     * @throws InterruptedIOException 조회를 기다리다 중단됐을 때
     * @throws IOException git을 시작하지 못했을 때
     */
    fun start(executable: String, workingDirectory: Path, arguments: List<String>): Process {
        val filterDrivers = filterDriverNames(configurationKeys(executable, workingDirectory))
        return launch(command(executable, workingDirectory, filterDrivers, arguments))
    }

    /**
     * 실행할 argv다. 공통 설정과 드라이버 무력화가 `-C`보다 앞에 온다. [arguments]는 `-c` 같은 전역 옵션으로 시작해도 된다.
     *
     * @param filterDrivers 덮을 드라이버 이름([filterDriverNames]의 결과). 이름마다 clean·smudge·process를 빈 값으로,
     *   required를 false로 둔다. 주어진 순서를 그대로 쓴다
     */
    fun command(executable: String, workingDirectory: Path, filterDrivers: List<String>, arguments: List<String>): List<String> =
        listOf(executable) + ISOLATION + filterDrivers.flatMap(::neutralizingOptions) + listOf("-C", workingDirectory.toString()) + arguments

    /**
     * 설정된 filter 드라이버 이름을 읽는 읽기 전용 조회 argv다. 분석용 git과 같은 실행 파일·공통 설정·디렉터리를 써서
     * 그 git이 읽을 설정(include 포함)을 그대로 본다. `--get-regexp`를 쓰지 않는다. UTF-8 로캘의 git은 정규식 `.`이
     * 깨진 UTF-8 바이트와 맞지 않아 그런 이름의 드라이버를 결과에서 빼지만, 속성은 바이트로 대조해 그 드라이버를 실행한다.
     */
    fun configurationQuery(executable: String, workingDirectory: Path): List<String> =
        command(executable, workingDirectory, emptyList(), listOf("config", "-z", "--name-only", "--includes", "--list"))

    /**
     * `git config -z --name-only --list` 출력에서 명령을 가진 filter 드라이버 이름을 모은다.
     *
     * 레코드는 `key\0`이고 section·변수 이름은 소문자로, subsection은 원래 대소문자로 나온다. `filter.`과 마지막 `.` 사이
     * 전체(점·빈 문자열 포함)가 이름이고 변수가 clean·smudge·process인 것만 모은다. 바이트를 ISO-8859-1로 1:1 보존해
     * 읽어 이름의 경계와 허용 목록 판정을 원래 바이트에서 한다. 비ASCII 바이트가 든 이름은 허용 목록에서 거부된다.
     *
     * @return 정렬·중복 제거한 이름. argv를 결정적으로 만든다
     * @throws GitIsolationException [OVERRIDABLE_DRIVER_NAME] 밖의 이름이 있을 때. 메시지에 이름 원문을 싣지 않는다
     */
    fun filterDriverNames(configurationKeys: ByteArray): List<String> =
        String(configurationKeys, Charsets.ISO_8859_1).split('\u0000').mapNotNull(::filterDriverName).onEach { name ->
            if (!OVERRIDABLE_DRIVER_NAME.matches(name)) throw GitIsolationException(UNOVERRIDABLE_DRIVER)
        }.distinct().sorted()

    /** `filter.<이름>.<명령 키>`의 이름이다. subsection이 없는 `filter.clean`이나 다른 키는 드라이버 명령이 아니다. */
    private fun filterDriverName(key: String): String? {
        if (!key.startsWith(FILTER_SECTION)) return null
        val rest = key.substring(FILTER_SECTION.length)
        val dot = rest.lastIndexOf('.')
        return if (dot >= 0 && rest.substring(dot + 1) in DRIVER_COMMANDS) rest.substring(0, dot) else null
    }

    /** 드라이버 하나를 덮는 `-c` 목록이다. 빈 명령은 실행되지 않고 required=false라 그 파일에서 git이 실패하지도 않는다. */
    private fun neutralizingOptions(name: String): List<String> =
        DRIVER_COMMANDS.flatMap { command -> listOf("-c", "$FILTER_SECTION$name.$command=") } +
            listOf("-c", "$FILTER_SECTION$name.required=false")

    /** [configurationQuery]를 실행해 출력 바이트를 돌려준다. 종료 코드 0만 성공이다(`--list`의 실패는 128이다). */
    private fun configurationKeys(executable: String, workingDirectory: Path): ByteArray {
        val process = launch(configurationQuery(executable, workingDirectory))
        val keys = process.inputStream.readAllBytes()
        val status = try {
            process.waitFor()
        } catch (interruption: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            throw InterruptedIOException("git configuration lookup was interrupted").apply { initCause(interruption) }
        }
        if (status != 0) throw GitIsolationException(UNREADABLE_CONFIGURATION)
        return keys
    }

    private fun launch(argv: List<String>): Process = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    /** 모든 git 호출(드라이버 조회 포함)이 `-C` 앞에 두는 설정이다. fsmonitor 훅과 log 계열의 서명 확인 명령을 끈다. */
    private val ISOLATION = listOf("-c", "core.fsmonitor=false", "-c", "log.showSignature=false")

    private const val FILTER_SECTION = "filter."

    /** git이 작업 트리 파일을 읽고 쓸 때 실행하는 드라이버 명령 키다. */
    private val DRIVER_COMMANDS = listOf("clean", "smudge", "process")

    /**
     * `-c`로 넘겨도 그대로 같은 이름이 되는 드라이버 이름이다. 빈 이름(`[filter ""]`)도 실제로 실행되는 드라이버라 허용한다.
     * 이 밖은 거부한다. `=`는 git이 `-c` 값을 나누는 자리라 덮을 수 없고, 비ASCII는 POSIX JDK가 argv를
     * `sun.jnu.encoding`으로 바꿔 C 로캘에서 `?`가 되며, 공백·따옴표·역슬래시는 Windows 인자 인용 규칙을 탄다.
     */
    private val OVERRIDABLE_DRIVER_NAME = Regex("[A-Za-z0-9._+-]*")

    private const val UNREADABLE_CONFIGURATION =
        "unable to read the git configuration to disable its filter drivers; verify the repository config"

    private const val UNOVERRIDABLE_DRIVER =
        "git configuration defines a filter driver whose name cannot be safely overridden; rename or remove that driver"

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

/**
 * git 설정이 정한 filter 명령을 막을 수 없어 git을 시작하지 않았다는 오류다(fail-closed).
 *
 * [IOException]이라 호출자의 "git을 쓸 수 없음" 경로를 그대로 탄다. 메시지에 드라이버 이름 원문을 싣지 않는다. 신뢰하지
 * 않는 설정 값이고 제어 문자가 섞일 수 있다.
 */
internal class GitIsolationException(message: String) : IOException(message)
