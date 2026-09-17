# HANDOFF

새 세션이 이어받기 위한 문서다. 작업 규칙은 [AGENTS.md](AGENTS.md), Claude Code 전용 사항은 [CLAUDE.md](CLAUDE.md). 이 파일은 **지금 어디까지 왔고 다음이 무엇인지**만 담는다.

마지막 갱신: 2026-09-17 (기준 `origin/main` 7b210c9, `VERSION` 0.10.0; 로컬 문서 통합)

## 목표

Kotlin/Android 코드베이스의 의존성 그래프를 컴파일러 산출물에서 만들고, 그 위에서 미사용 코드·순환·레이어 규칙·지표·변경 영향을 근거와 함께 답하는 CLI와 Gradle plugin. [cartograph](../cartograph)(Swift)의 자매. 제품 범위는 `docs/PRD.md`.

## 현재 상태

- 0.10.0은 공개됐고(GitHub Release·Plugin Portal, 독립 설치 검증 완료), main에는 `CHANGELOG.md` Unreleased가 쌓여 있다:
  Expo Modules 스캔(#67), 병렬 fingerprint(#61)와 그 리뷰 수정(#64), `impact` 결함 2건(#59), EventChannel(#60). **0.10.1 릴리스 후보 상태**다.
- 2026-09-16~17에 머지된 PR: #59 impact 결함 → #60 EventChannel → #61 fingerprint 병렬화 → #62 단계 B 기각 기록 → #63 과제 종료 결론 →
  #64 3관점 리뷰 반영 → #65 JDK 17/21 재측정 → #66 README 퇴고 → #67 Expo Modules(다른 세션) → #68 HANDOFF 완료 절 → #69 HANDOFF 갱신.
- 열린 PR: #47 Dependabot `jvm` 2.4.10→2.4.20(2026-09-17 재조회). 다음 착수 시 상태를 재확인하고 `gradle/verification-metadata.xml` 체크섬을 검토한다. Kotlin metadata 라이브러리 갱신과 JVM 플러그인 갱신은 별개다.
- 사용자 요청으로 `origin/main` 7b210c9에서 `docs/integrate-local-handoff`를 만들고 로컬 문서 변경을 통합했다. `AGENTS.md`의 산출물 정리·근거 보존 규칙과 `docs/PHASE4-AGENT.md`의 EventSink 미스캔 설명을 유지했다. 이 문서 통합의 리뷰·PR·머지는 승인됐으며 실제 완료 여부는 해당 PR 상태로 확인한다.
- 기존 344줄 HANDOFF와 로컬 문서 2개의 원문은 stash `96a029edea8810d6c5a8d01efad2334f18686d44`(`preserve-local-docs-before-integration-20260917`)에 보존했다. 필요한 원문은 `git show 96a029e:HANDOFF.md`로 확인한다. 최신 문서 위에 stash 전체를 적용해 과거 상태로 되돌리지 않는다.
- 근거·측정 산출물은 `.claude/worktrees/perf-fingerprint-parallel/build/reports/`에 있다. 등록된 보조 worktree 3개는 모두 보존했다. 추가 제품 코드 반영은 필요하지 않았다(아래 점검 기록).

## 완료 — 한 클래스 변경 속도 과제 (2026-09-16~17, PR #61~#66)

- 결론은 `docs/ONE-CLASS-CHANGE-DESIGN.md` §9~§13에 있다. **self(392 class) 15% 내부 목표(one-class/full ≤ 0.85)는 미달로 기록**했다:
  7회 중앙값이 실행마다 0.82~0.86에서 흔들려 안정적으로 만족하지 못했고(미달 실행 0.859·0.851 보존), 원인은 고정비 구조다. 기록을 그대로 두고 과제를 닫았다.
  nia(227 JAR)는 fingerprint 병렬화(#61)로 full 2.56→1.84 s, warm 2.13→1.40 s(모두 JDK 17, 9/16 측정).
- 단계 B(C1-only JIT, #62)는 nia 실행시간 +11~17%로 기각. 단계 C(전역 분석 재사용)는 캐시 on/off 동일 계약 위험 대비 이득이 작아 착수하지 않음(#63).
- 3관점 리뷰(보안·구조·성능) 반영(#64): symlink 검사/열기 원자화(`NOFOLLOW_LINKS`), interrupt 후 spool 정리 동기화, 크기 미상 JAR spool 제외, stat 통합.
- **JDK 21 이상에서 fingerprint가 크게 빠르다**(#65): Apple Silicon JDK 17 빌드에는 SHA-256 intrinsic이 없다. #64까지 머지된 같은 바이너리를 9/17에 재측정해
  nia full은 JDK 17에서 1.92 s, JDK 21에서 1.41 s(warm 1.44→0.95 s). 9/16의 1.84 s와 9/17의 1.92 s는 같은 JDK 17의 서로 다른 날 측정값이며 노이즈 범위다.
  벤치 러너는 과거 기록 비교를 위해 JDK 17 고정을 유지한다. `docs/INDEX-CACHE.md`에 권고를 적었다.
- README 영문·한글 퇴고(#66). 사실·링크·명령은 그대로.
- 근거 원본(캡처 JSON은 sha256 대조 후 gzip, 아래 용량은 gzip 후): worktree `.claude/worktrees/perf-fingerprint-parallel/build/reports/` 아래
  `benchmark-20260916-parallel/`(305 MB), `benchmark-20260916-stage-b/`(102 MB), `benchmark-20260917-jdk21/`(204 MB), 각 README 참조.
  nia 고정 입력(Gradle transform 캐시)이 두 번 사라져 Gradle 9.6.1로 NIA classpath를 재해석해 복원했다. 절차는 메인 체크아웃의
  `build/reports/benchmark-20260916/README.md` "입력 복원" 절에 있고, 그때 쓴 배포본은 worktree의 `build/tools/gradle-9.6.1`(151 MB)에 남겨 두었다.
- 후속이 있다면: cold/full(1.05~1.09)의 병목은 digest가 아니라 spool 쓰기·header 파싱으로 보이며 미측정이다(설계 노트 §12 성능 리뷰 B3).

## 효과가 있었던 방식

- **측정으로 가설을 먼저 기각한다.** 기존 벤치 stderr의 `--timings`를 집계하자 "전역 분석이 지배적"이라는 인계 가설이 nia에서 틀렸고(fingerprint 62%), 설계가 그 자리에서 바뀌었다. 재실행 없이 집계만으로 충분했다.
- 같은 러너·같은 입력·7회 중앙값을 고정하고 기준선/후보를 **번갈아** 실행해 순서 편향을 상쇄한다. 미달·실패 실행도 지우지 않는다. 그 덕에 1차의 self warm 미달이 부하 노이즈였음을 2차로 가릴 수 있었다.
- 병렬화는 "값 동일"을 타입으로 강제한다: 계획(파일 단위 작업) → digest → 입력 순서 결합. 독립 구현으로 golden digest를 테스트에 박아 두면 리뷰어가 값 불변을 스스로 검증한다.
- 리뷰는 관점을 나눠 받는다(보안·구조·성능 에이전트 + GLM). 지적은 코드로 재확인해 결함/취향으로 갈라 PR 코멘트 표로 남긴다. GLM 질문에 "지켜야 할 불변식 목록"을 넣으면 공격 지점이 정확해진다.
- 작은 파일 수백 개에는 stat을 더하지 않는다(멤버당 `Files.size` 하나가 self full을 +18 ms). 큰 독립 파일(JAR)만 크기순으로 먼저 배정한다.
- DECISION/설계 문서에는 소스나 심볼 이름 대신 집계값과 재현 조건만 남긴다(공개 저장소, 도그푸딩 대상은 비공개).

## 효과가 없었거나 주의할 것

- **C1-only JIT(`-XX:TieredStopAtLevel=1`)는 기각**: nia 실행시간 +11~17%(SHA-256 루프가 C2 없이 느려짐), self는 비율이 오히려 악화. 캐시 stat/버퍼 튜닝·JSON 임시 문자열 감소도 과거에 효과 없었다(`build/reports/product-limits-20260914/`).
- Apple Silicon **JDK 17 빌드에는 SHA-256 intrinsic이 없다**(Homebrew·Temurin 모두). 벤치 러너는 과거 비교를 위해 JDK 17 고정이라 절대값은 JDK 21보다 느리다. `sysctl hw.optional.arm.FEAT_SHA256=1`인데도 그렇다.
- nia 고정 입력(`~/.gradle/caches/9.6.1/transforms`)은 **두 번 사라졌다**. 벤치 전에 256개 경로 존재를 먼저 확인하고, 없으면 Gradle 9.6.1로 재해석한다(완료 절의 경로).
- 이 호스트는 상시 부하(Chrome·devin·VM)로 load < 2를 거의 못 맞춘다. 대기는 10분까지만 하고, 부하 조건임을 로그에 남기며, 비교는 같은 조건의 교대 실행 안에서만 한다.
- 호스트 메모리가 부족하면 시스템이 Gradle을 강제 종료한다. `--max-workers=2`로 재실행했다.
- Kotlin에서 `Path`는 `Iterable<Path>`라 `jars + jars[2]`가 경로 요소를 이어 붙인다(`listOf(jars[2])`로). `sortedByDescending { Files.size(..) }`는 비교마다 stat을 부른다(키 선계산).
- worktree 격리 훅은 `git -C`·복합 명령·heredoc을 거부한다. 편집·커밋 메시지·PR 본문은 파일로 만들어 단순 명령으로 실행한다.
- 로컬 표본 5개 중 4개는 의존성 다운로드 없이는 빌드되지 않는다. cartograph에서 배운 것(`../cartograph/HANDOFF.md`)도 그대로 적용된다: mtime을 신선도로 쓰지 말 것, 가지치기 목록 두 벌 만들지 말 것, 테스트를 일부러 부숴 볼 것.

## 다음 할 일 (순서대로)

1. Dependabot #47(`jvm` 2.4.20): `./gradlew --write-verification-metadata sha256 ...`로 체크섬 후보를 만들어 좌표·출처를 검토하고, GLM 리뷰·CI 통과 후 **사용자 승인을 받아** 머지한다. CI 실패 시 원인은 대개 verification-metadata 누락이다.
2. 0.10.1 릴리스 여부를 정한다. Unreleased에 제품 변경(Expo, 병렬 fingerprint, impact 결함 수정)이 있고 검증 절차는 `docs/AGENT-WORKFLOW.md` 릴리스 절과 `Scripts/verify-release-readiness.sh`다.
3. `docs/integrate-local-handoff` PR의 실제 머지 상태를 확인한다. 완료된 통합은 반복하지 않고 원본 stash와 보존 worktree는 유지한다. 로컬 근거나 `.claude/` 전체를 stage하지 않는다.
4. 선택 과제(착수 전 사용자 합의): (a) cold/full 1.05~1.09의 병목으로 추정되는 spool 쓰기·header 파싱 시간 계측(설계 노트 §12 B3), (b) README 설치 절에 "JDK 21 이상에서 fingerprint가 빠르다" 한 줄 추가 여부, (c) Expo `requireOptionalNativeModule`의 선택적 부재 의미(#67 세션의 별개 이슈).
5. 하지 않기로 한 것: self 15% 목표를 위한 단계 C(전역 분석 재사용). 근거는 `docs/ONE-CLASS-CHANGE-DESIGN.md` §11.

## 재개 프롬프트

저장소 루트에서 HANDOFF.md와 적용 AGENTS.md를 읽고 `git status --short --branch`를 확인해줘. 기준 main 7b210c9까지 PR #59~#69는 반영됐고, 한 클래스 변경 속도 과제는 미달 기록을 유지한 채 종료됐으니 반복하지 마. `docs/integrate-local-handoff` PR 상태를 확인해 완료된 통합은 반복하지 말고 원본 stash와 등록된 보존 worktree는 유지해. 새 작업은 나와 범위를 합의하고, Dependabot #47은 원격 상태·verification-metadata부터 확인해. PR·외부 리뷰·머지·릴리스는 각각 승인 범위를 지켜줘. 측정 근거와 복원 방법은 아래 기록을 참고해.

## 0.1.x 구현 이력

- 커밋 `3886900 docs: Phase 0 원천 결정 완료`
- 주 원천은 **ASM JVM bytecode + 공식 `kotlin-metadata-jvm`**이다
- Analysis API Standalone과 KSP/FIR processor는 v0.1 제품 의존성에서 제외한다
- 다섯 익명 로컬 표본과 공개 nowinandroid를 빌드·측정했다. 상세 숫자와 못 보는 것은
  `docs/DECISION-truth-source.md`
- Phase 0 일회성 probe 코드는 결과를 문서로 옮긴 뒤 제거했다
- Gradle 9.6.1 wrapper와 6개 모듈(`core`, `index`, `analysis`, `export`, `cli`, `gradle-plugin`) 골격이 있다
- CLI 종료 코드 `0/1/2/64`와 `Scripts/verify-cli-contract.sh`가 있다. `graph --classes <root>
  --format dot`는 indexer와 renderer를 실제 실행한다
- `core`에 `NodeId`, `GraphNode`, `GraphEdge`, `EdgeKind`, `CodeGraph`가 있다. dangling edge 제거,
  중복 weight 병합, 결정적 정렬, 방향별 인접 목록, 공통 `impliesUsage` 술어를 테스트한다
- `index`의 `ClassFileIndexer`가 실제 Kotlin class에서 class·method·field와 call·field access·
  inheritance·annotation·member 관계, source file/line을 읽는다. class name이 같은 중복 root는 첫 번째
  산출물만 사용하고 깨진 class에서는 부분 그래프를 반환하지 않는다. 잘린 class/JAR도 stack trace 대신
  정제된 도구 실패로 처리하며 declared/catch/multidimensional-array type 참조를 인덱싱한다
- 공식 `kotlin-metadata-jvm`이 Kotlin internal visibility, data class, object, extension receiver,
  backing property를 JVM 정점에 보강한다. metadata가 없는 Java class는 access flag를 visibility로 쓴다
- `export`의 `DotGraphRenderer`가 정렬·escape·shape·합성 style·edge weight를 보존한 DOT를 만든다.
  source path는 출력하지 않는다
- local sample E debug class root dogfood: exit 0, 408 nodes, 996 edges, 0.16초. DOT에 절대경로 없음.
  로컬에 Graphviz 실행 파일이 없어 외부 parser 검증은 건너뜀
- Kover 0.9.9 root 집계 line coverage는 91.2456%이고 `:koverVerify`가 90%를 강제한다.
  GitHub Actions는 clean test, coverage, CLI distribution 계약을 실행한다
- `fixtures/false-positive-corpus`는 실제 Android app으로 39개 retained case와 실제 미사용 2건을 담는다.
  verifier는 전체 finding exact 비교와 각 근거 파일·줄을 양방향으로 확인한다
- manifest `activity-alias` target과 `FragmentContainerView android:name`, nested binary class 이름을
  보존하며 element-level member type wildcard keep rule은 조용히 오해석하지 않고 거부한다
- `AndroidManifestScanner`와 `AndroidXmlScanner`가 class ID + `RetentionReason` + project 상대 파일/줄을
  반환한다. XML parser는 DTD/external entity를 끄고 project root 밖 실제 경로를 거부하며, 멀티라인
  시작 태그에서도 실제 class 값의 줄을 가리킨다
- ASM이 class/method/field annotation 이름을 정점에 보존하고 `KeepAnnotationRetention`이 AndroidX
  `@Keep`을 보존 root로 바꾼다. member에 붙은 경우 소유 class도 `KEEP_ANNOTATED_MEMBER` 근거로 보존한다
- `ReachabilityAnalyzer`가 공통 `impliesUsage` 간선으로 결정적 BFS 경로를 만들고, `dead --strict`와
  `dead --explain`이 실제 Android 코퍼스의 전체 retain/report 집합을 정확히 판정한다
- 반복 가능한 `--keep-rules`가 class/interface/enum `-keep` 규칙을 파일·줄 근거로 읽는다. `*`/`?`/`**`
  wildcard, `extends`/`implements` 전이 상속, public/protected/private JVM visibility를 지원하며 외부
  상위 타입 이름도 정점에 보존한다. `allowshrinking`과 이름만 보존하는 규칙은 root가 아니며,
  `final` 등 추가 access flag·조건부 등 미지원 문법은 조용히 무시하지 않고 실패한다.
- GLM 리뷰에서 외부 라이브러리의 2단계 이상 상속 chain이 끊기는 major를 확인했다. 현재는 해당 규칙의
  판정을 거부해 false positive를 막았다. 같은 리뷰의
  annotation member/include 오진, 멀티라인 상속 묵살, 동일 evidence 중복 지적은 회귀 테스트와 함께 수정했다
- 반복 가능한 `--classpath`가 directory/JAR의 class header만 별도 `ClassHierarchy`로 읽는다. app graph와
  dependency 정점을 섞지 않으면서 외부 전이 상속 규칙을 해결한다. 실행 JDK hierarchy와 multi-release
  JAR의 적용 가능한 최고 version도 처리하며, 실제 AGP app+library 코퍼스가 이를 검증한다
- `-include`와 `@file`을 포함 파일 기준으로 재귀 해석하고 cycle을 거부한다. project 규칙은 project
  realpath, 명시적 외부 규칙은 그 parent realpath 안에서만 include할 수 있다. library consumer rules도
  반복 가능한 `--keep-rules`로 합친다
- annotation class specification을 ASM annotation 이름과 wildcard로 매칭한다. 알려진 비보존 directive만
  무시하고 알 수 없는 directive는 오타로 간주해 실패한다
- annotation `-keepclasseswithmembers` 조건이 실제 graph member를 검사하고 class와 matching member를
  모두 root로 만든다. AGP 9.3.2가 생성한 기본 optimize 규칙까지 읽는다
- JVM `final`/`abstract`/`synthetic` access flag와 negation을 class specification에 적용한다. source
  visibility와 JVM flag는 분리되어 있고 nested visibility는 `InnerClasses` attribute에서 읽는다
- plain `-keep { *; }`는 class와 직접 member를 모두 root로 만든다. method/field instruction의 owner class
  `REFERENCE`도 추가해 keep된 member가 유일하게 쓰는 class가 보고되지 않는다
- 공통 `DefaultRetention`이 CLI와 Gradle plugin의 keep·annotation·generated·runtime 정책을 한곳에서 조립한다.
  Moshi/Room sibling naming과 실제 Room KSP 생성물, JNI, JavaScript, Worker, Retrofit, ViewModel/WebView/Camera2
  callback, compile-time constant owner를 보존하며 모든 근거는 enum 값으로 출력된다
- override dispatch, compiler synthetic callback body, class initializer 간선을 복원한다. compiler가 기록한
  line number 0은 파일 위치를 버리지 않고 unknown line으로 다룬다
- 다섯 로컬 프로젝트에 Android/JVM main root와 실제 keep rules를 전달해 상위 10건을 수동 대조했고
  false positive 0건을 확인했다. 집계와 재현 조건은 `docs/PHASE2-VALIDATION.md`에 있다
- `baseline --write`가 line-independent fingerprint를 정렬·중복 제거한 version 1 JSON으로 기록하고,
  `dead --baseline`은 기존 finding을 strict 판정에서 제외한다. parser는 JSON delimiter가 든 경로도
  round-trip하며 malformed 문서를 부분 적용하지 않는다
- `dead --since`는 merge-base commit, staged, unstaged, 전체 repository의 untracked 경로를 NUL delimiter로
  합친다. exact project path와 JVM SourceFile basename fallback을 구분하고 위치 없는 finding은 보수적으로
  포함하며 Git 오류에는 경로나 raw ref를 싣지 않는다
- 공용 reporter가 text, Gradle, GitHub Actions, SARIF 2.1.0, sorted JSON을 만들고 모든 형식에 limitation을
  포함한다. CLI와 Gradle plugin이 같은 baseline codec과 reporter를 사용한다
- Gradle extension은 optional `baseline`과 `reportFormat`을 제공한다. task는 public AGP Variant/Artifact API만
  사용하고 report를 먼저 쓴 뒤 strict 실패하며 configuration cache fixture를 통과한다
- `query`는 cartograph `SymbolQueryDocument`와 같은 필드와 optional omission 계약을 사용한다. containment와
  usage를 분리하고 retained/retainedByMember/reachable/unreachable, baseline, depth/limit truncation을 싣는다
- query는 dead와 같은 manifest/resource/keep/classpath/baseline 입력을 받고, 현재 class/source에서 reflection,
  JNI, 동적 등록, index staleness limitation을 세어 found/ambiguous/notFound 모두에 포함한다
- `bridges`는 Flutter MethodChannel과 React Native module/method 수신 사실을 isthmus bridge-facts v1로 내보낸다.
  multiline/chained/multiple channel과 opaque handler를 버리지 않고 limitation으로 표시하며 sibling parser 검증을 통과한다
- `skill`은 검토 가능한 `Skills/kartograph/SKILL.md`를 프로젝트에 설치하고 `--force` 없이는 기존 파일을
  덮어쓰지 않는다. unreachable을 삭제 승인으로 해석하지 않는 규칙을 포함한다
- `cycles`는 module/package usage graph의 iterative Tarjan SCC와 실제 대표 경로·weakest edge를 출력한다.
  self-loop를 포함하고 strict finding 경로를 실제 두 Java package fixture로 검증한다
- `rules`는 외부 object 생성을 허용하지 않는 fail-closed YAML subset을 읽는다. 빈/중복/unknown 설정을
  거부하고 violation과 unassigned를 strict finding으로 처리하며 실제 edge kind·weight·source 위치를 싣는다
- `metrics`는 같은 `EdgeKind.impliesUsage` 위에서 module/package별 Martin Ca/Ce/I/A/D를 계산한다
- plugin을 포함한 6개 production root(2,271 nodes, 9,728 edges) self-analysis는 dead/cycles/rules 0 findings,
  rules unassigned 0, metrics 6행이며 네 명령 모두 0.32초 이하다. `docs/PHASE5-VALIDATION.md`에 근거가 있다
- `graph`와 `dead`는 반복 `--classes`를 입력 순서로 합치고 중복 JVM class는 첫 root를 사용한다. invalid
  path는 graph/dead 모두 절대경로를 노출하지 않는 usage error로 변환한다
- Gradle plugin `io.github.ictechgy.kartograph`가 AGP public `onVariants`와 scoped `CLASSES`를 사용해
  `kartographDead<Variant>` task를 등록한다. PROJECT는 graph, ALL은 dependency hierarchy로 분리하고
  merged manifest·resource source·namespace·명시 keep rules를 lazy input으로 연결한다
- 실제 composite AGP fixture가 report exact 2건, strict 실패, configuration cache 재사용을 검증한다.
  `R.jar`/`BuildConfig`/`BR`/`Manifest`는 synthesized로 제외하고 Java invokedynamic handle도 인덱싱한다
- GLM의 단독 `*` package 의미와 positive modifier OR 지적은 공식 ProGuard의 하위 호환 예외와
  non-conflicting AND/conflicting OR 규칙에 반대라 반영하지 않았다. `-keepnames` root 지적도
  `allowshrinking` 동치라 반영하지 않았다
- 최근 검증의 Kover line coverage는 91.2456%다
- `VERSION`이 CLI와 Gradle plugin artifact의 단일 0.1.1 version 원천이고 모든 archive는 timestamp와
  entry order를 고정한다. CLI distribution에는 README, changelog, security/privacy, license가 들어간다
- Gradle plugin publication metadata는 website/VCS/tags를 포함한다. plugin jar는 runtime dependency를
  모두 내장하고 POM에서는 제거해 Portal 설치가 별도 kartograph artifact나 중복 dependency에 의존하지 않는다
- `Scripts/verify-release-readiness.sh`가 clean build 두 번의 SHA-256, plugin descriptor/내장 class/POM,
  Gradle plugin validation과 압축 해제 CLI 계약을 검사한다. 외부 publish는 하지 않는다
- `v0.1.0` workflow는 전체 test/coverage/fixture/release 검증, GitHub Release와 Plugin Portal 제출까지
  성공했다. Plugin Portal의 첫 plugin 검수는 외부 승인 대기 상태다
- `CHANGELOG.md`, `SECURITY.md`, `docs/LIMITATIONS.md`와 README 설치/호환성/안전 해석을 0.1.x 기준으로 작성했다
- CI action은 commit SHA로 고정하고 Android API 36을 명시적으로 설치한다. CLI와 plugin 배포본에는
  ASM·Kotlin·JetBrains runtime dependency의 제3자 고지와 Apache/BSD 라이선스 원문을 포함한다
- GLM 전체 repository 리뷰에서 확인된 4 major를 수정했고 high-effort diff 리뷰와 후속 리뷰는 최종적으로
  blocker/major 0건을 보고했다
- 배포 tree GLM max 리뷰에서 real ProGuard directive/inline conditional block과 manifest metadata 누락을
  확인했다. 현재 branch는 두 major와 12 minor의 검증 가능한 항목을 수정했고 max diff review와 후속
  review 모두 blocker/major 0건을 보고했다
- JDK 17 clean test/coverage와 JDK 21 clean test, CLI/agent/corpus/plugin fixture가 통과했다. release verifier는
  두 clean build의 ZIP/TAR/plugin JAR/POM 해시 일치와 압축 해제 CLI 계약을 확인했다
- 작업 브랜치: `fix/v0.1.1-max-review`

## 알아 두면 시간이 절약되는 것

- JVM 모듈 클래스: `build/classes/kotlin/main`. AGP 8 Android 모듈은 주로
  `build/tmp/kotlin-classes/<variant>`, AGP 9 built-in Kotlin은
  `build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`
- 도그푸딩 대상과 특징은 익명화된 `docs/PLAN.md` 0.1 표에 있다
- Android의 보존 규칙 지식은 이미 keep 규칙으로 존재한다. Phase 2에서 손으로 쓰기 전에 `proguard-rules.pro` · consumer rules · AGP 기본 규칙을 먼저 파싱한다(`AGENTS.md`)
- 2026-09-17: `feat/event-channel-ffi` 브랜치 4커밋은 PR #60(squash `e0974c3`)으로 main에 이미 반영됐다. battery_plus 실측(`dev.fluttercommunity.plus/battery` 스트림 경계 방출)과 `./gradlew --no-daemon :index:test` 통과가 그 검증이다. JNI/native interop 파일은 `unscanned-ffi-interop` limitation으로 보고된다.
- 문서 통합 검증: `BridgeFactScannerTest` 전체를 `--offline` JDK 17로 실행해 통과했고, sink 호출을 limitation으로 세지 않는 기존 테스트 `events keeps dynamic names and proven prefixes without flagging sink calls`가 이를 고정한다.
