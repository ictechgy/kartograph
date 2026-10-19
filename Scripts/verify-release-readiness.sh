#!/usr/bin/env bash
# 외부 publish 없이 release 산출물, plugin metadata, 재현성과 압축 해제 CLI 계약을 검증한다.

set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="$(tr -d '[:space:]' < VERSION)"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "VERSION은 안정 SemVer여야 합니다" >&2
    exit 2
fi

TEMPORARY_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/kartograph-release.XXXXXX")"

# 배포물 빌드와 해시 목록은 release workflow의 publish job이 다시 계산할 때와 같은 공용 스크립트로 만든다.
Scripts/release-artifacts.sh build
Scripts/release-artifacts.sh hashes > "$TEMPORARY_DIRECTORY/first.sha256"
Scripts/release-artifacts.sh build
Scripts/release-artifacts.sh hashes > "$TEMPORARY_DIRECTORY/second.sha256"

REPORT_DIRECTORY="build/reports/release-readiness"
mkdir -p "$REPORT_DIRECTORY"
cp "$TEMPORARY_DIRECTORY/first.sha256" "$TEMPORARY_DIRECTORY/second.sha256" "$REPORT_DIRECTORY/"
diff -u "$TEMPORARY_DIRECTORY/first.sha256" "$TEMPORARY_DIRECTORY/second.sha256"

python3 Scripts/verify-collector-installation.py \
    --archive "compiler-collectors/build/distributions/kartograph-compiler-collectors-$VERSION.zip" \
    --version "$VERSION" --evidence "$REPORT_DIRECTORY/collector-installation"

# 게시되는 POM이 출처와 라이선스를 알리는지 확인한다.
POM="gradle-plugin/build/publications/pluginMaven/pom-default.xml"
for ELEMENT in name description url licenses scm; do
    grep -Fq "<$ELEMENT>" "$POM" || {
        echo "게시 POM에 <$ELEMENT>가 없습니다: $POM" >&2
        exit 2
    }
done

PLUGIN_JAR="gradle-plugin/build/libs/kartograph-gradle-plugin-$VERSION.jar"
jar tf "$PLUGIN_JAR" | grep -Fxq 'META-INF/gradle-plugins/io.github.ictechgy.kartograph.properties'
jar tf "$PLUGIN_JAR" | grep -Fq 'dev/kartograph/analysis/ReachabilityAnalyzer.class'
jar tf "$PLUGIN_JAR" | grep -Fq 'dev/kartograph/index/ClassFileIndexer.class'
jar tf "$PLUGIN_JAR" | grep -Fq 'org/objectweb/asm/ClassReader.class'
jar tf "$PLUGIN_JAR" | grep -Fq 'kotlin/metadata/jvm/KotlinClassMetadata.class'
jar tf "$PLUGIN_JAR" | grep -Fq 'kotlin/jvm/internal/Intrinsics.class'
jar tf "$PLUGIN_JAR" | grep -Fq 'org/jetbrains/annotations/NotNull.class'
jar tf "$PLUGIN_JAR" | grep -Fxq 'META-INF/kartograph/LICENSE'
jar tf "$PLUGIN_JAR" | grep -Fxq 'META-INF/kartograph/THIRD_PARTY_NOTICES.md'
jar tf "$PLUGIN_JAR" | grep -Fxq 'META-INF/licenses/Apache-2.0.txt'
jar tf "$PLUGIN_JAR" | grep -Fxq 'META-INF/licenses/BSD-3-Clause.txt'
if grep -q '<dependencies>' gradle-plugin/build/publications/pluginMaven/pom-default.xml; then
    echo "plugin jar에 내장된 runtime dependency가 POM에도 남았습니다" >&2
    exit 1
fi

unzip -q "cli/build/distributions/kartograph-$VERSION.zip" -d "$TEMPORARY_DIRECTORY/unpacked"
BINARY="$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/bin/kartograph"
[[ "$("$BINARY" --version)" == "kartograph $VERSION" ]]
Scripts/verify-cli-contract.sh "$BINARY"
# 일반 test 실패(1)만 기록하고 독립적인 배포 검증을 계속한다. 다른 오류는 즉시 종료한다.
if KARTOGRAPH_BINARY="$BINARY" KARTOGRAPH_PR_SCRIPT="$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/Scripts/check-pr.py" \
    python3 -m unittest discover -s Scripts/tests -v; then
    ZIP_DISCOVERY_STATUS=0
else
    ZIP_DISCOVERY_STATUS=$?
fi
printf '%s\n' "$ZIP_DISCOVERY_STATUS" > "$REPORT_DIRECTORY/zip-discovery-exit.log"
if (( ZIP_DISCOVERY_STATUS != 0 && ZIP_DISCOVERY_STATUS != 1 )); then
    exit "$ZIP_DISCOVERY_STATUS"
fi

tar -xf "cli/build/distributions/kartograph-$VERSION.tar" -C "$TEMPORARY_DIRECTORY"
TAR_BINARY="$TEMPORARY_DIRECTORY/kartograph-$VERSION/bin/kartograph"
[[ "$("$TAR_BINARY" --version)" == "kartograph $VERSION" ]]
Scripts/verify-cli-contract.sh "$TAR_BINARY"
# 일반 test 실패(1)만 기록하고 독립적인 배포 검증을 계속한다. 다른 오류는 즉시 종료한다.
if KARTOGRAPH_BINARY="$TAR_BINARY" KARTOGRAPH_PR_SCRIPT="$TEMPORARY_DIRECTORY/kartograph-$VERSION/Scripts/check-pr.py" \
    python3 -m unittest discover -s Scripts/tests -v; then
    TAR_DISCOVERY_STATUS=0
else
    TAR_DISCOVERY_STATUS=$?
fi
printf '%s\n' "$TAR_DISCOVERY_STATUS" > "$REPORT_DIRECTORY/tar-discovery-exit.log"
if (( TAR_DISCOVERY_STATUS != 0 && TAR_DISCOVERY_STATUS != 1 )); then
    exit "$TAR_DISCOVERY_STATUS"
fi

# 동일 배포본의 collector와 ZIP/TAR CLI로 완료 영수증 기반 enum 비교를 검증한다.
cat > "$TEMPORARY_DIRECTORY/enum-runtime.gradle" <<'GRADLE'
import groovy.json.JsonOutput

gradle.projectsEvaluated {
    def project = gradle.rootProject
    project.tasks.register('releaseEnumRuntime') {
        doLast {
            def runtime = project.configurations.getByName('kotlinCompilerRuntime').files
            new File(System.getenv('KARTOGRAPH_ENUM_RUNTIME_FILE')).text =
                JsonOutput.toJson(runtime.collect { it.canonicalPath }.sort())
        }
    }
}
GRADLE
KARTOGRAPH_ENUM_RUNTIME_FILE="$TEMPORARY_DIRECTORY/enum-runtime.json" \
    ./gradlew --no-daemon --no-configuration-cache -p compiler-collectors \
    -I "$TEMPORARY_DIRECTORY/enum-runtime.gradle" releaseEnumRuntime \
    > "$REPORT_DIRECTORY/packaged-enum-runtime.log" 2>&1
python3 - "$VERSION" \
    "$REPORT_DIRECTORY/collector-installation/installed/kartograph-compiler-collectors-$VERSION/lib/kartograph-compiler-collectors.jar" \
    "$BINARY" "$TAR_BINARY" "$TEMPORARY_DIRECTORY/enum-runtime.json" "$REPORT_DIRECTORY" \
    > "$REPORT_DIRECTORY/packaged-enum.log" 2>&1 <<'PY'
from pathlib import Path
import json
import os
import sys

if not __debug__:
    raise SystemExit("packaged enum verification requires Python assertions")
sys.path.insert(0, str(Path.cwd() / "compiler-collectors/tests"))
from enum_decisions import _content_hash, _snapshot_comparison
from run import content_fingerprint, encoded, java_home, run, sha256

version = sys.argv[1]
collector, zip_cli, tar_cli, runtime_file = (Path(value).resolve(strict=True) for value in sys.argv[2:6])
reports = Path(sys.argv[6]).resolve()
runtime = [Path(value).resolve(strict=True) for value in json.loads(runtime_file.read_text(encoding="utf-8"))]
if not runtime or any(not path.is_file() for path in (collector, zip_cli, tar_cli, *runtime)):
    raise SystemExit("packaged enum verification requires the extracted artifacts and compiler runtime")
stdlib = next((path for path in runtime if path.name == "kotlin-stdlib-2.4.10.jar"), None)
if stdlib is None:
    raise SystemExit("packaged enum verification requires the configured Kotlin stdlib")
home = java_home()
collector_archive = Path(f"compiler-collectors/build/distributions/kartograph-compiler-collectors-{version}.zip")

def artifact_hashes(cli, archive):
    return {
        "collectorArchiveSha256": sha256(collector_archive),
        "collectorJarSha256": sha256(collector),
        "cliArchiveSha256": sha256(archive),
        "cliLauncherSha256": sha256(cli),
        "cliInstallationSha256": _content_hash(cli.parent.parent, sha256),
        "compilerRuntimeSha256": [{"name": path.name, "sha256": sha256(path)} for path in runtime],
    }

for label, cli in (("zip", zip_cli), ("tar", tar_cli)):
    evidence = reports / ("packaged-enum-" + label)
    evidence.mkdir(exist_ok=False)
    archive = Path(f"cli/build/distributions/kartograph-{version}.{label}")
    identities = artifact_hashes(cli, archive)
    result = {
        "status": "failed", "version": version, "cliFormat": label,
        "languages": ["javac", "kotlin"], "artifacts": identities,
        "log": "../packaged-enum.log",
    }
    try:
        os.environ["KARTOGRAPH_SNAPSHOT_CLI"] = str(cli)
        _snapshot_comparison(home / "bin/javac", home / "bin/java", collector,
                             os.pathsep.join(map(str, runtime)), stdlib, evidence,
                             run, encoded, sha256, content_fingerprint)
        if artifact_hashes(cli, archive) != identities:
            raise AssertionError("packaged enum verification changed consumed artifacts")
        result["snapshotSha256"] = {
            f"snapshot-{language}/{name}": sha256(evidence / ("snapshot-" + language) / name)
            for language in ("javac", "kotlin")
            for name in ("base.json", "current.json", "current-source-uses.json", "current-combined.json", "external.json")
        }
        result["status"] = "passed"
        print("packaged enum snapshot comparison passed: " + label)
    finally:
        (evidence / "verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

PY

# 최종 clean build 산출물과 두 압축 해제 CLI만 소비하며 이후 source build를 하지 않는다.
python3 Scripts/verify-agp-8-resource-impact.py --packaged \
    --plugin-jar "$PLUGIN_JAR" \
    --zip-cli "$BINARY" --tar-cli "$TAR_BINARY" \
    --zip-archive "cli/build/distributions/kartograph-$VERSION.zip" \
    --tar-archive "cli/build/distributions/kartograph-$VERSION.tar" \
    --checksums build/release/SHA256SUMS \
    --retained-hashes "$REPORT_DIRECTORY/second.sha256" \
    --version "$VERSION" --evidence "$REPORT_DIRECTORY/packaged-resource-impact"

[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/docs/LIMITATIONS.md" ]]
python3 - "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
for readme in ("README.md", "README.ko.md"):
    for target in re.findall(r'\]\((docs/[^)#]+\.md)(?:#[^)]*)?\)', (root / readme).read_text()):
        if ".." in Path(target).parts or not (root / target).is_file():
            raise SystemExit("release README documentation is missing: " + target)
PY
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/Skills/kartograph/SKILL.md" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/THIRD_PARTY_NOTICES.md" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/LICENSES/Apache-2.0.txt" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/LICENSES/BSD-3-Clause.txt" ]]
if grep -R -E -n '(/Users/|~/Desktop|[A-Za-z]:\\Users\\)' \
    "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION" --include='*.md'; then
    echo "release 문서에 로컬 사용자 경로가 남았습니다" >&2
    exit 1
fi

# 배포본 문서가 이전 release를 설치하라고 지시하면(버전 하드코딩) 사용자는 이 릴리스에 없는 구버전을 받게 된다.
for INSTALLATION in "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION" "$TEMPORARY_DIRECTORY/kartograph-$VERSION"; do
    for DOCUMENT in ANDROID-RESOURCE-BINDINGS ANDROID-RESOURCE-CAPTURE ANDROID-RESOURCE-EVIDENCE \
                    ANDROID-RESOURCE-IMPACT-EXPERIMENT ANDROID-RESOURCE-IMPACT ANDROID-RESOURCE-PRODUCER-EVIDENCE; do
        [[ -f "$INSTALLATION/docs/$DOCUMENT.md" ]] || {
            echo "release resource documentation is missing: $DOCUMENT" >&2
            exit 1
        }
    done
    python3 Scripts/verify-release-documentation.py --root "$INSTALLATION" --version "$VERSION"
done

# 배포 proof가 통과해도 앞서 실패한 test/smoke 예산을 성공으로 바꾸지 않는다.
if (( ZIP_DISCOVERY_STATUS != 0 || TAR_DISCOVERY_STATUS != 0 )); then
    printf 'Release readiness failed: script-test discovery exits zip=%s tar=%s; packaged resource verification passed.\n' \
        "$ZIP_DISCOVERY_STATUS" "$TAR_DISCOVERY_STATUS" >&2
    if (( ZIP_DISCOVERY_STATUS != 0 )); then
        exit "$ZIP_DISCOVERY_STATUS"
    fi
    exit "$TAR_DISCOVERY_STATUS"
fi

echo "release readiness 통과: $VERSION"
