package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** 시간·크기가 아니라 정렬된 상대 이름과 바이트를 읽는다. 입력 오류에는 원시 경로를 노출하지 않는다. */
public object ContentFingerprint {
    /** 디렉터리는 멤버 추가/삭제도 포함한다. source root는 Java/Kotlin 소스만 읽는다. */
    public fun hash(path: Path, sourcesOnly: Boolean = false): String {
        require(Files.exists(path, NOFOLLOW_LINKS)) { "fingerprint input is missing" }
        checkPath(path)
        val digest = MessageDigest.getInstance("SHA-256")
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
            put(digest, fileDigest(path))
        }
        return digest.digest().hex()
    }

    /** 옵션 값은 문서에 저장하지 않고 길이 구분한 SHA-256으로만 기록한다. */
    public fun values(values: List<String>): String = MessageDigest.getInstance("SHA-256").also { digest ->
        values.forEach { put(digest, it) }
    }.digest().hex()

    /** watch 역할만 선언된 파일·디렉터리의 부재를 추적한다. 필수 입력의 누락은 계속 오류다. */
    public fun hashInput(path: Path, role: String): String {
        if (role == "build-logic-watch") {
            val digest = MessageDigest.getInstance("SHA-256")
            put(digest, "directory")
            BuildLogicInputs.files(path).forEach { file ->
                put(digest, path.relativize(file).toString().replace('\\', '/'))
                put(digest, fileDigest(file))
            }
            return digest.digest().hex()
        }
        if (role == "file-watch") {
            if (Files.notExists(path, NOFOLLOW_LINKS)) return values(listOf("missing-file"))
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "watched input must be a regular file" }
            return hash(path)
        }
        if (role !in setOf("source-watch", "directory-watch")) return hash(path, role == "sources")
        // 없음과 빈 디렉터리는 모두 컴파일할 소스가 없는 상태다. 조회 실패를 없음으로 대체하지 않는다.
        if (Files.notExists(path, NOFOLLOW_LINKS)) return values(listOf("directory"))
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "watched input must be a directory" }
        // 소스 확장자가 없는 symlink 디렉터리도 이후 소스를 숨길 수 있으므로 먼저 거부한다.
        Files.walk(path).use { stream -> stream.forEach(::checkPath) }
        return hash(path, sourcesOnly = role == "source-watch")
    }

    /** 외부 입력의 로컬 경로를 직렬화하지 않는 이동 가능한 식별자를 만든다. */
    public fun capture(project: Path, path: Path, role: String, externalSlot: String): InputFingerprint {
        val digest = hashInput(path, role)
        val root = project.toRealPath()
        val absolute = if (role in setOf("source-watch", "directory-watch", "file-watch", "build-logic-watch") && Files.notExists(path, NOFOLLOW_LINKS)) {
            path.toFile().canonicalFile.toPath()
        } else path.toRealPath()
        val identity = if (absolute.startsWith(root)) root.relativize(absolute).toString().replace('\\', '/').ifEmpty { "." }
            else "external/$externalSlot"
        return InputFingerprint(role, identity, digest)
    }

    private fun checkPath(path: Path) {
        require(!Files.isSymbolicLink(path)) { "symbolic fingerprint inputs are not supported" }
    }

    private fun fileDigest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().hex()
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
