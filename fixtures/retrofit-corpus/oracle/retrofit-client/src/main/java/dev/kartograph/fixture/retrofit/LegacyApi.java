package dev.kartograph.fixture.retrofit;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.HTTP;
import retrofit2.http.Path;

/**
 * Java 서비스 인터페이스다. Java 어노테이션의 명명 요소(value·method·path), 인터페이스 상수(암묵적
 * static final), 다른 class의 상수를 보인다.
 */
public interface LegacyApi {
    /** 인터페이스 필드는 암묵적으로 public static final이다. */
    String ITEMS = "legacy/items";

    @GET(value = "legacy/items/{id}")
    Call<ResponseBody> item(@Path("id") String id);

    @HTTP(method = "PATCH", path = "legacy/items/{id}", hasBody = true)
    Call<ResponseBody> patch(@Path("id") String id, @Body RequestBody body);

    @GET(ITEMS)
    Call<ResponseBody> items();

    @GET(LegacyPaths.ARCHIVE)
    Call<ResponseBody> archive();

    @DELETE("/legacy/cache")
    Call<ResponseBody> purge();
}

/** 같은 파일의 상수 class다. */
final class LegacyPaths {
    static final String ARCHIVE = "legacy/archive";

    private LegacyPaths() {
    }
}
