# Spring 라우트 오라클 코퍼스

`routes --role server`의 규칙을 실제 Spring 동작에 묶는 합성 앱 두 개다. 코드는 이 저장소를 위해 새로 쓴 것이라
외부 라이선스가 없다(저장소 라이선스를 따른다).

| 앱 | 스택 | 다루는 규칙 |
|---|---|---|
| `edge-mvc` | Spring Boot 4.1.0 MVC, Java + Kotlin, `server.servlet.context-path: /api/` | 클래스×메서드 곱, `ANY`, 동사 배열, 정규식 제약(int·uuid·slug·regex), `{*path}`·`/**`, narrowed, 파일 밖 상수(`import static`, Kotlin 최상위·object·companion), 플레이스홀더(기본값·저장소 값·다른 프로필 전용), 부분 세그먼트, 가운데 `*`, 끝 슬래시, Java·Kotlin 합성 어노테이션(`@AliasFor`), `@GetExchange`, 인터페이스·상위 class 상속, 빈 매핑(`""`·`/`), 오버로드, 함수형 라우터 |
| `edge-webflux` | Spring Boot 4.1.0 WebFlux, Kotlin, `spring.webflux.base-path=wf/` | base-path 정리, `suspend` 핸들러, 동사 배열, 플레이스홀더, 정규식 제약, `{*tail}`, `@HttpExchange` 인터페이스, `coRouter` |

- `expected-mappings.json`: 각 앱을 `--server.address=127.0.0.1`로 띄워 받은 `GET /actuator/mappings`에서 프로젝트 핸들러
  (className·name·descriptor·methods·patterns)와 프레임워크 술어만 남긴 기록이다(2026-09-28, Spring Framework 7.0.8, JDK 21).
- `expected-routes.tsv`: 위 패턴에 접두사(`prefix`)를 붙여 `experiments/phase4-spring-routes/compare_mappings.py`의
  `canonical()`로 정규화한 (동사, 템플릿, 핸들러) 목록이다. 추출기와 독립인 파이썬 구현이다.
- `SpringRouteCorpusTest`가 소스 원천 추출 결과를 이 목록과 대조한다(정밀도 100%, 재현율 빈자리는 이유와 함께 고정).

다시 기록하려면 앱 디렉터리에서 Gradle 9.7(`gradle -p <app> bootJar`)로 빌드하고, 저장소 밖에서 실행해 mappings를 받은 뒤
위 두 파일을 같은 방식으로 다시 만든다. 빌드 산출물은 커밋하지 않는다.
