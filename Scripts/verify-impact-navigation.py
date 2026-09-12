#!/usr/bin/env python3
"""같은 저장 그래프에서 이전 CLI와 영향 탐색의 후보·경로·출력량·시간을 비교한다."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time


def fingerprint(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_paths(report):
    for item in report["affected"]:
        for path in item["paths"]:
            nodes, edges = path["nodes"], path["edges"]
            check(len(nodes) == len(edges) + 1, "invalid path length")
            check(nodes[0] == item["usr"] and nodes[-1] == path["changed"], "invalid path endpoints")
            for index, edge in enumerate(edges):
                direction = (edge["source"], edge["target"])
                if edge["traversal"] == "overrideContract":
                    direction = direction[::-1]
                else:
                    check(edge["traversal"] == "dependency", "unknown path traversal")
                check(direction == (nodes[index], nodes[index + 1]), "invalid edge direction")


def main():
    parser = argparse.ArgumentParser(description="Compare complete legacy impact output with navigation on identical captured inputs.")
    parser.add_argument("--baseline-binary", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--base-graph", required=True)
    parser.add_argument("--graph-file", required=True)
    parser.add_argument("--changed-file", action="append", required=True)
    parser.add_argument("--failure-file", required=True)
    parser.add_argument("--failure-target", required=True, help="exact USR from an independently executed failure oracle")
    parser.add_argument("--output", required=True, help="new local evidence directory")
    parser.add_argument("--repetitions", type=int, default=3, choices=range(1, 11))
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    baseline_binary = Path(args.baseline_binary).resolve(strict=True)
    binary = Path(args.binary).resolve(strict=True)
    inputs = {"base": Path(args.base_graph).resolve(strict=True), "current": Path(args.graph_file).resolve(strict=True)}
    common = ["impact", "--base-graph", str(inputs["base"]), "--graph-file", str(inputs["current"]), "--depth", "100"]
    for changed in args.changed_file:
        common += ["--file", changed]
    measurements = []
    hashes = {}

    def run(name, executable, options):
        path = output / (name + ".json")
        started = time.perf_counter()
        with path.open("w") as result, (output / (name + ".stderr")).open("w") as errors:
            process = subprocess.run([str(executable), *common, *options], stdout=result, stderr=errors,
                                     env=os.environ.copy(), timeout=120)
        seconds = time.perf_counter() - started
        check(process.returncode == 0, "impact command failed; inspect local evidence")
        check((output / (name + ".stderr")).stat().st_size == 0, "impact command emitted unexpected stderr")
        started = time.perf_counter()
        report = json.loads(path.read_text())
        decode_seconds = time.perf_counter() - started
        digest = fingerprint(path)
        if name in hashes:
            check(hashes[name] == digest, "repeated query output is not deterministic")
        hashes[name] = digest
        measurements.append({"name": name, "processSeconds": seconds, "decodeSeconds": decode_seconds,
                             "bytes": path.stat().st_size, "observed": report["observedAffected"],
                             "returned": len(report["affected"]), "sha256": digest})
        return report

    full_options = ["--all", "--path-limit", "500000"]
    focused_options = [*full_options, "--affected-file", args.failure_file, "--sort", "path"]
    baseline = candidate = focused = None
    for repeat in range(args.repetitions):
        # 실행 순서를 번갈아 OS cache 및 첫 실행의 편향을 드러낸다.
        if repeat % 2 == 0:
            baseline = run("baseline-full", baseline_binary, ["--limit", "100000"])
            candidate = run("candidate-full", binary, full_options)
        else:
            candidate = run("candidate-full", binary, full_options)
            baseline = run("baseline-full", baseline_binary, ["--limit", "100000"])
        focused = run("candidate-focused", binary, focused_options)

    expected_ids = {item["usr"] for item in baseline["affected"]}
    check(not any(baseline["truncated"].values()), "legacy baseline is incomplete")
    check(args.failure_target in expected_ids, "failure oracle target is absent from baseline")
    check({item["usr"] for item in candidate["affected"]} == expected_ids, "complete candidate set changed")
    check(candidate["observedAffected"] == len(expected_ids), "observed count differs from complete baseline")
    check(not any(candidate["truncated"].values()), "complete candidate export is truncated")
    check_paths(candidate)
    expected_focused = {item["usr"] for item in baseline["affected"]
                        if item.get("location") and item["location"]["path"] == args.failure_file}
    check({item["usr"] for item in focused["affected"]} == expected_focused, "focused candidate set differs from baseline file")
    check(args.failure_target in expected_focused, "failure target is not in the specified source file")
    check(focused["summary"]["observed"]["candidates"] == len(expected_ids), "filter changed observed summary")
    check(focused["summary"]["filtered"]["candidates"] == len(expected_focused), "incorrect focused summary")
    check_paths(focused)

    summary = run("candidate-summary", binary, ["--limit", "1", "--path-limit", "500000"])
    check(summary["summary"]["observed"]["candidates"] == len(expected_ids), "page changed observed summary")
    check(summary["summary"]["filtered"]["candidates"] == len(expected_ids), "page changed filtered summary")
    # 이 고정 입력에는 source/module 이동이 없다. 이동 시의 여러 bucket은 단위 테스트로 검증한다.
    for field, axis in [("module", "byModule"), ("location", "byFile")]:
        expected = Counter((item.get(field) or {}).get("path") if field == "location" else item.get(field)
                           for item in baseline["affected"])
        actual = {bucket["value"]: bucket["count"] for bucket in summary["summary"]["observed"][axis]}
        check(actual == dict(expected), "summary metadata differs from fixed baseline")

    paged = []
    for offset in range(0, len(expected_focused), 10):
        page = run("candidate-page-" + str(offset), binary,
                   ["--affected-file", args.failure_file, "--sort", "path", "--offset", str(offset),
                    "--limit", "10", "--path-limit", "500000"])
        check(page["navigation"]["hasPrevious"] == (offset > 0), "incorrect previous-page state")
        check(page["navigation"]["hasNext"] == (offset + 10 < len(expected_focused)), "incorrect next-page state")
        check(page["summary"]["filtered"]["candidates"] == len(expected_focused), "page changed filter count")
        paged += [item["usr"] for item in page["affected"]]
    check(paged == [item["usr"] for item in focused["affected"]], "pagination dropped, duplicated or reordered candidates")
    check(len(paged) == len(set(paged)), "pagination contains duplicates")

    medians = {name: statistics.median(row["processSeconds"] for row in measurements if row["name"] == name)
               for name in sorted({row["name"] for row in measurements})}
    artifacts = {}
    for label, executable in [("baseline", baseline_binary), ("candidate", binary)]:
        jars = sorted((executable.parent.parent / "lib").glob("*.jar"))
        check(bool(jars), "expected installed CLI distribution with lib jars")
        artifacts[label] = [{"name": jar.name, "sha256": fingerprint(jar)} for jar in jars]
    result = {"schemaVersion": 1, "inputFingerprints": {label: fingerprint(path) for label, path in inputs.items()},
              "cliArtifacts": artifacts, "changedFiles": args.changed_file, "failureFile": args.failure_file,
              "failureTarget": args.failure_target, "observedCandidates": len(expected_ids),
              "focusedCandidates": len(expected_focused),
              "baselineTargetOrdinal": next(index + 1 for index, item in enumerate(baseline["affected"])
                                            if item["usr"] == args.failure_target),
              "focusedTargetOrdinal": next(index + 1 for index, item in enumerate(focused["affected"])
                                           if item["usr"] == args.failure_target),
              "processSecondsMedian": medians, "measurements": measurements,
              "checks": {"candidateSet": True, "revisionPathDirections": True, "summaries": True,
                         "failureTarget": True, "pagination": True, "determinism": True},
              "limitations": ["An executed failure target is a lower-bound oracle, not a precision denominator.",
                              "The source file is known in this navigation task; blind AI repair is a separate evaluation.",
                              "No source build or snapshot capture is included in query process timing.",
                              "This fixed cohort has no moved source/module facts; movement semantics use separate tests."]}
    (output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Impact navigation comparison passed; results.json contains timings and evidence.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.TimeoutExpired):
        print("Impact navigation comparison failed; inspect the local evidence and input contract.", file=sys.stderr)
        sys.exit(1)
