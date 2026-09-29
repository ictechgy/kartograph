package dev.kartograph.fixture.retrofit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;

/** Java static 팩토리다. base는 같은 class의 static final 상수다. */
public final class ReportsClient {
    private static final String BASE_URL = "https://reports.example.com/r/";

    private ReportsClient() {
    }

    /** 지역 변수에 담은 Retrofit으로 [ReportsApi]를 만든다. client는 오라클의 요청 전환용이다. */
    public static ReportsApi reports(OkHttpClient client) {
        Retrofit retrofit = new Retrofit.Builder().baseUrl(BASE_URL).client(client).build();
        return retrofit.create(ReportsApi.class);
    }
}
