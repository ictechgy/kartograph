package dev.kartograph.index

import dev.kartograph.index.SpringBootVersions.Consumer
import dev.kartograph.index.SpringBootVersions.Version
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 공유 Boot 버전 검출기의 형식을 소비자별로 고정한다. 두 소비자가 합치기 전에 인정하던 형식 집합을 그대로 지켜야 동작이 바뀌지
 * 않는다 — 형식마다 (파일, 본문, 라우트 인정, JPA 인정)을 적고 실제 소비자(SpringProjectConfig·JpaNamingDetector)로 확인한다.
 */
class SpringBootVersionsTest {
    @TempDir
    lateinit var project: Path

    /** 형식 → (파일, 본문, 라우트 설정이 읽는가, JPA 명명이 읽는가). 모두 Boot 3.4를 가리킨다. */
    private val forms = mapOf(
        "kotlin plugin" to Form("build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.4.1\" }", routes = true, jpa = true),
        "groovy plugin" to Form("build.gradle", "plugins { id 'org.springframework.boot' version '3.4.1' }", routes = true, jpa = true),
        "starter coordinate" to Form("build.gradle.kts", "dependencies { implementation(\"org.springframework.boot:spring-boot-starter-web:3.4.1\") }", routes = false, jpa = true),
        "gradle plugin classpath" to Form("build.gradle", "buildscript { dependencies { classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.4.1' } }", routes = true, jpa = true),
        "bom" to Form("build.gradle.kts", "dependencies { implementation(platform(\"org.springframework.boot:spring-boot-dependencies:3.4.1\")) }", routes = true, jpa = true),
        "gradle property" to Form("gradle.properties", "springBootVersion=3.4.1", routes = true, jpa = true),
        "catalog version" to Form("gradle/libs.versions.toml", "[versions]\nspring-boot = \"3.4.1\"", routes = true, jpa = false),
        "catalog plugin reference" to Form("gradle/libs.versions.toml", "[versions]\nboot = \"3.4.1\"\n[plugins]\nspring-boot = { id = \"org.springframework.boot\", version.ref = \"boot\" }", routes = false, jpa = true),
        "catalog plugin" to Form("gradle/libs.versions.toml", "[plugins]\nboot = { id = \"org.springframework.boot\", version = \"3.4.1\" }", routes = false, jpa = true),
        "catalog library" to Form("gradle/libs.versions.toml", "[libraries]\nboot = { module = \"org.springframework.boot:spring-boot-starter\", version = \"3.4.1\" }", routes = false, jpa = true),
        "maven parent" to Form("pom.xml", "<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.4.1</version></parent>", routes = true, jpa = true),
        "maven parent without group" to Form("pom.xml", "<parent><artifactId>spring-boot-starter-parent</artifactId><version>3.4.1</version></parent>", routes = true, jpa = false),
        "maven property" to Form("pom.xml", "<properties><spring-boot.version>3.4.1</spring-boot.version></properties>", routes = true, jpa = true),
    )

    private data class Form(val file: String, val text: String, val routes: Boolean, val jpa: Boolean)

    @Test
    fun `each consumer keeps the forms it recognized before the merge`() {
        forms.forEach { (name, form) ->
            assertEquals(form.routes, Version(3, 4) in SpringBootVersions.find(form.text, Consumer.ROUTES), "$name routes")
            assertEquals(form.jpa, SpringBootVersions.find(form.text, Consumer.JPA_NAMING).any { it.major == 3 }, "$name jpa")
        }
        assertEquals(listOf(Version(4, null)), SpringBootVersions.find("springBootVersion=4", Consumer.JPA_NAMING))
        assertTrue(SpringBootVersions.mentioned("implementation(\"org.springframework.boot:spring-boot-starter-web\")"))
        assertFalse(SpringBootVersions.mentioned("implementation(\"io.ktor:ktor-server-core\")"))
    }

    @Test
    fun `the real consumers read each form as recorded`() {
        forms.forEach { (name, form) ->
            project.toFile().listFiles()?.forEach { it.deleteRecursively() }
            val path = project.resolve(form.file)
            path.parent.createDirectories()
            path.writeText(form.text + "\n")
            assertEquals(if (form.routes) 3 to 4 else null to null, SpringProjectConfig.read(project).let { it.bootMajor to it.bootMinor }, name)
            // Boot 3만 보면 후보가 spring-boot-3 하나다. 버전을 못 읽으면 검증된 조합 전체(`spring-boot-3|spring-boot-4|…`)다.
            assertEquals(form.jpa, JpaNamingDetector(project).detect().evidence.substringBefore(" (") == "spring-boot-3", name)
        }
    }

    @Test
    fun `a pinned starter coordinate does not make the route configuration version unknown`() {
        // 라우트 설정은 버전이 하나여야 끝 슬래시를 확정한다. starter 좌표 형식은 JPA 쪽에서만 인정한다.
        project.resolve("build.gradle.kts").writeText("""
            plugins { id("org.springframework.boot") version "3.5.0" }
            dependencies { implementation("org.springframework.boot:spring-boot-starter-web:3.4.9") }
        """.trimIndent())
        assertEquals(3 to 5, SpringProjectConfig.read(project).let { it.bootMajor to it.bootMinor })
    }
}
