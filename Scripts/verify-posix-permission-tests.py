#!/usr/bin/env python3
"""기존 chmod000 회귀가 비특권 POSIX에서 생략 없이 실행됐는지 확인한다."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {
    "dev.kartograph.index.SourceDirectoryFailureTest":
        "unreadable directory preserves sibling mappings and excludes build before opening(Path)",
    "dev.kartograph.index.AndroidXmlScannerTest":
        "unreadable resource directory fails without returning partial evidence(Path)",
}


def verify(directory):
    results = []
    for suite_name, case_name in REQUIRED.items():
        source = directory / f"TEST-{suite_name}.xml"
        data = source.read_bytes()
        suite = ET.fromstring(data)
        if suite.tag != "testsuite" or suite.get("name") != suite_name:
            raise ValueError(f"unexpected permission suite: {suite_name}")
        if int(suite.get("tests", "0")) <= 0 or any(int(suite.get(key, "-1")) != 0 for key in ("failures", "errors")):
            raise ValueError(f"empty or failed permission suite: {suite_name}")
        cases = [case for case in suite.findall("testcase")
                 if case.get("name") == case_name and case.get("classname") == suite_name]
        if len(cases) != 1 or any(cases[0].find(tag) is not None for tag in ("skipped", "failure", "error")):
            raise ValueError(f"required permission case absent, duplicated, skipped or failed: {suite_name}.{case_name}")
        results.append(dict(suite=suite_name, case=case_name, executed=1, skipped=0, failures=0, errors=0,
                            xmlSha256=hashlib.sha256(data).hexdigest()))
    return results


def main():
    if os.name != "posix" or os.geteuid() == 0:
        raise ValueError("permission evidence requires a non-privileged POSIX runner")
    cases = verify(ROOT / "index/build/test-results/test")
    report = dict(commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  effectiveUid=os.geteuid(), executed=len(cases), cases=cases)
    target = ROOT / "build/reports/posix-permission-tests.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
