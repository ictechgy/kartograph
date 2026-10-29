package dev.kartograph.gradle

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.index.ContentFingerprint
import java.nio.file.Files
import java.nio.file.Path

/**
 * compiler witness의 `external/` 입력을 이번 capture의 후보 파일에 다시 연결한다.
 *
 * 후보는 우선순위 순서의 그룹 목록이다. compiler가 직접 선언한 입력을 먼저 두고 snapshot 전용 classpath를 뒤에 둔다.
 * 같은 JAR가 Gradle cache와 AGP transform 출력처럼 여러 위치에 같은 바이트로 존재하면 이전에는 "ambiguous"로
 * capture 전체가 실패했다. 내용이 같은 일반 파일은 신선도 판정 결과가 같으므로 compiler 선언 입력을 우선해
 * 결정적으로 고른다. 디렉터리는 비어 있는 서로 다른 출력이 같은 해시를 가질 수 있어 바꿔 연결하지 않고,
 * task·입력·후보를 밝힌 오류로 거부한다.
 *
 * @property project 모듈 project 디렉터리이며 오류 문구의 후보를 상대 경로로 줄이는 기준이다
 * @property candidateGroups 우선순위 순서의 후보 경로 그룹이다. 같은 경로가 여러 그룹에 있으면 앞 그룹이 우선한다
 */
internal class CompilerInputBinder(private val project: Path, candidateGroups: List<List<Path>>) {
    /** 경로별 가장 높은 우선순위(작은 값)와 전체 후보의 결정적 순서다. */
    private val candidates: List<Pair<Path, Int>> = buildMap {
        candidateGroups.forEachIndexed { rank, group -> group.forEach { putIfAbsent(it, rank) } }
    }.toList()

    /** 한 capture 안에서 같은 경로·역할의 해시를 다시 읽지 않는다. */
    private val hashes = mutableMapOf<Pair<Path, String>, String>()

    /**
     * 기록된 [input]과 내용이 같은 후보 하나를 고른다.
     *
     * @param witness 오류 문구에 compiler task를 밝히기 위한 witness다
     * @param input 연결할 `external/` 입력이다
     * @param located 역할에 맞게 거른 후보다. `null`이면 전체 후보를 역할(파일·디렉터리)로 거른다
     * @param taken 같은 witness에서 이미 연결한 경로다. 같은 내용의 입력이 여럿이면 서로 다른 파일에 짝짓는다
     * @return 연결할 후보 경로
     * @throws IllegalArgumentException 후보가 없거나, 내용이 같은 디렉터리가 여럿이라 결정할 수 없을 때
     */
    fun bind(witness: BuildWitness, input: InputFingerprint, located: List<Path>?, taken: Set<Path>): Path {
        val eligible = (located ?: candidates.map { it.first }.filter { path ->
            if (input.role == "directory-watch") Files.isDirectory(path) else Files.exists(path)
        }).toSet()
        val matches = candidates.filter { (path, _) ->
            path in eligible && hashes.getOrPut(path to input.role) { ContentFingerprint.hashInput(path, input.role) } == input.sha256
        }
        require(matches.isNotEmpty()) {
            "compiler input binding is missing for ${witness.artifact}: no selected input matches the recorded ${input.role} " +
                "input ${input.path} among ${eligible.size} candidates; rebuild the compilation and include its declared inputs"
        }
        if (matches.size == 1) return matches.single().first
        require(matches.all { Files.isRegularFile(it.first) }) {
            "compiler input binding is ambiguous for ${witness.artifact}: recorded ${input.role} input ${input.path} matches " +
                "${matches.size} directories with identical content (${matches.take(MAX_LISTED).joinToString { describe(it.first) }}); " +
                "give each output a distinct location or declare only the compiler's own directory"
        }
        val preferred = matches.filter { it.second == matches.minOf { match -> match.second } }.map { it.first }
        return preferred.firstOrNull { it !in taken } ?: preferred.first()
    }

    /** 오류 문구용 후보 표기다. project 안은 상대 경로, 밖은 끝의 두 이름만 보여 절대 경로를 드러내지 않는다. */
    private fun describe(path: Path): String {
        val root = project.toAbsolutePath().normalize()
        val absolute = path.toAbsolutePath().normalize()
        if (absolute.startsWith(root)) return root.relativize(absolute).toString().replace('\\', '/')
        val tail = (maxOf(0, absolute.nameCount - 2) until absolute.nameCount).joinToString("/") { absolute.getName(it).toString() }
        return "<external>/$tail"
    }

    private companion object {
        /** 오류 문구에 나열할 후보 수의 상한이다. */
        const val MAX_LISTED = 5
    }
}
