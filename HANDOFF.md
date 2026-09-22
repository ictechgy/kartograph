# HANDOFF

마지막 갱신: 2026-09-22

현재 재개 정보다. 규칙은 [AGENTS.md](AGENTS.md), 이전 발행·측정은
[HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 보존한다. 과거 Next Steps는 현재 권한이 아니다.

## 최신 상태 — v6 구조화 출력 실험 완료

- 이 검토 branch는 `experiment/ai-utility-v6-20260922`다. 제품 기준은 발행된 0.15.0이며 새 제품 릴리스가 아니다.
  실험 자료는 로컬 커밋으로 보존했고 원격 push/PR/머지는 하지 않았다.
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
- 필수 실험 작업은 완료됐다. 다음 후보는 동치 source 표기와 호출·행동 oracle을 사전 검증하는 별도 프로토콜이다.
  원격 반영을 요청받으면 이 branch의 diff와 기존 검증 근거를 사용한다.

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
