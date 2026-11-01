#!/usr/bin/env python3
"""Linux에서 고정 입력의 두 CLI를 8쌍 비교한다. 필수 smoke 판정은 별도다."""

import argparse
import ast
import gzip
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import random
import re
import shutil
import signal
import stat
import statistics
import subprocess
import sys
import threading
import time
from typing import TypedDict
import zlib


MODULES = ("core", "index", "analysis", "export", "cli", "gradle-plugin")
NAMES = ("graph", "dead", "dead_private", "cycles", "rules", "metrics")
CONTRACTS = ("node_count", "zero_findings", "zero_findings", "zero_cycles", "zero_rule_violations", "valid_metrics")
PAIRS, RESAMPLES, SEED = 8, 10_000, 20261101
STREAM_LIMIT, TOTAL_LIMIT = 32 * 1024**2, 512 * 1024**2
DISK_STOP = int(5.5 * 1024**3)
SMOKE_SHA256 = "d8d4764592e758c87916bd522f489117fae9956453b72f5b15a49e7a9d7728a9"


class SuiteInput(TypedDict):
    pair: int | None
    arm: str
    warmup: bool


class Suite(SuiteInput):
    suite: int


class ErrorRecord(TypedDict):
    type: str
    reason: str


class SecondaryError(ErrorRecord):
    stage: str


class StreamReceipt(TypedDict, total=False):
    path: str
    bytes: int
    sha256: str
    gzip_path: str
    gzip_sha256: str
    partial_gzip_path: str


class Observation(Suite, total=False):
    command: str
    argv: list[str]
    exit: int
    seconds: float
    contract_failures: list[str]
    contract: dict[str, object]
    streams: dict[str, StreamReceipt]
    capture_error: str
    cleanup_error: str
    primary_error: ErrorRecord
    secondary_errors: list[SecondaryError]


class UnsupportedPlatform(ValueError):
    """자원 관측 계약이 없는 호스트를 측정 전에 구분한다."""


def error_record(error: BaseException) -> ErrorRecord:
    reason = str(error) if isinstance(error, (TimeoutError, ValueError, RuntimeError)) else \
        "errno-" + str(error.errno) if isinstance(error, OSError) else "interrupted-or-unclassified"
    return {"type": type(error).__name__, "reason": reason}


def primary_record(row: Observation, error: BaseException):
    row.setdefault("primary_error", error_record(error))


def secondary_record(row: Observation, stage: str, error: BaseException):
    row.setdefault("secondary_errors", []).append({"stage": stage, **error_record(error)})


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ValueError("module loader unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_sha(value):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("full lowercase commit SHA required")
    return value


def schedule() -> list[Suite]:
    result: list[SuiteInput] = [{"pair": None, "arm": arm, "warmup": True} for arm in ("baseline", "candidate")]
    for pair in range(PAIRS):
        arms = ("baseline", "candidate") if pair % 2 == 0 else ("candidate", "baseline")
        result.extend({"pair": pair, "arm": arm, "warmup": False} for arm in arms)
    return [Suite(pair=row["pair"], arm=row["arm"], warmup=row["warmup"], suite=i)
            for i, row in enumerate(result)]


def command_expression(node, values):
    """검토한 smoke의 정적 명령 list만 해석한다. 임의 Python은 실행하지 않는다."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.Name) and node.id in values:
        return values[node.id]
    if isinstance(node, (ast.List, ast.Tuple)):
        result = []
        for item in node.elts:
            if isinstance(item, ast.Starred):
                result.extend(command_expression(item.value, values))
            else:
                result.append(command_expression(item, values))
        return result
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "str" \
            and len(node.args) == 1 and not node.keywords:
        return str(command_expression(node.args[0], values))
    raise ValueError("unrecognized smoke command expression")


def commands(smoke_path, corpus, fixture, binary) -> list[tuple[str, list[str], str]]:
    if digest(smoke_path) != SMOKE_SHA256:
        raise ValueError("smoke evaluator identity changed")
    source = ast.parse(smoke_path.read_text())
    function = next((n for n in source.body if isinstance(n, ast.FunctionDef)
                     and n.name == "verify_self_analysis"), None)
    assignments = [] if function is None else [n.value for n in ast.walk(function)
        if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "commands" for t in n.targets)]
    if len(assignments) != 1:
        raise ValueError("missing or ambiguous smoke commands")
    values = {"binary": binary, "project_dir": fixture,
              "manifest_path": fixture / "AndroidManifest.xml", "res_dir": fixture / "res",
              "self_pro": corpus / ".kartograph-self.pro", "rules_yml": corpus / ".kartograph.yml",
              "class_args": [part for module in MODULES for part in
                  ("--classes", str(corpus / module / "build/classes/kotlin/main"))]}
    result = command_expression(assignments[0], values)
    if tuple(r[0] for r in result) != NAMES or tuple(r[2] for r in result) != CONTRACTS:
        raise ValueError("unrecognized command set or contracts")
    normalized = []
    for name, argv, contract in result:
        if not isinstance(name, str) or not isinstance(contract, str) or not isinstance(argv, list) \
                or not all(isinstance(value, str) for value in argv):
            raise ValueError("invalid command value types")
        normalized.append((name, [str(value) for value in argv], contract))
    return normalized


def statistics_for(rows):
    expected = [(suite, name) for suite in schedule() for name in NAMES]
    if len(rows) != len(expected):
        raise ValueError("all 108 scheduled command rows required")
    for row, (suite, name) in zip(rows, expected):
        if any(type(row.get(k)) is not type(v) or row[k] != v for k, v in suite.items()) \
                or row.get("command") != name or type(row.get("exit")) is not int or row["exit"] != 0 \
                or row.get("contract_failures") != [] or type(row.get("seconds")) not in (int, float) \
                or not math.isfinite(row["seconds"]) or row["seconds"] <= 0:
            raise ValueError("invalid or reordered paired observation")
    timed = [r for r in rows if not r["warmup"]]
    medians = {arm: {name: statistics.median(r["seconds"] for r in timed
                    if r["arm"] == arm and r["command"] == name) for name in NAMES}
               for arm in ("baseline", "candidate")}
    totals = {arm: [sum(r["seconds"] for r in timed if r["pair"] == pair and r["arm"] == arm)
                    for pair in range(PAIRS)] for arm in medians}
    differences = [right - left for left, right in zip(totals["baseline"], totals["candidate"])]
    generator = random.Random(SEED)
    bootstrap = sorted(statistics.mean(generator.choices(differences, k=PAIRS)) for _ in range(RESAMPLES))
    def quantile(p):
        position = (RESAMPLES - 1) * p
        low = int(position)
        return bootstrap[low] + (bootstrap[min(low + 1, RESAMPLES - 1)] - bootstrap[low]) * (position - low)
    interval = [quantile(.025), quantile(.975)]
    return {"command_medians_seconds": medians,
            "sum_command_medians_seconds": {arm: sum(v.values()) for arm, v in medians.items()},
            "block_totals_seconds": totals, "paired_candidate_minus_baseline_seconds": differences,
            "paired_mean_difference_seconds": statistics.mean(differences),
            "paired_bootstrap": {"statistic": "mean of eight paired total-block differences",
                "method": "percentile with linear interpolation", "resamples": RESAMPLES,
                "seed": SEED, "confidence": .95, "interval_seconds": interval},
            "speedup_demonstrated": interval[1] < 0}


def file_stamp(path):
    value = path.lstat()
    if stat.S_ISLNK(value.st_mode) or not (stat.S_ISREG(value.st_mode) or stat.S_ISDIR(value.st_mode)):
        raise ValueError("nonregular sealed input")
    return [value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns]


def seal(roots):
    result = []
    for token, root in roots.items():
        paths = [root, *sorted(root.rglob("*"))] if root.is_dir() else [root]
        if len(paths) > 100_000:
            raise ValueError("input entry count limit")
        for path in paths:
            before = file_stamp(path)
            row = {"root": token, "relative": str(path.relative_to(root)) if path != root else ".",
                   "stamp": before}
            if path.is_file():
                if before[3] > 512 * 1024**2:
                    raise ValueError("input file size limit")
                row["sha256"] = digest(path)
            if before != file_stamp(path):
                raise ValueError("input changed while sealing")
            result.append(row)
    return result


def verify_seal(roots, expected, full=False):
    if full:
        if seal(roots) != expected:
            raise ValueError("sealed inputs changed")
    else:
        for row in expected:
            if file_stamp(roots[row["root"]] / row["relative"]) != row["stamp"]:
                raise ValueError("sealed inputs changed")


class Capture:
    """기존 WNOWAIT lease로 두 파일 sink와 전체 출력 예산을 묶는다."""
    def __init__(self, output, cwd, env, owner, deadline, stream_limit=STREAM_LIMIT, total_limit=TOTAL_LIMIT):
        self.output, self.cwd, self.env, self.owner, self.deadline = output, cwd, env, owner, deadline
        self.stream_limit, self.total_limit, self.used = stream_limit, total_limit, 0
        self.lock = threading.Lock()

    def run(self, argv, stem, row: Observation, timeout=120):
        if self.used + 2 * self.stream_limit > self.total_limit:
            raise ValueError("total output reservation limit")
        # 두 stream의 최대 쓰기를 미리 예약하여 마지막 child도 중단 여유를 보존한다.
        if shutil.disk_usage(self.output).free < DISK_STOP + 2 * self.stream_limit:
            raise ValueError("5.5GiB disk stop")
        allowed = min(timeout, self.deadline - time.monotonic())
        if allowed <= 0:
            raise TimeoutError("overall deadline")
        paths = [self.output / (stem + suffix) for suffix in (".stdout", ".stderr")]
        row["argv"] = list(map(str, argv))
        counts, errors = [0, 0], []
        observe, census = self.owner.nonreaping_waiter(), self.owner.group_census()
        streams = []
        try:
            for path in paths:
                streams.append(path.open("xb"))
        except BaseException:
            for stream in streams:
                stream.close()
            raise
        process, owned, readers, primary = None, None, [], None
        started = time.monotonic()
        try:
            process = subprocess.Popen(argv, cwd=self.cwd, env=self.env, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, start_new_session=True)
            owned = self.owner.OwnedProcessGroup(process, observe, census)
            def drain(index, source):
                try:
                    for chunk in iter(lambda: source.read(65536), b""):
                        with self.lock:
                            if counts[index] + len(chunk) > self.stream_limit or self.used + len(chunk) > self.total_limit:
                                raise ValueError("bounded output limit")
                            streams[index].write(chunk)
                            counts[index] += len(chunk)
                            self.used += len(chunk)
                except BaseException as error:
                    errors.append(error)
                    owned.signal_group()
                finally:
                    source.close()
            readers = [threading.Thread(target=drain, args=(i, source), daemon=True)
                       for i, source in enumerate((process.stdout, process.stderr))]
            for reader in readers:
                reader.start()
            while not owned.ready():
                if time.monotonic() - started > allowed:
                    raise TimeoutError("child deadline")
                if shutil.disk_usage(self.output).free < DISK_STOP:
                    raise ValueError("5.5GiB disk stop")
                time.sleep(.01)
            row["seconds"] = time.monotonic() - started
            if row["seconds"] > allowed:
                raise TimeoutError("completed child exceeded deadline")
        except BaseException as error:
            primary = error
            row["capture_error"] = type(error).__name__
            primary_record(row, error)
        finally:
            def finalization_error(stage, error):
                nonlocal primary
                if primary is None:
                    primary = error
                    row["capture_error"] = type(error).__name__
                    primary_record(row, error)
                else:
                    secondary_record(row, stage, error)
            if owned is not None:
                try:
                    owned.close_signals()
                    if owned.lost:
                        raise RuntimeError("owned child lease lost")
                    if process is None:
                        raise RuntimeError("owned process reference missing")
                    row["exit"] = process.wait(timeout=10)
                except BaseException as error:
                    row["cleanup_error"] = type(error).__name__
                    finalization_error("owned-cleanup", error)
            for reader in readers:
                reader.join(timeout=10)
                if reader.is_alive():
                    finalization_error("reader-close", RuntimeError("owned stream did not close"))
            for stream in streams:
                try:
                    stream.close()
                except BaseException as error:
                    finalization_error("sink-close", error)
            receipts: dict[str, StreamReceipt] = {}
            row["streams"] = receipts
            for name, path in zip(("stdout", "stderr"), paths):
                receipt: StreamReceipt = {"path": path.name}
                receipts[name] = receipt
                try:
                    receipt["bytes"] = path.stat().st_size
                    receipt["sha256"] = digest(path)
                except BaseException as error:
                    finalization_error("stream-receipt-" + name, error)
        if errors:
            for error in errors:
                finalization_error("stream-reader", error)
        if primary is not None:
            row["capture_error"] = type(primary).__name__
            raise primary
        return paths


class DiskCheckedArchive:
    """gzip 헤더·압축 청크·종료 꼬리까지 같은 디스크 중단 기준을 적용한다."""
    def __init__(self, destination, parent):
        self.destination, self.parent = destination, parent

    def write(self, data):
        if shutil.disk_usage(self.parent).free < DISK_STOP + len(data):
            raise ValueError("archive disk stop")
        written = self.destination.write(data)
        if written != len(data):
            raise OSError("short archive write")
        return written

    def flush(self):
        return self.destination.flush()


def preserve(paths, row: Observation, output):
    receipts = row.get("streams")
    if receipts is None:
        raise ValueError("missing stream receipts")
    for name, path in zip(("stdout", "stderr"), paths):
        target = output / (path.name + ".gz")
        # 비압축 자료보다 큰 결과와 gzip 오버헤드까지 보수적으로 예약한다.
        reservation = path.stat().st_size * 2 + 1024
        if shutil.disk_usage(output).free < DISK_STOP + reservation:
            raise ValueError("archive disk reservation stop")
        try:
            source, destination, archive_primary = None, None, None
            try:
                source = path.open("rb")
                destination = target.open("xb", buffering=0)
                sink, compressor = DiskCheckedArchive(destination, output), zlib.compressobj(level=6, wbits=31)
                for chunk in iter(lambda: source.read(65536), b""):
                    sink.write(compressor.compress(chunk))
                sink.write(compressor.flush())
            except BaseException as error:
                archive_primary = error
                primary_record(row, error)
            finally:
                for stream in (source, destination):
                    if stream is not None:
                        try:
                            stream.close()
                        except BaseException as error:
                            if archive_primary is None:
                                archive_primary = error
                                primary_record(row, error)
                            else:
                                secondary_record(row, "archive-close", error)
            if archive_primary is not None:
                raise archive_primary
            receipts[name]["gzip_sha256"] = digest(target)
            receipts[name]["gzip_path"] = target.name
            path.unlink()
        except BaseException as error:
            primary_record(row, error)
            try:
                if target.exists() and "gzip_path" not in receipts[name]:
                    receipts[name]["partial_gzip_path"] = target.name
            except BaseException as secondary:
                secondary_record(row, "archive-partial-receipt", secondary)
            raise


def equal_raw(reference, current):
    with gzip.open(reference, "rb") as left, current.open("rb") as right:
        while True:
            a, b = left.read(65536), right.read(65536)
            if a != b:
                return False
            if not a:
                return True


def source_identity(owner, repo, expected, env):
    def git(*args):
        value = owner.run(["git", *args], repo, env, timeout=10, limit=65536, phase="source-identity")
        if value["exit"] != 0:
            raise ValueError("source identity command failed")
        return value["stdout"].decode("utf-8", errors="strict").strip()
    head, dirty = git("rev-parse", "HEAD"), git("status", "--porcelain", "--untracked-files=no")
    if head != expected or dirty:
        raise ValueError("source identity mismatch or dirty tracked tree")
    return {"head": head, "tree": git("rev-parse", "HEAD^{tree}")}


def copy_input(source, destination):
    """고정 입력을 복사하는 동안에도 파일·디스크 예산을 먼저 확인한다."""
    source, destination = Path(source), Path(destination)
    before = file_stamp(source)
    if not source.is_file() or before[3] > 512 * 1024**2:
        raise ValueError("invalid frozen input file")
    total = 0
    with source.open("rb") as incoming, destination.open("xb") as outgoing:
        for chunk in iter(lambda: incoming.read(65536), b""):
            total += len(chunk)
            if total > before[3] or shutil.disk_usage(destination.parent).free < DISK_STOP + len(chunk):
                raise ValueError("frozen copy size or disk budget")
            outgoing.write(chunk)
    if total != before[3] or before != file_stamp(source):
        raise ValueError("source changed during frozen copy")
    shutil.copystat(source, destination, follow_symlinks=False)
    return str(destination)


def qualify(args):
    validate_sha(args.baseline_sha)
    validate_sha(args.target_sha)
    validate_sha(args.harness_sha)
    harness = Path(__file__).resolve().parents[1]
    owner = load_module("pair_owned", harness / "Scripts/verify-index-cost.py")
    repeatability = load_module("pair_environment", harness / "Scripts/verify-cli-repeatability.py")
    baseline, candidate, output = args.baseline_root.resolve(), args.repo_root.resolve(), args.output.resolve()
    if shutil.disk_usage(output.parent).free < DISK_STOP:
        raise ValueError("5.5GiB disk stop")
    output.mkdir(parents=True, exist_ok=False)
    (output / "tmp").mkdir()
    env = repeatability.cli_environment(os.environ, output / "tmp")
    result = {"status": "incomplete", "scope": "Linux fixed-input paired comparison; not mandatory smoke acceptance",
              "protocol": {"pairs": PAIRS, "bootstrap_resamples": RESAMPLES, "seed": SEED,
                           "stream_limit_bytes": STREAM_LIMIT, "total_raw_limit_bytes": TOTAL_LIMIT},
              "rows": [], "sources": {}, "helper_sha256": digest(Path(__file__)),
              "environment_keys": sorted(env),
              "environment_sha256": hashlib.sha256(json.dumps(env, sort_keys=True).encode()).hexdigest()}
    def save(primary=None):
        active = primary or sys.exc_info()[1]
        try:
            (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        except BaseException as error:
            if active is None:
                raise
            result.setdefault("secondary_receipt_errors", []).append(type(error).__name__)
            print("secondary receipt failure: " + type(error).__name__, file=sys.stderr)
    roots, sealed, primary = None, None, None
    old_handler = signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt("terminated")))
    try:
        result["platform_preflight"] = {"required": "linux", "observed": sys.platform,
                                        "supported": sys.platform == "linux"}
        if sys.platform != "linux":
            raise UnsupportedPlatform("Linux qualification host required")
        java = Path(env["JAVA_HOME"]).resolve(strict=True)
        for arm, repo, sha in (("baseline", baseline, args.baseline_sha), ("candidate", candidate, args.target_sha),
                              ("harness", harness, args.harness_sha)):
            result["sources"][arm] = source_identity(owner, repo, sha, env)
            if digest(repo / "Scripts/verify-analysis-smoke-gate.py") != SMOKE_SHA256:
                raise ValueError("smoke evaluator identity changed")
        corpus, fixture = output / "corpus", output / "fixture"
        corpus.mkdir()
        for module in MODULES:
            relative = Path(module) / "build/classes/kotlin/main"
            source = baseline / relative
            if not any(source.rglob("*.class")):
                raise ValueError("empty baseline production class root")
            shutil.copytree(source, corpus / relative, symlinks=True, copy_function=copy_input)
        for name in (".kartograph-self.pro", ".kartograph.yml"):
            copy_input(baseline / name, corpus / name)
        (fixture / "res").mkdir(parents=True)
        (fixture / "AndroidManifest.xml").write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
            '    <application/>\n</manifest>\n')
        roots = {"corpus": corpus, "fixture": fixture,
                 "baseline_install": baseline / "cli/build/install/kartograph",
                 "candidate_install": candidate / "cli/build/install/kartograph",
                 "jdk_release": java / "release", "jdk_java": java / "bin/java", "jdk_modules": java / "lib/modules",
                 "harness_helper": Path(__file__), "harness_owned": harness / "Scripts/verify-index-cost.py",
                 "harness_environment": harness / "Scripts/verify-cli-repeatability.py",
                 "harness_workflow": harness / ".github/workflows/cli-repeatability.yml",
                 "baseline_evaluator": baseline / "Scripts/verify-analysis-smoke-gate.py",
                 "candidate_evaluator": candidate / "Scripts/verify-analysis-smoke-gate.py"}
        vm = java / "lib/server/libjvm.so"
        roots["jdk_vm"] = vm
        sealed = seal(roots)
        result["sealed_inputs"] = sealed
        smoke = load_module("pair_smoke", baseline / "Scripts/verify-analysis-smoke-gate.py")
        declared = {arm: commands(baseline / "Scripts/verify-analysis-smoke-gate.py", corpus, fixture,
                    repo / "cli/build/install/kartograph/bin/kartograph")
                    for arm, repo in (("baseline", baseline), ("candidate", candidate))}
        capture = Capture(output, baseline, env, owner, time.monotonic() + 900)
        references: dict[str, dict[str, str]] = {}
        result["resource_policy"] = repeatability.resources()["policy"]
        for suite in schedule():
            for name, argv, contract in declared[suite["arm"]]:
                verify_seal(roots, sealed)
                if repeatability.resources()["policy"] != result["resource_policy"]:
                    raise ValueError("resource policy changed")
                row: Observation = {**suite, "command": name, "contract_failures": []}
                result["rows"].append(row)
                stem = f"suite-{suite['suite']:02d}-{suite['arm']}-{name}"
                paths = [output / (stem + suffix) for suffix in (".stdout", ".stderr")]
                row_primary = None
                try:
                    capture.run(argv, stem, row)
                    if name in references and any(not equal_raw(output / references[name][stream], path)
                            for stream, path in zip(("stdout", "stderr"), paths)):
                        raise ValueError("raw output parity mismatch")
                    exit_code = row.get("exit")
                    if type(exit_code) is not int:
                        raise ValueError("missing integer child exit")
                    observation: dict[str, object] = {}
                    contract_failures: list[str] = []
                    smoke.check_contract(name, contract, subprocess.CompletedProcess(argv, exit_code,
                        paths[0].read_text(), paths[1].read_text()), observation, contract_failures)
                    row["contract_failures"] = contract_failures
                    row["contract"] = observation
                    if exit_code != 0 or contract_failures:
                        raise ValueError("failed child or semantic contract")
                except BaseException as error:
                    row_primary = error
                    primary_record(row, error)
                finally:
                    try:
                        if all(p.exists() for p in paths) and "streams" in row:
                            preserve(paths, row, output)
                    except BaseException as error:
                        if row_primary is None:
                            row_primary = error
                            primary_record(row, error)
                        else:
                            secondary_record(row, "archive", error)
                    finally:
                        save(row_primary)
                if row_primary is not None:
                    raise row_primary
                streams = row.get("streams")
                if streams is None:
                    raise ValueError("missing stream receipts after capture")
                archive_paths = {}
                for stream in ("stdout", "stderr"):
                    gzip_path = streams[stream].get("gzip_path")
                    if not isinstance(gzip_path, str):
                        raise ValueError("missing complete archive")
                    archive_paths[stream] = gzip_path
                references.setdefault(name, archive_paths)
        result["statistics"] = statistics_for(result["rows"])
        result["status"] = "speedup-demonstrated" if result["statistics"]["speedup_demonstrated"] else "no-proven-speedup"
    except BaseException as error:
        primary = error
        result.update(status="unsupported-os" if isinstance(error, UnsupportedPlatform) else "failed-or-incomplete",
                      error=error_record(error))
    finally:
        try:
            if sealed is not None:
                verify_seal(roots, sealed, full=True)
                result["sealed_inputs_unchanged"] = True
            if result["sources"]:
                for arm, repo, sha in (("baseline", baseline, args.baseline_sha), ("candidate", candidate, args.target_sha),
                                      ("harness", harness, args.harness_sha)):
                    if source_identity(owner, repo, sha, env) != result["sources"].get(arm):
                        raise ValueError("source identity changed")
        except BaseException as error:
            primary = primary or error
            result.update(status="failed-or-incomplete", final_integrity_error=type(error).__name__)
        result["finished_utc_ns"] = time.time_ns()
        try:
            save(primary)
        finally:
            signal.signal(signal.SIGTERM, old_handler)
    if isinstance(primary, UnsupportedPlatform):
        print(json.dumps({"status": "unsupported-os", "result_sha256": digest(output / "result.json")}))
        return 2
    if primary is not None:
        raise primary
    print(json.dumps({"status": result["status"], "result_sha256": digest(output / "result.json")}))
    return 0 if result["status"] == "speedup-demonstrated" else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-root", required=True, type=Path)
    parser.add_argument("--baseline-sha", required=True)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--harness-sha", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        validate_sha(args.baseline_sha)
        validate_sha(args.target_sha)
        validate_sha(args.harness_sha)
    except ValueError as error:
        parser.error(str(error))
    return qualify(args)


if __name__ == "__main__":
    sys.exit(main())
