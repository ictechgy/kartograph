package dev.kartograph.cli

import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** 저장 질의와 영향 점검에 같은 UTF-8·크기 제한을 적용한다. */
internal object SnapshotFiles {
    fun limit(values: List<String>): SnapshotFileLimit? {
        if (values.size > 1) return null
        val maximumMiB = when {
            values.isEmpty() -> QuerySnapshotCodec.DEFAULT_MAX_MIB
            else -> values.single().toIntOrNull() ?: return null
        }
        val maximumBytes = try {
            QuerySnapshotCodec.maximumBytes(maximumMiB)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return SnapshotFileLimit(maximumMiB, maximumBytes)
    }

    fun read(path: String, maximumBytes: Int = QuerySnapshotCodec.MAX_BYTES): QuerySnapshot =
        QuerySnapshotCodec.parse(readText(path, maximumBytes), maximumBytes)

    /** 파서가 소비하는 같은 bounded byte 배열의 identity다. live freshness를 검사하지 않는다. */
    data class Loaded(val snapshot: QuerySnapshot, val sha256: String)

    fun readWithIdentity(path: String, maximumBytes: Int = QuerySnapshotCodec.MAX_BYTES): Loaded {
        val bytes = readBytes(path, maximumBytes, true)
        val snapshot = QuerySnapshotCodec.parse(decode(bytes), maximumBytes)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Loaded(snapshot, hash)
    }

    fun readText(value: String, maximum: Int, followLinks: Boolean = true): String = decode(readBytes(value, maximum, followLinks))

    private fun readBytes(value: String, maximum: Int, followLinks: Boolean): ByteArray {
        require(maximum > 0)
        val path = Path.of(value)
        require(Files.isRegularFile(path))
        val observedSize = Files.size(path)
        require(observedSize in 0..maximum.toLong())
        val bytes = ByteArray(observedSize.toInt())
        val links = if (followLinks) emptyArray() else arrayOf(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        Files.newInputStream(path, *links).use { input ->
            require(input.readNBytes(bytes, 0, bytes.size) == bytes.size && input.read() == -1)
        }
        return bytes
    }

    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

internal data class SnapshotFileLimit(val maximumMiB: Int, val maximumBytes: Int)
