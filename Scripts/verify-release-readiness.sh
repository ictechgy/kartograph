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

build_artifacts() {
    ./gradlew --no-daemon clean \
        :cli:distZip :cli:distTar \
        :gradle-plugin:jar :gradle-plugin:generatePomFileForPluginMavenPublication \
        :gradle-plugin:validatePlugins
}

artifact_paths() {
    printf '%s\n' \
        "cli/build/distributions/kartograph-$VERSION.zip" \
        "cli/build/distributions/kartograph-$VERSION.tar" \
        "gradle-plugin/build/libs/kartograph-gradle-plugin-$VERSION.jar" \
        "gradle-plugin/build/publications/pluginMaven/pom-default.xml"
}

hash_artifacts() {
    while IFS= read -r artifact; do
        [[ -f "$artifact" ]] || {
            echo "release 산출물이 없습니다: $artifact" >&2
            exit 2
        }
        shasum -a 256 "$artifact"
    done < <(artifact_paths)
}

build_artifacts
hash_artifacts > "$TEMPORARY_DIRECTORY/first.sha256"
build_artifacts
hash_artifacts > "$TEMPORARY_DIRECTORY/second.sha256"
diff -u "$TEMPORARY_DIRECTORY/first.sha256" "$TEMPORARY_DIRECTORY/second.sha256"

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
KARTOGRAPH_BINARY="$BINARY" KARTOGRAPH_PR_SCRIPT="$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/Scripts/check-pr.py" \
    python3 -m unittest discover -s Scripts/tests -v

tar -xf "cli/build/distributions/kartograph-$VERSION.tar" -C "$TEMPORARY_DIRECTORY"
TAR_BINARY="$TEMPORARY_DIRECTORY/kartograph-$VERSION/bin/kartograph"
[[ "$("$TAR_BINARY" --version)" == "kartograph $VERSION" ]]
Scripts/verify-cli-contract.sh "$TAR_BINARY"
KARTOGRAPH_BINARY="$TAR_BINARY" KARTOGRAPH_PR_SCRIPT="$TEMPORARY_DIRECTORY/kartograph-$VERSION/Scripts/check-pr.py" \
    python3 -m unittest discover -s Scripts/tests -v
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/docs/LIMITATIONS.md" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/Skills/kartograph/SKILL.md" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/THIRD_PARTY_NOTICES.md" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/LICENSES/Apache-2.0.txt" ]]
[[ -f "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION/LICENSES/BSD-3-Clause.txt" ]]
if grep -R -E -n '(/Users/|~/Desktop|[A-Za-z]:\\Users\\)' \
    "$TEMPORARY_DIRECTORY/unpacked/kartograph-$VERSION" --include='*.md'; then
    echo "release 문서에 로컬 사용자 경로가 남았습니다" >&2
    exit 1
fi

echo "release readiness 통과: $VERSION"
