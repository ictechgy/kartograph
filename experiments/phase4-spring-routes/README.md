# Phase 4a — Spring route-decl 오라클 실험

`routes --role server` 사실을 실제 Spring Boot 앱의 `/actuator/mappings`와 비교한 기록이다. 결과 요약과 해석은
[Spring 서버 라우트](../../docs/SPRING-ROUTES.md#오라클-검증-2026-09-28)에 있다.

## 절차

1. 공개 앱을 저장소 밖(임시 디렉터리)에 `git clone --depth 1`하고 빌드한다. 사용한 revision은 아래 표다. 모두 Apache-2.0이며
   코드는 이 저장소에 복사하지 않았다.
2. `java -jar <app>.jar --server.address=127.0.0.1 --server.port=<port> --management.endpoints.web.exposure.include=mappings,health`로
   띄워 `GET /actuator/mappings`를 받고 프로세스를 끈다.
3. `kartograph snapshot --classes <class root> --project <app> --include-paths > graph.json` 뒤
   `kartograph routes --role server --project <app> --graph-file graph.json`(바이트코드 모드)와 `--graph-file` 없는 실행(소스 모드)을 만든다.
4. `python3 compare_mappings.py mappings.json routes.json [--context-path /ctx]`로 비교한다. 오라클은 프레임워크 패키지
   (`org.springframework.boot.`·`.web.`·`.data.`, `org.springdoc.`) 밖의 핸들러 메서드만 센다. WebFlux base-path와 서블릿
   context-path는 mappings 패턴에 없으므로 `--context-path`로 붙인다.

| 앱 | revision | 빌드 |
|---|---|---|
| spring-projects/spring-petclinic | `818c4136ea971c21674525f9053de0d9c7ad8cfe` | Gradle, Boot 4.1.0 |
| spring-petclinic/spring-petclinic-kotlin | `da08609c277f95c37dd91187867f74dbad1090f8` | Gradle, Boot 4.1.1 |
| spring-petclinic/spring-petclinic-rest | `4cd8e1b0cd42578e882247d8801f6be5d402f118` | Maven, Boot 4.x, openapi-generator |
| 합성 edge-mvc·edge-webflux | `fixtures/spring-routes-corpus/` | Gradle, Boot 4.1.0 |

## 결과(2026-09-28)

| 앱 | 바이트코드 모드 | 소스 모드 |
|---|---|---|
| spring-petclinic | 정밀도 17/17, 재현율 17/17 | 17/17, 17/17 |
| spring-petclinic-kotlin | 18/18, 18/18 | 18/18, 18/18 |
| spring-petclinic-rest | 37/37, 37/37 | 37/37, 37/37 |
| edge-mvc | 60/60, 60/64 | 60/60, 60/64 |
| edge-webflux | 16/16, 16/16 | 16/16, 16/16 |

오탐(false positive)은 모든 실행에서 0건이다. edge-mvc의 빈자리 4건은 계약상 dynamic으로 남긴 경로다(다른 프로필 전용
플레이스홀더 2, 한 세그먼트 변수 두 개 2). error 판정 가능 비율은 지금 isthmus에서 0%(문서 전체 `framework-provided-routes:`),
http limitation 스코프가 생기면 root·정적·비테스트 사실 기준 100%(edge-mvc는 60/64 사실)다.
