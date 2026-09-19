package dev.kartograph.export

import dev.kartograph.core.DeclaredDependency
import dev.kartograph.core.DependencyScope

/**
 * 선언 의존성 목록을 `coordinate<TAB>scope<TAB>artifact` TSV로 교환한다.
 * 빈 줄과 `#` 주석을 허용하고, 알 수 없는 scope·빈 필드·잘못된 열 수는 부분 적용 없이 거부한다.
 */
public object DependencyListCodec {
    /** 좌표·scope·artifact 순으로 정렬해 같은 입력에 같은 문서를 만든다. */
    public fun render(dependencies: Collection<DeclaredDependency>): String =
        dependencies.distinct()
            .sortedWith(compareBy({ it.coordinate }, { it.scope.option }, { it.artifact }))
            .joinToString(separator = "\n", postfix = "\n") { dependency ->
                "${dependency.coordinate}\t${dependency.scope.option}\t${dependency.artifact}"
            }

    public fun parse(content: String): List<DeclaredDependency> {
        val dependencies = mutableListOf<DeclaredDependency>()
        content.split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r").trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val fields = rawLine.removeSuffix("\r").split('\t')
            val lineNumber = index + 1
            require(fields.size == 3) { "dependency list has a malformed line at line $lineNumber" }
            val coordinate = fields[0].trim()
            val scope = DependencyScope.fromOption(fields[1].trim())
            val artifact = fields[2].trim()
            require(coordinate.isNotEmpty() && coordinate.none(Char::isWhitespace)) {
                "dependency list has an invalid coordinate at line $lineNumber"
            }
            require(scope != null) { "dependency list has an unknown scope at line $lineNumber" }
            require(artifact.isNotEmpty()) { "dependency list has a blank artifact at line $lineNumber" }
            dependencies += DeclaredDependency(coordinate, scope, artifact)
        }
        return dependencies.distinct()
    }
}
