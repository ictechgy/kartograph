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
     * 프로젝트 디렉터리 아래에 커밋하지 않은 변경(추적되지 않는 파일 포함)이 없을 때의 HEAD 커밋 id다.
     *
     * 저장소가 아니거나 HEAD가 없거나(첫 커밋 전) git을 실행할 수 없으면 null이다. git 설정의 filter 명령을 막을 수 없거나
     * (덮을 수 없는 드라이버 이름) 프로젝트 아래에 서브모듈이 있어 깨끗함을 증명할 수 없을 때도 null이다. 그때는
     * revision을 모르는 것이지 순회 실패가 아니므로 오류로 올리지 않는다. 사용자는 `--revision`으로 줄 수 있다.
     *
     * @param project `--project`의 realpath다. 경로 명세 `.`은 이 디렉터리라 저장소의 다른 곳의 변경은 무시한다
     */
    fun cleanHead(project: Path): String? {
        val head = runGit(project, listOf("rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"))
            ?.trim()?.takeIf(::isObjectId) ?: return null
        // 서브모듈 작업 트리가 깨끗한지는 그 안에서 서브모듈 저장소의 설정(filter 등)을 따르는 git을 띄워야만 안다. 그
        // 설정을 실행하지 않고는 깨끗함을 증명할 수 없으므로 index에 gitlink가 있으면 revision을 싣지 않는다. ls-files는
        // 작업 트리 내용을 읽지 않아 filter를 부르지 않는다.
        val index = runGit(project, listOf("ls-files", "--stage", "-z", "--", ".")) ?: return null
        if (index.split('\u0000').any { entry -> entry.startsWith(GITLINK_MODE) }) return null
        // --no-optional-locks: 읽기 전용 질의가 index를 갱신하려고 잠그지 않게 한다.
        // --ignore-submodules=all: 서브모듈 안에서 git을 띄우지 않는다(위에서 gitlink가 없음을 확인했다).
        val dirty = runGit(project, listOf("--no-optional-locks", "status", "--porcelain", "--untracked-files=normal",
            "--ignore-submodules=all", "-z", "--", ".")) ?: return null
        return head.takeIf { dirty.isEmpty() }
    }

    /** `ls-files --stage` 항목(`<mode> <object> <stage>\t<path>`) 중 gitlink(서브모듈)의 mode다. */
    private const val GITLINK_MODE = "160000 "

    /** SHA-1(40자)·SHA-256(64자) 소문자 hex 커밋 id인지다. */
    private fun isObjectId(value: String): Boolean = Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(value)

    /** 실패하면 null이다. stderr는 [GitCommand.start]가 버린다. */
    private fun runGit(workingDirectory: Path, arguments: List<String>): String? {
        val process = try {
            GitCommand.start(workingDirectory, arguments)
        } catch (_: IOException) {
            // PATH의 절대경로 항목에 git이 없거나 시작할 수 없는 환경이거나, git 설정의 filter 드라이버를 덮을 수 없어 git을
            // 시작하지 않았다(GitIsolationException). revision을 모른다고 두고 순회는 계속한다.
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
