package dev.kartograph.fixture.retrofit

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.OPTIONS
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

/** 같은 파일 최상위 상수다. 경로 인자와 문자열 템플릿 안 치환을 함께 확인한다. */
private const val USERS = "users"

/**
 * 동사 어노테이션 전체와 매개변수 어노테이션이 경로 템플릿을 바꾸는지(바꾸지 않는지) 보이는 합성 서비스다.
 *
 * `@Query`·`@QueryMap`·어노테이션 안 query 문자열은 경로를 바꾸지 않고, 앞 `/`는 base 경로를 버리고 host
 * 루트부터 다시 시작한다(RFC 3986 절대 경로 참조).
 */
interface UsersApi {
    @GET("$USERS/{id}")
    fun user(@Path("id") id: String, @Query("fields") fields: String?): Call<ResponseBody>

    @POST(USERS)
    fun create(@Body body: RequestBody): Call<ResponseBody>

    @PUT("users/{id}")
    fun replace(@Path("id") id: String, @Body body: RequestBody): Call<ResponseBody>

    @PATCH(value = "users/{id}")
    fun update(@Path("id") id: String, @Body body: RequestBody): Call<ResponseBody>

    @DELETE("users/{id}")
    fun remove(@Path("id") id: String): Call<ResponseBody>

    @HEAD("users/{id}")
    fun exists(@Path("id") id: String): Call<Void>

    @OPTIONS("users")
    fun options(): Call<ResponseBody>

    @HTTP(method = "DELETE", path = "users/{id}/sessions", hasBody = true)
    fun endSessions(@Path("id") id: String, @Body body: RequestBody): Call<ResponseBody>

    @HTTP(method = "PURGE", path = "users/cache")
    fun purgeCache(): Call<ResponseBody>

    @GET("search")
    fun search(@QueryMap filters: Map<String, String>): Call<ResponseBody>

    @GET("users?active=true")
    fun active(@Query("page") page: Int): Call<ResponseBody>

    @GET("/health")
    fun health(): Call<ResponseBody>

    @GET
    fun raw(@Url url: String): Call<ResponseBody>

    @GET("users/{id}/profile")
    suspend fun profile(@Path("id") id: String): ResponseBody

    @retrofit2.http.GET("users/{id}/avatar")
    fun avatar(@Path("id") id: String): Call<ResponseBody>
}
