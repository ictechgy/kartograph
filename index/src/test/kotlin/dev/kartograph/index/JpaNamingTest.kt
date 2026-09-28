package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JpaNamingTest {
    @TempDir
    lateinit var project: Path

    private fun physical(strategy: JpaPhysicalStrategy, text: String, quoted: Boolean = false) =
        strategy.apply(JpaIdentifier(text, quoted)).text

    @Test
    fun `hibernate 6 and 7 snake case differ only on digits and quoted names`() {
        val h6 = JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_6
        val h7 = JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_7
        assertEquals("created_at", physical(h6, "createdAt"))
        assertEquals("created_at", physical(h7, "createdAt"))
        assertEquals("urlvalue", physical(h6, "URLValue"))
        assertEquals("address2line", physical(h6, "address2Line"))
        assertEquals("address2_line", physical(h7, "address2Line"))
        assertEquals("sales_data_x", physical(h6, "salesData.x"))
        assertEquals("user_account", physical(h6, "UserAccount", quoted = true))
        assertEquals("UserAccount", physical(h7, "UserAccount", quoted = true))
        assertEquals("fooBar", physical(JpaPhysicalStrategy.STANDARD, "fooBar"))
    }

    @Test
    fun `identifier parsing strips double quotes and backticks`() {
        assertEquals(JpaIdentifier("Order", quoted = true), JpaIdentifier.parse("\"Order\""))
        assertEquals(JpaIdentifier("Order", quoted = true), JpaIdentifier.parse("`Order`"))
        assertEquals(JpaIdentifier("orders"), JpaIdentifier.parse(" orders "))
    }

    @Test
    fun `context confirms a name only when every candidate agrees`() {
        val unknown = JpaNamingContext(JpaNamingProfile.VERIFIED, "test")
        assertEquals("title", unknown.column(JpaLogicalName.of(JpaIdentifier("title"))))
        assertNull(unknown.column(JpaLogicalName.of(JpaIdentifier("createdAt"))))
        assertNull(JpaNamingContext(emptyList(), "custom").column(JpaLogicalName.of(JpaIdentifier("title"))))
        val table = JpaTableName(JpaLogicalName.of(JpaIdentifier("OrderLine")), JpaLogicalName.of(JpaIdentifier("sales")))
        assertEquals("sales.order_line", JpaNamingContext.fixed(JpaNamingProfile.SPRING_BOOT_3, "t").table(table))
        assertEquals("spring-boot-4", JpaNamingProfile.SPRING_BOOT_4.id)
        assertEquals("snake-case-hibernate-7+jpa-compliant",
            JpaNamingProfile(JpaPhysicalStrategy.SNAKE_CASE_HIBERNATE_7, JpaImplicitStrategy.JPA_COMPLIANT).id)
        assertEquals(JpaNamingProfile.HIBERNATE_6, JpaNamingProfile.fromId("hibernate-7"))
    }

    private fun detect(): JpaNamingContext = JpaNamingDetector(project).detect()

    private fun write(relative: String, text: String) {
        val path = project.resolve(relative)
        Files.createDirectories(path.parent)
        path.writeText(text.trimIndent())
    }

    @Test
    fun `spring boot 3 gradle plugin selects hibernate 6 snake case`() {
        write("build.gradle.kts", """plugins { id("org.springframework.boot") version "3.5.6" }""")
        val context = detect()
        assertEquals(listOf(JpaNamingProfile.SPRING_BOOT_3), context.candidates)
        assertTrue("build.gradle.kts" in context.evidence)
    }

    @Test
    fun `spring boot 4 maven parent selects hibernate 7 snake case`() {
        write(
            "pom.xml",
            """
            <project><parent>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-parent</artifactId>
              <version>4.0.2</version>
            </parent></project>
            """,
        )
        assertEquals(listOf(JpaNamingProfile.SPRING_BOOT_4), detect().candidates)
    }

    @Test
    fun `version catalog reference and explicit hibernate override are read`() {
        write("gradle/libs.versions.toml", """
            [versions]
            boot = "3.4.1"
            [plugins]
            spring-boot = { id = "org.springframework.boot", version.ref = "boot" }
            [libraries]
            hibernate = "org.hibernate.orm:hibernate-core:7.1.0.Final"
        """)
        assertEquals(listOf(JpaNamingProfile.SPRING_BOOT_4), detect().candidates)
    }

    @Test
    fun `unversioned boot starter keeps both boot generations as candidates`() {
        write("build.gradle", """dependencies { implementation 'org.springframework.boot:spring-boot-starter-data-jpa' }""")
        assertEquals(setOf(JpaNamingProfile.SPRING_BOOT_3, JpaNamingProfile.SPRING_BOOT_4), detect().candidates.toSet())
    }

    @Test
    fun `plain hibernate without boot uses the standard strategy`() {
        write("build.gradle.kts", """dependencies { implementation("org.hibernate.orm:hibernate-core:6.6.1.Final") }""")
        assertEquals(listOf(JpaNamingProfile.HIBERNATE_6), detect().candidates)
    }

    @Test
    fun `unverified hibernate 5 generations keep every verified profile`() {
        write("build.gradle", """plugins { id 'org.springframework.boot' version '2.7.18' }""")
        assertEquals(JpaNamingProfile.VERIFIED, detect().candidates)
        Files.delete(project.resolve("build.gradle"))
        write("build.gradle.kts", """plugins { id("org.springframework.boot") version "3.5.6" }""")
        val legacy = JpaNamingDetector(project).detect(legacyJavax = true)
        assertEquals(JpaNamingProfile.VERIFIED, legacy.candidates)
        assertTrue("javax.persistence" in legacy.evidence)
    }

    @Test
    fun `no build evidence keeps every verified profile`() {
        assertEquals(JpaNamingProfile.VERIFIED, detect().candidates)
    }

    @Test
    fun `known strategy settings override the boot default`() {
        write("build.gradle.kts", """plugins { id("org.springframework.boot") version "3.5.6" }""")
        write(
            "src/main/resources/application.yml",
            """
            spring:
              jpa:
                hibernate:
                  naming:
                    physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
            """,
        )
        assertEquals(listOf(JpaNamingProfile(JpaPhysicalStrategy.STANDARD, JpaImplicitStrategy.SPRING)), detect().candidates)
    }

    @Test
    fun `custom strategy settings sources and global quoting disable name confirmation`() {
        write("src/main/resources/application.properties", "spring.jpa.hibernate.naming.physical-strategy=com.example.MyStrategy")
        assertTrue(detect().candidates.isEmpty())
        Files.delete(project.resolve("src/main/resources/application.properties"))
        write("src/main/kotlin/Naming.kt", "class Naming : PhysicalNamingStrategyStandardImpl()")
        assertTrue("custom naming strategy" in detect().evidence)
        Files.delete(project.resolve("src/main/kotlin/Naming.kt"))
        write("src/main/resources/application.properties", "spring.jpa.properties.hibernate.globally_quoted_identifiers=true")
        assertTrue(detect().candidates.isEmpty())
    }

    @Test
    fun `explicit override skips detection`() {
        write("src/main/resources/application.properties", "spring.jpa.hibernate.naming.physical-strategy=com.example.MyStrategy")
        val context = JpaNamingDetector(project).detect("spring-boot-4")
        assertEquals(listOf(JpaNamingProfile.SPRING_BOOT_4), context.candidates)
        assertTrue("--jpa-naming" in context.evidence)
        assertEquals("hibernate-7 from --jpa-naming", JpaNamingDetector(project).detect("hibernate-7").evidence)
    }
}
