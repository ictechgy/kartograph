#!/usr/bin/env python3
"""Safe Args 단일 native 실행과 보존된 hash receipt를 확인한다. runtime 완전성 검사는 아니다."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SUITE = "dev.kartograph.gradle.AndroidSafeArgsIntegrationTest"
CASE = "SafeArgs 실제 생성물과 수기 대조군의 출처 및 bytecode 호출을 구분한다(Path)"
HASH = re.compile(r"[0-9a-f]{64}\Z")
CLASSES = ("p/StartFragmentArgs.class", "p/StartFragmentDirections.class",
           "p/StartFragmentDirections$Go.class", "p/StartFragmentDirections$Idle.class",
           "p/ManualArgs.class", "p/ManualDirections.class", "other/StartFragmentArgs.class")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def inventory(directory):
    rows = {}
    for line in (directory / "capture.tsv").read_text(encoding="utf-8").splitlines():
        sha, count, name = line.split("\t")
        path = PurePosixPath(name)
        if not HASH.fullmatch(sha) or path.is_absolute() or ".." in path.parts or "\\" in name or name in rows:
            raise ValueError("invalid or duplicate capture identity")
        target = directory / name
        if target.is_symlink() or not target.resolve().is_relative_to(directory.resolve()):
            raise ValueError("capture escapes evidence directory")
        size = int(count)
        if not 0 <= size <= 10 * 1024 * 1024 or target.stat().st_size != size:
            raise ValueError("capture size mismatch or budget exceeded")
        if digest(target.read_bytes()) != sha:
            raise ValueError("capture content mismatch")
        rows[name] = sha
        if len(rows) > 200:
            raise ValueError("capture file budget exceeded")
    return rows


def verify(xml_path, evidence):
    raw = xml_path.read_bytes()
    suite = ET.fromstring(raw)
    if suite.tag != "testsuite" or suite.get("name") != SUITE or suite.get("tests") != "1":
        raise ValueError("exact Safe Args suite absent or unexpected")
    if any(suite.get(key) != "0" for key in ("skipped", "failures", "errors")):
        raise ValueError("Safe Args native test was skipped or failed")
    cases = suite.findall("testcase")
    if len(cases) != 1 or cases[0].get("name") != CASE or cases[0].get("classname") != SUITE:
        raise ValueError("required Safe Args case absent or duplicated")
    if any(cases[0].find(tag) is not None for tag in ("skipped", "failure", "error")):
        raise ValueError("Safe Args case did not pass")
    lines = (suite.findtext("system-out") or "").splitlines()
    rows = inventory(evidence)
    outcomes = (evidence / "observations/task-outcomes.txt").read_text(encoding="utf-8").splitlines()
    variants = []
    for variant, seed in (("Debug", 11), ("Release", 22)):
        marker = f"safe-args-native complete={variant} freshness=matched controls=checked"
        if lines.count(marker) != 1:
            raise ValueError("native completion marker absent or duplicated")
        for task in (f"generateSafeArgs{variant}", f"compile{variant}JavaWithJavac", f"observeSafeArgs{variant}", f"kartographSnapshot{variant}"):
            if outcomes.count(f":{task}=SUCCESS") != 1:
                raise ValueError("successful cold task execution missing")
        relative = f"observations/{variant}.json"
        if relative not in rows or f"snapshots/{variant.lower()}-snapshot.json" not in rows:
            raise ValueError("variant observation or snapshot capture missing")
        observed = json.loads((evidence / relative).read_text(encoding="utf-8"))
        descriptor = observed["providerDescriptor"]
        if digest(descriptor.encode("utf-8")) != observed["providerSha256"]:
            raise ValueError("provider descriptor hash mismatch")
        providers = json.loads(descriptor)
        expected = {"applicationId": "p.safeargs", "rFilePackage": "p", "useAndroidX": True, "generateKotlin": False,
                    "generatorTask": f":generateSafeArgs{variant}", "compilerTask": f":compile{variant}JavaWithJavac"}
        if any(providers.get(key) != value for key, value in expected.items()):
            raise ValueError("wrong variant/provider options")
        nav = f"src/{'debug' if variant == 'Debug' else 'main'}/res/navigation/graph.xml"
        if observed["navigation"] != {nav: rows.get("fixture/" + nav)}:
            raise ValueError("navigation input identity mismatch")
        for name, sha in observed["generated"].items():
            text = observed["generatedSourceTexts"][PurePosixPath(name).name]
            if digest(text.encode("utf-8")) != sha:
                raise ValueError("generated source bytes mismatch")
            source = providers["generatedSourceRoot"] + "/" + name
            if observed["compilerSources"].get(source) != sha:
                raise ValueError("generated source not present in observed compiler inputs")
        if set(observed["generated"]) != {"p/StartFragmentArgs.java", "p/EndFragmentArgs.java", "p/StartFragmentDirections.java"}:
            raise ValueError("unexpected generated source inventory")
        if f'"seed", {seed}' not in observed["generatedSourceTexts"]["StartFragmentArgs.java"]:
            raise ValueError("actual generated variant default missing")
        for source in ("p/ManualArgs.java", "p/ManualDirections.java", "other/StartFragmentArgs.java"):
            source = "src/main/java/" + source
            if observed["compilerSources"].get(source) != rows.get("fixture/" + source) or "fixture/" + source not in rows:
                raise ValueError("handwritten compiler-source control missing")
        if any(not HASH.fullmatch(observed["compiled"].get(name, "")) for name in CLASSES):
            raise ValueError("mixed compiled-root controls missing")
        variants.append({"variant": variant, "providerSha256": observed["providerSha256"],
                         "observationSha256": rows[relative], "generatedSources": len(observed["generated"]),
                         "mixedClassControls": len(CLASSES)})
    return {"status": "passed", "executed": 1, "skipped": 0, "xmlSha256": digest(raw), "variants": variants,
            "scope": "cold synthetic AGP/SafeArgs compilation and current-baseline classification; no device runtime, cached admission, private-app accuracy or completeness claim"}


def main():
    output = ROOT / "build/reports/safeargs-native-verification.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = verify(ROOT / f"gradle-plugin/build/test-results/test/TEST-{SUITE}.xml", ROOT / "build/reports/safeargs-native")
    except Exception as error:
        result = {"status": "failed", "error": f"{type(error).__name__}: {error}"}
        raise
    finally:
        result["commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
