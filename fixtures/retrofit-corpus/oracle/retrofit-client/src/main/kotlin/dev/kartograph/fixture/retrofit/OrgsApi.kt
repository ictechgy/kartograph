package dev.kartograph.fixture.retrofit

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

/** 동반 객체·같은 파일 object·다른 파일 object 상수가 경로에 들어가는 합성 서비스다. */
interface OrgsApi {
    @GET("$ORGS/{org}")
    fun org(@Path("org") org: String): Call<ResponseBody>

    @GET(ORGS + "/{org}/repos")
    fun repos(@Path("org") org: String): Call<ResponseBody>

    @GET(OrgsApi.AUDIT)
    fun audit(): Call<ResponseBody>

    @GET(OrgPaths.TEAMS)
    fun teams(): Call<ResponseBody>

    @GET(SharedPaths.MEMBERS)
    fun members(): Call<ResponseBody>

    @GET(SharedPaths.MEMBERS + "/{member}")
    fun member(@Path("member") member: String): Call<ResponseBody>

    companion object {
        const val ORGS = "orgs"
        const val AUDIT = "orgs/audit"
    }
}

/** 같은 파일 object 상수다. */
object OrgPaths {
    const val TEAMS = "teams"
}
