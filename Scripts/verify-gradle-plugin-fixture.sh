#!/usr/bin/env bash
# 실제 AGP Variant API로 등록된 task가 class와 dependency 입력을 분석하는지 확인한다.

set -uo pipefail

cd "$(dirname "$0")/.."
FIXTURE_ROOT="fixtures/false-positive-corpus"
REPORT="$FIXTURE_ROOT/app/build/reports/kartograph/debug.txt"
EXPECTED_REPORTS="$(mktemp "${TMPDIR:-/tmp}/kartograph-plugin-expected.XXXXXX")"
ACTUAL_REPORTS="$(mktemp "${TMPDIR:-/tmp}/kartograph-plugin-actual.XXXXXX")"
STRICT_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-plugin-strict.XXXXXX")"
CACHE_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-plugin-cache.XXXXXX")"
FORMAT_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-plugin-format.XXXXXX")"
trap 'rm -f "$EXPECTED_REPORTS" "$ACTUAL_REPORTS" "$STRICT_OUTPUT" "$CACHE_OUTPUT" "$FORMAT_OUTPUT"' EXIT

./gradlew --no-daemon --console=plain -p "$FIXTURE_ROOT" :app:kartographDeadDebug >/dev/null || exit 1

if [[ ! -f "$REPORT" ]]; then
    echo "kartograph Gradle report was not produced" >&2
    exit 1
fi
printf '%s\n' \
    'class:dev/kartograph/fixture/ActuallyUnused' \
    'class:dev/kartograph/fixture/OpenFinalRule' \
    'method:dev/kartograph/fixture/CompilerGeneratedFixturesKt#compilerGeneratedSequence()Lkotlin/sequences/Sequence;' \
    'method:dev/kartograph/fixture/TopLevelFixturesKt#unusedTopLevelFunction()Ljava/lang/String;' | LC_ALL=C sort >"$EXPECTED_REPORTS"
awk -F '\t' '$1 == "unreachable" { print $2 }' "$REPORT" | LC_ALL=C sort -u >"$ACTUAL_REPORTS"
if ! diff -u "$EXPECTED_REPORTS" "$ACTUAL_REPORTS"; then
    echo "Gradle report does not exactly match expected findings" >&2
    exit 1
fi

for FORMAT in gradle github-actions sarif json; do
    ./gradlew --no-daemon --console=plain -p "$FIXTURE_ROOT" \
        :app:kartographDeadDebug "-Pkartograph.reportFormat=$FORMAT" >/dev/null || exit 1
    cp "$REPORT" "$FORMAT_OUTPUT"
    case "$FORMAT" in
        gradle) grep -Fq -- 'warning: class:dev/kartograph/fixture/ActuallyUnused is unreachable [kartograph.dead]' "$FORMAT_OUTPUT" ;;
        github-actions) grep -Fq -- '::warning ' "$FORMAT_OUTPUT" ;;
        sarif) grep -Fq -- '"version": "2.1.0"' "$FORMAT_OUTPUT" ;;
        json) grep -Fq -- '"state": "unreachable"' "$FORMAT_OUTPUT" ;;
    esac || { echo "Gradle $FORMAT report fixture failed" >&2; exit 1; }
done

./gradlew --no-daemon --console=plain -p "$FIXTURE_ROOT" \
    :app:kartographDeadDebug -Pkartograph.strict=true >"$STRICT_OUTPUT" 2>&1
STRICT_COMMAND_STATUS=$?
if [[ "$STRICT_COMMAND_STATUS" -eq 0 ]] || ! grep -Fq -- 'kartograph found 4 unreachable declarations' "$STRICT_OUTPUT"; then
    echo "Gradle strict mode did not fail with the expected findings" >&2
    exit 1
fi

./gradlew --no-daemon --console=plain --configuration-cache -p "$FIXTURE_ROOT" \
    :app:kartographDeadDebug >/dev/null || exit 1
./gradlew --no-daemon --console=plain --configuration-cache -p "$FIXTURE_ROOT" \
    :app:kartographDeadDebug >"$CACHE_OUTPUT" || exit 1
if ! grep -Fq -- 'Reusing configuration cache.' "$CACHE_OUTPUT"; then
    echo "Gradle configuration cache was not reused" >&2
    exit 1
fi

echo "Gradle plugin fixture verified"

# Kotlin compiler 산출물로 private member opt-in과 configuration cache를 함께 검증한다.
./gradlew --no-daemon --console=plain --configuration-cache -p "$FIXTURE_ROOT" \
    :app:kartographDeadDebug -Pkartograph.includePrivateMembers=true >/dev/null || exit 1
./gradlew --no-daemon --console=plain --configuration-cache -p "$FIXTURE_ROOT" \
    :app:kartographDeadDebug -Pkartograph.includePrivateMembers=true >"$CACHE_OUTPUT" || exit 1
grep -Fq 'Reusing configuration cache.' "$CACHE_OUTPUT" || exit 1
printf '%s\n' \
    'field:dev/kartograph/fixture/PrivateMemberFixture#unusedValue:I' \
    'method:dev/kartograph/fixture/PrivateMemberFixture#unused()I' >"$EXPECTED_REPORTS"
awk -F '\t' '$1 == "unreachable" && $2 ~ /:dev\/kartograph\/fixture\/PrivateMemberFixture#/ { print $2 }' \
    "$REPORT" | LC_ALL=C sort >"$ACTUAL_REPORTS"
diff -u "$EXPECTED_REPORTS" "$ACTUAL_REPORTS" || exit 1
echo "Private Kotlin members and configuration cache verified"

# 그래프 교환 문서 task가 실제 variant artifact에서 결정적 JSON과 경로 해석 opt-in을 지키는지 확인한다.
GRAPH_REPORT="$FIXTURE_ROOT/app/build/reports/kartograph/debug-graph.json"
./gradlew --no-daemon --console=plain --configuration-cache -p "$FIXTURE_ROOT" \
    :app:kartographGraphDebug >/dev/null || exit 1
if [[ ! -f "$GRAPH_REPORT" ]]; then
    echo "kartograph graph document was not produced" >&2
    exit 1
fi
grep -Fq -- '"format": "code-graph"' "$GRAPH_REPORT" || { echo "graph document is not a code-graph exchange document" >&2; exit 1; }
grep -Fq -- '"usr": "class:dev/kartograph/fixture/ActuallyUnused"' "$GRAPH_REPORT" || { echo "graph document is missing fixture declarations" >&2; exit 1; }
# 기본값은 opt-in이 아니므로 확정 경로가 없어야 한다.
if grep -Fq -- '"pathKind": "projectRelative"' "$GRAPH_REPORT"; then
    echo "graph document resolved source paths without the opt-in" >&2
    exit 1
fi

./gradlew --no-daemon --console=plain --configuration-cache -p "$FIXTURE_ROOT" \
    :app:kartographGraphDebug -Pkartograph.includeSourcePaths=true >"$CACHE_OUTPUT" 2>&1 || exit 1
grep -Fq 'Reusing configuration cache.' "$CACHE_OUTPUT" || { echo "graph task did not reuse the configuration cache" >&2; exit 1; }
grep -Fq -- '"pathKind": "projectRelative"' "$GRAPH_REPORT" || { echo "graph document did not resolve any project source path" >&2; exit 1; }
# task의 project root는 Gradle project(app)이므로 경로도 그 기준 상대경로다.
grep -Fq -- '"path": "src/main/kotlin/dev/kartograph/fixture/RetentionFixtures.kt"' "$GRAPH_REPORT" || { echo "graph document did not resolve the expected fixture source path" >&2; exit 1; }
# 절대경로는 어떤 경우에도 문서에 실리지 않는다.
if grep -Fq -- "$(pwd)" "$GRAPH_REPORT"; then
    echo "graph document leaked an absolute local path" >&2
    exit 1
fi

echo "Graph exchange document and source path opt-in verified"
