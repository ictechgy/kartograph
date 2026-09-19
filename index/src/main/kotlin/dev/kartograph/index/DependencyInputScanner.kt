package dev.kartograph.index

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope
import dev.kartograph.core.DependencyUsage
import java.nio.file.Path
import java.nio.file.Files
import java.io.IOException

/** CLI와 Gradle adapter가 같은 컴파일 입력 해석을 재사용한다. 분석 정책은 포함하지 않는다. */
public data class ScannedDependencyInputs(
    val main: DependencyUsage,
    val test: DependencyUsage?,
    val artifactClasses: Map<String, Set<String>>,
)

/** main/test class와 선언·해석된 artifact 목록을 동일한 경로 규칙으로 읽는다. */
public object DependencyInputScanner {
    public fun scan(
        project: Path,
        classRoots: Iterable<Path>,
        testClassRoots: List<Path>,
        declared: Collection<DeclaredDependency>,
        resolved: Collection<DeclaredDependency>?,
    ): ScannedDependencyInputs {
        val scanner = DependencyUsageScanner()
        val main = scanner.scan(classRoots)
        val test = testClassRoots.takeIf { it.isNotEmpty() }?.let(scanner::scan)
        val artifacts = DependencyArtifactScanner()
        val cached = mutableMapOf<Path, Set<String>>()
        val result = linkedMapOf<String, Set<String>>()
        (declared + resolved.orEmpty()).forEach { dependency ->
            if (dependency.scope in ignored || (test == null && dependency.scope in testScopes)) return@forEach
            val candidate = Path.of(dependency.artifact).let { if (it.isAbsolute) it else project.resolve(it) }
            if (!Files.isDirectory(candidate) && !Files.isRegularFile(candidate)) {
                throw ClassIndexingException("dependency artifact for ${dependency.coordinate.filterNot(Char::isISOControl)} does not exist")
            }
            try {
                val path = candidate.toRealPath()
                result[dependency.artifact] = cached.getOrPut(path) { artifacts.scan(path) }
            } catch (error: IOException) {
                throw ClassIndexingException("dependency artifact cannot be read", error)
            }
        }
        return ScannedDependencyInputs(main, test, result)
    }

    private val ignored = setOf(DependencyScope.RUNTIME_ONLY, DependencyScope.TEST_RUNTIME_ONLY,
        DependencyScope.ANNOTATION_PROCESSOR, DependencyScope.KAPT, DependencyScope.KSP)
    private val testScopes = setOf(DependencyScope.TEST_IMPLEMENTATION, DependencyScope.TEST_COMPILE_ONLY)
}
