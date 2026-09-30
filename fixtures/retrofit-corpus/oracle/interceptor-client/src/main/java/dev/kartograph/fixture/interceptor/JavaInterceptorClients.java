package dev.kartograph.fixture.interceptor;

import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.http.GET;

/** Java 빌더 지역 변수·익명 인터셉터·람다 인터셉터로 만드는 팩토리다. */
public final class JavaInterceptorClients {
    /** 익명 class 재작성 인터셉터를 붙인 client로 만드는 서비스다. */
    public interface JavaEdgeApi {
        @GET("java/ping")
        Call<ResponseBody> ping();
    }

    /** 요청을 그대로 넘기는 람다 인터셉터를 붙인 client로 만드는 서비스다. */
    public interface JavaPlainApi {
        @GET("java/status")
        Call<ResponseBody> status();
    }

    private JavaInterceptorClients() {
    }

    /** 빌더 지역 변수에 host 재작성 익명 인터셉터를 붙인다. */
    public static JavaEdgeApi edge() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.addInterceptor(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request original = chain.request();
                HttpUrl url = original.url().newBuilder().host("java.example.net").build();
                return chain.proceed(original.newBuilder().url(url).build());
            }
        });
        Retrofit retrofit = new Retrofit.Builder().baseUrl("http://java.api.example.com/").client(builder.build()).build();
        return retrofit.create(JavaEdgeApi.class);
    }

    /** 요청을 그대로 넘기는 람다 인터셉터를 붙인다. */
    public static JavaPlainApi plain() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> chain.proceed(chain.request())).build();
        return new Retrofit.Builder().baseUrl("http://java.api.example.com/").client(client).build().create(JavaPlainApi.class);
    }
}
