package dev.kartograph.fixture.retrofit;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

/** Java 팩토리([ReportsClient])의 지역 변수 Retrofit으로 만드는 합성 서비스다. */
public interface ReportsApi {
    @GET("reports/{year}")
    Call<ResponseBody> year(@Path("year") String year);
}
