package dev.kartograph.index

import java.util.Locale

/**
 * JPA 식별자 하나다. [quoted]는 소스가 `"…"`·백틱으로 인용했다는 뜻이다 —
 * Hibernate 6과 7의 snake-case physical 전략이 인용 식별자를 다르게 다루므로 보존한다.
 */
internal data class JpaIdentifier(val text: String, val quoted: Boolean = false) {
    companion object {
        /** 어노테이션 문자열 값을 식별자로 푼다 — 감싼 인용 부호는 벗기고 [quoted]로 기억한다. */
        fun parse(value: String): JpaIdentifier {
            val trimmed = value.trim()
            val quotedForm = trimmed.length >= 2 &&
                ((trimmed.first() == '"' && trimmed.last() == '"') || (trimmed.first() == '`' && trimmed.last() == '`'))
            return if (quotedForm) JpaIdentifier(trimmed.substring(1, trimmed.length - 1), quoted = true)
            else JpaIdentifier(trimmed)
        }
    }
}

/**
 * Hibernate physical 명명 전략이다. 모든 이름(명시 이름 포함)에 마지막으로 적용된다.
 * 구현은 각 버전의 소스를 그대로 옮겼다 — hibernate-core 6.6 `CamelCaseToUnderscoresNamingStrategy`,
 * 7.x `PhysicalNamingStrategySnakeCaseImpl`, `PhysicalNamingStrategyStandardImpl`.
 */
internal enum class JpaPhysicalStrategy {
    /** 이름을 바꾸지 않는다 — Hibernate 기본값이다. */
    STANDARD,

    /** Hibernate 6 snake-case: 소문자-대문자-소문자 경계에 `_`, `.`→`_`, 인용 여부와 무관하게 소문자. */
    SNAKE_CASE_HIBERNATE_6,

    /** Hibernate 7 snake-case: 숫자도 경계로 보고, 인용 식별자는 그대로 둔다. */
    SNAKE_CASE_HIBERNATE_7,
    ;

    /** 논리 이름을 물리 이름으로 바꾼다. */
    fun apply(name: JpaIdentifier): JpaIdentifier = when (this) {
        STANDARD -> name
        SNAKE_CASE_HIBERNATE_6 -> JpaIdentifier(snake(name.text, digitsAreLowercase = false).lowercase(Locale.ROOT), name.quoted)
        SNAKE_CASE_HIBERNATE_7 -> if (name.quoted) name
        else JpaIdentifier(snake(name.text, digitsAreLowercase = true).lowercase(Locale.ROOT))
    }

    /** camelCase 경계에 `_`를 넣는다 — Hibernate 소스와 같은 좌→우 삽입 순서를 지킨다. */
    private fun snake(text: String, digitsAreLowercase: Boolean): String {
        val builder = StringBuilder(text.replace('.', '_'))
        var index = 1
        while (index < builder.length - 1) {
            if (underscoreRequired(builder[index - 1], builder[index], builder[index + 1], digitsAreLowercase)) {
                builder.insert(index, '_')
                index++
            }
            index++
        }
        return builder.toString()
    }

    private fun underscoreRequired(before: Char, current: Char, after: Char, digits: Boolean): Boolean {
        fun lowerLike(c: Char) = Character.isLowerCase(c) || (digits && Character.isDigit(c))
        return lowerLike(before) && Character.isUpperCase(current) && lowerLike(after)
    }
}

/** Hibernate implicit 명명 전략이다 — 두 전략은 join table 이름만 다르다. */
internal enum class JpaImplicitStrategy {
    /** `ImplicitNamingStrategyJpaCompliantImpl` — join table은 `{owner table}_{target table}`. */
    JPA_COMPLIANT,

    /** Spring Boot `SpringImplicitNamingStrategy` — join table은 `{owner table}_{attribute}`. */
    SPRING,
}

/**
 * 명명 전략 조합 하나다. 이름 있는 조합의 id는 CLI `--jpa-naming` 값과 limitation 문구에 쓰인다.
 * 조합과 버전 대응은 Spring Boot 자동 구성 소스(3.5: `CamelCaseToUnderscoresNamingStrategy`,
 * 4.0·4.1: `PhysicalNamingStrategySnakeCaseImpl`, 둘 다 `SpringImplicitNamingStrategy`)와
 * 명명 벡터의 실행 결과로 확인했다. 설정으로 한쪽 전략만 바꾼 조합은 이름 없이 두 전략 이름으로 적는다.
 */
internal data class JpaNamingProfile(val physical: JpaPhysicalStrategy, val implicit: JpaImplicitStrategy) {
    /** 사람이 읽는 조합 이름이다. */
    val id: String
        get() = NAMED.entries.firstOrNull { it.value == this }?.key
            ?: "${physical.name.lowercase().replace('_', '-')}+${implicit.name.lowercase().replace('_', '-')}"

    companion object {
        val SPRING_BOOT_3 = JpaNamingProfile(JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_6, JpaImplicitStrategy.SPRING)
        val SPRING_BOOT_4 = JpaNamingProfile(JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_7, JpaImplicitStrategy.SPRING)
        val HIBERNATE_6 = JpaNamingProfile(JpaPhysicalStrategy.STANDARD, JpaImplicitStrategy.JPA_COMPLIANT)
        val HIBERNATE_7 = HIBERNATE_6

        /** CLI가 받는 이름 있는 조합이다 — hibernate-6·7은 같은 전략 조합이다(벡터로 확인). */
        val NAMED: Map<String, JpaNamingProfile> = linkedMapOf(
            "spring-boot-3" to SPRING_BOOT_3,
            "spring-boot-4" to SPRING_BOOT_4,
            "hibernate-6" to HIBERNATE_6,
            "hibernate-7" to HIBERNATE_7,
        )

        /** 검증된 모든 조합이다 — 버전을 모를 때의 후보 집합이다. */
        val VERIFIED: List<JpaNamingProfile> = NAMED.values.distinct()

        /** CLI 값과 정확히 같은 조합이다. */
        fun fromId(id: String): JpaNamingProfile? = NAMED[id]
    }
}

/**
 * profile마다 논리 이름을 계산하는 규칙이다. 대부분의 이름은 profile과 무관하고,
 * join table처럼 implicit 전략에 따라 달라지는 이름만 profile을 본다.
 */
internal fun interface JpaLogicalName {
    fun logical(profile: JpaNamingProfile): JpaIdentifier?

    companion object {
        /** profile과 무관한 고정 논리 이름이다. */
        fun of(identifier: JpaIdentifier): JpaLogicalName = Fixed(identifier)
    }

    /** 고정 이름은 값으로 비교한다 — 같은 이름을 다른 경로로 만들어도 집합에서 한 번만 센다. */
    data class Fixed(val identifier: JpaIdentifier) : JpaLogicalName {
        override fun logical(profile: JpaNamingProfile): JpaIdentifier = identifier
    }
}

/** 논리 이름을 profile의 physical 전략으로 바꾼 물리 이름 문자열이다. null은 계산할 수 없다는 뜻이다. */
internal fun JpaLogicalName.physical(profile: JpaNamingProfile): String? =
    logical(profile)?.let { profile.physical.apply(it).text }

/** (schema, 이름) 테이블 규칙이다 — schema가 있으면 `schema.name` 한정 채널이 된다. */
internal data class JpaTableName(val name: JpaLogicalName, val schema: JpaLogicalName? = null) {
    /** 한 profile에서의 채널 문자열이다 — 이름 안의 `.`는 교환 계약대로 escape한다. */
    fun channel(profile: JpaNamingProfile): String? {
        val table = name.physical(profile) ?: return null
        val qualifier = schema?.let { it.physical(profile) ?: return null }
        return listOfNotNull(qualifier, table).joinToString(".") { escapeName(it) }
    }
}

/**
 * 스캔에 적용할 명명 후보 집합이다. 이름은 모든 후보 profile에서 같을 때만 확정한다 —
 * 버전을 모르면 결과가 갈리는 이름만 dynamic으로 내리고 나머지는 그대로 싣는다.
 *
 * @property candidates 가능한 profile이다. 비었으면(사용자 정의 전략) 어떤 이름도 확정하지 않는다
 * @property evidence 선택 근거 문구다 — limitation에 실린다
 */
internal data class JpaNamingContext(val candidates: List<JpaNamingProfile>, val evidence: String) {
    /** 모든 후보에서 같은 값일 때만 그 값을 돌려준다. */
    fun <T : Any> agreed(compute: (JpaNamingProfile) -> T?): T? {
        if (candidates.isEmpty()) return null
        val values = candidates.map { compute(it) ?: return null }.distinct()
        return values.singleOrNull()
    }

    fun column(name: JpaLogicalName): String? = agreed { name.physical(it) }?.let(::escapeName)

    fun table(name: JpaTableName): String? = agreed { name.channel(it) }

    companion object {
        /** 한 profile로 고정한 문맥이다. */
        fun fixed(profile: JpaNamingProfile, evidence: String): JpaNamingContext =
            JpaNamingContext(listOf(profile), evidence)
    }
}
