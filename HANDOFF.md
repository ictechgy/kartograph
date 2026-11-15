# HANDOFF

마지막 갱신: 2026-11-15. 작업 규칙은 [AGENTS.md](AGENTS.md), 분석 한계는 [LIMITATIONS](docs/LIMITATIONS.md)다.
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

### 2026-11-15 브랜치 `ccr-37904670-hxdjsx`(d133c52 기준, 미push·PR 미생성)

- **TestKit daemon 정리 flake 원인과 수정**: main CI `compatibility (17)`(d133c52, job 129974581927)에서
  `OwnedTestKitHomeTest > failed build ... awaits its owned daemon`이 45행 `Expected value to be false`로 실패했다.
  - 근거: TestKit daemon은 테스트 JVM의 직접 자식이고(로컬에서 `parent()`로 확인) `onExit()`는 JDK의 reaping completion을
    돌려준다. 그래서 `close()`가 정상 반환하면 daemon은 이미 회수돼 45행이 실패할 수 없다. 즉 CI에서는 `close()`가 **던졌다**.
    실패 경로에서는 `use`가 그 예외를 `UnexpectedBuildFailure`의 suppressed로만 남기고 테스트가 출력하지 않아 원인이 사라졌다
    (실패 테스트 XML artifact는 이 환경에서 blob 저장소가 막혀 받지 못했고, 받아도 suppressed는 없다).
  - 결함: `close()`가 daemon 종료를 정상 경로에서만 보장했다. 정상 stop 전에 JVM 사이 `startInstant`를 비교해 실패하면 stop
    자체를 건너뛰었고(Linux JDK 17은 startInstant를 JVM마다 한 번 읽은 `/proc/stat` btime으로 계산함을 로컬에서 확인 — 시계가
    보정되면 같은 프로세스도 초 단위로 달라진다), stop client 실패·40초 초과·daemon 종료 10초 초과에는 소유 daemon을 그대로 둔 채
    예외만 던졌다. `gradle --stop`은 아무것도 멈추지 못해도 0으로 끝날 수 있다.
  - CI가 어느 분기를 탔는지는 증거가 없어 확정하지 못했다. 로컬에서는 stop 응답 후 daemon 종료가 34~372ms(부하 15~20, 4 vCPU)라
    10초 기한을 넘기려면 runner 정지 수준의 지연이 필요하다. 같은 JVM에서 뒤이은 3개 daemon 테스트가 통과했으므로 지속적인
    btime 어긋남은 아니다.
  - 수정(`OwnedTestKitHome`): 소유 확인은 테스트 JVM의 살아 있는 자식 handle로 하고 이후 비교는 같은 JVM handle로만 한다(JVM 사이
    시각 비교 제거, stop 전 실패 분기 제거). 정상 stop이 어떤 이유로든 실패하면 소유가 확인된 daemon만 `destroyForcibly`로 끝내고
    종료를 기다린 뒤, 원래 실패를 cause로 담은 `terminated it forcibly` 예외를 던진다. 실패 경로 테스트는 단언 메시지에 suppressed를
    함께 보여 준다.
  - 회귀 테스트 3개(수정 전 모두 실패 확인): 종료 hook에 묶여 stop 후 끝나지 않는 daemon, 1초 다른 시계로 보고한 신원,
    테스트 JVM 자신을 신원으로 보고하는 fixture. `:gradle-plugin`은 로컬에서 해석되지 않아 같은 두 소스 파일을 독립 Gradle 9.8.1
    harness(JDK 17)에서 돌렸다: 수정 전 3개 실패, 수정 후 8/8 통과, 실패 경로 반복 10회 문제 0. 원격 CI가 최종 근거다.
- **의존성 갱신(Dependabot #210·#211·#214·#213·#224·#215·#212 대체)**:
  - ASM 9.11.1: ASM PR들은 `gradle/verification-metadata.xml`에 9.11.1 핀이 없어 5개 검사가 의존성 검증에서 실패했다. Maven Central
    원본 6개 파일을 받아 공개 SHA-1·SHA-256·SHA-512와 모두 일치함을 확인하고 핀을 추가했다. index-cost가 baseline 핀의 유지를
    요구하므로 9.10.1 핀은 남겼다(`verification-metadata-existing-pin-changed`). 공개 API는 추가만 있다. `Analyzer` 기본 한도는
    CHANGELOG에 적었다. THIRD_PARTY_NOTICES의 ASM 행도 9.11.1로 고쳤다(Kotlin 행 2.4.10은 이전부터 낡았고 그대로 두었다).
  - Gradle wrapper 9.8.2: #213은 index-cost가 검토한 wrapper 전환을 9.7.1→9.8.1 하나로 고정해 `build-tool-identity-mismatch`로
    실패했다. `REVIEWED_WRAPPER_TRANSITIONS`에 9.8.1→9.8.2를 더하고 9.8.2 신원·배포 SHA256을 등록했다. 배포 ZIP SHA256과 wrapper JAR
    checksum은 gradle.org/release-checksums에서 확인했다(JAR은 9.8.1과 동일). wrapper가 이 환경에서 9.8.2 배포 ZIP을 받아
    `distributionSha256Sum`으로 검증했고, 9.8.2에서 공식 `./gradlew wrapper --gradle-version 9.8.2 --distribution-type bin
    --gradle-distribution-sha256-sum …`을 다시 돌려도 `gradlew`·`gradlew.bat`·`gradle-wrapper.jar`·properties가 바이트 그대로였다
    (gradle/gradle v9.8.1·v9.8.2 start script template도 바이트 동일).
  - `actions/setup-java` 6.2.0(`77dd5b2…`), `gradle/actions` 6.4.1(annotated tag의 commit `8da46b5…`): `git ls-remote`로 공식 tag를
    확인했다. #224의 실패는 GitHub 다운로드 500(AndroidApplicationSnapshotIntegrationTest의 kotlin-compiler 다운로드)이라 action과
    무관하다. kartograph workflow에는 파일 바이트·해시 고정 검사가 없다(실행 중 harness 해시 비교만 있다).
- **로컬 검증(JDK 17, Gradle 9.8.2)**: `:core`·`:index`·`:analysis`·`:export`·`:cli` test와 `:cli:installDist` 통과(실패 0,
  의존성 검증이 새 ASM 핀으로 해석됨). 같은 production class root 5개에 대한 `graph --format json`·`ndjson` 출력은 ASM 9.10.1(main
  clone)과 9.11.1에서 바이트 동일했다. `Scripts/verify-cli-contract.sh`(`LC_ALL=C.UTF-8`)·`verify-agent-surface.sh` 통과.
  `python3 -m unittest discover -s Scripts/tests`의 실패 5·오류 2는 main clone과 같은 환경 요인이다(smoke gate의 gradle-plugin
  class root 부재, `JAVA_TOOL_OPTIONS`). 실제 index-cost 진단 전체·smoke gate·`:gradle-plugin`·release readiness는 AGP가 필요해
  CI가 근거다. 실제 baseline(c7c1049) wrapper·metadata에 대한 `aligned_build_inputs`는 9.8.1→9.8.2·핀 6개 추가로 통과했다.

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
5. ASM 9.11 `LimitExceededException`(RuntimeException) 처리: `CallbackFactScanner`는 frame 한도 없이 `Analyzer`를 돌리고
   `AnalyzerException`만 잡는다. 지금은 9.10.1도 heap을 소진했을 크기에서만 닿는다. 잡아서 한계로 셀지는 보존이 좁아지는
   방향(callback 사실 누락)이라 반대 방향 코퍼스가 먼저다.

## 로컬 검증 메모

- 저장소 toolchain은 JDK 17이다. 17이 없는 환경은 `-Porg.gradle.java.installations.paths=<JDK17 경로>`로 명시한다.
- `dl.google.com`이 막힌 환경에서는 AGP가 필요한 `:gradle-plugin`이 해석되지 않는다. 그 모듈의 컴파일·TestKit은 CI가 근거다.
- 비 BMP 파일 이름을 쓰는 `AndroidResourceCaptureTest`는 POSIX locale에서 `InvalidPathException`으로 실패한다. `LC_ALL=C.UTF-8`로 돌린다.
- `JAVA_TOOL_OPTIONS`가 설정된 환경(프록시 주입 등)에서는 JVM이 stderr에 `Picked up ...`을 써서 `Scripts/tests/test_index_cost.py`의
  실제 java probe 테스트가 `invalid-probe-output`으로 실패한다. CI에는 없는 환경 요인이다.
- 이 환경에서 curl의 `downloads.gradle.org`(`.sha256` 파일)와 Actions artifact blob 저장소 접근은 프록시가 막는다(wrapper JVM의
  배포 ZIP 다운로드는 됐다). Gradle checksum은 gradle.org/release-checksums, action tag는 `git ls-remote`로 확인한다. `OwnedTestKitHome`은 AGP가 필요 없어 두 소스 파일을
  `gradleTestKit()`·`kotlin("test")`만 쓰는 독립 Gradle 프로젝트로 옮겨(`kartograph.test.gradleHome` system property 필요) 돌릴 수 있다.
