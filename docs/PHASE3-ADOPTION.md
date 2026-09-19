# Phase 3 adoption contract

## Baseline

`kartograph baseline --write <file>`은 현재 `dead` finding 전체를 version 1 JSON으로 기록한다. 지문은
`dead|<node-id>|<source-path>`이며 줄·열은 포함하지 않는다. 출력은 지문을 정렬하고 중복 제거하므로 같은
입력은 byte-for-byte 같은 파일을 만든다. 상대 `<file>` 경로는 shell working directory가 아니라
`--project`를 기준으로 해석한다. Gradle task의 baseline capture도 기존 baseline filter를 적용하지 않는다.

`dead --baseline <file>`은 지문이 같은 기존 finding을 report와 strict 판정에서 제외하고, 제외한 수를
machine report의 `suppressedCount`에 기록한다. 알 수 없는 버전·필드·타입·escape는 부분 적용하지 않고
exit 2로 실패한다.

## Changed scope

`dead --since <git-ref>`는 다음 경로를 합친다.

- `<git-ref>...HEAD`의 merge-base 이후 commit 변경
- `git diff HEAD`의 staged와 unstaged 변경
- ignore되지 않은 untracked 파일

Git 출력은 NUL delimiter로 읽으므로 공백·줄바꿈·Unicode 파일명을 보존한다. 삭제 파일은 현재 finding을
만들 수 없으므로 제외한다. finding의 project-relative source path가 있으면 exact match하고, JVM
`SourceFile`처럼 basename만 남은 경우에는 basename fallback을 쓴다. source 위치가 없으면 새 finding을
조용히 놓치지 않도록 변경 범위에 보수적으로 포함한다.

Git 실행 실패와 shallow history의 누락은 경로나 raw ref를 출력하지 않고 exit 2와 해결 방향으로 반환한다.

## Suppression with expiry

`dead --suppress <file>`은 기한이 있는 억제를 baseline과 별개로 적용한다. 파일은 version 1 JSON이고 각
항목은 baseline과 같은 지문과 사람이 읽는 `reason`, ISO 달력날짜 `expires`를 가진다. `expires` 날짜까지
(그날 포함) finding이 report와 strict 판정에서 제외되고, 만료한 항목은 억제하지 않으며 machine report의 `expiredSuppressions`에 개수를 기록한다.
개수는 현재 finding과 매치되지 않는 만료 항목까지 포함한 전체 만료 항목 수다. 무기한 억제는 baseline의 역할이다. 모르는 버전·필드·날짜 형식은
부분 적용하지 않고 exit 2로 실패한다. `--explain`·`--write-baseline` 모드와 함께 쓰면 사용 오류이다.

```json
{
  "suppressions": [
    {
      "expires": "2027-01-31",
      "fingerprint": "dead|class:app/Legacy|src/app/Legacy.kt",
      "reason": "reviewed 2026-09; removal scheduled with the api cleanup"
    }
  ],
  "version": 1
}
```

## Confidence tiers

JSON·SARIF·markdown 보고의 finding은 같은 소스 파일(파일 이름)에서 측정된 미해결 runtime 채널
(reflection 문자열·동적 등록·JNI·class loading·reflection 생성·ServiceLoader·reflection member 접근·값 분석 한도)
관측 합계에서 온 `confidence` 등급을 가질 수 있다.

- `static` — 같은 소스에서 측정된 채널이 0개다.
- `needs-runtime-review` — 같은 소스에서 하나 이상이 측정됐다.
- `unmeasured` — 소스 위치나 대응 관측이 없어 측정하지 못했다.

등급은 같은 컴파일 단위의 관측이라는 좁은 근거만 말하며, 전역 분석 한계는 모든 형식에 계속 함께
보고된다. 등급은 삭제 판정이 아니다. text/gradle/github-actions 형식은 바뀌지 않고, 등급을 전달하지
않는 호출자(예: 현재 Gradle plugin task)의 출력도 바뀌지 않는다.

## PR comment rendering

배포본 `Scripts/render-dead-comment.py`는 dead JSON 리포트를 리뷰 코멘트용 마크다운 본문으로 바꾼다.
게시(gh 등)는 호출자가 소유한다. 같은 리포트는 같은 본문을 만든다.

```bash
kartograph dead ... --report-format json | python3 Scripts/render-dead-comment.py > comment.md
```

## Input hints

`dead` 보고는 finding이 있을 때 누락된 보존 입력을 `input-hint` 진단으로 함께 싣는다.

- `missing-keep-rules` — keep/consumer rule 입력이 하나도 전달되지 않았다.
- `missing-classpath` — dependency classpath 입력이 전달되지 않았다.
- `manifest-without-components` — 전달된 manifest가 component 보존 근거를 만들지 못했다.

힌트는 finding이 아니므로 strict 실패·종료 코드·baseline 지문에 관여하지 않고, finding이 0건이면
보고하지 않는다. JSON은 `inputHints` 배열(`id`·`message`)로 노출하며, Gradle plugin report도 같은
신호를 공유한다. 문구는 보존 판정을 대신하지 않으며 과소계측 가능성만 알린다.

## Reports

`--report-format`은 `text`, `gradle`, `github-actions`, `sarif`, `json`, `markdown`을 지원한다. 모든 형식은 finding을
결정적으로 정렬하며 알려진 분석 한계를 함께 운반한다. JSON과 SARIF는 표준 JSON parser로 검증한다.
`markdown`은 사람의 리뷰 설명을 위한 표(위치·선언·confidence)와 한계 목록을 렌더링한다.

Gradle plugin은 같은 renderer와 baseline codec을 사용한다.

```kotlin
kartograph {
    strict.set(true)
    baseline.set(layout.projectDirectory.file(".kartograph-baseline.json"))
    reportFormat.set("github-actions")
}
```

task는 AGP public Variant/Artifact API 입력만 사용하며 report를 쓴 뒤 새 finding이 있을 때 strict 실패한다.
