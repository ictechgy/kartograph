# Spring HTTP 클라이언트 요청 오라클 코퍼스

`routes --role client`의 Spring `RestTemplate`·`RestClient`·`WebClient`·`@HttpExchange` 규칙을 실제 Spring 동작에 묶는 합성 Spring Boot
클라이언트 앱이다. 코드는 이 저장소를 위해 새로 쓴 것이라 외부 라이선스가 없다(저장소 라이선스를 따른다).

- `oracle/spring-client/src/main/{kotlin,java,resources}`: 앱 소스와 기본·`prod` 프로필 설정. `@Value` base(yml·properties), 이름·
  `@Qualifier` 주입, `RestTemplateBuilder.rootUri`, base 없는 RestTemplate의 상수·`@Value` 연결과 `UriComponentsBuilder`, WebClient 끝
  슬래시 base, 빌더 람다, 실행 시점 base, `@HttpExchange`(RestClient·WebClient 어댑터)를 다룬다. host는 모두 `.example.test`다.
- `oracle/spring-client-requests.json`: 각 호출을 Spring Boot 3.5.16(Spring Framework 6.2.19)으로 실행해 로컬 프록시가 받은 요청(동사·
  인코딩된 경로·query·원래 host)이다. `experiments/phase7b-spring-clients/run.py`가 만든다.
- cli `SpringClientOracleTest`가 `oracle/`을 테스트 리소스로 읽어 비교한다. 방법과 결과는
  [experiments/phase7b-spring-clients](../../experiments/phase7b-spring-clients/README.md)이다.

빌드 산출물은 커밋하지 않는다.
