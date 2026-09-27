package dev.kartograph.cli

import java.io.IOException
import java.nio.file.Path

/**
 * 순회 문서의 `revision`을 git에서 확인한다.
 *
 * git 접근은 CLI 계층에만 둔다([ChangedFiles]와 같은 이유). HEAD는 작업 트리가 그 커밋과 같을 때만 분석한 소스의
 * 신원이다. 고친 파일이 있는데 HEAD를 실으면 isthmus는 다른 분석과 revision이 같다는 이유로 낡은 분석을 최신으로
 * 판단한다. 그래서 프로젝트 디렉터리 아래가 깨끗할 때만 싣고, 아니면 뺀다.
 */
internal object GitRevision {
    /**
     * 프로젝트 디렉터리 아래에 커밋하지 않은 변경(추적되지 않는 파일 포함)이 없을 때의 HEAD 커밋 id다.
     *
     * 저장소가 아니거나 HEAD가 없거나(첫 커밋 전) git을 실행할 수 없으면 null이다. 그때는 revision을 모르는 것이지
     * 순회 실패가 아니므로 오류로 올리지 않는다.
     *
     * @param project `--project`의 realpath다. 경로 명세 `.`은 이 디렉터리라 저장소의 다른 곳의 변경은 무시한다
     */
    fun cleanHead(project: Path): String? {
        val head = runGit(project, listOf("rev-parse", "--verify", "--end-of-options", "HEAD^{commit}"))
            ?.trim()?.takeIf(::isObjectId) ?: return null
        // --no-optional-locks: 읽기 전용 질의가 index를 갱신하려고 잠그지 않게 한다.
        val dirty = runGit(project, listOf("--no-optional-locks", "status", "--porcelain", "--untracked-files=normal", "-z", "--", "."))
            ?: return null
        return head.takeIf { dirty.isEmpty() }
    }

    /** SHA-1(40자)·SHA-256(64자) 소문자 hex 커밋 id인지다. */
    private fun isObjectId(value: String): Boolean = Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(value)

    /** 실패하면 null이다. stderr는 버린다 — 저장소 밖 경로 같은 원시 git 메시지를 사용자 출력에 섞지 않는다. */
    private fun runGit(workingDirectory: Path, arguments: List<String>): String? {
        val process = try {
            ProcessBuilder(listOf("git", "-C", workingDirectory.toString()) + arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            // git이 설치되지 않은 환경이다. revision을 모른다고 두고 순회는 계속한다.
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
