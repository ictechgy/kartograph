package dev.kartograph.experiments.springclient

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.kartograph.fixture.springclient.BillingClient
import dev.kartograph.fixture.springclient.CatalogApi
import dev.kartograph.fixture.springclient.EnvClient
import dev.kartograph.fixture.springclient.InventoryClient
import dev.kartograph.fixture.springclient.LegacyClient
import dev.kartograph.fixture.springclient.OracleApplication
import dev.kartograph.fixture.springclient.OrdersClient
import dev.kartograph.fixture.springclient.ReportsApi
import dev.kartograph.fixture.springclient.SearchClient
import dev.kartograph.fixture.springclient.ShippingClient
import dev.kartograph.fixture.springclient.UserClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * 합성 Spring Boot 클라이언트 앱(`fixtures/spring-clients-corpus/oracle/spring-client`)의 각 호출을 실제로 실행해, 요청이 도착한
 * 동사·인코딩된 경로·host를 기록한다(검증 전용 오라클, 기본 CI 밖).
 *
 * 앱의 host는 모두 `.example.test`다. JVM의 HTTP 프록시(`http.proxyHost`)를 127.0.0.1의 JDK `HttpServer`로 두면 JDK HttpClient
 * (RestClient·WebClient·Boot RestTemplateBuilder)와 `HttpURLConnection`(`new RestTemplate()`)이 요청을 absolute-form으로 보낸다 —
 * 그래서 앱 소스의 base URL을 바꾸지 않고 Spring이 만든 경로와 원래 host를 그대로 받는다. 서버는 이 프로세스 안에서만 떠 있다.
 *
 * 사용: `SpringClientOracle <output.json>`
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "usage: SpringClientOracle <output.json>" }
    val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange -> record(exchange, recorded) }
    server.start()
    System.setProperty("http.proxyHost", "127.0.0.1")
    System.setProperty("http.proxyPort", server.address.port.toString())
    System.setProperty("http.nonProxyHosts", "")
    var context: ConfigurableApplicationContext? = null
    try {
        context = SpringApplicationBuilder(OracleApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties("spring.main.banner-mode=off", "logging.level.root=WARN")
            .run()
        val results = CASES.map { case ->
            recorded.clear()
            runCatching { case.invoke(context) }.exceptionOrNull()?.let { failure ->
                if (recorded.isEmpty()) throw IllegalStateException("case ${case.id} sent no request: ${failure.message}", failure)
            }
            val request = recorded.singleOrNull() ?: error("case ${case.id} sent ${recorded.size} requests")
            case to request
        }
        File(arguments[0]).writeText(render(results))
    } finally {
        context?.close()
        server.stop(0)
    }
}

/** 기록한 요청 하나다. [path]는 인코딩된 요청 경로, [authority]는 기본 포트를 뗀 소문자 `host[:port]`다. */
data class Recorded(val method: String, val path: String, val query: String?, val authority: String)

private fun record(exchange: HttpExchange, recorded: MutableList<Recorded>) {
    val uri = exchange.requestURI
    // 프록시로 받은 요청은 absolute-form이다. 아니면 Host 헤더가 원래 host다.
    val host = uri.host?.let { host -> if (uri.port == -1 || uri.port == 80) host else "$host:${uri.port}" }
        ?: exchange.requestHeaders.getFirst("Host").orEmpty()
    recorded += Recorded(exchange.requestMethod, uri.rawPath, uri.rawQuery, host.lowercase())
    val body = "{}".toByteArray()
    exchange.responseHeaders.add("Content-Type", "text/plain")
    if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1) else exchange.sendResponseHeaders(200, body.size.toLong())
    if (exchange.requestMethod != "HEAD") exchange.responseBody.use { it.write(body) }
    exchange.close()
}

/**
 * 오라클 케이스 하나다.
 *
 * @property symbol 사실의 소스 한정 이름이다(명령형 호출은 감싸는 함수, `@HttpExchange`는 인터페이스 메서드)
 * @property basePath 생산자가 base를 풀 수 없는 케이스의 base 경로다(실행 시점 값). 풀 수 있는 케이스는 null이다
 * @property pathArguments 템플릿의 `{}` 자리에 들어가는 값이다(요청 경로에 나타나는 인코딩된 모양)
 */
class Case(val id: String, val symbol: String, val basePath: String?, val pathArguments: List<String>, val invoke: (ConfigurableApplicationContext) -> Unit)

private inline fun <reified T : Any> ConfigurableApplicationContext.bean(): T = getBean(T::class.java)

private const val PKG = "dev.kartograph.fixture.springclient"

val CASES: List<Case> = listOf(
    Case("users.fetch", "$PKG.UserClient.fetchUser", null, listOf("42")) { it.bean<UserClient>().fetchUser("42") },
    Case("users.active.query", "$PKG.UserClient.activeUsers", null, emptyList()) { it.bean<UserClient>().activeUsers() },
    Case("users.status.no-slash-join", "$PKG.UserClient.status", null, emptyList()) { it.bean<UserClient>().status() },
    Case("users.delete.lambda", "$PKG.UserClient.deleteUser", null, listOf("7")) { it.bean<UserClient>().deleteUser("7") },
    Case("users.bulk.double-slash", "$PKG.UserClient.bulkPatch", null, emptyList()) { it.bean<UserClient>().bulkPatch() },
    Case("users.audit.absolute", "$PKG.UserClient.audit", null, listOf("mon")) { it.bean<UserClient>().audit("mon") },
    Case("users.dotted", "$PKG.UserClient.dotted", null, emptyList()) { it.bean<UserClient>().dotted() },
    Case("search.profiled", "$PKG.SearchClient.search", null, listOf("shoes")) { it.bean<SearchClient>().search("shoes") },
    Case("orders.list.trailing-base", "$PKG.OrdersClient.listOrders", null, emptyList()) { it.bean<OrdersClient>().listOrders() },
    Case("orders.one.slash-collapse", "$PKG.OrdersClient.order", null, listOf("5")) { it.bean<OrdersClient>().order(5) },
    Case("orders.add-item.lambda", "$PKG.OrdersClient.addItem", null, listOf("5")) { it.bean<OrdersClient>().addItem(5) },
    Case("orders.cancel.map-vars", "$PKG.OrdersClient.cancel", null, listOf("5")) { it.bean<OrdersClient>().cancel(5) },
    Case("billing.invoice.root-uri", "$PKG.BillingClient.invoice", null, listOf("inv-1")) { it.bean<BillingClient>().invoice("inv-1") },
    Case("billing.replace.exchange", "$PKG.BillingClient.replaceInvoice", null, listOf("inv-1")) { it.bean<BillingClient>().replaceInvoice("inv-1") },
    Case("billing.remove.delete", "$PKG.BillingClient.removeInvoice", null, listOf("inv-1")) { it.bean<BillingClient>().removeInvoice("inv-1") },
    Case("billing.head", "$PKG.BillingClient.invoiceHeaders", null, emptyList()) { it.bean<BillingClient>().invoiceHeaders() },
    Case("billing.create.post", "$PKG.BillingClient.createInvoice", null, emptyList()) { it.bean<BillingClient>().createInvoice() },
    Case("billing.external.absolute", "$PKG.BillingClient.external", null, emptyList()) { it.bean<BillingClient>().external() },
    Case("legacy.item.value-concat", "$PKG.LegacyClient.item", null, listOf("i-9")) { it.bean<LegacyClient>().item("i-9") },
    Case("legacy.search.uri-builder", "$PKG.LegacyClient.search", null, emptyList()) { it.bean<LegacyClient>().search("red shoes") },
    Case("legacy.segment.path-segment", "$PKG.LegacyClient.segment", null, listOf("i-9")) { it.bean<LegacyClient>().segment("i-9") },
    Case("env.ping.runtime-base", "$PKG.EnvClient.ping", "/e", emptyList()) { it.bean<EnvClient>().ping() },
    Case("catalog.item.exchange", "$PKG.CatalogApi.item", null, listOf("c-1")) { it.bean<CatalogApi>().item("c-1") },
    Case("catalog.create.exchange", "$PKG.CatalogApi.createItem", null, emptyList()) { it.bean<CatalogApi>().createItem("body") },
    Case("catalog.stock.http-exchange", "$PKG.CatalogApi.updateStock", null, listOf("c-1")) { it.bean<CatalogApi>().updateStock("c-1", "body") },
    Case("catalog.delete.exchange", "$PKG.CatalogApi.deleteItem", null, listOf("c-1")) { it.bean<CatalogApi>().deleteItem("c-1") },
    Case("inventory.stock.java-qualifier", "$PKG.InventoryClient.stock", null, listOf("sku-1")) { it.bean<InventoryClient>().stock("sku-1") },
    Case("inventory.reserve.java-lambda", "$PKG.InventoryClient.reserve", null, listOf("sku-1")) { it.bean<InventoryClient>().reserve("sku-1") },
    Case("shipping.parcel.java-constant", "$PKG.ShippingClient.parcel", null, listOf("p-1")) { it.bean<ShippingClient>().parcel("p-1") },
    Case("shipping.create.java-constant", "$PKG.ShippingClient.createParcel", null, emptyList()) { it.bean<ShippingClient>().createParcel() },
    Case("reports.daily.java-exchange", "$PKG.ReportsApi.daily", null, listOf("2026-09-30")) { it.bean<ReportsApi>().daily("2026-09-30") },
    Case("reports.reopen.java-exchange", "$PKG.ReportsApi.reopen", null, listOf("2026-09")) { it.bean<ReportsApi>().reopen("2026-09") },
)

private fun render(results: List<Pair<Case, Recorded>>): String = buildString {
    append("{\n  \"cases\": [\n")
    results.forEachIndexed { index, (case, request) ->
        append("    {\"id\": ${quote(case.id)}, \"symbol\": ${quote(case.symbol)}, \"basePath\": ${case.basePath?.let(::quote) ?: "null"}, ")
        append("\"pathArguments\": [${case.pathArguments.joinToString(", ") { quote(it) }}], ")
        append("\"recorded\": {\"method\": ${quote(request.method)}, \"path\": ${quote(request.path)}, ")
        append("\"query\": ${request.query?.let(::quote) ?: "null"}, \"authority\": ${quote(request.authority)}}}")
        append(if (index < results.lastIndex) ",\n" else "\n")
    }
    append("  ]\n}\n")
}

private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
