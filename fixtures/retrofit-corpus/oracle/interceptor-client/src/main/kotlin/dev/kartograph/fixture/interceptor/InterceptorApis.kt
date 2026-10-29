package dev.kartograph.fixture.interceptor

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** host만 바꾸는 인터셉터 class가 붙은 client로 만드는 서비스다([EdgeClients.edge]). */
interface EdgeApi {
    /** base 경로 뒤의 상대 경로다. */
    @GET("items/{id}")
    fun item(@Path("id") id: String): Call<ResponseBody>

    /** 앞 `/`로 host 루트에 붙는 경로다. */
    @POST("/orders")
    fun order(): Call<ResponseBody>
}

/** 헤더만 더하는 인터셉터가 붙은 client로 만드는 서비스다([EdgeClients.plain]). */
interface PlainApi {
    @GET("status")
    fun status(): Call<ResponseBody>
}

/** 경로 앞에 세그먼트를 붙이는 network 인터셉터 람다가 붙은 client로 만드는 서비스다([prefixApi]). */
interface PrefixApi {
    @GET("reports/{id}")
    fun report(@Path("id") id: String): Call<ResponseBody>
}

/** 속성에 담은 `Interceptor { … }` 람다가 붙은 client로 만드는 서비스다([lambdaApi]). */
interface LambdaApi {
    @GET("lambda/ping")
    fun ping(): Call<ResponseBody>
}

/** `object` 인터셉터가 붙은 client로 만드는 서비스다([objectApi]). */
interface ObjectApi {
    @GET("object/ping")
    fun ping(): Call<ResponseBody>
}

/** 재작성 인터셉터가 없는 공유 client로 만드는 서비스다([SharedClients.sharedApi]). */
interface SharedApi {
    @GET("shared/ping")
    fun ping(): Call<ResponseBody>
}

/** 공유 client를 `newBuilder()`로 복사해 재작성 인터셉터를 더한 client로 만드는 서비스다([SharedClients.derivedApi]). */
interface DerivedApi {
    @GET("derived/ping")
    fun ping(): Call<ResponseBody>
}

/** `@Provides` 재작성 client로 만드는 서비스다([InterceptorModule.edgeApi]). */
interface ProvidedEdgeApi {
    @GET("provided/items/{id}")
    fun item(@Path("id") id: String): Call<ResponseBody>
}

/** `@Provides` 일반 client로 만드는 서비스다([InterceptorModule.plainApi]). */
interface ProvidedPlainApi {
    @GET("provided/status")
    fun status(): Call<ResponseBody>
}

/** Koin 재작성 client로 만드는 서비스다([interceptorKoinModule]). */
interface KoinEdgeApi {
    @GET("koin/items/{id}")
    fun item(@Path("id") id: String): Call<ResponseBody>
}

/** Koin 일반 client로 만드는 서비스다([interceptorKoinModule]). */
interface KoinPlainApi {
    @GET("koin/status")
    fun status(): Call<ResponseBody>
}

/** 요청을 다른 host로 다시 보내는 `Authenticator`가 붙은 client로 만드는 서비스다([authApi]). */
interface AuthApi {
    @GET("secure/profile")
    fun profile(): Call<ResponseBody>
}

/** 헤더만 더하는 `Authenticator`가 붙은 client로 만드는 서비스다([tokenApi]). */
interface TokenApi {
    @GET("secure/token")
    fun token(): Call<ResponseBody>
}

/** 요청을 바꾸지 않는 `EventListener`가 붙은 client로 만드는 서비스다([listenerApi]). */
interface ListenerApi {
    @GET("events/ping")
    fun ping(): Call<ResponseBody>
}

/** 요청 host를 바꾸는 직접 구현 `Call.Factory`로 만드는 서비스다([factoryApi]). */
interface FactoryApi {
    @GET("factory/ping")
    fun ping(): Call<ResponseBody>
}
