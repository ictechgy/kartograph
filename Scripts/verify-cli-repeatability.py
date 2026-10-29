#!/usr/bin/env python3
"""동일 CLI의 고정 A/A 반복성을 검사한다. 제품 성능 게이트를 대체하지 않는다."""

import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import signal
import statistics
import subprocess
import sys
import time


MODULES = ("core", "index", "analysis", "export", "cli", "gradle-plugin")
NAMES = ("graph", "dead", "dead_private", "cycles", "rules", "metrics")
PAIRS = ("AB", "BA", "BA", "AB", "AB", "BA")
ENV_KEYS = ("JAVA_HOME", "PATH", "HOME", "LANG", "LC_ALL", "TZ")
POLICY_FILES = ("cpu.max", "cpuset.cpus.effective", "memory.max")
COUNTER_FILES = ("cpu.stat", "cpu.pressure", "memory.current", "memory.events")
STREAM_LIMITS = (64 * 1024 * 1024, 8 * 1024 * 1024)


def cli_environment(parent, temporary):
    env = {key: parent[key] for key in ENV_KEYS if key in parent}
    env["TMPDIR"] = str(temporary)
    return env


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def file_rows(root, paths):
    return [{"path": str(p.relative_to(root)), "bytes": p.stat().st_size,
             "sha256": digest(p)} for p in sorted(paths)]


def read_small(path, limit=256 * 1024):
    try:
        with path.open("rb") as stream:
            data = stream.read(limit + 1)
        if len(data) > limit:
            return {"unavailable": "size-limit"}
        return data.decode("utf-8", errors="strict").strip()
    except (OSError, UnicodeError) as error:
        return {"unavailable": type(error).__name__}


def cgroup_directory():
    text = read_small(Path("/proc/self/cgroup"))
    if isinstance(text, str):
        for line in text.splitlines():
            if line.startswith("0::"):
                root = Path("/sys/fs/cgroup").resolve()
                candidate = (root / line[3:].lstrip("/")).resolve()
                if candidate.is_relative_to(root):
                    return candidate
    return None


def resources():
    # 실행 중 JVM을 관찰하지 않고 각 명령의 타이머 밖에서만 읽는다.
    cgroup = cgroup_directory()
    stat = read_small(Path("/proc/stat"))
    memory = read_small(Path("/proc/meminfo"))
    policy = {"affinity": sorted(os.sched_getaffinity(0)),
              "observed_leaf_cgroup": str(cgroup) if cgroup else None}
    counters = {}
    for name in POLICY_FILES + COUNTER_FILES:
        value = read_small(cgroup / name) if cgroup else {"unavailable": "no-cgroup-v2"}
        (policy if name in POLICY_FILES else counters)[name] = value
    return {
        "utc_ns": time.time_ns(), "monotonic_ns": time.monotonic_ns(),
        "policy": policy, "cgroup_counters": counters,
        "cpu_stat": stat.splitlines()[0] if isinstance(stat, str) else stat,
        "loadavg": read_small(Path("/proc/loadavg")),
        "cpu_pressure": read_small(Path("/proc/pressure/cpu")),
        "mem_available": next((x for x in memory.splitlines() if x.startswith("MemAvailable:")),
                              "not-reported") if isinstance(memory, str) else memory,
    }


def schedule():
    suites = [{"pair": None, "label": label, "warmup": True} for label in "AB"]
    for pair, order in enumerate(PAIRS):
        suites.extend({"pair": pair, "label": label, "warmup": False} for label in order)
    return [dict(row, suite=index) for index, row in enumerate(suites)]


def commands(repo, fixture):
    binary = repo / "cli/build/install/kartograph/bin/kartograph"
    classes = [part for module in MODULES
               for part in ("--classes", str(repo / module / "build/classes/kotlin/main"))]
    dead = [str(binary), "dead", *classes, "--project", str(fixture),
            "--manifest", str(fixture / "AndroidManifest.xml"),
            "--resources", str(fixture / "res"), "--namespace", "dev.kartograph",
            "--keep-rules", str(repo / ".kartograph-self.pro")]
    return [
        ("graph", [str(binary), "graph", *classes, "--format", "dot"], "node_count"),
        ("dead", [*dead, "--strict"], "zero_findings"),
        ("dead_private", [*dead, "--include-private-members", "--strict"], "zero_findings"),
        ("cycles", [str(binary), "cycles", *classes, "--strict"], "zero_cycles"),
        ("rules", [str(binary), "rules", *classes, "--config", str(repo / ".kartograph.yml"),
                   "--strict"], "zero_rule_violations"),
        ("metrics", [str(binary), "metrics", *classes], "valid_metrics"),
    ]


def statistic(a, b):
    if len(a) != 6 or len(b) != 6 or any(type(x) not in (int, float) or not math.isfinite(x)
                                      or x <= 0 for x in a + b):
        raise ValueError("six finite positive paired observations are required")
    differences = [2 * (right - left) / (left + right) for left, right in zip(a, b)]
    maximum = max(abs(x) for x in differences)
    imbalance = abs(statistics.median(differences))
    spread = max(a + b) / min(a + b)
    checks = {"all_pairs_within_5_percent": maximum <= .05,
              "median_imbalance_within_2_5_percent": imbalance <= .025,
              "full_range_at_most_1_10": spread <= 1.10}
    return {"A": a, "B": b, "signed_symmetric_differences": differences,
            "maximum_absolute_difference": maximum, "absolute_median_difference": imbalance,
            "max_min_ratio": spread, "checks": checks, "passed": all(checks.values())}


def grade(rows):
    expected = [(suite, name) for suite in schedule() for name in NAMES]
    if len(rows) != len(expected):
        raise ValueError("all 84 scheduled rows are required")
    for row, (suite, name) in zip(rows, expected):
        if any(type(row[key]) is not type(value) or row[key] != value for key, value in suite.items()) \
                or type(row["command"]) is not str or row["command"] != name:
            raise ValueError("fixed schedule changed")
        if type(row["returncode"]) is not int or row["returncode"] != 0 \
                or type(row["contract_failures"]) is not list or row["contract_failures"]:
            raise ValueError("all command contracts must pass")
        if type(row["seconds"]) not in (int, float) or not math.isfinite(row["seconds"]) \
                or row["seconds"] <= 0:
            raise ValueError("all walls must be finite and positive")
    timed = [row for row in rows if not row["warmup"]]
    results = {}
    for name in (*NAMES, "suite_total"):
        by_label = {"A": [], "B": []}
        for pair in range(6):
            for label in by_label:
                selected = [r for r in timed if r["pair"] == pair and r["label"] == label
                            and (name == "suite_total" or r["command"] == name)]
                by_label[label].append(sum(r["seconds"] for r in selected))
        results[name] = statistic(by_label["A"], by_label["B"])
    return {"statistics": results, "qualified": all(r["passed"] for r in results.values())}


class Capture:
    def __init__(self, output, cwd, env, deadline):
        self.output, self.cwd, self.env, self.deadline = output, cwd, env, deadline
        self.unreaped = False

    def remaining(self):
        value = self.deadline - time.monotonic()
        if value <= 0:
            raise TimeoutError("overall deadline")
        return value

    def run(self, argv, stem, row, timeout=120, limits=STREAM_LIMITS):
        stdout, stderr = (self.output / (stem + suffix) for suffix in (".stdout", ".stderr"))
        row.update(argv=argv, stdout=stdout.name, stderr=stderr.name)
        process, reaped, primary = None, False, None
        try:
            with stdout.open("xb") as out, stderr.open("xb") as err:
                allowed = min(timeout, self.remaining())
                started = time.monotonic()
                process = subprocess.Popen(argv, cwd=self.cwd, env=self.env,
                                           stdout=out, stderr=err, start_new_session=True)
                last_size_check = 0.0
                while True:
                    pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                    if pid:
                        reaped = True
                        process.returncode = os.waitstatus_to_exitcode(status)
                        row.update(returncode=process.returncode, seconds=time.monotonic() - started,
                                   user_seconds=usage.ru_utime, system_seconds=usage.ru_stime,
                                   max_rss_kib=usage.ru_maxrss)
                        if row["seconds"] > allowed:
                            raise TimeoutError("completed child exceeded deadline")
                        break
                    now = time.monotonic()
                    if now - started > allowed:
                        raise TimeoutError("child deadline")
                    if now - last_size_check >= .05:
                        if any(p.stat().st_size > cap for p, cap in zip((stdout, stderr), limits)):
                            raise ValueError("stream limit")
                        last_size_check = now
                    time.sleep(.001)
            if row["returncode"] != 0:
                raise RuntimeError("child returned " + str(row["returncode"]))
        except BaseException as error:
            primary = error
            row["error"] = type(error).__name__ + ": " + str(error)
        finally:
            if process is not None and not reaped:
                try:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    cleanup_deadline = time.monotonic() + 5
                    while time.monotonic() < cleanup_deadline:
                        pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                        if pid:
                            reaped = True
                            process.returncode = os.waitstatus_to_exitcode(status)
                            row.update(returncode=process.returncode,
                                       user_seconds=usage.ru_utime, system_seconds=usage.ru_stime,
                                       max_rss_kib=usage.ru_maxrss)
                            break
                        time.sleep(.001)
                    if not reaped:
                        raise RuntimeError("owned child did not reap within cleanup bound")
                except BaseException as error:
                    row["cleanup_error"] = type(error).__name__ + ": " + str(error)
                    if primary is None:
                        primary = error
                self.unreaped = not reaped
            for name, path, cap in zip(("stdout", "stderr"), (stdout, stderr), limits):
                try:
                    size = path.stat().st_size
                    row[name + "_bytes"] = size
                    row[name + "_overflow"] = size > cap
                    if size <= cap:
                        row[name + "_sha256"] = digest(path)
                    elif primary is None:
                        primary = ValueError("post-exit stream limit")
                except BaseException as error:
                    row[name + "_capture_error"] = type(error).__name__ + ": " + str(error)
                    if primary is None:
                        primary = error
        if primary is not None:
            raise primary
        self.remaining()
        return stdout, stderr


def source_identity(repo, target):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=repo, text=True, timeout=10).strip()
    head = git("rev-parse", "HEAD")
    dirty = git("status", "--porcelain", "--untracked-files=no")
    if head != target or dirty:
        raise ValueError("target source identity or tracked files changed")
    return {"head": head, "tree": git("rev-parse", "HEAD^{tree}"), "tracked_status": dirty}


def snapshot(repo, fixture, java_home, target):
    install = repo / "cli/build/install/kartograph"
    corpus = {}
    for module in MODULES:
        root = repo / module / "build/classes/kotlin/main"
        files = list(root.rglob("*.class"))
        if not files:
            raise ValueError("empty class root: " + module)
        corpus[module] = file_rows(repo, files)
    return {
        "source": source_identity(repo, target), "corpus": corpus,
        "install": file_rows(install, [p for p in install.rglob("*") if p.is_file()]),
        "contracts": file_rows(repo, [repo / p for p in (
            ".kartograph-self.pro", ".kartograph.yml", "Scripts/verify-analysis-smoke-gate.py")]),
        "fixture": file_rows(fixture, [p for p in fixture.rglob("*") if p.is_file()]),
        "fixture_directories": sorted(str(p.relative_to(fixture)) for p in fixture.rglob("*") if p.is_dir()),
        "jdk": file_rows(java_home, [java_home / p for p in ("release", "bin/java", "lib/server/libjvm.so")]),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    if not re.fullmatch("[0-9a-f]{40}", args.target_sha):
        parser.error("target-sha must be a full lowercase commit SHA")
    repo, output = args.repo_root.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    result = {"scope": "Prospective same-executable A/A qualification; not the production smoke gate",
              "status": "incomplete", "schedule": schedule(), "rows": [], "suites": [], "metadata": [],
              "helper_sha256": digest(Path(__file__)), "target_sha": args.target_sha}
    def save():
        active = sys.exc_info()[1]
        try:
            (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        except BaseException as error:
            if active is None:
                raise
            result.setdefault("secondary_receipt_errors", []).append(type(error).__name__)
            print("secondary receipt failure: " + type(error).__name__, file=sys.stderr)
    primary, capture = None, None
    def terminate(signum, frame):
        raise KeyboardInterrupt("termination requested")
    old_handler = signal.signal(signal.SIGTERM, terminate)
    try:
        env = cli_environment(os.environ, output / "tmp")
        java_home = Path(env["JAVA_HOME"]).resolve()
        (output / "tmp").mkdir()
        result["environment"] = env
        result["runner"] = {key: os.environ.get(key) for key in (
            "RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion",
            "GITHUB_WORKFLOW_REF", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")}
        result["host"] = {"uname": list(os.uname()), "logical_cpus": os.cpu_count(),
                          "cpu_model": next((line for line in str(read_small(Path("/proc/cpuinfo"))).splitlines()
                                             if line.startswith("model name")), "unavailable")}
        fixture = output / "fixture"
        (fixture / "res").mkdir(parents=True)
        (fixture / "AndroidManifest.xml").write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
            '    <application/>\n</manifest>\n')
        capture = Capture(output, repo, env, time.monotonic() + 900)
        harness = Path(__file__).resolve().parents[1]
        harness_paths = [harness / p for p in ("Scripts/verify-cli-repeatability.py",
                         ".github/workflows/cli-repeatability.yml", "experiments/cli-repeatability/README.md")]
        result["harness_before"] = file_rows(harness, harness_paths)
        result["before"] = snapshot(repo, fixture, java_home, args.target_sha)
        policy = resources()["policy"]
        result["resource_policy"] = policy
        spec = importlib.util.spec_from_file_location("smoke", repo / "Scripts/verify-analysis-smoke-gate.py")
        smoke = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(smoke)
        declared = commands(repo, fixture)
        result["commands"] = declared
        references = {}
        def metadata(endpoint):
            for kind, flags in (("flags", ["-XX:+PrintFlagsFinal"]), ("system", ["-XshowSettings:system"])):
                row = {"endpoint": endpoint, "kind": kind}
                result["metadata"].append(row)
                try:
                    capture.run([str(java_home / "bin/java"), *flags, "-version"],
                                endpoint + "-" + kind, row, timeout=10, limits=(1024 * 1024,) * 2)
                finally:
                    save()
        metadata("before")
        for suite in schedule():
            suite_start = time.monotonic()
            suite_row = dict(suite)
            result["suites"].append(suite_row)
            suite_rows = []
            for name, command, contract in declared:
                row = dict(suite, command=name)
                result["rows"].append(row)
                suite_rows.append(row)
                try:
                    row["resources_before"] = resources()
                    if row["resources_before"]["policy"] != policy:
                        raise ValueError("resource policy changed")
                    stdout, stderr = capture.run(command, f"suite-{suite['suite']:02d}-{suite['label']}-{name}", row)
                    row["resources_after"] = resources()
                    if row["resources_after"]["policy"] != policy:
                        raise ValueError("resource policy changed")
                    identity = (row["stdout_sha256"], row["stderr_sha256"])
                    if identity != references.setdefault(name, identity):
                        raise ValueError("raw output parity failed: " + name)
                    observation, failures = {}, []
                    smoke.check_contract(name, contract, subprocess.CompletedProcess(
                        command, row["returncode"], stdout.read_text(), stderr.read_text()), observation, failures)
                    row.update(contract=observation, contract_failures=failures)
                    if failures:
                        raise ValueError("semantic contract failed: " + name)
                finally:
                    save()
            suite_row["sum_command_seconds"] = sum(row["seconds"] for row in suite_rows)
            suite_row["enclosing_seconds"] = time.monotonic() - suite_start
            save()
        metadata("after")
        result["after"] = snapshot(repo, fixture, java_home, args.target_sha)
        result["harness_after"] = file_rows(harness, harness_paths)
        if result["before"] != result["after"]:
            raise ValueError("source, corpus, installation or fixture identity changed")
        if result["harness_before"] != result["harness_after"]:
            raise ValueError("diagnostic source identity changed")
        capture.remaining()
        result["score"] = grade(result["rows"])
        result["status"] = "qualified" if result["score"]["qualified"] else "not-qualified"
    except BaseException as error:
        primary = error
        result["error"] = type(error).__name__ + ": " + str(error)
        result["status"] = "failed-or-incomplete"
        result["owned_child_unreaped"] = bool(capture and capture.unreaped)
    finally:
        result["finished_utc_ns"] = time.time_ns()
        try:
            save()
        except BaseException as error:
            if primary is None:
                primary = error
            else:
                print("secondary receipt failure: " + type(error).__name__, file=sys.stderr)
        signal.signal(signal.SIGTERM, old_handler)
    if primary is not None:
        raise primary
    print(json.dumps({"status": result["status"], "commands": len(result["rows"]),
                      "result_sha256": digest(output / "result.json")}))
    return 0 if result["status"] == "qualified" else 1


if __name__ == "__main__":
    sys.exit(main())
