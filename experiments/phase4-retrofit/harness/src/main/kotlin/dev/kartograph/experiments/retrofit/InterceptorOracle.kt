package dev.kartograph.experiments.retrofit

import dev.kartograph.fixture.interceptor.EdgeClients
import dev.kartograph.fixture.interceptor.InterceptorModule
import dev.kartograph.fixture.interceptor.JavaInterceptorClients
import dev.kartograph.fixture.interceptor.KoinEdgeApi
import dev.kartograph.fixture.interceptor.KoinPlainApi
import dev.kartograph.fixture.interceptor.SharedClients
import dev.kartograph.fixture.interceptor.authApi
import dev.kartograph.fixture.interceptor.factoryApi
import dev.kartograph.fixture.interceptor.interceptorKoinModule
import dev.kartograph.fixture.interceptor.lambdaApi
import dev.kartograph.fixture.interceptor.listenerApi
import dev.kartograph.fixture.interceptor.objectApi
import dev.kartograph.fixture.interceptor.prefixApi
import dev.kartograph.fixture.interceptor.tokenApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * 인터셉터 결합 코퍼스(`fixtures/retrofit-corpus/oracle/interceptor-client`)의 케이스다. 코퍼스의 client는 코퍼스 안에서 만들어지므로
 * 오라클은 JVM 기본 [ProxySelector]를 MockWebServer로 돌려 요청을 받는다 — `http` 요청은 프록시에 절대 URL 요청 줄로 오므로
 * 인터셉터·`Authenticator`·`Call.Factory`가 바꾼 뒤의 host와 경로를 그대로 기록한다.
 *
 * @property declaredBase 코퍼스 소스가 이 서비스의 Retrofit에 준 base다(스캐너가 base를 버렸을 때 기록과 비교할 값)
 * @property challenge 첫 요청에 401을 돌려 `Authenticator`의 재요청을 기록한다
 */
class InterceptorCase(
    val id: String,
    val symbol: String,
    val declaredBase: String,
    val pathArguments: List<PathArgument>,
    val challenge: Boolean = false,
    val invoke: () -> Unit,
)

/** 인터셉터 케이스 하나의 기록이다. [challenged]는 401을 받은 첫 요청의 (authority, 경로)다. */
class InterceptorRecorded(
    val case: InterceptorCase,
    val method: String,
    val path: String,
    val query: String?,
    val authority: String,
    val challenged: Pair<String, String>?,
)

private const val PACKAGE = "dev.kartograph.fixture.interceptor"

/** 인터셉터 결합 규칙마다 실제 요청을 확인하는 케이스다. id는 결과 파일의 정렬 키다. */
val INTERCEPTOR_CASES: List<InterceptorCase> = listOf(
    InterceptorCase("interceptor.class-instance.item", "$PACKAGE.EdgeApi.item", "http://api.example.com/v1/", listOf(PathArgument("42"))) {
        EdgeClients.edge().item("42").execute()
    },
    InterceptorCase("interceptor.class-instance.order", "$PACKAGE.EdgeApi.order", "http://api.example.com/v1/", emptyList()) {
        EdgeClients.edge().order().execute()
    },
    InterceptorCase("interceptor.header-only.status", "$PACKAGE.PlainApi.status", "http://api.example.com/v1/", emptyList()) {
        EdgeClients.plain().status().execute()
    },
    InterceptorCase("interceptor.network-path.report", "$PACKAGE.PrefixApi.report", "http://reports.example.com/v1/", listOf(PathArgument("7"))) {
        prefixApi().report("7").execute()
    },
    InterceptorCase("interceptor.lambda.ping", "$PACKAGE.LambdaApi.ping", "http://lambda.api.example.com/", emptyList()) { lambdaApi().ping().execute() },
    InterceptorCase("interceptor.object.ping", "$PACKAGE.ObjectApi.ping", "http://object.api.example.com/", emptyList()) { objectApi().ping().execute() },
    InterceptorCase("interceptor.shared.ping", "$PACKAGE.SharedApi.ping", "http://shared.api.example.com/", emptyList()) {
        SharedClients.sharedApi().ping().execute()
    },
    InterceptorCase("interceptor.derived.ping", "$PACKAGE.DerivedApi.ping", "http://shared.api.example.com/", emptyList()) {
        SharedClients.derivedApi().ping().execute()
    },
    InterceptorCase("interceptor.provides.edge", "$PACKAGE.ProvidedEdgeApi.item", "http://provided.api.example.com/", listOf(PathArgument("9"))) {
        // Dagger가 만들 그래프와 같은 순서로 제공 함수를 부른다.
        InterceptorModule.edgeApi(InterceptorModule.edgeRetrofit(InterceptorModule.edgeClient(InterceptorModule.hostRewriter()))).item("9").execute()
    },
    InterceptorCase("interceptor.provides.plain", "$PACKAGE.ProvidedPlainApi.status", "http://provided.api.example.com/", emptyList()) {
        InterceptorModule.plainApi(InterceptorModule.plainRetrofit(InterceptorModule.plainClient())).status().execute()
    },
    InterceptorCase("interceptor.koin.edge", "$PACKAGE.KoinEdgeApi.item", "http://koin.api.example.com/", listOf(PathArgument("5"))) {
        GlobalContext.get().get<KoinEdgeApi>().item("5").execute()
    },
    InterceptorCase("interceptor.koin.plain", "$PACKAGE.KoinPlainApi.status", "http://koin.api.example.com/", emptyList()) {
        GlobalContext.get().get<KoinPlainApi>().status().execute()
    },
    InterceptorCase("interceptor.java.edge", "$PACKAGE.JavaInterceptorClients.JavaEdgeApi.ping", "http://java.api.example.com/", emptyList()) {
        JavaInterceptorClients.edge().ping().execute()
    },
    InterceptorCase("interceptor.java.plain", "$PACKAGE.JavaInterceptorClients.JavaPlainApi.status", "http://java.api.example.com/", emptyList()) {
        JavaInterceptorClients.plain().status().execute()
    },
    InterceptorCase("interceptor.authenticator.reroute", "$PACKAGE.AuthApi.profile", "http://auth.api.example.com/", emptyList(), challenge = true) {
        authApi().profile().execute()
    },
    InterceptorCase("interceptor.authenticator.header", "$PACKAGE.TokenApi.token", "http://token.api.example.com/", emptyList(), challenge = true) {
        tokenApi().token().execute()
    },
    InterceptorCase("interceptor.event-listener.ping", "$PACKAGE.ListenerApi.ping", "http://events.api.example.com/", emptyList()) {
        listenerApi().ping().execute()
    },
    InterceptorCase("interceptor.call-factory.ping", "$PACKAGE.FactoryApi.ping", "http://factory.api.example.com/", emptyList()) {
        factoryApi().ping().execute()
    },
)

/**
 * 루프백이 아닌 host의 요청을 MockWebServer 프록시로 보낸다. 루프백은 프록시하지 않는다 — 기존 케이스의 전환 client가 쓰는
 * `localhost` 요청을 바꾸지 않기 위해서다.
 */
private class OracleProxySelector(private val server: MockWebServer) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val host = uri.host.orEmpty()
        if (host == "localhost" || host.startsWith("127.") || host == server.hostName) return listOf(Proxy.NO_PROXY)
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, server.port)))
    }

    override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
        throw IllegalStateException("oracle proxy connection failed for $uri; check that MockWebServer is running", failure)
    }
}

/**
 * 인터셉터 케이스를 모두 실행한다. 기본 [ProxySelector]와 Koin은 이 함수 안에서만 바꾸고 끝나면 되돌린다.
 *
 * @param challenge 참이면 MockWebServer가 다음 요청 하나에 401을 돌려준다
 */
fun recordInterceptorCases(server: MockWebServer, challenge: AtomicBoolean): List<InterceptorRecorded> {
    val previous = ProxySelector.getDefault()
    ProxySelector.setDefault(OracleProxySelector(server))
    startKoin { modules(interceptorKoinModule) }
    try {
        return INTERCEPTOR_CASES.map { case -> recordInterceptor(server, challenge, case) }
    } finally {
        stopKoin()
        ProxySelector.setDefault(previous)
    }
}

private fun recordInterceptor(server: MockWebServer, challenge: AtomicBoolean, case: InterceptorCase): InterceptorRecorded {
    challenge.set(case.challenge)
    case.invoke()
    val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "case ${case.id} sent no request; check the corpus factory" }
    val challenged = if (case.challenge) proxiedUrl(case, first.requestLine) else null
    val final = if (case.challenge) {
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "case ${case.id} did not retry after 401; check the Authenticator" }
    } else first
    val url = proxiedUrl(case, final.requestLine)
    return InterceptorRecorded(
        case, requireNotNull(final.method), url.encodedPath(), url.encodedQuery(), oracleAuthority(url),
        challenged?.let { oracleAuthority(it) to it.encodedPath() },
    )
}

/** 프록시 요청 줄(`GET http://host/path HTTP/1.1`)의 절대 URL이다. 절대 URL이 아니면 프록시를 거치지 않은 것이라 실패한다. */
private fun proxiedUrl(case: InterceptorCase, requestLine: String): HttpUrl {
    val target = requestLine.split(' ').getOrNull(1).orEmpty()
    return requireNotNull(HttpUrl.parse(target)) { "case ${case.id} did not reach the oracle proxy with an absolute URL: $requestLine" }
}

/** 기본 포트를 뺀 소문자 `host[:port]`다. */
private fun oracleAuthority(url: HttpUrl): String =
    if (url.port() == HttpUrl.defaultPort(url.scheme())) url.host() else "${url.host()}:${url.port()}"

/** 인터셉터 케이스 기록의 JSON 배열 원소들이다(케이스 id 순, 키 고정 순서). */
fun renderInterceptorCases(results: List<InterceptorRecorded>): String = results.sortedBy { it.case.id }.joinToString(",\n") { result ->
    val case = result.case
    buildString {
        append("    {")
        append("\"id\": ").append(quoteJson(case.id)).append(", ")
        append("\"symbol\": ").append(quoteJson(case.symbol)).append(", ")
        append("\"declaredBase\": ").append(quoteJson(case.declaredBase)).append(", ")
        append("\"pathArguments\": [")
        append(case.pathArguments.joinToString(", ") { "{\"value\": ${quoteJson(it.value)}, \"encoded\": ${it.encoded}}" })
        append("], ")
        append("\"challenged\": ")
        append(result.challenged?.let { "{\"authority\": ${quoteJson(it.first)}, \"path\": ${quoteJson(it.second)}}" } ?: "null")
        append(", \"recorded\": {")
        append("\"method\": ").append(quoteJson(result.method)).append(", ")
        append("\"path\": ").append(quoteJson(result.path)).append(", ")
        append("\"query\": ").append(result.query?.let(::quoteJson) ?: "null").append(", ")
        append("\"authority\": ").append(quoteJson(result.authority))
        append("}}")
    }
}
