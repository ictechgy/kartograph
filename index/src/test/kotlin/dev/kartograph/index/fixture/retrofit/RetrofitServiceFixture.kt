package dev.kartograph.index.fixture.retrofit

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** 상위 서비스다. 하위 인터페이스로 부른 호출도 이 선언에 닿아야 한다. */
interface CatalogApi {
    @GET("items/{id}")
    fun item(@Path("id") id: Int): String
}

/** 상속·suspend·@HTTP·기본 인자·overload·제네릭 매개변수를 담은 서비스다. */
interface ShopApi : CatalogApi {
    @POST("orders")
    suspend fun placeOrder(@Body payload: Map<String, Any>): String

    @HTTP(method = "DELETE", path = "orders/{id}", hasBody = true)
    fun cancel(@Path("id") id: Int): String

    @GET("search")
    fun search(@Query("q") query: String, @Query("page") page: Int = 1): String

    @GET("list")
    fun list(): String

    @GET("list")
    fun list(@Query("page") page: Int): String
}

/** 중첩 서비스다 — JVM 이름은 `Outer$NestedApi`다. */
object RetrofitOuter {
    interface NestedApi {
        @GET("nested")
        fun nested(): String
    }
}

/** 두 저장소가 같은 엔드포인트를 부른다 — 사실 하나의 usr가 두 호출자에 모두 닿아야 한다. */
class OrderRepository(private val api: ShopApi) {
    fun item(id: Int): String = api.item(id)
    suspend fun place(): String = api.placeOrder(mapOf("sku" to 1))
    fun cancel(id: Int): String = api.cancel(id)
    fun search(query: String): String = api.search(query)
    fun page(page: Int): String = api.list(page)
}

/** 같은 cancel 엔드포인트를 부르는 두 번째 저장소다. */
class AdminRepository(private val api: ShopApi, private val nested: RetrofitOuter.NestedApi) {
    fun forceCancel(id: Int): String = api.cancel(id)
    fun nested(): String = nested.nested()
}

/** 화면 상태 홀더다. 역방향 순회가 여기까지 닿아야 한다. */
class OrderViewModel(private val orders: OrderRepository, private val admin: AdminRepository) {
    fun open(id: Int): String = orders.item(id)
    suspend fun checkout(): String = orders.place()
    fun cancel(id: Int): String = orders.cancel(id) + admin.forceCancel(id)
    fun find(): String = orders.search("x")
    fun more(): String = orders.page(2)
    fun nested(): String = admin.nested()
}
