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
import tempfile
import threading
import time
from typing import TypedDict
import zlib


MODULES = ("core", "index", "analysis", "export", "cli", "gradle-plugin")
NAMES = ("graph", "dead", "dead_private", "cycles", "rules", "metrics")
CONTRACTS = ("node_count", "zero_findings", "zero_findings", "zero_cycles", "zero_rule_violations", "valid_metrics")
PAIRS, RESAMPLES, SEED = 8, 10_000, 20261101
STREAM_LIMIT, GRAPH_STDOUT_LIMIT, TOTAL_LIMIT = 32 * 1024**2, 64 * 1024**2, 2 * 1024**3
DISK_STOP = int(5.5 * 1024**3)
DRIVER_SECONDS, FINALIZATION_SECONDS = 840, 900
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


class CaptureReceipt(TypedDict, total=False):
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


class Observation(Suite, CaptureReceipt):
    pass


class UnsupportedPlatform(ValueError):
    """자원 관측 계약이 없는 호스트를 측정 전에 구분한다."""


def error_record(error: BaseException) -> ErrorRecord:
    reason = str(error) if isinstance(error, (TimeoutError, ValueError, RuntimeError)) else \
        "errno-" + str(error.errno) if isinstance(error, OSError) else "interrupted-or-unclassified"
    return {"type": type(error).__name__, "reason": reason}


def primary_record(row: CaptureReceipt, error: BaseException):
    row.setdefault("primary_error", error_record(error))


def secondary_record(row: CaptureReceipt, stage: str, error: BaseException):
    row.setdefault("secondary_errors", []).append({"stage": stage, **error_record(error)})


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ValueError("module loader unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def remaining(deadline, maximum):
    if deadline is None:
        return maximum
    value = deadline - time.monotonic()
    if value <= 0:
        raise TimeoutError("driver deadline")
    return min(maximum, value)


def check_deadline(deadline):
    remaining(deadline, math.inf)


def digest(path, deadline=None):
    check_deadline(deadline)
    h = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            check_deadline(deadline)
            h.update(chunk)
    check_deadline(deadline)
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


def commands(smoke_path, corpus, fixture, binary, deadline=None) -> list[tuple[str, list[str], str]]:
    if digest(smoke_path, deadline) != SMOKE_SHA256:
        raise ValueError("smoke evaluator identity changed")
    source = ast.parse(smoke_path.read_text())
    check_deadline(deadline)
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


def seal(roots, deadline=None):
    result = []
    for token, root in roots.items():
        check_deadline(deadline)
        paths = [root]
        if root.is_dir():
            for path in root.rglob("*"):
                check_deadline(deadline)
                paths.append(path)
                if len(paths) > 100_000:
                    raise ValueError("input entry count limit")
            paths[1:] = sorted(paths[1:])
        for path in paths:
            check_deadline(deadline)
            before = file_stamp(path)
            row = {"root": token, "relative": str(path.relative_to(root)) if path != root else ".",
                   "stamp": before}
            if path.is_file():
                if before[3] > 512 * 1024**2:
                    raise ValueError("input file size limit")
                row["sha256"] = digest(path, deadline)
            if before != file_stamp(path):
                raise ValueError("input changed while sealing")
            result.append(row)
    return result


def verify_seal(roots, expected, full=False, deadline=None):
    check_deadline(deadline)
    if full:
        if seal(roots, deadline) != expected:
            raise ValueError("sealed inputs changed")
    else:
        for row in expected:
            check_deadline(deadline)
            if file_stamp(roots[row["root"]] / row["relative"]) != row["stamp"]:
                raise ValueError("sealed inputs changed")


class Capture:
    """기존 WNOWAIT lease로 두 파일 sink와 전체 출력 예산을 묶는다."""
    def __init__(self, output, cwd, env, owner, deadline, stream_limit=STREAM_LIMIT, total_limit=TOTAL_LIMIT,
                 finalization_deadline=None):
        self.output, self.cwd, self.env, self.owner, self.deadline = output, cwd, env, owner, deadline
        self.stream_limit, self.total_limit, self.used = stream_limit, total_limit, 0
        self.finalization_deadline = finalization_deadline
        self.lock = threading.Lock()

    def run(self, argv, stem, row: CaptureReceipt, timeout=120, stdout_limit=None):
        command_start = time.monotonic()
        command_deadline = min(self.deadline, command_start + timeout)
        if stdout_limit is not None and (row.get("command") != "graph" or type(stdout_limit) is not int
                                        or not 0 < stdout_limit <= GRAPH_STDOUT_LIMIT):
            raise ValueError("invalid graph stdout limit override")
        stream_limits = (self.stream_limit if stdout_limit is None else stdout_limit, self.stream_limit)
        reservation = sum(stream_limits)
        if self.used + reservation > self.total_limit:
            raise ValueError("total output reservation limit")
        # 두 stream의 최대 쓰기를 미리 예약하여 마지막 child도 중단 여유를 보존한다.
        if shutil.disk_usage(self.output).free < DISK_STOP + reservation:
            raise ValueError("5.5GiB disk stop")
        if time.monotonic() >= command_deadline:
            raise TimeoutError("overall deadline")
        paths = [self.output / (stem + suffix) for suffix in (".stdout", ".stderr")]
        row["argv"] = list(map(str, argv))
        counts = [0, 0]
        observe, census = self.owner.nonreaping_waiter(), self.owner.group_census()
        if time.monotonic() >= command_deadline:
            raise TimeoutError("overall deadline")
        streams = []
        process, owned, readers = None, None, []
        handoff_lock, old_handlers, pending_signals = threading.Lock(), {}, []
        pending_delivered = False
        primary: BaseException | None = None
        failure_lock = threading.Lock()
        def record_error(stage: str, error: BaseException):
            # reader와 main의 최초 관측을 같은 순서로 기록한 뒤에만 종료 신호를 보낸다.
            nonlocal primary
            with failure_lock:
                if primary is None:
                    primary = error
                    row["capture_error"] = type(error).__name__
                    primary_record(row, error)
                else:
                    secondary_record(row, stage, error)
        started = time.monotonic()
        def restore_handlers():
            for number, handler in list(old_handlers.items()):
                signal.signal(number, handler)
                del old_handlers[number]
        try:
            for path in paths:
                streams.append(path.open("xb"))
            check_deadline(command_deadline)
            # OS mask는 바꾸지 않는다. Popen 반환값과 lease가 결합되기 전 Python
            # 취소 예외만 미뤄서 handle을 잃는 assignment 공백을 닫는다.
            with handoff_lock:
                for number in (signal.SIGTERM, signal.SIGINT):
                    handler = signal.getsignal(number)
                    if handler != signal.SIG_IGN:
                        old_handlers[number] = signal.signal(number, lambda number, frame: pending_signals.append(number))
                check_deadline(command_deadline)
                process = subprocess.Popen(argv, cwd=self.cwd, env=self.env, stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE, start_new_session=True)
                owned = self.owner.OwnedProcessGroup(process, observe, census)
                restore_handlers()
            if pending_signals:
                pending_delivered = True
                raise KeyboardInterrupt("interrupted during child handoff")
            def drain(index, source):
                try:
                    for chunk in iter(lambda: source.read(65536), b""):
                        with self.lock:
                            if counts[index] + len(chunk) > stream_limits[index] or self.used + len(chunk) > self.total_limit:
                                raise ValueError("bounded output limit")
                            streams[index].write(chunk)
                            counts[index] += len(chunk)
                            self.used += len(chunk)
                except BaseException as error:
                    record_error("stream-reader", error)
                    try:
                        owned.signal_group()
                    except BaseException as secondary:
                        record_error("reader-signal", secondary)
                finally:
                    try:
                        source.close()
                    except BaseException as error:
                        record_error("reader-source-close", error)
            readers = [threading.Thread(target=drain, args=(i, source), daemon=True)
                       for i, source in enumerate((process.stdout, process.stderr))]
            for reader in readers:
                reader.start()
            while not owned.ready():
                if time.monotonic() >= command_deadline:
                    raise TimeoutError("child deadline")
                if shutil.disk_usage(self.output).free < DISK_STOP:
                    raise ValueError("5.5GiB disk stop")
                time.sleep(.01)
            finished = time.monotonic()
            row["seconds"] = finished - started
            if finished >= command_deadline:
                raise TimeoutError("completed child exceeded deadline")
        except BaseException as error:
            record_error("child-control", error)
        finally:
            if owned is not None:
                try:
                    owned.close_signals()
                except BaseException as error:
                    row.setdefault("cleanup_error", type(error).__name__)
                    record_error("owned-signal-close", error)
                try:
                    if process is None:
                        raise RuntimeError("owned process reference missing")
                    if owned.lost:
                        raise RuntimeError("owned child lease lost")
                    row["exit"] = process.wait(timeout=remaining(self.finalization_deadline, 10))
                except BaseException as error:
                    row.setdefault("cleanup_error", type(error).__name__)
                    record_error("owned-reap", error)
            elif process is not None:
                # constructor 오류 경로에는 reader가 없다. 같은 handoff lock에서
                # 원래 WNOWAIT 관측으로 direct child를 증명한 뒤에만 신호한다.
                leased = False
                with handoff_lock:
                    try:
                        if process.returncode is not None:
                            raise RuntimeError("unbound child lease lost")
                        terminal = observe(process.pid)
                        leased = True
                        if not terminal or census(process.pid):
                            self.owner.kill_group(process)
                    except BaseException as error:
                        record_error("handoff-signal-close", error)
                    # 이 지점 뒤에는 unbound numeric PGID 신호 경로를 다시 열지 않는다.
                    if leased:
                        try:
                            row["exit"] = process.wait(timeout=remaining(self.finalization_deadline, 10))
                        except BaseException as error:
                            record_error("handoff-reap", error)
            for reader in readers:
                try:
                    reader.join(timeout=remaining(self.finalization_deadline, 10))
                    if reader.is_alive():
                        raise RuntimeError("owned stream did not close")
                except BaseException as error:
                    record_error("reader-close", error)
            if process is not None:
                for index, source in enumerate((process.stdout, process.stderr)):
                    if source is not None and (index >= len(readers) or readers[index].ident is None):
                        try:
                            source.close()
                        except BaseException as error:
                            record_error("unstarted-source-close", error)
            try:
                restore_handlers()
            except BaseException as error:
                record_error("signal-handler-restore", error)
            if pending_signals and not pending_delivered:
                record_error("deferred-interruption", KeyboardInterrupt("interrupted during child handoff"))
            for stream in streams:
                try:
                    stream.close()
                except BaseException as error:
                    record_error("sink-close", error)
            receipts: dict[str, StreamReceipt] = {}
            row["streams"] = receipts
            for name, path in zip(("stdout", "stderr"), paths):
                receipt: StreamReceipt = {"path": path.name}
                receipts[name] = receipt
                try:
                    receipt["bytes"] = path.stat().st_size
                    receipt["sha256"] = digest(path, self.finalization_deadline)
                except BaseException as error:
                    record_error("stream-receipt-" + name, error)
        if primary is not None:
            row["capture_error"] = type(primary).__name__
            raise primary
        return paths


class DiskCheckedArchive:
    """gzip 헤더·압축 청크·종료 꼬리까지 같은 디스크 중단 기준을 적용한다."""
    def __init__(self, destination, parent, deadline=None):
        self.destination, self.parent, self.deadline = destination, parent, deadline

    def write(self, data):
        check_deadline(self.deadline)
        if shutil.disk_usage(self.parent).free < DISK_STOP + len(data):
            raise ValueError("archive disk stop")
        written = self.destination.write(data)
        if written != len(data):
            raise OSError("short archive write")
        return written

    def flush(self):
        return self.destination.flush()


def preserve(paths, row: CaptureReceipt, output, deadline=None):
    receipts = row.get("streams")
    if receipts is None:
        raise ValueError("missing stream receipts")
    for name, path in zip(("stdout", "stderr"), paths):
        check_deadline(deadline)
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
                sink, compressor = DiskCheckedArchive(destination, output, deadline), zlib.compressobj(level=6, wbits=31)
                read = source.read
                for chunk in iter(lambda: read(65536), b""):
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
            receipts[name]["gzip_sha256"] = digest(target, deadline)
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


def equal_raw(reference, current, deadline=None):
    check_deadline(deadline)
    with gzip.open(reference, "rb") as left, current.open("rb") as right:
        while True:
            check_deadline(deadline)
            a, b = left.read(65536), right.read(65536)
            if a != b:
                return False
            if not a:
                return True


def source_identity(owner, repo, expected, env, deadline=None):
    check_deadline(deadline)
    temporary = tempfile.TemporaryDirectory(prefix="pair-identity-", dir=env.get("TMPDIR"))
    primary, row = None, CaptureReceipt()
    try:
        capture = Capture(Path(temporary.name), repo, env, owner, deadline if deadline is not None else math.inf,
                          stream_limit=65536, total_limit=6 * 65536, finalization_deadline=deadline)
        ordinal = 0
        def git(*args):
            nonlocal ordinal, row
            row = CaptureReceipt()
            paths = capture.run(["git", *args], "identity-" + str(ordinal), row, timeout=remaining(deadline, 10))
            ordinal += 1
            check_deadline(deadline)
            if row.get("exit") != 0:
                raise ValueError("source identity command failed")
            return paths[0].read_text(encoding="utf-8", errors="strict").strip()
        head, dirty = git("rev-parse", "HEAD"), git("status", "--porcelain", "--untracked-files=no")
        if head != expected or dirty:
            raise ValueError("source identity mismatch or dirty tracked tree")
        return {"head": head, "tree": git("rev-parse", "HEAD^{tree}")}
    except BaseException as error:
        primary = error
        primary_record(row, error)
        setattr(error, "identity_receipt", row)
        raise
    finally:
        try:
            temporary.cleanup()
        except BaseException as error:
            if primary is None:
                primary_record(row, error)
                setattr(error, "identity_receipt", row)
                raise
            secondary_record(row, "identity-temp-cleanup", error)


def copy_input(source, destination, deadline=None):
    """고정 입력을 복사하는 동안에도 파일·디스크 예산을 먼저 확인한다."""
    source, destination = Path(source), Path(destination)
    check_deadline(deadline)
    before = file_stamp(source)
    if not source.is_file() or before[3] > 512 * 1024**2:
        raise ValueError("invalid frozen input file")
    total = 0
    with source.open("rb") as incoming, destination.open("xb") as outgoing:
        for chunk in iter(lambda: incoming.read(65536), b""):
            check_deadline(deadline)
            total += len(chunk)
            if total > before[3] or shutil.disk_usage(destination.parent).free < DISK_STOP + len(chunk):
                raise ValueError("frozen copy size or disk budget")
            outgoing.write(chunk)
    check_deadline(deadline)
    if total != before[3] or before != file_stamp(source):
        raise ValueError("source changed during frozen copy")
    shutil.copystat(source, destination, follow_symlinks=False)
    return str(destination)


def qualify(args, entered=None):
    entered = time.monotonic() if entered is None else entered
    deadline, finalization_deadline = entered + DRIVER_SECONDS, entered + FINALIZATION_SECONDS
    validate_sha(args.baseline_sha)
    validate_sha(args.target_sha)
    validate_sha(args.harness_sha)
    harness = Path(__file__).resolve().parents[1]
    check_deadline(deadline)
    owner = load_module("pair_owned", harness / "Scripts/verify-index-cost.py")
    check_deadline(deadline)
    repeatability = load_module("pair_environment", harness / "Scripts/verify-cli-repeatability.py")
    check_deadline(deadline)
    baseline, candidate, output = args.baseline_root.resolve(), args.repo_root.resolve(), args.output.resolve()
    if shutil.disk_usage(output.parent).free < DISK_STOP:
        raise ValueError("5.5GiB disk stop")
    output.mkdir(parents=True, exist_ok=False)
    (output / "tmp").mkdir()
    env = repeatability.cli_environment(os.environ, output / "tmp")
    check_deadline(deadline)
    result = {"status": "incomplete", "scope": "Linux fixed-input paired comparison; not mandatory smoke acceptance",
              "protocol": {"pairs": PAIRS, "bootstrap_resamples": RESAMPLES, "seed": SEED,
                           "stream_limit_bytes": STREAM_LIMIT, "total_raw_limit_bytes": TOTAL_LIMIT,
                           "stream_limit_scope": "default per stream; graph stdout uses the explicit override",
                           "default_stdout_limit_bytes": STREAM_LIMIT, "graph_stdout_limit_bytes": GRAPH_STDOUT_LIMIT,
                           "stderr_limit_bytes": STREAM_LIMIT,
                           "total_raw_limit_scope": "cumulative uncompressed bytes processed by CLI captures; not resident memory",
                           "driver_seconds_from_entry": DRIVER_SECONDS, "finalization_seconds_from_entry": FINALIZATION_SECONDS},
              "rows": [], "sources": {}, "helper_sha256": digest(Path(__file__)),
              "python_version": sys.version,
              "environment_keys": sorted(env),
              "environment_sha256": hashlib.sha256(json.dumps(env, sort_keys=True).encode()).hexdigest()}
    def save(primary=None):
        active = primary or sys.exc_info()[1]
        try:
            temporary = output / "result.json.tmp"
            temporary.write_text(json.dumps(result, indent=2) + "\n")
            temporary.replace(output / "result.json")
        except BaseException as error:
            if active is None:
                raise
            result.setdefault("secondary_receipt_errors", []).append(type(error).__name__)
            print("secondary receipt failure: " + type(error).__name__, file=sys.stderr)
    roots, sealed, primary = None, None, None
    old_handler = signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt("terminated")))
    try:
        check_deadline(deadline)
        result["platform_preflight"] = {"required": "linux", "observed": sys.platform,
                                        "supported": sys.platform == "linux"}
        if sys.platform != "linux":
            raise UnsupportedPlatform("Linux qualification host required")
        java = Path(env["JAVA_HOME"]).resolve(strict=True)
        for arm, repo, sha in (("baseline", baseline, args.baseline_sha), ("candidate", candidate, args.target_sha),
                              ("harness", harness, args.harness_sha)):
            result["sources"][arm] = source_identity(owner, repo, sha, env, deadline)
            if digest(repo / "Scripts/verify-analysis-smoke-gate.py", deadline) != SMOKE_SHA256:
                raise ValueError("smoke evaluator identity changed")
        corpus, fixture = output / "corpus", output / "fixture"
        corpus.mkdir()
        for module in MODULES:
            check_deadline(deadline)
            relative = Path(module) / "build/classes/kotlin/main"
            source = baseline / relative
            if not any(source.rglob("*.class")):
                raise ValueError("empty baseline production class root")
            shutil.copytree(source, corpus / relative, symlinks=True,
                            copy_function=lambda source, destination: copy_input(source, destination, deadline))
        for name in (".kartograph-self.pro", ".kartograph.yml"):
            copy_input(baseline / name, corpus / name, deadline)
        check_deadline(deadline)
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
        sealed = seal(roots, deadline)
        result["sealed_inputs"] = sealed
        smoke = load_module("pair_smoke", baseline / "Scripts/verify-analysis-smoke-gate.py")
        check_deadline(deadline)
        declared = {arm: commands(baseline / "Scripts/verify-analysis-smoke-gate.py", corpus, fixture,
                    repo / "cli/build/install/kartograph/bin/kartograph", deadline)
                    for arm, repo in (("baseline", baseline), ("candidate", candidate))}
        check_deadline(deadline)
        capture = Capture(output, baseline, env, owner, deadline, finalization_deadline=finalization_deadline)
        references: dict[str, dict[str, str]] = {}
        result["resource_policy"] = repeatability.resources()["policy"]
        for suite in schedule():
            for name, argv, contract in declared[suite["arm"]]:
                check_deadline(deadline)
                verify_seal(roots, sealed, deadline=deadline)
                if repeatability.resources()["policy"] != result["resource_policy"]:
                    raise ValueError("resource policy changed")
                row: Observation = {**suite, "command": name, "contract_failures": []}
                result["rows"].append(row)
                stem = f"suite-{suite['suite']:02d}-{suite['arm']}-{name}"
                paths = [output / (stem + suffix) for suffix in (".stdout", ".stderr")]
                row_primary = None
                try:
                    if name == "graph":
                        capture.run(argv, stem, row, stdout_limit=GRAPH_STDOUT_LIMIT)
                    else:
                        capture.run(argv, stem, row)
                    check_deadline(deadline)
                    if name in references and any(not equal_raw(output / references[name][stream], path, deadline)
                            for stream, path in zip(("stdout", "stderr"), paths)):
                        raise ValueError("raw output parity mismatch")
                    exit_code = row.get("exit")
                    if type(exit_code) is not int:
                        raise ValueError("missing integer child exit")
                    observation: dict[str, object] = {}
                    contract_failures: list[str] = []
                    smoke.check_contract(name, contract, subprocess.CompletedProcess(argv, exit_code,
                        paths[0].read_text(), paths[1].read_text()), observation, contract_failures)
                    check_deadline(deadline)
                    row["contract_failures"] = contract_failures
                    row["contract"] = observation
                    if exit_code != 0 or contract_failures:
                        raise ValueError("failed child or semantic contract")
                except BaseException as error:
                    row_primary = error
                    primary_record(row, error)
                finally:
                    # 원래 실패를 압축·전체 무결성 재검사보다 먼저 원자적으로 남긴다.
                    if row_primary is not None:
                        result.update(status="failed-or-incomplete", error=error_record(row_primary))
                        save(row_primary)
                    try:
                        if all(p.exists() for p in paths) and "streams" in row:
                            preserve(paths, row, output, finalization_deadline)
                    except BaseException as error:
                        if row_primary is None:
                            row_primary = error
                            primary_record(row, error)
                        else:
                            secondary_record(row, "archive", error)
                    finally:
                        if row_primary is not None:
                            result.update(status="failed-or-incomplete", error=error_record(row_primary))
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
        check_deadline(deadline)
        result["statistics"] = statistics_for(result["rows"])
        check_deadline(deadline)
        result["status"] = "speedup-demonstrated" if result["statistics"]["speedup_demonstrated"] else "no-proven-speedup"
    except BaseException as error:
        primary = error
        result.update(status="unsupported-os" if isinstance(error, UnsupportedPlatform) else "failed-or-incomplete",
                      error=error_record(error))
        identity_receipt = getattr(error, "identity_receipt", None)
        if identity_receipt is not None:
            result["source_identity_failure"] = identity_receipt
        save(primary)
    finally:
        try:
            if sealed is not None:
                verify_seal(roots, sealed, full=True, deadline=finalization_deadline)
                result["sealed_inputs_unchanged"] = True
            if result["sources"]:
                for arm, repo, sha in (("baseline", baseline, args.baseline_sha), ("candidate", candidate, args.target_sha),
                                      ("harness", harness, args.harness_sha)):
                    if source_identity(owner, repo, sha, env, finalization_deadline) != result["sources"].get(arm):
                        raise ValueError("source identity changed")
        except BaseException as error:
            if primary is None:
                primary = error
                result["error"] = error_record(error)
            result.update(status="failed-or-incomplete", final_integrity_error=type(error).__name__,
                          final_integrity_failure=error_record(error))
            identity_receipt = getattr(error, "identity_receipt", None)
            if identity_receipt is not None:
                result["final_source_identity_failure"] = identity_receipt
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
    entered = time.monotonic()
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
    return qualify(args, entered=entered)


if __name__ == "__main__":
    sys.exit(main())
