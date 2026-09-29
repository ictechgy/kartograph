package dev.kartograph.fixture.retrofit

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/** base URL의 host다. 템플릿 안에서 다른 상수와 이어 붙인다. */
private const val CATALOG_HOST = "https://catalog.example.com"

/** 카탈로그 base URL이다. Retrofit은 `/`로 끝나는 base만 받는다. */
const val CATALOG_BASE_URL = "$CATALOG_HOST/shop/v2/"

/**
 * 같은 식의 `Retrofit.Builder()…baseUrl(상수)…build().create(…)` 사슬로 [CatalogApi]를 만든다. [client]는 오라클이 요청을
 * MockWebServer로 돌리는 데만 쓴다 — base 결합과 무관하다.
 */
fun catalogApi(client: OkHttpClient): CatalogApi =
    Retrofit.Builder()
        .baseUrl(CATALOG_BASE_URL)
        .client(client)
        .build()
        .create(CatalogApi::class.java)

/** 두 base로 [InventoryApi]를 만드는 객체다 — 리터럴 문자열 base와, 포트가 있는 `HttpUrl` 속성 base다. */
object InventoryClients {
    /** 경로가 있는 미러 base다. 상수가 아닌 읽기 전용 속성이다. */
    private val mirrorUrl: HttpUrl = HttpUrl.get("https://mirror.example.com:8443/inv/")

    /** 경로 없는 base로 만든다. */
    fun primary(client: OkHttpClient): InventoryApi {
        val retrofit = Retrofit.Builder().baseUrl("https://inventory.example.com/").client(client).build()
        return retrofit.create(InventoryApi::class.java)
    }

    /** 미러 base로 만든다. */
    fun mirror(client: OkHttpClient): InventoryApi =
        Retrofit.Builder().baseUrl(mirrorUrl).client(client).build().create(InventoryApi::class.java)
}
