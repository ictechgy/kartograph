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

## Reports

`--report-format`은 `text`, `gradle`, `github-actions`, `sarif`, `json`을 지원한다. 모든 형식은 finding을
결정적으로 정렬하며 알려진 분석 한계를 함께 운반한다. JSON과 SARIF는 표준 JSON parser로 검증한다.

Gradle plugin은 같은 renderer와 baseline codec을 사용한다.

```kotlin
kartograph {
    strict.set(true)
    baseline.set(layout.projectDirectory.file(".kartograph-baseline.json"))
    reportFormat.set("github-actions")
}
```

task는 AGP public Variant/Artifact API 입력만 사용하며 report를 쓴 뒤 새 finding이 있을 때 strict 실패한다.
