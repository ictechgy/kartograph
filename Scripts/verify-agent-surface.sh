#!/usr/bin/env bash
# 빌드된 CLI의 query와 bridge-facts JSON 계약을 실제 parser로 확인한다.

set -uo pipefail

cd "$(dirname "$0")/.."
BINARY="${1:-cli/build/install/kartograph/bin/kartograph}"
QUERY_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-query.XXXXXX")"
BRIDGE_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-bridges.XXXXXX")"
SNAPSHOT_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-snapshot.XXXXXX")"
SAVED_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/kartograph-saved-query.XXXXXX")"
trap 'rm -f "$QUERY_OUTPUT" "$BRIDGE_OUTPUT" "$SNAPSHOT_OUTPUT" "$SAVED_OUTPUT"' EXIT

"$BINARY" query MissingAgentSurfaceSymbol \
    --classes cli/build/classes/kotlin/main --project . >"$QUERY_OUTPUT"
QUERY_STATUS=$?
if [[ "$QUERY_STATUS" -ne 64 ]]; then
    echo "query notFound returned $QUERY_STATUS instead of 64" >&2
    exit 1
fi

"$BINARY" bridges --project fixtures/bridge-corpus --format json >"$BRIDGE_OUTPUT" || exit 1
"$BINARY" snapshot --classes cli/build/classes/kotlin/main --project . >"$SNAPSHOT_OUTPUT" || exit 1
"$BINARY" query MissingAgentSurfaceSymbol --graph-file "$SNAPSHOT_OUTPUT" >"$SAVED_OUTPUT"
if [[ "$?" -ne 64 ]]; then
    echo "saved query notFound did not return 64" >&2
    exit 1
fi

python3 - "$QUERY_OUTPUT" "$BRIDGE_OUTPUT" "$SAVED_OUTPUT" <<'PY'
import json
import pathlib
import sys

query = json.loads(pathlib.Path(sys.argv[1]).read_text())
if set(query) != {"level", "limitations", "requested", "status"} or query["status"] != "notFound":
    raise SystemExit("query document does not match the notFound field contract")
saved = json.loads(pathlib.Path(sys.argv[3]).read_text())
if set(saved) != set(query) or saved["status"] != "notFound" or not any(
        item.startswith("saved-graph:") for item in saved["limitations"]):
    raise SystemExit("saved query lost its document or snapshot contract")

bridges = json.loads(pathlib.Path(sys.argv[2]).read_text())
if bridges["format"] != "bridge-facts" or bridges["version"] != 1 or bridges["platform"] != "kotlin":
    raise SystemExit("bridge document metadata does not match v1")
if bridges["project"] != ".":
    raise SystemExit("bridge document project must not expose an absolute path")
facts = {(fact["kind"], fact["channel"], fact.get("method")) for fact in bridges["facts"]}
expected = {
    ("channel-register", "dev.kartograph/camera", None),
    ("method-handle", "dev.kartograph/camera", "takePhoto"),
    ("module-export", "Calendar", None),
    ("method-handle", "Calendar", "addEvent"),
}
if facts != expected:
    raise SystemExit("bridge facts do not match the Kotlin/React Native fixture")
if not any(item.startswith("missing-handler-usrs:") for item in bridges["limitations"]):
    raise SystemExit("bridge facts omit the unresolved handler identifier limitation")
PY

echo "Agent surface verified"
