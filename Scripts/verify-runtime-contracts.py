#!/usr/bin/env python3
"""실행 증거, 정적 후보, R8 변환 결과를 독립적으로 대조한다."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "fixtures/runtime-contracts"
BINARY = ROOT / "cli/build/install/kartograph/bin/kartograph"


def run(args, cwd=ROOT, expected=0, details=False):
    try:
        result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True, timeout=180)
    except subprocess.TimeoutExpired:
        raise RuntimeError("runtime contract stage timed out") from None
    except OSError:
        raise RuntimeError("required runtime contract tool is unavailable") from None
    if result.returncode != expected:
        raise RuntimeError(f"runtime contract stage {Path(str(args[0])).name} failed: expected {expected}, actual {result.returncode}")
    return result if details else result.stdout


def main():
    jdk = Path(os.environ["JAVA_HOME"])
    sdk = Path(os.environ["ANDROID_HOME"])
    r8 = sdk / "build-tools/35.0.0/lib/d8.jar"
    if not r8.is_file():
        raise RuntimeError("install Android SDK Build Tools 35.0.0 for the pinned R8 comparison")
    compiler_version = run([jdk / "bin/javac", "-version"], details=True)
    if not (compiler_version.stdout + compiler_version.stderr).startswith("javac 17."):
        raise RuntimeError("runtime differential contracts require JDK 17")
    if not (jdk / "jmods").is_dir():
        raise RuntimeError("R8 comparison requires a complete JDK 17 with jmods")
    version = run([jdk / "bin/java", "-cp", r8, "com.android.tools.r8.R8", "--version"]).strip()
    expected = json.loads((FIXTURES / "expectations.json").read_text())
    cases = {p.parent.name: p for p in FIXTURES.glob("*/Entry.java")}
    assert set(cases) == set(expected), "fixture and expectation sets differ"
    rows = []
    with tempfile.TemporaryDirectory(prefix="kartograph-runtime-contracts-") as directory:
        for case, source in sorted(cases.items()):
            project = Path(directory) / case
            classes = project / "classes"
            classes.mkdir(parents=True)
            run([jdk / "bin/javac", "-g", "-d", classes, source])
            output = run([jdk / "bin/java", "-cp", classes, "probe.Entry"]).strip()
            assert output == ("USED" if expected[case]["runtimeUsed"] else ""), case
            (project / "keep.pro").write_text("-keep class probe.Entry { *; }\n")
            states = {}
            for name in ["Used", "Unused"]:
                document = json.loads(run([BINARY, "query", "class:probe/Entry$" + name,
                    "--project", project, "--classes", classes, "--keep-rules", "keep.pro"]))
                assert document["status"] == "found", case
                states[name] = document["result"]["reachability"]["state"]
            assert states["Used"] == expected[case]["kartographState"], (case, states)
            assert states["Unused"] == "unreachable", case
            with zipfile.ZipFile(project / "input.jar", "w") as archive:
                for file in sorted(classes.rglob("*.class")):
                    archive.write(file, file.relative_to(classes))
            (project / "r8.pro").write_text((project / "keep.pro").read_text() +
                "-dontobfuscate\n-dontoptimize\n-printusage usage.txt\n-whyareyoukeeping class probe.Entry$Used\n")
            why = run([jdk / "bin/java", "-cp", r8, "com.android.tools.r8.R8", "--classfile", "--no-desugaring",
                       "--lib", jdk, "--pg-conf", "r8.pro", "--output", "r8.jar", "input.jar"], cwd=project)
            with zipfile.ZipFile(project / "r8.jar") as archive:
                keeps = "probe/Entry$Used.class" in archive.namelist()
                assert "probe/Entry$Unused.class" not in archive.namelist(), case
            assert keeps == expected[case]["r8KeepsUsed"], case
            assert ("r8.pro:1" in why) if keeps else ("Nothing is keeping" in why), case
            assert "probe.Entry$Used" in why, "R8 did not report the requested retention reason"
            assert (project / "usage.txt").is_file(), "R8 usage evidence missing"
            after_run = run([jdk / "bin/java", "-cp", project / "r8.jar", "probe.Entry"],
                        expected=expected[case]["r8Exit"], details=True)
            after = after_run.stdout.strip()
            if expected[case]["r8Exit"] == 0:
                assert after == output, case
            rows.append({"case": case, "runtimeUsed": output == "USED", "kartographState": states["Used"],
                         "r8KeepsUsed": keeps, "r8Exit": after_run.returncode, "r8PreservesExecution": after_run.returncode == 0 and after == output,
                         "unusedControl": states["Unused"], "r8Reasons": [name for phrase, name in [("is reflected from", "reflection"),
                             ("is instantiated in", "instantiation"), ("is referenced in keep rule", "keepRule"),
                             ("Nothing is keeping", "notKept")] if phrase in why]})
    assert all(row["r8Reasons"] for row in rows), "R8 reason wording was not recognized"
    report = ROOT / "build/reports/runtime-contracts.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"buildTools": "35.0.0", "r8Version": version, "r8Sha256": hashlib.sha256(r8.read_bytes()).hexdigest(),
                                 "cases": rows}, indent=2) + "\n")
    print(f"Runtime differential contracts verified: {len(rows)} cases, compiled unused controls, R8 reasons and execution")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, AssertionError, KeyError) as error:
        print("runtime contracts failed: " + str(error), file=sys.stderr)
        raise SystemExit(2)
