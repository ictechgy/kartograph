package dev.kartograph.gradle

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import dev.kartograph.export.DependencyListCodec
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/** Gradle 결과 객체는 task에 남기지 않고 문자열 입력과 명시적인 미해석 범위로 변환한다. */
internal data class DependencyResolution(val declared: List<String>, val resolved: List<String>, val limitations: List<String>) {
    companion object {
        private val priority = listOf(DependencyScope.API, DependencyScope.COMPILE_ONLY_API, DependencyScope.IMPLEMENTATION,
            DependencyScope.COMPILE_ONLY, DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY)

        fun capture(root: ResolvedComponentResult, artifacts: Set<ResolvedArtifactResult>, requests: List<String>, test: Boolean): DependencyResolution {
            val scopes = requests.distinct().groupBy({ it.substringAfter('\t') }, {
                requireNotNull(DependencyScope.fromOption(it.substringBefore('\t')))
            })
            val direct = root.dependencies.filterIsInstance<ResolvedDependencyResult>().filterNot { it.isConstraint }
                .groupBy { it.selected.id }
            val declared = mutableListOf<DeclaredDependency>()
            val resolved = mutableListOf<DeclaredDependency>()
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
                val coordinate = coordinate(id, artifact.file)
                if (scope == null && id in direct) {
                    unknown += coordinate
                    continue
                }
                resolved += DeclaredDependency(coordinate, if (test) DependencyScope.TEST_IMPLEMENTATION else DependencyScope.IMPLEMENTATION, artifact.file.absolutePath)
                if (scope != null && (!test || scope in setOf(DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY))) {
                    declared += DeclaredDependency(coordinate, scope, artifact.file.absolutePath)
                }
            }
            val limitations = buildList {
                if (unknown.isNotEmpty()) add("${unknown.size} direct components have no supported declaration scope; their ownership was not judged")
                val unavailable = scopes.keys - matchedRequests
                if (unavailable.isNotEmpty()) add("${unavailable.size} dependency declarations have no artifact in the selected compile classpath; platform, runtime and processor-only use was not judged")
            }
            fun lines(values: List<DeclaredDependency>) = DependencyListCodec.render(values).lineSequence().filter(String::isNotBlank).toList()
            return DependencyResolution(lines(declared), lines(resolved), limitations)
        }

        private fun coordinate(id: ComponentIdentifier, file: File): String = when (id) {
            is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
            is ProjectComponentIdentifier -> "project:" + URLEncoder.encode(id.displayName, StandardCharsets.UTF_8)
            else -> "file:" + URLEncoder.encode(file.name, StandardCharsets.UTF_8) + "#" +
                MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
                    .take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
