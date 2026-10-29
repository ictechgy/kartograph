package dev.kartograph.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * 저장소 config의 `core.fsmonitor`에 표식 파일을 남기는 명령을 건다.
 *
 * kartograph가 실행한 git이 이 명령을 부르면 표식이 생긴다. 설치할 때 일반 `git status`로 훅이 실제로 실행되는지
 * 먼저 확인한다. git이 훅을 부르지 않는 환경에서 시험이 헛되이 통과하지 않게 하기 위해서다.
 *
 * @param scratch 저장소 밖 디렉터리. 훅과 표식이 저장소 안에 있으면 추적되지 않는 파일이 되어 결과를 바꾼다
 * @param repository 훅을 걸 git 저장소
 */
internal class FsmonitorHookProbe(scratch: Path, private val repository: Path) {
    private val marker = scratch.resolve("fsmonitor-ran")

    init {
        val hook = scratch.resolve("fsmonitor-hook.sh")
        Files.writeString(hook, "#!/bin/sh\n: > '$marker'\n")
        check(hook.toFile().setExecutable(true)) { "cannot mark the fsmonitor hook executable" }
        git("config", "core.fsmonitor", hook.toString())
        git("status", "--porcelain")
        check(Files.exists(marker)) { "plain git status did not run core.fsmonitor; this probe cannot observe the boundary" }
        Files.delete(marker)
    }

    /** 설치 확인 뒤 저장소의 fsmonitor 명령이 실행됐는지다. */
    val hookRan: Boolean get() = Files.exists(marker)

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { output }
    }
}
