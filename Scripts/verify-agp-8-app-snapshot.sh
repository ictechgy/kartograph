#!/usr/bin/env bash
# docs/APP-MODULE-EVIDENCE.md 1단계 + 후보 A wiring 검증: AGP 8 application fixture에서
# processResources 산출 R.jar까지 resource producer witness로 커버돼 snapshot이 matched인지 확인한다.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRADLE_8_HOME:?Set GRADLE_8_HOME to a verified Gradle 8.10.2 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35}"
GRADLE_8="$GRADLE_8_HOME/bin/gradle"
"$GRADLE_8" --version | grep -Fxq 'Gradle 8.10.2'
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    echo "ANDROID_HOME must contain platforms/android-35 (fixture compileSdk 35)" >&2
    exit 2
fi
./gradlew --no-daemon :gradle-plugin:jar :cli:installDist
FIXTURE=fixtures/agp-8-smoke
BINARY=cli/build/install/kartograph/bin/kartograph
SNAPSHOT="$FIXTURE/build/reports/kartograph/debug-snapshot.json"
BINDINGS="$FIXTURE/build/kartograph/debug-input-bindings.json"
LOG="$(mktemp "${TMPDIR:-/tmp}/kartograph-app-snapshot.XXXXXX")"
trap 'rm -f "$LOG"' EXIT

if ! "$GRADLE_8" --no-daemon --console=plain -p "$FIXTURE" kartographSnapshotDebug >"$LOG" 2>&1; then
    cat "$LOG" >&2
    if grep -Fq 'unwitnessed-class-root' "$LOG"; then
        echo 'snapshot rejected with unwitnessed-class-root: the resource producer witness is not wired or did not cover R.jar' >&2
    fi
    exit 1
fi
if [[ ! -f "$SNAPSHOT" ]]; then
    echo 'kartographSnapshotDebug succeeded but the snapshot file is missing' >&2
    exit 1
fi
if ! grep -q 'R.jar' "$SNAPSHOT"; then
    cat >&2 <<'NOTE'
the captured scope contains no R.jar class root, so this fixture cannot exercise the
application R.jar contract yet. Add src/main/res/values/strings.xml so processDebugResources
emits R.jar, re-run, and record which class root carries it (1단계 남은 진단).
NOTE
    exit 4
fi
SCOPE="$(python3 -c '
import json, sys
document = json.load(open(sys.argv[1]))
witnesses = (document.get("provenance") or {}).get("witnesses") or []
print(witnesses[0]["scope"] if witnesses else "")
' "$SNAPSHOT")"
if [[ -z "$SCOPE" ]]; then
    echo 'snapshot carries no witness scope; cannot verify (wiring may have changed)' >&2
    exit 1
fi
if "$BINARY" verify-snapshot --graph-file "$SNAPSHOT" --project "$FIXTURE" --scope "$SCOPE" \
    --input-bindings "$BINDINGS" >"$LOG" 2>&1; then
    echo '1단계 + 후보 A 완료: application snapshot(실물 R.jar 포함)이 matched evidence로 검증됐다'
    exit 0
fi
cat "$LOG" >&2
echo 'verification did not reach matched; inspect the reasons above' >&2
exit 1
