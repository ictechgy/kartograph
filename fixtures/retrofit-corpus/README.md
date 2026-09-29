# Retrofit 요청 오라클 코퍼스

`routes --role client`의 Retrofit 규칙을 실제 Retrofit 동작에 묶는 합성 서비스다. 코드는 이 저장소를 위해 새로 쓴 것이라
외부 라이선스가 없다(저장소 라이선스를 따른다).

- `oracle/retrofit-client/src/main/{kotlin,java}`: 서비스 인터페이스. 동사 전체, `@Path`(인코딩 여부)·`@Query`·`@QueryMap`,
  상대·앞 `/`·점 세그먼트·전체 URL·network-path 경로, `@Url`, 인터페이스 상속, `suspend`, 동반 객체·파일 밖 상수, Java 명명
  요소·인터페이스 상수를 다룬다.
- `oracle/retrofit-requests.json`: 각 메서드를 Retrofit 2.12.0으로 호출해 OkHttp MockWebServer가 받은 요청(동사·인코딩된
  경로·query·원래 host)이다. `experiments/phase4-retrofit/run.py`가 만든다.
- cli `RetrofitOracleTest`가 `oracle/`을 테스트 리소스로 읽어 비교한다. 방법과 결과는
  [experiments/phase4-retrofit](../../experiments/phase4-retrofit/README.md)이다.

빌드 산출물은 커밋하지 않는다.
