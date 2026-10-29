package dev.kartograph.export

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** 닫힌 완성 디렉터리만 게시하고 실패 시 이번 호출의 staging만 제거한다. */
public object GraphCsvPublication {
    /** destination은 새 경로여야 하며 외부의 기존 디렉터리·파일을 덮어쓰지 않는다. */
    public fun publish(destination: Path, write: (Path) -> Unit) {
        val target = destination.toAbsolutePath().normalize()
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException("graph export destination exists")
        val parent = requireNotNull(target.parent)
        val slot = java.security.MessageDigest.getInstance("SHA-256").digest(target.fileName.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val reservation = parent.resolve(".kartograph-export-$slot.lock")
        Files.createFile(reservation)
        var staging: Path? = null
        var published = false
        var primary: Throwable? = null
        try {
            staging = Files.createTempDirectory(parent, ".kartograph-export-")
            write(staging)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException("graph export destination exists")
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            published = true
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                if (!published && staging != null) {
                    removeOwnedStaging(staging)
                }
            } catch (cleanup: IOException) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            } finally {
                try { Files.deleteIfExists(reservation) }
                catch (cleanup: IOException) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    /** 이번 호출이 만든 트리만 순회하며 symlink의 대상을 따라가지 않는다. */
    private fun removeOwnedStaging(staging: Path) {
        Files.walkFileTree(staging, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
