package dev.kartograph.fixture.interceptor

import okhttp3.OkHttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit

/** 한정자로 나눈 두 client·Retrofit과 서비스를 정의하는 Koin 모듈이다. 오라클은 이 모듈로 Koin을 시작해 서비스를 꺼낸다. */
val interceptorKoinModule = module {
    single(named("edge-client")) { OkHttpClient.Builder().addInterceptor(HostRewriteInterceptor("koin.example.net")).build() }
    single(named("plain-client")) { OkHttpClient() }
    single(named("edge-retrofit")) { Retrofit.Builder().baseUrl("http://koin.api.example.com/").client(get(named("edge-client"))).build() }
    single(named("plain-retrofit")) { Retrofit.Builder().baseUrl("http://koin.api.example.com/").client(get(named("plain-client"))).build() }
    single { get<Retrofit>(named("edge-retrofit")).create(KoinEdgeApi::class.java) }
    single { get<Retrofit>(named("plain-retrofit")).create(KoinPlainApi::class.java) }
}
