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
        try {
            Files.walkFileTree(projectRoot, SourceVisitor(projectRoot, pathsByFileName))
        } catch (error: IOException) {
            // 인덱스 실패는 오류로 중단하지 않고 기존 보수적 basename 매칭으로 폴백한다.
            return emptyMap()
        }
        return pathsByFileName
    }

    /**
     * prune 대상 디렉터리는 하위로 내려가지 않고(SKIP_SUBTREE), 개별 파일 순회 실패는 건너뛴다.
     * Files.walk와 달리 순회 중 I/O 오류가 UncheckedIOException으로 전체 실행을 중단시키지 않는다.
     */
    private class SourceVisitor(
        private val projectRoot: Path,
        private val pathsByFileName: MutableMap<String, MutableSet<Path>>,
    ) : SimpleFileVisitor<Path>() {
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

        // 읽을 수 없는 파일·디렉터리는 전체 인덱스를 실패시키지 않고 해당 항목만 건너뛴다.
        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult = FileVisitResult.CONTINUE
    }

    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val PRUNED_DIRECTORIES = setOf(
        "build", ".git", ".gradle", ".kotlin", ".worktrees", "node_modules", ".omx", ".claude", "out",
    )
}
