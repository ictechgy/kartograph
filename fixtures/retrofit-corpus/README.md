# Retrofit 요청 오라클 코퍼스

`routes --role client`의 Retrofit 규칙을 실제 Retrofit 동작에 묶는 합성 서비스다. 코드는 이 저장소를 위해 새로 쓴 것이라
외부 라이선스가 없다(저장소 라이선스를 따른다).

- `oracle/retrofit-client/src/main/{kotlin,java}`: 서비스 인터페이스. 동사 전체, `@Path`(인코딩 여부)·`@Query`·`@QueryMap`,
  상대·앞 `/`·점 세그먼트·전체 URL·network-path 경로, `@Url`, 인터페이스 상속, `suspend`, 동반 객체·파일 밖 상수, Java 명명
  요소·인터페이스 상수를 다룬다. `CatalogApi.kt`·`CatalogClients.kt`·`ReportsApi.java`·`ReportsClient.java`는 리터럴 base로
  서비스를 만드는 팩토리(같은 식의 빌더, 지역 변수, `HttpUrl` 속성, base 둘)로 baseUrl 결합을 보인다.
- `oracle/interceptor-client/src/main/{kotlin,java}`: 인터셉터 결합 코퍼스다. host·경로를 바꾸는 인터셉터(class·`object`·람다·
  network 인터셉터·Java 익명 class), 헤더만 더하는 인터셉터, `newBuilder()` 복사본, Dagger `@Provides`·Koin client, `Authenticator`·
  `EventListener`·직접 구현한 `Call.Factory`로 서비스를 만든다. 모든 client를 코퍼스 안에서 만들어 스캐너가 인스턴스별로 판단한다.
  재작성 인터셉터가 `retrofit-client`의 결합을 바꾸지 않도록 따로 스캔한다.
- `oracle/retrofit-requests.json`: 각 메서드를 Retrofit 2.12.0으로 호출해 OkHttp MockWebServer가 받은 요청(동사·인코딩된
  경로·query·원래 host)이다. `interceptorCases`는 인터셉터 결합 코퍼스의 요청을 로컬 프록시로 받아 재작성 뒤 host·경로를 기록한다.
  `experiments/phase4-retrofit/run.py`가 만든다.
- cli `RetrofitOracleTest`가 `oracle/`을 테스트 리소스로 읽어 비교한다. 방법과 결과는
  [experiments/phase4-retrofit](../../experiments/phase4-retrofit/README.md)이다.

빌드 산출물은 커밋하지 않는다.
