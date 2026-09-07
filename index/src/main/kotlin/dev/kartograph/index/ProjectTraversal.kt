package dev.kartograph.index

import java.nio.file.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 프로젝트 source 탐색이 공유하는 가지치기 규칙이다.
 *
 * 세 source 스캐너([SourcePathIndex], [BridgeFactScanner], [RuntimeLimitationScanner])가 각자 다른
 * 제외 집합을 쓰면 어시스턴트·캐시 디렉터리의 코드가 교환 문서로 수확되므로, 한 벌의 집합만 둔다.
 * 기본은 `docs/DECISION-truth-source.md`의 정본 6종이고, 빌드 산출물·도구 캐시는 project source가
 * 아니어서 탐색 비용만 지배하므로 함께 제외한다(`docs/RESEARCH.md`의 가지치기 실측 참조).
 */
internal object ProjectTraversal {
    /** 열거 뒤 바뀐 링크도 읽기 직전에 다시 검사하고 최종 파일 링크를 따라 열지 않는다. */
    fun readSourceLines(projectRoot: Path, source: Path): List<String> {
        val root = projectRoot.toRealPath()
        val realSource = source.toRealPath()
        if (!realSource.startsWith(root) || isPrunedSource(root, realSource)) {
            throw IOException("source file is outside the allowed project sources")
        }
        return Files.newInputStream(realSource, LinkOption.NOFOLLOW_LINKS).bufferedReader().use { it.readLines() }
    }

    /** 실제 프로젝트 경계 안의 source만 방문하고 제외 디렉터리는 열거하지 않는다. */
    fun walkSources(projectRoot: Path, includeTests: Boolean = false, visit: (Path) -> Unit) {
        val root = projectRoot.toRealPath()
        fun pruned(path: Path): Boolean = if (includeTests) isPruned(root, path) else isPrunedSource(root, path)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (pruned(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString().substringAfterLast('.', "") !in setOf("kt", "java") || pruned(file)) {
                    return FileVisitResult.CONTINUE
                }
                val realFile = file.toRealPath()
                if (!realFile.startsWith(root)) throw IOException("source file is outside the project root")
                if (!pruned(realFile) && Files.isRegularFile(realFile)) visit(file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 탐색에서 통째로 건너뛰는 디렉터리 이름이다. 경로 어느 깊이에 나와도 제외한다. */
    val PRUNED_DIRECTORY_NAMES: Set<String> = setOf(
        ".git",
        ".gradle",
        ".omx",
        ".worktrees",
        ".claude",
        "node_modules",
        "build",
        ".idea",
        ".kotlin",
        "out",
    )

    /** `src/` 아래에서 project source로 보지 않는 source set 디렉터리 이름이다. */
    val PRUNED_SOURCE_SETS: Set<String> = setOf("test", "androidTest", "testFixtures")

    /**
     * 가지치기 디렉터리 아래에 있는지 확인한다.
     *
     * @param projectRoot 탐색 기준이 되는 프로젝트 루트
     * @param path 검사할 파일 또는 디렉터리 경로
     * @return 제외 대상이면 true
     */
    fun isPruned(projectRoot: Path, path: Path): Boolean =
        relativeSegments(projectRoot, path).any(PRUNED_DIRECTORY_NAMES::contains)

    /**
     * source 탐색에서 제외할 경로인지 확인한다. 가지치기 디렉터리에 더해 `src/<test 집합>`도 제외한다.
     *
     * test 컴파일물의 class debug 정보도 경로 해석 대상이므로 [SourcePathIndex]는 이 검사가 아닌
     * [isPruned]만 써서 test source를 인덱스에 남긴다. bridges·staleness는 test를 project 사실에서
     * 제외하는 기존 설계를 유지한다.
     *
     * @param projectRoot 탐색 기준이 되는 프로젝트 루트
     * @param path 검사할 source 파일 경로
     * @return 제외 대상이면 true
     */
    fun isPrunedSource(projectRoot: Path, path: Path): Boolean {
        val segments = relativeSegments(projectRoot, path)
        if (segments.any(PRUNED_DIRECTORY_NAMES::contains)) return true
        return segments.windowed(2).any { (first, second) ->
            first == "src" && second in PRUNED_SOURCE_SETS
        }
    }

    /** project 기준 상대경로를 세그먼트 목록으로 만든다. */
    private fun relativeSegments(projectRoot: Path, path: Path): List<String> =
        projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
            .map(Path::toString)
}
