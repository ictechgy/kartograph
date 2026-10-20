package dev.kartograph.gradle

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import dev.kartograph.export.DependencyListCodec
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * Gradle 결과 객체는 task에 남기지 않고 문자열 입력과 명시적인 미해석 범위로 변환한다.
 * task 입력의 artifact 열에는 checkout·Gradle user home 위치를 뺀 [ArtifactLocationKeys] 키를 쓰고, 실제 파일은 [locations]로 잇는다.
 */
internal data class DependencyResolution(
    val declared: List<String>,
    val resolved: List<String>,
    val limitations: List<String>,
    /** 위치 키에서 실제 artifact 파일 경로로 가는 대응이다. task 입력이 아니며 파일 내용은 task의 dependencyArtifacts가 추적한다. */
    val locations: Map<String, String>,
) {
    companion object {
        private val priority = listOf(DependencyScope.API, DependencyScope.COMPILE_ONLY_API, DependencyScope.IMPLEMENTATION,
            DependencyScope.COMPILE_ONLY, DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY)

        /** [rootDirectory]·[gradleUserHome]은 위치 키의 기준이다. provider가 Project를 붙잡지 않도록 파일 값으로 받는다. */
        fun capture(root: ResolvedComponentResult, artifacts: Set<ResolvedArtifactResult>, requests: List<String>, test: Boolean,
            rootDirectory: File, gradleUserHome: File): DependencyResolution {
            val scopes = requests.distinct().groupBy({ it.substringAfter('\t') }, {
                requireNotNull(DependencyScope.fromOption(it.substringBefore('\t')))
            })
            val direct = root.dependencies.filterIsInstance<ResolvedDependencyResult>().filterNot { it.isConstraint }
                .groupBy { it.selected.id }
            val locationKeys = ArtifactLocationKeys(rootDirectory, gradleUserHome)
            val declared = mutableListOf<DeclaredDependency>()
            val resolved = mutableListOf<DeclaredDependency>()
            val locations = mutableMapOf<String, String>()
            val unknown = mutableSetOf<String>()
            val matchedRequests = mutableSetOf<String>()
            for (artifact in artifacts.sortedBy { it.file.absolutePath }) {
                val id = artifact.id.componentIdentifier
                val keys = direct[id].orEmpty().mapNotNull { edge ->
                    when (val requested = edge.requested) {
                        is ModuleComponentSelector -> "module:${requested.group}:${requested.module}"
                        is ProjectComponentSelector -> "project:${requested.projectPath}"
                        else -> null
                    }
                } + "file:${artifact.file.absolutePath}"
                val candidates = keys.flatMap { scopes[it].orEmpty() }.distinct()
                matchedRequests += keys.filter(scopes::containsKey)
                val scope = priority.firstOrNull(candidates::contains)
                val location = locationKeys.of(artifact.file)
                val coordinate = coordinate(id, artifact.file, location)
                if (scope == null && id in direct) {
                    unknown += coordinate
                    continue
                }
                locations[location] = artifact.file.absolutePath
                resolved += DeclaredDependency(coordinate, if (test) DependencyScope.TEST_IMPLEMENTATION else DependencyScope.IMPLEMENTATION, location)
                if (scope != null && (!test || scope in setOf(DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY))) {
                    declared += DeclaredDependency(coordinate, scope, location)
                }
            }
            val limitations = buildList {
                if (unknown.isNotEmpty()) add("${unknown.size} direct components have no supported declaration scope; their ownership was not judged")
                val unavailable = scopes.keys - matchedRequests
                if (unavailable.isNotEmpty()) add("${unavailable.size} dependency declarations have no artifact in the selected compile classpath; platform, runtime and processor-only use was not judged")
            }
            fun lines(values: List<DeclaredDependency>) = DependencyListCodec.render(values).lineSequence().filter(String::isNotBlank).toList()
            return DependencyResolution(lines(declared), lines(resolved), limitations, locations)
        }

        /**
         * file 의존성 coordinate다. 파일 이름과 위치 키의 SHA-256 앞 6바이트로 같은 이름의 다른 파일을 구분한다.
         * 키가 checkout·Gradle user home 위치를 담지 않으므로 두 디렉터리 안 파일의 보고서 coordinate와 baseline 지문은
         * 그 위치와 무관하다. 두 디렉터리 밖 파일은 root와의 상대 배치가 같을 때만 같다([ArtifactLocationKeys]).
         * coordinate는 cache 출력이라 task 입력인 위치 키로만 만든다. 절대 경로를 섞으면 다른 배치의 결과가 복원된다.
         */
        internal fun fileCoordinate(fileName: String, locationKey: String): String =
            "file:" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "#" +
                MessageDigest.getInstance("SHA-256").digest(locationKey.toByteArray(StandardCharsets.UTF_8))
                    .take(6).joinToString("") { "%02x".format(it) }

        private fun coordinate(id: ComponentIdentifier, file: File, locationKey: String): String = when (id) {
            is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
            is ProjectComponentIdentifier -> "project:" + URLEncoder.encode(id.displayName, StandardCharsets.UTF_8)
            else -> fileCoordinate(file.name, locationKey)
        }
    }
}

/**
 * artifact 파일 위치를 checkout·Gradle user home 위치와 무관한 키로 바꾼다. build cache가 다른 checkout·머신에서도 맞도록
 * task 입력에는 이 키만 둔다.
 *
 * - root project와 Gradle user home 중 파일을 담은 더 깊은 디렉터리 기준 상대 경로를 `{root}/…`, `{gradle-user-home}/…`로 쓴다.
 *   Gradle user home이 checkout 안에 있어도 user home 기준이 되어 그 위치가 키에 들어가지 않는다.
 * - Gradle은 두 디렉터리를 실제 경로로 바꿔 두므로, 디렉터리 별칭(symlink)으로 적힌 파일은 그 디렉터리의 실제 경로로도
 *   맞춰 본다. 파일 이름은 적힌 그대로 둔다.
 * - 둘 다 밖이면 root 기준 `..` 상대 경로다. 두 위치의 배치가 같을 때만 같은 키가 된다. 드라이브가 달라 상대 경로가 없으면
 *   절대 경로(`{absolute}/…`)를 써 재배치를 포기한다.
 * - 구분자는 `/`로 통일해 OS가 달라도 같은 키다. 같은 기준 안의 artifact끼리는 상대 경로 순서가 예전 절대경로 순서와 같아
 *   분석의 대표 artifact 선택이 바뀌지 않는다. 마지막 이름은 언제나 파일 이름이다.
 */
internal class ArtifactLocationKeys(rootDirectory: File, gradleUserHome: File) {
    /** 기준마다 적힌 경로와 실제 경로를 모두 둔다. 같으면 하나다. */
    private val bases = listOf(ROOT to rootDirectory, GRADLE_USER_HOME to gradleUserHome).map { (prefix, directory) ->
        val written = written(directory)
        prefix to listOf(written, real(directory, written)).distinct()
    }

    fun of(file: File): String {
        val written = written(file)
        within(written)?.let { return it }
        // 디렉터리 별칭만 풀고 파일 이름은 적힌 그대로 둔다. 링크 파일도 대상이 아니라 자기 이름으로 보고된다.
        val real = written.parent?.let { parent -> real(parent.toFile(), parent).resolve(written.fileName) } ?: written
        within(real)?.let { return it }
        val root = bases.first().second.last()
        return if (real.root == root.root) "$ROOT/" + root.relativize(real).invariantSeparatorsPathString
        else "$ABSOLUTE/" + real.invariantSeparatorsPathString
    }

    /** [path]를 담은 가장 깊은 기준 디렉터리 기준 키다. 어느 기준에도 없으면 null이다. */
    private fun within(path: Path): String? = bases
        .flatMap { (prefix, directories) -> directories.filter(path::startsWith).map { prefix to it } }
        .maxByOrNull { (_, directory) -> directory.nameCount }
        ?.let { (prefix, directory) -> "$prefix/" + directory.relativize(path).invariantSeparatorsPathString }

    private companion object {
        const val ROOT = "{root}"
        const val GRADLE_USER_HOME = "{gradle-user-home}"
        const val ABSOLUTE = "{absolute}"

        fun written(file: File): Path = file.toPath().toAbsolutePath().normalize()

        /** 별칭을 푼 실제 경로다. 없는 경로도 있는 앞부분까지 푼다. */
        fun real(file: File, written: Path): Path = try {
            file.canonicalFile.toPath()
        } catch (error: IOException) {
            // 실제 경로를 얻지 못하면 적힌 경로로만 키를 만든다. 키의 재배치 범위만 좁아지고 분석 입력은 그대로다.
            written
        }
    }
}
