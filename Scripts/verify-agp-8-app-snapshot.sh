#!/usr/bin/env bash
# docs/APP-MODULE-EVIDENCE.md 1단계: AGP 8 application fixture에서 R.jar 증거 거부를 재현한다.
#
# 현재 계약(AppModuleRJarCliTest가 CLI 레벨로 검증한 것의 AGP 대응물): application의
# processDebugResources R.jar는 compiler witness 커버 밖이므로 kartographSnapshotDebug는
# unwitnessed-class-root를 이유로 실패해야 한다. 설계 후보 A(processDebugResources producer
# witness)가 plugin에 wiring되면 이 스크립트의 기대를 matched로 뒤집는다.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRADLE_8_HOME:?Set GRADLE_8_HOME to a verified Gradle 8.10.2 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK; processDebugResources needs platform 35}"
GRADLE_8="$GRADLE_8_HOME/bin/gradle"
"$GRADLE_8" --version | grep -Fxq 'Gradle 8.10.2'
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    echo "ANDROID_HOME must contain platforms/android-35 (fixture compileSdk 35)" >&2
    exit 2
fi
if [[ ! -d fixtures/agp-8-smoke/src/main/res ]]; then
    cat >&2 <<'NOTE'
note: the fixture has no res/ sources. processDebugResources still emits R.jar for
applications on most AGP versions; if the scope below ends up without an R.jar class
root, add src/main/res/values/strings.xml to the fixture and re-run (recorded as the
remaining diagnosis step of docs/APP-MODULE-EVIDENCE.md 1단계).
NOTE
fi
./gradlew --no-daemon :gradle-plugin:jar :cli:installDist
FIXTURE=fixtures/agp-8-smoke
BINARY=cli/build/install/kartograph/bin/kartograph
SNAPSHOT="$FIXTURE/build/reports/kartograph/debug-snapshot.json"
LOG="$(mktemp "${TMPDIR:-/tmp}/kartograph-app-snapshot.XXXXXX")"
trap 'rm -f "$LOG"' EXIT

if "$GRADLE_8" --no-daemon --console=plain -p "$FIXTURE" kartographSnapshotDebug >"$LOG" 2>&1; then
    echo 'capture succeeded; checking evidence coverage at verify time'
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
    if "$BINARY" verify-snapshot --graph-file "$SNAPSHOT" --project "$FIXTURE" --scope "$SCOPE" >"$LOG" 2>&1; then
        echo 'UNEXPECTED: R.jar is already covered; candidate A appears wired. Flip this script to enforce matched and update docs/APP-MODULE-EVIDENCE.md.'
        exit 1
    fi
    grep -Fq 'unwitnessed-class-root' "$LOG" || {
        echo 'verification failed for a different reason; inspect the log above' >&2
        exit 1
    }
    echo 'AGP 8 application R.jar rejection reproduced at verify stage (1단계 재현 완료)'
    exit 0
fi

if grep -Fq 'unwitnessed-class-root' "$LOG"; then
    echo 'AGP 8 application R.jar rejection reproduced at capture stage (1단계 재현 완료)'
    exit 0
fi
cat "$LOG" >&2
echo 'kartographSnapshotDebug failed for an unrelated reason; inspect the log above' >&2
exit 1
