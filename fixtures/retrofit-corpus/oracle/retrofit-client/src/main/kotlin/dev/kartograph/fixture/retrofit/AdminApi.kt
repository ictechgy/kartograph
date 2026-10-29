package dev.kartograph.fixture.retrofit

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET

/** 상위 서비스 인터페이스다. 하위 인터페이스로 만든 프록시도 이 선언의 어노테이션으로 요청한다. */
interface BaseApi {
    @GET("ping")
    fun ping(): Call<ResponseBody>
}

/** [BaseApi]를 물려받은 서비스다. */
interface AdminApi : BaseApi {
    @GET("admin/stats")
    fun stats(): Call<ResponseBody>
}
