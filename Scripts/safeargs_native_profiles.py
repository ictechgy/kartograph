"""합성 Android 실행의 고정 도구 조합과 실제 artifact를 대조한다."""
PLUGIN_SHA = "5e585dfe55474ce5361f2759faf9afd4a12a7e6a8259c7371bab1b7e8a846c56"
GENERATOR_SHA = "d4eb204430ce5e350799981cbac0941cea76cf6feeffd959878363bfc13b5c3d"
PROFILES = {
    "gradle971-agp932": ("9.7.1", "9.3.2", "470ae06ceef38cf2c69f1a20838bc80ece735bc92c2615f5bad77a50ab379d25"),
    "gradle813-agp813-java": ("8.13", "8.13.0", "0a69dc9e20abce45bbe11135351dca180dd6b98b77274fd2b347655d0cd6fa41"),
}
DEFAULT_PROFILE = "gradle971-agp932"


def expected_profile(name):
    if name not in PROFILES:
        raise ValueError("unknown SafeArgs native profile")
    return PROFILES[name]


def verify_profile(observed, name):
    gradle, agp, _ = expected_profile(name)
    if not isinstance(observed, dict) or any(observed.get(k) != v for k, v in {"id": name, "gradle": gradle, "agp": agp, "java": 17}.items()):
        raise ValueError("actual native toolchain profile missing or mismatched")
    if any(not isinstance(observed.get(k), str) or not 0 < len(observed[k]) <= 128 for k in ("javaRuntime", "javaVendor")):
        raise ValueError("actual Java runtime identity missing or unbounded")
    return observed


def verify_action_hashes(values, name):
    if values != [expected_profile(name)[2]]:
        raise ValueError("actual generator action artifact does not match native profile")


def verify_build_timing(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            if key in values:
                raise ValueError("duplicate build timing property")
            values[key] = value
    if values.get("buildResult") != "succeeded" or not values.get("elapsedNanos", "").isdigit() or not 0 < int(values["elapsedNanos"]) < 2**63:
        raise ValueError("successful bounded build elapsed timing missing")
    if values.get("outputTruncated") != "false" or values.get("taskOutcomesTruncated") != "false":
        raise ValueError("build execution evidence is truncated")
    if "excludes external tool setup" not in values.get("timingScope", ""):
        raise ValueError("build preparation timing scope missing")
    return {"elapsedNanos": int(values["elapsedNanos"]), "scope": values["timingScope"]}
