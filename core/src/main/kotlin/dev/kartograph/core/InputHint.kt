package dev.kartograph.core

/**
 * dead 보고를 해석할 때 함께 읽어야 하는 누락 입력 신호다.
 * 입력 옵션 누락 자체가 아니라 그 입력이 만들 수 있었던 보존 근거를 확인하지 못했다는 측정이며,
 * finding이 아니므로 strict 판정에 관여하지 않는다.
 */
public enum class InputHint(public val id: String, public val description: String) {
    MISSING_KEEP_RULES(
        "missing-keep-rules",
        "no keep or consumer rule inputs were supplied; reachability may be under-measured for declarations kept only by shrinker rules",
    ),
    MISSING_CLASSPATH(
        "missing-classpath",
        "no dependency classpath inputs were supplied; reachability may be under-measured for dependency supertype and framework annotation retention",
    ),
    MANIFEST_WITHOUT_COMPONENTS(
        "manifest-without-components",
        "the manifest scan produced no component retention evidence; reachability may be under-measured for declarations rooted at manifest components",
    ),
}
