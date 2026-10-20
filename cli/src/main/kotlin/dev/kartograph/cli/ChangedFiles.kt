package dev.kartograph.cli

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Path

/**
 * Git 기준점 이후의 커밋·worktree·untracked 파일을 안전하게 합친다.
 *
 * 모든 호출이 [GitCommand]의 격리를 지나 저장소 설정의 filter 드라이버를 실행하지 않는다(설계 결정 D1). 작업 트리 diff는
 * stat 정보가 index와 다른 파일의 내용을 다시 읽어 걸러 내지 않으므로 그런 파일(시각만 바뀐 파일, git-lfs처럼 filter로
 * 정규화되던 파일)도 바뀐 경로로 남는다(넓히는 쪽).
 */
internal object ChangedFiles {
    /**
     * 변경 경로 diff의 앞부분이다.
     * - `diff.autoRefreshIndex=false`: 작업 트리 비교가 stat만 바뀐 파일의 내용을 다시 읽어 걸러 내고 index를 고쳐 쓰는
     *   단계를 끈다. 고쳐 쓸 때 저장소의 post-index-change hook이 실행된다. 전역 옵션이라 하위 명령 앞에 둔다.
     * - `--no-ext-diff --no-textconv`: 저장소 설정의 외부 diff·textconv 명령을 끈다.
     * - `--ignore-submodules=dirty`: 서브모듈 안에서 git을 띄우지 않는다. 기록과 다른 커밋을 가리키는 서브모듈 경로는 남는다.
     * - `--no-renames`: 이름만 내는 diff라 rename 판정은 결과를 바꾸지 않는다(새 경로는 추가로 남고 옛 경로는 `--diff-filter=d`가
     *   뺀다). 판정을 빼면 blob을 읽지 않으므로 blobless partial clone도 빠진 객체를 받지 않고([GitCommand]가 막는다) 범위를 읽는다.
     */
    private val DIFF = listOf("-c", "diff.autoRefreshIndex=false", "diff", "--name-only", "--diff-filter=d", "-z",
        "--no-ext-diff", "--no-textconv", "--ignore-submodules=dirty", "--no-renames")

    fun since(reference: String, workingDirectory: Path): Set<Path> {
        val root = runGit(workingDirectory, listOf("rev-parse", "--show-toplevel"), nulSeparated = false)
            .singleOrNull()?.let(Path::of)
            ?: throw ChangedFilesException("changed files unavailable; verify the repository and Git reference")
        val commit = runGit(
            root,
            listOf("rev-parse", "--verify", "--end-of-options", "$reference^{commit}"),
            nulSeparated = false,
        ).singleOrNull() ?: throw ChangedFilesException("git reference is unavailable; in CI, fetch full history")
        val paths = buildList {
            addAll(runGit(root, DIFF + "$commit...HEAD", true))
            addAll(runGit(root, DIFF + "HEAD", true))
            addAll(runGit(root, listOf("ls-files", "--others", "--exclude-standard", "--full-name", "-z"), true))
        }
        return paths.map { root.resolve(it).normalize() }.toSet()
    }

    private fun runGit(
        workingDirectory: Path,
        arguments: List<String>,
        nulSeparated: Boolean,
    ): List<String> {
        val process = try {
            GitCommand.start(workingDirectory, arguments)
        } catch (error: GitIsolationException) {
            // git 설정의 filter 명령을 막을 수 없어 git을 시작하지 않았다(fail-closed). 원인과 우회 방법을 함께 알린다.
            throw ChangedFilesException("${error.message}, or run without --since", error)
        } catch (error: InterruptedIOException) {
            throw ChangedFilesException("git operation was interrupted", error)
        } catch (error: IOException) {
            throw ChangedFilesException("unable to run git; verify that Git is installed", error)
        }
        val stdout = process.inputStream.readAllBytes()
        val status = try {
            process.waitFor()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ChangedFilesException("git operation was interrupted", error)
        }
        if (status != 0) {
            // partial clone이면 막아 둔 lazy fetch 때문에 빠진 객체를 읽지 못했을 수 있다. 그 사실과 해결 방향을 함께 알린다.
            throw ChangedFilesException(if (GitCommand.hasPromisorRemote(workingDirectory)) PARTIAL_CLONE_UNAVAILABLE else UNAVAILABLE)
        }
        return stdout.toString(Charsets.UTF_8).split(if (nulSeparated) '\u0000' else '\n').filter(String::isNotEmpty)
    }

    /** git이 실패했을 때의 기존 문구다. 기준 ref가 없거나 얕은 clone이라 history가 모자란 경우가 흔하다. */
    private const val UNAVAILABLE = "changed files unavailable; verify the Git reference and fetch full history"

    /** promisor 원격이 있는 저장소에서 git이 실패했을 때의 문구다. 빠진 객체를 받지 않는 이유와 우회 방법을 덧붙인다. */
    private const val PARTIAL_CLONE_UNAVAILABLE = "$UNAVAILABLE (kartograph does not fetch objects missing from this partial clone, " +
        "because that runs commands from the repository's configuration; fetch them in a trusted checkout first, " +
        "clone without --filter, or run without --since)"
}

internal class ChangedFilesException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
