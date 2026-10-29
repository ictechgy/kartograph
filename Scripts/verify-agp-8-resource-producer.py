#!/usr/bin/env python3
"""완료된 AGP 생산자 뒤 XML만 바꿔 새 snapshot 발행을 거부하는지 검사한다."""
import hashlib
import json
import os
from pathlib import Path
import runpy
import tempfile

ROOT = Path(__file__).resolve().parents[1]
run_process = runpy.run_path(str(Path(__file__).with_name("resource_process_diagnostics.py")))["run"]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def injection_script(original_sha256):
    require(len(original_sha256) == 64 and all(c in "0123456789abcdef" for c in original_sha256),
            "invalid fixture XML digest")
    return """
gradle.projectsEvaluated {
    if (gradle.rootProject.name == 'kartograph-agp-8-smoke') {
        gradle.rootProject.tasks.named('kartographSnapshotDebug').configure {
            outputs.upToDateWhen { false }
            doFirst {
                def xml = project.file('src/main/res/values/kartograph_producer_evidence.xml')
                def fingerprint = { file -> java.security.MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString() }
                def witness = project.file('build/kartograph/witnesses/processDebugResources/witness.json')
                def rows = new groovy.json.JsonSlurper().parse(witness).outputs
                def jarHashes = rows.collect { row ->
                    def relative = row.path.toString()
                    if (new File(relative).isAbsolute() || relative.startsWith('external/') || relative.tokenize('/').contains('..')) {
                        throw new GradleException('fixture producer output is not project relative')
                    }
                    fingerprint(project.file(relative))
                }
                def witnessHash = fingerprint(witness)
                def original = xml.bytes
                def sha = java.security.MessageDigest.getInstance('SHA-256').digest(original).encodeHex().toString()
                if (sha != '__ORIGINAL_SHA__') {
                    throw new GradleException('fixture XML changed before snapshot injection')
                }
                xml.withOutputStream { stream ->
                    stream.write(original)
                    stream.write('\\n<!-- producer-only XML tamper -->\\n'.getBytes('UTF-8'))
                }
                println 'KARTOGRAPH_FIXTURE_XML_TAMPER_AFTER_PRODUCERS ' + groovy.json.JsonOutput.toJson([
                    witnessSha256: witnessHash, jarSha256: jarHashes,
                    originalXmlSha256: sha, mutatedXmlSha256: fingerprint(xml)
                ])
            }
        }
    }
}
""".replace("__ORIGINAL_SHA__", original_sha256)


def main():
    project = ROOT / "fixtures/agp-8-smoke"
    snapshot = project / "build/reports/kartograph/debug-snapshot.json"
    witness = project / "build/kartograph/witnesses/processDebugResources/witness.json"
    xml = project / "src/main/res/values/kartograph_producer_evidence.xml"
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
    mutated = original + b"\n<!-- producer-only XML tamper -->\n"
    injection = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".gradle",
                                         prefix="snapshot-tamper-", dir=output, delete=False) as owned:
            injection = Path(owned.name)
            owned.write(injection_script(hashlib.sha256(original).hexdigest()))
        result = run_process(command + ["--init-script", str(injection), "kartographSnapshotDebug"],
                             cwd=ROOT, timeout=600, phase="producer-tamper-snapshot")
        log = result.stdout + result.stderr
        (output / "tamper.log").write_text(log, encoding="utf-8")
        require(result.returncode != 0, "XML tamper unexpectedly published a snapshot")
        require("changed-android-resources" in log, "failure did not identify producer XML mismatch")
        prefix = "KARTOGRAPH_FIXTURE_XML_TAMPER_AFTER_PRODUCERS "
        markers = [line[len(prefix):] for line in log.splitlines() if line.startswith(prefix)]
        require(len(markers) == 1, "snapshot injection marker is missing or duplicated")
        boundary = json.loads(markers[0])
        require(boundary == {"witnessSha256": before_witness, "jarSha256": before_jars,
                             "originalXmlSha256": hashlib.sha256(original).hexdigest(),
                             "mutatedXmlSha256": hashlib.sha256(mutated).hexdigest()},
                "snapshot-entry producer identities or XML transition differ")
        require(xml.read_bytes() == mutated, "snapshot hook did not write the exact tampered XML")
        require(digest(witness) == before_witness and [digest(path) for path in jars] == before_jars,
                "producer witness or R.jar changed across the snapshot-entry tamper check")
        require(snapshot.is_file() and digest(snapshot) == before_snapshot,
                "failed capture changed the previously published snapshot")
    finally:
        xml.write_bytes(original)
        if injection is not None:
            injection.unlink()
    result = run_process(command + ["kartographSnapshotDebug", "--rerun-tasks"],
                         cwd=ROOT, timeout=600, phase="producer-restored-rebuild")
    (output / "rebuilt.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    require(result.returncode == 0, "restored producer rebuild failed")
    binary = ROOT / "cli/build/install/kartograph/bin/kartograph"
    result = run_process([str(binary), "verify-snapshot", "--graph-file", str(snapshot),
                             "--project", str(project), "--input-bindings",
                             str(project / "build/kartograph/debug-input-bindings.json")],
                            cwd=ROOT, timeout=600, phase="producer-rebuilt-verification")
    require(result.returncode == 0 and json.loads(result.stdout).get("status") == "matched",
            "rebuilt resource producer did not restore matched snapshot")
    print(json.dumps({"format": "kartograph-resource-producer-regression", "version": 1,
                      "xmlTamperAfterProducer": "rejected", "reason": "changed-android-resources",
                      "injectionPoint": "snapshot-doFirst-after-producer-dependencies", "witnessAndJarUnchanged": True,
                      "failedCaptureSnapshotUnchanged": True, "restoredRebuild": "matched"},
                     sort_keys=True))


if __name__ == "__main__":
    main()
