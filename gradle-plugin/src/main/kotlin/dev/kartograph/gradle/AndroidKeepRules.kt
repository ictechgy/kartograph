package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path

/** minify 이전에는 없을 수 있는 AGP build 출력만 제외하고 소스 트리의 누락은 오류로 남긴다. */
internal object AndroidKeepRules {
    fun existing(files: List<Path>, buildDirectory: Path): List<Path> {
        val buildRoot = canonical(buildDirectory)
        return files.filter { Files.exists(it) || !canonical(it).startsWith(buildRoot) }
    }

    /** 존재하지 않는 경로도 기존 조상의 symbolic link를 같은 기준으로 정규화한다. */
    private fun canonical(path: Path): Path = try {
        Path.of(path.toFile().canonicalPath)
    } catch (_: java.io.IOException) {
        path.toAbsolutePath().normalize()
    }
}
