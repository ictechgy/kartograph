package dev.kartograph.cli

import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** 저장 질의와 영향 점검에 같은 UTF-8·크기 제한을 적용한다. */
internal object SnapshotFiles {
    fun read(path: String): QuerySnapshot = QuerySnapshotCodec.parse(readText(path, QuerySnapshotCodec.MAX_BYTES))

    fun readText(value: String, maximum: Int): String {
        val path = Path.of(value)
        require(Files.isRegularFile(path))
        val bytes = Files.newInputStream(path).use { it.readNBytes(maximum + 1) }
        require(bytes.size <= maximum)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
