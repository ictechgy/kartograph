package dev.kartograph.cli

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 프로젝트 source 파일명을 실제 경로 집합으로 인덱싱한다.
 * debug 정보가 basename만 남겼을 때 `--since` 매칭의 모호성을 줄이는 데 쓴다.
 */
internal object SourcePaths {
    /** 빌드·의존성 디렉터리는 순회 자체를 건너뛰고 파일명별 source 경로를 모은다. */
    fun byFileName(projectRoot: Path): Map<String, Set<Path>> {
        if (!Files.isDirectory(projectRoot)) return emptyMap()
        val pathsByFileName = mutableMapOf<String, MutableSet<Path>>()
        val visitor = SourceVisitor(projectRoot, pathsByFileName)
        try {
            Files.walkFileTree(projectRoot, visitor)
        } catch (error: IOException) {
            return emptyMap()
        } catch (error: SecurityException) {
            return emptyMap()
        }
        // 일부 항목을 읽지 못한 부분 인덱스는 유일 후보를 잘못 확정해 under-reporting을 만들 수 있으므로
        // 신뢰하지 않고 전체를 보수적 basename fallback으로 돌린다.
        if (visitor.encounteredFailure) return emptyMap()
        return pathsByFileName.mapValues { entry -> entry.value.toSet() }
    }

    /**
     * prune 대상 디렉터리는 하위로 내려가지 않고(SKIP_SUBTREE), 개별 항목 순회 실패를 기록한다.
     * Files.walk와 달리 순회 중 I/O 오류가 UncheckedIOException으로 전체 실행을 중단시키지 않는다.
     */
    private class SourceVisitor(
        private val projectRoot: Path,
        private val pathsByFileName: MutableMap<String, MutableSet<Path>>,
    ) : SimpleFileVisitor<Path>() {
        /** 순회 중 하나라도 읽지 못하면 true가 되어 호출부가 부분 인덱스를 거부하게 한다. */
        var encounteredFailure: Boolean = false
            private set

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            val name = dir.fileName?.toString()
            return if (dir != projectRoot && name != null && name in PRUNED_DIRECTORIES) {
                FileVisitResult.SKIP_SUBTREE
            } else {
                FileVisitResult.CONTINUE
            }
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val name = file.fileName.toString()
            if (name.substringAfterLast('.', "") in SOURCE_EXTENSIONS) {
                pathsByFileName.getOrPut(name) { mutableSetOf() }.add(file.toAbsolutePath().normalize())
            }
            return FileVisitResult.CONTINUE
        }

        // 읽을 수 없는 항목은 전체 실행을 중단시키지 않지만, 부분 인덱스를 신뢰하지 않게 실패를 기록한다.
        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
            encounteredFailure = true
            return FileVisitResult.CONTINUE
        }
    }

    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val PRUNED_DIRECTORIES = setOf(
        "build", ".git", ".gradle", ".kotlin", ".worktrees", "node_modules", ".omx", ".claude", "out",
    )
}
