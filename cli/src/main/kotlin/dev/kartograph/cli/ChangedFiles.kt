package dev.kartograph.cli

import java.io.IOException
import java.nio.file.Path

/** Git 기준점 이후의 커밋·worktree·untracked 파일을 안전하게 합친다. */
internal object ChangedFiles {
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
            addAll(runGit(root, listOf("diff", "--name-only", "--diff-filter=d", "-z", "$commit...HEAD"), true))
            addAll(runGit(root, listOf("diff", "--name-only", "--diff-filter=d", "-z", "HEAD"), true))
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
            ProcessBuilder(listOf("git", "-C", workingDirectory.toString()) + arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
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
            throw ChangedFilesException("changed files unavailable; verify the Git reference and fetch full history")
        }
        return stdout.toString(Charsets.UTF_8).split(if (nulSeparated) '\u0000' else '\n').filter(String::isNotEmpty)
    }
}

internal class ChangedFilesException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
