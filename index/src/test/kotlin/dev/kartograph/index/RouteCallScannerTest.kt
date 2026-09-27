package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.HttpWrapperArgument
import dev.kartograph.core.HttpWrapperDeclaration
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 합성 Kotlin/Java 표본으로 route-call 수확 규칙을 하나씩 고정한다. 공개 저장소라 실제 앱 코드는 쓰지 않는다. */
class RouteCallScannerTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun scan(
        wrappers: List<HttpWrapperDeclaration> = emptyList(),
        includeTests: Boolean = false,
        roots: List<Path> = emptyList(),
        service: String? = null,
    ): BridgeFactsDocument = RouteCallScanner(project, roots, wrappers, includeTests, service).scan(generatedAt = "2026-01-01T00:00:00Z")

    private fun BridgeFactsDocument.at(line: Int): BridgeFact = facts.single { it.location.line == line }

    private fun endpointWrapper(anchor: String = "root", service: String? = null) = HttpWrapperDeclaration(
        language = "kotlin", kind = "constructor", owner = "dev.example.net.Endpoint", name = "<init>",
        methodArg = HttpWrapperArgument(0, "verb"), pathArg = HttpWrapperArgument(1, "path"), defaultMethod = null,
        methodEnum = mapOf("GET" to "GET", "POST" to "POST", "DELETE" to "DELETE"), pathAnchor = anchor, service = service,
    )

    private fun sendWrapper(owner: String = "dev.example.net.Client", name: String = "send") = HttpWrapperDeclaration(
        language = "kotlin", kind = "function", owner = owner, name = name,
        methodArg = HttpWrapperArgument(2, "method"), pathArg = HttpWrapperArgument(0, "path"), defaultMethod = "POST",
        methodEnum = emptyMap(), pathAnchor = "root", service = null,
    )

    private val endpointSource = """
        package dev.example.net

        private const val ITEMS = "/api/v1/items"
        val TOP = "/api/v1/top"

        enum class Verb { GET, POST, DELETE, PATCH }

        data class Endpoint(val verb: Verb, val path: String, val auth: Boolean = false) {
            companion object {
                const val TAGS = "/api/v1/tags"

                fun list(q: String?, page: Int): Endpoint {
                    val items = buildList { if (page > 1) add("page=${'$'}page") }
                    val suffix = items.takeIf { it.isNotEmpty() }?.joinToString(prefix = "?", separator = "&").orEmpty()
                    return Endpoint(Verb.GET, "${'$'}ITEMS${'$'}suffix")
                }
                fun search(tag: String?): Endpoint {
                    val suffix = tag?.let { "?tag=${'$'}{it}" }.orEmpty()
                    return Endpoint(Verb.GET, "${'$'}TAGS/search${'$'}suffix")
                }
                fun detail(id: String): Endpoint =
                    Endpoint(Verb.GET, "${'$'}ITEMS/${'$'}{id.trim()}", auth = true)
                fun remove(id: String) = Endpoint(path = "${'$'}ITEMS/${'$'}id", verb = Verb.DELETE)
                fun top() = Endpoint(Verb.GET, TOP)
                fun tags() = Endpoint(Verb.POST, Companion.TAGS + "/new")
                fun partial(name: String) = Endpoint(Verb.GET, "/files/${'$'}name.json")
                fun unmapped() = Endpoint(Verb.PATCH, "/api/v1/patch")
                fun unproven(extra: String): Endpoint {
                    val suffix = extra.lowercase()
                    return Endpoint(Verb.GET, "${'$'}ITEMS${'$'}suffix")
                }
            }
        }
    """

    @Test
    fun `constructor wrapper resolves constants, suffix locals, segments and enum verbs`() {
        write("app/src/main/kotlin/dev/example/net/Endpoint.kt", endpointSource)
        val document = scan(listOf(endpointWrapper()))

        assertEquals("http", document.target)
        assertEquals(listOf("client"), document.roles)
        assertEquals("excluded", document.testSources)
        val list = document.at(15)
        assertEquals("/api/v1/items", list.channel)
        assertEquals("GET", list.method)
        assertEquals(true, list.route?.queryTailStripped)
        assertEquals("root", list.route?.pathAnchor)
        assertEquals("dev.example.net.Endpoint.Companion.list", list.symbol?.qualifiedName)
        assertNull(list.symbol?.usr)
        assertEquals("/api/v1/tags/search", document.at(19).channel)
        assertEquals(true, document.at(19).route?.queryTailStripped)
        // 여러 줄 호출은 호출식이 시작하는 줄이다.
        assertEquals("/api/v1/items/{}", document.at(22).channel)
        assertEquals("DELETE", document.at(23).method)
        assertEquals("/api/v1/items/{}", document.at(23).channel)
        assertEquals("/api/v1/top", document.at(24).channel)
        assertEquals("/api/v1/tags/new", document.at(25).channel)
        assertEquals("POST", document.at(25).method)
        val partial = document.at(26)
        assertTrue(partial.dynamic)
        assertEquals("/files/", partial.channelPrefix)
        assertNull(partial.channel)
        val unmapped = document.at(27)
        assertNull(unmapped.method)
        assertEquals(true, unmapped.route?.methodDynamic)
        val unproven = document.at(30)
        assertTrue(unproven.dynamic)
        assertEquals("/api/v1/items", unproven.channelPrefix)
        assertTrue(document.limitations.isEmpty(), document.limitations.toString())
    }

    @Test
    fun `location column is the utf-8 byte column of the call expression`() {
        write(
            "src/main/kotlin/dev/example/net/Endpoint.kt",
            """
            package dev.example.net
            enum class Verb { GET }
            data class Endpoint(val verb: Verb, val path: String)
            fun list() = /* 한글 주석 */ Endpoint(Verb.GET, "/a")
            """,
        )
        val fact = scan(listOf(endpointWrapper().copy(methodEnum = mapOf("GET" to "GET")))).facts.single()
        assertEquals(4, fact.location.line)
        assertEquals("fun list() = /* 한글 주석 */ ".toByteArray(Charsets.UTF_8).size + 1, fact.location.column)
        assertEquals("dev.example.net.list", fact.symbol?.qualifiedName)
    }

    @Test
    fun `function wrapper binds default, named out of order, positional and literal verbs`() {
        write(
            "src/main/kotlin/dev/example/net/Client.kt",
            """
            package dev.example.net

            import java.net.URL
            import java.net.HttpURLConnection

            class Client(private val base: String) {
                private fun send(path: String, body: String? = null, method: String = "POST"): String {
                    val connection = URL(base + path).openConnection() as HttpURLConnection
                    connection.requestMethod = method
                    return ""
                }
                private fun send(path: String) = send(path, null, "GET")

                fun login() = send(path = "/v1/login", body = "{}")
                fun me() = send(path = "/v1/me", body = null, method = "GET")
                fun logout() = send("/v1/logout", "{}")
                fun patch() = send("/v1/patch", null, "PATCH")
                fun lower() = send("/v1/lower", null, "get")
                fun dynamicVerb(verb: String) = send("/v1/verb", null, verb)
                fun local(): String {
                    val path = "/v1/local"
                    return send(path)
                }
                fun mutable(): String {
                    var path = "/v1/mutable"
                    return this.send(path)
                }
            }
            """,
        )
        val document = scan(listOf(sendWrapper()))

        assertEquals("POST", document.at(14).method)
        assertEquals("/v1/login", document.at(14).channel)
        assertEquals("GET", document.at(15).method)
        assertEquals("POST", document.at(16).method)
        assertEquals("PATCH", document.at(17).method)
        assertEquals(true, document.at(18).route?.methodDynamic)
        assertEquals(true, document.at(19).route?.methodDynamic)
        assertEquals("/v1/verb", document.at(19).channel)
        assertEquals("/v1/local", document.at(22).channel)
        assertTrue(document.at(26).dynamic)
        // 래퍼 자신의 오버로드와 URL 싱크는 선언이 덮으므로 사실도 한계도 내지 않는다.
        assertEquals(setOf(14, 15, 16, 17, 18, 19, 22, 26), document.facts.map { it.location.line }.toSet())
        assertTrue(document.limitations.isEmpty(), document.limitations.toString())
    }

    @Test
    fun `enum name method and imported enum constants are verbs only through their names`() {
        write(
            "src/main/kotlin/dev/example/net/Api.kt",
            """
            package dev.example.net

            import dev.example.net.Verb.DELETE

            enum class Verb { GET, DELETE }

            fun request(method: String, path: String): String = ""
            fun request(verb: Verb, path: String, flag: Boolean): String = ""

            object Calls {
                fun a() = request(Verb.GET.name, "/a")
                fun b() = request(DELETE, "/b")
                fun c() = request(method = Verb.GET, path = "/c")
            }
            """,
        )
        val wrapper = HttpWrapperDeclaration(
            language = "kotlin", kind = "function", owner = "dev.example.net", name = "request",
            methodArg = HttpWrapperArgument(0, "method"), pathArg = HttpWrapperArgument(1, "path"), defaultMethod = null,
            methodEnum = mapOf("DELETE" to "DELETE"), pathAnchor = "root", service = "svc",
        )
        val document = scan(listOf(wrapper))

        assertEquals("GET", document.at(11).method)
        assertEquals("DELETE", document.at(12).method)
        assertEquals(true, document.at(13).route?.methodDynamic)
        assertEquals("svc", document.at(11).route?.service)
    }

    @Test
    fun `top level wrappers are visible through imports and aliases only`() {
        write(
            "src/main/kotlin/dev/example/net/Http.kt",
            """
            package dev.example.net
            fun call(path: String, method: String = "GET"): String = path
            """,
        )
        write(
            "src/main/kotlin/dev/example/feature/Feature.kt",
            """
            package dev.example.feature
            import dev.example.net.call as fetch
            class Feature {
                fun load() = fetch("/feature")
                fun qualified() = dev.example.net.call("/qualified", "PUT")
            }
            """,
        )
        write(
            "src/main/kotlin/dev/example/other/Other.kt",
            """
            package dev.example.other
            fun call(path: String) = path
            fun use() = call("/not-a-wrapper")
            """,
        )
        val wrapper = HttpWrapperDeclaration(
            language = "kotlin", kind = "function", owner = "dev.example.net.HttpKt", name = "call",
            methodArg = HttpWrapperArgument(1, "method"), pathArg = HttpWrapperArgument(0, "path"), defaultMethod = "GET",
            methodEnum = emptyMap(), pathAnchor = "base", service = null,
        )
        val document = scan(listOf(wrapper))

        assertEquals(listOf("/feature", "/qualified"), document.facts.map { it.channel })
        assertEquals(listOf("GET", "PUT"), document.facts.map { it.method })
        assertEquals("base", document.facts.first().route?.pathAnchor)
    }

    @Test
    fun `member wrappers match companion qualifiers and typed receivers`() {
        write(
            "src/main/kotlin/dev/example/net/Api.kt",
            """
            package dev.example.net
            class Api {
                fun get(path: String): String = path
                companion object {
                    fun create(path: String): String = path
                }
            }
            """,
        )
        write(
            "src/main/kotlin/dev/example/feature/Screen.kt",
            """
            package dev.example.feature
            import dev.example.net.Api
            class Screen(private val api: Api, private val other: Other) {
                fun load() = api.get("/typed")
                fun skip() = other.get("/untyped")
                fun made() = Api.create("/companion")
            }
            class Other { fun get(path: String) = path }
            """,
        )
        val get = sendWrapper(owner = "dev.example.net.Api", name = "get").copy(methodArg = null, defaultMethod = "GET")
        val create = get.copy(owner = "dev.example.net.Api.Companion", name = "create", defaultMethod = "POST")
        val document = scan(listOf(get, create))

        assertEquals(listOf("/typed", "/companion"), document.facts.map { it.channel })
        assertEquals(listOf("GET", "POST"), document.facts.map { it.method })
    }

    @Test
    fun `stale or unused wrapper declarations are reported instead of reading as no calls`() {
        write("src/main/kotlin/dev/example/net/Endpoint.kt", endpointSource)
        val missing = sendWrapper(owner = "dev.example.net.Missing")
        val wrongName = sendWrapper(owner = "dev.example.net.Endpoint", name = "absent")
        val unused = endpointWrapper().copy(owner = "dev.example.net.Verb")
        val swift = endpointWrapper().copy(language = "swift", owner = "Net.Endpoint", name = "init")
        val document = scan(listOf(missing, wrongName, unused, swift))

        val limitation = document.limitations.single { it.startsWith("http-wrapper-unresolved:") }
        assertTrue(limitation.startsWith("http-wrapper-unresolved: 3 declared kotlin wrapper(s)"), limitation)
        assertTrue("dev.example.net.Missing.send" in limitation)
        assertTrue("dev.example.net.Endpoint.absent" in limitation)
        assertTrue(document.facts.isEmpty())
    }

    @Test
    fun `undeclared sinks are counted without guessing facts`() {
        write(
            "src/main/kotlin/dev/example/net/Raw.kt",
            """
            package dev.example.net
            import java.net.URL
            import java.net.HttpURLConnection
            class Raw(private val base: String) {
                fun call(path: String): String {
                    val connection = URL(base + path).openConnection() as HttpURLConnection
                    return connection.responseMessage
                }
                fun forward(url: String) = open(URL(url))
                fun validate(url: String) = require(URL(url).protocol == "https")
                private fun open(url: URL) = url.openStream()
            }
            """,
        )
        val document = scan()

        assertTrue(document.facts.isEmpty(), document.facts.toString())
        assertTrue(
            document.limitations.contains(
                "http-wrapper-undeclared: 2 HTTP sink(s) pass enclosing-function parameters into a request path; declare the wrapper in http-wrappers to resolve its callers",
            ),
            document.limitations.toString(),
        )
    }

    @Test
    fun `java net url requests carry proven verbs, stripped urls and masked segments`() {
        write(
            "src/main/kotlin/dev/example/net/Direct.kt",
            """
            package dev.example.net
            import java.net.*

            private const val HOST = "https://user:secret@API.Example.com"

            class Direct(private val base: String, private val flag: Boolean) {
                fun health(): String = URL("${'$'}HOST/health?token=abc#top").readText()
                fun post() {
                    val connection = URL("https://api.example.com/v1/items").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                }
                fun upload() {
                    val url = URL("https://api.example.com/v1/upload")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doOutput = true
                }
                fun maybe() {
                    val connection = URL("https://api.example.com/v1/maybe").openConnection() as HttpURLConnection
                    if (flag) connection.requestMethod = "PUT"
                }
                fun scoped() {
                    (URL("https://api.example.com/v1/scoped").openConnection() as HttpURLConnection).apply {
                        requestMethod = "DELETE"
                    }
                }
                fun hook() = URL("https://hooks.slack.com/services/T0/B0/abcdefghij1234567890").openStream()
                fun token() = URL("https://api.example.com/v1/items/550e8400e29b41d4a716446655440000").readBytes()
                fun ambiguous() = URL(base + "items").readText()
                fun based() = URL(base + "/v1/based").readText()
                fun parsed() = URL("https://api.example.com/v1/parsed").host
            }
            """,
        )
        val document = scan()

        val health = document.at(7)
        assertEquals("/health", health.channel)
        assertEquals("GET", health.method)
        assertEquals("api.example.com", health.route?.authority)
        assertEquals(true, health.route?.queryTailStripped)
        assertEquals("POST", document.at(9).method)
        assertEquals("/v1/items", document.at(9).channel)
        assertEquals("POST", document.at(13).method)
        assertEquals(true, document.at(18).route?.methodDynamic)
        assertEquals("DELETE", document.at(22).method)
        assertEquals("/{}/{}/{}/{}", document.at(26).channel)
        assertEquals(4, document.at(26).route?.maskedSegments)
        assertEquals("/v1/items/{}", document.at(27).channel)
        assertEquals(1, document.at(27).route?.maskedSegments)
        assertTrue(document.at(28).dynamic)
        assertEquals("base", document.at(29).route?.pathAnchor)
        assertEquals("/v1/based", document.at(29).channel)
        assertTrue(document.facts.none { it.location.line == 30 })
        assertTrue(document.limitations.any { it.startsWith("ambiguous-base-join: 1 call(s)") }, document.limitations.toString())
        assertTrue(document.facts.none { it.channel.orEmpty().contains("secret") || it.channel.orEmpty().contains("token") })
    }

    @Test
    fun `java sources use new URL, setRequestMethod and static final constants`() {
        write(
            "src/main/java/dev/example/Legacy.java",
            """
            package dev.example;

            import java.net.HttpURLConnection;
            import java.net.URL;

            public class Legacy {
                private static final String ITEMS = "https://api.example.com/v1/items";

                public void remove(String id) throws Exception {
                    HttpURLConnection connection = (HttpURLConnection) new URL(ITEMS + "/" + id).openConnection();
                    connection.setRequestMethod("DELETE");
                }

                public void price() throws Exception {
                    URL url = new URL("https://api.example.com/v1/${'$'}price");
                    url.openStream();
                }
            }
            """,
        )
        val document = scan()

        assertEquals("/v1/items/{}", document.at(10).channel)
        assertEquals("DELETE", document.at(10).method)
        assertEquals("dev.example.Legacy.remove", document.at(10).symbol?.qualifiedName)
        // Java 문자열의 `$`는 보간이 아니라 리터럴이다.
        assertEquals("/v1/\$price", document.at(15).channel)
    }

    @Test
    fun `retrofit annotations map paths through rfc 3986 anchors`() {
        write(
            "src/main/kotlin/dev/example/api/Service.kt",
            """
            package dev.example.api

            import retrofit2.http.*

            private const val USERS = "users"

            interface Service {
                @GET("${'$'}USERS/{id}")
                suspend fun user(@Path("id") id: String): Any

                @Headers("Accept: application/json")
                @POST("/v1/items?draft=true")
                fun create(@Body body: Any): Any

                @GET
                fun raw(@Url url: String): Any

                @HTTP(method = "PATCH", path = "/v1/items/{id}", hasBody = true)
                fun patch(@Path("id") id: String): Any

                @HTTP(method = "purge", path = "/v1/cache")
                fun purge(): Any

                @GET("files/{name}.json")
                fun file(@Path("name") name: String): Any
            }
            """,
        )
        val document = scan()

        val user = document.at(8)
        assertEquals("/users/{}", user.channel)
        assertEquals("base", user.route?.pathAnchor)
        assertEquals("GET", user.method)
        assertEquals("dev.example.api.Service.user", user.symbol?.qualifiedName)
        assertEquals("/v1/items", document.at(12).channel)
        assertEquals("root", document.at(12).route?.pathAnchor)
        assertEquals(true, document.at(12).route?.queryTailStripped)
        assertTrue(document.at(15).dynamic)
        assertNull(document.at(15).channel)
        assertEquals("PATCH", document.at(18).method)
        assertEquals("/v1/items/{}", document.at(18).channel)
        assertEquals(true, document.at(21).route?.methodDynamic)
        assertTrue(document.at(24).dynamic)
        assertEquals("/files/", document.at(24).channelPrefix)
    }

    @Test
    fun `retrofit in java interfaces is read from the method signature`() {
        write(
            "src/main/java/dev/example/api/JavaService.java",
            """
            package dev.example.api;
            import retrofit2.Call;
            import retrofit2.http.DELETE;
            import retrofit2.http.Path;
            public interface JavaService {
                @DELETE("/v1/items/{id}")
                Call<Void> remove(@Path("id") String id);
            }
            """,
        )
        val fact = scan().facts.single()
        assertEquals("DELETE", fact.method)
        assertEquals("/v1/items/{}", fact.channel)
        assertEquals("dev.example.api.JavaService.remove", fact.symbol?.qualifiedName)
    }

    @Test
    fun `test source sets are excluded by default and marked when included`() {
        val call = """
            package dev.example.net
            enum class Verb { GET }
            data class Endpoint(val verb: Verb, val path: String)
            fun main() = Endpoint(Verb.GET, "/main")
        """
        write("app/src/main/kotlin/dev/example/net/Endpoint.kt", call)
        for (set in listOf("test", "androidTest", "testDebug", "commonTest")) {
            write(
                "app/src/$set/kotlin/dev/example/net/${set}Probe.kt",
                """
                package dev.example.net
                fun probe$set() = Endpoint(Verb.GET, "/$set")
                """,
            )
        }
        val wrapper = endpointWrapper().copy(methodEnum = mapOf("GET" to "GET"))

        val excluded = scan(listOf(wrapper))
        assertEquals(listOf("/main"), excluded.facts.map { it.channel })
        assertEquals("excluded", excluded.testSources)

        val included = scan(listOf(wrapper), includeTests = true)
        assertEquals("included", included.testSources)
        assertEquals(5, included.facts.size)
        assertEquals(4, included.facts.count { it.route?.testSource == true })
        assertEquals(false, included.facts.single { it.channel == "/main" }.route?.testSource)
    }

    @Test
    fun `comments strings and declarations are not calls`() {
        write(
            "src/main/kotlin/dev/example/net/Endpoint.kt",
            """
            package dev.example.net
            enum class Verb { GET }
            // Endpoint(Verb.GET, "/comment")
            open class Endpoint(val verb: Verb, val path: String)
            class Sub : Endpoint(Verb.GET, "/super")
            val text = "Endpoint(Verb.GET, \"/string\")"
            fun elvis(cached: Endpoint?) = cached ?: Endpoint(Verb.GET, "/elvis")
            """,
        )
        val document = scan(listOf(endpointWrapper().copy(methodEnum = mapOf("GET" to "GET"))))
        assertEquals(listOf("/elvis"), document.facts.map { it.channel })
    }

    @Test
    fun `source roots restrict scanning and unmodeled clients are counted`() {
        write(
            "app/src/main/kotlin/dev/example/Ok.kt",
            """
            package dev.example
            import okhttp3.OkHttpClient
            class Ok(val client: OkHttpClient)
            """,
        )
        write(
            "lib/src/main/kotlin/dev/example/Ktor.kt",
            """
            package dev.example
            import io.ktor.client.HttpClient
            class Ktor(val client: HttpClient)
            """,
        )
        val all = scan()
        assertTrue(all.limitations.any { it.startsWith("route-call-coverage: 2 source file(s)") }, all.limitations.toString())
        val restricted = scan(roots = listOf(project.resolve("app")))
        assertTrue(restricted.limitations.any { it.startsWith("route-call-coverage: 1 source file(s)") })
    }

    @Test
    fun `the wrapper implementation file is not an unmodeled client gap`() {
        write(
            "src/main/kotlin/dev/example/net/Client.kt",
            """
            package dev.example.net
            import okhttp3.OkHttpClient
            class Client(private val http: OkHttpClient) {
                fun send(path: String, body: String? = null, method: String = "POST") = path
                fun ping() = send("/ping", null, "GET")
            }
            """,
        )
        val document = scan(listOf(sendWrapper()))
        assertEquals(listOf("/ping"), document.facts.map { it.channel })
        assertTrue(document.limitations.isEmpty(), document.limitations.toString())
    }

    @Test
    fun `empty scans keep the http target and scans are deterministic`() {
        write("src/main/kotlin/dev/example/Plain.kt", "class Plain")
        val first = scan(service = "mobile")
        assertEquals("http", first.target)
        assertTrue(first.facts.isEmpty())
        assertEquals("mobile", first.service)

        write("src/main/kotlin/dev/example/net/Endpoint.kt", endpointSource)
        val once = scan(listOf(endpointWrapper()))
        val twice = scan(listOf(endpointWrapper()))
        assertEquals(once.facts, twice.facts)
        assertEquals(once.limitations, twice.limitations)
    }

    // ---- 리뷰 지적 회귀 ----

    @Test
    fun `scheme relative urls never leak userinfo into channels or prefixes`() {
        write(
            "src/main/kotlin/dev/example/net/Relative.kt",
            """
            package dev.example.net
            import java.net.URL
            class Relative(private val protocol: String, private val host: String) {
                fun data(): String = URL("${'$'}protocol//user:pass@example.com/data?k=v").readText()
                fun file(name: String): String = URL("${'$'}protocol//user:pass@example.com/files/${'$'}{name}.json").readText()
                fun hostless(): String = URL("${'$'}protocol//user:pass@${'$'}host/v1/items").readText()
            }
            """,
        )
        val document = scan()

        val data = document.at(4)
        assertEquals("/data", data.channel)
        assertEquals("root", data.route?.pathAnchor)
        assertEquals("example.com", data.route?.authority)
        assertEquals(true, data.route?.queryTailStripped)
        val file = document.at(5)
        assertTrue(file.dynamic)
        assertEquals("/files/", file.channelPrefix)
        val hostless = document.at(6)
        assertEquals("/v1/items", hostless.channel)
        assertEquals("base", hostless.route?.pathAnchor)
        assertNull(hostless.route?.authority)
        assertTrue(document.facts.none { fact -> listOfNotNull(fact.channel, fact.channelPrefix).any { "pass" in it || "@" in it } })
    }
}
