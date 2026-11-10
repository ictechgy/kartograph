# HANDOFF

마지막 갱신: 2026-11-10. 작업 규칙은 [AGENTS.md](AGENTS.md), 분석 한계는 [LIMITATIONS](docs/LIMITATIONS.md)다.
2026-09-30 이전 원문은 [HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)로 옮겼다. 그 안의 "진행 중"·재개 프롬프트는 현재 지시가 아니다.

## 현재 상태

- `VERSION`·CHANGELOG는 **0.26.0**(2026-11-03 준비)이다. 2026-11-04 GitHub Releases 조회 기준 최신 공개 Release는 **v0.25.0**이고
  v0.26.0 Release·Plugin Portal 게시는 확인하지 않았다. 게시 여부는 Release·Portal에서 따로 확인한다(태그·문서만으로 배포 완료가 아니다).
- 0.26.0 이후 `main`에는 release workflow 수정(#220–#222)과 #223이 있다.
- `[Unreleased]`는 PR #223(계열 9개 도구 보완)이다. 2026-11-10 `main`에 squash 머지됐다(c7c1049). 아직 release하지 않았다.
  - 라이브러리가 부르는 재정의(`Runnable.run`·`Comparator.compare`·외부 abstract callback·`Object` 계약)를 도달한 owner에서
    새 origin `libraryCallbackModel`로 잇는다. language-traversal은 이 간선을 `candidate`로 분류한다(owner-callback 아님).
    header 없는 외부 상위 타입 수는 query·snapshot 한계 `library-callback-headers:`로 센다(`dead` 출력에는 없다). 기본 `impact`는
    계층을 쓰지 않아 이 간선을 다른 간선처럼 따라간다.
  - resource XML의 class 이름 속성(`layout_behavior`·`<fragment class>`·menu action·preference `fragment`)과 namespace 기준
    상대 이름(`.Name`은 읽는 모든 속성에서 namespace 아래로 푼다. 런타임이 실제로 푸는 것은 CoordinatorLayout·Navigation뿐이라
    다른 속성은 넓게 보존될 수 있다).
  - SOURCE retention 어노테이션 class 보고 제외: count는 query·snapshot의 `source-retention-annotations:`, 정적 한계
    `SOURCE_RETENTION_ANNOTATIONS`는 dead text·JSON·plugin에 나오고, `--explain`은 숨긴 노드에 `not-reported` 줄을 더한다.
  - `@JvmOverloads` 생성 overload의 `synthesized` 표시는 같은 owner의 `@JvmOverloads` 원본에서 default 매개변수를 끝부터 뺀
    정확한 시그니처만(`@JvmStatic` bridge·직접 작성한 더 긴 같은 이름 함수 제외).
  - 인덱싱은 Android framework·라이브러리 callback 파생 간선을 dispatch 보강의 재구성에 함께 넣는다(출력 바이트 동일, 그래프
    재구성 1회 절약). performance 게이트가 1.0524배로 넘던 원인이었고 수정 뒤 1.0219배다.
  - CLI와 Gradle plugin의 dead `confidence` 규칙 공유(`FindingConfidences`), test 전용 도달 정책을 analysis로 이동.
  - `dynamicScope` 생산자 규칙과 isthmus 벡터 재벤더링(`c76e105`, `sql-relations` 추가).
- 리뷰: 이 환경에 GLM `packet-ask`가 없어 독립 Claude 리뷰로 대신했다(PR 본문 Verification에 명시). 지적 should-fix 3건과
  nit 5건을 반영했다.
- **index-cost baseline 이동(완료)**: #223 검토 중에는 고정 baseline 78153c3 대비 구조 계수·CLI 출력·semantic graph 차이를
  arm별 고정값으로만 허용하는 임시 delta가 있었다. 머지 직후 이 브랜치의 commit에서 `SOURCE_BASELINE`·`BASELINE`을 c7c1049로 옮기고
  `STRUCTURAL_DELTA_BASELINE`·고정값(`EXPECTED_PROFILE_COUNTS`·`EXPECTED_CLI`·`EXPECTED_SEMANTIC`)·`StructuralDeltaTest`를
  지웠다(33f8ce1·c10be0e와 같은 절차: delta 장치까지 제거하고 행의 `expectedContractDelta=false`·`exact-byte-comparison`은 유지).
  profile 계수·CLI 출력·semantic graph·raw payload는 다시 두 arm의 완전 동일 비교다. 엔진(index·core)을 바꾸지 않는 PR은 엔진 jar
  바이트가 같아 same-engine control로 통과한다. 다음에 의도적으로 구조를 바꾸는 PR은 같은 방식으로 임시 delta를 두거나 머지 뒤
  baseline을 다시 옮긴다(장치는 `git show c7c1049:Scripts/verify-index-cost.py`로 복원할 수 있다).

## 다음 후보

사용자가 고르기 전에는 착수하지 않는다.

0. ~~(머지 직후, 필수) index-cost baseline을 #223 병합 commit으로 옮기고 임시 고정 delta를 제거한다.~~ 완료(이 브랜치의
   baseline 이동 commit, 위 현재 상태 참고). 원격 CI의 index-cost 통과는 PR에서 확인한다.
1. **보존을 좁히는 두 후보**(검토 보고서 P1-5·P1-6, #223에서 제외): JVM private 상수만 가진 owner의 `INLINE_CONSTANT` 보존,
   패키지와 무관한 `R`·`BR`·`BuildConfig`·`Manifest` 이름 숨김. 둘 다 미사용 판정을 늘리는 방향이라 거짓 양성 위험이 있어
   보존을 넓히기만 하는 #223에 섞지 않았다. 반대 방향 코퍼스와 실제 프로젝트 델타가 먼저다.
2. `routes --role client`: OkHttp·Ktor·Volley 직접 호출 사실화, `@file:JvmName` 래퍼 연결(지금은 `route-call-coverage:`·
   `http-wrapper-unresolved`로만 남는다).
3. 함수형 라우터 등에서 `dynamicScope`를 실제로 내기(GRAPH-EXCHANGE 생산자 의무 예시). 규칙과 벡터는 이미 통과한다.
4. Gradle plugin의 test 전용 표시(`TestOnlyReachability`를 plugin에도 연결).

## 로컬 검증 메모

- 저장소 toolchain은 JDK 17이다. 17이 없는 환경은 `-Porg.gradle.java.installations.paths=<JDK17 경로>`로 명시한다.
- `dl.google.com`이 막힌 환경에서는 AGP가 필요한 `:gradle-plugin`이 해석되지 않는다. 그 모듈의 컴파일·TestKit은 CI가 근거다.
- 비 BMP 파일 이름을 쓰는 `AndroidResourceCaptureTest`는 POSIX locale에서 `InvalidPathException`으로 실패한다. `LC_ALL=C.UTF-8`로 돌린다.
- `JAVA_TOOL_OPTIONS`가 설정된 환경(프록시 주입 등)에서는 JVM이 stderr에 `Picked up ...`을 써서 `Scripts/tests/test_index_cost.py`의
  실제 java probe 테스트가 `invalid-probe-output`으로 실패한다. CI에는 없는 환경 요인이다.
