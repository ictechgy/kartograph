package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path

/**
 * 저장소 안 Gradle 빌드 파일의 `buildConfigField("String", "NAME", "\"…\"")` 선언으로 `BuildConfig.NAME` 값을 푼다.
 *
 * Retrofit `baseUrl(BuildConfig.API_URL)`처럼 빌드 변형마다 값이 다른 base를 다루기 위한 좁은 입력이다. 값은 소스 파일에
 * 가장 가까운 모듈 빌드 파일(`build.gradle.kts`·`build.gradle`)에서만 찾는다 — 다른 모듈의 같은 이름 필드는 다른
 * `BuildConfig` class이기 때문이다. 그 파일의 모든 선언이 문자열 리터럴일 때만 값 집합을 돌려주고(빌드 타입·flavor마다 다른
 * 값이면 여럿), 하나라도 식·보간·속성 참조면 전체를 모른다(null)고 본다. `namespace`가 선언돼 있으면 참조한
 * `BuildConfig`의 패키지가 그 namespace와 같아야 한다. `gradle.properties`·환경 변수·`local.properties` 주입은 모델링하지
 * 않는다.
 *
 * @param projectRoot 모듈 탐색의 상한이 되는 실제 프로젝트 루트다
 */
internal class BuildConfigFields(private val projectRoot: Path) {
    /** 모듈 디렉터리(프로젝트 상대) → 주석을 지운 빌드 파일 내용이다. 빌드 파일이 없으면 빈 문자열이다. */
    private val buildFiles = mutableMapOf<String, String>()

    /**
     * [file]에서 참조한 `BuildConfig.[name]`의 값 집합이다.
     *
     * @param referencePackage 참조가 가리키는 `BuildConfig`의 패키지다. 한정 이름·import에서 얻고, 없으면 파일 패키지다
     * @return 중복을 뺀 값 목록. 선언이 없거나 리터럴이 아닌 선언이 있거나 namespace가 다르면 null이다
     */
    fun values(file: RouteSourceFile, name: String, referencePackage: String): List<String>? {
        val content = moduleBuildFile(file.relative) ?: return null
        val namespace = NAMESPACE.find(content)?.groupValues?.get(1)
        if (namespace != null && namespace != referencePackage) return null
        val masked = maskStringContents(content)
        val values = BUILD_CONFIG_FIELD.findAll(masked).map { match -> fieldValue(content, masked, match.range.last + 1, name) }
            .filter { it != FieldValue.OtherField }.toList()
        if (values.isEmpty() || values.any { it !is FieldValue.Literal }) return null
        return values.map { (it as FieldValue.Literal).value }.distinct().sorted()
    }

    /** `buildConfigField` 호출 하나를 읽은 결과다. */
    private sealed interface FieldValue {
        /** 다른 이름의 필드다. */
        data object OtherField : FieldValue

        /** 같은 이름(또는 이름을 읽지 못한 호출)인데 값이 리터럴이 아니다. */
        data object Unknown : FieldValue

        /** 같은 이름의 문자열 리터럴 값이다. */
        data class Literal(val value: String) : FieldValue
    }

    /**
     * `buildConfigField` 호출 하나의 값이다. 인자를 읽지 못하면 어느 필드인지 모르므로 [FieldValue.Unknown]이다(보수적).
     * Kotlin DSL(`(…)`)과 Groovy 명령 문법(줄 끝까지)을 모두 읽는다.
     */
    private fun fieldValue(content: String, masked: String, after: Int, name: String): FieldValue {
        val open = content.indexOfFirst(after) { !it.isWhitespace() || it == '\n' }
        val arguments = if (open >= 0 && content[open] == '(') {
            val close = balancedEnd(content, open).takeIf { it > open } ?: return FieldValue.Unknown
            // Kotlin DSL의 끝 쉼표(`…,\n)`)는 인자가 아니다.
            callArguments(content, open, close).let { if (it.lastOrNull()?.isBlank() == true) it.dropLast(1) else it }
        } else {
            // Groovy 명령 문법은 줄 끝이나 같은 줄에서 닫히는 블록(`defaultConfig { … }`)·`;`에서 끝난다.
            val lineEnd = (after until content.length).firstOrNull { masked[it] == '\n' || masked[it] == '}' || masked[it] == ';' } ?: content.length
            val line = "(" + content.substring(after, lineEnd) + ")"
            callArguments(line, 0, line.length - 1)
        }
        if (arguments.size != 3) return FieldValue.Unknown
        val fieldName = scriptString(arguments[1]) ?: return FieldValue.Unknown
        if (fieldName != name) return FieldValue.OtherField
        if (scriptString(arguments[0]) != "String") return FieldValue.Unknown
        val value = scriptString(arguments[2])?.let(::javaStringLiteral) ?: return FieldValue.Unknown
        return FieldValue.Literal(value)
    }

    /** 소스 파일에서 프로젝트 루트까지 올라가며 처음 만나는 모듈 빌드 파일 내용이다. */
    private fun moduleBuildFile(relative: String): String? {
        var directory = relative.substringBeforeLast('/', "")
        while (true) {
            val content = buildFiles.getOrPut(directory) { readBuildFile(directory) }
            if (content.isNotEmpty()) return content
            if (directory.isEmpty()) return null
            directory = directory.substringBeforeLast('/', "")
        }
    }

    /** 모듈 디렉터리의 빌드 파일을 주석을 지워 읽는다. 링크로 프로젝트 밖을 가리키면 읽지 않는다. */
    private fun readBuildFile(directory: String): String {
        val root = projectRoot.toRealPath()
        val base = if (directory.isEmpty()) root else root.resolve(directory)
        val file = BUILD_FILE_NAMES.map(base::resolve).firstOrNull(Files::isRegularFile) ?: return ""
        val real = file.toRealPath()
        if (!real.startsWith(root) || Files.size(real) > MAX_BUILD_FILE_BYTES) return ""
        // 깨진 UTF-8도 예외 없이 읽는다(대체 문자) — 빌드 파일 하나 때문에 스캔 전체가 실패하지 않게 한다.
        return stripComments(String(Files.readAllBytes(real), Charsets.UTF_8))
    }

    private companion object {
        val BUILD_FILE_NAMES = listOf("build.gradle.kts", "build.gradle")
        val BUILD_CONFIG_FIELD = Regex("\\bbuildConfigField\\b")
        val NAMESPACE = Regex("\\bnamespace\\s*=?\\s*[\"']([A-Za-z_][\\w.]*)[\"']")
        /** 빌드 파일 상한이다. 이보다 크면 생성물이거나 비정상 입력으로 보고 읽지 않는다. */
        const val MAX_BUILD_FILE_BYTES = 2L * 1024 * 1024
    }
}

/** [from]부터 [predicate]를 만족하는 첫 위치다. 없으면 -1이다. */
private fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (index in from until length) if (predicate(this[index])) return index
    return -1
}

/**
 * Gradle 스크립트의 문자열 리터럴 인자(`"…"`·`'…'`)를 푼다. 큰따옴표 안의 `$` 보간은 값이 아니므로 null이다.
 */
internal fun scriptString(argument: String): String? {
    val text = argument.trim()
    if (text.length < 2) return null
    val quote = text.first()
    if ((quote != '"' && quote != '\'') || text.last() != quote || text.startsWith("\"\"\"")) return null
    val body = text.substring(1, text.length - 1)
    if (quote == '"' && hasInterpolation(body)) return null
    return decodeLiteral(body)
}

/** 백슬래시로 가리지 않은 `$`가 있으면 보간이다. */
private fun hasInterpolation(body: String): Boolean {
    var index = 0
    while (index < body.length) {
        when (body[index]) {
            '\\' -> index++
            '$' -> return true
        }
        index++
    }
    return false
}

/** `"…"` 모양의 Java 문자열 리터럴 본문을 푼다. 리터럴이 아니면(식·다른 필드 참조) null이다. */
internal fun javaStringLiteral(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return null
    val body = trimmed.substring(1, trimmed.length - 1)
    // 가운데 이스케이프하지 않은 `"`가 있으면 `"a" + X + "b"` 같은 식이다.
    var index = 0
    while (index < body.length) {
        when (body[index]) {
            '\\' -> index++
            '"' -> return null
        }
        index++
    }
    return decodeLiteral(body)
}
