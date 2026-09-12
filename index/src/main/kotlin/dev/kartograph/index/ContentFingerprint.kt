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

    /** 외부 입력의 로컬 경로를 직렬화하지 않는 이동 가능한 식별자를 만든다. */
    public fun capture(project: Path, path: Path, role: String, externalSlot: String): InputFingerprint {
        val digest = hash(path, role == "sources")
        val root = project.toRealPath()
        val absolute = path.toRealPath()
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
