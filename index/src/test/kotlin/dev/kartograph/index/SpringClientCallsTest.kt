package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Spring `RestTemplate`·`RestClient`·`WebClient`·`@HttpExchange` route-call 사실을 합성 Kotlin/Java 표본으로 고정한다. 공개 저장소라
 * 실제 앱 코드는 쓰지 않는다. 결합 규칙은 Spring Framework 6.2.19 `DefaultUriBuilderFactory`(문자열 연결 + `//` 축약)와 Boot
 * `RootUriBuilderFactory`(`/`로 시작하는 템플릿에만 root)다.
 */
class SpringClientCallsTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun scan(includeTests: Boolean = false): BridgeFactsDocument =
        RouteCallScanner(project, includeTests = includeTests).scan(generatedAt = "2026-01-01T00:00:00Z")

    private fun BridgeFactsDocument.of(name: String): List<BridgeFact> = facts.filter { it.symbol?.qualifiedName?.endsWith(".$name") == true }

    private fun BridgeFact.summary(): String =
        "${method ?: "dyn"} ${route?.pathAnchor} ${route?.authority ?: "-"} ${channel ?: "dynamic:${channelPrefix ?: "-"}"} ${route?.baseRef ?: "-"}"

    @Test
    fun `a RestClient bean built from an @Value base in the default profile joins the template as a string`() {
        write("src/main/resources/application.yml", """
            users:
              base-url: http://Users.Internal:8081/api
            """)
        write("src/main/kotlin/dev/example/ClientConfig.kt", """
            package dev.example

            import org.springframework.beans.factory.annotation.Value
            import org.springframework.context.annotation.Bean
            import org.springframework.context.annotation.Configuration
            import org.springframework.web.client.RestClient

            @Configuration
            class ClientConfig {
                @Bean
                fun usersRestClient(builder: RestClient.Builder, @Value("\${'$'}{users.base-url}") baseUrl: String): RestClient =
                    builder.baseUrl(baseUrl).defaultHeader("x", "y").build()
            }
            """)
        write("src/main/kotlin/dev/example/UserClient.kt", """
            package dev.example

            import org.springframework.stereotype.Component
            import org.springframework.web.client.RestClient

            @Component
            class UserClient(private val restClient: RestClient) {
                fun fetchUser(id: String): String? = restClient.get().uri("/users/{id}", id).retrieve().body(String::class.java)
                fun relative(): String? = restClient.get().uri("status").retrieve().body(String::class.java)
                fun lambda(id: String): String? = restClient.delete().uri { it.path("/users/{id}").queryParam("hard", true).build(id) }
                    .retrieve().body(String::class.java)
                fun create(): String? = restClient.method(org.springframework.http.HttpMethod.PATCH).uri("/users//bulk/").retrieve().body(String::class.java)
            }
            """)
        val document = scan()
        assertEquals(listOf("GET root users.internal:8081 /api/users/{} kt:dev.example.ClientConfig.usersRestClient"), document.of("fetchUser").map { it.summary() })
        // `/api` + `status`는 슬래시를 넣지 않는다(FullPathComponentBuilder.append).
        assertEquals(listOf("GET root users.internal:8081 /apistatus kt:dev.example.ClientConfig.usersRestClient"), document.of("relative").map { it.summary() })
        assertEquals(listOf("DELETE root users.internal:8081 /api/users/{} kt:dev.example.ClientConfig.usersRestClient"), document.of("lambda").map { it.summary() })
        // `//`는 한 슬래시로 줄고 끝 슬래시는 남는다(getSanitizedPath).
        assertEquals(listOf("PATCH root users.internal:8081 /api/users/bulk/ kt:dev.example.ClientConfig.usersRestClient"), document.of("create").map { it.summary() })
        assertTrue(document.limitations.none { it.startsWith("route-call-coverage:") || it.startsWith("unresolved-base-url:") }, document.limitations.toString())
    }

    @Test
    fun `WebClient properties, RestTemplateBuilder rootUri and plain RestTemplate follow their own join rules`() {
        write("src/main/kotlin/dev/example/Clients.kt", """
            package dev.example

            import org.springframework.beans.factory.annotation.Value
            import org.springframework.boot.web.client.RestTemplateBuilder
            import org.springframework.http.HttpMethod
            import org.springframework.web.client.RestTemplate
            import org.springframework.web.reactive.function.client.WebClient

            class OrdersClient {
                private val webClient = WebClient.builder().baseUrl("http://orders.internal/v1/").build()

                fun list() = webClient.get().uri("orders").retrieve()
                fun one(id: Long) = webClient.get().uri("/orders/{id}", id).retrieve()
            }

            class BillingClient(builder: RestTemplateBuilder) {
                private val template: RestTemplate = builder.rootUri("https://billing.internal:443/api").build()

                fun invoice(id: String) = template.getForObject("/invoices/{id}", String::class.java, id)
                fun replace(id: String) = template.exchange("/invoices/{id}", HttpMethod.PUT, null, String::class.java, id)
                fun absolute() = template.getForObject("http://audit.internal/log", String::class.java)
            }

            class CatalogClient(@Value("\${'$'}{catalog.url}") private val catalogUrl: String) {
                private val rest = RestTemplate()

                fun item(id: String) = rest.getForObject("${'$'}catalogUrl/items/{id}", String::class.java, id)
                fun raw() = rest.postForEntity("/relative", null, String::class.java)
            }
            """)
        val document = scan()
        assertEquals(listOf("GET root orders.internal /v1/orders -"), document.of("list").map { it.summary() }.map { it.replace(" kt:dev.example.OrdersClient.webClient", " -") })
        assertEquals("GET root orders.internal /v1/orders/{}", document.of("one").single().summary().substringBeforeLast(' '))
        assertEquals("GET root billing.internal /api/invoices/{}", document.of("invoice").single().summary().substringBeforeLast(' '))
        assertEquals("PUT root billing.internal /api/invoices/{}", document.of("replace").single().summary().substringBeforeLast(' '))
        assertEquals("GET root audit.internal /log", document.of("absolute").single().summary().substringBeforeLast(' '))
        // 설정에 없는 `@Value` 키 뒤의 `/items/{id}`는 base 뒤 꼬리이고, baseRef는 그 값을 담은 선언이다.
        assertEquals(listOf("GET base - /items/{} kt:dev.example.CatalogClient.catalogUrl"), document.of("item").map { it.summary() })
        assertEquals("POST base - /relative", document.of("raw").single().summary().substringBeforeLast(' '))
        assertTrue(document.limitations.any { it.startsWith("unresolved-base-url: 2 Spring") }, document.limitations.toString())
    }

    @Test
    fun `Java constructor injection, qualifiers, profiles, RequestEntity and parameter sinks`() {
        write("src/main/resources/application.properties", """
            inventory.url=http://inventory.internal/inv
            """)
        write("src/main/resources/application-prod.properties", """
            inventory.url=http://inventory.prod/inv
            """)
        write("src/main/java/dev/example/InventoryConfig.java", """
            package dev.example;

            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.web.client.RestClient;

            @Configuration
            public class InventoryConfig {
                @Bean("inventory")
                public RestClient inventoryClient(RestClient.Builder builder, @Value("${'$'}{inventory.url}") String url) {
                    return builder.baseUrl(url).build();
                }

                @Bean
                public RestClient other() {
                    return RestClient.create("http://other.internal");
                }
            }
            """)
        write("src/main/java/dev/example/InventoryClient.java", """
            package dev.example;

            import org.springframework.beans.factory.annotation.Qualifier;
            import org.springframework.http.RequestEntity;
            import org.springframework.web.client.RestClient;
            import org.springframework.web.client.RestTemplate;

            public class InventoryClient {
                private final RestClient client;
                private final RestTemplate template = new RestTemplate();

                public InventoryClient(@Qualifier("inventory") RestClient client) {
                    this.client = client;
                }

                public String stock(String sku) {
                    return client.get().uri("/stock/{sku}", sku).retrieve().body(String.class);
                }

                public String entity() {
                    return template.exchange(RequestEntity.get("http://x.internal/a").build(), String.class).getBody();
                }

                public String sink(String path) {
                    return client.get().uri(path).retrieve().body(String.class);
                }
            }
            """)
        val document = scan()
        assertEquals(listOf("GET root inventory.internal /inv/stock/{} kt:dev.example.InventoryConfig.inventoryClient"), document.of("stock").map { it.summary() })
        assertTrue(document.of("entity").isEmpty() && document.of("sink").isEmpty(), document.facts.toString())
        assertTrue(document.limitations.any { it.startsWith("unresolved-base-url: 1 Spring HTTP client call(s) take their base URL") }, document.limitations.toString())
        assertTrue(document.limitations.any { it.startsWith("route-call-coverage: 1 Spring") }, document.limitations.toString())
        assertTrue(document.limitations.any { it.startsWith("http-wrapper-undeclared: 1") }, document.limitations.toString())
    }

    @Test
    fun `HttpExchange interfaces join type and method urls onto the adapter client base`() {
        write("src/main/kotlin/dev/example/UsersApi.kt", """
            package dev.example

            import org.springframework.web.bind.annotation.PathVariable
            import org.springframework.web.service.annotation.GetExchange
            import org.springframework.web.service.annotation.HttpExchange
            import org.springframework.web.service.annotation.PostExchange
            import java.net.URI

            @HttpExchange("/v2")
            interface UsersApi {
                @GetExchange("users/{id}")
                fun user(@PathVariable id: String): String

                @PostExchange(url = "/users")
                fun create(body: String): String

                @HttpExchange(method = "DELETE", url = "users/{id}")
                fun remove(@PathVariable id: String)

                @GetExchange
                fun dynamic(uri: URI): String
            }
            """)
        write("src/main/kotlin/dev/example/ApiConfig.kt", """
            package dev.example

            import org.springframework.context.annotation.Bean
            import org.springframework.web.client.RestClient
            import org.springframework.web.client.support.RestClientAdapter
            import org.springframework.web.service.invoker.HttpServiceProxyFactory

            class ApiConfig {
                @Bean
                fun usersApi(): UsersApi {
                    val client = RestClient.builder().baseUrl("http://users.internal/api").build()
                    val factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                    return factory.createClient(UsersApi::class.java)
                }
            }
            """)
        val document = scan()
        assertEquals(listOf("GET root users.internal /api/v2/users/{} kt:dev.example.ApiConfig.usersApi"), document.of("user").map { it.summary() })
        assertEquals(listOf("POST root users.internal /api/v2/users kt:dev.example.ApiConfig.usersApi"), document.of("create").map { it.summary() })
        assertEquals(listOf("DELETE root users.internal /api/v2/users/{} kt:dev.example.ApiConfig.usersApi"), document.of("remove").map { it.summary() })
        assertEquals("GET base - dynamic:- kt:dev.example.ApiConfig.usersApi", document.of("dynamic").single().summary())
    }

    @Test
    fun `HttpExchange interfaces implemented by a controller are server contracts, not calls`() {
        write("src/main/kotlin/dev/example/Contract.kt", """
            package dev.example

            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.service.annotation.GetExchange

            interface Contract {
                @GetExchange("/ping")
                fun ping(): String
            }

            @RestController
            class ContractController : Contract {
                override fun ping() = "pong"
            }
            """)
        assertTrue(scan().facts.isEmpty())
    }

    @Test
    fun `imperative calls take the enclosing method id and exchange methods the interface method id`() {
        val path = "src/main/kotlin/dev/example/Gateway.kt"
        write(path, """
            package dev.example

            import org.springframework.web.client.RestClient
            import org.springframework.web.service.annotation.GetExchange

            class Gateway(private val client: RestClient) {
                fun fetch(id: String): String? = client.get().uri("/users/{id}", id).retrieve().body(String::class.java)

                fun twoLines(id: String): String? =
                    client.get().uri("/users/{id}/v2", id)
                        .retrieve().body(String::class.java)

                abstract fun marker(): String
            }

            interface Remote {
                @GetExchange("/remote/{id}")
                fun remote(id: String): String
            }
            """)
        fun node(owner: String, signature: String, line: Int?, vararg annotations: String) = GraphNode(
            NodeId("method:dev/example/$owner#$signature"), signature.substringBefore('('), NodeKind.METHOD,
            location = SourceLocation(path, line), annotations = annotations.toSet(),
            jvmModifiers = if (line == null) setOf(JvmModifier.ABSTRACT) else emptySet(),
        )
        val graph = CodeGraph(
            listOf(
                node("Gateway", "fetch(Ljava/lang/String;)Ljava/lang/String;", 7),
                node("Gateway", "twoLines(Ljava/lang/String;)Ljava/lang/String;", 10),
                node("Remote", "remote(Ljava/lang/String;)Ljava/lang/String;", null, "org/springframework/web/service/annotation/GetExchange"),
            ),
            emptyList(),
        )
        val facts = RouteCallScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z", graph = graph).facts.associate { it.channel to it.symbol?.usr }
        // 한 줄 식 몸체 함수는 주 생성자가 있는 클래스 머리(Java 선언 모양)에 가려지지 않는다.
        assertEquals("method:dev/example/Gateway#fetch(Ljava/lang/String;)Ljava/lang/String;", facts["/users/{}"])
        assertEquals("method:dev/example/Gateway#twoLines(Ljava/lang/String;)Ljava/lang/String;", facts["/users/{}/v2"])
        assertEquals("method:dev/example/Remote#remote(Ljava/lang/String;)Ljava/lang/String;", facts["/remote/{}"])
    }

    @Test
    fun `less common client shapes resolve through fields, factories, URIs and helper functions`() {
        write("src/main/resources/application.yml", """
            ledger:
              url: http://ledger.internal/l
            """)
        write("src/main/kotlin/dev/example/More.kt", """
            package dev.example

            import java.net.URI
            import org.springframework.beans.factory.annotation.Autowired
            import org.springframework.beans.factory.annotation.Value
            import org.springframework.boot.web.client.RestTemplateBuilder
            import org.springframework.context.annotation.Bean
            import org.springframework.http.HttpMethod
            import org.springframework.web.client.RestClient
            import org.springframework.web.client.RestTemplate
            import org.springframework.web.reactive.function.client.WebClient
            import org.springframework.web.util.DefaultUriBuilderFactory
            import org.springframework.web.util.UriComponentsBuilder

            class Beans {
                @Bean
                fun ledgerTemplate() = RestTemplateBuilder().uriTemplateHandler(DefaultUriBuilderFactory("http://factory.internal/f")).build()
            }

            class Fields {
                @Value("\${'$'}{ledger.url}")
                lateinit var ledgerUrl: String

                @Autowired
                lateinit var template: RestTemplate

                private val base = "http://readonly.internal/r"
                private val uris = RestClient.create(URI.create("http://uri.internal/u/"))
                private val custom = RestClient.builder().baseUrl("http://custom.internal").someVendorOption().build()
                private val web = WebClient.create("http://web.internal")

                private fun helper(): RestClient = RestClient.create(base)

                fun viaField(id: String) = template.getForObject("${'$'}ledgerUrl/entries/{id}", String::class.java, id)
                fun viaFactory() = template.getForObject("/factory-path", String::class.java)
                fun viaHelper() = helper().get().uri("/helper").retrieve()
                fun viaValueOf() = uris.method(HttpMethod.valueOf("DELETE")).uri("item").retrieve()
                fun relativeUri() = uris.get().uri(URI.create("/root-only")).retrieve()
                fun siblingUri() = uris.get().uri(URI.create("sibling")).retrieve()
                fun absoluteUri() = web.get().uri(URI.create("http://elsewhere.internal/x")).retrieve()
                fun customBuilder() = custom.get().uri("/c").retrieve()
                fun builtHost() = template.getForObject(
                    UriComponentsBuilder.newInstance().scheme("http").host("built.internal").path("/b/{id}").toUriString(), String::class.java, 1,
                )
                fun uriObject() = template.getForObject(URI.create("http://object.internal/o"), String::class.java)
            }
            """)
        write("src/main/java/dev/example/JavaFields.java", """
            package dev.example;

            import static org.springframework.http.HttpMethod.GET;

            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.web.client.RestTemplate;

            public class JavaFields {
                @Value("${'$'}{ledger.url}")
                private String url;

                private final RestTemplate template = new RestTemplate();

                public String entry(String id) {
                    return template.exchange(url + "/java/{id}", GET, null, String.class, id).getBody();
                }
            }
            """)
        val document = scan()
        fun one(name: String) = document.of(name).single().summary().substringBeforeLast(' ')
        assertEquals("GET root ledger.internal /l/entries/{}", one("viaField"))
        assertEquals("GET root factory.internal /f/factory-path", one("viaFactory"))
        assertEquals("GET root readonly.internal /r/helper", one("viaHelper"))
        assertEquals("DELETE root uri.internal /u/item", one("viaValueOf"))
        // RestClient는 상대 URI를 base에 RFC 3986으로 해석한다 — `/x`는 base 경로를 버리고, `x`는 base의 마지막 `/` 뒤에 붙는다.
        assertEquals("GET root uri.internal /root-only", one("relativeUri"))
        assertEquals("GET root uri.internal /u/sibling", one("siblingUri"))
        assertEquals("GET root elsewhere.internal /x", one("absoluteUri"))
        // 모르는 빌더 메서드는 base를 바꿀 수 있다 — base 앵커다.
        assertEquals("GET base - /c", one("customBuilder"))
        assertEquals("GET root built.internal /b/{}", one("builtHost"))
        assertEquals("GET root object.internal /o", one("uriObject"))
        assertEquals("GET root ledger.internal /l/java/{}", one("entry"))
    }

    @Test
    fun `unproven receivers are counted instead of guessed and exchange interfaces without a factory keep a base anchor`() {
        write("src/main/kotlin/dev/example/Unproven.kt", """
            package dev.example

            import org.springframework.web.bind.annotation.PathVariable
            import org.springframework.web.client.RestClient
            import org.springframework.web.client.RestTemplate
            import org.springframework.web.service.annotation.GetExchange
            import org.springframework.web.service.annotation.HttpExchange
            import org.springframework.web.service.invoker.HttpServiceProxyFactory
            import org.springframework.web.client.support.RestClientAdapter

            class Holder(val template: RestTemplate)

            class Unproven(private val holder: Holder, private val builder: RestClient.Builder) {
                private val cache = mutableMapOf<String, String>()
                private lateinit var lateBuilder: RestClient.Builder
                private val factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.baseUrl("http://late.internal/z").build())).build()
                private val api: PropertyApi = factory.createClient(PropertyApi::class.java)

                fun viaHolder() = holder.template.put("/remote/{id}", "body", 1)
                fun viaHolderGet() = holder.template.getForObject("/remote", String::class.java)
                fun notAClient() = cache.put("/key", "value")
                fun injectedBuilder() = builder.build().get().uri("/built").retrieve()
            }

            @HttpExchange("/orphan")
            interface OrphanApi {
                @GetExchange("{id}")
                fun orphan(@PathVariable id: String): String

                @GetExchange("\${'$'}{orphan.path}")
                fun placeholder(): String
            }

            interface PropertyApi {
                @GetExchange("/p")
                fun property(): String
            }
            """)
        val document = scan()
        assertTrue(document.of("viaHolder").isEmpty() && document.of("viaHolderGet").isEmpty() && document.of("notAClient").isEmpty(), document.facts.toString())
        assertTrue(document.limitations.any { it.startsWith("route-call-coverage: 2 Spring") }, document.limitations.toString())
        // base 없는 Boot 주입 빌더에 상대 경로다 — 요청을 보낼 수 없어 base 앵커로만 남긴다.
        assertEquals("GET base - /built", document.of("injectedBuilder").single().summary().substringBeforeLast(' '))
        assertEquals("GET base - /orphan/{} -", document.of("orphan").single().summary())
        assertEquals("GET base - dynamic:- -", document.of("placeholder").single().summary())
        assertEquals("GET root late.internal /z/p", document.of("property").single().summary().substringBeforeLast(' '))
        assertTrue(document.limitations.any { it.startsWith("unresolved-base-url: 1 Spring RestTemplate/RestClient/WebClient call(s) and 1 @HttpExchange") }, document.limitations.toString())
    }

    @Test
    fun `beans that inject a client of their own kind do not recurse forever`() {
        write("src/main/kotlin/dev/example/Loop.kt", """
            package dev.example

            import org.springframework.context.annotation.Bean
            import org.springframework.web.client.RestClient

            class LoopConfig {
                @Bean
                fun first(second: RestClient) = second.mutate().build()

                @Bean
                fun second(first: RestClient) = first.mutate().build()
            }

            class LoopUser(private val first: RestClient) {
                fun call() = first.get().uri("/loop").retrieve()
            }
            """)
        assertEquals("GET base - /loop", scan().of("call").single().summary().substringBeforeLast(' '))
    }

    @Test
    fun `review findings - constructor parameter types, single interpolation bases, FQN builders and query slashes`() {
        write("src/main/resources/application.yml", """
            api:
              host: http://single.internal/s
            """)
        write("src/main/kotlin/dev/example/Review.kt", """
            package dev.example

            import org.springframework.beans.factory.annotation.Value
            import org.springframework.web.bind.annotation.RestController
            import org.springframework.web.client.RestClient
            import org.springframework.web.service.annotation.GetExchange

            interface InjectedApi {
                @GetExchange("/injected")
                fun injected(): String
            }

            @RestController
            class UsesApi(private val api: InjectedApi) {
                fun go() = api.injected()
            }

            class Single(@Value("\${'$'}{api.host}") private val host: String) {
                private val client = RestClient.builder().baseUrl("${'$'}host").build()
                private val template = org.springframework.boot.restclient.RestTemplateBuilder().rootUri("http://fqn.internal/q").build()

                fun single() = client.get().uri("/one").retrieve()
                fun fqn() = template.getForObject("/two", String::class.java)
                fun query() = client.get().uri("/redirect?to=http://internal.svc/next").retrieve()
                fun relativeUri() = client.get().uri(java.net.URI.create("x")).retrieve()
            }

            class UnknownBase(private val client: RestClient) {
                fun relative() = client.get().uri(java.net.URI.create("x")).retrieve()
            }
            """)
        val document = scan()
        fun one(name: String) = document.of(name).single().summary().substringBeforeLast(' ')
        // 생성자 매개변수 타입은 컨트롤러의 상위 타입이 아니다 — 주입받아 쓰는 클라이언트 인터페이스의 사실을 버리지 않는다.
        assertEquals("GET base - /injected -", document.of("injected").single().summary())
        // 보간 하나뿐인 base 템플릿(`"${'$'}host"`)도 `@Value` 값으로 푼다.
        assertEquals("GET root single.internal /s/one", one("single"))
        assertEquals("GET root fqn.internal /q/two", one("fqn"))
        // query는 템플릿에서 떼어 내므로 `//` 축약이 query에 닿지 않는다.
        assertEquals("GET root single.internal /s/redirect", one("query"))
        // base를 모르는 상대 URI는 문자열 템플릿과 같이 모호한 결합으로 센다.
        assertEquals("GET base - dynamic:-", one("relative"))
        assertTrue(document.limitations.any { it.startsWith("ambiguous-base-join: 1") }, document.limitations.toString())
    }
}
