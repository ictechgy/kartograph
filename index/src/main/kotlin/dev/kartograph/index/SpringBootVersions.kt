package dev.kartograph.index

/**
 * 빌드 파일 본문에서 Spring Boot 버전 표지를 찾는 공유 검출기다. JPA 명명 전략 판정([JpaNamingDetector])과 Spring 라우트
 * 설정([SpringProjectConfig])이 표지 목록·버전 해석·버전 카탈로그 참조 해석을 이 한 곳에서 쓴다. 빌드 도구를 실행하지 않고
 * 문자열 표지만 읽으며, 어떤 파일을 읽을지와 여러 표지를 합치는 규칙은 호출자가 정한다.
 *
 * **소비자별 형식 집합.** 두 소비자는 합치기 전에 서로 다른 형식을 인정했고, 합치는 목적은 동작을 바꾸지 않는 것이었다. 그래서
 * 형식마다 인정하는 소비자([Consumer])를 적는다. 형식을 합집합으로 넓히면 결과가 바뀐다 — 예를 들어 라우트 설정은 버전이 하나로
 * 정해져야 끝 슬래시를 확정하는데, plugin `3.5.0`과 버전을 고정한 starter 좌표 `3.4.9`가 함께 있으면 JPA 쪽 좌표 형식까지 읽는 순간
 * 버전이 둘이 되어 "모름"으로 바뀐다. 형식 집합을 넓히는 것은 별도 결정으로 남긴다.
 */
internal object SpringBootVersions {
    /**
     * 관측한 버전 하나다.
     *
     * @property major 주 버전
     * @property minor 부 버전이다. 표지에 부 버전이 없으면 null이다
     */
    data class Version(val major: Int, val minor: Int?)

    /** 표지를 인정하는 소비자다. */
    enum class Consumer { ROUTES, JPA_NAMING }

    /**
     * 형식 하나다. 첫 그룹이 버전 문자열이거나, 둘째 그룹이 있으면 (주, 부) 숫자다.
     *
     * @property consumers 이 형식을 인정하는 소비자다
     */
    private class Form(val regex: Regex, vararg consumers: Consumer) {
        val consumers: Set<Consumer> = consumers.toSet()
    }

    private val FORMS = listOf(
        // Gradle plugin DSL(Kotlin·Groovy).
        Form(Regex("org\\.springframework\\.boot['\"]?\\)?\\s+version\\s+['\"](\\d+)\\.(\\d+)"), Consumer.ROUTES),
        Form(Regex("id\\s*\\(?\\s*[\"']org\\.springframework\\.boot[\"']\\s*\\)?\\s*version\\s*[\"']([0-9][^\"']*)[\"']"), Consumer.JPA_NAMING),
        // Boot 좌표에 적힌 버전: 플랫폼 좌표(gradle plugin·BOM)와 모든 Boot 좌표.
        Form(Regex("spring-boot-(?:gradle-plugin|dependencies)[:'\"]+\\s*(?:version\\s*[:=]?\\s*['\"])?(\\d+)\\.(\\d+)"), Consumer.ROUTES),
        Form(Regex("org\\.springframework\\.boot:spring-boot[A-Za-z0-9-]*:([0-9][A-Za-z0-9.+-]*)"), Consumer.JPA_NAMING),
        // 버전 속성(gradle.properties·버전 카탈로그 [versions]·ext 블록).
        Form(Regex("(?i)spring[-_.]?boot(?:[-_.]?version)?\\s*[=:]\\s*['\"]?(\\d+)\\.(\\d+)"), Consumer.ROUTES),
        Form(Regex("(?m)^\\s*(?:springBootVersion|spring-boot\\.version|springboot\\.version)\\s*=\\s*[\"']?([0-9][^\"'\\s]*)"), Consumer.JPA_NAMING),
        // Maven parent·BOM.
        Form(Regex("<artifactId>spring-boot-(?:starter-parent|dependencies)</artifactId>\\s*<version>(\\d+)\\.(\\d+)"), Consumer.ROUTES),
        Form(
            Regex(
                "<groupId>\\s*org\\.springframework\\.boot\\s*</groupId>\\s*<artifactId>\\s*spring-boot-(?:starter-parent|dependencies)\\s*</artifactId>\\s*<version>\\s*([0-9][^<\\s]*)\\s*</version>",
            ),
            Consumer.JPA_NAMING,
        ),
        // Maven 속성.
        Form(Regex("<spring-boot\\.version>(\\d+)\\.(\\d+)"), Consumer.ROUTES),
        Form(Regex("<spring-boot\\.version>\\s*([0-9][^<\\s]*)\\s*</spring-boot\\.version>"), Consumer.JPA_NAMING),
        // 버전 카탈로그의 plugin·library 항목.
        Form(Regex("(?m)^\\s*org\\.springframework\\.boot\\s*=\\s*\\{[^}\\n]*\\bversion\\s*=\\s*[\"']([0-9][^\"']*)[\"']"), Consumer.JPA_NAMING),
        Form(Regex("\\{\\s*id\\s*=\\s*\"org\\.springframework\\.boot\"\\s*,\\s*version\\s*=\\s*\"([0-9][^\"]*)\""), Consumer.JPA_NAMING),
        Form(Regex("module\\s*=\\s*\"org\\.springframework\\.boot:spring-boot[A-Za-z0-9-]*\"\\s*,\\s*version\\s*=\\s*\"([0-9][^\"]*)\""), Consumer.JPA_NAMING),
    )

    /** 버전 카탈로그 `version.ref` 참조를 인정하는 소비자다. */
    private val CATALOG_REFERENCE_CONSUMERS = setOf(Consumer.JPA_NAMING)

    /** [consumer]가 인정하는 형식으로 찾은 본문의 모든 Boot 버전 표지다. 같은 표지를 여러 형식이 잡으면 중복될 수 있다. */
    fun find(text: String, consumer: Consumer): List<Version> {
        val direct = FORMS.filter { consumer in it.consumers }.flatMap { form -> form.regex.findAll(text).mapNotNull(::version).toList() }
        if (consumer !in CATALOG_REFERENCE_CONSUMERS) return direct
        val catalog = CATALOG_VERSION.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        return direct + CATALOG_BOOT_REF.findAll(text).mapNotNull { match -> catalog[match.groupValues[1]]?.let(::parse) }.toList()
    }

    /** 버전 없이 Boot를 언급하는지다(plugin id·starter 좌표). */
    fun mentioned(text: String): Boolean = MENTION.containsMatchIn(text)

    /** 그룹이 둘이면 (주, 부) 숫자, 하나면 버전 문자열이다. */
    private fun version(match: MatchResult): Version? {
        val groups = match.groupValues
        if (groups.size > 2) return groups[1].toIntOrNull()?.let { Version(it, groups[2].toIntOrNull()) }
        return parse(groups[1])
    }

    /** `3.5.5`·`2.7`·`4.0.0-M1` 같은 버전 문자열의 주·부 버전이다. 숫자로 시작하지 않으면 null이다. */
    private fun parse(version: String): Version? {
        val parts = version.trim().split('.')
        val major = parts[0].toIntOrNull() ?: return null
        return Version(major, parts.getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull())
    }

    private val CATALOG_VERSION = Regex("(?m)^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\"([0-9][^\"]*)\"\\s*$")
    private val CATALOG_BOOT_REF = Regex("org\\.springframework\\.boot[^\\n]*?version\\.ref\\s*=\\s*\"([A-Za-z0-9_.-]+)\"")
    private val MENTION = Regex("org\\.springframework\\.boot\\b|spring-boot-starter")
}
