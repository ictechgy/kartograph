#!/usr/bin/env bash
# release 배포물을 만들고 그 경로와 SHA-256 목록을 낸다.
# verify-release-readiness.sh와 release workflow의 publish job이 같은 빌드 명령과 경로 목록을 쓴다.
# publish job은 다시 만든 배포물의 목록이 verify job이 넘긴 목록과 문자 그대로 같을 때만 게시한다.
# 두 쪽이 다른 명령이나 순서를 쓰면 정직한 실행도 게시에 실패하므로 정의를 이 파일 한 곳에 둔다.

set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="$(tr -d '[:space:]' < VERSION)"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "VERSION은 안정 SemVer여야 합니다" >&2
    exit 2
fi

# 루트와 compiler-collectors 배포물을 clean build로 만든다. 두 빌드 모두 각자의 verification-metadata가 적용된다.
build_artifacts() {
    ./gradlew --no-daemon clean \
        :cli:distZip :cli:distTar \
        :gradle-plugin:jar :gradle-plugin:generatePomFileForPluginMavenPublication \
        :gradle-plugin:validatePlugins :cli:runtimeSbom :gradle-plugin:runtimeSbom
    ./gradlew --no-daemon -p compiler-collectors clean distZip
    python3 Scripts/prepare-release-metadata.py
}

# 해시로 비교할 배포물을 정해진 순서로 낸다. 순서가 곧 목록 비교의 기준이다.
artifact_paths() {
    printf '%s\n' \
        "cli/build/distributions/kartograph-$VERSION.zip" \
        "cli/build/distributions/kartograph-$VERSION.tar" \
        "gradle-plugin/build/libs/kartograph-gradle-plugin-$VERSION.jar" \
        "compiler-collectors/build/distributions/kartograph-compiler-collectors-$VERSION.zip" \
        "gradle-plugin/build/publications/pluginMaven/pom-default.xml" \
        "build/release/kartograph-$VERSION.cdx.json" \
        "build/release/kartograph-gradle-plugin-$VERSION.cdx.json" \
        "build/release/SHA256SUMS"
}

# 배포물마다 "<sha256>  <경로>" 한 줄을 낸다. 빠진 배포물이 있으면 목록을 끝까지 내지 않고 2로 끝낸다.
hash_artifacts() {
    while IFS= read -r artifact; do
        [[ -f "$artifact" ]] || {
            echo "release 산출물이 없습니다: $artifact" >&2
            exit 2
        }
        shasum -a 256 "$artifact"
    done < <(artifact_paths)
}

if (( $# != 1 )); then
    echo "Usage: Scripts/release-artifacts.sh build|paths|hashes" >&2
    exit 64
fi
case "$1" in
    build) build_artifacts ;;
    paths) artifact_paths ;;
    hashes) hash_artifacts ;;
    *)
        echo "Usage: Scripts/release-artifacts.sh build|paths|hashes" >&2
        exit 64
        ;;
esac
