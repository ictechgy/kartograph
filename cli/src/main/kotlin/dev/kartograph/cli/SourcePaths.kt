package dev.kartograph.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 프로젝트 source 파일명을 실제 경로 집합으로 인덱싱한다.
 * debug 정보가 basename만 남겼을 때 `--since` 매칭의 모호성을 줄이는 데 쓴다.
 */
internal object SourcePaths {
    /** 빌드·의존성 디렉터리를 가지치기하고 파일명별 source 경로를 모은다. */
    fun byFileName(projectRoot: Path): Map<String, Set<Path>> {
        if (!Files.isDirectory(projectRoot)) return emptyMap()
        val sources = try {
            Files.walk(projectRoot).use { paths ->
                paths.filter { path -> Files.isRegularFile(path) }
                    .filter { path -> path.fileName.toString().substringAfterLast('.', "") in SOURCE_EXTENSIONS }
                    .filter { path -> !isPruned(projectRoot, path) }
                    .map { path -> path.toAbsolutePath().normalize() }
                    .toList()
            }
        } catch (error: IOException) {
            // 인덱스 실패는 오류로 중단하지 않고 기존 보수적 basename 매칭으로 폴백한다.
            return emptyMap()
        }
        return sources.groupBy { path -> path.fileName.toString() }
            .mapValues { entry -> entry.value.toSet() }
    }

    private fun isPruned(projectRoot: Path, path: Path): Boolean {
        val relative = projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
        return relative.map(Path::toString).any(PRUNED_DIRECTORIES::contains)
    }

    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val PRUNED_DIRECTORIES = setOf(
        "build", ".git", ".gradle", ".kotlin", ".worktrees", "node_modules", ".omx", ".claude", "out",
    )
}
