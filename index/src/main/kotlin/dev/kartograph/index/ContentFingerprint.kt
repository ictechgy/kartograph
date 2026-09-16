package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentLinkedQueue

/** 시간·크기가 아니라 정렬된 상대 이름과 바이트를 읽는다. 입력 오류에는 원시 경로를 노출하지 않는다. */
public object ContentFingerprint {
    /** 디렉터리는 멤버 추가/삭제도 포함한다. source root는 Java/Kotlin 소스만 읽는다. */
    public fun hash(path: Path, sourcesOnly: Boolean = false): String = observeHash(path, sourcesOnly).sha256

    private fun observeHash(
        path: Path,
        sourcesOnly: Boolean = false,
        maximumSpoolBytes: Long = 0,
        spoolDirectory: Path? = null,
    ): HashObservation = planHash(path, sourcesOnly, maximumSpoolBytes, spoolDirectory).completeSequentially()

    /**
     * 입력 하나를 "독립적으로 digest할 파일 목록"과 "입력 순서를 지키는 결합"으로 나눈다.
     * 파일 digest끼리는 서로 독립이므로 병렬로 계산해도 결합 결과는 순차 계산과 바이트 단위로 같다.
     */
    private fun planHash(
        path: Path,
        sourcesOnly: Boolean,
        maximumSpoolBytes: Long,
        spoolDirectory: Path?,
    ): ObservationPlan {
        require(Files.exists(path, NOFOLLOW_LINKS)) { "fingerprint input is missing" }
        checkPath(path)
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            val files = Files.walk(path).use { stream -> stream.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
                .filter { !sourcesOnly || it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".kt") }
                .sorted(compareBy { path.relativize(it).toString().replace('\\', '/') }).toList() }
            files.forEach(::checkPath)
            return directoryPlan(path, files)
        }
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "fingerprint input is not a regular file" }
        return ObservationPlan(listOf(FileDigestJob(path, maximumSpoolBytes, spoolDirectory, standalone = true))) { observations ->
            val file = observations.single()
            val digest = MessageDigest.getInstance("SHA-256")
            put(digest, "file")
            put(digest, file.sha256)
            HashObservation(digest.digest().hex(), file.sha256, file.spool)
        }
    }

    /** 디렉터리 결합은 정렬된 상대 이름과 각 파일 digest를 순서대로 넣는다. 멤버는 spool하지 않는다. */
    private fun directoryPlan(root: Path, files: List<Path>): ObservationPlan =
        ObservationPlan(files.map { FileDigestJob(it, 0, null, standalone = false) }) { observations ->
            val digest = MessageDigest.getInstance("SHA-256")
            put(digest, "directory")
            files.zip(observations).forEach { (file, observed) ->
                put(digest, root.relativize(file).toString().replace('\\', '/'))
                put(digest, observed.sha256)
            }
            HashObservation(digest.digest().hex())
        }

    /** 옵션 값은 문서에 저장하지 않고 길이 구분한 SHA-256으로만 기록한다. */
    public fun values(values: List<String>): String = MessageDigest.getInstance("SHA-256").also { digest ->
        values.forEach { put(digest, it) }
    }.digest().hex()

    /** watch 역할만 선언된 파일·디렉터리의 부재를 추적한다. 필수 입력의 누락은 계속 오류다. */
    public fun hashInput(path: Path, role: String): String = observeHashInput(path, role).sha256

    private fun observeHashInput(
        path: Path,
        role: String,
        maximumSpoolBytes: Long = 0,
        spoolDirectory: Path? = null,
    ): HashObservation = planInput(path, role, maximumSpoolBytes, spoolDirectory).completeSequentially()

    private fun planInput(path: Path, role: String, maximumSpoolBytes: Long, spoolDirectory: Path?): ObservationPlan {
        if (role == "build-logic-watch") return directoryPlan(path, BuildLogicInputs.files(path))
        if (role == "file-watch") {
            if (Files.notExists(path, NOFOLLOW_LINKS)) return ObservationPlan.constant(values(listOf("missing-file")))
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "watched input must be a regular file" }
            return planHash(path, sourcesOnly = false, maximumSpoolBytes, spoolDirectory)
        }
        if (role !in setOf("source-watch", "directory-watch")) {
            return planHash(path, role == "sources", maximumSpoolBytes, spoolDirectory)
        }
        // 없음과 빈 디렉터리는 모두 컴파일할 소스가 없는 상태다. 조회 실패를 없음으로 대체하지 않는다.
        if (Files.notExists(path, NOFOLLOW_LINKS)) return ObservationPlan.constant(values(listOf("directory")))
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "watched input must be a directory" }
        // 소스 확장자가 없는 symlink 디렉터리도 이후 소스를 숨길 수 있으므로 먼저 거부한다.
        Files.walk(path).use { stream -> stream.forEach(::checkPath) }
        return planHash(path, sourcesOnly = role == "source-watch", 0, null)
    }

    /** 외부 입력의 로컬 경로를 직렬화하지 않는 이동 가능한 식별자를 만든다. */
    public fun capture(project: Path, path: Path, role: String, externalSlot: String): InputFingerprint =
        captureObserved(project, path, role, externalSlot).fingerprint

    internal fun captureObserved(
        project: Path,
        path: Path,
        role: String,
        externalSlot: String,
        maximumSpoolBytes: Long = 0,
        spoolDirectory: Path? = null,
    ): CapturedContentFingerprint = captureObservedAll(
        project,
        listOf(ObservationRequest(path, role, externalSlot, maximumSpoolBytes, spoolDirectory)),
    ) { jobs, digest -> jobs.map(digest) }.single()

    /**
     * 여러 입력을 한 번에 관측한다. 계획과 결합은 입력 순서대로 호출 스레드에서 하고,
     * 파일 digest만 [mapJobs]에 맡긴다. [mapJobs]는 같은 순서의 결과를 돌려줘야 한다.
     * 실패하면 이미 만든 spool을 모두 닫고 입력 순서상 첫 오류를 던진다.
     */
    internal fun captureObservedAll(
        project: Path,
        requests: List<ObservationRequest>,
        mapJobs: (List<FileDigestJob>, (FileDigestJob) -> Result<FileObservation>) -> List<Result<FileObservation>>,
    ): List<CapturedContentFingerprint> {
        val plans = requests.map { planInput(it.path, it.role, it.maximumSpoolBytes, it.spoolDirectory) }
        val jobs = plans.flatMap(ObservationPlan::jobs)
        // worker가 만든 spool은 결과 목록과 별도로 기록한다. 호출 스레드가 interrupt되면 결과 목록을 받지 못하므로
        // 이 기록으로 닫는다. cancel된 worker는 observeFile의 finally가 자기 spool을 닫는다.
        val createdSpools = ConcurrentLinkedQueue<OwnedJarSpool>()
        val results = try {
            mapJobs(jobs) { job -> digestJob(job, createdSpools) }
        } catch (error: Throwable) {
            createdSpools.forEach(OwnedJarSpool::close)
            throw error
        }
        if (results.size != jobs.size) {
            createdSpools.forEach(OwnedJarSpool::close)
            throw IllegalStateException("fingerprint digests do not match the requested inputs")
        }
        val observations = results.map { result ->
            result.getOrElse { failure ->
                createdSpools.forEach(OwnedJarSpool::close)
                throw failure
            }
        }
        val captured = mutableListOf<CapturedContentFingerprint>()
        var transferred = false
        try {
            var offset = 0
            plans.forEachIndexed { index, plan ->
                val observed = plan.complete(observations.subList(offset, offset + plan.jobs.size))
                offset += plan.jobs.size
                captured += resolveIdentity(project, requests[index], observed)
            }
            transferred = true
            return captured
        } finally {
            if (!transferred) {
                observations.forEach { it.spool?.close() }
                captured.forEach { it.spool?.close() }
            }
        }
    }

    /** worker 스레드의 digest 하나. interrupt로 끊긴 읽기는 IO 실패가 아니라 중단으로 보고한다. */
    private fun digestJob(job: FileDigestJob, createdSpools: ConcurrentLinkedQueue<OwnedJarSpool>): Result<FileObservation> =
        runCatching {
            observeFile(job.path, job.maximumSpoolBytes, job.spoolDirectory).also { observed -> observed.spool?.let(createdSpools::add) }
        }.recoverCatching { failure ->
            if (failure is ClosedByInterruptException || failure is InterruptedIOException) {
                throw ClassIndexingException("fingerprint capture was interrupted", failure)
            }
            throw failure
        }

    private fun resolveIdentity(project: Path, request: ObservationRequest, observation: HashObservation): CapturedContentFingerprint {
        val root = project.toRealPath()
        val watchRole = request.role in setOf("source-watch", "directory-watch", "file-watch", "build-logic-watch")
        val absolute = if (watchRole && Files.notExists(request.path, NOFOLLOW_LINKS)) {
            request.path.toFile().canonicalFile.toPath()
        } else request.path.toRealPath()
        val identity = if (absolute.startsWith(root)) root.relativize(absolute).toString().replace('\\', '/').ifEmpty { "." }
            else "external/${request.externalSlot}"
        return CapturedContentFingerprint(
            InputFingerprint(request.role, identity, observation.sha256),
            absolute,
            observation.rawFileSha256,
            observation.spool,
        )
    }

    /** before/after 내용 지문이 같은 경우에만 capture action의 결과를 반환한다. */
    public fun <T> withVerifiedCapture(
        capture: (VerifiedCaptureScope) -> SnapshotProvenance,
        cache: ClassIndexCache? = null,
        action: (VerifiedCaptureScope, SnapshotProvenance) -> T,
    ): T {
        val scope = VerifiedCaptureScope.open(cache)
        var failure: Throwable? = null
        try {
            val before = capture(scope)
            scope.beginAction()
            val result = action(scope, before)
            scope.beginAfterCapture()
            val after = capture(scope)
            check(before == after) { "snapshot inputs changed during verified capture" }
            scope.markPopulatedIfComplete()
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            scope.close(failure)
        }
    }

    private fun checkPath(path: Path) {
        require(!Files.isSymbolicLink(path)) { "symbolic fingerprint inputs are not supported" }
    }

    private fun observeFile(path: Path, maximumSpoolBytes: Long, spoolDirectory: Path?): FileObservation {
        val digest = MessageDigest.getInstance("SHA-256")
        var spoolWriter = if (maximumSpoolBytes > 0) {
            val observedSize = Files.size(path)
            if (observedSize in 0..maximumSpoolBytes) {
                JarSpoolWriter.open(spoolDirectory, maximumSpoolBytes)
            } else null
        } else {
            null
        }
        try {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (size == 0) continue
                    digest.update(buffer, 0, size)
                    if (spoolWriter?.write(buffer, 0, size) == false) spoolWriter = null
                }
            }
            val sha256 = digest.digest().hex()
            val spool = spoolWriter?.finish()
            spoolWriter = null
            var transferred = false
            try {
                val observation = FileObservation(sha256, spool)
                transferred = true
                return observation
            } finally {
                if (!transferred) spool?.close()
            }
        } finally {
            spoolWriter?.close()
        }
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun ByteArray.hex(): String = HexFormat.of().formatHex(this)

    private data class HashObservation(
        val sha256: String,
        val rawFileSha256: String? = null,
        val spool: OwnedJarSpool? = null,
    )

    /** 파일 digest 작업 목록과, 그 결과를 입력 순서대로 결합하는 규칙이다. */
    private class ObservationPlan(
        val jobs: List<FileDigestJob>,
        private val combine: (List<FileObservation>) -> HashObservation,
    ) {
        fun complete(observations: List<FileObservation>): HashObservation {
            var transferred = false
            try {
                val observed = combine(observations)
                transferred = true
                return observed
            } finally {
                if (!transferred) observations.forEach { it.spool?.close() }
            }
        }

        fun completeSequentially(): HashObservation {
            val observations = mutableListOf<FileObservation>()
            try {
                jobs.forEach { job -> observations += observeFile(job.path, job.maximumSpoolBytes, job.spoolDirectory) }
            } catch (error: Throwable) {
                observations.forEach { it.spool?.close() }
                throw error
            }
            return complete(observations)
        }

        companion object {
            fun constant(sha256: String): ObservationPlan = ObservationPlan(emptyList()) { HashObservation(sha256) }
        }
    }
}

/**
 * 파일 하나의 digest 요청. spool은 정규 파일 입력에만 붙고 디렉터리 멤버에는 붙지 않는다.
 * [standalone]은 입력 자체가 파일(JAR 등)인 경우로, 크기 기반 배정 대상이다. 디렉터리 멤버는 작아서 크기를 재지 않는다.
 */
internal data class FileDigestJob(val path: Path, val maximumSpoolBytes: Long, val spoolDirectory: Path?, val standalone: Boolean)

/** 파일 하나의 digest와, 요청된 경우 그 바이트를 담은 owned spool이다. */
internal data class FileObservation(val sha256: String, val spool: OwnedJarSpool?)

/** 일괄 관측 요청 하나. [maximumSpoolBytes]가 0이면 spool하지 않는다. */
internal data class ObservationRequest(
    val path: Path,
    val role: String,
    val externalSlot: String,
    val maximumSpoolBytes: Long,
    val spoolDirectory: Path?,
)

internal data class CapturedContentFingerprint(
    val fingerprint: InputFingerprint,
    val realPath: Path,
    val rawFileSha256: String?,
    val spool: OwnedJarSpool?,
)
