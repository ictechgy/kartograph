#!/usr/bin/env bash
# 빌드된 실행 파일이 문서화한 종료 코드 계약을 실제로 지키는지 검증한다.

set -uo pipefail

cd "$(dirname "$0")/.."
BINARY="${1:-cli/build/install/kartograph/bin/kartograph}"

if [[ ! -x "$BINARY" ]]; then
    echo "실행 파일을 찾지 못했습니다: $BINARY" >&2
    exit 2
fi

FAILURES=0

expect_status() {
    local expected="$1"
    local description="$2"
    shift 2

    "$BINARY" "$@" >/dev/null 2>&1
    local actual=$?
    if [[ "$actual" -eq "$expected" ]]; then
        printf '  ok    %-3s %s\n' "$actual" "$description"
    else
        printf '  FAIL  %-3s %s (기대 %s)\n' "$actual" "$description" "$expected"
        FAILURES=$((FAILURES + 1))
    fi
}

expect_output() {
    local needle="$1"
    local description="$2"
    shift 2

    local output
    output="$("$BINARY" "$@" 2>&1)" || true
    if grep -q -- "$needle" <<<"$output"; then
        printf '  ok        %s\n' "$description"
    else
        printf '  FAIL      %s ("%s" 없음)\n' "$description" "$needle"
        FAILURES=$((FAILURES + 1))
    fi
}

TEMPORARY_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/kartograph-cli-contract.XXXXXX")"
trap 'rmdir "$TEMPORARY_DIRECTORY"' EXIT

echo "CLI 계약 검증: $BINARY"

echo "종료 코드 0 — 정상"
expect_status 0 "인자 없음(도움말)"
expect_status 0 "--help" --help
expect_status 0 "--version" --version
expect_status 0 "graph --help" graph --help
expect_status 0 "dead --help" dead --help
expect_status 0 "baseline --help" baseline --help
expect_status 0 "빈 class root graph" graph --classes "$TEMPORARY_DIRECTORY"
expect_status 0 "빈 class root metrics" metrics --classes "$TEMPORARY_DIRECTORY"

echo "종료 코드 64 — 사용 오류"
expect_status 64 "알 수 없는 옵션" --no-such-option
expect_status 64 "알 수 없는 하위 명령" no-such-command
expect_status 64 "잘못된 그래프 형식" graph --format yaml
expect_status 64 "class root 누락" graph
expect_status 64 "dead 필수 옵션 누락" dead
expect_status 64 "baseline write 경로 누락" baseline
expect_status 64 "잘못된 report 형식" dead --report-format yaml
expect_status 64 "cycles class root 누락" cycles
expect_status 64 "rules config 누락" rules --classes "$TEMPORARY_DIRECTORY"

echo "종료 코드 2 — 도구 실패"
expect_status 2 "없는 class root" graph --classes "$TEMPORARY_DIRECTORY/missing"

echo "출력 내용"
expect_output "kartograph" "도움말에 도구 이름" --help
expect_output "Exit codes:" "도움말에 종료 코드 표" --help
expect_output "kartograph baseline" "도움말에 baseline 명령" --help
expect_output "kartograph cycles" "도움말에 architecture 명령" --help
expect_output "digraph kartograph" "graph의 DOT 문서" graph --classes "$TEMPORARY_DIRECTORY"

echo
if [[ "$FAILURES" -eq 0 ]]; then
    echo "통과"
else
    echo "실패 $FAILURES 건" >&2
    exit 1
fi
