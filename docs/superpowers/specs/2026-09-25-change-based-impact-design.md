# 변경 내용 기반 impact 진입점 설계

- 날짜: 2026-09-25
- 상태: 설계 승인됨(대화). spec 검토 대기
- 관련: [경쟁 비교](../../COMPETITIVE-EVIDENCE.md), [v8 평가](../../../experiments/ai-utility-v8/README.md), [MCP](../../MCP.md), [IMPACT](../../IMPACT.md)

## 목적

v8 실제 모듈 평가에서 MCP 조건의 impact 요청 3회는 모두 `notFound`였다. 원인은 분석이 아니라 선택자였다.
모델은 모듈 이름이 앞에 붙은 저장소 기준 파일 경로와 JVM file-facade owner가 없는 소스 형태 이름을 보냈다.
0.16.0의 `discover_symbols`는 복구 경로를 제공하지만, agent가 먼저 정확한 USR을 만들어야 한다는 전제는 그대로다.

이 설계는 agent가 이미 가진 정보, 즉 **수정한 파일과 줄**을 그대로 받아 변경 선언을 찾고 기존 impact로 잇는다.

### 성공 기준

- v8에서 실패한 입력 형태(모듈 접두 경로, 소스 형태 이름 대신 수정 줄)로 올바른 선언을 선택한다.
- 모호하거나 해석할 수 없는 입력은 추측하지 않고 상태·후보·미대응 줄로 명시한다.
- 기존 `impact`·`SymbolQueryDocument`·graph JSON 계약, 결정적 출력, 16 KiB MCP 응답 한도를 유지한다.
- 이 기능의 AI 효용은 주장하지 않는다. 별도의 새 평가 프로토콜로 판단한다.

### 범위 밖

- MCP 서버가 git이나 현재 소스를 직접 읽는 방식. MCP는 저장된 snapshot만 읽는다는 계약을 유지한다.
- 줄 범위를 해석하지 못했을 때 파일 전체 impact로 자동 대체하는 동작.
- 새 AI 효용 평가와 릴리스.

## 전체 흐름

```
snapshot --include-paths  (캡처 시점, 소스 경로 해석이 이미 일어나는 곳)
  ├─ bytecode: 메서드별 LineNumberTable 범위 (Kotlin inline 호출 줄은 SMAP으로 제외)
  ├─ 소스 어휘 분석: 선언 머리(어노테이션·시그니처)부터 끝 줄까지
  │     └─ bytecode 첫 줄을 포함하는 가장 좁은 같은 이름 선언일 때만 범위 확장
  └─ snapshot 선택 필드 `sourceRanges`  { nodeId → 구간 목록, origin }

MCP impact(changes | diff)   /   CLI impact --diff <file> | --changes-from <json>
  ├─ 경로 해석: discover_symbols와 같은 규칙 (정확 일치 우선, 그다음 경로 구성 요소 suffix)
  ├─ 줄 → 선언: 겹치는 선언 중 가장 안쪽의 synthesized가 아닌 선언
  │     ├─ diff의 새 쪽 줄 → current snapshot
  │     └─ diff의 옛 쪽(삭제) 줄 → base snapshot
  ├─ changeResolution { 선택 USR, unmappedLines, 모호한 파일 후보 }
  └─ 선택된 USR로 기존 impact 실행 (보고서 형식 유지)
```

### 저장 위치 결정

줄 범위는 `GraphNode`, `SourceLocation`, `SymbolQueryDocument`에 넣지 않고 query snapshot의 선택 필드
`sourceRanges`에 둔다. graph JSON·query 스키마·cartograph와 공유하는 계약이 바뀌지 않는다. v1과 compact v2 모두
같은 필드를 선택적으로 싣고 snapshot 버전은 올리지 않는다. 필드가 없는 snapshot과, 예전 CLI가 새 snapshot을 읽는
경우의 실제 동작은 구현 단계의 호환 테스트로 확인한다. 예전 CLI가 알 수 없는 키를 거부하면 이 결정을 다시 검토한다.

### 도구 표면 결정

새 MCP 도구를 만들지 않고 기존 `impact`의 입력을 넓힌다. v8에서 모델은 제공된 도구 중 일부만 호출했다
(`query_symbol`·`freshness`는 호출하지 않았다). 도구 수를 늘리기보다 이미 선택되는 도구에서 선택자 작성 단계를 없앤다.
CLI는 같은 해석 코드를 `SavedSnapshotOperations`에서 공유한다.

## 입력

기존 `symbols`·`files`와 합집합 선택자다.

- `changes: [{file, lines: [[start, end], ...], side?: "new" | "old"}]`
  - 줄은 1부터 세며 `start ≤ end`다. `side` 기본값은 `new`이고 `old`는 base snapshot이 필요하다.
  - 상한: 파일 256개, 파일당 구간 1000개. 초과는 사용 오류다.
- `diff: "<unified diff>"` (상한 512 KiB, UTF-8)
  - `diff --git`, `---`/`+++`, `@@ -a,b +c,d @@` 헤더를 읽는다. 실제로 바뀐 줄(`+`/`-`)만 쓰고 문맥 줄은 쓰지 않는다.
  - `+` 줄은 새 경로로 current에서, `-` 줄은 옛 경로로 base에서 해석한다. rename과 `/dev/null`(추가·삭제 파일)을 처리한다.
  - `a/`·`b/` 접두는 제거한다. CRLF를 허용한다. binary·mode만 바뀐 항목은 `skipped`로 기록한다.
  - 경로는 `ImpactCommand.portable` 규칙을 통과해야 한다. 절대경로·`..`는 거부한다.
- CLI: `impact --diff <file|->`, `impact --changes-from <json>`. 두 옵션은 `--graph-file`이 필요하고 옛 쪽 줄이 있으면
  `--base-graph`가 필요하다.

## 해석 규칙

- **파일:** snapshot 경로와 정확히 일치하는 것을 먼저 찾는다. 없으면 경로 구성 요소 suffix가 **유일하게** 맞는 파일을 쓴다.
  여러 개면 `ambiguous`로 후보만 보여 주고 그 파일에서는 아무것도 선택하지 않는다.
- **줄 → 선언:** 바뀐 줄마다 범위가 겹치는 선언 중 가장 안쪽(가장 좁은 범위)이면서 synthesized가 아닌 선언을 고른다.
  람다·inline 복제 같은 synthesized 메서드는 그것을 감싸는 실제 선언으로 올라간다. 동률이면 모두 선택하고 USR로 정렬한다.
- **미대응 줄:** 어느 선언에도 속하지 않는 줄(import, 주석, 빈 줄, 파일 머리)은 `unmappedLines`로 알리고 선택하지 않는다.
- **클래스 머리 줄:** 클래스 선언 자체가 선택된다. 후보가 클 수 있으며 기존 잘림·페이지 안내를 따른다.
- **범위 없는 snapshot:** 해당 파일은 `rangesUnavailable`이며 `source-ranges-unavailable` 한계와 재캡처 안내를 낸다.

## 출력

기존 impact 보고서에 `changeResolution`을 추가한다. 기존 필드는 바꾸지 않는다.

```json
"changeResolution": {
  "status": "complete | partial | unresolved",
  "files": [{
    "input": "detekt-rules/src/main/kotlin/p/Foo.kt",
    "side": "new",
    "status": "matched | ambiguous | notFound | rangesUnavailable | baseRequired | skipped",
    "graphPath": "src/main/kotlin/p/Foo.kt",
    "selected": [{"usr": "...", "name": "visitKtFile", "kind": "method",
                  "range": [[40, 58]], "origin": "bytecode | source-extended | source-only", "lines": [[44, 45]]}],
    "unmappedLines": [[3, 3]],
    "candidates": []
  }],
  "truncated": false
}
```

- 일부만 해석되면 해석된 선언으로 impact를 실행하고 `partial`로 표시한다.
- 하나도 해석되지 않으면 기존의 알 수 없는 선택자와 같게 처리한다(CLI 종료 코드 64). `changeResolution`을 함께 내서
  무엇을 고칠지 보여 준다.
- MCP 16 KiB 한도에서는 기존 축소 순서를 따르며 `changeResolution`의 파일·선택 목록도 상한과 `truncated`를 가진다.
  잘리면 선택된 USR을 페이지로 다시 조회하라고 안내한다.

## 캡처 시점의 범위 계산

### bytecode 범위 (기준 근거)

- 일반 메서드: LineNumberTable의 최소~최대 줄. Kotlin은 `SourceDebugExtension` SMAP의 첫 파일 구간(예: `1#1,50`)
  밖의 줄을 inline 호출에서 온 줄로 보고 제외한다. SMAP이 없으면(Java) 표의 줄을 모두 쓴다.
- 생성자와 `<clinit>`: 프로퍼티 초기화 식과 `init` 블록이 흩어져 있으므로 최소~최대로 합치지 않고 실제 줄 구간만 저장한다.
- 본문이 없는 선언(abstract, interface, native, 필드)은 bytecode 범위가 없다.
- 클래스 범위는 소스 선언이 없을 때 멤버 범위의 합이다.

### 소스 확장

`ChannelBridgeScanner`의 선언 범위 어휘 분석(`enclosingDeclaration`)을 index 모듈의 공용 스캐너로 분리한다.
주석·문자열 마스킹과 중괄호 깊이 계산을 재사용하고 다음을 인식하도록 넓힌다.

- 선언 바로 위의 어노테이션 줄, 여러 줄 시그니처, `class`/`object`/`interface`/`enum`, `val`/`var`, Java 메서드·생성자.
- 식 본문처럼 중괄호가 없는 선언의 끝은 시그니처 끝 줄과 bytecode 최대 줄 중 큰 쪽이다.

소스 선언과 그래프 정점의 대응은 다음을 모두 만족할 때만 성립한다.

- 같은 파일이다.
- 이름이 같다. `getX`/`setX`/`isX` ↔ 프로퍼티 `x`, `<init>` ↔ `constructor`/클래스 이름을 같은 이름으로 본다.
- bytecode 첫 줄을 포함하는 소스 선언 중 가장 좁다.

범위는 넓히기만 한다. 최종 범위는 bytecode 범위와 소스 범위의 합이다. 소스 범위가 bytecode 첫 줄을 포함하지 않으면
소스 쪽을 버린다. 같은 크기의 후보가 여럿이면 확장하지 않는다. bytecode 범위가 없는 선언은 bytecode로 확정된 클래스
범위 안에서 이름이 유일할 때만 `source-only`로 기록한다. `build/` 아래 생성 소스는 기존 경로 인덱스와 같이 제외한다.

### snapshot 한계

- `source-ranges-unavailable`: 위치는 있으나 범위가 없는 정점 수.
- `source-range-conflicts`: 대응이 모호해 확장하지 않은 수.

구간과 정점은 결정적으로 정렬하고, compact v2에서는 정수 배열로 저장한다.

## 구현 전 비교 실험

AGENTS.md는 원천 변경 전에 비교 실험을 요구한다. `experiments/source-ranges/`에 A(bytecode만)와 C(혼합)의 버리는
prototype을 만들고 다음 정답 세트로 줄 → 선언 정답률을 잰다.

- v8 실제 변경 4건: detekt/ktlint 원본 커밋의 diff. 입력 revision을 고정한다.
- 사례 fixture: 시그니처 변경, 어노테이션 변경, interface 메서드, 식 본문 함수, 람다 내부, 프로퍼티 초기화, companion,
  Java 메서드, 클래스 머리.
- 바뀐 줄마다 정답 선언을 손으로 표기하고 표기 근거(소스 줄)를 남긴다.

판정: 실제 변경 4건에서 A의 정답률이 C와 같고 fixture에서 A가 놓치는 사례가 실제 변경에 나타나지 않으면 A로 단순화한다.
그렇지 않으면 C로 간다. 원문·정답·결과는 experiments에 보존하고, 결정을 사용자에게 보고한 뒤 2단계로 넘어간다.

## 작업 단위와 검증

| PR | 내용 | 검증 |
|---|---|---|
| 1. 실험 | prototype, 정답 세트, 결과 README. 제품 코드 변경 없음 | 정답 출처·입력 해시 보존. A/C 확정 |
| 2. 범위 캡처 | 공용 소스 선언 스캐너, bytecode 범위(SMAP 필터), snapshot `sourceRanges`(v1·compact v2) | 선언 종류별 실패 재현 테스트 선행, 이전 snapshot 읽기와 예전 CLI 호환, 같은 입력의 바이트 결정성, `Scripts/verify-fixture-corpus.sh`, 자기 분석 게이트 |
| 3. 변경 해석 | diff 파서, 경로·줄 해석, MCP `impact`의 `changes`/`diff`, CLI `--diff`/`--changes-from`, 문서·스킬 | diff 경계(rename, 추가·삭제, 헤더만, CRLF, 상한 초과), ambiguous·unmapped·baseRequired, 16 KiB 축소, `Scripts/verify-cli-contract.sh`, `Scripts/verify-agent-surface.sh`, `./gradlew --no-daemon test :koverVerify :cli:installDist` |

완료 확인: v8에서 `notFound`였던 입력 3건을 실제 diff 형태로 넣어 정확한 선언과 impact로 이어지는지 확인한다.
저장 그래프 재조회 검증이며 AI 효용 개선의 증거가 아니다.

문서: `docs/MCP.md`·`docs/IMPACT.md`(입력과 해석 규칙), `docs/LIMITATIONS.md`(범위 근거·SMAP·source-only),
`Skills/kartograph/SKILL.md`(선택자를 만들기 전에 diff를 넘기라는 안내), CHANGELOG Unreleased.

## 위험

- 소스 어휘 분석은 휴리스틱이다. 문자열 안의 중괄호, 복잡한 DSL, 여러 줄 식 본문에서 범위가 틀릴 수 있다.
  확장은 넓히기만 하고, 출처를 표시하며, 동률이면 확장하지 않는다.
- 생성자 구간이 여러 멤버 사이에 흩어져 있어 가장 안쪽 규칙이 프로퍼티 초기화 줄에서 생성자와 프로퍼티 중 무엇을
  고를지 실험으로 확인해야 한다.
- 예전 CLI가 `sourceRanges`를 거부하면 snapshot 버전 정책을 다시 정해야 한다.
