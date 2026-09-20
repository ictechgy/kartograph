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

    fun readText(value: String, maximum: Int, followLinks: Boolean = true): String {
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
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}

internal data class SnapshotFileLimit(val maximumMiB: Int, val maximumBytes: Int)
