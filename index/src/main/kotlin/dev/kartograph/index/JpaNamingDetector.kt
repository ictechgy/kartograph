package dev.kartograph.index

import java.nio.file.Path

/**
 * 빌드 파일·설정 파일·소스에서 JPA 명명 전략 후보를 고른다.
 *
 * 우선순위: 사용자 정의 전략(소스의 전략 클래스·bean, 알 수 없는 설정 값) > 설정 파일의 알려진 전략 값 >
 * Spring Boot 버전 > Hibernate 버전 > 모름. 버전을 모르면 검증된 조합 전체를 후보로 두어
 * 결과가 갈리는 이름만 dynamic으로 내린다. 저장소 밖(환경 변수, 외부 설정 서버)의 재정의는 관측하지 못한다.
 */
internal class JpaNamingDetector(private val projectRoot: Path) {

    /** 한 저장소에서 관측한 명명 근거다. */
    private data class Evidence(
        val bootMajors: MutableSet<Int> = sortedSetOf(),
        val bootUnversioned: MutableList<String> = mutableListOf(),
        val hibernateMajors: MutableSet<Int> = sortedSetOf(),
        val hibernateMentioned: MutableList<String> = mutableListOf(),
        val physical: MutableSet<String> = sortedSetOf(),
        val implicit: MutableSet<String> = sortedSetOf(),
        val customSources: MutableList<String> = mutableListOf(),
        val globallyQuoted: MutableList<String> = mutableListOf(),
        val files: MutableSet<String> = sortedSetOf(),
    )

    /**
     * 저장소를 읽어 명명 문맥을 만든다. [override]가 있으면 관측 없이 그 조합을 쓴다.
     * [legacyJavax]는 소스가 `javax.persistence`(Hibernate 5 세대)를 쓴다는 뜻이다 — 벡터로 검증하지 않은 세대라
     * 검증된 조합 전체를 후보로 둔다.
     */
    fun detect(override: String? = null, legacyJavax: Boolean = false): JpaNamingContext {
        // 사용자가 준 id를 그대로 근거에 적는다 — hibernate-6·7은 같은 조합이라 id로 되돌리면 표기가 바뀐다.
        override?.let { id -> JpaNamingProfile.fromId(id)?.let { return JpaNamingContext.fixed(it, "$id from --jpa-naming") } }
        val evidence = Evidence()
        ProjectTraversal.walkSources(projectRoot, extensions = CONFIG_EXTENSIONS) { path ->
            val name = path.fileName.toString()
            if (isConfigFile(name)) readConfig(path, name, evidence)
        }
        ProjectTraversal.walkSources(projectRoot) { path -> readSource(path, evidence) }
        val decided = decide(evidence)
        val unverifiedBoot = evidence.bootMajors.any { it !in BOOT_MANAGED_HIBERNATE }
        if ((legacyJavax || unverifiedBoot) && evidence.physical.isEmpty() && evidence.implicit.isEmpty() && decided.candidates.isNotEmpty()) {
            val reason = if (legacyJavax) "javax.persistence sources" else "Spring Boot ${evidence.bootMajors.joinToString("/")}"
            return JpaNamingContext(JpaNamingProfile.VERIFIED, describe(evidence, JpaNamingProfile.VERIFIED) +
                "; $reason (Hibernate 5 generation) are not covered by the naming vectors")
        }
        return decided
    }

    private fun isConfigFile(name: String): Boolean =
        name in BUILD_FILES || name.endsWith(".versions.toml") || name == "persistence.xml" ||
            name == "hibernate.properties" || APPLICATION_CONFIG.matches(name)

    /** 설정 파일 한 개의 근거를 모은다 — 읽을 수 없는 파일은 호출자의 스캔 실패로 드러난다. */
    private fun readConfig(path: Path, name: String, evidence: Evidence) {
        val text = ProjectTraversal.readSourceLines(projectRoot, path).joinToString("\n")
        val relative = projectRoot.toRealPath().relativize(path.toRealPath()).joinToString("/")
        val before = evidence.copyCounts()
        readBootVersions(text, evidence, relative)
        readHibernateVersions(text, evidence, relative)
        readStrategySettings(text, evidence, relative)
        if (evidence.copyCounts() != before) evidence.files += relative
    }

    private fun Evidence.copyCounts(): List<Int> = listOf(
        bootMajors.size, bootUnversioned.size, hibernateMajors.size, hibernateMentioned.size, physical.size, implicit.size,
        globallyQuoted.size,
    )

    /** Spring Boot plugin·BOM·parent·starter 좌표·버전 카탈로그에서 major 버전을 읽는다([SpringBootVersions]의 JPA 형식). */
    private fun readBootVersions(text: String, evidence: Evidence, relative: String) {
        SpringBootVersions.find(text, SpringBootVersions.Consumer.JPA_NAMING).forEach { evidence.bootMajors += it.major }
        if (SpringBootVersions.mentioned(text)) evidence.bootUnversioned += relative
    }

    /** 명시 Hibernate 좌표·버전 속성에서 major 버전을 읽는다. */
    private fun readHibernateVersions(text: String, evidence: Evidence, relative: String) {
        // 설정 파일의 `org.hibernate.dialect…` 값은 의존성 근거가 아니다 — 빌드 파일의 좌표만 센다.
        val buildFile = relative.substringAfterLast('/').let { it in BUILD_FILES || it.endsWith(".versions.toml") }
        if (buildFile && HIBERNATE_MENTION.containsMatchIn(text)) evidence.hibernateMentioned += relative
        HIBERNATE_VERSION_PATTERNS.forEach { pattern ->
            pattern.findAll(text).forEach { match -> majorOf(match.groupValues[1])?.let(evidence.hibernateMajors::add) }
        }
    }

    /** properties·yml·persistence.xml의 명명 전략 설정 값을 모은다. */
    private fun readStrategySettings(text: String, evidence: Evidence, relative: String) {
        PHYSICAL_SETTING.findAll(text).forEach { evidence.physical += settingValue(it.groupValues[1]) }
        IMPLICIT_SETTING.findAll(text).forEach { evidence.implicit += settingValue(it.groupValues[1]) }
        if (GLOBALLY_QUOTED.containsMatchIn(text)) evidence.globallyQuoted += relative
    }

    private fun settingValue(raw: String): String = raw.trim().trim('"', '\'').trim()

    /** 소스에 선언된 사용자 명명 전략 클래스·bean을 찾는다 — Boot는 이런 bean을 자동으로 쓴다. */
    private fun readSource(path: Path, evidence: Evidence) {
        val code = maskStringContents(stripComments(ProjectTraversal.readSourceLines(projectRoot, path).joinToString("\n")))
        if (CUSTOM_STRATEGY_SOURCE.containsMatchIn(code)) {
            evidence.customSources += projectRoot.toRealPath().relativize(path.toRealPath()).joinToString("/")
        }
    }

    /** 근거를 명명 문맥으로 바꾼다. */
    private fun decide(evidence: Evidence): JpaNamingContext {
        if (evidence.customSources.isNotEmpty()) {
            return JpaNamingContext(emptyList(), "custom naming strategy declared in ${evidence.customSources.sorted().joinToString(", ")}")
        }
        if (evidence.globallyQuoted.isNotEmpty()) {
            return JpaNamingContext(emptyList(), "globally quoted identifiers configured in ${evidence.globallyQuoted.sorted().joinToString(", ")}")
        }
        val physical = evidence.physical.map(::physicalKind)
        val implicit = evidence.implicit.map(::implicitKind)
        if (physical.any { it == null } || implicit.any { it == null }) {
            val values = (evidence.physical + evidence.implicit).sorted().joinToString(", ")
            return JpaNamingContext(emptyList(), "unrecognized naming strategy setting ($values)")
        }
        val hibernateMajors = hibernateMajors(evidence)
        val boot = evidence.bootMajors.isNotEmpty() || evidence.bootUnversioned.isNotEmpty()
        val hibernate = evidence.hibernateMajors.isNotEmpty() || evidence.hibernateMentioned.isNotEmpty()
        if (!boot && !hibernate && physical.isEmpty() && implicit.isEmpty()) {
            // Boot인지 순수 Hibernate인지 알 수 없다 — 검증된 조합 전부를 후보로 두어 갈리는 이름만 dynamic으로 내린다.
            return JpaNamingContext(JpaNamingProfile.VERIFIED, describe(evidence, JpaNamingProfile.VERIFIED))
        }
        val physicalKinds = physical.filterNotNull().toSet().ifEmpty { setOf(if (boot) SNAKE else STANDARD) }
        val implicitKinds = implicit.filterNotNull().toSet()
            .ifEmpty { setOf(if (boot) JpaImplicitStrategy.SPRING else JpaImplicitStrategy.JPA_COMPLIANT) }
        val candidates = physicalKinds.flatMap { kind -> hibernateMajors.map { major -> physicalFor(kind, major) } }
            .flatMap { strategy -> implicitKinds.map { JpaNamingProfile(strategy, it) } }
            .distinct()
        return JpaNamingContext(candidates, describe(evidence, candidates))
    }

    /** Hibernate major 후보다 — 명시 좌표가 우선이고, 없으면 Boot major가 관리하는 버전(3→6, 4→7)이다. */
    private fun hibernateMajors(evidence: Evidence): Set<Int> {
        val explicit = evidence.hibernateMajors.filter { it in SUPPORTED_HIBERNATE }.toSet()
        if (explicit.isNotEmpty()) return explicit
        val managed = evidence.bootMajors.mapNotNull { BOOT_MANAGED_HIBERNATE[it] }.toSet()
        // Boot 2(Hibernate 5)처럼 검증하지 않은 조합은 두 후보에 모두 맡겨 갈리는 이름을 dynamic으로 내린다.
        return if (managed.isNotEmpty() && evidence.bootMajors.all { it in BOOT_MANAGED_HIBERNATE }) managed
        else SUPPORTED_HIBERNATE
    }

    private fun physicalFor(kind: String, major: Int): JpaPhysicalStrategy = when {
        kind == STANDARD -> JpaPhysicalStrategy.STANDARD
        major >= 7 -> JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_7
        else -> JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_6
    }

    private fun physicalKind(value: String): String? = when (value.substringAfterLast('.')) {
        "PhysicalNamingStrategyStandardImpl" -> STANDARD
        "CamelCaseToUnderscoresNamingStrategy", "PhysicalNamingStrategySnakeCaseImpl" -> SNAKE
        else -> null
    }

    private fun implicitKind(value: String): JpaImplicitStrategy? = when (value.substringAfterLast('.')) {
        "ImplicitNamingStrategyJpaCompliantImpl", "default", "jpa" -> JpaImplicitStrategy.JPA_COMPLIANT
        "SpringImplicitNamingStrategy" -> JpaImplicitStrategy.SPRING
        else -> null
    }

    private fun describe(evidence: Evidence, candidates: List<JpaNamingProfile>): String {
        val source = when {
            evidence.files.isNotEmpty() -> "from ${evidence.files.joinToString(", ")}"
            else -> "no build evidence"
        }
        val ids = candidates.flatMap { profile -> JpaNamingProfile.NAMED.filterValues { it == profile }.keys }
        return "${ids.joinToString("|")} ($source)"
    }

    private fun majorOf(version: String): Int? = version.trim().substringBefore('.').toIntOrNull()

    private companion object {
        const val STANDARD = "standard"
        const val SNAKE = "snake"
        val SUPPORTED_HIBERNATE = setOf(6, 7)
        val BOOT_MANAGED_HIBERNATE = mapOf(3 to 6, 4 to 7)
        val CONFIG_EXTENSIONS = setOf("kts", "gradle", "toml", "xml", "properties", "yml", "yaml")
        val BUILD_FILES = setOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "pom.xml", "gradle.properties")
        val APPLICATION_CONFIG = Regex("application(?:-[A-Za-z0-9_.-]+)?\\.(?:properties|ya?ml)")

        val HIBERNATE_MENTION = Regex("org\\.hibernate(?:\\.orm)?:hibernate-|<groupId>\\s*org\\.hibernate(?:\\.orm)?\\s*</groupId>")

        val HIBERNATE_VERSION_PATTERNS = listOf(
            Regex("org\\.hibernate(?:\\.orm)?:hibernate-core:([0-9][A-Za-z0-9.+-]*)"),
            Regex("<hibernate\\.version>\\s*([0-9][^<\\s]*)\\s*</hibernate\\.version>"),
            Regex("[\"']hibernate\\.version[\"']\\s*\\]?\\s*=\\s*[\"']([0-9][^\"']*)[\"']"),
            Regex("(?m)^\\s*hibernate(?:[-.]orm|[-.]core)?(?:\\.version)?\\s*=\\s*\"([0-9][^\"]*)\""),
            Regex("module\\s*=\\s*\"org\\.hibernate(?:\\.orm)?:hibernate-core\"\\s*,\\s*version\\s*=\\s*\"([0-9][^\"]*)\""),
        )

        // properties(`a.b=c`), yml(`physical-strategy: c`), persistence.xml(`name="…" value="…"`)의 값을 모두 받는다.
        val PHYSICAL_SETTING = Regex(
            "(?:physical-strategy|physical_naming_strategy|physicalStrategy)[\"']?\\s*(?:[:=]|value\\s*=)\\s*[\"']?([A-Za-z0-9_.$]+)",
        )
        val IMPLICIT_SETTING = Regex(
            "(?:implicit-strategy|implicit_naming_strategy|implicitStrategy)[\"']?\\s*(?:[:=]|value\\s*=)\\s*[\"']?([A-Za-z0-9_.$]+)",
        )
        val GLOBALLY_QUOTED = Regex("globally_quoted_identifiers[\"']?\\s*(?:[:=]|value\\s*=)\\s*[\"']?true")

        // 사용자 전략 클래스(상속·구현)나 전략을 돌려주는 @Bean이 있으면 Boot가 그 전략을 쓴다.
        val CUSTOM_STRATEGY_SOURCE = Regex(
            "(?:class|object)\\s+\\w+[^{]*?(?:[:,]|extends|implements)\\s*(?:[\\w.]+\\.)?" +
                "(?:PhysicalNamingStrategy\\w*|ImplicitNamingStrategy\\w*|CamelCaseToUnderscoresNamingStrategy|SpringImplicitNamingStrategy)\\b" +
                "|@Bean[^{;]*?(?:PhysicalNamingStrategy|ImplicitNamingStrategy|CamelCaseToUnderscoresNamingStrategy)",
        )
    }
}
