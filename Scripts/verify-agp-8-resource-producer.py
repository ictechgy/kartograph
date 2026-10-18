#!/usr/bin/env python3
"""완료된 AGP 생산자 뒤 XML만 바꿔 새 snapshot 발행을 거부하는지 검사한다."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    project = ROOT / "fixtures/agp-8-smoke"
    snapshot = project / "build/reports/kartograph/debug-snapshot.json"
    witness = project / "build/kartograph/witnesses/processDebugResources/witness.json"
    xml = project / "src/main/res/values/strings.xml"
    output = ROOT / "build/reports/agp-8-resource-producer"
    output.mkdir(parents=True, exist_ok=True)
    command = [str(Path(os.environ["GRADLE_8_HOME"]) / "bin/gradle"),
               "--no-daemon", "--console=plain", "-p", str(project)]
    recorded = json.loads(witness.read_text(encoding="utf-8"))
    require(recorded["compiler"] == "agp-process-resources", "wrong resource producer")
    require(any(row["role"] == "android-resources" for row in recorded["inputs"]),
            "resource producer has no byte-complete XML input evidence")
    jars = []
    for row in recorded["outputs"]:
        require(not Path(row["path"]).is_absolute() and not row["path"].startswith("external/")
                and ".." not in Path(row["path"]).parts,
                "this bounded fixture requires project-relative producer outputs")
        jars.append(project / row["path"])
    require(bool(jars) and all(path.is_file() for path in jars), "producer output missing")
    before_snapshot, before_witness = digest(snapshot), digest(witness)
    before_jars = [digest(path) for path in jars]
    original = xml.read_bytes()
    try:
        xml.write_bytes(original + b"\n<!-- producer-only XML tamper -->\n")
        result = subprocess.run(command + ["kartographSnapshotDebug", "-x", "processDebugResources"],
                                cwd=ROOT, text=True, capture_output=True, timeout=600)
        log = result.stdout + result.stderr
        (output / "tamper.log").write_text(log, encoding="utf-8")
        require(result.returncode != 0, "XML tamper unexpectedly published a snapshot")
        require("changed-android-resources" in log, "failure did not identify producer XML mismatch")
        require("> Task :processDebugResources" not in log, "resource producer unexpectedly ran")
        require(digest(witness) == before_witness and [digest(path) for path in jars] == before_jars,
                "producer witness or R.jar changed during the excluded-producer check")
        require(snapshot.is_file() and digest(snapshot) == before_snapshot,
                "failed capture changed the previously published snapshot")
    finally:
        xml.write_bytes(original)
    result = subprocess.run(command + ["kartographSnapshotDebug", "--rerun-tasks"],
                            cwd=ROOT, text=True, capture_output=True, timeout=600)
    (output / "rebuilt.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    require(result.returncode == 0, "restored producer rebuild failed")
    binary = ROOT / "cli/build/install/kartograph/bin/kartograph"
    result = subprocess.run([str(binary), "verify-snapshot", "--graph-file", str(snapshot),
                             "--project", str(project), "--input-bindings",
                             str(project / "build/kartograph/debug-input-bindings.json")],
                            cwd=ROOT, text=True, capture_output=True, timeout=600)
    require(result.returncode == 0 and json.loads(result.stdout).get("status") == "matched",
            "rebuilt resource producer did not restore matched snapshot")
    print(json.dumps({"format": "kartograph-resource-producer-regression", "version": 1,
                      "xmlTamperAfterProducer": "rejected", "reason": "changed-android-resources",
                      "producerExcluded": True, "witnessAndJarUnchanged": True,
                      "failedCaptureSnapshotUnchanged": True, "restoredRebuild": "matched"},
                     sort_keys=True))


if __name__ == "__main__":
    main()
