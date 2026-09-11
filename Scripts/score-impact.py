#!/usr/bin/env python3
"""실제 테스트 전이와 공통 영향 CLI를 대조한다. 영향 그래프 알고리즘은 재구현하지 않는다."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys
import time


def identity(test):
    # 현재 채점 대상은 인자 없는 JVM 테스트다. display name을 추측해야 하는 사례는 분리한다.
    name = test["method"]
    if "[" in name or ("(" in name and not name.endswith("()")):
        return None
    if name.endswith("()"):
        name = name[:-2]
    return "method:" + test["class"].replace(".", "/") + "#" + name + "()V"


def main():
    parser = argparse.ArgumentParser(description="Score potential impact against reproduced JVM test transitions.")
    for name in ("binary", "base-graph", "graph-file", "before-tests", "after-tests", "files", "output"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    if args.repeats < 1:
        raise RuntimeError("repeats must be positive")
    before = json.loads(Path(args.before_tests).read_text())["tests"]
    after = json.loads(Path(args.after_tests).read_text())["tests"]
    old = {identity(test): test["status"] for test in before if identity(test) is not None}
    new = {identity(test): test["status"] for test in after if identity(test) is not None}
    if old.keys() - new.keys():
        raise RuntimeError("tests disappeared from the fixed run; oracle results are incomplete")
    targets = {test for test in old if old[test] == "failed"}
    if any(new[test] != "passed" for test in targets):
        raise RuntimeError("the fixed oracle did not repair every observed failure")
    regressions = {test for test in old if old[test] == "passed" and new[test] != "passed"}
    if not targets:
        raise RuntimeError("no reproduced fail-to-pass test; this sample cannot score failure-target recall")
    rows = {}
    for mode in ["preflight", "comparison"]:
        command = [str(Path(args.binary).resolve()), "impact", "--files-from", str(Path(args.files).resolve()),
            "--graph-file", str(Path(args.base_graph if mode == "preflight" else args.graph_file).resolve()),
            "--depth", "100", "--limit", "100000"]
        if mode == "comparison":
            command += ["--base-graph", str(Path(args.base_graph).resolve())]
        times = []
        previous = None
        for _ in range(args.repeats):
            start = time.perf_counter()
            run = subprocess.run(command, capture_output=True, text=True, timeout=120)
            times.append(time.perf_counter() - start)
            if run.returncode:
                raise RuntimeError("impact command did not resolve the evaluation inputs")
            document = json.loads(run.stdout)
            if previous is not None and document != previous:
                raise RuntimeError("repeated impact reports differ")
            previous = document
        if any(document["truncated"].values()):
            raise RuntimeError("truncated impact cannot be scored as a complete candidate set")
        predicted = {item["usr"] for item in document["changed"] + document["affected"]}
        rows[mode] = {"reproducedFailureTargets": len(targets), "targetsFound": len(targets & predicted),
            "missedTargets": sorted(targets - predicted), "affectedSymbols": document["observedAffected"],
            "selectedKnownTests": len(predicted & old.keys()), "knownTests": len(old),
            "seconds": times, "medianSeconds": statistics.median(times),
            "p95Seconds": sorted(times)[max(0, math.ceil(len(times) * .95) - 1)],
            "limitations": document["limitations"],
            "failurePaths": {item["usr"]: item["paths"] for item in document["affected"] if item["usr"] in targets}}
    report = {"format": "kartograph-impact-score", "version": 1, "modes": rows,
        "newFailures": sorted(regressions), "unmappedTestDisplayNames": sum(identity(t) is None for t in before),
        "inputs": {name: hashlib.sha256(Path(value).read_bytes()).hexdigest() for name, value in [
            ("baseSnapshot", args.base_graph), ("currentSnapshot", args.graph_file),
            ("beforeTests", args.before_tests), ("afterTests", args.after_tests), ("changedFiles", args.files)]},
        "interpretation": "Failure-target recall is relative to the supplied executed tests; other selected tests may still be affected. This is not permission to skip tests."}
    Path(args.output).write_text(json.dumps(report, indent=2) + "\n")
    print("Impact scoring completed:",len(targets),"reproduced failure target(s),",len(regressions),"new failure(s)")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("error: " + (str(error) if isinstance(error, RuntimeError) else "unable to score impact inputs"), file=sys.stderr)
        raise SystemExit(2)
