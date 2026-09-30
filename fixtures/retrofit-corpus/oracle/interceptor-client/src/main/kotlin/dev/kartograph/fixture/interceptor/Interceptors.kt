package dev.kartograph.fixture.interceptor

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** 요청에 헤더만 더한다 — URL을 바꾸지 않는다. */
class HeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Oracle", "1").build())
}

/** 요청 host를 [host]로 바꾼다. 경로·method는 그대로다. */
class HostRewriteInterceptor(private val host: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = original.url().newBuilder().host(host).build()
        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}

/** 요청 host를 바꾸는 `object` 인터셉터다. */
object ObjectHostRewrite : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().url(chain.request().url().newBuilder().host("object.example.net").build()).build())
}

/** 요청 host를 바꾸는 `Interceptor { … }` 람다다. */
val lambdaHostRewrite: Interceptor = Interceptor { chain ->
    chain.proceed(chain.request().newBuilder().url(chain.request().url().newBuilder().host("lambda.example.net").build()).build())
}

/** 401 응답에 대해 인증 헤더를 단 요청을 다른 host로 다시 보낸다. */
class RerouteAuthenticator : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val original = response.request()
        if (original.header("Authorization") != null) return null
        return original.newBuilder().url(original.url().newBuilder().host("auth.example.net").build()).header("Authorization", "Bearer oracle").build()
    }
}

/** 401 응답에 대해 같은 URL로 인증 헤더만 단 요청을 다시 보낸다. */
class TokenAuthenticator : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val original = response.request()
        if (original.header("Authorization") != null) return null
        return original.newBuilder().header("Authorization", "Bearer oracle").build()
    }
}

/** 호출 수만 센다 — `EventListener`는 요청을 바꿀 수 없다. */
object CallCounter : EventListener() {
    /** 시작한 호출 수다. */
    var started: Int = 0

    override fun callStart(call: Call) {
        started++
    }
}
