package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Retrofit `baseUrl` 결합을 합성 Kotlin/Java 표본으로 고정한다. 공개 저장소라 실제 앱 코드는 쓰지 않는다.
 *
 * 각 표본은 서비스 인터페이스를 만드는 흔한 모양(같은 식의 빌더, 속성·lazy, Java 싱글턴, Hilt `@Provides`, `@Inject` 생성자,
 * Koin, 생성 함수, `BuildConfig`)이다. 리터럴 base면 authority와 base 경로를 결합한 root 템플릿, 아니면 base 앵커와 인스턴스
 * 선언의 `baseRef`를 기대한다.
 */
class RetrofitBaseUrlTest {
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
        "${route?.pathAnchor} ${route?.authority ?: "-"} ${channel ?: "dynamic:${channelPrefix ?: "-"}"} ${route?.baseRef ?: "-"}"

    private fun service(pkg: String, name: String, vararg methods: String): String = """
        package $pkg

        import retrofit2.http.*

        interface $name {
        ${methods.joinToString("\n") { "    $it" }}
        }
    """

    @Test
    fun `an inline builder resolves relative, rooted, climbing and absolute paths against a literal base`() {
        write(
            "src/main/kotlin/dev/example/api/UsersApi.kt",
            service(
                "dev.example.api", "UsersApi",
                "@GET(\"users/{id}\") fun user(@Path(\"id\") id: String): Any",
                "@GET(\"/health\") fun health(): Any",
                "@GET(\"../v2/status\") fun status(): Any",
                "@GET(\"https://uploads.example.com/blobs/{id}\") fun blob(@Path(\"id\") id: String): Any",
                "@GET fun raw(@Url url: String): Any",
                "@GET(\"docs/{path}\") fun doc(@Path(value = \"path\", encoded = true) path: String): Any",
            ),
        )
        write(
            "src/main/kotlin/dev/example/api/Factory.kt",
            """
            package dev.example.api

            import retrofit2.Retrofit

            private const val HOST = "https://API.Example.com"
            const val BASE_URL = "${'$'}HOST/v1/"

            fun createUsersApi(): UsersApi =
                Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .build()
                    .create(UsersApi::class.java)
            """,
        )
        val document = scan()
        val ref = "kt:dev.example.api.createUsersApi"
        assertEquals(listOf("root api.example.com /v1/users/{} $ref"), document.of("user").map { it.summary() })
        assertEquals(listOf("root api.example.com /health $ref"), document.of("health").map { it.summary() })
        assertEquals(listOf("root api.example.com /v2/status $ref"), document.of("status").map { it.summary() })
        // 전체 URL은 base를 쓰지 않는다 — baseRef도 싣지 않는다.
        assertEquals(listOf("root uploads.example.com /blobs/{} -"), document.of("blob").map { it.summary() })
        assertEquals(listOf("base - dynamic:- -"), document.of("raw").map { it.summary() })
        assertEquals(listOf("root api.example.com dynamic:/v1/docs/ $ref"), document.of("doc").map { it.summary() })
        assertTrue(document.limitations.none { it.startsWith("unresolved-base-url:") }, document.limitations.toString())
    }

    @Test
    fun `object properties, lazy delegates and apply blocks in another file supply the base`() {
        write("src/main/kotlin/dev/example/api/Apis.kt", service("dev.example.api", "OrdersApi", "@POST(\"orders\") fun place(): Any"))
        write("src/main/kotlin/dev/example/api/Other.kt", service("dev.example.api", "CartApi", "@GET(\"cart\") fun cart(): Any"))
        write(
            "src/main/kotlin/dev/example/net/ApiClient.kt",
            """
            package dev.example.net

            import dev.example.config.Endpoints
            import okhttp3.HttpUrl.Companion.toHttpUrl
            import retrofit2.Retrofit

            object ApiClient {
                val retrofit: Retrofit by lazy {
                    val client = Any()
                    Retrofit.Builder().baseUrl(Endpoints.SHOP.toHttpUrl()).build()
                }
                val cartRetrofit = Retrofit.Builder().apply {
                    baseUrl(okhttp3.HttpUrl.get("https://cart.example.com:8443/c/"))
                }.build()
            }
            """,
        )
        write("src/main/kotlin/dev/example/config/Endpoints.kt", "package dev.example.config\nobject Endpoints { const val SHOP = \"https://shop.example.com/api/\" }")
        write(
            "src/main/kotlin/dev/example/ui/Screens.kt",
            """
            package dev.example.ui

            import dev.example.api.CartApi
            import dev.example.api.OrdersApi
            import dev.example.net.ApiClient

            class Screens {
                private val orders = ApiClient.retrofit.create(OrdersApi::class.java)
                private val cart: CartApi = ApiClient.cartRetrofit.create(CartApi::class.java)
            }
            """,
        )
        val document = scan()
        assertEquals(listOf("root shop.example.com /api/orders kt:dev.example.net.ApiClient.retrofit"), document.of("place").map { it.summary() })
        assertEquals(listOf("root cart.example.com:8443 /c/cart kt:dev.example.net.ApiClient.cartRetrofit"), document.of("cart").map { it.summary() })
    }

    @Test
    fun `a java singleton getter follows the null-checked field assignment`() {
        write(
            "src/main/java/dev/example/legacy/LegacyApi.java",
            """
            package dev.example.legacy;
            import retrofit2.Call;
            import retrofit2.http.GET;
            public interface LegacyApi {
                @GET("legacy/items")
                Call<Object> items();
            }
            """,
        )
        write(
            "src/main/java/dev/example/legacy/ApiClient.java",
            """
            package dev.example.legacy;
            import retrofit2.Retrofit;
            public final class ApiClient {
                private static final String BASE_URL = "https://legacy.example.com/";
                private static Retrofit retrofit = null;
                public static Retrofit getClient() {
                    if (retrofit == null) {
                        retrofit = new Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .build();
                    }
                    return retrofit;
                }
                public static LegacyApi legacy() {
                    Retrofit local = getClient();
                    return local.create(LegacyApi.class);
                }
            }
            """,
        )
        assertEquals(listOf("root legacy.example.com /legacy/items kt:dev.example.legacy.ApiClient.retrofit"), scan().of("items").map { it.summary() })
    }

    @Test
    fun `hilt providers match qualifiers and build config variants become one fact per base`() {
        write(
            "app/build.gradle.kts",
            """
            android {
                namespace = "dev.example.app"
                buildTypes {
                    debug { buildConfigField("String", "API_URL", "\"https://staging.example.com/\"") }
                    release { buildConfigField("String", "API_URL", "\"https://api.example.com/\"") }
                    // buildConfigField("String", "API_URL", "\"https://commented.example.com/\"")
                }
            }
            """,
        )
        write(
            "app/src/main/kotlin/dev/example/app/Apis.kt",
            """
            package dev.example.app

            import retrofit2.http.GET

            interface OrdersApi { @GET("orders") fun orders(): Any }
            interface AuthApi { @GET("token") fun token(): Any }
            """,
        )
        write(
            "app/src/main/kotlin/dev/example/app/di/NetworkModule.kt",
            """
            package dev.example.app.di

            import dagger.Module
            import dagger.Provides
            import dev.example.app.AuthApi
            import dev.example.app.BuildConfig
            import dev.example.app.OrdersApi
            import javax.inject.Named
            import javax.inject.Singleton
            import okhttp3.OkHttpClient
            import retrofit2.Retrofit

            @Module
            object NetworkModule {
                @Provides
                @Singleton
                fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
                    .baseUrl(BuildConfig.API_URL)
                    .client(client)
                    .build()

                @Provides @Named("auth")
                fun provideAuthRetrofit(): Retrofit {
                    return Retrofit.Builder().baseUrl("https://auth.example.com/oauth/").build()
                }

                @Provides
                fun provideOrders(retrofit: Retrofit): OrdersApi = retrofit.create(OrdersApi::class.java)

                @Provides
                fun provideAuth(@Named("auth") retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
            }
            """,
        )
        val document = scan()
        assertEquals(
            listOf(
                "root api.example.com /orders kt:dev.example.app.di.NetworkModule.provideRetrofit",
                "root staging.example.com /orders kt:dev.example.app.di.NetworkModule.provideRetrofit",
            ),
            document.of("orders").map { it.summary() },
        )
        assertEquals(listOf("root auth.example.com /oauth/token kt:dev.example.app.di.NetworkModule.provideAuthRetrofit"), document.of("token").map { it.summary() })
    }

    @Test
    fun `inject constructors, koin definitions and generic creators resolve their retrofit instance`() {
        write(
            "src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface ProfileApi { @GET("me") fun me(): Any }
            interface FeedApi { @GET("feed") fun feed(): Any }
            interface GenApi { @GET("gen") fun gen(): Any }
            interface ReifiedApi { @GET("reified") fun reified(): Any }
            """,
        )
        write(
            "src/main/kotlin/dev/example/di/Modules.kt",
            """
            package dev.example.di

            import dagger.Provides
            import dev.example.api.FeedApi
            import dev.example.api.ProfileApi
            import javax.inject.Inject
            import org.koin.core.qualifier.named
            import org.koin.dsl.module
            import retrofit2.Retrofit

            class Providers {
                @Provides
                fun retrofit(): Retrofit = Retrofit.Builder().baseUrl("https://dagger.example.com/").build()
            }

            class ProfileRepository @Inject constructor(private val retrofit: Retrofit) {
                private val api = retrofit.create(ProfileApi::class.java)
            }

            val networkModule = module {
                single(named("feed")) { Retrofit.Builder().baseUrl("https://koin.example.com/f/").build() }
                single<FeedApi> { get<Retrofit>(named("feed")).create(FeedApi::class.java) }
            }
            """,
        )
        write(
            "src/main/kotlin/dev/example/net/ServiceGenerator.kt",
            """
            package dev.example.net

            import dev.example.api.GenApi
            import dev.example.api.ReifiedApi
            import retrofit2.Retrofit

            object ServiceGenerator {
                private val retrofit = Retrofit.Builder().baseUrl("https://gen.example.com/").build()

                fun <S> createService(serviceClass: Class<S>): S = retrofit.create(serviceClass)

                inline fun <reified T> service(): T = retrofit.create(T::class.java)
            }

            class Consumer {
                val gen = ServiceGenerator.createService(GenApi::class.java)
                val reified = ServiceGenerator.service<ReifiedApi>()
            }
            """,
        )
        val document = scan()
        assertEquals(listOf("root dagger.example.com /me kt:dev.example.di.Providers.retrofit"), document.of("me").map { it.summary() })
        assertEquals(listOf("root koin.example.com /f/feed kt:dev.example.di.networkModule#named:feed"), document.of("feed").map { it.summary() })
        assertEquals(listOf("root gen.example.com /gen kt:dev.example.net.ServiceGenerator.retrofit"), document.of("gen").map { it.summary() })
        assertEquals(listOf("root gen.example.com /reified kt:dev.example.net.ServiceGenerator.retrofit"), document.of("reified").map { it.summary() })
    }

    @Test
    fun `unresolved bases keep the base anchor with the instance baseRef and are counted`() {
        write(
            "src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface DynamicApi { @GET("dyn") fun dyn(): Any }
            interface OrphanApi { @GET("orphan") fun orphan(): Any }
            interface InvalidApi { @GET("invalid") fun invalid(): Any }
            interface AmbiguousApi { @GET("ambiguous") fun ambiguous(): Any }
            interface RootedApi { @GET("/rooted") fun rooted(): Any }
            """,
        )
        write(
            "src/main/kotlin/dev/example/net/Factories.kt",
            """
            package dev.example.net

            import dagger.Provides
            import dev.example.api.AmbiguousApi
            import dev.example.api.DynamicApi
            import dev.example.api.InvalidApi
            import dev.example.api.RootedApi
            import retrofit2.Retrofit

            fun dynamic(baseUrl: String): DynamicApi = Retrofit.Builder().baseUrl(baseUrl).build().create(DynamicApi::class.java)

            // Retrofit은 `/`로 끝나지 않는 base 경로를 거부한다 — 템플릿을 확정하지 않는다.
            fun invalid(): InvalidApi = Retrofit.Builder().baseUrl("https://h.example.com/v1").build().create(InvalidApi::class.java)

            fun rooted(url: String): RootedApi = Retrofit.Builder().baseUrl(url).build().create(RootedApi::class.java)

            class ModuleA { @Provides fun one(): Retrofit = Retrofit.Builder().baseUrl("https://a.example.com/").build() }
            class ModuleB { @Provides fun two(): Retrofit = Retrofit.Builder().baseUrl("https://b.example.com/").build() }
            class ModuleC { @Provides fun ambiguous(retrofit: Retrofit): AmbiguousApi = retrofit.create(AmbiguousApi::class.java) }
            """,
        )
        write(
            "src/test/kotlin/dev/example/net/FactoryTest.kt",
            """
            package dev.example.net

            import dev.example.api.OrphanApi
            import retrofit2.Retrofit

            class FactoryTest { val api = Retrofit.Builder().baseUrl("https://mock.example.com/").build().create(OrphanApi::class.java) }
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /dyn kt:dev.example.net.dynamic"), document.of("dyn").map { it.summary() })
        assertEquals(listOf("base - /invalid kt:dev.example.net.invalid"), document.of("invalid").map { it.summary() })
        assertEquals(listOf("root - /rooted kt:dev.example.net.rooted"), document.of("rooted").map { it.summary() })
        // 두 provider가 같은 한정자로 Retrofit을 내면 어느 쪽인지 고르지 않는다.
        assertEquals(listOf("base - /ambiguous -"), document.of("ambiguous").map { it.summary() })
        // 테스트 소스의 create(MockWebServer 등)는 production base가 아니다.
        assertEquals(listOf("base - /orphan -"), document.of("orphan").map { it.summary() })
        val limitation = document.limitations.single { it.startsWith("unresolved-base-url:") }
        assertTrue(limitation.startsWith("unresolved-base-url: 5 Retrofit service interface(s)"), limitation)
    }

    @Test
    fun `several create sites emit one fact per base and inherited methods follow the sub-interface`() {
        write(
            "src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface BaseApi { @GET("ping") fun ping(): Any }
            interface AdminApi : BaseApi { @GET("stats") fun stats(): Any }
            """,
        )
        write(
            "src/main/kotlin/dev/example/net/Clients.kt",
            """
            package dev.example.net

            import dev.example.api.AdminApi
            import dev.example.api.BaseApi
            import retrofit2.Retrofit

            class Clients(private val runtime: String) {
                fun primary(): AdminApi {
                    val retrofit = Retrofit.Builder().baseUrl("https://primary.example.com/").build()
                    return retrofit.create(AdminApi::class.java)
                }
                fun mirror(): AdminApi = Retrofit.Builder().baseUrl("https://mirror.example.com/m/").build().create(AdminApi::class.java)
                fun runtimeBase(): BaseApi = Retrofit.Builder().baseUrl(runtime).build().create(BaseApi::class.java)
            }
            """,
        )
        val document = scan()
        assertEquals(
            listOf(
                "root mirror.example.com /m/stats kt:dev.example.net.Clients.mirror",
                "root primary.example.com /stats kt:dev.example.net.Clients.primary",
            ),
            document.of("stats").map { it.summary() },
        )
        assertEquals(
            listOf(
                "base - /ping kt:dev.example.net.Clients.runtimeBase",
                "root mirror.example.com /m/ping kt:dev.example.net.Clients.mirror",
                "root primary.example.com /ping kt:dev.example.net.Clients.primary",
            ),
            document.of("ping").map { it.summary() }.sorted(),
        )
    }

    @Test
    fun `url rewriting interceptors drop resolved bases but header interceptors do not`() {
        write("src/main/kotlin/dev/example/api/Api.kt", service("dev.example.api", "Api", "@GET(\"items\") fun items(): Any"))
        write(
            "src/main/kotlin/dev/example/net/Client.kt",
            """
            package dev.example.net

            import dev.example.api.Api
            import okhttp3.Interceptor
            import retrofit2.Retrofit

            class HeaderInterceptor : Interceptor {
                override fun intercept(chain: Interceptor.Chain) = chain.proceed(chain.request().newBuilder().header("X-App", "1").build())
            }

            fun api(): Api = Retrofit.Builder().baseUrl("https://api.example.com/").build().create(Api::class.java)
            """,
        )
        assertEquals(listOf("root api.example.com /items kt:dev.example.net.api"), scan().of("items").map { it.summary() })
        write(
            "src/main/kotlin/dev/example/net/HostInterceptor.kt",
            """
            package dev.example.net

            import okhttp3.Interceptor

            class HostInterceptor(private val host: String) : Interceptor {
                override fun intercept(chain: Interceptor.Chain) = chain.request().let { request ->
                    chain.proceed(request.newBuilder().url(request.url.newBuilder().host(host).build()).build())
                }
            }
            """,
        )
        val document = scan()
        assertEquals(listOf("base - /items kt:dev.example.net.api"), document.of("items").map { it.summary() })
        assertTrue(document.limitations.any { it.startsWith("url-rewrite-interceptors: 2 OkHttp interceptor") }, document.limitations.toString())
        assertTrue(document.limitations.any { it.startsWith("unresolved-base-url: 1 ") }, document.limitations.toString())
    }

    @Test
    fun `build config fields resolve only from literal definitions of the same module namespace`() {
        write("lib/build.gradle", "android {\n  namespace 'dev.example.lib'\n  defaultConfig { buildConfigField 'String', 'BASE', '\"https://groovy.example.com/\"' }\n}")
        write("lib/src/main/kotlin/dev/example/lib/Api.kt", service("dev.example.lib", "LibApi", "@GET(\"lib\") fun lib(): Any"))
        write(
            "lib/src/main/kotlin/dev/example/lib/Factory.kt",
            """
            package dev.example.lib

            import retrofit2.Retrofit

            fun lib(): LibApi = Retrofit.Builder().baseUrl(BuildConfig.BASE).build().create(LibApi::class.java)
            """,
        )
        write("core/build.gradle.kts", "android {\n  namespace = \"dev.example.core\"\n  defaultConfig { buildConfigField(\"String\", \"BASE\", \"\\\"${'$'}{property(\"base\")}\\\"\") }\n}")
        write("core/src/main/kotlin/dev/example/core/Api.kt", service("dev.example.core", "CoreApi", "@GET(\"core\") fun core(): Any"))
        write(
            "core/src/main/kotlin/dev/example/core/Factory.kt",
            """
            package dev.example.core

            import retrofit2.Retrofit

            fun core(): CoreApi = Retrofit.Builder().baseUrl(BuildConfig.BASE).build().create(CoreApi::class.java)
            fun other(): OtherApi = Retrofit.Builder().baseUrl(dev.example.lib.BuildConfig.BASE).build().create(OtherApi::class.java)
            """,
        )
        write("core/src/main/kotlin/dev/example/core/Other.kt", service("dev.example.core", "OtherApi", "@GET(\"other\") fun other(): Any"))
        val document = scan()
        assertEquals(listOf("root groovy.example.com /lib kt:dev.example.lib.lib"), document.of("lib").map { it.summary() })
        // 보간이 든 선언은 값이 아니다.
        assertEquals(listOf("base - /core kt:dev.example.core.core"), document.of("core").map { it.summary() })
        // 다른 모듈의 BuildConfig는 가장 가까운 빌드 파일의 namespace와 달라 풀지 않는다.
        assertEquals(listOf("base - /other kt:dev.example.core.other"), document.of("other").map { it.summary() })
    }

    @Test
    fun `getters, field injection, java inject constructors and read-only url properties are followed`() {
        write(
            "src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface GetterApi { @GET("getter") fun getter(): Any }
            interface FieldApi { @GET("field") fun field(): Any }
            interface KoinApi { @GET("koin") fun koin(): Any }
            """,
        )
        write(
            "src/main/kotlin/dev/example/net/Holders.kt",
            """
            package dev.example.net

            import dagger.Provides
            import dev.example.api.FieldApi
            import dev.example.api.GetterApi
            import dev.example.api.KoinApi
            import javax.inject.Inject
            import org.koin.dsl.module
            import retrofit2.Retrofit

            class GetterHolder {
                private val base = "https://getter.example.com/g/"
                private val retrofit: Retrofit
                    get() = Retrofit.Builder().baseUrl(this.base).build()
                fun api(): GetterApi = this.retrofit.create(GetterApi::class.java)
            }

            class FieldHolder {
                @Inject lateinit var retrofit: Retrofit
                fun api(): FieldApi = retrofit.create(FieldApi::class.java)
            }

            object Wiring {
                @Provides fun retrofit(): Retrofit = Retrofit.Builder().baseUrl("https://field.example.com/").build()
            }

            val appModule = module {
                single { Retrofit.Builder().baseUrl("https://koin-plain.example.com/").build() }
                factory { get<Retrofit>().create(KoinApi::class.java) }
            }
            """,
        )
        write(
            "src/main/java/dev/example/legacy/InjectedClient.java",
            """
            package dev.example.legacy;
            import javax.inject.Inject;
            import retrofit2.Retrofit;
            import retrofit2.http.GET;
            interface JavaInjectedApi { @GET("java-injected") Object injected(); }
            public final class InjectedClient {
                private final Retrofit retrofit;
                @Inject
                public InjectedClient(Retrofit retrofit) {
                    this.retrofit = retrofit;
                }
                JavaInjectedApi api() { return retrofit.create(JavaInjectedApi.class); }
            }
            """,
        )
        val document = scan()
        assertEquals(listOf("root getter.example.com /g/getter kt:dev.example.net.GetterHolder.retrofit"), document.of("getter").map { it.summary() })
        assertEquals(listOf("root field.example.com /field kt:dev.example.net.Wiring.retrofit"), document.of("field").map { it.summary() })
        assertEquals(listOf("root field.example.com /java-injected kt:dev.example.net.Wiring.retrofit"), document.of("injected").map { it.summary() })
        // 두 번째 정의(factory)는 Retrofit이 아니라 서비스를 만든다 — Retrofit 정의는 하나다.
        assertEquals(listOf("root koin-plain.example.com /koin kt:dev.example.net.appModule"), document.of("koin").map { it.summary() })
    }

    @Test
    fun `dynamic absolute hosts and shadowed local assignments never borrow the service base`() {
        write(
            "src/main/kotlin/dev/example/api/EdgeApi.kt",
            service(
                "dev.example.api", "EdgeApi",
                "@GET(\"https://{tenant}.example.com/x\") fun tenant(@Path(\"tenant\") tenant: String): Any",
                "@GET(\"//{cdn}/logo.png\") fun logo(@Path(\"cdn\") cdn: String): Any",
                "@GET(\"items\") fun items(): Any",
            ),
        )
        write(
            "src/main/java/dev/example/legacy/Edge.java",
            """
            package dev.example.legacy;
            import dev.example.api.EdgeApi;
            import retrofit2.Retrofit;
            public final class Edge {
                private static Retrofit retrofit = new Retrofit.Builder().baseUrl("https://edge.example.com/").build();
                static void rebuild() {
                    Retrofit retrofit;
                    retrofit = new Retrofit.Builder().baseUrl("https://other.example.com/").build();
                }
                static EdgeApi api() { return retrofit.create(EdgeApi.class); }
            }
            """,
        )
        val document = scan()
        // host가 보간인 전체 URL은 base host를 빌리지 않는다.
        assertEquals(listOf("base - /x -"), document.of("tenant").map { it.summary() })
        assertEquals(listOf("base - /logo.png -"), document.of("logo").map { it.summary() })
        // 지역 변수에 한 대입은 필드의 base가 아니다.
        assertEquals(listOf("root edge.example.com /items kt:dev.example.legacy.Edge.retrofit"), document.of("items").map { it.summary() })
    }

    @Test
    fun `review reproductions - same-named members elsewhere, dot segments in the base and multi-line build config`() {
        write("app/build.gradle.kts", """
            android {
                buildTypes {
                    release {
                        buildConfigField(
                            "String",
                            "API_URL",
                            "\"https://multi.example.com/\"",
                        )
                    }
                }
            }
        """)
        write(
            "app/src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface HostApi { @GET("users") fun users(): Any }
            interface DotApi { @GET("dots") fun dots(): Any }
            interface MultiApi { @GET("multi") fun multi(): Any }
            """,
        )
        write(
            "app/src/main/kotlin/dev/example/net/Hosts.kt",
            """
            package dev.example.net

            import dev.example.api.DotApi
            import dev.example.api.HostApi
            import dev.example.api.MultiApi
            import retrofit2.Retrofit

            class ApiHost {
                var retrofit = Retrofit.Builder().baseUrl("https://a.example.com/").build()
                fun api(): HostApi = retrofit.create(HostApi::class.java)
            }

            class Other {
                var retrofit = Retrofit.Builder().baseUrl("https://b.example.com/").build()
                fun reset() { retrofit = Retrofit.Builder().baseUrl("https://c.example.com/").build() }
            }

            fun dots(): DotApi = Retrofit.Builder().baseUrl("https://d.example.com/v2/../api/").build().create(DotApi::class.java)
            fun multi(): MultiApi = Retrofit.Builder().baseUrl(dev.example.BuildConfig.API_URL).build().create(MultiApi::class.java)
            """,
        )
        val document = scan()
        // 다른 class의 같은 이름 속성과 그 대입은 ApiHost.retrofit의 값이 아니다.
        assertEquals(listOf("root a.example.com /users kt:dev.example.net.ApiHost.retrofit"), document.of("users").map { it.summary() })
        // base 경로의 점 세그먼트는 OkHttp처럼 지운다.
        assertEquals(listOf("root d.example.com /api/dots kt:dev.example.net.dots"), document.of("dots").map { it.summary() })
        // 여러 줄 Kotlin DSL 인자도 읽는다(namespace가 없으면 패키지를 대조하지 않는다).
        assertEquals(listOf("root multi.example.com /multi kt:dev.example.net.multi"), document.of("multi").map { it.summary() })
    }

    @Test
    fun `review reproductions - default ports, class-like parameter names and rewrites outside interceptors`() {
        write(
            "src/main/kotlin/dev/example/api/Apis.kt",
            """
            package dev.example.api

            import retrofit2.http.GET

            interface PortApi { @GET("port") fun port(): Any }
            """,
        )
        write(
            "src/main/kotlin/dev/example/net/Net.kt",
            """
            package dev.example.net

            import dev.example.api.PortApi
            import okhttp3.Interceptor
            import okhttp3.Request
            import retrofit2.Retrofit

            enum class ClassKind { A }

            object Factory {
                fun create(kind: ClassKind): Any = create(kind)
            }

            val logging = Interceptor { chain -> chain.proceed(chain.request()) }

            fun retarget(request: Request, url: String): Request = request.newBuilder().url(url).build()

            fun port(): PortApi = Retrofit.Builder().baseUrl("https://port.example.com:443/").build().create(PortApi::class.java)
            """,
        )
        val document = scan()
        // https 기본 포트는 OkHttp가 지운다. 인터셉터 밖의 요청 재작성은 URL 재작성 인터셉터가 아니다.
        assertEquals(listOf("root port.example.com /port kt:dev.example.net.port"), document.of("port").map { it.summary() })
        assertTrue(document.limitations.none { it.startsWith("url-rewrite-interceptors:") }, document.limitations.toString())
    }

    @Test
    fun `base url literals follow okhttp acceptance rules`() {
        assertEquals(RetrofitBaseUrl("api.example.com", "/"), RetrofitBaseUrl.parse("https://user:pw@API.example.com"))
        assertEquals(RetrofitBaseUrl("h.example.com:8080", "/v1/"), RetrofitBaseUrl.parse(" http://h.example.com:8080/v1/?x=1#f "))
        assertEquals(RetrofitBaseUrl("[::1]", "/"), RetrofitBaseUrl.parse("http://[::1]/"))
        assertNull(RetrofitBaseUrl.parse("https://h.example.com/v1"))
        assertNull(RetrofitBaseUrl.parse("ftp://h.example.com/"))
        assertNull(RetrofitBaseUrl.parse("/relative/"))
        assertNull(RetrofitBaseUrl.parse("https://bad_host/"))
        assertEquals(RetrofitBaseUrl("h.example.com", "/"), RetrofitBaseUrl.parse("http://h.example.com:80/"))
        assertEquals(RetrofitBaseUrl("h.example.com:443", "/"), RetrofitBaseUrl.parse("http://h.example.com:443/"))
        assertNull(RetrofitBaseUrl.parse("https://h.example.com:0/"))
        assertNull(RetrofitBaseUrl.parse("https://h.example.com:99999/"))
        assertNull(RetrofitBaseUrl.parse("https://h.example.com:0443/"))
    }
}
