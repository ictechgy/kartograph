package dev.kartograph.fixture.retrofit

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 경로가 있는 리터럴 base(`https://catalog.example.com/shop/v2/`)로 만드는 합성 서비스다([catalogApi]).
 *
 * 상대 경로는 base 경로 뒤에 붙고, 앞 `/`는 host 루트로 되돌아가며, `..`는 base 경로를 거슬러 오른다(RFC 3986).
 */
interface CatalogApi {
    /** base 경로 뒤의 상대 경로다. */
    @GET("items/{id}")
    fun item(@Path("id") id: String): Call<ResponseBody>

    /** 앞 `/`는 base 경로를 버린다. */
    @GET("/health")
    fun health(): Call<ResponseBody>

    /** `..`가 base 경로의 마지막 세그먼트(`v2`)를 지운다. */
    @GET("../v1/legacy")
    fun legacy(): Call<ResponseBody>

    /** `.` 세그먼트와 끝 슬래시다. */
    @GET("./search/")
    fun search(): Call<ResponseBody>

    /** 본문이 있는 상대 경로다. */
    @POST("items")
    fun create(@Body body: RequestBody): Call<ResponseBody>
}

/** 여러 base로 만드는 합성 서비스다([InventoryClients]). 메서드마다 base별 사실이 하나씩 나온다. */
interface InventoryApi {
    /** base 경로 뒤의 상대 경로다. */
    @GET("stock/{sku}")
    fun stock(@Path("sku") sku: String): Call<ResponseBody>

    /** 앞 `/`는 어느 base에서도 host 루트다. */
    @GET("/admin/stock")
    fun audit(): Call<ResponseBody>
}
