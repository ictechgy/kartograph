package dev.kartograph.fixture.retrofit;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.POST;

/** {@link LegacyApi}를 물려받은 Java 서비스 인터페이스다. */
public interface LegacyAdminApi extends LegacyApi {
    @POST("legacy/admin/reindex")
    Call<ResponseBody> reindex();
}
