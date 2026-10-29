package dev.kartograph.fixture.interceptor

import dagger.Module
import dagger.Provides
import javax.inject.Named
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * 한정자로 나눈 두 client·Retrofit을 제공하는 Dagger 모듈이다. 오라클은 Dagger 생성 코드 없이 제공 함수를 DI 그래프와 같은
 * 순서로 직접 부른다 — 스캐너가 보는 것은 어노테이션과 한정자다.
 */
@Module
object InterceptorModule {
    /** host 재작성 인터셉터다. 같은 타입의 주입 지점([edgeClient])으로 간다. */
    @Provides
    fun hostRewriter(): HostRewriteInterceptor = HostRewriteInterceptor("provided.example.net")

    /** 재작성 인터셉터를 붙인 client다. */
    @Provides
    @Named("edge")
    fun edgeClient(rewriter: HostRewriteInterceptor): OkHttpClient = OkHttpClient.Builder().addInterceptor(rewriter).build()

    /** 헤더 인터셉터만 붙인 client다. */
    @Provides
    @Named("plain")
    fun plainClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(HeaderInterceptor()).build()

    /** 재작성 client의 Retrofit이다. */
    @Provides
    @Named("edge")
    fun edgeRetrofit(@Named("edge") client: OkHttpClient): Retrofit =
        Retrofit.Builder().baseUrl("http://provided.api.example.com/").client(client).build()

    /** 일반 client의 Retrofit이다. */
    @Provides
    @Named("plain")
    fun plainRetrofit(@Named("plain") client: OkHttpClient): Retrofit =
        Retrofit.Builder().baseUrl("http://provided.api.example.com/").client(client).build()

    /** 재작성 Retrofit으로 만든 서비스다. */
    @Provides
    fun edgeApi(@Named("edge") retrofit: Retrofit): ProvidedEdgeApi = retrofit.create(ProvidedEdgeApi::class.java)

    /** 일반 Retrofit으로 만든 서비스다. */
    @Provides
    fun plainApi(@Named("plain") retrofit: Retrofit): ProvidedPlainApi = retrofit.create(ProvidedPlainApi::class.java)
}
