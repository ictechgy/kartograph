package dev.kartograph.index

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 한 모듈의 Spring Boot 설정이다. 기본 프로필(기본 문서 + `spring.profiles.active`로 켠 프로필)의 값과
 * 그 밖의 프로필이 바꾸는 키를 따로 둔다.
 *
 * 기본 프로필만 템플릿에 쓴다. 다른 프로필이 같은 키를 다르게 정하면 값은 기본 프로필 것을 쓰되 호출자가
 * 한계로 알리게 한다. 환경 변수·명령행·config server 재정의는 모델링하지 않는다.
 *
 * @property root 모듈 루트의 프로젝트 상대 경로다(`""`는 프로젝트 루트)
 * @property hasConfig 모듈에 application 설정 파일이 하나 이상 있는지다
 * @property unreadable 설정 파일 하나라도 읽거나 해석하지 못했다. 그러면 어떤 키도 확정하지 않는다([Lookup.Unknown])
 */
internal class SpringModuleConfig(
    val root: String,
    val hasConfig: Boolean,
    private val effective: Map<String, String>,
    private val otherProfiles: Map<String, Set<String>>,
    val unreadable: Boolean = false,
) {
    /** 키 조회 결과다. */
    sealed interface Lookup {
        /** 기본 프로필 값이다. [profileDependent]면 다른 프로필이 다른 값으로 재정의한다. */
        data class Value(val text: String, val profileDependent: Boolean) : Lookup

        /** 기본 프로필에는 없고 다른 프로필에만 있다. */
        data object OtherProfilesOnly : Lookup

        /** 저장소 안 어디에도 없다. */
        data object Absent : Lookup

        /** 설정을 읽지 못했거나 설정 후보끼리 값이 달라 확정하지 못한다. */
        data object Unknown : Lookup
    }

    /** [key]를 relaxed binding으로 찾는다. */
    fun lookup(key: String): Lookup {
        if (unreadable) return Lookup.Unknown
        val normalized = SpringConfigDocuments.normalizeKey(key)
        val value = effective[normalized]
        val others = otherProfiles[normalized].orEmpty()
        return when {
            value != null -> Lookup.Value(value, others.any { it != value })
            others.isNotEmpty() -> Lookup.OtherProfilesOnly
            else -> Lookup.Absent
        }
    }

    companion object {
        /**
         * 문서들로 모듈 설정을 만든다. `spring.profiles.active`(없으면 `spring.profiles.default`, 그것도 없으면
         * `default`)가 기본 프로필이다. 활성 프로필 값이 플레이스홀더면 확정하지 못하므로 프로필 문서를 모두 "다른
         * 프로필"로 둔다.
         */
        fun of(root: String, documents: List<SpringConfigDocuments.Document>, unreadable: Boolean = false): SpringModuleConfig {
            val base = documents.filter { it.profiles.isEmpty() }.fold(emptyMap<String, String>()) { acc, doc -> acc + doc.values }
            val active = activeProfiles(base)
            val effective = documents.filter { doc -> doc.profiles.isNotEmpty() && doc.profiles.all { it in active } }
                .fold(base) { acc, doc -> acc + doc.values }
            val others = mutableMapOf<String, MutableSet<String>>()
            documents.filter { doc -> doc.profiles.isNotEmpty() && !doc.profiles.all { it in active } }.forEach { doc ->
                doc.values.forEach { (key, value) -> others.getOrPut(key) { mutableSetOf() } += value }
            }
            return SpringModuleConfig(root, documents.isNotEmpty() || unreadable, effective, others, unreadable)
        }

        private fun activeProfiles(base: Map<String, String>): Set<String> {
            val declared = (base[ACTIVE] ?: base[DEFAULT_PROFILE] ?: "default") + "," + base[INCLUDE].orEmpty()
            if ("\${" in declared) return emptySet()
            return declared.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        }

        private val ACTIVE = SpringConfigDocuments.normalizeKey("spring.profiles.active")
        private val DEFAULT_PROFILE = SpringConfigDocuments.normalizeKey("spring.profiles.default")
        private val INCLUDE = SpringConfigDocuments.normalizeKey("spring.profiles.include")
    }
}

/**
 * 플레이스홀더(`${key}`·`${key:default}`) 해석 결과다.
 *
 * @property text 해석한 문자열이다. 해석하지 못하면 null이다
 * @property usedDefault 저장소 어디서도 정하지 않은 키의 기본값을 썼다
 * @property profileDependent 쓴 값을 다른 프로필이 다르게 재정의한다
 */
internal data class SpringPlaceholderResult(val text: String?, val usedDefault: Boolean = false, val profileDependent: Boolean = false)

/**
 * `PropertySourcesPlaceholderConfigurer`와 같은 모양의 플레이스홀더를 설정 후보들로 푼다.
 *
 * 후보 모듈이 여럿이면(라이브러리 모듈을 쓰는 앱 모듈이 여럿) 모든 후보가 같은 값을 줄 때만 확정한다.
 * `#{…}` SpEL은 풀지 않는다.
 */
internal class SpringPlaceholders(private val candidates: List<SpringModuleConfig>) {
    /** [text]의 모든 플레이스홀더를 푼다. 플레이스홀더가 없으면 그대로다. */
    fun resolve(text: String, depth: Int = 0): SpringPlaceholderResult {
        if ("#{" in text) return SpringPlaceholderResult(null)
        val start = text.indexOf("\${")
        if (start < 0) return SpringPlaceholderResult(text)
        if (depth > MAX_DEPTH) return SpringPlaceholderResult(null)
        val end = placeholderEnd(text, start) ?: return SpringPlaceholderResult(null)
        val inner = resolveInner(text.substring(start + 2, end), depth)
        if (inner.text == null) return inner
        val rest = resolve(text.substring(end + 1), depth)
        return SpringPlaceholderResult(rest.text?.let { text.substring(0, start) + inner.text + it },
            inner.usedDefault || rest.usedDefault, inner.profileDependent || rest.profileDependent)
    }

    /** `key:default` 본문 하나를 푼다. 키 안의 중첩 플레이스홀더도 푼다. */
    private fun resolveInner(body: String, depth: Int): SpringPlaceholderResult {
        val separator = separatorIndex(body)
        val keyResult = resolve(if (separator < 0) body else body.substring(0, separator), depth + 1)
        val key = keyResult.text ?: return keyResult
        val fallback = if (separator < 0) null else body.substring(separator + 1)
        return when (val found = lookupAll(key)) {
            is SpringModuleConfig.Lookup.Value -> resolve(found.text, depth + 1).let { value ->
                value.copy(profileDependent = value.profileDependent || found.profileDependent || keyResult.profileDependent)
            }
            SpringModuleConfig.Lookup.OtherProfilesOnly, SpringModuleConfig.Lookup.Unknown -> SpringPlaceholderResult(null)
            SpringModuleConfig.Lookup.Absent -> fallback?.let { resolve(it, depth + 1).copy(usedDefault = true) } ?: SpringPlaceholderResult(null)
        }
    }

    /** 후보 모두에서 찾는다. 후보가 서로 다른 결과를 주면 확정하지 못한 것(다른 프로필 전용과 같게)으로 본다. */
    private fun lookupAll(key: String): SpringModuleConfig.Lookup {
        val results = candidates.map { it.lookup(key) }.distinct()
        if (results.isEmpty()) return SpringModuleConfig.Lookup.Absent
        return results.singleOrNull() ?: SpringModuleConfig.Lookup.Unknown
    }

    /** 중첩 `${`를 건너뛴 최상위 `:` 위치다. */
    private fun separatorIndex(body: String): Int {
        var depth = 0
        body.forEachIndexed { index, character ->
            when {
                body.startsWith("\${", index) -> depth++
                character == '}' -> depth--
                character == ':' && depth == 0 -> return index
            }
        }
        return -1
    }

    private fun placeholderEnd(text: String, start: Int): Int? {
        var depth = 0
        var index = start
        while (index < text.length) {
            when {
                text.startsWith("\${", index) -> { depth++; index += 2; continue }
                text[index] == '}' -> { depth--; if (depth == 0) return index }
            }
            index++
        }
        return null
    }

    private companion object {
        const val MAX_DEPTH = 16
    }
}

/**
 * 프로젝트의 Spring 빌드·설정 사실이다 — Boot 버전, 웹 스택, 프레임워크 제공 경로, 모듈별 설정.
 *
 * 빌드 파일은 문자열 표지로만 읽는다(Gradle·Maven을 실행하지 않는다). 버전을 여러 값으로 보거나 찾지 못하면
 * 모른다고 둔다.
 *
 * @property bootMajor Spring Boot 주 버전이다. 모르면 null이다
 * @property bootMinor Spring Boot 부 버전이다
 * @property servlet Spring MVC(서블릿) 표지를 봤다
 * @property reactive WebFlux 표지를 봤다
 * @property frameworkRoutes 프로젝트 선언 없이 프레임워크가 등록하는 경로 제공자 이름이다
 * @property modules 모듈 설정이다. 루트가 긴 순서다
 */
internal class SpringProjectConfig(
    val bootMajor: Int?,
    val bootMinor: Int?,
    val servlet: Boolean,
    val reactive: Boolean,
    val frameworkRoutes: List<String>,
    val modules: List<SpringModuleConfig>,
    private val buildTexts: Map<String, String>,
) {
    /**
     * 소스 경로의 설정 후보다. 자기 모듈이 앱 모듈(`@SpringBootApplication`이 있는 모듈)이거나 설정이 있으면 그것이다.
     * 아니면(라이브러리 모듈) 앱 모듈 전부, 앱 모듈을 모르면 설정이 있는 모든 모듈, 그것도 없으면 빈 자기 모듈이다.
     * 후보가 여럿이면 호출자는 모든 후보가 같은 결과를 줄 때만 확정한다.
     *
     * @param applicationRoots `@SpringBootApplication` 선언이 있는 모듈 루트다
     */
    fun candidatesFor(sourcePath: String?, applicationRoots: Set<String> = emptySet()): List<SpringModuleConfig> {
        val own = moduleOf(sourcePath) ?: SpringModuleConfig.of("", emptyList())
        if (own.root in applicationRoots || own.hasConfig) return listOf(own)
        if (applicationRoots.isNotEmpty()) return modules.filter { it.root in applicationRoots }.ifEmpty { listOf(own) }
        return modules.filter { it.hasConfig }.ifEmpty { listOf(own) }
    }

    /** 소스 경로를 담은 가장 깊은 모듈이다. 경로가 없으면 null이다. */
    fun moduleOf(sourcePath: String?): SpringModuleConfig? =
        modules.firstOrNull { module -> sourcePath != null && (module.root.isEmpty() || sourcePath.startsWith(module.root + "/")) }

    /** 모듈 빌드 파일에 [markers] 중 하나가 있는지 본다. 모듈 빌드 파일이 없으면 null이다. */
    fun moduleMentions(root: String, markers: List<String>): Boolean? {
        val texts = buildTexts.filterKeys { it.substringBeforeLast('/', "") == root }.values
        if (texts.isEmpty()) return null
        return texts.any { text -> markers.any(text::contains) }
    }

    companion object {
        /** 서블릿 스택 표지다(Boot 4는 `spring-boot-starter-webmvc`로 이름을 바꿨다). */
        val SERVLET_MARKERS = listOf("spring-boot-starter-web\"", "spring-boot-starter-web'", "spring-boot-starter-web<",
            "spring-boot-starter-web:", "spring-boot-starter-webmvc", "spring-webmvc", "spring-boot-starter-web\n")

        /** WebFlux 스택 표지다. */
        val REACTIVE_MARKERS = listOf("spring-boot-starter-webflux", "spring-webflux")

        /** 프로젝트를 읽는다. 빌드 파일은 가지치기 디렉터리와 `target`·`src` 밖에서 찾는다. */
        fun read(projectRoot: Path): SpringProjectConfig {
            val root = projectRoot.toRealPath()
            val buildFiles = findBuildFiles(root)
            val texts = buildFiles.associate { relative(root, it) to mainDependencyText(withoutComments(it.fileName.toString(), readSmallText(it))) }
            val moduleRoots = (texts.keys.map { it.substringBeforeLast('/', "") } + "").distinct().sortedByDescending { it.length }
            val modules = moduleRoots.map { moduleRoot -> moduleConfig(root, moduleRoot) }
            val joined = texts.values.joinToString("\n")
            val version = bootVersion(texts)
            return SpringProjectConfig(version?.first, version?.second, SERVLET_MARKERS.any(joined::contains),
                REACTIVE_MARKERS.any(joined::contains), frameworkRoutes(joined, modules, version != null), modules, texts)
        }

        private val BUILD_FILE_NAMES = setOf("build.gradle", "build.gradle.kts", "pom.xml", "libs.versions.toml", "gradle.properties")
        private val SKIPPED_DIRECTORIES = ProjectTraversal.PRUNED_DIRECTORY_NAMES + setOf("target", "src")

        private fun findBuildFiles(root: Path): List<Path> {
            val found = mutableListOf<Path>()
            Files.walkFileTree(root, emptySet(), MAX_BUILD_DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && (dir.fileName.toString() in SKIPPED_DIRECTORIES || attrs.isSymbolicLink)) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && file.fileName.toString() in BUILD_FILE_NAMES) found.add(file)
                    return FileVisitResult.CONTINUE
                }
            })
            return found.sorted()
        }

        private fun relative(root: Path, path: Path): String = root.relativize(path).joinToString("/")

        /**
         * 빌드 파일 주석을 지운다 — 주석 처리한 옛 의존성이 스택·버전 표지로 읽히지 않게 한다. Gradle은 `/* */`와 줄 앞이나
         * 공백 뒤의 `//`(URL의 `://`는 남긴다), Maven은 `<!-- -->`, 버전 카탈로그·properties는 `#` 줄 주석이다.
         */
        internal fun withoutComments(fileName: String, text: String): String = when {
            fileName.endsWith(".xml") -> XML_COMMENT.replace(text, "")
            fileName.endsWith(".toml") || fileName.endsWith(".properties") -> HASH_COMMENT.replace(text, "")
            else -> LINE_COMMENT.replace(BLOCK_COMMENT.replace(text, ""), "")
        }

        private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val HASH_COMMENT = Regex("(?m)^\\s*#.*$")
        private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("(?m)(^|\\s)//.*$")

        /**
         * 테스트 전용 의존성을 뺀 빌드 파일 본문이다 — Gradle `test*`·`androidTest*` 구성 줄과 Maven `<scope>test</scope>`
         * 의존성 블록을 지운다. 테스트 클래스패스의 WebFlux 클라이언트가 서블릿 앱을 WebFlux로 오판하게 하지 않기 위해서다.
         */
        internal fun mainDependencyText(text: String): String {
            val gradle = text.lines().filterNot { TEST_CONFIGURATION.containsMatchIn(it) }.joinToString("\n")
            return MAVEN_DEPENDENCY.replace(gradle) { block -> if ("<scope>test</scope>" in block.value) "" else block.value }
        }

        private val TEST_CONFIGURATION = Regex("^\\s*(?:test|androidTest|testFixtures)[A-Za-z]*\\s*[(\\s\"']")
        private val MAVEN_DEPENDENCY = Regex("<dependency>.*?</dependency>", RegexOption.DOT_MATCHES_ALL)

        /** 빌드 파일은 표지만 찾는다. 읽지 못하면 빈 문자열이라 표지가 없는 것으로 본다(버전·스택 미상으로 남는다). */
        private fun readSmallText(path: Path): String = readText(path).orEmpty()

        /** 1 MiB 이하의 링크 아닌 UTF-8 파일이다. 아니면 null이다. */
        private fun readText(path: Path): String? = try {
            if (Files.isSymbolicLink(path) || Files.size(path) > MAX_TEXT_BYTES) null else Files.readString(path)
        } catch (_: java.io.IOException) {
            null
        }

        /**
         * 모듈 설정 문서를 Boot 우선순위 순서(뒤가 이김)로 읽는다 — classpath 루트, classpath `config/`, 모듈 루트,
         * 모듈 `config/`. 같은 위치에서는 `.properties`가 YAML을 이긴다.
         */
        private fun moduleConfig(root: Path, moduleRoot: String): SpringModuleConfig {
            val base = if (moduleRoot.isEmpty()) root else root.resolve(moduleRoot)
            val locations = listOf("src/main/resources", "src/main/resources/config", ".", "config").map { base.resolve(it).normalize() }
            val parsed = locations.flatMap { location -> configFiles(location) }.map { file ->
                readText(file)?.let { text -> SpringConfigDocuments.read(file.fileName.toString(), text) }
            }
            return SpringModuleConfig.of(moduleRoot, parsed.filterNotNull().flatten(), unreadable = parsed.any { it == null })
        }

        private val CONFIG_NAME = Regex("application(-[A-Za-z0-9_.-]+)?\\.(yml|yaml|properties)")

        private fun configFiles(location: Path): List<Path> {
            if (!Files.isDirectory(location, LinkOption.NOFOLLOW_LINKS)) return emptyList()
            val files: List<Path> = Files.list(location).use { stream -> stream.filter { CONFIG_NAME.matches(it.fileName.toString()) }.toList() }
            return files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sortedWith(compareBy({ it.fileName.toString().contains('-') }, { it.fileName.toString().endsWith(".properties") }, { it.fileName.toString() }))
        }

        private val VERSION_PATTERNS = listOf(
            Regex("org\\.springframework\\.boot['\"]?\\)?\\s+version\\s+['\"](\\d+)\\.(\\d+)"),
            Regex("spring-boot-(?:gradle-plugin|dependencies)[:'\"]+\\s*(?:version\\s*[:=]?\\s*['\"])?(\\d+)\\.(\\d+)"),
            Regex("(?i)spring[-_.]?boot(?:[-_.]?version)?\\s*[=:]\\s*['\"]?(\\d+)\\.(\\d+)"),
            Regex("<artifactId>spring-boot-(?:starter-parent|dependencies)</artifactId>\\s*<version>(\\d+)\\.(\\d+)"),
            Regex("<spring-boot\\.version>(\\d+)\\.(\\d+)"),
        )

        /** Boot 버전 표지를 모두 모은다. 서로 다른 (주, 부)가 섞이면 모른다(null)고 둔다. */
        private fun bootVersion(texts: Map<String, String>): Pair<Int, Int>? {
            val found = texts.values.flatMap { text ->
                VERSION_PATTERNS.flatMap { pattern -> pattern.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList() }
            }.distinct()
            return found.singleOrNull()
        }

        /** 프레임워크 제공 경로의 제공자 이름이다. 합성 decl은 내지 않고 한계 문구에만 쓴다. */
        private fun frameworkRoutes(buildText: String, modules: List<SpringModuleConfig>, boot: Boolean): List<String> = buildList {
            val springBoot = boot || "spring-boot" in buildText
            val servlet = SERVLET_MARKERS.any(buildText::contains)
            if (springBoot && servlet) add("error endpoint (/error)")
            if (springBoot && (servlet || REACTIVE_MARKERS.any(buildText::contains))) add("static resources and webjars (/**)")
            if ("spring-boot-starter-actuator" in buildText || "spring-boot-actuator" in buildText) add("actuator endpoints (/actuator)")
            if ("spring-boot-starter-security" in buildText) add("Spring Security login and logout pages")
            if ("springdoc-openapi" in buildText) add("springdoc OpenAPI and Swagger UI")
            if ("spring-boot-starter-data-rest" in buildText || "spring-data-rest-webmvc" in buildText) add("Spring Data REST repositories")
            if ("spring-boot-starter-graphql" in buildText) add("GraphQL endpoint")
            if (modules.any { module -> listOf("spring.h2.console.enabled", "spring.h2.console.path").any { key ->
                    module.lookup(key).let { it is SpringModuleConfig.Lookup.Value || it == SpringModuleConfig.Lookup.OtherProfilesOnly }
                } }) add("H2 console")
        }

        private const val MAX_BUILD_DEPTH = 8
        private const val MAX_TEXT_BYTES = 1024L * 1024L
    }
}
