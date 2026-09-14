package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** 시간·크기가 아니라 정렬된 상대 이름과 바이트를 읽는다. 입력 오류에는 원시 경로를 노출하지 않는다. */
public object ContentFingerprint {
    /** 디렉터리는 멤버 추가/삭제도 포함한다. source root는 Java/Kotlin 소스만 읽는다. */
    public fun hash(path: Path, sourcesOnly: Boolean = false): String = observeHash(path, sourcesOnly).sha256

    private fun observeHash(
        path: Path,
        sourcesOnly: Boolean = false,
        maximumSpoolBytes: Long = 0,
        spoolDirectory: Path? = null,
    ): HashObservation {
        require(Files.exists(path, NOFOLLOW_LINKS)) { "fingerprint input is missing" }
        checkPath(path)
        val digest = MessageDigest.getInstance("SHA-256")
        var rawFileSha256: String? = null
        var spool: OwnedJarSpool? = null
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            val files = Files.walk(path).use { stream -> stream.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
                .filter { !sourcesOnly || it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".kt") }
                .sorted(compareBy { path.relativize(it).toString().replace('\\', '/') }).toList() }
            put(digest, "directory")
            files.forEach { file ->
                checkPath(file)
                put(digest, path.relativize(file).toString().replace('\\', '/'))
                put(digest, fileDigest(file))
            }
        } else {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "fingerprint input is not a regular file" }
            put(digest, "file")
            val file = observeFile(path, maximumSpoolBytes, spoolDirectory)
            rawFileSha256 = file.sha256
            spool = file.spool
            try {
                put(digest, file.sha256)
            } catch (error: Throwable) {
                spool?.close()
                throw error
            }
        }
        var transferred = false
        try {
            val observation = HashObservation(digest.digest().hex(), rawFileSha256, spool)
            transferred = true
            return observation
        } finally {
            if (!transferred) spool?.close()
        }
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
    ): HashObservation {
        if (role == "build-logic-watch") {
            val digest = MessageDigest.getInstance("SHA-256")
            put(digest, "directory")
            BuildLogicInputs.files(path).forEach { file ->
                put(digest, path.relativize(file).toString().replace('\\', '/'))
                put(digest, fileDigest(file))
            }
            return HashObservation(digest.digest().hex())
        }
        if (role == "file-watch") {
            if (Files.notExists(path, NOFOLLOW_LINKS)) return HashObservation(values(listOf("missing-file")))
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "watched input must be a regular file" }
            return observeHash(path, maximumSpoolBytes = maximumSpoolBytes, spoolDirectory = spoolDirectory)
        }
        if (role !in setOf("source-watch", "directory-watch")) {
            return observeHash(path, role == "sources", maximumSpoolBytes, spoolDirectory)
        }
        // 없음과 빈 디렉터리는 모두 컴파일할 소스가 없는 상태다. 조회 실패를 없음으로 대체하지 않는다.
        if (Files.notExists(path, NOFOLLOW_LINKS)) return HashObservation(values(listOf("directory")))
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "watched input must be a directory" }
        // 소스 확장자가 없는 symlink 디렉터리도 이후 소스를 숨길 수 있으므로 먼저 거부한다.
        Files.walk(path).use { stream -> stream.forEach(::checkPath) }
        return observeHash(path, sourcesOnly = role == "source-watch")
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
    ): CapturedContentFingerprint {
        val observation = observeHashInput(path, role, maximumSpoolBytes, spoolDirectory)
        var transferred = false
        try {
            val root = project.toRealPath()
            val absolute = if (role in setOf("source-watch", "directory-watch", "file-watch", "build-logic-watch") && Files.notExists(path, NOFOLLOW_LINKS)) {
                path.toFile().canonicalFile.toPath()
            } else path.toRealPath()
            val identity = if (absolute.startsWith(root)) root.relativize(absolute).toString().replace('\\', '/').ifEmpty { "." }
                else "external/$externalSlot"
            val captured = CapturedContentFingerprint(
                InputFingerprint(role, identity, observation.sha256),
                absolute,
                observation.rawFileSha256,
                observation.spool,
            )
            transferred = true
            return captured
        } finally {
            if (!transferred) observation.spool?.close()
        }
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

    private fun fileDigest(path: Path): String = observeFile(path, 0, null).sha256

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

    private data class FileObservation(val sha256: String, val spool: OwnedJarSpool?)
}

internal data class CapturedContentFingerprint(
    val fingerprint: InputFingerprint,
    val realPath: Path,
    val rawFileSha256: String?,
    val spool: OwnedJarSpool?,
)
