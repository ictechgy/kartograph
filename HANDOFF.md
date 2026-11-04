# HANDOFF

마지막 갱신: 2026-11-04. 작업 규칙은 [AGENTS.md](AGENTS.md), 분석 한계는 [LIMITATIONS](docs/LIMITATIONS.md)다.
2026-09-30 이전 원문은 [HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)로 옮겼다. 그 안의 "진행 중"·재개 프롬프트는 현재 지시가 아니다.

## 현재 상태

- `VERSION`·CHANGELOG는 **0.26.0**(2026-11-03 준비)이다. 2026-11-04 GitHub Releases 조회 기준 최신 공개 Release는 **v0.25.0**이고
  v0.26.0 Release·Plugin Portal 게시는 확인하지 않았다. 게시 여부는 Release·Portal에서 따로 확인한다(태그·문서만으로 배포 완료가 아니다).
- 0.26.0 이후 `main`에는 release workflow 수정(#220–#222)만 있다.
- `[Unreleased]`(계열 9개 도구 보완 PR, 브랜치 `ccr-37904670-hxdjsx`):
  - 라이브러리가 부르는 재정의(`Runnable.run`·`Comparator.compare`·외부 abstract callback·`Object` 계약)를 도달한 owner에서 잇고,
    header 없는 외부 상위 타입 수를 `library-callback-headers:`로 센다.
  - resource XML의 class 이름 속성(`layout_behavior`·`<fragment class>`·menu action·preference `fragment`)과 namespace 기준 상대 이름.
  - SOURCE retention 어노테이션 class 보고 제외(`source-retention-annotations:`)와 `@JvmOverloads` 생성 overload의 `synthesized` 표시.
  - CLI와 Gradle plugin의 dead `confidence` 규칙 공유(`FindingConfidences`), test 전용 도달 정책을 analysis로 이동.
  - `dynamicScope` 생산자 규칙과 isthmus 벡터 재벤더링(`c76e105`, `sql-relations` 추가).

## 다음 후보

사용자가 고르기 전에는 착수하지 않는다.

1. **보존을 좁히는 두 후보**(검토 보고서 P1-5·P1-6): JVM private 상수만 가진 owner의 `INLINE_CONSTANT` 보존, 패키지와 무관한
   `R`·`BR`·`BuildConfig`·`Manifest` 이름 숨김. 둘 다 미사용 판정을 늘리므로 반대 방향 코퍼스와 실제 프로젝트 델타가 먼저다.
2. `routes --role client`: OkHttp·Ktor·Volley 직접 호출 사실화, `@file:JvmName` 래퍼 연결(지금은 `route-call-coverage:`·
   `http-wrapper-unresolved`로만 남는다).
3. 함수형 라우터 등에서 `dynamicScope`를 실제로 내기(GRAPH-EXCHANGE 생산자 의무 예시). 규칙과 벡터는 이미 통과한다.
4. Gradle plugin의 test 전용 표시(`TestOnlyReachability`를 plugin에도 연결).

## 로컬 검증 메모

- 저장소 toolchain은 JDK 17이다. 17이 없는 환경은 `-Porg.gradle.java.installations.paths=<JDK17 경로>`로 명시한다.
- `dl.google.com`이 막힌 환경에서는 AGP가 필요한 `:gradle-plugin`이 해석되지 않는다. 그 모듈의 컴파일·TestKit은 CI가 근거다.
- 비 BMP 파일 이름을 쓰는 `AndroidResourceCaptureTest`는 POSIX locale에서 `InvalidPathException`으로 실패한다. `LC_ALL=C.UTF-8`로 돌린다.
