# HANDOFF

새 세션이 이어받기 위한 문서다. 작업 규칙은 [AGENTS.md](AGENTS.md), Claude Code 전용 사항은 [CLAUDE.md](CLAUDE.md). 이 파일은 **지금 어디까지 왔고 다음이 무엇인지**만 담는다.

마지막 갱신: 2026-09-05

## 목표

Kotlin/Android 코드베이스의 의존성 그래프를 컴파일러 산출물에서 만들고, 그 위에서 미사용 코드 · 순환 · 레이어 규칙 · 지표를 근거와 함께 답하는 CLI. [cartograph](../cartograph)(Swift)의 자매. 자세한 것은 `docs/PRD.md`.

## 현재 상태 — 0.2.0 릴리스 작업

- 0.1.1은 공개됐다. private member opt-in PR #3은 main에 merge됐다.
- 작업 branch: `feat/pr-adoption-v0.2.0`. VERSION은 0.2.0이며 태그/배포 결과는 GitHub Releases에서 확인한다.
- `Scripts/check-pr.py`는 기준 commit baseline과 전체 그래프로 새 진단을 검사한다. PR baseline 확장을
  무시하고 untouched 파일의 새 미사용도 보고한다. Python/Git/javac/실제 배포 CLI 회귀 검증이 CI에 있다.
- nowinandroid에서 확인한 Hilt/Dagger 생성 marker·nested class와 Hilt component 보존을 추가했다.
- 공개 검증과 **아직 남은** protobuf/annotation-value/container 보고 한계는 `docs/PUBLIC-VALIDATION.md`에 있다.
- 아래는 0.1.x 구현 이력이다. 과거 커버리지·PR 상태를 현재 배포 상태로 해석하지 않는다.

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

## 다음 할 일 (순서대로)

1. 0.2.0의 최종 GLM 후속 리뷰, JDK/fixture/release/self-analysis 검증 결과와 PR CI를 확인한다
2. 승인된 공개 PR merge와 v0.2.0 tag/Release/Plugin Portal 제출을 완료하고 공개 설치 가능 여부를 별도로 확인한다
3. 다음 정확도 증분: protobuf 생성 출처, annotation 값·parameter 참조, nested type의 source container를 코퍼스부터 확장한다

## 효과가 있었던 방식

- **DECISION 문서에 소스나 심볼 이름을 적지 않고 집계값과 재현 조건만 남긴다** — 공개 저장소이고 도그푸딩 대상은 개인 프로젝트다. 이 원칙은 그 세션이 세웠고 옳다
- 오프라인 빌드가 되는 local sample C부터 측정을 시작해 기준값을 확보한 것

## 효과가 없었거나 주의할 것

- 로컬 표본 5개 중 4개가 **의존성 다운로드 없이는 빌드되지 않는다.** 오프라인 세션이면 Phase 0이 여기서 막힌다
- cartograph에서 배운 것(`../cartograph/HANDOFF.md` "효과가 없었거나 틀렸던 것") 중 여기 그대로 적용되는 것: 스토어/산출물 루트의 mtime을 신선도로 쓰지 말 것 · 가지치기 목록 두 벌 만들지 말 것 · 테스트가 실제로 무는지 일부러 부숴 볼 것

## 알아 두면 시간이 절약되는 것

- JVM 모듈 클래스: `build/classes/kotlin/main`. AGP 8 Android 모듈은 주로
  `build/tmp/kotlin-classes/<variant>`, AGP 9 built-in Kotlin은
  `build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`
- 도그푸딩 대상과 특징은 익명화된 `docs/PLAN.md` 0.1 표에 있다
- Android의 보존 규칙 지식은 이미 keep 규칙으로 존재한다. Phase 2에서 손으로 쓰기 전에 `proguard-rules.pro` · consumer rules · AGP 기본 규칙을 먼저 파싱한다(`AGENTS.md`)
