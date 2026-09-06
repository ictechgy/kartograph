package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeId
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 프로젝트 source 파일명을 실제 경로 집합으로 인덱싱한다.
 * class debug 정보가 파일 이름만 남겼을 때 `--since` 매칭과 그래프 경로 해석의 모호성을 줄이는 데 쓴다.
 */
public object SourcePathIndex {
    /**
     * 빌드·의존성 디렉터리는 순회 자체를 건너뛰고 파일명별 source 경로를 모은다.
     *
     * 돌려주는 값은 대조에 쓰는 **절대 정규화 경로**다. 보고서나 교환 문서에 그대로 실으면 로컬 경로가
     * 노출되므로, 노출 표면으로 나가는 값은 [resolve]처럼 project 기준으로 상대화한 뒤에 쓴다.
     */
    public fun byFileName(projectRoot: Path): Map<String, Set<Path>> {
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
     * 그래프 정점의 source file 이름을 project 기준 상대경로로 해석한다.
     *
     * 유일하게 일치하는 source가 있을 때만 확정하고, 모호하거나 project 밖에서 컴파일된 class는
     * 추측하지 않는다. 확정하지 못한 수는 계량된 한계로 함께 돌려준다.
     */
    public fun resolve(graph: CodeGraph, projectRoot: Path): SourcePathResolution {
        // byFileName은 절대 정규화 경로를 모으므로 symlink를 지나는 project root도 같은 기준으로 맞춘다.
        val root = try {
            projectRoot.toRealPath()
        } catch (error: IOException) {
            projectRoot
        } catch (error: SecurityException) {
            projectRoot
        }
        val pathsByFileName = byFileName(root)
        val byNodeId = mutableMapOf<NodeId, String>()
        var located = 0
        var unresolved = 0
        graph.nodeIds.forEach { nodeId ->
            val node = graph.nodes.getValue(nodeId)
            val sourceFileName = node.location?.path ?: return@forEach
            located++
            // 이름이 여러 파일과 맞거나(모호) project 밖에서 컴파일된 class(무일치)는 확정하지 않는다.
            val match = pathsByFileName[sourceFileName]?.singleOrNull()
            // 이름이 같기만 한 무관한 파일을 사실로 단언하지 않도록 선언의 package와 후보의 위치도 대조한다.
            if (match == null || !matchesPackage(node.id, root.relativize(match))) {
                unresolved++
                return@forEach
            }
            // 교환 문서가 플랫폼과 무관하게 같아지도록 항상 '/'로 잇는다.
            byNodeId[nodeId] = root.relativize(match).joinToString("/")
        }
        return SourcePathResolution(
            byNodeId = byNodeId,
            limitations = buildList {
                if (unresolved > 0) add(
                    "unresolved-source-paths: $unresolved of $located located node(s) did not match exactly one project source file",
                )
                addAll(missingSourcePaths(graph))
            },
        )
    }

    /**
     * 위치를 복원하지 못한 정점 수를 계량된 한계로 만든다.
     *
     * 경로 문자열이 아니라 개수만 내므로 project를 몰라도 계산할 수 있다. 경로 해석을 요청하지 않았다고
     * 해서 "알릴 한계가 없다"고 보고하지 않도록, 경로 해석과 분리해 항상 쓸 수 있게 둔다.
     */
    public fun missingSourcePaths(graph: CodeGraph): List<String> {
        val missing = graph.nodeIds.count { nodeId -> graph.nodes.getValue(nodeId).location == null }
        if (missing == 0) return emptyList()
        return listOf(
            "missing-source-paths: $missing of ${graph.nodeCount} node(s) have no source file in the class debug attributes",
        )
    }

    /**
     * 선언의 JVM package가 후보 source 파일이 놓인 디렉터리의 suffix인지 확인한다.
     *
     * basename만 같은 다른 모듈·project 밖 class가 무관한 파일로 확정되는 것을 막는다.
     * package와 디렉터리가 다른 것은 Kotlin에서 합법이므로, 일치하지 않으면 틀렸다고 단정하지 않고
     * 확정만 포기해 보수적으로 미확정으로 센다. default package는 대조할 것이 없어 통과시킨다.
     */
    private fun matchesPackage(nodeId: NodeId, relativePath: Path): Boolean {
        val owner = nodeId.value.substringAfter(':').substringBefore('#')
        val packageSegments = owner.split('/').dropLast(1)
        if (packageSegments.isEmpty()) return true
        val directorySegments = relativePath.map(Path::toString).dropLast(1)
        if (directorySegments.size < packageSegments.size) return false
        return directorySegments.takeLast(packageSegments.size) == packageSegments
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

/**
 * class debug 정보의 source file 이름을 project 기준 상대경로로 해석한 결과다.
 * 유일하게 확정된 경로만 담고, 확정하지 못한 정점 수는 계량된 한계로 알린다.
 */
public data class SourcePathResolution(
    val byNodeId: Map<NodeId, String> = emptyMap(),
    val limitations: List<String> = emptyList(),
)
