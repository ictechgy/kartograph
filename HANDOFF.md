# HANDOFF

마지막 갱신: 2026-09-22

현재 재개 정보다. 규칙은 [AGENTS.md](AGENTS.md), 이전 발행·측정은
[HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 보존한다. 과거 Next Steps는 현재 권한이 아니다.

## 최신 상태 — v8 실제 공개 모듈 효용 검증 완료

- 사용자 요청대로 실제 detekt style180파일·ktlint standard207파일에서 네 제안 변경을 사전 고정해 16회를 완료했다.
  [v8 결과](experiments/ai-utility-v8/README.md)를 따른다. 단일 제공 모듈의 실험이며 자연 발생 PR/무작위 대표 표본이 아니다.
- source8/8·MCP8/8 구조화 응답 유효, 인프라 오류0·선택적 재시작0. 원문112개·반환 객체/출력 tool input·재채점이 일치했다.
  모델612.61초·CLI 비용 추정3.5699645USD. 이전 v5/v6/v7 105개 파일은 불변이다.
- 실제 product impact3회는 모두 notFound였다. 유효한 변경 대상 경로를 받지 못했으므로 MCP 조건의 일부 높은 점수를
  graph 경로 효과로 해석하지 않는다. 별도 사후 정확한 USR 진단은 found1/partial2였으며 모델 재실행·가점은 없다.
  파일 경로만 바로잡은 한 진단은16KiB 한도에 걸렸고 단일 함수 USR로 좁혀야 했다.
- 미대응18개는 nested owner 누락16·잘못된 선언 이름2였다. line-content의 caller0점은 별도의 downstream visit를 선택한
  결과이고 원래 visitKtFile caller와 구분했다. 양쪽 모두 FunctionLiteralRuleTest의 disabled-rule 회귀를 놓쳤다.
  import 사례의 음성 대조 오선택은 source2/MCP1이었다. 일관된 효용/일반 생산성 우위를 확인하지 못했다.
- 원본 전체 test 실행: detekt3649 pass, ktlint2253 pass·11 skip. 조건별 정적 caller22·assertion 변경method21을 독립 고정했다.
  스코프 밖 상속 test1개는 계속 실행·보존하고 primary에서 분리한다. source/USR7,332개의 사례별 선언 쌍을 검증했다.
- 준비 중 observer.jar 입력 분류 오류·실제 test 이름 끝 공백 손실을 v8 adapter/collector에서만 수정했다.
  baseline 빌드 후 tracked 변경·shadowed owner를 거부하는 검사를 추가했다. 발행0.15.0 제품 바이너리는 그대로다.
- 회귀40개·실제 JUnit observer 실패 경로가 통과했다. GLM 사전 지적은 처리했고 결과 리뷰는 차단사항0이다.
  원본 리뷰·산식 오독의 교정·선택적 의견 처분을 보존한다. CI에도 v8 unit/native observer 검사·원시 증거 업로드를 추가했다.
- protocol `9f36bf7`, freeze `eaecea7`; manifest SHA256
  `d539bf1b19d16b9525939b23895d880848eacc0e2ecc35f7bb00ab3bd33868e6`.
  branch `experiment/ai-utility-v8-20260922`; 원격 반영 상태는 해당 PR의 정확한 head/CI와 merge tree로 확인한다.
- 로컬 원장 `.git/ai-utility-v8-20260922/FINAL.json`, 모델 원문 `trials-final/`, 고정 oracle `oracles-final/`.
  native 준비는 archive 상태다. 약75.10MB/38,043개 member 실제 복원·두 snapshot matched·준비 artifact2,028개를 확인했다.
  정리 후 report byte-identical. 복원은 `cleanup.json`과 parser의 v6 archive 참조를 따른다. 다른 checkout에 로컬 원장을 가정하지 않는다.
  완료된 모델 run은 재시작하지 않는다. 구체적인 후속 후보는 selector 복구·scope/페이지 안내를 실제 agent 흐름에서 검증하는 일이다.

## 최신 상태 — v7 표기 대응·호출/동작 평가 완료

- 사용자 요청의 v6 원격 통합은 [PR96](https://github.com/ictechgy/kartograph/pull/96)·`4e9f64b`로 완료했다.
  네 CI 검사 성공, merge tree는 검증 head `f94a1e8`과 같다. 제품은 발행된 **0.15.0**이며 이번 실험은 새 제품 릴리스가 아니다.
- [v7 결과](experiments/ai-utility-v7/README.md): 새 Kotlin 통제4개·16회 전부 완료. 양쪽 구조화 응답8/8 유효,
  인프라 오류0. baseline 테스트27개, 변경 후 assertion 실패12·통과15, 독립 production 호출 anchor12를 고정했다.
- 양쪽 모두 동작 예측100%·음성 대조 오선택0이다. callback 호출 coverage source80%/MCP100%는 같은 caller의
  `Function2` 표기 미대응이다. source2개·MCP1개의 미대응을 원문 대조했고 사후 재채점하지 않았다.
  작은 합성 사례의 천장 효과이며 일반 효용·caller 발견 우위를 주장하지 않는다.
- 프로토콜 `0a0749c`, 실행 전 동결 `4f62a5f`. 모델 단계345.18초·CLI 비용 추정1.7533455USD.
  원문112개·반환 객체/출력 tool input·재채점이 일치한다. v5/v6 61개 파일은 불변이다.
- 선택적 함수 타입 인자 이름 대응·typed scoring·실패 거부·raw 검증 회귀56개가 통과했고 CI에 추가했다.
  GLM 사전/결과 검토의 차단사항은 없고 보완·외부 리뷰 산식 오타 처분을 결과와 함께 보존했다.
- 작업 branch는 `experiment/ai-utility-v7-20260922`다. 원격 상태는 해당 PR의 정확한 head/CI와 merge tree로 확인한다.
  로컬 정본 `.git/ai-utility-v7-20260922/FINAL.json`, 원문 `trials-final/`·`native-1/`, 복원 `cleanup.json`을 따른다.
  다른 checkout에 이 원장이 있다고 가정하지 않는다. 이미 완료된 frozen run은 재시작하지 않는다.
- 원본 native 입력을 약343KB archive로 보존하고 2,164개 member를 실제 복원·해시 대조했다. 복원 snapshot4개 matched,
  원문183개·v5/v6 hashes 유지, 정리 후 report byte-identical이다. native base/changed 경로는 현재 archive 상태다.
  중복 parser는 v6 보존 archive의 동일6개 member를 확인하고 정리했다. 기존 전역 cache/도구/worktree는 유지했다.

## v6 완료 기록 — PR96 통합

- v6 branch는 `experiment/ai-utility-v6-20260922`이며 PR96으로 main에 통합했다. 제품 기준은 발행된 0.15.0이다.
- [v6 결과](experiments/ai-utility-v6/README.md): 새 공개 표본4개·16회 전부 완료, source8/8·MCP8/8 구조화 응답 유효,
  인프라 오류0. 원문112개 해시·원문 재채점·반환 객체와 원문 tool input·v5 보존29개를 대조했다.
- 모델 단계966.58초, CLI 비용 추정4.736082USD. 준비·smoke·리뷰는 별도다. 기존 테스트79개와 회귀32개 통과.
- 점수상 caller 차이는 source의 동치 함수 타입 표기를 고정 매칭기가 놓친 결과였다. 원문에서는 양쪽이 모두 지목했다.
  기존 원문·점수는 그대로 두고 [대조 기록](experiments/ai-utility-v6/results/source-signature-readback.json)을 분리했다.
  suite anchor79개·caller2개의 작은 표본이므로 일반 AI 효용 우위를 확정하지 않는다.
- 프로토콜 `9c124fb`, 실행 전 고정 `0e9f423`, 결과 `38bdd1d`다. GLM 코드/결과 검토의 의견 처분도 완료했다.
- 원본은 공통 Git 디렉터리의 `ai-utility-v6-20260922/archives/native-inputs.tar.gz`에 보존했다. 26,222개 member와
  실제 복원 snapshot6개 matched·원문 근거180개 해시를 확인하고 task cache를 정리했다. 원본 native 경로는 제거됐다.
  복원은 같은 원장의 `native-cleanup.json`·`native-archive-manifest.json`, 최종 상태는 `FINAL.json`을 따른다.
  다른 checkout에 이 로컬 원장이 있다고 가정하지 않는다. 완료한 frozen 실행을 재시작하지 않는다.
- 당시 후속 후보였던 동치 source 표기와 호출·행동 oracle은 위 v7의 새 프로토콜로 수행했다.

아래는 2026-09-20 당시 기록이다. 현재 제품/실험 상태와 과거 Next Steps를 혼동하지 않는다.

## 2026-09-20 당시 상태

- 현재 소스 버전은 **0.13.0**이다. [새 릴리스](https://github.com/ictechgy/kartograph/releases/tag/v0.13.0)와
  [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.13.0)은 발행 후 독립 설치 근거와 함께 확인한다.
  이전 **0.12.0**의 [PR87](https://github.com/ictechgy/kartograph/pull/87)과
  [릴리스](https://github.com/ictechgy/kartograph/releases/tag/v0.12.0)에 P1.1-2·RN target 필터 수정이
  포함됐다. 이전 버전의 GitHub·Plugin Portal·독립 설치 검증은 과거 원장에 있다.
- 0.13.0의 `dependencies`는 `--baseline`, `--suppress`, `--write-baseline`을 지원한다.
  좌표·버전·scope·제안·클래스 근거를 정확히 지문화하고, Gradle은 dependencyBaseline/
  dependencySuppress와 task baselineOutput을 제공한다. capture는 필터 전 관찰을 저장하며,
  UTC 만료일·파일 변경을 task 입력으로 확인한다. CLI·Gradle strict는 필터 뒤 진단만 센다.
  [사용법](docs/DEPENDENCIES.md)을 따른다. 억제는 제거 승인이 아니다.
- `bridges`의 generatedAt은 추출 시각으로 수정했고 최신 source mtime은 optional
  sourceModifiedAt에 보존한다. v1·Basic·Event·RN 모두 UTC 밀리초 형식을 사용한다.
  0.12.0 이하의 기본 generatedAt이 source mtime이었던 사실과 compiler freshness 한계를 구분한다.
- [processor source 귀속](docs/PROCESSOR-GENERATION.md)은 실제 JSR-269 Filer 생성/close와
  processor artifact를 관찰한다. javac-processors v2 raw evidence는 완료된 compiler/generated-source
  receipt와 일치할 때만 snapshot.processorGenerations로 들어간다. 생성기 이름 추측·자동 보존·
  dependency unused 판정 변경은 없다. 실제 generic processor·Dagger2.59와 실패 경로를 검사한다.
  0.13.0의 snapshot 연동 범위는 source-only로 유지한다. 개발 collector의 별도 output sidecar/
  runner는 javac/KAPT/KSP source·class·resource 및 지정 디렉터리 callback 중 직접 byte 변경을
  구분한다. 실제 3가지 처리 경로에서 각 4종 출력과 stale/미닫힘/깨진 source 실패 대조를 통과했다.
  직접 쓰기 관찰은 API 호출 귀속이나 다른 writer 신원의 증명이 아니다. 캐시·전체 compiler 입력·
  그래프 연동을 주장하지 않으며 [정확한 실행 범위](docs/PROCESSOR-GENERATION.md)를 따른다.
- source mtime·basename·빈 결과로 완전성을 추론하지 않는다. 공개 RN Sound.kt의 컴파일
  JVM ID·retention·원본 JS explain과 실제 Flutter macOS/Android 실행 근거는
  [isthmus 인계](https://github.com/ictechgy/isthmus/blob/main/HANDOFF.md)에 있다.
- 최종 제품 검사·compiler/Gradle 연동·GLM 처분·머지는 각 PR 기록으로 확인한다.
  로컬 후속 근거는 자매 isthmus의 `.git/remaining-all-20260920/`에 있으며 필수 설치 경로가 아니다.
- `.claude/`와 `HANDOFF.cartograph-notes.md`는 기존 사용자 미추적 파일로 보존한다.

## 다음 범위 선택

0.13.0 발행 작업은 자매 isthmus의 로컬 `.git/release-followups-20260920/`에 기록한다.
GitHub·Portal·설치 성공을 각각 확인하며 버전 파일만으로 발행을 단정하지 않는다. 개발 output collector는 아직 발행하지 않았다.
이번 원시는 자매 isthmus의 `.git/evidence-runtime-expansion-20260920/`에 있다. runtime LCOV·method 매핑,
새 output receipt의 snapshot 연동과 cache 복원은 별도 범위다. 기존 baseline/suppress·
JSR-269 source 귀속을 과거 목록 때문에 다시 구현하지 않는다. P2/P3·성능 후보는 실제 근거로 선택한다.
