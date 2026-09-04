# AGENTS.md

이 저장소에서 작업하는 코딩 에이전트를 위한 안내입니다. 작업 규칙의 **정본**입니다.
Claude Code 전용 사항은 [CLAUDE.md](CLAUDE.md), 진행 상태와 다음 할 일은 [HANDOFF.md](HANDOFF.md)에 있습니다.

> 이 저장소는 [cartograph](../cartograph)(Swift)의 자매 프로젝트입니다. cartograph가 여섯 번의
> 릴리스와 아홉 번의 PR로 굳힌 작업 방식을 상속합니다. 원문은 `../cartograph/AGENTS.md`입니다.

폴더별 규칙이 필요해지면 그 폴더의 AGENTS.md로 나눕니다. 지금은 이 파일이 전체 코드의 경계를 정합니다.

---

## 이 프로젝트가 하는 일

Kotlin/Android 코드베이스의 의존성 그래프를 컴파일러 산출물에서 만들고, 그 위에서 미사용 코드 · 순환 · 레이어 규칙 · 지표를 **근거와 함께** 답합니다. Kotlin에는 Periphery에 해당하는 도구가 없습니다.

핵심 설계는 cartograph와 같은 한 문장입니다. **그래프가 산출물이고, 나머지는 전부 그 위의 질의입니다.**

## 모듈 경계

의존은 아래 방향으로만 흐릅니다.

```text
core <- index
core <- analysis <- export
index + analysis + export <- cli, gradle-plugin
```

| 모듈 | 책임 | 금지 |
|---|---|---|
| `core` | 그래프 모델, 설정 값 타입, 진단, 파일 시스템 추상화 | 외부 라이브러리와 파일·프로세스 접근 |
| `index` | class root 입력, ASM와 Kotlin metadata adapter | 도달성 판정과 출력 형식 |
| `analysis` | 도달성, SCC, 보존, 지표, 레이어 규칙 | 파일 접근과 렌더링 |
| `export` | DOT와 machine-readable 진단 reporter | 분석 알고리즘 |
| `cli` | 인자 파싱, 파이프라인 조립, 종료 코드, stdout/stderr | 그래프 알고리즘 |
| `gradle-plugin` | variant의 class root를 찾아 파이프라인에 전달 | 별도 분석 구현 |

`core`를 순수하게 두어 compiler 산출물 없이 분석을 테스트하고, 흔들리는 ASM/metadata/Gradle API는
adapter 모듈 밖으로 새지 않게 합니다. 여러 분석을 함께 실행할 때는 index를 한 번만 읽습니다.

## 먼저 읽을 것

1. `docs/PRD.md` — 무엇을 만들고 무엇을 만들지 않는지
2. `docs/PLAN.md` — 지금 어느 Phase인지. **Phase 0이 끝나기 전에는 제품 코드를 쓰지 않습니다**
3. `docs/RESEARCH.md` — 확인된 사실과 확인되지 않은 주장. 확인되지 않은 주장 위에 설계를 세우지 않습니다
4. `docs/DECISION-truth-source.md` — 원천 결정과 실측 근거(Phase 0 완료)

## cartograph에서 그대로 가져오는 것

취향이 아니라 실제 사고에서 나온 규칙입니다. 각각 왜 생겼는지는 `../cartograph/CHANGELOG.md`와 `../cartograph/HANDOFF.md`에 있습니다.

- **삭제 판정을 내지 않습니다.** 출력의 어떤 필드도 "지워도 된다"고 말하지 않습니다. `state`는 그래프 사실이고 `reason`은 값입니다. 에이전트는 산문보다 데이터를 믿으므로 데이터 구조의 권위로 하는 거짓말이 더 위험합니다.
- **모든 판정에 근거를 붙입니다.** `dead --explain`, `cycles --explain`, `rules --explain`.
- **분석 한계를 문서가 아니라 응답에 싣습니다.** `query`는 프로젝트에서 실제로 센 항목만 `limitations`에
  싣고 `notFound`에도 유지합니다. `dead`는 삭제 판단에 쓰이는 명령이라 계량할 수 없는 보수적 한계도 항상 알립니다.
- **종료 코드 계약**: `0` 정상 · `1` 문제 발견(`--strict`/임계값) · `2` 도구 실패 · `64` 사용 오류. 빌드된 산출물로 직접 검증하는 스크립트를 둡니다.
- **오탐 코퍼스를 첫날부터.** 실제로 빌드되는 픽스처와, 오탐 추가 · 검출 상실 양방향으로 실패하는 검증 스크립트. 단위 테스트는 손으로 만든 스냅샷을 보므로 컴파일러가 실제로 무엇을 기록하는지 검증하지 못합니다. **수정을 끄고 돌려 실패하는지 한 번은 확인합니다.**
- **베이스라인과 `--since`.** 기존 코드베이스에 도입할 수 있어야 합니다.
- **`query`와 `skill`을 처음부터.** `query` 출력은 cartograph의 `SymbolQueryDocument`와 **필드 이름까지 같아야** 합니다. 자매 도구 사이에서 소비자가 같은 파서와 같은 스킬 문장을 씁니다. 스킬은 `../cartograph/Skills/cartograph/SKILL.md`를 출발점으로 하고 "규칙을 통과한 뒤 무엇을 할지"와 "public API는 기본으로 보존 뿌리가 아니다" 절을 그대로 둡니다.
- **커버리지 게이트 90%**, 라인 기준, CI에서 강제.
- **JSON은 키 정렬**, 가지치기 목록은 **한 벌**, 신선도 마커는 **실측으로**.

## 이 프로젝트만의 규칙

- **원천(truth source)을 실험 없이 정하지 않습니다.** 바이트코드+R8 · Analysis API Standalone · KSP 덤프 세 갈래를 로컬 실제 프로젝트에서 각각 돌려 보고 표로 비교한 뒤 정합니다(`docs/PLAN.md` Phase 0). 이 결정이 프로젝트의 전부입니다. "불안정하다"는 이유만으로 배제하지 말고 실험 결과로 배제합니다.
- **keep 규칙을 보존 규칙의 1차 원천으로 씁니다.** `proguard-rules.pro`, consumer rules, `@Keep`, AGP 기본 규칙을 파싱해 근거로 삼고, 근거에 "어느 규칙 파일 몇 번째 줄"을 남깁니다. 손으로 쓴 보존 규칙은 keep 규칙이 못 덮는 것(매니페스트, XML 레이아웃, DI 어노테이션)에만 씁니다.
- **생성 코드를 따로 표시합니다.** KSP/kapt/Compose 산출물은 `synthesized`로 구분합니다. cartograph의 `.compilerSynthesized`와 같은 자리입니다.
- **도구 언어는 Kotlin/JVM.** ASM · `kotlin-metadata-jvm` · Gradle 플러그인 API가 전부 JVM입니다. 배포는 Gradle 플러그인 + 독립 CLI(jar).
- **Java를 2등 시민으로 두지 않습니다.** 원천이 바이트코드면 Java는 공짜로 따라옵니다.
- **cartograph 코드를 복사하지 않습니다.** 설계를 옮기되, Swift의 사정에서 나온 결정(액세서 USR 필터링, 프로퍼티 래퍼 분할)을 그대로 들고 오지 않습니다.

## 검증

Phase 1부터 적용됩니다. 작업을 끝냈다고 말하기 전에 반드시 실제로 실행하고 출력을 확인합니다.

- `./gradlew test`, `./gradlew :koverVerify`, CLI 계약 스크립트, 픽스처 스크립트, 자기 분석(findings 0)
- PR마다 GLM 리뷰(`packet-ask`). **리뷰의 주장은 코드로 확인한 뒤 반영합니다.** 거절한 지적은 이유와 함께 PR 코멘트에 남깁니다. `../cartograph` PR #7 · #8의 코멘트가 형식의 예입니다
- 외부 프로젝트 도그푸딩(`docs/PLAN.md` 0.1의 목록). 새 오탐 계열은 코퍼스에 먼저 넣고 고칩니다

Phase 0(실험)에서는 대신 **측정값과 못 보는 것의 목록**이 검증입니다. 숫자 없는 결론은 결론이 아닙니다.

## 커밋

Conventional Commits, 본문은 한국어로 **왜**를 적습니다. `main`에 직접 커밋하지 않습니다. 스코프는 Phase 1의 모듈 이름을 씁니다(`core`, `index`, `analysis`, `export`, `cli`, `plugin`). 그 전에는 `docs`, `experiment`.

## 코드 스타일

- 주석은 한국어, 식별자는 영어. 사용자에게 보이는 출력 문자열은 영어(오픈소스 대상)
- 모든 public 타입·함수에 문서 주석. *무엇을*이 아니라 *왜*
- 함수는 하나의 역할만. 빈 `catch` 금지. 오류 메시지에는 원인과 해결 방향
