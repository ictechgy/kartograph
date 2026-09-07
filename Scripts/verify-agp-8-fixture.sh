#!/usr/bin/env bash
# 별도 Gradle 8.10.2로 최소 AGP 8.7/KGP 2.0 소비 프로젝트를 검증한다.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRADLE_8_HOME:?Set GRADLE_8_HOME to a verified Gradle 8.10.2 installation}"
GRADLE_8="$GRADLE_8_HOME/bin/gradle"
"$GRADLE_8" --version | grep -Fxq 'Gradle 8.10.2'
./gradlew --no-daemon :gradle-plugin:jar
FIXTURE=fixtures/agp-8-smoke
CACHE_LOG="$(mktemp "${TMPDIR:-/tmp}/kartograph-agp8.XXXXXX")"
trap 'rm -f "$CACHE_LOG"' EXIT
for PASS in 1 2; do
    "$GRADLE_8" --no-daemon --console=plain --configuration-cache -p "$FIXTURE" \
        kartographDeadDebug kartographGraphDebug >"$CACHE_LOG" 2>&1 || {
        cat "$CACHE_LOG" >&2
        exit 1
    }
done
grep -Fq 'Reusing configuration cache.' "$CACHE_LOG"
python3 - <<'CHECK'
import json
from pathlib import Path
root = Path('fixtures/agp-8-smoke/build/reports/kartograph')
findings = [line.split('\t')[1] for line in (root / 'debug.txt').read_text().splitlines() if line.startswith('unreachable\t')]
assert findings == ['class:dev/kartograph/compat/Unused'], findings
doc = json.loads((root / 'debug-graph.json').read_text())
text = json.dumps(doc)
assert 'class:dev/kartograph/compat/MainActivity' in text
assert 'src/main/kotlin/dev/kartograph/compat/Unused.kt' in text
CHECK
if "$GRADLE_8" --no-daemon --console=plain -p "$FIXTURE" kartographDeadDebug \
    -Pkartograph.strict=true >"$CACHE_LOG" 2>&1; then
    echo 'AGP 8 strict mode unexpectedly succeeded' >&2
    exit 1
fi
grep -Fq 'kartograph found 1 unreachable declarations' "$CACHE_LOG"
echo 'AGP 8.7.3 / Gradle 8.10.2 / KGP 2.0.21 fixture verified'
