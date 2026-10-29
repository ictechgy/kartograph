package dev.kartograph.index

import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.RouteLimitationScope
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `framework-provided-routes:` 스코프를 제공자마다 고정한다. 합성 빌드 파일·설정만 쓴다. 근거는 [SpringFrameworkRoutes]의 공식
 * 소스 설명과 `SpringRouteCorpusTest`의 실제 mappings 대조다.
 */
class SpringFrameworkRoutesTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun build(vararg starters: String, version: String = "3.5.5") = write("build.gradle.kts", """
        plugins { id("org.springframework.boot") version "$version" }
        dependencies {
        ${starters.joinToString("\n") { "    implementation(\"$it\")" }}
        }
    """)

    private fun scan(): BridgeFactsDocument = RouteDeclScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z")

    /** 라벨 조각으로 한계를 찾아 그 스코프를 준다. 스코프가 없으면 null이다. */
    private fun BridgeFactsDocument.scopeOf(fragment: String): RouteLimitationScope? {
        val message = limitations.single { it.startsWith("framework-provided-routes: ") && fragment in it }
        return limitationScopes.singleOrNull { it.limitation == message }
    }

    @Test
    fun `servlet defaults scope the error controller, welcome page, static resources and actuator`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-actuator")
        val document = scan()
        assertEquals(RouteLimitationScope(document.limitations.single { "error endpoint" in it }, templates = listOf("/error")), document.scopeOf("error endpoint"))
        assertEquals(listOf("/"), document.scopeOf("welcome page")!!.templates)
        assertTrue(document.scopeOf("welcome page")!!.methods.isEmpty())
        assertEquals(listOf("/"), document.scopeOf("static resources")!!.templatePrefixes)
        assertEquals(listOf("GET", "HEAD"), document.scopeOf("static resources")!!.methods)
        assertEquals(listOf("/actuator", "/cloudfoundryapplication"), document.scopeOf("actuator")!!.templatePrefixes)
        document.limitationScopes.forEach { assertNull(RouteLimitationScopes.problem(it), it.toString()) }
    }

    @Test
    fun `configured paths narrow the scopes under the context path`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-actuator", "com.h2database:h2")
        write("src/main/resources/application.properties", """
            server.servlet.context-path=/ctx/
            server.error.path=/oops/*
            spring.mvc.static-path-pattern=/assets/**
            spring.mvc.webjars-path-pattern=/libs/**
            management.endpoints.web.base-path=/manage/
            management.server.base-path=/mgmt
            spring.h2.console.enabled=true
            spring.h2.console.path=/db/
        """)
        val document = scan()
        // 끝 `*` 오류 경로는 빈 값 변형(`/ctx/oops/`)도 받는다.
        assertEquals(listOf("/ctx/oops/", "/ctx/oops/{}"), document.scopeOf("error endpoint")!!.templates)
        assertEquals(listOf("/ctx"), document.scopeOf("welcome page")!!.templates)
        assertEquals(listOf("/ctx/assets", "/ctx/libs", "/ctx/webjars"), document.scopeOf("static resources")!!.templatePrefixes)
        // 별도 관리 포트는 context-path 없이 관리 서버 접두사(Boot 3 키와 없으면 빈 Boot 2 키) 아래에 매핑된다. 포트 설정은 보지 않고 넓게 넣는다.
        assertEquals(listOf("/ctx/cloudfoundryapplication", "/ctx/manage", "/manage", "/mgmt/manage"), document.scopeOf("actuator")!!.templatePrefixes)
        assertEquals(listOf("/ctx/db"), document.scopeOf("H2 console")!!.templatePrefixes)
        val static = document.scopeOf("static resources")!!
        assertFalse(RouteLimitationScopes.applies(static, "/ctx/owners/{}", "GET", "root"))
        assertTrue(RouteLimitationScopes.applies(static, "/ctx/assets/app.css", "HEAD", "root"))
    }

    @Test
    fun `unprovable providers and values leave the limitation document-wide`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-actuator",
            "org.springframework.boot:spring-boot-starter-security", "org.springframework.boot:spring-boot-starter-data-rest",
            "org.springframework.boot:spring-boot-starter-graphql")
        write("src/main/resources/application.yml", """
            server:
              error:
                path: ${'$'}{custom.error}
            management:
              endpoints:
                web:
                  base-path: /
              endpoint:
                health:
                  group:
                    live:
                      additional-path: server:/livez
            spring:
              mvc:
                static-path-pattern: ${'$'}{unknown.pattern}
        """)
        write("src/main/java/demo/Security.java", """
            package demo;
            class Security { @org.springframework.context.annotation.Bean WebSecurityCustomizer ignore() { return null; } }
        """)
        val document = scan()
        // Data REST 기본 base path는 루트라 좁힐 것이 없다. WebSecurityCustomizer는 체인을 바꿀 수 있다.
        listOf("error endpoint", "actuator", "Spring Security", "Spring Data REST").forEach { fragment ->
            assertNull(document.scopeOf(fragment), fragment)
        }
        // 풀지 못한 정적 경로 패턴은 앱 전체로 넓히되 GET·HEAD 스코프는 유지한다(리소스 핸들러는 method로 좁혀진다).
        assertEquals(listOf("/"), document.scopeOf("static resources")!!.templatePrefixes)
    }

    @Test
    fun `health group additional paths and profile overrides drop the actuator and error scopes`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-actuator")
        write("src/main/resources/application.properties", "management.endpoint.health.group.live.additional-path=server:/livez")
        write("src/main/resources/application-prod.properties", "server.error.path=/failure")
        val document = scan()
        assertNull(document.scopeOf("actuator"))
        assertNull(document.scopeOf("error endpoint"))
    }

    @Test
    fun `an unknown web stack does not narrow the static scope`() {
        // 버전 카탈로그는 선언만 모으므로 양쪽 스택 표지가 다 보이면 스택을 모른다 — 어느 static-path-pattern 키가 쓰일지 모른다.
        write("gradle/libs.versions.toml", """
            [versions]
            spring-boot = "3.5.5"
            [libraries]
            web = { module = "org.springframework.boot:spring-boot-starter-web" }
            flux = { module = "org.springframework.boot:spring-boot-starter-webflux" }
        """)
        write("src/main/resources/application.properties", "spring.mvc.static-path-pattern=/assets/**")
        assertEquals(listOf("/"), scan().scopeOf("static resources")!!.templatePrefixes)
    }

    @Test
    fun `boot 2 management context path joins the actuator scope`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-actuator", version = "2.7.18")
        write("src/main/resources/application.properties", "management.server.port=9001\nmanagement.server.servlet.context-path=/ops")
        assertEquals(listOf("/actuator", "/cloudfoundryapplication", "/ops/actuator"), scan().scopeOf("actuator")!!.templatePrefixes)
    }

    @Test
    fun `registered resource handlers keep the static scope at the application root`() {
        build("org.springframework.boot:spring-boot-starter-web")
        write("src/main/resources/application.properties", "spring.mvc.static-path-pattern=/assets/**")
        write("src/main/java/demo/Web.java", """
            package demo;
            class Web { void resources(Object registry) { registry.addResourceHandler("/files/**"); } }
        """)
        assertEquals(listOf("/"), scan().scopeOf("static resources")!!.templatePrefixes)
    }

    @Test
    fun `webflux has no error controller and a GET welcome page under the base path`() {
        build("org.springframework.boot:spring-boot-starter-webflux", "org.springdoc:springdoc-openapi-starter-webflux-ui")
        write("src/main/resources/application.properties", "spring.webflux.base-path=wf/")
        val document = scan()
        assertTrue(document.limitations.none { "error endpoint" in it })
        assertEquals(RouteLimitationScope(document.limitations.single { "welcome page" in it }, templates = listOf("/wf"), methods = listOf("GET", "HEAD")),
            document.scopeOf("welcome page"))
        assertEquals(listOf("/wf"), document.scopeOf("springdoc")!!.templatePrefixes)
        assertEquals(listOf("GET", "HEAD"), document.scopeOf("springdoc")!!.methods)
    }

    @Test
    fun `an unresolved route prefix scopes the error path as a suffix and drops prefix-bound scopes`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-actuator")
        write("src/main/resources/application.properties", "spring.mvc.servlet.path=/dispatch")
        val document = scan()
        assertEquals(listOf("/error"), document.scopeOf("error endpoint")!!.templateSuffixes)
        assertNull(document.scopeOf("welcome page"))
        assertNull(document.scopeOf("actuator"))
        assertEquals(listOf("/"), document.scopeOf("static resources")!!.templatePrefixes)
        assertTrue(RouteLimitationScopes.applies(document.scopeOf("error endpoint")!!, "/dispatch/error", "POST", "root"))
    }

    @Test
    fun `applications without a web server get no scopes`() {
        build("org.springframework.boot:spring-boot-starter-web")
        write("src/main/resources/application.properties", "spring.main.web-application-type=none")
        val document = scan()
        assertTrue(document.limitations.any { it.startsWith("framework-provided-routes: ") })
        assertTrue(document.limitationScopes.isEmpty())
    }

    @Test
    fun `data rest is scoped to its base path under the context path`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-data-rest")
        write("src/main/resources/application.properties", "server.servlet.context-path=/ctx\nspring.data.rest.base-path=api/")
        val scope = scan().scopeOf("Spring Data REST")!!
        assertEquals(listOf("/ctx/api"), scope.templatePrefixes)
        assertTrue(scope.methods.isEmpty())
        assertFalse(RouteLimitationScopes.applies(scope, "/ctx/owners", "DELETE", "root"))
        assertTrue(RouteLimitationScopes.applies(scope, "/ctx/api/owners/{}", "PATCH", "root"))
    }

    @Test
    fun `data rest base paths that code or profiles can change stay document-wide`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-data-rest")
        write("src/main/resources/application.properties", "spring.data.rest.base-path=/api")
        write("src/main/resources/application-prod.properties", "spring.data.rest.base-path=/v2")
        assertNull(scan().scopeOf("Spring Data REST"), "profile override")
        project.resolve("src/main/resources/application-prod.properties").toFile().delete()
        write("src/main/java/demo/Rest.java", """
            package demo;
            class Rest { void configure(Object config) { config.setBasePath("/elsewhere"); } }
        """)
        assertNull(scan().scopeOf("Spring Data REST"), "setBasePath in code")
        project.resolve("src/main/java/demo/Rest.java").toFile().delete()
        // Boot 자동 구성 모듈이 없으면 spring.data.rest.* 속성이 적용된다는 근거가 없다.
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.data:spring-data-rest-webmvc")
        assertNull(scan().scopeOf("Spring Data REST"), "no Boot auto-configuration")
    }

    @Test
    fun `graphql is scoped to its router paths`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-graphql")
        assertEquals(listOf("/graphiql", "/graphql", "/graphql/schema"), scan().scopeOf("GraphQL")!!.templates)
        write("src/main/resources/application.yml", """
            server:
              servlet:
                context-path: /ctx
            spring:
              graphql:
                http:
                  path: /api/gql/
                graphiql:
                  path: /ide
                websocket:
                  path: /subscriptions
        """)
        val scope = scan().scopeOf("GraphQL")!!
        // 옛 키 기본값(/graphql)도 합집합에 남긴다 — 버전마다 읽는 키가 다르다.
        assertEquals(listOf("/ctx/api/gql", "/ctx/api/gql/schema", "/ctx/graphql", "/ctx/graphql/schema", "/ctx/ide", "/ctx/subscriptions"), scope.templates)
        assertEquals(listOf("GET", "HEAD", "POST"), scope.methods)
    }

    @Test
    fun `unresolved graphql paths stay document-wide`() {
        build("org.springframework.boot:spring-boot-starter-web", "org.springframework.boot:spring-boot-starter-graphql")
        write("src/main/resources/application.properties", "spring.graphql.path=${'$'}{gql.path}")
        assertNull(scan().scopeOf("GraphQL"))
        write("src/main/resources/application.properties", "spring.graphql.websocket.path=ws")
        assertNull(scan().scopeOf("GraphQL"))
    }
}
