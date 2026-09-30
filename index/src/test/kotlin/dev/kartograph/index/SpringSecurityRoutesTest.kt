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
 * Spring Security `framework-provided-routes:` 스코프를 고정한다. 합성 소스만 쓴다. 근거는 [SpringSecurityRoutes]의 공식 소스
 * 설명이고, spring-petclinic-rest를 실제로 띄워 `/logout`만 GET·POST·PUT·DELETE로 응답함을 확인했다(docs/SPRING-ROUTES.md).
 */
class SpringSecurityRoutesTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun build(vararg extra: String, version: String = "3.5.5") = write("build.gradle.kts", """
        plugins { id("org.springframework.boot") version "$version" }
        dependencies {
            implementation("org.springframework.boot:spring-boot-starter-web")
            implementation("org.springframework.boot:spring-boot-starter-security")
        ${extra.joinToString("\n") { "    implementation(\"$it\")" }}
        }
    """)

    private fun app() = write("src/main/java/demo/App.java", """
        package demo;
        import org.springframework.boot.autoconfigure.SpringBootApplication;
        @SpringBootApplication
        public class App {}
    """)

    /** `SecurityFilterChain` bean 하나를 가진 Java 구성 파일이다. [body]는 메서드 몸체, [classAnnotations]는 클래스 어노테이션이다. */
    private fun javaChain(body: String, classAnnotations: String = "", file: String = "src/main/java/demo/Security.java") = write(file, """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import org.springframework.security.config.annotation.web.builders.HttpSecurity;
        import org.springframework.security.web.SecurityFilterChain;
        import static org.springframework.security.config.Customizer.withDefaults;
        $classAnnotations
        @Configuration
        public class Security {
            @Bean
            public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                $body
            }
        }
    """)

    private fun kotlinChain(body: String) = write("src/main/kotlin/demo/Security.kt", """
        package demo
        import org.springframework.context.annotation.Bean
        import org.springframework.context.annotation.Configuration
        import org.springframework.security.config.annotation.web.builders.HttpSecurity
        import org.springframework.security.config.annotation.web.invoke
        import org.springframework.security.web.SecurityFilterChain
        @Configuration
        class Security {
            @Bean
            fun filterChain(http: HttpSecurity): SecurityFilterChain {
                $body
            }
        }
    """)

    private fun scan(sourceRoots: List<Path> = emptyList()): BridgeFactsDocument =
        RouteDeclScanner(project, sourceRoots).scan(generatedAt = "2026-01-01T00:00:00Z")

    private fun BridgeFactsDocument.security(): RouteLimitationScope? {
        val message = limitations.single { it.startsWith("framework-provided-routes: Spring Security") }
        return limitationScopes.singleOrNull { it.limitation == message }
    }

    private fun assertScope(templates: List<String>, methods: List<String>, prefixes: List<String> = emptyList()) {
        val scope = scan().security() ?: error("Spring Security scope is missing")
        assertEquals(templates, scope.templates)
        assertEquals(prefixes, scope.templatePrefixes)
        assertEquals(methods, scope.methods)
        assertNull(RouteLimitationScopes.problem(scope))
    }

    private fun assertUnscoped(reason: String) = assertNull(scan().security(), reason)

    @Test
    fun `the boot default chain serves the generated login and logout pages`() {
        build()
        app()
        assertScope(listOf("/default-ui.css", "/login", "/logout"), listOf("GET", "POST"))
    }

    @Test
    fun `an unconditional lambda chain without form login only serves logout`() {
        build()
        app()
        javaChain("""
            http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests((authz) -> authz.anyRequest().authenticated())
                .httpBasic(withDefaults())
                .headers((headers) -> headers.frameOptions(FrameOptionsConfig::sameOrigin));
            return http.build();
        """)
        // CSRF가 꺼지면 LogoutFilter는 GET·POST·PUT·DELETE를 받는다.
        assertScope(listOf("/logout"), listOf("DELETE", "GET", "POST", "PUT"))
    }

    @Test
    fun `conditional chains keep the boot default chain in the scope`() {
        build()
        app()
        javaChain("http.csrf(c -> c.disable()).httpBasic(withDefaults()); return http.build();",
            classAnnotations = "@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = \"sec\", havingValue = \"on\")")
        val scope = scan().security()!!
        assertEquals(listOf("/default-ui.css", "/login", "/logout"), scope.templates)
        assertEquals(listOf("DELETE", "GET", "POST", "PUT"), scope.methods)
        assertFalse(RouteLimitationScopes.applies(scope, "/api/owners", "POST", "root"))
    }

    @Test
    fun `project annotations on a chain bean may be conditional`() {
        build()
        app()
        // 프로젝트 어노테이션은 @Profile을 메타 어노테이션으로 달 수 있다 — Boot 기본 체인을 더한다.
        javaChain("http.csrf(withDefaults()).httpBasic(withDefaults()); return http.build();", classAnnotations = "@StagingOnly")
        assertScope(listOf("/default-ui.css", "/login", "/logout"), listOf("GET", "POST"))
    }

    @Test
    fun `chained form login with a custom page serves only the processing and logout urls`() {
        build()
        app()
        javaChain("""
            http.csrf().disable()
                .formLogin().loginPage("/signin").loginProcessingUrl("/auth").failureUrl("/signin?error").permitAll()
                .and().logout().logoutUrl("/bye").logoutSuccessUrl("/")
                .and().headers().frameOptions().sameOrigin();
            return http.build();
        """)
        assertScope(listOf("/auth", "/bye"), listOf("DELETE", "GET", "POST", "PUT"))
    }

    @Test
    fun `the kotlin dsl resolves literal login and logout paths under the context path`() {
        build()
        app()
        write("src/main/resources/application.properties", "server.servlet.context-path=/ctx")
        kotlinChain("""
            http {
                authorizeHttpRequests { authorize(anyRequest, authenticated) }
                formLogin {
                    loginPage = "/signin"
                    permitAll = true
                }
                logout { logoutUrl = "/bye" }
                httpBasic { }
            }
            return http.build()
        """)
        // loginProcessingUrl 기본값은 loginPage다. CSRF가 켜져 있어 logout은 POST만 받는다.
        assertScope(listOf("/ctx/bye", "/ctx/signin"), listOf("POST"))
    }

    @Test
    fun `a default login page also serves the configured failure url`() {
        build()
        app()
        kotlinChain("""
            http.formLogin { it.failureUrl("/oops?reason=bad").defaultSuccessUrl("/home", true) }
            return http.build()
        """)
        assertScope(listOf("/default-ui.css", "/login", "/logout", "/oops"), listOf("GET", "POST"))
    }

    @Test
    fun `oauth2 login and resource server add their fixed prefixes for every method`() {
        build("org.springframework.boot:spring-boot-starter-oauth2-client")
        app()
        javaChain("""
            http.oauth2Login(oauth -> oauth.loginPage("/sso").userInfoEndpoint(info -> info.userService(null)))
                .oauth2ResourceServer(rs -> rs.jwt(withDefaults()))
                .logout(LogoutConfigurer::permitAll);
            return http.build();
        """)
        assertScope(listOf("/logout"), emptyList(), listOf("/.well-known/oauth-protected-resource", "/login/oauth2/code", "/oauth2/authorization"))
    }

    @Test
    fun `disabled logout and form login leave no generated pages`() {
        build()
        app()
        kotlinChain("""
            http {
                csrf { disable() }
                logout { disable() }
                formLogin { disable() }
                oauth2ResourceServer { jwt { } }
            }
            return http.build()
        """)
        assertScope(emptyList(), listOf("GET"), listOf("/.well-known/oauth-protected-resource"))
    }

    @Test
    fun `configurations that are not statically resolvable stay document-wide`() {
        val cases = mapOf(
            "custom filter" to "http.addFilterBefore(new TokenFilter(), UsernamePasswordAuthenticationFilter.class); return http.build();",
            "request matcher" to "http.logout(l -> l.logoutRequestMatcher(matcher)); return http.build();",
            "constant path" to "http.formLogin(f -> f.loginPage(Paths.LOGIN)); return http.build();",
            "helper call" to "configure(http); return http.build();",
            "local declaration" to "var chain = http.build(); return chain;",
            "unmodeled configurer" to "http.saml2Login(withDefaults()); return http.build();",
            "pattern path" to "http.logout(l -> l.logoutUrl(\"/logout/**\")); return http.build();",
            "custom dsl" to "http.with(new MyDsl(), withDefaults()); return http.build();",
            "method reference" to "http.formLogin(this::form); return http.build();",
        )
        build()
        app()
        cases.forEach { (reason, body) ->
            javaChain(body)
            assertUnscoped(reason)
        }
    }

    @Test
    fun `customizer beans, web security customizers and unmodeled chain sources stay document-wide`() {
        build()
        app()
        val sources = mapOf(
            "web security customizer" to "@Bean WebSecurityCustomizer ignore() { return web -> web.ignoring(); }",
            "customizer bean" to "@Bean Customizer<HttpSecurity> extra() { return http -> {}; }",
            "helper taking HttpSecurity" to "void shared(HttpSecurity http) {}",
            "chain without HttpSecurity" to "@Bean SecurityFilterChain manual() { return null; }",
        )
        sources.forEach { (reason, member) ->
            write("src/main/java/demo/Extra.java", "package demo;\nclass Extra {\n    $member\n}")
            assertUnscoped(reason)
        }
    }

    @Test
    fun `project environment conditions stay document-wide`() {
        build()
        app()
        write("src/main/resources/META-INF/spring.factories", "org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer=demo.Dsl")
        assertUnscoped("spring.factories configurer")
        project.resolve("src/main/resources/META-INF/spring.factories").toFile().delete()

        // 소스 루트 일부만 스캔하면 보지 못한 구성이 있을 수 있다.
        write("src/main/java/demo/Web.java", "package demo;\nclass Web {}")
        assertNull(scan(listOf(project.resolve("src/main/java/demo"))).security(), "partial source roots")

        // 사용자 체인이 없으면 Boot 기본 체인이 쓰이는데 OAuth2 모듈이 있으면 기본 체인이 oauth2Login·oauth2Client가 된다.
        build("org.springframework.boot:spring-boot-starter-oauth2-client")
        assertUnscoped("oauth2 default chain")

        // Security 5(Boot 2)는 증명하지 않는다.
        build(version = "2.7.18")
        assertUnscoped("boot 2")
    }

    @Test
    fun `an unknown request prefix or a reactive stack drops the scope`() {
        build()
        app()
        write("src/main/resources/application.properties", "spring.mvc.servlet.path=/dispatch")
        assertUnscoped("servlet path prefix")
        write("src/main/resources/application.properties", "spring.main.web-application-type=reactive")
        assertUnscoped("reactive stack")
    }

    @Test
    fun `several applications add the boot default chain`() {
        write("settings.gradle.kts", "include(\"a\", \"b\")")
        listOf("a", "b").forEach { module ->
            write("$module/build.gradle.kts", """
                plugins { id("org.springframework.boot") version "3.5.5" }
                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    implementation("org.springframework.boot:spring-boot-starter-security")
                }
            """)
            write("$module/src/main/java/demo/$module/App.java", """
                package demo.$module;
                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class App {}
            """)
        }
        javaChain("http.csrf(withDefaults()).httpBasic(withDefaults()); return http.build();", file = "a/src/main/java/demo/a/Security.java")
        // 앱 b는 체인 bean이 없어 Boot 기본 체인을 쓴다.
        assertScope(listOf("/default-ui.css", "/login", "/logout"), listOf("GET", "POST"))
    }

    @Test
    fun `a chain in a library module keeps the boot default chain`() {
        write("settings.gradle.kts", "include(\"app\", \"lib\")")
        write("app/build.gradle.kts", """
            plugins { id("org.springframework.boot") version "3.5.5" }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-security")
            }
        """)
        write("lib/build.gradle.kts", "dependencies { implementation(\"org.springframework.boot:spring-boot-starter-security\") }")
        write("app/src/main/java/demo/App.java", "package demo;\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {}")
        javaChain("http.csrf(withDefaults()).httpBasic(withDefaults()); return http.build();", file = "lib/src/main/java/demo/Security.java")
        // 라이브러리 모듈의 bean이 앱 클래스패스에 있는지 모르므로 Boot 기본 체인을 더한다.
        assertScope(listOf("/default-ui.css", "/login", "/logout"), listOf("GET", "POST"))
    }

    @Test
    fun `security without the starter is still reported`() {
        write("build.gradle.kts", """
            plugins { id("org.springframework.boot") version "3.5.5" }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.security:spring-security-web")
                implementation("org.springframework.security:spring-security-config")
            }
        """)
        app()
        assertTrue(scan().limitations.any { it.startsWith("framework-provided-routes: Spring Security") })
    }
}
