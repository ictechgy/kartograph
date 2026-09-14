package dev.kartograph.index

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** fingerprint와 같은 바이트를 제한된 owned temp JAR에 기록한다. */
internal class JarSpoolWriter private constructor(
    private var path: Path?,
    private var output: OutputStream?,
    private val maximumBytes: Long,
) : AutoCloseable {
    private var written = 0L
    private var abandoned = false

    @Synchronized
    fun write(bytes: ByteArray, offset: Int, length: Int): Boolean {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val sink = output ?: return false
        if (length.toLong() > maximumBytes - written) {
            abandon()
            return false
        }
        return try {
            sink.write(bytes, offset, length)
            written += length
            true
        } catch (_: IOException) {
            abandon()
            false
        } catch (_: SecurityException) {
            abandon()
            false
        } catch (_: UnsupportedOperationException) {
            abandon()
            false
        }
    }

    @Synchronized
    fun finish(): OwnedJarSpool? {
        if (abandoned) return null
        val completedPath = path ?: return null
        val sink = output ?: return null
        return try {
            sink.close()
            output = null
            path = null
            OwnedJarSpool(completedPath, written)
        } catch (_: IOException) {
            abandon()
            null
        } catch (_: SecurityException) {
            abandon()
            null
        } catch (_: UnsupportedOperationException) {
            abandon()
            null
        }
    }

    @Synchronized
    override fun close() {
        abandon()
    }

    private fun abandon() {
        if (abandoned) return
        abandoned = true
        val owned = path
        try {
            output?.close()
        } catch (_: IOException) {
            // 아래 unlink 또는 deleteOnExit이 owned 파일 수명을 마무리한다.
        } catch (_: SecurityException) {
            // 아래 unlink 또는 deleteOnExit이 owned 파일 수명을 마무리한다.
        } catch (_: UnsupportedOperationException) {
            // 아래 unlink 또는 deleteOnExit이 owned 파일 수명을 마무리한다.
        } finally {
            output = null
            owned?.let(::deleteOwnedSpool)
            path = null
        }
    }

    internal companion object {
        fun open(directory: Path? = null, maximumBytes: Long): JarSpoolWriter? {
            if (maximumBytes <= 0) return null
            val temporary = try {
                if (directory == null) Files.createTempFile("kartograph-hierarchy-", ".jar")
                else Files.createTempFile(directory, "kartograph-hierarchy-", ".jar")
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            } catch (_: UnsupportedOperationException) {
                return null
            }
            val sink = try {
                Files.newOutputStream(temporary)
            } catch (_: IOException) {
                deleteOwnedSpool(temporary)
                return null
            } catch (_: SecurityException) {
                deleteOwnedSpool(temporary)
                return null
            } catch (_: UnsupportedOperationException) {
                deleteOwnedSpool(temporary)
                return null
            }
            return JarSpoolWriter(temporary, sink, maximumBytes)
        }
    }
}

/** consume 또는 close가 temp JAR을 정확히 한 번 해제한다. */
internal class OwnedJarSpool(private var path: Path?, val byteSize: Long) : AutoCloseable {
    private var consuming = false

    fun <T> consume(action: (Path) -> T): T {
        val owned = synchronized(this) {
            check(!consuming)
            consuming = true
            path ?: throw IllegalStateException("captured JAR spool is unavailable")
        }
        return try {
            action(owned)
        } finally {
            deleteOwnedSpool(owned)
            synchronized(this) { path = null }
        }
    }

    override fun close() {
        val owned = synchronized(this) {
            if (consuming) return
            path.also { path = null }
        }
        owned?.let(::deleteOwnedSpool)
    }
}

private fun deleteOwnedSpool(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        scheduleOwnedSpoolDeletion(path)
    } catch (_: SecurityException) {
        scheduleOwnedSpoolDeletion(path)
    } catch (_: UnsupportedOperationException) {
        scheduleOwnedSpoolDeletion(path)
    }
}

private fun scheduleOwnedSpoolDeletion(path: Path) {
    try {
        path.toFile().deleteOnExit()
    } catch (_: SecurityException) {
        // 플랫폼이 unlink와 종료 시 삭제를 모두 거부하면 더 넓은 권한을 요청하지 않는다.
    } catch (_: UnsupportedOperationException) {
        // 기본 파일 시스템이 아닌 경우에도 분석 결과 대신 정리 오류를 노출하지 않는다.
    }
}
