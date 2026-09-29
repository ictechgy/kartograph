package dev.kartograph.experiments.retrofit

import dev.kartograph.fixture.retrofit.AdminApi
import dev.kartograph.fixture.retrofit.FilesApi
import dev.kartograph.fixture.retrofit.LegacyAdminApi
import dev.kartograph.fixture.retrofit.LegacyApi
import dev.kartograph.fixture.retrofit.OrgsApi
import dev.kartograph.fixture.retrofit.UsersApi
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import retrofit2.Retrofit

/**
 * 합성 Retrofit 서비스(`fixtures/retrofit-corpus/oracle/retrofit-client`)의 각 메서드를 실제로 호출해 OkHttp
 * MockWebServer가 받은 요청 동사·경로·query를 기록한다(검증 전용 오라클, 기본 CI 밖).
 *
 * 다른 host로 가는 전체 URL·network-path 어노테이션은 네트워크를 쓰지 않도록 인터셉터가 host·port만 MockWebServer로
 * 바꾼다. 경로는 Retrofit이 만든 그대로 두고, 바꾸기 전 host를 `authority`로 기록한다.
 *
 * 사용: `RetrofitOracle <output.json>`
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "usage: RetrofitOracle <output.json>" }
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200).setBody("")
    }
    server.start()
    try {
        val results = CASES.map { case -> record(server, case) }
        File(arguments[0]).writeText(render(results))
    } finally {
        server.shutdown()
    }
}

/** `@Path` 인자 하나다. [encoded]는 `@Path(encoded = true)` 여부다. */
data class PathArgument(val value: String, val encoded: Boolean = false)

/**
 * 오라클 케이스 하나다.
 *
 * @property symbol 어노테이션을 선언한 인터페이스 FQN + 메서드 이름이다(상속 메서드는 상위 인터페이스)
 * @property basePath Retrofit `baseUrl`의 경로다. Retrofit은 `/`로 끝나야 받는다
 * @property pathArguments 어노테이션 템플릿에 나타나는 순서의 `@Path` 인자다
 */
class Case(
    val id: String,
    val symbol: String,
    val basePath: String,
    val pathArguments: List<PathArgument>,
    val invoke: suspend (Retrofit) -> Unit,
)

/** 한 케이스를 실행한 기록이다. [authority]는 인터셉터가 본 원래 host다. */
class Recorded(val case: Case, val method: String, val path: String, val query: String?, val authority: String)

private val PLAIN: MediaType = requireNotNull(MediaType.parse("text/plain"))

private fun body(): RequestBody = RequestBody.create(PLAIN, "x")

private const val API = "/api/v1/"
private const val ROOT = "/"
private const val USERS = "dev.kartograph.fixture.retrofit.UsersApi"
private const val ORGS = "dev.kartograph.fixture.retrofit.OrgsApi"
private const val FILES = "dev.kartograph.fixture.retrofit.FilesApi"
private const val LEGACY = "dev.kartograph.fixture.retrofit.LegacyApi"

private inline fun <reified T> Retrofit.service(): T = create(T::class.java)

/** 케이스 목록이다. id는 결과 파일의 정렬 키다. */
val CASES: List<Case> = listOf(
    Case("users.user", "$USERS.user", API, listOf(PathArgument("42"))) { it.service<UsersApi>().user("42", "name").execute() },
    Case("users.user.root-base", "$USERS.user", ROOT, listOf(PathArgument("42"))) { it.service<UsersApi>().user("42", null).execute() },
    Case("users.create", "$USERS.create", API, emptyList()) { it.service<UsersApi>().create(body()).execute() },
    Case("users.replace", "$USERS.replace", API, listOf(PathArgument("7"))) { it.service<UsersApi>().replace("7", body()).execute() },
    Case("users.update", "$USERS.update", API, listOf(PathArgument("7"))) { it.service<UsersApi>().update("7", body()).execute() },
    Case("users.remove", "$USERS.remove", API, listOf(PathArgument("7"))) { it.service<UsersApi>().remove("7").execute() },
    Case("users.exists", "$USERS.exists", API, listOf(PathArgument("7"))) { it.service<UsersApi>().exists("7").execute() },
    Case("users.options", "$USERS.options", API, emptyList()) { it.service<UsersApi>().options().execute() },
    Case("users.end-sessions", "$USERS.endSessions", API, listOf(PathArgument("7"))) { it.service<UsersApi>().endSessions("7", body()).execute() },
    Case("users.purge-cache", "$USERS.purgeCache", API, emptyList()) { it.service<UsersApi>().purgeCache().execute() },
    Case("users.search", "$USERS.search", API, emptyList()) { it.service<UsersApi>().search(mapOf("q" to "ann", "sort" to "asc")).execute() },
    Case("users.active", "$USERS.active", API, emptyList()) { it.service<UsersApi>().active(2).execute() },
    Case("users.health", "$USERS.health", API, emptyList()) { it.service<UsersApi>().health().execute() },
    Case("users.health.root-base", "$USERS.health", ROOT, emptyList()) { it.service<UsersApi>().health().execute() },
    Case("users.raw", "$USERS.raw", API, emptyList()) { it.service<UsersApi>().raw("reports/daily?format=csv").execute() },
    Case("users.profile.suspend", "$USERS.profile", API, listOf(PathArgument("42"))) { it.service<UsersApi>().profile("42") },
    Case("users.avatar.qualified-annotation", "$USERS.avatar", API, listOf(PathArgument("42"))) { it.service<UsersApi>().avatar("42").execute() },
    Case("orgs.org", "$ORGS.org", API, listOf(PathArgument("acme"))) { it.service<OrgsApi>().org("acme").execute() },
    Case("orgs.repos", "$ORGS.repos", API, listOf(PathArgument("acme"))) { it.service<OrgsApi>().repos("acme").execute() },
    Case("orgs.audit", "$ORGS.audit", API, emptyList()) { it.service<OrgsApi>().audit().execute() },
    Case("orgs.teams", "$ORGS.teams", API, emptyList()) { it.service<OrgsApi>().teams().execute() },
    Case("orgs.members.external-constant", "$ORGS.members", API, emptyList()) { it.service<OrgsApi>().members().execute() },
    Case("orgs.member.external-constant", "$ORGS.member", API, listOf(PathArgument("ann"))) { it.service<OrgsApi>().member("ann").execute() },
    Case("admin.stats", "dev.kartograph.fixture.retrofit.AdminApi.stats", API, emptyList()) { it.service<AdminApi>().stats().execute() },
    Case("admin.ping.inherited", "dev.kartograph.fixture.retrofit.BaseApi.ping", API, emptyList()) { it.service<AdminApi>().ping().execute() },
    Case("files.file", "$FILES.file", API, listOf(PathArgument("report"))) { it.service<FilesApi>().file("report").execute() },
    Case("files.file.slash-value", "$FILES.file", API, listOf(PathArgument("a/b"))) { it.service<FilesApi>().file("a/b").execute() },
    Case("files.file.space-value", "$FILES.file", API, listOf(PathArgument("q 1"))) { it.service<FilesApi>().file("q 1").execute() },
    Case("files.doc.encoded", "$FILES.doc", API, listOf(PathArgument("guide/intro", encoded = true))) {
        it.service<FilesApi>().doc("guide/intro").execute()
    },
    Case("files.metadata.partial-segment", "$FILES.metadata", API, listOf(PathArgument("report"))) { it.service<FilesApi>().metadata("report").execute() },
    Case("files.status.dot", "$FILES.status", API, emptyList()) { it.service<FilesApi>().status().execute() },
    Case("files.status-v2.dot-dot", "$FILES.statusV2", API, emptyList()) { it.service<FilesApi>().statusV2().execute() },
    Case("files.index.dot-only", "$FILES.index", API, emptyList()) { it.service<FilesApi>().index().execute() },
    Case("files.summary.inner-dot-dot", "$FILES.summary", API, emptyList()) { it.service<FilesApi>().summary().execute() },
    Case("files.export.rooted-dots", "$FILES.export", API, emptyList()) { it.service<FilesApi>().export().execute() },
    Case("files.blob.absolute", "$FILES.blob", API, listOf(PathArgument("9"))) { it.service<FilesApi>().blob("9").execute() },
    Case("files.logo.network-path", "$FILES.logo", API, emptyList()) { it.service<FilesApi>().logo().execute() },
    Case("legacy.item.java-value", "$LEGACY.item", API, listOf(PathArgument("5"))) { it.service<LegacyApi>().item("5").execute() },
    Case("legacy.patch.java-http", "$LEGACY.patch", API, listOf(PathArgument("5"))) { it.service<LegacyApi>().patch("5", body()).execute() },
    Case("legacy.items.interface-constant", "$LEGACY.items", API, emptyList()) { it.service<LegacyApi>().items().execute() },
    Case("legacy.items.inherited", "$LEGACY.items", API, emptyList()) { it.service<LegacyAdminApi>().items().execute() },
    Case("legacy.archive.class-constant", "$LEGACY.archive", API, emptyList()) { it.service<LegacyApi>().archive().execute() },
    Case("legacy.purge.rooted", "$LEGACY.purge", API, emptyList()) { it.service<LegacyApi>().purge().execute() },
    Case("legacy.reindex", "dev.kartograph.fixture.retrofit.LegacyAdminApi.reindex", API, emptyList()) {
        it.service<LegacyAdminApi>().reindex().execute()
    },
)

/**
 * 케이스를 실행하고 받은 요청을 기록한다. 인터셉터는 원래 host를 기록한 뒤 MockWebServer로 보낸다.
 * 요청이 오지 않으면(Retrofit이 요청 전에 거부) 원인과 함께 실패한다.
 */
private fun record(server: MockWebServer, case: Case): Recorded {
    var authority = ""
    val redirect = Interceptor { chain ->
        val original = chain.request().url()
        authority = authorityOf(original)
        val local = original.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(chain.request().newBuilder().url(local).build())
    }
    val client = OkHttpClient.Builder().addInterceptor(redirect).build()
    val retrofit = Retrofit.Builder().baseUrl(server.url(case.basePath)).client(client).build()
    runBlocking { case.invoke(retrofit) }
    val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "case ${case.id} sent no request; check the service method" }
    val url = requireNotNull(request.requestUrl) { "case ${case.id} has no request URL" }
    return Recorded(case, requireNotNull(request.method), url.encodedPath(), url.encodedQuery(), authority)
}

/** 기본 포트를 뺀 소문자 `host[:port]`다. */
private fun authorityOf(url: HttpUrl): String =
    if (url.port() == HttpUrl.defaultPort(url.scheme())) url.host() else "${url.host()}:${url.port()}"

/** 결정적 JSON이다(케이스 id 순, 키 고정 순서). MockWebServer의 host·port는 싣지 않는다. */
private fun render(results: List<Recorded>): String = buildString {
    append("{\n  \"cases\": [\n")
    results.sortedBy { it.case.id }.forEachIndexed { index, result ->
        val case = result.case
        append("    {")
        append("\"id\": ").append(quote(case.id)).append(", ")
        append("\"symbol\": ").append(quote(case.symbol)).append(", ")
        append("\"basePath\": ").append(quote(case.basePath)).append(", ")
        append("\"pathArguments\": [")
        append(case.pathArguments.joinToString(", ") { "{\"value\": ${quote(it.value)}, \"encoded\": ${it.encoded}}" })
        append("], ")
        append("\"recorded\": {")
        append("\"method\": ").append(quote(result.method)).append(", ")
        append("\"path\": ").append(quote(result.path)).append(", ")
        append("\"query\": ").append(result.query?.let(::quote) ?: "null").append(", ")
        append("\"authority\": ").append(quote(result.authority.takeUnless { it.startsWith("localhost") || it.startsWith("127.") } ?: ""))
        append("}}")
        append(if (index < results.lastIndex) ",\n" else "\n")
    }
    append("  ]\n}\n")
}

private fun quote(text: String): String = buildString {
    append('"')
    text.forEach { character ->
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character.code < 0x20 -> append("\\u%04x".format(character.code))
            else -> append(character)
        }
    }
    append('"')
}
