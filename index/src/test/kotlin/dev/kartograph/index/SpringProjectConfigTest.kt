package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Boot 설정 문서·프로필·플레이스홀더·빌드 표지 규칙을 고정한다. */
class SpringProjectConfigTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun value(text: String) = SpringModuleConfig.Lookup.Value(text, false)

    @Test
    fun `properties documents split on separators and read profile activation`() {
        val documents = SpringConfigDocuments.read("application.properties", """
            server.servlet.contextPath=/base
            #---
            spring.config.activate.on-profile=prod
            server.servlet.context-path=/prod
        """.trimIndent())!!
        assertEquals(listOf(emptyList(), listOf("prod")), documents.map { it.profiles })
        assertEquals("/base", documents.first().values[SpringConfigDocuments.normalizeKey("server.servlet.context-path")])
        assertEquals(listOf("local"), SpringConfigDocuments.read("application-local.properties", "a=b")!!.single().profiles)
    }

    @Test
    fun `yaml reader keeps mappings and scalars and skips lists and blocks`() {
        val documents = SpringConfigDocuments.read("application.yml", """
            server:
              servlet:
                context-path: "/api/"   # 주석
              port: 8080
            app:
              names:
                - one
                - nested: value
              banner: |
                text: ignored
              flow: {a: b}
              quoted: 'it''s'
              'key.with': x
            plain line without colon
            ---
            spring:
              profiles: dev
            app.items: /dev-items
            ...
        """.trimIndent())!!
        val base = documents.first().values
        assertEquals("/api/", base[SpringConfigDocuments.normalizeKey("server.servlet.context-path")])
        assertEquals("it's", base["app.quoted"])
        assertEquals("x", base["app.key.with"])
        assertNull(base["app.names.nested"])
        assertNull(base["app.banner.text"])
        assertNull(base["app.flow"])
        assertEquals(listOf("dev"), documents[1].profiles)
        assertEquals("/dev-items", documents[1].values["app.items"])
        assertEquals(listOf("prod & cloud"), SpringConfigDocuments.read("application.yml", "spring.profiles: prod & cloud")!!.single().profiles)
    }

    @Test
    fun `module lookup separates default profile values from other profiles`() {
        val module = SpringModuleConfig.of("", listOf(
            SpringConfigDocuments.Document(emptyList(), mapOf("a" to "1", "b" to "2", "spring.profiles.active" to "cloud")),
            SpringConfigDocuments.Document(listOf("cloud"), mapOf("b" to "3")),
            SpringConfigDocuments.Document(listOf("prod"), mapOf("a" to "9", "c" to "4")),
        ))
        assertEquals(SpringModuleConfig.Lookup.Value("1", true), module.lookup("a"))
        assertEquals(value("3"), module.lookup("b"))
        assertEquals(SpringModuleConfig.Lookup.OtherProfilesOnly, module.lookup("c"))
        assertEquals(SpringModuleConfig.Lookup.Absent, module.lookup("d"))
        val defaultProfile = SpringModuleConfig.of("", listOf(SpringConfigDocuments.Document(listOf("default"), mapOf("x" to "d"))))
        assertEquals(value("d"), defaultProfile.lookup("x"))
        val placeholderActive = SpringModuleConfig.of("", listOf(
            SpringConfigDocuments.Document(emptyList(), mapOf("spring.profiles.active" to "\${PROFILE:prod}")),
            SpringConfigDocuments.Document(listOf("prod"), mapOf("x" to "p")),
        ))
        assertEquals(SpringModuleConfig.Lookup.OtherProfilesOnly, placeholderActive.lookup("x"))
    }

    @Test
    fun `placeholders use repo values then defaults and refuse unresolvable ones`() {
        val module = SpringModuleConfig.of("", listOf(
            SpringConfigDocuments.Document(emptyList(), mapOf("app.base" to "/v2", "app.nested" to "\${app.base}/n", "app.key" to "app.base")),
            SpringConfigDocuments.Document(listOf("prod"), mapOf("app.prodonly" to "/p", "app.base" to "/v3")),
        ))
        val placeholders = SpringPlaceholders(listOf(module))
        assertEquals(SpringPlaceholderResult("/x"), placeholders.resolve("/x"))
        assertEquals(SpringPlaceholderResult("/v2/u", profileDependent = true), placeholders.resolve("\${app.base}/u"))
        assertEquals(SpringPlaceholderResult("/v2/n", profileDependent = true), placeholders.resolve("\${app.nested}"))
        assertEquals(SpringPlaceholderResult("/d/u", usedDefault = true), placeholders.resolve("\${missing:/d}/u"))
        assertEquals(SpringPlaceholderResult("/v2", profileDependent = true), placeholders.resolve("\${\${app.key}:/d}"))
        assertNull(placeholders.resolve("\${app.prod-only:/fallback}").text)
        assertNull(placeholders.resolve("\${missing}").text)
        assertNull(placeholders.resolve("#{bean.path}").text)
        assertNull(placeholders.resolve("\${unclosed").text)
        assertEquals(SpringPlaceholderResult("/a:b", usedDefault = true), placeholders.resolve("\${missing:/a:b}"))
        val disagreeing = SpringPlaceholders(listOf(
            SpringModuleConfig.of("a", listOf(SpringConfigDocuments.Document(emptyList(), mapOf("k" to "1")))),
            SpringModuleConfig.of("b", listOf(SpringConfigDocuments.Document(emptyList(), mapOf("k" to "2")))),
        ))
        assertNull(disagreeing.resolve("\${k:0}").text)
        assertEquals(SpringPlaceholderResult("0", usedDefault = true), SpringPlaceholders(emptyList()).resolve("\${k:0}"))
    }

    @Test
    fun `build files give boot version, stack and framework routes`() {
        write("build.gradle.kts", """
            plugins { id("org.springframework.boot") version "3.3.4" }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-actuator")
                testImplementation("org.springframework.boot:spring-boot-starter-webflux")
            }
        """)
        write("src/main/resources/application.properties", "spring.h2.console.enabled=true")
        val config = SpringProjectConfig.read(project)
        assertEquals(3, config.bootMajor)
        assertEquals(3, config.bootMinor)
        assertTrue(config.servlet)
        assertFalse(config.reactive)
        assertEquals(listOf("error endpoint (/error)", "static resources and webjars (/**)", "actuator endpoints (/actuator)", "H2 console"), config.frameworkRoutes)
    }

    @Test
    fun `boot version patterns cover gradle maven catalogs and properties`() {
        mapOf(
            "build.gradle" to "plugins { id 'org.springframework.boot' version '2.7.18' }",
            "pom.xml" to "<parent><artifactId>spring-boot-starter-parent</artifactId>\n<version>2.7.18</version></parent>",
            "gradle/libs.versions.toml" to "[versions]\nspring-boot = \"2.7.18\"",
            "gradle.properties" to "springBootVersion=2.7.18",
        ).forEach { (file, text) ->
            project.toFile().listFiles()?.forEach { it.deleteRecursively() }
            write(file, text)
            assertEquals(2 to 7, SpringProjectConfig.read(project).let { it.bootMajor to it.bootMinor }, file)
        }
        write("build.gradle", "plugins { id 'org.springframework.boot' version '3.1.0' }")
        assertNull(SpringProjectConfig.read(project).bootMajor)
    }

    @Test
    fun `maven test scope and other framework providers`() {
        write("pom.xml", """
            <project>
              <dependencies>
                <dependency><artifactId>spring-boot-starter-webflux</artifactId></dependency>
                <dependency><artifactId>spring-boot-starter-web</artifactId><scope>test</scope></dependency>
                <dependency><artifactId>spring-boot-starter-security</artifactId></dependency>
                <dependency><artifactId>springdoc-openapi-starter-webflux-ui</artifactId></dependency>
                <dependency><artifactId>spring-boot-starter-data-rest</artifactId></dependency>
                <dependency><artifactId>spring-boot-starter-graphql</artifactId></dependency>
              </dependencies>
            </project>
        """)
        val config = SpringProjectConfig.read(project)
        assertFalse(config.servlet)
        assertTrue(config.reactive)
        assertEquals(listOf("static resources and webjars (/**)", "Spring Security login and logout pages", "springdoc OpenAPI and Swagger UI",
            "Spring Data REST repositories", "GraphQL endpoint"), config.frameworkRoutes)
    }

    @Test
    fun `config candidates prefer the own module then every configured module`() {
        write("settings.gradle.kts", "")
        write("app/build.gradle.kts", "")
        write("lib/build.gradle.kts", "")
        write("other/build.gradle.kts", "")
        write("app/src/main/resources/application.yml", "server.servlet.context-path: /app")
        write("other/src/main/resources/application.properties", "server.servlet.context-path=/other")
        write("other/src/main/resources/config/application-dev.properties", "a=b")
        val config = SpringProjectConfig.read(project)
        assertEquals(listOf("app"), config.candidatesFor("app/src/main/java/A.java").map { it.root })
        assertEquals(listOf("other", "app"), config.candidatesFor("lib/src/main/java/L.java").map { it.root })
        assertEquals(listOf("other", "app"), config.candidatesFor(null).map { it.root })
        assertEquals(null, config.moduleMentions("missing", SpringProjectConfig.SERVLET_MARKERS))
        assertEquals(false, config.moduleMentions("app", SpringProjectConfig.SERVLET_MARKERS))
    }

    @Test
    fun `test configurations are removed from gradle dependency text`() {
        val text = SpringProjectConfig.mainDependencyText("implementation(\"a:web\")\ntestImplementation(\"a:flux\")\n  androidTestImplementation 'x'\ntestFixturesApi(\"y\")")
        assertEquals("implementation(\"a:web\")", text)
    }

    @Test
    fun `unreadable configuration never settles a key`() {
        assertNull(SpringConfigDocuments.read("application.properties", "bad=\\uZZZZ"))
        write("src/main/resources/application.properties", "server.servlet.context-path=/ctx\nbad=\\uZZZZ")
        val module = SpringProjectConfig.read(project).candidatesFor("src/main/java/A.java").single()
        assertTrue(module.hasConfig)
        assertEquals(SpringModuleConfig.Lookup.Unknown, module.lookup("server.servlet.context-path"))
        assertNull(SpringPlaceholders(listOf(module)).resolve("\${a:b}").text)
    }

    @Test
    fun `build file comments are removed per file type`() {
        assertEquals("<a/>\n<b/>", SpringProjectConfig.withoutComments("pom.xml", "<a/><!-- <web/> -->\n<b/>"))
        assertEquals("x = 1\n", SpringProjectConfig.withoutComments("libs.versions.toml", "x = 1\n# web = 2"))
        assertEquals("url(\"https://repo\")\nkeep", SpringProjectConfig.withoutComments("build.gradle", "url(\"https://repo\")\nkeep /* web */// web"))
    }
}
