package dev.kartograph.index

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** class와 의존성 header 캐시가 공유하는 크기 제한·원자적 파일 교체 경계다. */
internal class IndexCacheStorage(private val directory: Path) {
    fun read(name: String, maximum: Int): CacheBytesResult {
        val path = entry(name)
        return try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) return CacheBytesResult.Miss
            val size = attributes.size()
            if (size !in 1..maximum.toLong()) return CacheBytesResult.Invalid
            val bytes = ByteArray(size.toInt())
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                // 관측 크기보다 줄거나 늘어난 엔트리는 재파싱한다. 중간 chunk와 전체 복사도 피한다.
                if (input.readNBytes(bytes, 0, bytes.size) != bytes.size || input.read() != -1) {
                    return CacheBytesResult.Invalid
                }
            }
            CacheBytesResult.Hit(bytes)
        } catch (_: NoSuchFileException) { CacheBytesResult.Miss }
        catch (_: IOException) { CacheBytesResult.Invalid }
        catch (_: SecurityException) { CacheBytesResult.Unavailable }
        catch (_: UnsupportedOperationException) { CacheBytesResult.Unavailable }
    }

    fun write(name: String, bytes: ByteArray, maximum: Int): CacheWriteResult {
        if (bytes.size !in 1..maximum) return CacheWriteResult.Failed
        return try {
            if (!Files.isDirectory(directory)) Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".entry-", ".tmp")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, entry(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
            CacheWriteResult.Written
        } catch (_: IOException) { CacheWriteResult.Failed }
        catch (_: SecurityException) { CacheWriteResult.Unavailable }
        catch (_: UnsupportedOperationException) { CacheWriteResult.Unavailable }
    }

    private fun entry(name: String): Path {
        require(name.matches(ENTRY_NAME) && name != "." && name != "..")
        return directory.resolve(name)
    }

    private companion object {
        val ENTRY_NAME = Regex("[a-zA-Z0-9.-]+")
    }
}

internal sealed interface CacheBytesResult {
    data class Hit(val bytes: ByteArray) : CacheBytesResult
    data object Miss : CacheBytesResult
    data object Invalid : CacheBytesResult
    data object Unavailable : CacheBytesResult
}
