package dev.kartograph.fixture.retrofit

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * `@Path` 인코딩, 부분 세그먼트, 점 세그먼트, 전체 URL·network-path 참조를 보이는 합성 서비스다.
 *
 * `encoded = true`인 값은 `/`를 그대로 보내 여러 세그먼트가 될 수 있다. 점 세그먼트는 OkHttp `HttpUrl`이
 * RFC 3986 §5.2.4대로 지운다.
 */
interface FilesApi {
    @GET("files/{name}")
    fun file(@Path("name") name: String): Call<ResponseBody>

    @GET("docs/{path}")
    fun doc(@Path(value = "path", encoded = true) path: String): Call<ResponseBody>

    @GET("files/{name}.json")
    fun metadata(@Path("name") name: String): Call<ResponseBody>

    @GET("./status")
    fun status(): Call<ResponseBody>

    @GET("../v2/status")
    fun statusV2(): Call<ResponseBody>

    @GET(".")
    fun index(): Call<ResponseBody>

    @GET("reports/../summary")
    fun summary(): Call<ResponseBody>

    @GET("/legacy/./reports/../export")
    fun export(): Call<ResponseBody>

    @GET("https://uploads.example.com/v2/blobs/{id}")
    fun blob(@Path("id") id: String): Call<ResponseBody>

    @GET("//cdn.example.com/assets/logo.png")
    fun logo(): Call<ResponseBody>
}
