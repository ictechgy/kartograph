#!/usr/bin/env bash
# 실제 Android compiler 산출물에 보존/보고 양방향 표본이 모두 존재하는지 확인한다.

set -uo pipefail

cd "$(dirname "$0")/.."
FIXTURE_ROOT="fixtures/false-positive-corpus"
EXPECTATIONS="$FIXTURE_ROOT/expectations.tsv"
CLASS_ROOT="$FIXTURE_ROOT/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
DEPENDENCY_CLASS_PATH="fixture-library/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
DEPENDENCY_CLASS_ROOT="$FIXTURE_ROOT/$DEPENDENCY_CLASS_PATH"
DEFAULT_RULES_PATH="app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-9.3.2"
DEFAULT_RULES="$FIXTURE_ROOT/$DEFAULT_RULES_PATH"
BINARY="cli/build/install/kartograph/bin/kartograph"

./gradlew --no-daemon --console=plain -p "$FIXTURE_ROOT" \
    :app:assembleDebug :app:extractProguardFiles >/dev/null || exit 1
./gradlew --no-daemon --console=plain :cli:installDist >/dev/null || exit 1

if [[ ! -d "$CLASS_ROOT" || ! -d "$DEPENDENCY_CLASS_ROOT" || ! -f "$DEFAULT_RULES" ]]; then
    echo "fixture compiler outputs were not produced" >&2
    exit 1
fi

GRAPH_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-fixture-graph.XXXXXX")"
DEAD_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-fixture-dead.XXXXXX")"
EXPLAIN_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-fixture-explain.XXXXXX")"
EXPECTED_REPORTS="$(mktemp "${TMPDIR:-/tmp}/kartograph-fixture-expected.XXXXXX")"
ACTUAL_REPORTS="$(mktemp "${TMPDIR:-/tmp}/kartograph-fixture-actual.XXXXXX")"
trap 'rm -f "$GRAPH_OUTPUT" "$DEAD_OUTPUT" "$EXPLAIN_OUTPUT" "$EXPECTED_REPORTS" "$ACTUAL_REPORTS"' EXIT
"$BINARY" graph --classes "$CLASS_ROOT" --format dot >"$GRAPH_OUTPUT" || exit 1

DEAD_ARGUMENTS=(
    dead
    --classes "$CLASS_ROOT"
    --project "$FIXTURE_ROOT"
    --manifest app/src/main/AndroidManifest.xml
    --resources app/src/main/res
    --namespace dev.kartograph.fixture
    --keep-rules app/proguard-rules.pro
    --keep-rules fixture-library/consumer-rules.pro
    --keep-rules "$DEFAULT_RULES_PATH"
    --classpath "$DEPENDENCY_CLASS_PATH"
)
"$BINARY" "${DEAD_ARGUMENTS[@]}" >"$DEAD_OUTPUT" || exit 1
"$BINARY" "${DEAD_ARGUMENTS[@]}" --strict >/dev/null 2>&1
STRICT_COMMAND_STATUS=$?

FAILURES=0
RETAIN_COUNT=0
REPORT_COUNT=0
NODE_IDS=()
RETAIN_FAMILIES=()

if [[ "$STRICT_COMMAND_STATUS" -ne 1 ]]; then
    echo "strict dead command returned $STRICT_COMMAND_STATUS instead of 1" >&2
    FAILURES=$((FAILURES + 1))
fi

while IFS=$'\t' read -r outcome node_id reason evidence_path evidence_line evidence_pattern family; do
    [[ -z "$outcome" || "$outcome" == \#* ]] && continue
    NODE_IDS+=("$node_id")

    case "$outcome" in
        retain)
            RETAIN_COUNT=$((RETAIN_COUNT + 1))
            RETAIN_FAMILIES+=("$family")
            if grep -Fq -- $'unreachable\t'"$node_id"$'\t' "$DEAD_OUTPUT"; then
                echo "retained fixture was reported unreachable: $node_id" >&2
                FAILURES=$((FAILURES + 1))
            fi
            "$BINARY" "${DEAD_ARGUMENTS[@]}" --explain "$node_id" >"$EXPLAIN_OUTPUT"
            EXPLAIN_COMMAND_STATUS=$?
            if [[ "$EXPLAIN_COMMAND_STATUS" -ne 0 ]] ||
                ! grep -Fq -- $'retained\t'"$node_id"$'\t'"$reason"$'\t' "$EXPLAIN_OUTPUT"; then
                echo "retention explanation mismatch: $node_id ($reason)" >&2
                FAILURES=$((FAILURES + 1))
            else
                case "$reason" in
                    KEEP_ANNOTATION)
                        EVIDENCE_FILE_NAME="${evidence_path##*/}"
                        if ! grep -Fq -- $'\t'"$EVIDENCE_FILE_NAME"$'\t' "$EXPLAIN_OUTPUT"; then
                            echo "retention evidence file mismatch: $node_id ($reason)" >&2
                            FAILURES=$((FAILURES + 1))
                        fi
                        ;;
                    KEEP_ANNOTATED_MEMBER)
                        EVIDENCE_FILE_NAME="${evidence_path##*/}"
                        if ! grep -Fq -- "$EVIDENCE_FILE_NAME:$evidence_line" "$EXPLAIN_OUTPUT"; then
                            echo "retention evidence location mismatch: $node_id ($reason)" >&2
                            FAILURES=$((FAILURES + 1))
                        fi
                        ;;
                    DEPENDENCY_INJECTION)
                        EVIDENCE_FILE_NAME="${evidence_path##*/}"
                        if ! grep -Fq -- "$EVIDENCE_FILE_NAME:$evidence_line" "$EXPLAIN_OUTPUT"; then
                            echo "retention evidence location mismatch: $node_id ($reason)" >&2
                            FAILURES=$((FAILURES + 1))
                        fi
                        ;;
                    SERIALIZATION|GENERATED_CODE|RUNTIME_ENTRY_POINT|INLINE_CONSTANT)
                        EVIDENCE_FILE_NAME="${evidence_path##*/}"
                        if grep -Fq -- $'\t'"$EVIDENCE_FILE_NAME:" "$EXPLAIN_OUTPUT"; then
                            EVIDENCE_LOCATION="$EVIDENCE_FILE_NAME:$evidence_line"
                        else
                            EVIDENCE_LOCATION="$EVIDENCE_FILE_NAME"
                        fi
                        if ! grep -Fq -- $'\t'"$EVIDENCE_LOCATION"$'\t' "$EXPLAIN_OUTPUT"; then
                            echo "retention evidence file mismatch: $node_id ($reason)" >&2
                            FAILURES=$((FAILURES + 1))
                        fi
                        ;;
                    MANIFEST_COMPONENT|XML_LAYOUT|KEEP_RULE)
                        if ! grep -Fq -- "$evidence_path:$evidence_line" "$EXPLAIN_OUTPUT"; then
                            echo "retention evidence location mismatch: $node_id ($reason)" >&2
                            FAILURES=$((FAILURES + 1))
                        fi
                        ;;
                esac
            fi
            ;;
        reachable)
            RETAIN_COUNT=$((RETAIN_COUNT + 1))
            RETAIN_FAMILIES+=("$family")
            if grep -Fq -- $'unreachable\t'"$node_id"$'\t' "$DEAD_OUTPUT"; then
                echo "reachable fixture was reported unreachable: $node_id" >&2
                FAILURES=$((FAILURES + 1))
            fi
            "$BINARY" "${DEAD_ARGUMENTS[@]}" --explain "$node_id" >"$EXPLAIN_OUTPUT"
            EXPLAIN_COMMAND_STATUS=$?
            if [[ "$EXPLAIN_COMMAND_STATUS" -ne 0 ]] ||
                ! grep -Fq -- $'reachable\t'"$node_id"$'\tvia\t' "$EXPLAIN_OUTPUT"; then
                echo "transitive reachability mismatch: $node_id ($reason)" >&2
                FAILURES=$((FAILURES + 1))
            fi
            ;;
        report)
            REPORT_COUNT=$((REPORT_COUNT + 1))
            printf '%s\n' "$node_id" >>"$EXPECTED_REPORTS"
            "$BINARY" "${DEAD_ARGUMENTS[@]}" --explain "$node_id" >"$EXPLAIN_OUTPUT"
            EXPLAIN_COMMAND_STATUS=$?
            if [[ "$EXPLAIN_COMMAND_STATUS" -ne 0 ]] ||
                ! grep -Fxq -- $'unreachable\t'"$node_id" "$EXPLAIN_OUTPUT"; then
                echo "unreachable explanation mismatch: $node_id" >&2
                FAILURES=$((FAILURES + 1))
            fi
            ;;
        *)
            echo "invalid fixture outcome: $outcome" >&2
            FAILURES=$((FAILURES + 1))
            ;;
    esac

    if ! grep -Fq -- "\"$node_id\"" "$GRAPH_OUTPUT"; then
        echo "fixture node missing from graph: $node_id" >&2
        FAILURES=$((FAILURES + 1))
    fi
    if [[ ! -f "$FIXTURE_ROOT/$evidence_path" || ! "$evidence_line" =~ ^[1-9][0-9]*$ ]]; then
        echo "invalid fixture evidence for $node_id ($reason)" >&2
        FAILURES=$((FAILURES + 1))
    elif ! sed -n "${evidence_line}p" "$FIXTURE_ROOT/$evidence_path" | grep -Fq -- "$evidence_pattern"; then
        echo "fixture evidence line does not match $node_id ($reason)" >&2
        FAILURES=$((FAILURES + 1))
    fi
done <"$EXPECTATIONS"

awk -F '\t' '$1 == "unreachable" { print $2 }' "$DEAD_OUTPUT" | LC_ALL=C sort -u >"$ACTUAL_REPORTS"
LC_ALL=C sort -u -o "$EXPECTED_REPORTS" "$EXPECTED_REPORTS"
if ! diff -u "$EXPECTED_REPORTS" "$ACTUAL_REPORTS"; then
    echo "dead findings do not exactly match fixture report expectations" >&2
    FAILURES=$((FAILURES + 1))
fi

UNIQUE_COUNT="$(printf '%s\n' "${NODE_IDS[@]}" | sort -u | wc -l | tr -d ' ')"
if [[ "$UNIQUE_COUNT" -ne "${#NODE_IDS[@]}" ]]; then
    echo "fixture expectations contain duplicate node IDs" >&2
    FAILURES=$((FAILURES + 1))
fi
RETENTION_FAMILY_COUNT="$(printf '%s\n' "${RETAIN_FAMILIES[@]}" | sort -u | wc -l | tr -d ' ')"
if [[ "$RETENTION_FAMILY_COUNT" -lt 10 ]]; then
    echo "fixture must contain at least 10 independent retention families" >&2
    FAILURES=$((FAILURES + 1))
fi
if [[ "$RETAIN_COUNT" -ne 44 || "$REPORT_COUNT" -ne 4 ]]; then
    echo "fixture must contain exactly 44 retained cases and 4 report cases" >&2
    FAILURES=$((FAILURES + 1))
fi

if [[ "$FAILURES" -ne 0 ]]; then
    echo "fixture verification failed: $FAILURES" >&2
    exit 1
fi

echo "fixture verified: $RETAIN_COUNT retained, $REPORT_COUNT reportable"
