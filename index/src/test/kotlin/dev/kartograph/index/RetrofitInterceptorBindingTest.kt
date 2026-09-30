package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * URL을 바꾸는 OkHttp 인터셉터가 어느 client → Retrofit 인스턴스에 붙는지 따라가, 그 인스턴스의 base만 버리는지 합성
 * Kotlin/Java 표본으로 고정한다. 결합을 증명하지 못하는 모양은 #123처럼 모든 base를 버려야 한다.
 */
class RetrofitInterceptorBindingTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, content: String) {
        val path = project.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun scan(): BridgeFactsDocument = RouteCallScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z")

    private fun BridgeFactsDocument.of(name: String): List<BridgeFact> = facts.filter { it.symbol?.qualifiedName?.endsWith(".$name") == true }

    private fun BridgeFact.summary(): String = "${route?.pathAnchor} ${route?.authority ?: "-"} ${channel ?: "dynamic"} ${route?.baseRef ?: "-"}"

    private fun BridgeFactsDocument.rewriteLimitations(): List<String> = limitations.filter { it.startsWith("url-rewrite-interceptors:") }

    private fun BridgeFactsDocument.globalFallback(): Boolean = rewriteLimitations().any { "are not bound to the OkHttp clients they affect" in it }

    /** 두 서비스 인터페이스다. [rewriter]면 host만 바꾸는 인터셉터 class `HostRewriteInterceptor`도 쓴다. */
    private fun services(rewriter: Boolean = true) {
        write(
            "src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.*

            interface EdgeApi {
                @GET("items/{id}") fun item(@Path("id") id: String): Any
                @POST("/orders") fun order(): Any
                @GET("https://cdn.example.com/assets/{name}") fun asset(@Path("name") name: String): Any
            }

            interface PlainApi { @GET("status") fun status(): Any }
            """,
        )
        if (rewriter) write(
            "src/main/kotlin/dev/example/net/HostRewriteInterceptor.kt",
            """
            package dev.example.net

            import okhttp3.Interceptor
            import okhttp3.Response

            class HostRewriteInterceptor(private val host: String) : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val request = chain.request()
                    val url = request.url.newBuilder().host(host).build()
                    return chain.proceed(request.newBuilder().url(url).build())
                }
            }
            """,
        )
    }

    @Test
    fun `a class interceptor drops only the base of the retrofit instance whose client carries it`() {
        services()
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.Interceptor
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            class HeaderInterceptor : Interceptor {
                override fun intercept(chain: Interceptor.Chain) = chain.proceed(chain.request().newBuilder().header("X-App", "1").build())
            }

            object Clients {
                private val edgeClient = OkHttpClient.Builder()
                    .addInterceptor(HeaderInterceptor())
                    .addInterceptor(HostRewriteInterceptor("edge.example.net"))
                    .build()

                private val plainClient: OkHttpClient = OkHttpClient.Builder().addInterceptor(HeaderInterceptor()).build()

                val edge: EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/v1/").client(edgeClient).build().create(EdgeApi::class.java)

                val plain: PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/v1/").client(plainClient).build().create(PlainApi::class.java)
            }
            """,
        )
        val document = scan()
        val edge = "kt:dev.example.net.Clients.edge"
        assertEquals(listOf("base - /items/{} $edge"), document.of("item").map { it.summary() })
        assertEquals(listOf("root - /orders $edge"), document.of("order").map { it.summary() })
        // 재작성 client는 전체 URL 어노테이션의 host도 바꿀 수 있다.
        assertEquals(listOf("root - /assets/{} -"), document.of("asset").map { it.summary() })
        assertEquals(listOf("root api.example.com /v1/status kt:dev.example.net.Clients.plain"), document.of("status").map { it.summary() })
        val limitation = document.rewriteLimitations().single()
        assertTrue(limitation.startsWith("url-rewrite-interceptors: Retrofit instance $edge sends requests through an OkHttp client with 1 request rewrite(s)"), limitation)
        assertTrue(limitation.endsWith("applied to 1 service interface(s)"), limitation)
        // host만 바꾸는 인터셉터라 숨은 요청의 경로·method 상한을 증명한다.
        val scope = document.limitationScopes.single()
        assertEquals(limitation, scope.limitation)
        assertEquals(listOf("/assets/{}", "/orders", "/v1/items/{}"), scope.templates)
        assertEquals(listOf("GET", "POST"), scope.methods)
        assertTrue(RouteLimitationScopes.applies(scope, "/v1/items/{}", "GET", "root", declaration = true))
        assertFalse(RouteLimitationScopes.applies(scope, "/v1/status", "GET", "root", declaration = true))
        assertTrue(document.limitations.any { it.startsWith("unresolved-base-url: 1 Retrofit service interface(s)") }, document.limitations.toString())
    }

    @Test
    fun `network interceptors, lambdas and objects that change the path drop their instance without a scope`() {
        services(rewriter = false)
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.Interceptor
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            object PrefixRewrite : Interceptor {
                override fun intercept(chain: Interceptor.Chain) =
                    chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().encodedPath("/edge").build()).build())
            }

            val pathRewrite = Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().url("https://other.example.com/x").build())
            }

            fun edgeApi(): EdgeApi = Retrofit.Builder()
                .baseUrl("https://api.example.com/")
                .client(OkHttpClient.Builder().addNetworkInterceptor(pathRewrite).addInterceptor(PrefixRewrite).build())
                .build()
                .create(EdgeApi::class.java)

            fun plainApi(): PlainApi = Retrofit.Builder()
                .baseUrl("https://api.example.com/")
                .client(OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request()) }.build())
                .build()
                .create(PlainApi::class.java)
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items/{} kt:dev.example.net.edgeApi"), document.of("item").map { it.summary() })
        assertEquals(listOf("root api.example.com /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
        val limitation = document.rewriteLimitations().single()
        assertTrue("kt:dev.example.net.edgeApi" in limitation && "with 2 request rewrite(s)" in limitation, limitation)
        assertTrue(document.limitationScopes.isEmpty(), document.limitationScopes.toString())
    }

    @Test
    fun `hilt providers with qualifiers bind the injected interceptor to one client`() {
        services()
        write(
            "src/main/kotlin/dev/example/di/NetworkModule.kt",
            """
            package dev.example.di

            import dagger.Module
            import dagger.Provides
            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import dev.example.net.HostRewriteInterceptor
            import javax.inject.Named
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            @Module
            object NetworkModule {
                @Provides fun rewriter(): HostRewriteInterceptor = HostRewriteInterceptor("edge.example.net")

                @Provides @Named("edge")
                fun edgeClient(rewriter: HostRewriteInterceptor): OkHttpClient = OkHttpClient.Builder().addInterceptor(rewriter).build()

                @Provides @Named("plain")
                fun plainClient(): OkHttpClient = OkHttpClient()

                @Provides @Named("edge")
                fun edgeRetrofit(@Named("edge") client: OkHttpClient): Retrofit =
                    Retrofit.Builder().baseUrl("https://api.example.com/").client(client).build()

                @Provides @Named("plain")
                fun plainRetrofit(@Named("plain") client: OkHttpClient): Retrofit =
                    Retrofit.Builder().baseUrl("https://api.example.com/").client(client).build()

                @Provides fun edgeApi(@Named("edge") retrofit: Retrofit): EdgeApi = retrofit.create(EdgeApi::class.java)

                @Provides fun plainApi(@Named("plain") retrofit: Retrofit): PlainApi = retrofit.create(PlainApi::class.java)
            }
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items/{} kt:dev.example.di.NetworkModule.edgeRetrofit"), document.of("item").map { it.summary() })
        assertEquals(listOf("root api.example.com /status kt:dev.example.di.NetworkModule.plainRetrofit"), document.of("status").map { it.summary() })
        assertFalse(document.globalFallback(), document.limitations.toString())
    }

    @Test
    fun `koin definitions and derived clients keep the shared client's retrofit base`() {
        services()
        write(
            "src/main/kotlin/dev/example/di/Koin.kt",
            """
            package dev.example.di

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import dev.example.net.HostRewriteInterceptor
            import okhttp3.OkHttpClient
            import org.koin.core.qualifier.named
            import org.koin.dsl.module
            import retrofit2.Retrofit

            val shared: OkHttpClient = OkHttpClient.Builder().build()

            val network = module {
                single<OkHttpClient>(named("edge")) { shared.newBuilder().addInterceptor(HostRewriteInterceptor("edge.example.net")).build() }
                single(named("edge-retrofit")) { Retrofit.Builder().baseUrl("https://api.example.com/").client(get(named("edge"))).build() }
                single { get<Retrofit>(named("edge-retrofit")).create(EdgeApi::class.java) }
            }

            fun plainApi(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/").client(shared).build().create(PlainApi::class.java)
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items/{} kt:dev.example.di.network#named:edge-retrofit"), document.of("item").map { it.summary() })
        assertEquals(listOf("root api.example.com /status kt:dev.example.di.plainApi"), document.of("status").map { it.summary() })
        assertFalse(document.globalFallback(), document.limitations.toString())
    }

    @Test
    fun `java local builders and anonymous interceptors bind to the client that builds them`() {
        services(rewriter = false)
        write(
            "src/main/java/dev/example/net/JavaClients.java",
            """
            package dev.example.net;

            import dev.example.api.EdgeApi;
            import dev.example.api.PlainApi;
            import okhttp3.Interceptor;
            import okhttp3.OkHttpClient;
            import okhttp3.Response;
            import retrofit2.Retrofit;

            public final class JavaClients {
                public static EdgeApi edge() {
                    OkHttpClient.Builder builder = new OkHttpClient.Builder();
                    builder.addInterceptor(new Interceptor() {
                        @Override
                        public Response intercept(Chain chain) throws java.io.IOException {
                            return chain.proceed(chain.request().newBuilder().url("https://other.example.com/").build());
                        }
                    });
                    Retrofit retrofit = new Retrofit.Builder().baseUrl("https://api.example.com/").client(builder.build()).build();
                    return retrofit.create(EdgeApi.class);
                }

                public static PlainApi plain() {
                    OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(chain.request())).build();
                    return new Retrofit.Builder().baseUrl("https://api.example.com/").client(client).build().create(PlainApi.class);
                }
            }
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items/{} kt:dev.example.net.JavaClients.edge"), document.of("item").map { it.summary() })
        assertEquals(listOf("root api.example.com /status kt:dev.example.net.JavaClients.plain"), document.of("status").map { it.summary() })
        assertFalse(document.globalFallback(), document.limitations.toString())
    }

    @Test
    fun `authenticators, event listeners and custom call factories count as rewrites unless proven otherwise`() {
        services(rewriter = false)
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.Authenticator
            import okhttp3.Call
            import okhttp3.EventListener
            import okhttp3.OkHttpClient
            import okhttp3.Request
            import okhttp3.Response
            import okhttp3.Route
            import retrofit2.Retrofit

            class TokenAuthenticator : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request =
                    response.request.newBuilder().header("Authorization", "Bearer t").build()
            }

            object Timing : EventListener() {
                override fun callStart(call: Call) = Unit
            }

            class Apis(private val injected: Authenticator, private val shared: OkHttpClient) {
                fun plain(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                    .client(OkHttpClient.Builder().authenticator(TokenAuthenticator()).eventListener(Timing).proxyAuthenticator(Authenticator.NONE).build())
                    .build().create(PlainApi::class.java)

                fun edge(): EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                    .client(OkHttpClient.Builder().authenticator(injected).build())
                    .build().create(EdgeApi::class.java)
            }

            fun factoryApi(): PlainApi = Retrofit.Builder().baseUrl("https://factory.example.com/")
                .callFactory(Call.Factory { request -> OkHttpClient().newCall(request) })
                .build().create(PlainApi::class.java)
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items/{} kt:dev.example.net.Apis.edge"), document.of("item").map { it.summary() })
        assertEquals(
            listOf("base - /status kt:dev.example.net.factoryApi", "root api.example.com /status kt:dev.example.net.Apis.plain"),
            document.of("status").map { it.summary() }.sorted(),
        )
        assertEquals(2, document.rewriteLimitations().size, document.limitations.toString())
        assertFalse(document.globalFallback(), document.limitations.toString())
        // 증명하지 못한 부착은 경로 보존도 증명하지 못한다.
        assertTrue(document.limitationScopes.isEmpty(), document.limitationScopes.toString())
    }

    @Test
    fun `unresolvable bindings fall back to dropping every base`() {
        services()
        val clients = "src/main/kotlin/dev/example/net/Clients.kt"
        val plain = """
            fun plainApi(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/").build().create(PlainApi::class.java)
        """
        // 목록으로 넘긴 인터셉터
        write(
            clients,
            """
            package dev.example.net

            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            val client: OkHttpClient = OkHttpClient.Builder().apply {
                listOf(HostRewriteInterceptor("edge.example.net")).forEach { addInterceptor(it) }
            }.build()
            $plain
            """,
        )
        scan().let { document ->
            assertTrue(document.globalFallback(), document.limitations.toString())
            assertEquals(listOf("base - /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
            assertTrue(document.limitationScopes.isEmpty())
        }
        // 다른 함수에 넘긴 빌더에 붙인 인터셉터
        write(
            clients,
            """
            package dev.example.net

            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            fun configure(builder: OkHttpClient.Builder) { builder.addInterceptor(HostRewriteInterceptor("edge.example.net")) }
            $plain
            """,
        )
        assertTrue(scan().globalFallback())
        // 원천을 모르는 client로 base를 푼 인스턴스
        write(
            clients,
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            val edgeClient = OkHttpClient.Builder().addInterceptor(HostRewriteInterceptor("edge.example.net")).build()

            fun edgeApi(): EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/").client(edgeClient).build().create(EdgeApi::class.java)

            fun plainApi(client: OkHttpClient): PlainApi =
                Retrofit.Builder().baseUrl("https://api.example.com/").client(client.newBuilder().build()).build().create(PlainApi::class.java)
            """,
        )
        scan().let { document ->
            assertTrue(document.rewriteLimitations().single().contains("OkHttp client whose source is not resolved"), document.limitations.toString())
            assertEquals(listOf("base - /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
        }
        // 어디에도 붙지 않은 재작성 인터셉터
        write(
            clients,
            """
            package dev.example.net

            import dev.example.api.PlainApi
            import retrofit2.Retrofit
            $plain
            """,
        )
        scan().let { document ->
            assertTrue(document.rewriteLimitations().single().contains("not attached to any OkHttpClient.Builder"), document.limitations.toString())
            assertEquals(listOf("base - /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
        }
    }

    @Test
    fun `interceptor flows through supertypes, subclasses, wrappers, extensions and interceptor lists fall back`() {
        services(rewriter = false)
        write(
            "src/main/kotlin/dev/example/net/HostRewriteInterceptor.kt",
            """
            package dev.example.net

            import okhttp3.Interceptor

            open class HostRewriteInterceptor(private val host: String) : Interceptor {
                override fun intercept(chain: Interceptor.Chain) = chain.request().let { request ->
                    chain.proceed(request.newBuilder().url(request.url.newBuilder().host(host).build()).build())
                }
            }

            class Wrapper(private val inner: Interceptor) : Interceptor {
                override fun intercept(chain: Interceptor.Chain) = inner.intercept(chain)
            }
            """,
        )
        val flows = mapOf(
            "provides-supertype" to "@dagger.Provides fun rewriter(): Interceptor = HostRewriteInterceptor(\"edge\")\n" +
                "@dagger.Provides fun client(i: Interceptor): OkHttpClient = OkHttpClient.Builder().addInterceptor(i).build()",
            "subclass-in-list" to "class EdgeRewrite : HostRewriteInterceptor(\"edge\")\nval all = listOf(EdgeRewrite())\n" +
                "val used = OkHttpClient.Builder().addInterceptor(HostRewriteInterceptor(\"x\")).build()",
            "wrapper" to "val wrapped = OkHttpClient.Builder().addInterceptor(Wrapper(HostRewriteInterceptor(\"edge\"))).build()",
            "extension" to "fun OkHttpClient.Builder.edge() = addInterceptor(HostRewriteInterceptor(\"edge\"))",
            "interceptor-list" to "val listed = OkHttpClient.Builder().apply { interceptors().add(HostRewriteInterceptor(\"edge\")) }.build()",
            "method-reference" to "fun make() = HostRewriteInterceptor(\"edge\")\nval factory = ::make",
        )
        flows.forEach { (name, code) ->
            write(
                "src/main/kotlin/dev/example/net/Clients.kt",
                "package dev.example.net\n\nimport dev.example.api.PlainApi\nimport okhttp3.Interceptor\nimport okhttp3.OkHttpClient\n" +
                    "import retrofit2.Retrofit\n\n$code\n\n" +
                    "fun plainApi(): PlainApi = Retrofit.Builder().baseUrl(\"https://api.example.com/\").build().create(PlainApi::class.java)\n",
            )
            val document = scan()
            assertTrue(document.globalFallback(), "$name: ${document.limitations}")
            assertEquals(listOf("base - /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() }, name)
        }
    }

    @Test
    fun `clients set inside retrofit scope blocks are all considered`() {
        services()
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            val edgeClient = OkHttpClient.Builder().addInterceptor(HostRewriteInterceptor("edge.example.net")).build()
            val plainClient = OkHttpClient()

            fun edgeApi(debug: Boolean): EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                .also { if (debug) it.client(edgeClient) else it.client(plainClient) }
                .build().create(EdgeApi::class.java)

            fun plainApi(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/").apply { client(plainClient) }.build().create(PlainApi::class.java)
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items/{} kt:dev.example.net.edgeApi"), document.of("item").map { it.summary() })
        assertEquals(listOf("root api.example.com /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
    }

    @Test
    fun `self registration, foreign proceeded requests and unresolved clients of absolute hosts stay conservative`() {
        services(rewriter = false)
        val rewriter = "src/main/kotlin/dev/example/net/HostRewriteInterceptor.kt"
        val clients = "src/main/kotlin/dev/example/net/Clients.kt"
        write(
            clients,
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            fun edgeApi(): EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                .client(OkHttpClient.Builder().addInterceptor(HostRewriteInterceptor("edge.example.net")).build()).build().create(EdgeApi::class.java)

            fun plainApi(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/").build().create(PlainApi::class.java)
            """,
        )
        // 자기 자신을 등록하는 인터셉터는 생성 지점 밖으로 흐른다.
        write(
            rewriter,
            """
            package dev.example.net

            import okhttp3.Interceptor

            object Registry { val all = mutableListOf<Interceptor>() }

            class HostRewriteInterceptor(private val host: String) : Interceptor {
                init { Registry.all += this }
                override fun intercept(chain: Interceptor.Chain) =
                    chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().host(host).build()).build())
            }
            """,
        )
        assertTrue(scan().globalFallback())
        // 다른 곳에서 온 요청을 넘기면 경로 보존을 증명하지 못한다 — base는 버리지만 스코프는 없다.
        write(
            rewriter,
            """
            package dev.example.net

            import okhttp3.Interceptor
            import okhttp3.Request

            class HostRewriteInterceptor(private val host: String) : Interceptor {
                var pending: Request? = null
                override fun intercept(chain: Interceptor.Chain) =
                    chain.proceed(pending ?: chain.request().newBuilder().url(chain.request().url.newBuilder().host(host).build()).build())
            }
            """,
        )
        scan().let { document ->
            assertFalse(document.globalFallback(), document.limitations.toString())
            assertEquals(listOf("base - /items/{} kt:dev.example.net.edgeApi"), document.of("item").map { it.summary() })
            assertTrue(document.limitationScopes.isEmpty(), document.limitationScopes.toString())
        }
        // base를 모르는 인스턴스의 client를 모르면 전체 URL 어노테이션 host도 믿지 않는다.
        write(
            "src/main/kotlin/dev/example/api/Cdn.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface CdnApi { @GET("https://cdn.example.com/logo.png") fun logo(): Any }
            """,
        )
        write(
            "src/main/kotlin/dev/example/net/Cdn.kt",
            """
            package dev.example.net

            import dev.example.api.CdnApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            fun cdnApi(base: String, client: OkHttpClient): CdnApi = Retrofit.Builder().baseUrl(base).client(client).build().create(CdnApi::class.java)
            """,
        )
        scan().let { document ->
            assertFalse(document.globalFallback(), document.limitations.toString())
            assertEquals(listOf("root - /logo.png -"), document.of("logo").map { it.summary() })
        }
    }

    @Test
    fun `review reproductions - foreign proceeded requests, rebuilt requests, shadowed let parameters and delegating authenticators`() {
        services(rewriter = false)
        val rewriter = "src/main/kotlin/dev/example/net/HostRewriteInterceptor.kt"
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            fun edgeApi(): EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                .client(OkHttpClient.Builder().addInterceptor(HostRewriteInterceptor("edge.example.net")).build()).build().create(EdgeApi::class.java)

            fun plainApi(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/").build().create(PlainApi::class.java)
            """,
        )
        // D1: 재작성 호출 없이 다른 곳에서 온 요청을 넘긴다.
        write(
            rewriter,
            """
            package dev.example.net

            import okhttp3.Interceptor
            import okhttp3.Request

            object Vault { fun rebuild(request: Request): Request = request }

            class HostRewriteInterceptor(private val host: String) : Interceptor {
                override fun intercept(chain: Interceptor.Chain) = chain.proceed(Vault.rebuild(chain.request()))
            }
            """,
        )
        scan().let { document ->
            assertEquals(listOf("base - /items/{} kt:dev.example.net.edgeApi"), document.of("item").map { it.summary() })
            assertEquals(listOf("root api.example.com /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
            assertTrue(document.limitationScopes.isEmpty(), document.limitationScopes.toString())
        }
        // D2: 헬퍼가 다시 만든 요청의 사본에서 host만 바꿔도 경로 보존은 증명하지 못한다.
        write(
            rewriter,
            """
            package dev.example.net

            import okhttp3.Interceptor
            import okhttp3.Request

            object Vault { fun rebuild(request: Request): Request = request }

            class HostRewriteInterceptor(private val host: String) : Interceptor {
                override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                    val request = Vault.rebuild(chain.request())
                    return chain.proceed(request.newBuilder().url(request.url.newBuilder().host(host).build()).build())
                }
            }
            """,
        )
        scan().let { document ->
            assertEquals(listOf("base - /items/{} kt:dev.example.net.edgeApi"), document.of("item").map { it.summary() })
            assertTrue(document.limitationScopes.isEmpty(), document.limitationScopes.toString())
        }
        // D3: 안쪽 람다가 같은 이름을 다시 묶으면 원래 요청이 아니다.
        write(
            rewriter,
            """
            package dev.example.net

            import okhttp3.Interceptor

            class HostRewriteInterceptor(private val host: String) : Interceptor {
                val pending = mutableListOf<okhttp3.Request>()
                override fun intercept(chain: Interceptor.Chain) = chain.request().let { request ->
                    pending.first().let { request ->
                        chain.proceed(chain.request().newBuilder().url(request.url.newBuilder().host(host).build()).build())
                    }
                }
            }
            """,
        )
        assertTrue(scan().limitationScopes.isEmpty())
        // D4: 헬퍼가 만든 요청을 돌려주는 Authenticator는 재작성으로 본다.
        write(rewriter, "package dev.example.net\n")
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.EdgeApi
            import dev.example.api.PlainApi
            import okhttp3.Authenticator
            import okhttp3.OkHttpClient
            import okhttp3.Request
            import okhttp3.Response
            import okhttp3.Route
            import retrofit2.Retrofit

            object AuthVault { fun retry(response: Response): Request? = null }

            class DelegatingAuthenticator : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? = AuthVault.retry(response)
            }

            class HeaderAuthenticator : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.request.header("Authorization") != null) return null
                    return response.request.newBuilder().header("Authorization", "t").build()
                }
            }

            fun edgeApi(): EdgeApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                .client(OkHttpClient.Builder().authenticator(DelegatingAuthenticator()).build()).build().create(EdgeApi::class.java)

            fun plainApi(): PlainApi = Retrofit.Builder().baseUrl("https://api.example.com/")
                .client(OkHttpClient.Builder().authenticator(HeaderAuthenticator()).authenticator { _, response -> response.request }.build())
                .build().create(PlainApi::class.java)
            """,
        )
        scan().let { document ->
            assertEquals(listOf("base - /items/{} kt:dev.example.net.edgeApi"), document.of("item").map { it.summary() })
            assertEquals(listOf("root api.example.com /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
            assertFalse(document.globalFallback(), document.limitations.toString())
        }
    }

    @Test
    fun `without rewrites unresolved clients keep their bases`() {
        services(rewriter = false)
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.PlainApi
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            fun plainApi(client: OkHttpClient): PlainApi =
                Retrofit.Builder().baseUrl("https://api.example.com/").client(client).build().create(PlainApi::class.java)
            """,
        )
        val document = scan()
        assertEquals(listOf("root api.example.com /status kt:dev.example.net.plainApi"), document.of("status").map { it.summary() })
        assertTrue(document.rewriteLimitations().isEmpty(), document.limitations.toString())
    }
}
