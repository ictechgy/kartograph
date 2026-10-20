package dev.kartograph.cli

import java.io.IOException
import java.nio.file.Path

/**
 * 순회 문서의 `revision`을 git에서 확인한다.
 *
 * git 접근은 CLI 계층에만 둔다([ChangedFiles]와 같은 이유). HEAD는 작업 트리가 그 커밋과 같을 때만 분석한 소스의
 * 신원이다. 고친 파일이 있는데 HEAD를 실으면 isthmus는 다른 분석과 revision이 같다는 이유로 낡은 분석을 최신으로
 * 판단한다. 그래서 프로젝트 디렉터리 아래가 깨끗할 때만 싣고, 아니면 뺀다.
 *
 * 모든 호출이 [GitCommand]의 격리를 지나 저장소 설정의 filter 드라이버를 실행하지 않는다(설계 결정 D1). 그래서 git-lfs처럼
 * filter로 정규화되던 파일은 stat 정보가 낡으면 바뀐 것으로 보이고, 그때는 깨끗하다고 주장하지 않는다(fail-safe).
 */
internal object GitRevision {
    /**
     * [inspect]의 결과다.
     *
     * @property revision 프로젝트 아래가 깨끗할 때의 HEAD 커밋 id. 싣지 않으면 null이다
     * @property limitation revision을 싣지 않은 이유가 git 격리(서브모듈 안이나 덮을 수 없는 설정의 git을 실행하지 않음)일 때
     *   순회 문서에 남길 한계 문구다. 막은 사실을 조용히 넘기지 않기 위해서다(설계 원칙 1). 작업 트리가 더럽거나 저장소가
     *   아니거나 git을 쓸 수 없어서 뺀 경우는 문서화된 생략이라 null이다
     */
    data class Inspection(val revision: String?, val limitation: String?)

    /** [inspect]의 revision이다. */
    fun cleanHead(project: Path): String? = inspect(project).revision

    /**
     * 프로젝트 디렉터리 아래에 커밋하지 않은 변경(추적되지 않는 파일 포함)이 없을 때의 HEAD 커밋 id와, 싣지 못한 이유가
     * git 격리일 때의 한계 문구다.
     *
     * 저장소가 아니거나 HEAD가 없거나(첫 커밋 전) git을 실행할 수 없으면 revision이 null이다. git 설정의 filter 명령을 막을 수
     * 없거나(덮을 수 없는 드라이버 이름, 읽지 못한 설정) 프로젝트 아래에 서브모듈이 있어 깨끗함을 증명할 수 없을 때도 null이고
     * 그 이유를 [Inspection.limitation]에 둔다. 어느 쪽이든 revision을 모르는 것이지 순회 실패가 아니므로 오류로 올리지 않는다.
     * 사용자는 `--revision`으로 줄 수 있다.
     *
     * @param project `--project`의 realpath다. 경로 명세 `.`은 이 디렉터리라 저장소의 다른 곳의 변경은 무시한다
     */
    fun inspect(project: Path): Inspection = try {
        inspectWorkingTree(project)
    } catch (_: GitIsolationException) {
        // git 설정의 filter 드라이버를 덮을 수 없거나 설정을 읽지 못해 git을 시작하지 않았다(fail-closed). 그 사실을 한계로 알린다.
        Inspection(null, UNISOLATED_CONFIGURATION)
    }

    /** [inspect]의 본체다. git을 격리할 수 없으면 [GitIsolationException]을 던진다. */
    private fun inspectWorkingTree(project: Path): Inspection {
        val head = runGit(project, listOf("rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"))
            ?.trim()?.takeIf(::isObjectId) ?: return UNKNOWN
        // 서브모듈 작업 트리가 깨끗한지는 그 안에서 서브모듈 저장소의 설정(filter 등)을 따르는 git을 띄워야만 안다. 그
        // 설정을 실행하지 않고는 깨끗함을 증명할 수 없으므로 index에 gitlink가 있으면 revision을 싣지 않는다. ls-files는
        // 작업 트리 내용을 읽지 않아 filter를 부르지 않는다.
        val index = runGit(project, listOf("ls-files", "--stage", "-z", "--", ".")) ?: return UNKNOWN
        if (index.split('\u0000').any { entry -> entry.startsWith(GITLINK_MODE) }) return Inspection(null, UNINSPECTED_SUBMODULES)
        // --no-optional-locks: 읽기 전용 질의가 index를 갱신하려고 잠그지 않게 한다.
        // --ignore-submodules=all: 서브모듈 안에서 git을 띄우지 않는다(위에서 gitlink가 없음을 확인했다).
        val dirty = runGit(project, listOf("--no-optional-locks", "status", "--porcelain", "--untracked-files=normal",
            "--ignore-submodules=all", "-z", "--", ".")) ?: return UNKNOWN
        return Inspection(head.takeIf { dirty.isEmpty() }, null)
    }

    /** revision을 모르고 알릴 한계도 없는 결과다(저장소가 아님, HEAD 없음, git 사용 불가, git 실패). */
    private val UNKNOWN = Inspection(null, null)

    /** 프로젝트 아래에 서브모듈이 있어 revision을 싣지 않았을 때의 한계 문구다. 경로·이름 원문을 싣지 않는다. */
    private const val UNINSPECTED_SUBMODULES = "revision-unverified: the project contains git submodules, and kartograph does not " +
        "inspect their working trees because that would run commands from their repository configuration; " +
        "pass --revision to record the analyzed commit"

    /** git 설정을 격리할 수 없어 git을 시작하지 않았을 때의 한계 문구다. 드라이버 이름 원문을 싣지 않는다. */
    private const val UNISOLATED_CONFIGURATION = "revision-unverified: the git configuration could not be read or defines a filter " +
        "driver whose name cannot be safely overridden, so kartograph did not run git; pass --revision to record the analyzed commit"

    /** `ls-files --stage` 항목(`<mode> <object> <stage>\t<path>`) 중 gitlink(서브모듈)의 mode다. */
    private const val GITLINK_MODE = "160000 "

    /** SHA-1(40자)·SHA-256(64자) 소문자 hex 커밋 id인지다. */
    private fun isObjectId(value: String): Boolean = Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(value)

    /**
     * 실패하면 null이다. stderr는 [GitCommand.start]가 버린다.
     *
     * @throws GitIsolationException git 설정의 filter 드라이버를 덮을 수 없어 git을 시작하지 않았을 때. [inspect]가 한계로 바꾼다
     */
    private fun runGit(workingDirectory: Path, arguments: List<String>): String? {
        val process = try {
            GitCommand.start(workingDirectory, arguments)
        } catch (isolation: GitIsolationException) {
            throw isolation
        } catch (_: IOException) {
            // PATH의 절대경로 항목에 git이 없거나 시작할 수 없는 환경이다. revision을 모른다고 두고 순회는 계속한다.
            return null
        }
        val stdout = process.inputStream.readAllBytes()
        val status = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            return null
        }
        return if (status == 0) stdout.toString(Charsets.UTF_8) else null
    }
}
