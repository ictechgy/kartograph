package dev.kartograph.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * 분석 대상 저장소의 config에 표식 파일을 남기는 filter 드라이버를 건다(설계 결정 D1의 회귀 고정물).
 *
 * 압축본으로 받은 저장소의 `.git/config`에 실린 드라이버를 흉내 낸다. git은 stat 정보가 index와 다른 추적 파일의
 * 내용을 다시 읽을 때 드라이버를 실행한다. 그래서 [stale] 파일의 수정 시각을 먼 과거로 돌리고, 설치할 때 일반
 * git status로 드라이버가 실제로 실행되는지 먼저 확인한다. git이 드라이버를 부르지 않는 환경에서 시험이 헛되이
 * 통과하지 않게 하기 위해서다. 확인 뒤에는 시각을 다른 과거로 한 번 더 돌려 stat이 계속 어긋나게 둔다.
 *
 * 설정은 git config 명령이 아니라 config 파일에 바이트로 직접 쓴다. JVM은 argv를 로캘 인코딩으로 바꾸므로 명령으로
 * 쓰면 비ASCII 이름이 로캘에 따라 다른 이름이 된다. 커밋을 마친 뒤에 설치한다. 설치 뒤 고정물 git이 돌면 표식이
 * 먼저 생긴다.
 *
 * @param scratch 저장소 밖 디렉터리. 명령과 표식이 저장소 안에 있으면 추적되지 않는 파일이 되어 결과를 바꾼다
 * @param repository 드라이버를 걸 작업 트리. 서브모듈이면 그 저장소 자신의 config(`.git/modules/...`)에 건다
 * @param name 드라이버 이름. 커밋된 `.gitattributes`가 이 이름을 고르거나 [infoAttributes]로 고른다
 * @param key 명령 키. `clean`은 내용을 그대로 돌려준다. `process`는 표식만 남기고 끝나므로 그 드라이버를 부른 git은
 *   실패한다(실행 여부만 표식으로 본다)
 * @param infoAttributes true면 저장소의 `info/attributes`에 `* filter=<name>`을 둔다
 * @param stale 수정 시각을 돌릴 추적 파일. status가 내용을 다시 읽게 하는 파일이다. 비우면 호출자가 먼저
 *   [GitFixture.racyIndex]로 stat이 그대로인 항목도 다시 읽게 만들어 둔다
 * @param required true면 git-lfs의 기본 설정처럼 `required = true`를 함께 둔다. 명령만 비우면 git이 그 파일에서 실패한다
 */
internal class FilterDriverProbe(
    scratch: Path,
    repository: Path,
    name: String,
    key: String = "clean",
    infoAttributes: Boolean = false,
    stale: List<Path>,
    required: Boolean = false,
) {
    private val marker: Path

    init {
        val directory = Files.createTempDirectory(scratch, "filter-driver-")
        marker = directory.resolve("ran")
        val driver = directory.resolve("driver.sh")
        val passThrough = if (key == "process") "" else "cat\n"
        Files.writeString(driver, "#!/bin/sh\n: > '$marker'\n$passThrough")
        check(driver.toFile().setExecutable(true)) { "cannot mark the filter driver executable" }
        val gitDirectory = Path.of(GitFixture.git(repository, "rev-parse", "--absolute-git-dir"))
        val requiredLine = if (required) "\trequired = true\n" else ""
        Files.write(gitDirectory.resolve("config"), "[filter \"$name\"]\n\t$key = \"$driver\"\n$requiredLine".toByteArray(Charsets.UTF_8),
            StandardOpenOption.APPEND)
        if (infoAttributes) {
            Files.createDirectories(gitDirectory.resolve("info"))
            Files.write(gitDirectory.resolve("info/attributes"), "* filter=$name\n".toByteArray(Charsets.UTF_8))
        }
        GitFixture.stale(stale, GitFixture.STALE_TIME)
        // process 드라이버는 handshake에 답하지 않으므로 이 status는 실패한다. 여기서 보는 것은 종료 코드가 아니라 표식이다.
        GitFixture.exitCode(repository, "--no-optional-locks", "status", "--porcelain")
        check(Files.exists(marker)) { "plain git status did not run the filter driver; this probe cannot observe the boundary" }
        Files.delete(marker)
        GitFixture.stale(stale, GitFixture.RESTALE_TIME)
    }

    /** 설치 확인 뒤 저장소의 filter 드라이버가 실행됐는지다. */
    val ran: Boolean get() = Files.exists(marker)
}

/**
 * partial clone의 lazy fetch가 저장소 config의 원격 전송 명령을 실행하는지 보는 고정물이다(설계 결정 D1의 회귀 고정물).
 *
 * [source]를 filter를 받는 원격으로 열고 [filter](예: `blob:none`, `tree:0`)로 `file://` clone [clone]을 만든 뒤, clone의
 * `remote.origin.uploadpack`을 표식을 남기고 진짜 upload-pack을 실행하는 스크립트로 바꾼다. 압축본으로 받은 checkout의
 * `.git/config`가 고른 원격 명령을 흉내 낸다. 같은 방법으로 만든 두 번째 clone에서 일반 git이 [observe]로 lazy fetch를 띄워
 * 표식을 남기는지 먼저 확인한다. git이 그 경로를 타지 않는 환경에서 시험이 헛되이 통과하지 않게 하기 위해서다. 시험할
 * clone의 빠진 객체는 건드리지 않는다.
 *
 * @param scratch 저장소 밖 디렉터리. 명령·표식과 확인용 clone을 둔다
 * @param prepare 원격 명령을 걸기 전에 두 clone에 똑같이 하는 준비(예: `reset --soft`). 빠진 객체를 받아 오면 안 된다
 */
internal class LazyFetchProbe(scratch: Path, source: Path, clone: Path, filter: String, observe: List<String>, prepare: (Path) -> Unit = {}) {
    private val marker: Path

    init {
        GitFixture.git(source, "config", "uploadpack.allowFilter", "true")
        val directory = Files.createTempDirectory(scratch, "upload-pack-")
        marker = directory.resolve("ran")
        val command = directory.resolve("upload-pack.sh")
        Files.writeString(command, "#!/bin/sh\n: > '$marker'\nexec git-upload-pack \"\$@\"\n")
        check(command.toFile().setExecutable(true)) { "cannot mark the upload-pack command executable" }
        val witness = directory.resolve("witness")
        for (target in listOf(witness, clone)) {
            GitFixture.git(scratch, "clone", "-q", "--no-local", "--filter=$filter", source.toUri().toString(), target.toString())
            prepare(target)
            check(GitFixture.git(target, "rev-list", "--objects", "--missing=print", "--all").lines().any { it.startsWith("?") }) {
                "the $filter clone has no missing objects; this probe cannot observe a lazy fetch"
            }
            GitFixture.git(target, "config", "remote.origin.uploadpack", command.toString())
        }
        GitFixture.exitCode(witness, *observe.toTypedArray())
        check(Files.exists(marker)) { "plain git did not fetch through the configured upload-pack; this probe cannot observe the boundary" }
        Files.delete(marker)
    }

    /** 확인 뒤 시험한 clone에서 원격 명령이 실행됐는지다. */
    val ran: Boolean get() = Files.exists(marker)
}

/** D1 회귀 시험의 고정물 git이다. 사용자 전역·시스템 설정과 훅·서명이 고정물에 끼어들지 않게 한다. */
internal object GitFixture {
    /** [commit]이 커밋 전에 두는 파일 시각(2000-01-01 UTC)이다. 아래 두 시각과 달라야 stat이 어긋난다. */
    private val COMMITTED_TIME: FileTime = FileTime.fromMillis(946_684_800_000)

    /** stat을 낡게 만드는 시각(2001-01-01 UTC)이다. 같은 초의 racy 판정을 피하려고 먼 과거로 둔다. */
    val STALE_TIME: FileTime = FileTime.fromMillis(978_307_200_000)

    /** 한 번 더 낡게 만드는 시각(2002-01-01 UTC)이다. 중간의 git이 [STALE_TIME]을 index에 기록했을 때를 대비한다. */
    val RESTALE_TIME: FileTime = FileTime.fromMillis(1_009_843_200_000)

    /** [racyIndex]가 index 파일에 두는 시각(1999-01-01 UTC)이다. [COMMITTED_TIME]보다 일러야 한다. */
    private val RACY_INDEX_TIME: FileTime = FileTime.fromMillis(915_148_800_000)

    /** git을 실행하고 표준 출력(앞뒤 공백 제거)을 돌려준다. 실패하면 출력과 함께 시험을 멈춘다. */
    fun git(directory: Path, vararg arguments: String): String {
        val process = process(directory, arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { output }
        return output.trim()
    }

    /** git을 실행하고 종료 코드만 돌려준다. 실패할 수 있는 확인용 호출에 쓴다. */
    fun exitCode(directory: Path, vararg arguments: String): Int =
        process(directory, arguments).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()

    /**
     * 새 저장소를 만들고 [files]를 써서 첫 커밋을 만든다.
     *
     * 파일 시각을 커밋 전에 먼 과거로 둔다. 같은 초에 쓰고 커밋한 파일은 git이 racy로 보고 내용을 다시 읽어, stat이
     * 그대로인 경우의 단언이 시각에 따라 흔들리기 때문이다.
     *
     * @param configuration 커밋 전에 둘 저장소 설정(저장소가 믿는 filter 등). ASCII 값만 쓴다
     */
    fun commit(repository: Path, files: Map<String, String>, vararg configuration: Pair<String, String>) {
        Files.createDirectories(repository)
        git(repository, "init", "-q")
        configuration.forEach { (key, value) -> git(repository, "config", key, value) }
        val written = files.map { (name, text) ->
            val file = repository.resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        stale(written, COMMITTED_TIME)
        git(repository, "add", "-A")
        git(repository, "commit", "-q", "-m", "fixture")
    }

    /** 파일의 수정 시각을 [time]으로 돌려 index의 stat 정보와 어긋나게 한다. 내용은 그대로다. */
    fun stale(paths: List<Path>, time: FileTime) {
        paths.forEach { Files.setLastModifiedTime(it, time) }
    }

    /**
     * index 파일의 시각을 항목에 기록된 시각보다 이르게 돌려 모든 항목을 racy로 만든다. git은 racy 항목을 stat이
     * 그대로여도 내용을 다시 읽어 대조한다(그때 filter 드라이버가 돈다. `diff.autoRefreshIndex=false`여도 그렇다).
     * 압축본을 만든 쪽은 이 상태를 쉽게 만들 수 있다.
     */
    fun racyIndex(repository: Path) {
        Files.setLastModifiedTime(Path.of(git(repository, "rev-parse", "--absolute-git-dir")).resolve("index"), RACY_INDEX_TIME)
    }

    private fun process(directory: Path, arguments: Array<out String>): ProcessBuilder =
        ProcessBuilder(listOf("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
            "-c", "core.hooksPath=/dev/null", "-c", "protocol.file.allow=always", "-C", directory.toString()) + arguments).apply {
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        }
}
