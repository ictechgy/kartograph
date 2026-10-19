package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.HttpWrapperArgument
import dev.kartograph.core.HttpWrapperDeclaration
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import dev.kartograph.index.RouteUrlRules.UrlPart
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun `snapshot symbol follows the enclosing function after a double quote character literal`() {
        val path = "src/main/kotlin/dev/example/net/Direct.kt"
        write(
            path,
            """
            package dev.example.net
            import java.net.*

            class Direct {
                fun isQuote(c: Char): Boolean {
                    return c == '"'
                }
                fun post() {
                    val connection = URL("https://api.example.com/v1/items").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                }
            }
            """,
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:dev/example/net/Direct#isQuote(C)Z"), "isQuote", NodeKind.METHOD, location = SourceLocation(path, 6)),
            GraphNode(NodeId("method:dev/example/net/Direct#post()V"), "post", NodeKind.METHOD, location = SourceLocation(path, 9)),
        ), emptyList())

        val fact = RouteCallScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z", graph = graph).facts.single()

        assertEquals("/v1/items", fact.channel)
        // `'"'`를 문자열 시작으로 읽으면 isQuote 범위가 파일 끝까지 늘어나 호출이 isQuote에 붙는다.
        assertEquals("method:dev/example/net/Direct#post()V", fact.symbol?.usr)
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
    fun `retrofit java annotations read named elements and interface constants`() {
        write(
            "src/main/java/dev/example/api/LegacyService.java",
            """
            package dev.example.api;
            import okhttp3.RequestBody;
            import okhttp3.ResponseBody;
            import retrofit2.Call;
            import retrofit2.http.*;
            public interface LegacyService {
                String ITEMS = "legacy/items";
                @GET(value = "legacy/items/{id}")
                Call<ResponseBody> item(@Path("id") String id);
                @HTTP(method = "PATCH", path = "legacy/items/{id}", hasBody = true)
                Call<ResponseBody> patch(@Path("id") String id, @Body RequestBody body);
                @GET(ITEMS)
                Call<ResponseBody> items();
            }
            """,
        )
        val document = scan()
        assertEquals("/legacy/items/{}", document.at(8).channel)
        assertEquals("PATCH", document.at(10).method)
        assertEquals("/legacy/items/{}", document.at(10).channel)
        assertEquals("/legacy/items", document.at(12).channel)
        // Retrofit 서비스가 import한 OkHttp 본문 타입은 모델링하지 않은 호출의 근거가 아니다.
        assertTrue(document.limitations.none { it.startsWith("route-call-coverage:") }, document.limitations.toString())
    }

    @Test
    fun `okhttp body helper imports in a retrofit service are not unmodeled clients`() {
        write(
            "src/main/kotlin/dev/example/api/Upload.kt",
            """
            package dev.example.api

            import okhttp3.MultipartBody
            import okhttp3.MultipartBody.Part.Companion.createFormData
            import okhttp3.RequestBody.Companion.asRequestBody
            import okhttp3.RequestBody.Companion.create
            import retrofit2.http.Multipart
            import retrofit2.http.POST
            import retrofit2.http.Part

            interface Upload {
                @Multipart
                @POST("files")
                fun upload(@Part file: MultipartBody.Part): Any
                @POST("raw")
                fun raw(@retrofit2 . http . Url url: String): Any
            }
            """,
        )
        write("src/main/kotlin/dev/example/api/Client.kt", "package dev.example.api\nimport okhttp3.OkHttpClient\nclass Client(val http: OkHttpClient)")
        val document = scan()
        assertTrue(document.limitations.any { it.startsWith("route-call-coverage: 1 source file(s)") }, document.limitations.toString())
        assertEquals("/files", document.at(13).channel)
        // 완전한 이름의 `@Url`도 공백과 무관하게 URL 전체를 실행 시점 값으로 만든다.
        assertTrue(document.at(15).dynamic)
    }

    @Test
    fun `retrofit paths that cannot be proven stay dynamic instead of becoming parameters`() {
        write("src/main/kotlin/dev/example/api/Paths.kt", "package dev.example.api\nobject Paths { const val MEMBERS = \"orgs/members\" }")
        write(
            "src/main/kotlin/dev/example/api/Service.kt",
            """
            package dev.example.api

            import retrofit2.http.GET
            import retrofit2.http.Path

            interface Service {
                @GET(Paths.MEMBERS)
                fun members(): Any
                @GET("orgs/" + Paths.MEMBERS)
                fun nested(): Any
                @GET("docs/{path}/raw")
                fun doc(@Path(value = "path", encoded = true) path: String): Any
                @GET("../v2/status")
                fun escape(): Any
                @retrofit2.http.DELETE("./users/{id}")
                fun remove(@Path("id") id: String): Any
                @GET("search?q=" + Paths.MEMBERS)
                fun search(): Any
            }
            """,
        )
        val document = scan()
        assertTrue(document.at(7).dynamic)
        assertNull(document.at(7).channelPrefix)
        assertTrue(document.at(9).dynamic)
        assertEquals("/orgs/", document.at(9).channelPrefix)
        assertTrue(document.at(11).dynamic)
        assertEquals("/docs/", document.at(11).channelPrefix)
        assertTrue(document.at(13).dynamic)
        assertEquals("DELETE", document.at(15).method)
        assertEquals("/users/{}", document.at(15).channel)
        assertEquals("/search", document.at(17).channel)
        assertEquals(true, document.at(17).route?.queryTailStripped)
    }

    @Test
    fun `encoded path names read from constants in a parameter list keep the scan alive`() {
        // 매개변수 어노테이션은 함수 머리 안이다. 그 위치에서 지역 선언을 찾을 때 함수 몸체 시작이 검색 입력(위치 앞까지) 밖이라
        // IndexOutOfBoundsException이 났고, routes --role client가 exit 2로 끝났다. 머리에서는 몸체의 지역 선언이 보이지 않는다.
        write(
            "src/main/kotlin/dev/example/api/Encoded.kt",
            """
            package dev.example.api

            import retrofit2.http.GET
            import retrofit2.http.Path

            const val DOC = "path"

            interface Encoded {
                @GET("docs/{path}/raw")
                fun doc(@Path(value = DOC, encoded = true) path: String): Any
            }
            """,
        )
        val doc = scan().at(9)
        // 상수로 읽은 이름도 리터럴 이름(`retrofit paths that cannot be proven ...`)과 같게 그 자리부터 dynamic이다.
        assertTrue(doc.dynamic)
        assertEquals("/docs/", doc.channelPrefix)
    }

    @Test
    fun `retrofit constant fan-out beyond the per-call budget stays dynamic`() {
        // 리뷰 지적 재현(index-security#0): 단계마다 다음 상수를 64번 잇는 Kotlin 상수다. 펼침이 최상위 호출당 예산(1만 번)을 넘는다.
        // 예전 코드는 이 크기를 1초 안에 끝까지 펼쳤지만, 한 단계만 늘려도 수 초, 두 단계면 끝나지 않았다.
        write("src/main/kotlin/dev/example/api/Fan.kt", "package dev.example.api\n\nimport retrofit2.http.GET\n\n" +
            (0 until 3).joinToString("") { level -> "const val K$level = \"" + "\$K${level + 1}".repeat(64) + "\"\n" } +
            "const val K3 = \"\"\nconst val SMALL = \"\$K3\$K3/small\"\n\ninterface Fan {\n    @GET(\"/fan\$K0\")\n    fun fan(): Any\n\n" +
            "    @GET(SMALL)\n    fun small(): Any\n}")
        val facts = scan().facts.associateBy { it.symbol?.qualifiedName }
        val fan = facts.getValue("dev.example.api.Fan.fan")
        assertTrue(fan.dynamic)
        // 예산을 넘긴 식은 앞부분만 펼친 조각을 남기지 않고 식 전체를 모르는 값으로 둔다.
        assertNull(fan.channelPrefix)
        assertEquals("/small", facts.getValue("dev.example.api.Fan.small").channel)
    }

    @Test
    fun `query tail proofs over self or mutually referencing locals stop at the depth limit`() {
        // 리뷰 지적 재현: query 꼬리 증명은 joinToString의 prefix 식을 깊이 0부터 다시 풀었다. 지역 val은 사용 위치에서 찾으므로
        // 자기 이름을 가린 val이나 서로를 가리키는 val에서 증명이 끝없이 돌았고, 펼침 예산(1만 번)보다 스택이 먼저 고갈됐다.
        write(
            "src/main/kotlin/dev/example/net/Loop.kt",
            """
            package dev.example.net
            import java.net.*

            class Loop(private val b: String) {
                fun shadowed(suffix: String) {
                    val suffix = listOf("a").joinToString(prefix = suffix)
                    val connection = URL("https://api.example.com/v1/items${'$'}suffix").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                }
                fun mutual() {
                    val a = listOf("x").joinToString(prefix = b)
                    val b = listOf("y").joinToString(prefix = a)
                    val connection = URL("https://api.example.com/v1/pairs${'$'}a").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                }
                fun proven() {
                    val suffix = listOf("a").joinToString(prefix = "?")
                    val connection = URL("https://api.example.com/v1/proven${'$'}suffix").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                }
            }
            """,
        )
        val document = scan()

        // 증명하지 못한 꼬리는 경로를 지어내지 않고 dynamic으로 남는다.
        val shadowed = document.at(7)
        assertTrue(shadowed.dynamic)
        assertEquals("/v1/items", shadowed.channelPrefix)
        assertEquals("POST", shadowed.method)
        val mutual = document.at(13)
        assertTrue(mutual.dynamic)
        assertEquals("/v1/pairs", mutual.channelPrefix)
        // 대조: prefix가 증명되는 꼬리는 그대로 떼어 낸다.
        assertEquals("/v1/proven", document.at(18).channel)
        assertEquals(true, document.at(18).route?.queryTailStripped)
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

    @Test
    fun `request method assignments are unconditional only on the connection's own block or scope function`() {
        write(
            "src/main/kotlin/dev/example/net/Methods.kt",
            """
            package dev.example.net
            import java.net.URL
            import java.net.HttpURLConnection
            class Methods(private val upload: Boolean, private val mode: Int) {
                fun sameBlock() {
                    if (upload) {
                        val c = URL("https://api.example.com/v1/same").openConnection() as HttpURLConnection
                        c.requestMethod = "POST"
                        c.connect()
                    }
                }
                fun nestedIf() {
                    val c = URL("https://api.example.com/v1/nested").openConnection() as HttpURLConnection
                    if (upload) {
                        c.requestMethod = "POST"
                    }
                }
                fun nestedWhen() {
                    val c = URL("https://api.example.com/v1/when").openConnection() as HttpURLConnection
                    when (mode) {
                        1 -> c.requestMethod = "PUT"
                    }
                }
                fun conditionalRun() {
                    val c = URL("https://api.example.com/v1/run").openConnection() as HttpURLConnection
                    if (upload) c.run {
                        requestMethod = "PATCH"
                    }
                }
                fun otherObject(other: HttpURLConnection) {
                    val c = URL("https://api.example.com/v1/other").openConnection() as HttpURLConnection
                    other.requestMethod = "DELETE"
                    c.connect()
                }
            }
            """,
        )
        val document = scan()

        assertEquals("POST", document.at(7).method)
        assertEquals(true, document.at(13).route?.methodDynamic)
        assertEquals(true, document.at(19).route?.methodDynamic)
        assertEquals(true, document.at(25).route?.methodDynamic)
        assertEquals("GET", document.at(31).method)
    }

    @Test
    fun `same line scope functions prove the request method`() {
        write(
            "src/main/kotlin/dev/example/net/Scoped.kt",
            """
            package dev.example.net
            import java.net.URL
            import java.net.HttpURLConnection
            class Scoped {
                fun applied() {
                    val c = URL("https://api.example.com/v1/applied").openConnection().apply { (this as HttpURLConnection).requestMethod = "X" }
                }
                fun chained() {
                    val c = (URL("https://api.example.com/v1/chained").openConnection() as HttpURLConnection).apply { requestMethod = "POST" }
                }
                fun withBlock() {
                    val c = URL("https://api.example.com/v1/with").openConnection() as HttpURLConnection
                    with(c) { requestMethod = "DELETE" }
                }
                fun alsoBlock() {
                    URL("https://api.example.com/v1/also").openConnection().also { (it as HttpURLConnection).connect() }
                }
            }
            """,
        )
        val document = scan()

        assertEquals(true, document.at(6).route?.methodDynamic)
        assertEquals("POST", document.at(9).method)
        assertEquals("DELETE", document.at(12).method)
        assertEquals("GET", document.at(16).method)
    }

    @Test
    fun `a url assigned across a line break still reaches its request`() {
        write(
            "src/main/kotlin/dev/example/net/Wrapped.kt",
            """
            package dev.example.net
            import java.net.URL
            import java.net.HttpURLConnection
            class Wrapped {
                fun read(): String {
                    val url =
                        URL("https://api.example.com/v1/items")
                    return url.readText()
                }
                fun open() {
                    val connection =
                        URL("https://api.example.com/v1/open").openConnection() as HttpURLConnection
                    connection.requestMethod = "PUT"
                }
            }
            """,
        )
        val document = scan()

        assertEquals("/v1/items", document.at(7).channel)
        assertEquals("GET", document.at(7).method)
        assertEquals("PUT", document.at(12).method)
    }

    @Test
    fun `malformed owners never crash the scan`() {
        write("src/main/kotlin/dev/example/Plain.kt", "package dev.example\nfun plain() = 1")
        val broken = listOf("com.", ".", "", "..x").map { owner -> sendWrapper(owner = owner) } +
            listOf("com.", ".").map { owner -> endpointWrapper().copy(owner = owner) }
        val document = scan(broken)
        assertTrue(document.facts.isEmpty())
        assertTrue(document.limitations.any { it.startsWith("http-wrapper-unresolved:") })
    }

    @Test
    fun `kotlin statement ends are found without copying the rest of the file`() {
        // 리뷰 지적 재현(index-security#3): 깊이 0의 줄바꿈마다 파일 앞뒤를 통째로 복사해, 상수 수 × 파일 크기만큼 할당했다.
        // 시간 대신 같은 모양의 파일에서 상수 수만 두 배로 늘린 스레드 할당량을 비교한다. 선형이면 두 배 안팎이고 예전에는 네 배였다.
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "thread allocation accounting is unavailable on this JVM")
        fun allocated(count: Int): Long {
            val source = "package demo\n\nobject Keys {\n" + (0 until count).joinToString("") { "    const val KEY_$it = \"value-$it\"\n" } + "}\n"
            fun model() = RouteSourceFile("src/main/kotlin/demo/Keys.kt", isJava = false, isTest = false, source = source)
            // 첫 실행의 클래스 적재 할당을 빼려고 한 번 먼저 만든다.
            model()
            val before = threads!!.currentThreadAllocatedBytes
            val constants = model().constants
            val bytes = threads.currentThreadAllocatedBytes - before
            assertEquals(count, constants.size)
            assertEquals("\"value-${count - 1}\"", constants.getValue("KEY_${count - 1}").single().expression)
            return bytes
        }
        val small = allocated(STATEMENT_CONSTANTS)
        val large = allocated(STATEMENT_CONSTANTS * 2)
        assertTrue(large < small * 3, "doubling $STATEMENT_CONSTANTS constants grew allocation from $small to $large bytes")
    }

    @Test
    fun `statement ends match the copying implementation on random kotlin text`() {
        // index-security#3 수정의 보존 대조: 위치로 공백을 건너뛰는 판정이 예전처럼 앞뒤를 복사해 trimEnd·trimStart한 판정과 같아야 한다.
        // 공백 술어(Char.isWhitespace)가 같은지 보려고 NBSP·EM SPACE·CR을 섞는다.
        val random = Random(20_261_019)
        val tokens = listOf("a", "x1", "+", "-", "*", "/", ".", "(", ")", "[", "]", "{", "}", ",", "=", "?", ":", "?.", "?:", "&", "|",
            "%", "<", ">", "!", ";", " ", "\t", "\u00A0", "\u2003", "\r", "\n", "\n", "\n  ", "\n\n")
        repeat(200) { sample ->
            val source = buildString { repeat(random.nextInt(1, 80)) { append(tokens[random.nextInt(tokens.size)]) } }
            val file = RouteSourceFile("src/main/kotlin/demo/R.kt", isJava = false, isTest = false, source = source)
            for (start in 0..file.masked.length) {
                assertEquals(copyingStatementEnd(file.masked, start), file.statementEnd(start), "sample $sample start $start")
            }
        }
    }

    /** 수정 전 [RouteSourceFile.statementEnd]를 그대로 옮긴 대조용 구현이다. 연산자 집합도 그때 값이다. */
    private fun copyingStatementEnd(masked: String, start: Int): Int {
        fun continuesAfter(newline: Int): Boolean {
            val before = masked.substring(0, newline).trimEnd()
            if (before.isEmpty() || before.last() in "+-*/.(,=?:&|%<>!") return true
            val after = masked.substring(newline + 1).trimStart()
            return after.startsWith("?.") || after.startsWith("?:") || (after.isNotEmpty() && after.first() in ".+-*/?:&|%)]")
        }
        var depth = 0
        var index = start
        while (index < masked.length) {
            when (masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { if (depth == 0) return index; depth-- }
                ';' -> if (depth == 0) return index
                '\n' -> if (depth == 0 && !continuesAfter(index)) return index
            }
            index++
        }
        return masked.length
    }

    @Test
    fun `budget exhausting path expressions do not repeat work proportional to the file`() {
        // 리뷰 지적 재현: 한정자 없는 이름을 따라갈 때마다 지역 선언을 찾으며 이름별 정규식을 만들고, 파일을 사용 위치까지 복사해
        // 함수 몸체를 다시 훑었다. 그래서 펼침 예산(1만 번)을 다 쓰는 호출 지점 하나가 예산 × 파일 크기만큼 일했다.
        // 시간 대신 해석 한 번의 스레드 할당량을 채움 없는 파일과 비교한다. 채움과 무관해야 하고, 예전에는 수백 배였다.
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "thread allocation accounting is unavailable on this JVM")
        val expression = "\"https://api.example.com/x\$C0\""
        fun source(classPadding: Int, closedLocals: Int): String = buildString {
            append("package dev.example.net\n\nimport java.net.*\n\n")
            // 단계마다 다음 상수를 16번 잇는다 — 16^5번 펼쳐야 해서 예산을 다 쓴다.
            (0 until 5).forEach { level -> append("const val C$level = \"" + "\$C${level + 1}".repeat(16) + "\"\n") }
            append("const val C5 = \"\"\n\nclass Calls {\n")
            repeat(classPadding) { append("    // padding line $it keeps the call far from the start of the file\n") }
            append("    fun call() {\n")
            // 닫힌 블록의 같은 이름 지역 선언은 사용 위치에서 보이지 않지만, 이름별 정규식은 펼침마다 이 선언들을 다시 찾았다.
            repeat(closedLocals) { append("        run { val C${it % 6} = \"local-$it\"; println(C${it % 6}) }\n") }
            append("        URL($expression).openConnection()\n    }\n}\n")
        }
        fun allocated(text: String): Long {
            val file = RouteSourceFile("src/main/kotlin/dev/example/net/Calls.kt", isJava = false, isTest = false, source = text)
            val offset = file.masked.indexOf("URL(")
            val before = threads!!.currentThreadAllocatedBytes
            val parts = RoutePathResolver(file).parts(expression, offset)
            val bytes = threads.currentThreadAllocatedBytes - before
            // 예산을 다 쓴 식은 식 전체가 값 조각 하나다(`retrofit constant fan-out beyond the per-call budget stays dynamic`).
            assertEquals(listOf<UrlPart>(UrlPart.Value(expression)), parts)
            return bytes
        }
        // 첫 실행의 클래스 적재 할당을 빼려고 한 번 먼저 푼다.
        allocated(source(0, 0))
        val plain = allocated(source(0, 0))
        val padded = allocated(source(BUDGET_PADDING_LINES, 0))
        val locals = allocated(source(0, BUDGET_CLOSED_LOCALS))
        assertTrue(padded < plain * 3, "$BUDGET_PADDING_LINES padding lines grew allocation from $plain to $padded bytes")
        assertTrue(locals < plain * 3, "$BUDGET_CLOSED_LOCALS closed local blocks grew allocation from $plain to $locals bytes")
    }

    @Test
    fun `path expressions that read more than the scan budget stay unresolved`() {
        // 리뷰 지적 재현: 펼침 예산은 횟수만 셌다. 펼침 한 번은 식 길이만큼 읽으므로, 큰 식을 여러 번 따라가는 입력은 횟수 상한 안에서도
        // 횟수 × 식 길이만큼 일했다(380KB 식을 5천 번 따라가는 호출 하나가 4.8초). 여기서는 2천 자 식을 천 번 따라간다.
        // 펼침은 2천 번이라 횟수 상한 안이지만 읽는 양은 2백만 자로 읽기 상한(1MiB)을 넘는다.
        val big = "listOf(" + (0 until 280).joinToString(", ") { "arg$it" } + ")"
        write(
            "src/main/kotlin/dev/example/net/Wide.kt",
            "package dev.example.net\n\nimport java.net.*\n\nconst val WIDE = \"" + "\$BIG".repeat(1_000) + "\"\nval BIG = $big\n" +
                "const val NARROW = \"" + "\$SMALL".repeat(1_000) + "\"\nval SMALL = listOf(arg).first()\n\n" +
                "class Wide {\n    fun wide() = URL(\"https://api.example.com/x\$WIDE\").readText()\n" +
                "    fun narrow() = URL(\"https://api.example.com/y\$NARROW\").readText()\n}",
        )
        assertTrue(big.length in 2_000..2_200, big.length.toString())
        val facts = scan().facts.associateBy { it.symbol?.qualifiedName }
        val wide = facts.getValue("dev.example.net.Wide.wide")
        assertTrue(wide.dynamic)
        // 상한을 넘긴 식은 앞부분만 읽은 조각을 남기지 않고 식 전체를 모르는 값으로 둔다.
        assertNull(wide.channelPrefix)
        // 대조: 같은 모양이라도 읽는 양이 상한 안이면 증명된 접두사를 남긴다.
        val narrow = facts.getValue("dev.example.net.Wide.narrow")
        assertTrue(narrow.dynamic)
        assertEquals("/y", narrow.channelPrefix)
    }

    @Test
    fun `local declarations match the per-name regex lookup on random kotlin text`() {
        // 지역 선언 색인의 보존 대조: 함수마다 한 번 만든 색인이 예전처럼 이름마다 정규식으로 사용 위치 앞까지 훑은 결과와 같아야 한다.
        // 한 줄의 같은 이름 선언(예전 정규식의 겹치지 않는 찾기), 닫힌 블록, 중첩 함수, 식 몸체, 함수 머리 안 위치를 섞는다.
        val random = Random(20_261_020)
        val tokens = listOf("fun f(x: String) {", "fun g() = run {", "fun h(y: Int)", " = ", "{", "}", "\n", "; ", " ", "val x = ", "var x = ",
            "val y: Int = ", "var y: Map<K, V> = ", "val z = ", "val x: T; ", "x", "y", "z", "==", "\"s\"", "(", ")", "if (x == y) ", "1", ",")
        val names = listOf("x", "y", "z", "f")
        var found = 0
        var hidden = 0
        repeat(300) { sample ->
            val source = buildString { repeat(random.nextInt(1, 60)) { append(tokens[random.nextInt(tokens.size)]) } }
            val file = RouteSourceFile("src/main/kotlin/demo/L.kt", isJava = false, isTest = false, source = source)
            for (offset in 0..file.masked.length) {
                // 사용 위치가 `==`의 둘째 `=`일 때만 다르다. 예전 정규식은 잘라 낸 끝에서 `(?!=)`가 통과해 `x =`를 선언으로 읽었다.
                // 해석기가 묻는 위치는 식의 시작이고, 식은 `=`로 시작하지 않는다.
                if (file.masked.getOrNull(offset) == '=') continue
                for (name in names) {
                    val expected = regexLocalDeclaration(file, name, offset)
                    assertEquals(expected, file.localDeclaration(name, offset), "sample $sample offset $offset name $name")
                    if (expected != null) found++ else if (file.enclosingFunction(offset) != null && "val $name" in file.masked.substring(0, offset)) hidden++
                }
            }
        }
        // 대조가 비지 않았는지 본다: 선언을 찾은 경우와, 앞에 같은 이름 선언이 있어도 범위·위치 때문에 못 찾는 경우가 모두 많아야 한다.
        assertTrue(found > 5_000 && hidden > 5_000, "found $found, hidden $hidden")
    }

    /** 색인 전 [RouteSourceFile.localDeclaration]을 그대로 옮긴 대조용 구현이다. */
    private fun regexLocalDeclaration(file: RouteSourceFile, name: String, offset: Int): RouteLocal? {
        val function = file.enclosingFunction(offset) ?: return null
        val searchStart = function.bodyStart.coerceAtLeast(function.start)
        val prefix = file.masked.substring(0, offset.coerceAtMost(file.masked.length))
        if (searchStart > prefix.length) return null
        val pattern = Regex("\\b(val|var)\\s+${Regex.escape(name)}\\b\\s*(?::[^=\\n]+)?=(?!=)")
        val match = pattern.findAll(prefix, searchStart).lastOrNull { file.inScope(it.range.first, offset) } ?: return null
        val initializerStart = match.range.last + 1
        return RouteLocal(match.groupValues[1] == "val", file.code.substring(initializerStart, file.statementEnd(initializerStart)).trim())
    }

    private companion object {
        /** 할당량 비교의 작은 쪽 상수 수다. 예전 구현은 이 크기에서도 상수마다 약 80KB를 복사했다. */
        const val STATEMENT_CONSTANTS = 2_000

        /** 예산 소진 호출 앞의 채움 줄 수다(약 140KB). 예전 구현은 펼침마다 이만큼을 두 번 복사했다. */
        const val BUDGET_PADDING_LINES = 2_000

        /** 예산 소진 호출 앞의 닫힌 블록 수다. 예전 구현은 펼침마다 같은 이름 선언 약 100개를 다시 찾았다. */
        const val BUDGET_CLOSED_LOCALS = 600
    }
}
