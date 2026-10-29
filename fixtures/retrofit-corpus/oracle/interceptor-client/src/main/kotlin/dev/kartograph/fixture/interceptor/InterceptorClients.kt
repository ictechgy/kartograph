package dev.kartograph.fixture.interceptor

import okhttp3.Call
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * 인터셉터 결합 오라클의 client와 서비스 팩토리다. client는 모두 코퍼스 안에서 만든다 — 스캐너가 인터셉터 → client → Retrofit
 * 결합을 끝까지 볼 수 있어야 인스턴스마다 판단하기 때문이다. base는 오라클의 로컬 프록시가 받을 수 있도록 `http`다.
 */
object EdgeClients {
    /** 헤더 인터셉터와 host 재작성 인터셉터가 붙은 client다. */
    private val edgeClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HeaderInterceptor())
        .addInterceptor(HostRewriteInterceptor("edge.example.net"))
        .build()

    /** 헤더 인터셉터만 붙은 client다. */
    private val plainClient: OkHttpClient = OkHttpClient.Builder().addInterceptor(HeaderInterceptor()).build()

    /** 재작성 client로 만든다 — 요청은 `edge.example.net`으로 간다. */
    fun edge(): EdgeApi = Retrofit.Builder().baseUrl("http://api.example.com/v1/").client(edgeClient).build().create(EdgeApi::class.java)

    /** 일반 client로 만든다 — 요청은 base 그대로다. */
    fun plain(): PlainApi = Retrofit.Builder().baseUrl("http://api.example.com/v1/").client(plainClient).build().create(PlainApi::class.java)
}

/** 경로 앞에 `/edge`를 붙이는 network 인터셉터 람다가 붙은 client로 만든다. network 인터셉터는 host를 바꿀 수 없다. */
fun prefixApi(): PrefixApi = Retrofit.Builder()
    .baseUrl("http://reports.example.com/v1/")
    .client(
        OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val url = chain.request().url()
            chain.proceed(chain.request().newBuilder().url(url.newBuilder().encodedPath("/edge" + url.encodedPath()).build()).build())
        }.build(),
    )
    .build()
    .create(PrefixApi::class.java)

/** 속성에 담은 람다 인터셉터가 붙은 client로 만든다. */
fun lambdaApi(): LambdaApi = Retrofit.Builder()
    .baseUrl("http://lambda.api.example.com/")
    .client(OkHttpClient.Builder().addInterceptor(lambdaHostRewrite).build())
    .build()
    .create(LambdaApi::class.java)

/** `object` 인터셉터가 붙은 client로 만든다. */
fun objectApi(): ObjectApi = Retrofit.Builder()
    .baseUrl("http://object.api.example.com/")
    .client(OkHttpClient.Builder().addInterceptor(ObjectHostRewrite).build())
    .build()
    .create(ObjectApi::class.java)

/** 공유 client와 그 복사본이다. `newBuilder()`는 원본 client를 바꾸지 않는다. */
object SharedClients {
    /** 재작성 인터셉터가 없는 공유 client다. */
    val sharedClient: OkHttpClient = OkHttpClient.Builder().addInterceptor(HeaderInterceptor()).build()

    /** 공유 client를 복사해 host 재작성 인터셉터를 더한 client다. */
    private val derivedClient: OkHttpClient = sharedClient.newBuilder().addInterceptor(HostRewriteInterceptor("derived.example.net")).build()

    /** 공유 client로 만든다. */
    fun sharedApi(): SharedApi = Retrofit.Builder().baseUrl("http://shared.api.example.com/").client(sharedClient).build().create(SharedApi::class.java)

    /** 복사본 client로 만든다. */
    fun derivedApi(): DerivedApi = Retrofit.Builder().baseUrl("http://shared.api.example.com/").client(derivedClient).build().create(DerivedApi::class.java)
}

/** 다른 host로 다시 보내는 `Authenticator`가 붙은 client로 만든다. */
fun authApi(): AuthApi = Retrofit.Builder()
    .baseUrl("http://auth.api.example.com/")
    .client(OkHttpClient.Builder().authenticator(RerouteAuthenticator()).build())
    .build()
    .create(AuthApi::class.java)

/** 헤더만 더하는 `Authenticator`가 붙은 client로 만든다. */
fun tokenApi(): TokenApi = Retrofit.Builder()
    .baseUrl("http://token.api.example.com/")
    .client(OkHttpClient.Builder().authenticator(TokenAuthenticator()).build())
    .build()
    .create(TokenApi::class.java)

/** 요청을 바꾸지 않는 `EventListener`가 붙은 client로 만든다. */
fun listenerApi(): ListenerApi = Retrofit.Builder()
    .baseUrl("http://events.api.example.com/")
    .client(OkHttpClient.Builder().eventListener(CallCounter).build())
    .build()
    .create(ListenerApi::class.java)

/** 요청 host를 바꿔 기본 client로 보내는 직접 구현 `Call.Factory`로 만든다. */
fun factoryApi(): FactoryApi = Retrofit.Builder()
    .baseUrl("http://factory.api.example.com/")
    .callFactory(
        Call.Factory { request ->
            OkHttpClient().newCall(request.newBuilder().url(request.url().newBuilder().host("factory.example.net").build()).build())
        },
    )
    .build()
    .create(FactoryApi::class.java)
