# HANDOFF

마지막 갱신: 2026-11-16. 작업 규칙은 [AGENTS.md](AGENTS.md), 분석 한계는 [LIMITATIONS](docs/LIMITATIONS.md)다.
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

### 2026-11-15~16 브랜치 `ccr-37904670-hxdjsx`(d133c52 기준, PR #226)

PR #226의 원격 head와 이 브랜치의 차이는 `git log origin/ccr-37904670-hxdjsx..HEAD`로 확인한다. 41ac472(1차 리뷰 반영)의 CI는
`Android fixtures and release metadata`만 실패했다(아래 host ASM 문제). 그 수정과 2차 리뷰(N1·S1'·nit) 반영이 그 위에 있다.

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
  - 수정(`OwnedTestKitHome`): 소유 확인은 이미 소유로 확인한 daemon이거나 **이번 빌드 직전 `children()` 스냅샷에 없던** 테스트
    JVM의 살아 있는 자식 handle로 하고(리뷰 S1: 테스트가 띄운 프로세스나, 빌드 사이에 다른 fixture가 띄운 daemon은 소유가
    아니다), 이후 비교는 같은 JVM handle로만 한다
    (JVM 사이 시각 비교 제거, stop 전 실패 분기 제거). 정상 stop이 어떤 이유로든 실패하면 소유가 확인된 daemon만
    `destroyForcibly`로 끝내고 종료를 기다린 뒤, 원래 실패를 cause로 담은 예외를 던진다. 그 메시지에는 JUnit 임시 폴더와 함께
    지워지는 `testkit-stop.log`의 앞 4KB를 붙인다(리뷰 S2). 실패 경로 테스트는 단언 메시지에 suppressed를 함께 보여 준다.
  - **flake를 없앤 것이 아니라 완화한 것이다.** 정상 stop이 기한을 넘기면 실패 경로 테스트는 강제 종료 덕분에 통과하고(정리 예외는
    suppressed에만 남는다), 성공 경로 테스트는 같은 상황에서 정리 예외로 실패한다. 10초를 넘는 runner 정지는 여전히 테스트를
    실패시킬 수 있다. 바뀐 것은 daemon이 테스트 뒤에 남지 않는다는 점과 실패 원인(stop log 포함)이 보인다는 점이다.
  - 회귀 테스트 4개(수정 전 모두 실패 확인): 종료 hook에 묶여 stop 후 끝나지 않는 daemon(stop log 포함 여부까지), 1초 다른
    시계로 보고한 신원, 테스트 JVM 자신을 신원으로 보고하는 fixture, 첫 빌드 전에 테스트 JVM이 띄운 형제 프로세스를 신원으로
    보고하는 fixture. `:gradle-plugin`은 로컬에서 해석되지 않아 같은 두 소스 파일을 독립 Gradle 9.8.1 harness(JDK 17)에서
    돌렸다: 수정 전 각각 실패, 수정 후 9/9 통과. 원격 CI가 최종 근거다.
  - 리뷰 nit(남은 일): 강제 종료는 daemon의 자손 프로세스를 남길 수 있다(정상 `--stop`도 같다, 회귀 아님). 필요하면
    `descendants()`도 함께 끝낸다.
- **의존성 갱신(Dependabot #210·#211·#214·#213·#224·#215·#212 대체)**:
  - ASM 9.11.1: ASM PR들은 `gradle/verification-metadata.xml`에 9.11.1 핀이 없어 5개 검사가 의존성 검증에서 실패했다. Maven Central
    원본 6개 파일을 받아 공개 SHA-1·SHA-256·SHA-512와 모두 일치함을 확인하고 핀을 추가했다. index-cost가 baseline 핀의 유지를
    요구하므로 9.10.1 핀은 남겼다(`verification-metadata-existing-pin-changed`). THIRD_PARTY_NOTICES의 ASM 행도 9.11.1로 고쳤다
    (Kotlin 행 2.4.10은 이전부터 낡았고 그대로 두었다).
  - **ASM 9.11 계산 한도(리뷰 B1)**: 9.11의 `Analyzer`는 메서드마다 기본 한도(추적 할당 400MiB, 연산 5×10¹⁰)를 두고 넘으면
    `LimitExceededException`(RuntimeException, `analyze`가 그대로 다시 던짐)을 낸다. 연산은 처리한 명령마다 그 위치의 10배씩 세므로
    분기 40개가 람다를 고르는 2만 명령 javac 메서드도 넘는다. 한도 없는 `CallbackFactScanner`가 이를 "invalid class file"로
    바꿔 snapshot·query·Gradle `kartographDead`가 종료 코드 2로 실패했다(9.10.1은 성공). 처음의 "heap을 소진할 크기에서만"이라는
    판단은 틀렸다. 수정: 다섯 `Analyzer` 생성 지점을 `AnalysisLimits.UNBOUNDED`(`setComputeLimits(Int.MAX_VALUE, Long.MAX_VALUE)`,
    API가 받는 최대치)로 통일했다. 연산 한도는 사실상 없어졌지만 **9.10.1과 완전히 같아지지는 않는다**: 추적 할당 한도는 `int`라
    끌 수 없어 메서드 분석 하나당 약 2GiB가 남고, GC와 무관하게 누적되며 예외 처리 범위 안 명령은 처리마다 frame 두 개 분량을 더
    센다. 리뷰 재현(2차, S1'): 지역 변수 800개·try/catch 하나·람다 40개 cascade(76KB)와 지역 변수 400개·중첩 try 3개 메서드는
    9.10.1 snapshot이 약 22초에 성공하지만 이 브랜치는 원인을 밝힌 메시지로 종료 코드 2다(지역 변수 400개·try 하나는 1.5~2GiB에서
    통과). 로컬 재현: 지역 변수 800개·try/catch 하나·람다 40개×문장 90개(82KB) javac 메서드는 이 브랜치 CLI가 8초에 종료 코드 2
    (`callback value-flow analysis exceeded the ASM compute limit (Too many allocated bytes); … not analyzed: method:TryLocals800#run…`),
    같은 빌드에 ASM 9.10.1 jar를 넣으면(host ASM 경로, 한도 설정 생략) 15초에 성공한다(46KB 판은 둘 다 성공). 실제
    kotlin-compiler-embeddable·KGP 2.4.21 class 41,312개는 실패 0, 사실 digest가 9.10.1과 같다(리뷰 측정, 기본 400MiB에서도).
    한도에 닿으면 RuntimeValueAnalyzer는 기존 `valueAnalysisLimits`로 세고, RuntimeFieldDemand는 기존처럼 "수요 모름"으로 돌아가며,
    계량 한계가 없는 콜백 값 흐름은 그 사실을 조용히 버리지 않고 메서드와 원인을 밝힌 `ClassIndexingException`으로 실패한다
    (`parse`가 이를 "invalid class file"로 덮지 않는다). 기존 `AnalyzerException`·`MAX_ORIGINS` 경로는 이전처럼 그 메서드 사실을
    세지 않고 버린다. 회귀 테스트(`AnalyzerComputeLimitTest` 5개, cli 1개): javac cascade 메서드는 수정 전 실패했고 수정 후 콜백
    사실이 9.10.1 결과(파라미터 쓰임 3개, 람다 빠져나감 80개, 콜백 인자 0개)와 같다. reflective class(본문·정적 helper 반환값·
    `<clinit>` 정적 필드에서 온 `Class.forName` 이름)는 한도 없이 세 호출 모두 `class:Target`으로 풀리고, 내부 seam
    (`ClassFileIndexer.runtimeLimits`)으로 낮춘 연산 한도에서는 실패 없이 계량 한계 3개로 센다(RuntimeValueAnalyzer·RuntimeFieldDemand의
    한도 catch 경로). 낮춘 할당 한도에서 콜백 분석 메시지가 메서드와 `Too many allocated bytes`를 밝히는 것도 확인한다.
  - **plugin은 host build의 ASM에서 돈다(41ac472 CI 실패)**: `Scripts/verify-gradle-plugin-fixture.sh`의
    `:app:kartographDeadDebug`가 `NoSuchMethodError: Analyzer.setComputeLimits(int, long)`로 실패했다. false-positive-corpus는
    AGP 9.3.2를 root `plugins { … apply false }`(부모 classloader)에, kartograph plugin(ASM 9.11.1을 내장한 JAR)를 `:app`(자식)에
    올린다. Gradle classloader는 부모를 먼저 보므로 `Analyzer`는 AGP의 9.11 이전 ASM에서, 9.11에만 있는 `LimitExceededException`은
    plugin JAR에서 와 ASM 판이 섞였다(AGP 9.3.2의 정확한 ASM 판은 Google Maven이 이 환경에서 막혀 확인하지 못했다. 메서드 부재가
    9.11 이전임을 보인다). AGP와 plugin을 한 buildscript classloader에 두는 agp-8-smoke·TestKit Android 테스트는 통과했다(그쪽에서는
    plugin JAR의 ASM이 이긴 것으로 보인다). 수정: `AnalysisLimits`가 실행 중인 `Analyzer`에서 `setComputeLimits`를 한 번 reflection으로
    찾아 있을 때만 부르고(9.11 이전 ASM에는 한도 자체가 없어 이미 무제한이다), 한도 초과는 `LimitExceededException`을 직접 참조하지
    않고 class 이름으로 구분한다. index·analysis·export·cli·core bytecode에 9.11 전용 class·메서드 참조가 없음을 constant pool로
    확인했다(남은 것은 reflection·이름 비교용 문자열뿐). 회귀 테스트 `LegacyAsmRuntimeTest`(테스트 전용 `legacyAsmTestRuntime`
    구성으로 ASM 9.10.1 jar를 받음): 격리 classloader(9.10.1만)와 부모(9.10.1)·자식(kartograph+9.11.1) 구성 둘 다 수정 전
    `NoClassDefFoundError: LimitExceededException`·CI와 같은 `NoSuchMethodError`로 실패했고, 수정 후 cascade·reflective 입력의 외부 호출
    (풀린 대상 포함)·콜백 사실·간선·runtime 관측이 테스트 classpath(9.11.1)의 결과와 문자열로 같다. 즉 Android build에서 plugin
    결과는 내장 ASM이 아니라 AGP가 올린 ASM이 정한다(그 ASM에는 한도가 없어 위의 할당 한도 실패도 없다).
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
  의존성 검증이 새 ASM 핀으로 해석됨). ASM 동등성: 같은 production class root 5개에 대한 `graph --format json`·`ndjson`(콜백 사실을
  모으지 않는 경로)과, 같은 root에 cascade class를 더한 `snapshot`(v1 265MB·`--compact` 46MB)·live `query` 3개 심볼×depth 1/2·
  `--graph-file` query가 ASM 9.10.1(d133c52 빌드)과 이 브랜치에서 모두 바이트 동일했다. 같은 입력의 index parse 시간은 부하
  6~19에서 두 묶음의 중앙값 비율이 1.26·0.88로 잡음이 커 차이를 판정하지 못했다(시간 판정은 CI performance가 근거). `Scripts/verify-cli-contract.sh`(`LC_ALL=C.UTF-8`)·`verify-agent-surface.sh` 통과.
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
5. 콜백 값 흐름 분석의 계량 한계: 지금은 ASM 추적 할당 한도(메서드당 약 2GiB, 끌 수 없음)에 닿으면 원인을 밝혀 실패한다. 즉
   9.10.1이 끝내던 큰 try 범위·많은 지역 변수 메서드가 CLI에서 종료 코드 2가 될 수 있다(위 S1' 재현). 기존 `AnalyzerException`·
   출처 집합 상한(`MAX_ORIGINS`)은 그 메서드 사실을 세지 않고 버린다("unanalyzed"로 이어진다). 버린 메서드를 snapshot·query 한계로
   세는 방식으로 바꾸려면 출력 계약이 바뀌므로 자매 저장소 공지와 함께 다룬다. 할당 한도를 피하려면 콜백 스캔에도 frame 예산을
   두는 방법이 있다(보존 영향 검토 필요).
7. Gradle plugin JAR는 ASM을 relocation 없이 내장해, Android build에서는 AGP가 부모 classloader에 올린 ASM이 쓰인다(판이 섞일 수
   있다). 결과가 host ASM에 좌우되지 않게 하려면 plugin JAR의 ASM relocation(shading)을 검토한다(배포물·라이선스 고지·SBOM 영향).
6. 리뷰 nit: CI의 `actions/upload-artifact` 고정 11곳에 버전 주석이 없다. `actions/setup-java` v6.3.0(d1374f8)이 이미 나왔다
   (이 PR은 Dependabot #224와 같은 6.2.0). 역방향 9.8.2→9.8.1 wrapper 전환을 직접 거부하는 테스트는 없다(집합 소속으로 거부된다).

## 로컬 검증 메모

- 저장소 toolchain은 JDK 17이다. 17이 없는 환경은 `-Porg.gradle.java.installations.paths=<JDK17 경로>`로 명시한다.
- `dl.google.com`이 막힌 환경에서는 AGP가 필요한 `:gradle-plugin`이 해석되지 않는다. 그 모듈의 컴파일·TestKit은 CI가 근거다.
- 비 BMP 파일 이름을 쓰는 `AndroidResourceCaptureTest`는 POSIX locale에서 `InvalidPathException`으로 실패한다. `LC_ALL=C.UTF-8`로 돌린다.
- `JAVA_TOOL_OPTIONS`가 설정된 환경(프록시 주입 등)에서는 JVM이 stderr에 `Picked up ...`을 써서 `Scripts/tests/test_index_cost.py`의
  실제 java probe 테스트가 `invalid-probe-output`으로 실패한다. CI에는 없는 환경 요인이다.
- 이 환경에서 curl의 `downloads.gradle.org`(`.sha256` 파일)와 Actions artifact blob 저장소 접근은 프록시가 막는다(wrapper JVM의
  배포 ZIP 다운로드는 됐다). Gradle checksum은 gradle.org/release-checksums, action tag는 `git ls-remote`로 확인한다. `OwnedTestKitHome`은 AGP가 필요 없어 두 소스 파일을
  `gradleTestKit()`·`kotlin("test")`만 쓰는 독립 Gradle 프로젝트로 옮겨(`kartograph.test.gradleHome` system property 필요) 돌릴 수 있다.
