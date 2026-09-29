package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.RouteLimitationScope
import dev.kartograph.core.RouteParamConstraint
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
 * 합성 Spring 소스로 route-decl 수확 규칙을 하나씩 고정한다(소스 원천). 공개 저장소라 실제 앱 코드는 쓰지 않는다.
 * 기대값의 근거는 Spring Boot 4.1.0 앱을 실제로 띄워 받은 `/actuator/mappings`다(`docs/SPRING-ROUTES.md`의 오라클 절).
 */
class RouteDeclScannerTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun boot(version: String = "4.1.0", starter: String = "spring-boot-starter-webmvc") = write("build.gradle.kts", """
        plugins { id("org.springframework.boot") version "$version" }
        dependencies { implementation("org.springframework.boot:$starter") }
    """)

    private fun scan(includeTests: Boolean = false, service: String? = null): BridgeFactsDocument =
        RouteDeclScanner(project, includeTests = includeTests, service = service).scan(generatedAt = "2026-01-01T00:00:00Z")

    private fun BridgeFactsDocument.keys(): Set<String> = facts.filter { !it.dynamic }.mapTo(sortedSetOf()) { "${it.method} ${it.channel}" }

    private fun BridgeFactsDocument.fact(key: String): BridgeFact = facts.single { !it.dynamic && "${it.method} ${it.channel}" == key && it.routeDecl?.catchAllPrefix != true }

    /** 둘째 줄은 원문 블록과 같은 12칸 들여쓰기다 — 들여쓰기가 모자라면 `trimIndent`가 블록 들여쓰기를 지우지 못한다. */
    private val javaImports = "import org.springframework.web.bind.annotation.*;\n" + " ".repeat(12) +
        "import org.springframework.web.service.annotation.GetExchange;"

    @Test
    fun `java controller combines class and method mappings with context path`() {
        boot()
        write("src/main/resources/application.yml", "server:\n  servlet:\n    context-path: /api/")
        write("src/main/java/demo/Paths.java", """
            package demo;
            public interface Paths {
                String CONST = "/constant";
            }
        """)
        write("src/main/java/demo/DemoController.java", """
            package demo;

            import static demo.Paths.CONST;
            $javaImports

            @RestController
            @RequestMapping({"/j", "java"})
            public class DemoController {
                private static final String LOCAL = "/local";

                @GetMapping({"/a", "b"})
                public String cartesian() { return ""; }

                @RequestMapping(path = "/any")
                public String any() { return ""; }

                @RequestMapping(value = "/multi", method = {RequestMethod.GET, RequestMethod.POST})
                public String multi() { return ""; }

                @GetMapping(CONST + LOCAL)
                public String constant() { return ""; }

                @GetMapping(value = "/narrow", params = "x=1")
                public String narrow() { return ""; }

                @GetExchange("/exchange")
                public String exchange() { return ""; }

                @GetMapping("/over")
                public String over(String a) { return ""; }

                @PostMapping("/over")
                public String over(Integer b) { return ""; }

                public String helper() { return ""; }
            }
        """)
        val document = scan(service = "demo")
        assertEquals(
            setOf("GET /api/j/a", "GET /api/j/b", "GET /api/java/a", "GET /api/java/b", "ANY /api/j/any", "ANY /api/java/any",
                "GET /api/j/multi", "POST /api/j/multi", "GET /api/java/multi", "POST /api/java/multi",
                "GET /api/j/constant/local", "GET /api/java/constant/local", "GET /api/j/narrow", "GET /api/java/narrow",
                "GET /api/j/exchange", "GET /api/java/exchange", "GET /api/j/over", "POST /api/j/over", "GET /api/java/over", "POST /api/java/over"),
            document.keys(),
        )
        val narrow = document.fact("GET /api/j/narrow")
        assertTrue(narrow.routeDecl!!.narrowed)
        assertEquals("strict", narrow.routeDecl!!.trailingSlash)
        assertEquals("root", narrow.routeDecl!!.pathAnchor)
        assertEquals("demo.DemoController.narrow", narrow.symbol!!.qualifiedName)
        assertEquals("src/main/java/demo/DemoController.java", narrow.location.path)
        assertEquals(24, narrow.location.line)
        assertEquals(5, narrow.location.column)
        assertEquals(listOf("server"), document.roles)
        assertEquals("specificity", document.dispatch)
        assertEquals("excluded", document.testSources)
        assertEquals("demo", document.service)
        assertEquals("http", document.target)
        assertEquals(listOf(
            "framework-provided-routes: error endpoint (/error) accepts every method; no project declaration and no synthetic route-decl facts are emitted",
            "framework-provided-routes: static resources and webjars (/**) may be served for GET and HEAD; no project declaration and no synthetic route-decl facts are emitted",
            "framework-provided-routes: welcome page handlers may answer the root path (/); no project declaration and no synthetic route-decl facts are emitted",
        ), document.limitations)
        // 스코프는 context-path를 붙인 요청 경로다. 오류 컨트롤러는 모든 method, 정적 리소스는 GET·HEAD다.
        assertEquals(listOf(
            RouteLimitationScope(document.limitations[0], templates = listOf("/api/error")),
            RouteLimitationScope(document.limitations[1], templatePrefixes = listOf("/api"), methods = listOf("GET", "HEAD")),
            RouteLimitationScope(document.limitations[2], templates = listOf("/api")),
        ), document.limitationScopes.sortedBy { document.limitations.indexOf(it.limitation) })
    }

    @Test
    fun `kotlin constants composed annotations inheritance and empty mappings`() {
        boot()
        write("src/main/kotlin/demo/Paths.kt", """
            package demo

            const val K_BASE = "/k"

            object Routes {
                const val DEEP = "${'$'}K_BASE/deep"
            }
        """)
        write("src/main/kotlin/demo/KGet.kt", """
            package demo

            import org.springframework.core.annotation.AliasFor
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.RequestMethod

            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.RUNTIME)
            @RequestMapping(method = [RequestMethod.GET], headers = ["X-Edge=1"])
            annotation class KGet(
                @get:AliasFor(annotation = RequestMapping::class, attribute = "path")
                val path: Array<String> = [],
            )
        """)
        write("src/main/java/demo/JsonGet.java", """
            package demo;

            import org.springframework.core.annotation.AliasFor;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestMethod;

            @RequestMapping(method = RequestMethod.GET, produces = "application/json")
            public @interface JsonGet {
                @AliasFor(annotation = RequestMapping.class, attribute = "path")
                String[] value() default {};

                @AliasFor("value")
                String[] path() default {};
            }
        """)
        write("src/main/kotlin/demo/Controllers.kt", """
            package demo

            import org.springframework.web.bind.annotation.GetMapping
            import org.springframework.web.bind.annotation.PutMapping
            import org.springframework.web.bind.annotation.RequestMapping
            import org.springframework.web.bind.annotation.RestController

            @RestController
            @RequestMapping(K_BASE)
            class KotlinController {
                @Value("ignored") val field = 1

                @GetMapping("${'$'}ITEMS/{id}")
                fun item(): String = ""

                @GetMapping(Routes.DEEP, "/two")
                fun deep(): String = ""

                @KGet(["/kget"])
                fun kget(): String = ""

                @JsonGet(path = ["/json"])
                fun json(): String = ""

                @PutMapping
                suspend fun put(): String = ""

                companion object {
                    const val ITEMS = "/items"
                }
            }

            interface KotlinApi {
                @GetMapping("/iface/{id}")
                fun iface(id: String): String
            }

            @RestController
            class KotlinApiController : KotlinApi {
                override fun iface(id: String): String = id

                @GetMapping
                fun root(): String = ""
            }

            abstract class BaseController {
                @GetMapping("/base")
                fun base(): String = ""
            }

            @RestController
            @RequestMapping("/child")
            class ChildController : BaseController() {
                @RequestMapping
                fun all(): String = ""
            }

            @RequestMapping("/not-a-bean")
            class PlainMapped {
                @GetMapping("/x")
                fun x(): String = ""
            }
        """)
        val document = scan()
        assertEquals(
            setOf("GET /k/items/{}", "GET /k/k/deep", "GET /k/two", "GET /k/kget", "GET /k/json", "PUT /k", "GET /iface/{}", "GET /",
                "GET /child/base", "ANY /child"),
            document.keys(),
        )
        assertTrue(document.fact("GET /k/kget").routeDecl!!.narrowed)
        assertTrue(document.fact("GET /k/json").routeDecl!!.narrowed)
        assertEquals("src/main/kotlin/demo/Controllers.kt", document.fact("GET /iface/{}").location.path)
        assertEquals("demo.KotlinApiController.iface", document.fact("GET /iface/{}").symbol!!.qualifiedName)
        assertEquals("demo.BaseController.base", document.fact("GET /child/base").symbol!!.qualifiedName)
        assertEquals(1, document.facts.count { it.channel == "/" })
    }

    @Test
    fun `placeholders profiles and dynamic templates are reported as server limitations`() {
        boot()
        write("src/main/resources/application.yml", """
            app:
              items: /goods
              shared: /base
            ---
            spring:
              config:
                activate:
                  on-profile: prod
            app:
              other: /other
              shared: /prod
        """)
        write("src/main/java/demo/PlaceholderController.java", """
            package demo;
            $javaImports

            @RestController
            public class PlaceholderController {
                @GetMapping("${'$'}{app.version:/v1}/ver")
                public String defaulted() { return ""; }

                @GetMapping("${'$'}{app.items:/items}")
                public String valued() { return ""; }

                @GetMapping("${'$'}{app.other:/fallback}")
                public String otherProfile() { return ""; }

                @GetMapping("${'$'}{app.shared}")
                public String shared() { return ""; }

                @GetMapping("/v{major}.{minor}")
                public String twoVariables() { return ""; }

                @GetMapping(Unknown.PATH)
                public String unresolvedConstant() { return ""; }

                @RequestMapping(value = "/method", method = UNKNOWN_METHOD)
                public String unresolvedMethod() { return ""; }
            }
        """)
        val document = scan()
        assertEquals(setOf("GET /v1/ver", "GET /goods", "GET /base"), document.keys())
        assertTrue(document.fact("GET /v1/ver").routeDecl!!.configDefault)
        assertFalse(document.fact("GET /goods").routeDecl!!.configDefault)
        val dynamic = document.facts.filter { it.dynamic }
        assertEquals(listOf(null, "\${app.other:/fallback}", "/v{major}.{minor}"), dynamic.map { it.channel }.sortedBy { it.orEmpty() })
        assertTrue(dynamic.all { it.routeDecl!!.trailingSlash == null && it.method == "GET" })
        val limitations = document.limitations.joinToString("\n")
        assertTrue("route-coverage: 2 mapping path(s) could not be converted" in limitations, limitations)
        assertTrue("route-coverage: 1 mapping path(s) use placeholders without a default-profile value" in limitations, limitations)
        assertTrue("route-coverage: 1 mapping path(s) use placeholder values that other profiles override" in limitations, limitations)
        assertTrue("route-coverage: 1 handler method(s) have request methods that could not be resolved" in limitations, limitations)
    }

    @Test
    fun `catch-all declarations expand prefixes without identities`() {
        boot()
        write("src/main/java/demo/FilesController.java", """
            package demo;
            $javaImports

            @RestController
            public class FilesController {
                @GetMapping("/files/{*path}")
                public String files() { return ""; }

                @GetMapping("/static/**")
                public String statics() { return ""; }

                @GetMapping("/static")
                public String staticRoot() { return ""; }

                @GetMapping("/u/{id:\\d+}/{*rest}")
                public String constrained() { return ""; }
            }
        """)
        val document = scan()
        assertEquals(setOf("GET /files/{**}", "GET /files", "GET /static/{**}", "GET /static", "GET /u/{}/{**}", "GET /u/{}"), document.keys())
        assertTrue(document.facts.none { it.routeDecl!!.catchAllPrefix })
        assertEquals(1, document.facts.count { it.channel == "/static" })
        assertEquals("demo.FilesController.files", document.facts.single { it.channel == "/files" }.symbol!!.qualifiedName)
        assertEquals(listOf(RouteParamConstraint(1, "int")), document.facts.single { it.channel == "/u/{}" }.routeDecl!!.paramConstraints)
    }

    @Test
    fun `webflux base path and exchange interfaces`() {
        boot(starter = "spring-boot-starter-webflux")
        write("src/main/resources/application.properties", "spring.webflux.base-path=wf/\nserver.servlet.context-path=/ignored")
        write("src/main/kotlin/demo/Flux.kt", """
            package demo

            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.service.annotation.GetExchange
            import org.springframework.web.service.annotation.HttpExchange
            import org.springframework.web.service.annotation.PostExchange

            @HttpExchange("/ex")
            interface ExchangeApi {
                @GetExchange("/one")
                fun one(): String

                @PostExchange
                fun create(): String

                @HttpExchange(url = "/any")
                fun any(): String

                @HttpExchange(url = "/bad", method = "FETCH")
                fun bad(): String
            }

            @RestController
            class ExchangeController : ExchangeApi {
                override fun one(): String = ""
                override fun create(): String = ""
                override fun any(): String = ""
                override fun bad(): String = ""
            }
        """)
        val document = scan()
        assertEquals(setOf("GET /wf/ex/one", "POST /wf/ex", "ANY /wf/ex/any"), document.keys())
        assertTrue(document.limitations.any { it.startsWith("route-coverage: 1 handler method(s) have request methods") })
    }

    @Test
    fun `unresolved prefixes fall back to base anchors`() {
        boot()
        write("src/main/resources/application.properties", "spring.mvc.servlet.path=/dispatch")
        write("src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        val servletPath = scan()
        assertEquals("base", servletPath.fact("GET /a").routeDecl!!.pathAnchor)
        assertTrue(servletPath.limitations.any { it.startsWith("unresolved-route-prefix: spring.mvc.servlet.path") })

        write("src/main/resources/application.properties", "server.servlet.context-path=relative")
        assertEquals("base", scan().fact("GET /a").routeDecl!!.pathAnchor)

        write("src/main/resources/application.properties", "server.servlet.context-path=\${CTX:/ctx}")
        val defaulted = scan().fact("GET /ctx/a").routeDecl!!
        assertTrue(defaulted.configDefault)
        assertEquals("root", defaulted.pathAnchor)

        write("src/main/resources/application.properties", "a=b\n#---\nspring.config.activate.on-profile=prod\nserver.servlet.context-path=/prod")
        val profiled = scan()
        assertEquals("root", profiled.fact("GET /a").routeDecl!!.pathAnchor)
        assertTrue(profiled.limitations.any { it.startsWith("unresolved-route-prefix: server.servlet.context-path differs in other profiles") })

        write("src/main/resources/application.properties", "server.context-path=/legacy")
        assertEquals("base", scan().fact("GET /a").routeDecl!!.pathAnchor)

        write("src/main/resources/application.properties", "server.servlet.context-path=/ctx\nbroken=\\uZZZZ")
        val unreadable = scan()
        assertEquals("base", unreadable.fact("GET /a").routeDecl!!.pathAnchor)
        assertTrue(unreadable.limitations.any { it.startsWith("unresolved-route-prefix: an unreadable application configuration file") },
            unreadable.limitations.toString())

        write("src/main/java/demo/Config.java", "package demo;\nclass Config { void configure(Object c) { c.addPathPrefix(\"/v1\", null); } }")
        assertEquals("base", scan().fact("GET /a").routeDecl!!.pathAnchor)
    }

    @Test
    fun `unknown web stack with a configured prefix is unresolved`() {
        write("src/main/resources/application.properties", "spring.webflux.base-path=/wf")
        write("src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        val document = scan()
        val evidence = document.fact("GET /a").routeDecl!!
        assertEquals("base", evidence.pathAnchor)
        assertNull(evidence.trailingSlash)
        assertTrue(document.limitations.any { it.startsWith("route-framework-version-unknown:") })
        assertTrue(document.limitations.any { it.contains("(web stack unknown)") })
    }

    @Test
    fun `boot 2 trailing slash is optional and type-level mappings are handlers`() {
        boot(version = "2.7.18", starter = "spring-boot-starter-web")
        write("src/main/java/demo/Legacy.java", """
            package demo;
            $javaImports

            @RequestMapping("/legacy")
            public class Legacy {
                @GetMapping("/x")
                public String x() { return ""; }
            }
        """)
        val document = scan()
        assertEquals("optional", document.fact("GET /legacy/x").routeDecl!!.trailingSlash)
        boot(version = "2.5.0", starter = "spring-boot-starter-web")
        assertTrue(scan().limitations.any { it.contains("AntPathMatcher") })
    }

    @Test
    fun `trailing slash configuration and project signals`() {
        boot()
        write("src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        write("src/main/java/demo/Web.java", """
            package demo;
            import org.springframework.web.servlet.function.RouterFunctions;
            import jakarta.ws.rs.Path;
            class Web {
                void configure(Object configurer, Object registry) {
                    configurer.setUseTrailingSlashMatch(true);
                    registry.addViewController("/login");
                    configurer.setPathMatcher(null);
                    Object r = RouterFunctions.route();
                }
                Object servlet = new ServletRegistrationBean();
            }
        """)
        write("src/main/resources/application.properties", "spring.mvc.pathmatch.matching-strategy=ant-path-matcher")
        val document = scan()
        assertNull(document.fact("GET /a").routeDecl!!.trailingSlash)
        val limitations = document.limitations.joinToString("\n")
        listOf("functional routes", "view, redirect or status controllers", "register servlets", "JAX-RS", "AntPathMatcher").forEach {
            assertTrue(it in limitations, "$it in $limitations")
        }
    }

    @Test
    fun `stereotypes multiple mappings and invisible supertypes`() {
        boot()
        write("src/main/java/demo/ApiController.java", """
            package demo;
            import org.springframework.stereotype.Controller;
            @Controller
            public @interface ApiController {}
        """)
        write("src/main/java/demo/Custom.java", """
            package demo;
            $javaImports
            import com.vendor.BaseApi;

            @ApiController
            public class Custom extends BaseApi {
                @GetMapping("/one")
                @PostMapping("/two")
                public String both() { return ""; }
            }
        """)
        val document = scan()
        assertEquals(setOf("GET /one"), document.keys())
        val limitations = document.limitations.joinToString("\n")
        assertTrue("1 declaration(s) carry more than one mapping annotation" in limitations, limitations)
        assertTrue("1 controller(s) inherit from types outside the scanned classes" in limitations, limitations)
    }

    @Test
    fun `test sources are excluded unless included and then marked`() {
        boot()
        write("src/test/java/demo/TestController.java", "package demo;\n$javaImports\n@RestController public class TestController { @GetMapping(\"/t\") public String t() { return \"\"; } }")
        assertTrue(scan().facts.isEmpty())
        val included = scan(includeTests = true)
        assertTrue(included.fact("GET /t").routeDecl!!.testSource)
        assertEquals("included", included.testSources)
    }

    @Test
    fun `project without controllers still declares a server document`() {
        val document = scan()
        assertTrue(document.facts.isEmpty())
        assertEquals("http", document.target)
        assertEquals(emptyList(), document.limitations)
    }

    @Test
    fun `java annotation arguments may start on the next line`() {
        boot()
        write("src/main/java/demo/Split.java", """
            package demo;
            $javaImports

            @RestController
            public class Split {
                @GetMapping
                ("/users/{id}")
                public String user() { return ""; }
            }
        """)
        assertEquals(setOf("GET /users/{}"), scan().keys())
    }

    @Test
    fun `commented and catalog-only starters do not decide the web stack`() {
        write("build.gradle.kts", """
            plugins { id("org.springframework.boot") version "3.3.0" }
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-webflux")
                // implementation("org.springframework.boot:spring-boot-starter-web")
                /* implementation("org.springframework.boot:spring-boot-starter-web") */
            }
        """)
        write("src/main/resources/application.properties", "spring.webflux.base-path=/api")
        write("src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        assertEquals(setOf("GET /api/a"), scan().keys())

        write("build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(libs.web) }")
        write("gradle/libs.versions.toml", "[libraries]\nweb = \"org.springframework.boot:spring-boot-starter-web\"\nflux = \"org.springframework.boot:spring-boot-starter-webflux\"")
        val unknown = scan()
        assertEquals("base", unknown.fact("GET /a").routeDecl!!.pathAnchor)
        assertTrue(unknown.limitations.any { it.contains("(web stack unknown)") }, unknown.limitations.toString())

        write("src/main/resources/application.properties", "spring.webflux.base-path=/api\nspring.main.web-application-type=reactive")
        assertEquals(setOf("GET /api/a"), scan().keys())
    }

    @Test
    fun `application modules keep their own configuration and libraries need agreement`() {
        write("app-servlet/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(\"org.springframework.boot:spring-boot-starter-web\") }")
        write("app-reactive/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(\"org.springframework.boot:spring-boot-starter-webflux\") }")
        write("lib/build.gradle.kts", "")
        write("app-reactive/src/main/resources/application.yml", "spring.webflux.base-path: /api")
        write("app-servlet/src/main/java/demo/ServletApp.java", "package demo;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@SpringBootApplication public class ServletApp {}")
        write("app-reactive/src/main/java/demo/ReactiveApp.java", "package demo;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@SpringBootApplication public class ReactiveApp {}")
        write("app-servlet/src/main/java/demo/Users.java", "package demo;\n$javaImports\n@RestController public class Users { @GetMapping(\"/users\") public String a() { return \"\"; } }")
        write("app-reactive/src/main/java/demo/Items.java", "package demo;\n$javaImports\n@RestController public class Items { @GetMapping(\"/items\") public String a() { return \"\"; } }")
        write("lib/src/main/java/demo/Shared.java", "package demo;\n$javaImports\n@RestController public class Shared { @GetMapping(\"/shared\") public String a() { return \"\"; } }")
        val document = scan()
        assertEquals(setOf("GET /users", "GET /api/items", "GET /shared"), document.keys())
        assertEquals("root", document.fact("GET /users").routeDecl!!.pathAnchor)
        assertEquals("base", document.fact("GET /shared").routeDecl!!.pathAnchor)
        assertTrue(document.limitations.any { it.contains("differs between application modules") }, document.limitations.toString())
    }

    @Test
    fun `mapped classes whose controller status depends on invisible supertypes are counted`() {
        boot()
        write("src/main/java/demo/Maybe.java", """
            package demo;
            $javaImports
            import com.vendor.VendorController;

            public class Maybe extends VendorController {
                @GetMapping("/maybe")
                public String maybe() { return ""; }
            }
        """)
        val document = scan()
        assertTrue(document.facts.isEmpty())
        assertTrue(document.limitations.any { it.startsWith("route-coverage: 1 class(es) declare mappings without a visible @Controller") }, document.limitations.toString())

        write("src/main/java/demo/Maybe.java", "package demo;\n$javaImports\n@com.lib.ApiController public class Maybe { @GetMapping(\"/maybe\") public String m() { return \"\"; } }")
        assertTrue(scan().limitations.any { it.startsWith("route-coverage: 1 class(es) declare mappings without a visible @Controller") })
    }

    @Test
    fun `web application type none serves no routes`() {
        boot()
        write("src/main/resources/application.properties", "spring.main.web-application-type=none")
        write("src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        val document = scan()
        assertTrue(document.facts.isEmpty())
        assertTrue(document.limitations.any { it.startsWith("route-coverage: 1 handler method(s) belong to application modules with spring.main.web-application-type=none") },
            document.limitations.toString())
    }

    @Test
    fun `library configuration never replaces the application prefix`() {
        write("app/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(\"org.springframework.boot:spring-boot-starter-webflux\") }")
        write("common/build.gradle.kts", "")
        write("app/src/main/resources/application.yml", "spring.webflux.base-path: /api")
        write("common/src/main/resources/application.yml", "logging.level.root: info")
        write("app/src/main/kotlin/demo/App.kt", "package demo\nimport org.springframework.boot.runApplication\nclass App\nfun main() { runApplication<App>() }")
        write("common/src/main/java/demo/Shared.java", "package demo;\n$javaImports\n@RestController public class Shared { @GetMapping(\"/shared\") public String a() { return \"\"; } }")
        assertEquals(setOf("GET /api/shared"), scan().keys())
    }

    @Test
    fun `wildcard imported boot application marks the module`() {
        write("app/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(\"org.springframework.boot:spring-boot-starter-web\") }")
        write("other/build.gradle.kts", "")
        write("other/src/main/resources/application.properties", "server.servlet.context-path=/other")
        write("app/src/main/java/demo/App.java", "package demo;\nimport org.springframework.boot.autoconfigure.*;\n@SpringBootApplication public class App {}")
        write("app/src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        assertEquals(setOf("GET /a"), scan().keys())
    }

    @Test
    fun `project helpers named runApplication and config imports do not settle prefixes`() {
        write("app/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(\"org.springframework.boot:spring-boot-starter-web\") }")
        write("lib/build.gradle.kts", "")
        write("app/src/main/resources/application.properties", "server.servlet.context-path=/app")
        write("app/src/main/java/demo/App.java", "package demo;\nimport org.springframework.boot.SpringApplication;\npublic class App { public static void main(String[] a) { SpringApplication.run(App.class, a); } }")
        write("lib/src/main/kotlin/demo/Jobs.kt", "package demo\nfun <T> runApplication(): Unit = Unit\nfun start() { runApplication<String>() }")
        write("lib/src/main/resources/application.properties", "server.servlet.context-path=/lib")
        write("lib/src/main/java/demo/Shared.java", "package demo;\n$javaImports\n@RestController public class Shared { @GetMapping(\"/shared\") public String a() { return \"\"; } }")
        assertEquals(setOf("GET /app/shared"), scan().keys())

        write("app/src/main/resources/application.properties", "spring.config.import=classpath:lib-defaults.properties")
        val imported = scan()
        assertEquals("base", imported.fact("GET /shared").routeDecl!!.pathAnchor)
    }

    @Test
    fun `imports make every local value provisional and boot names must be the launcher itself`() {
        boot()
        write("src/main/resources/application.properties", "spring.config.import=optional:configserver:\nserver.servlet.context-path=/api\nspring.main.web-application-type=none")
        write("src/main/java/demo/A.java", "package demo;\n$javaImports\n@RestController public class A { @GetMapping(\"/a\") public String a() { return \"\"; } }")
        val imported = scan()
        assertEquals("base", imported.fact("GET /a").routeDecl!!.pathAnchor)
        assertTrue(imported.limitations.any { it.startsWith("unresolved-route-prefix: spring.config.import") }, imported.limitations.toString())
    }

    @Test
    fun `unrelated boot imports do not make a launcher and auto-configured configurations are applications`() {
        write("app/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.3.0\" }\ndependencies { implementation(\"org.springframework.boot:spring-boot-starter-web\") }")
        write("lib/build.gradle.kts", "")
        write("app/src/main/resources/application.properties", "server.servlet.context-path=/app")
        write("app/src/main/java/demo/App.java", "package demo;\nimport org.springframework.boot.SpringBootConfiguration;\n" +
            "import org.springframework.boot.autoconfigure.EnableAutoConfiguration;\n@SpringBootConfiguration @EnableAutoConfiguration public class App {}")
        write("lib/src/main/kotlin/demo/Jobs.kt", "package demo\nimport org.springframework.boot.web.client.RestTemplateBuilder\nfun runApplication(args: Array<String>) = Unit\nfun start() { runApplication(emptyArray()) }")
        write("lib/src/main/resources/application.properties", "server.servlet.context-path=/lib")
        write("lib/src/main/java/demo/Shared.java", "package demo;\n$javaImports\n@RestController public class Shared { @GetMapping(\"/shared\") public String a() { return \"\"; } }")
        assertEquals(setOf("GET /app/shared"), scan().keys())
    }
}
