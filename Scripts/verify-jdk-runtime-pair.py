#!/usr/bin/env python3
"""승인된 한 VM의 JDK patch 대조다. 필수 smoke gate를 대체하지 않는다."""

import argparse
import hashlib
import importlib.util
import itertools
import json
import math
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tarfile
import time
import urllib.parse
import urllib.request
import zipfile


SPEC = importlib.util.spec_from_file_location("repeatability", Path(__file__).with_name("verify-cli-repeatability.py"))
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("repeatability helper is unavailable")
R = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(R)
TARGET = "741903922f011aab92888c395226f8e4d4f4b6a9"
MIB, GIB = 1024 ** 2, 1024 ** 3
FLOOR = 11 * GIB // 2
RAW_CAP, ZIP_CAP, INSTALL_CAP = GIB, 127 * MIB, 2 * GIB
RSS_CAP = 12 * GIB
PINS = (
    ("A", "17.0.20.1+1", "OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz", 193252603,
     "3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e"),
    ("B", "17.0.21+9", "OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz", 193594705,
     "8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711"),
)
EXPECTED = {
    "corpus": "9a9aa3a02933de3037f9fcd6736492d6f0d3653cc7ff9745e2e03aae49a39c90",
    "install": "0ce7f4609312c066e555bd026967c1852565184ed3f94186ed12cece84558ce1",
    "contracts": "a931a8f864709e8a569f074b91c7c0212f194b96638f37f6aea72f02ea5362b0",
    "fixture": "5f184f7264e2c2948e6021bd552dd903f7d72f59964d1efb30565da4061dba2e",
}


def canonical(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def receipt(path, value):
    text = json.dumps(value, indent=2) + "\n"
    if len(text.encode()) > 12 * MIB:
        raise ValueError("receipt size limit")
    path.write_text(text)


def preserve(path, value):
    primary = sys.exc_info()[1]
    try:
        receipt(path, value)
    except BaseException as error:
        if primary is None:
            raise
        print("secondary receipt failure: " + type(error).__name__, file=sys.stderr)


def deadline(seconds):
    limit = float(os.environ.get("PAIR_DEADLINE_MONOTONIC", str(time.monotonic() + seconds)))
    if not math.isfinite(limit) or limit <= time.monotonic():
        raise TimeoutError("overall diagnostic deadline")
    return min(time.monotonic() + seconds, limit)


def free_guard(path, required=FLOOR):
    free = shutil.disk_usage(path).free
    if free < required:
        raise ValueError("disk reserve: " + str(free))
    return free


def runtime_root():
    base = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    return base / "kartograph-runtime-pair"


def file_tree(root):
    rows = []
    for p in sorted(root.rglob("*")):
        mode = p.lstat().st_mode & 0o7777
        row = {"path": str(p.relative_to(root)), "mode": mode}
        if p.is_symlink():
            if not p.resolve(strict=True).is_relative_to(root):
                raise ValueError("symlink outside root")
            row["link"] = os.readlink(p)
        elif p.is_file():
            row.update(bytes=p.stat().st_size, sha256=R.digest(p))
        elif p.is_dir():
            row["directory"] = True
        else:
            raise ValueError("unsupported input type")
        rows.append(row)
    return rows


def extract_runtime(archive, root, version, cap=768 * MIB):
    """검증된 archive도 경로·중복·link graph·해제 크기를 검사한 뒤 수동 복원한다."""
    prefix = "jdk-" + version
    with tarfile.open(archive, "r:gz") as tar:
        members, total = {}, 0
        for member in tar:
            name = member.name.rstrip("/")
            path = PurePosixPath(name)
            if (not name or path.is_absolute() or str(path) != name or ".." in path.parts
                    or path.parts[0] != prefix or name in members or len(members) >= 20000
                    or member.mode & 0o7000 or member.size < 0
                    or not (member.isfile() or member.isdir() or member.issym() or member.islnk())):
                raise ValueError("unsafe runtime member")
            if member.isfile():
                total += member.size
                if total > cap:
                    raise ValueError("expanded runtime size limit")
            elif member.size:
                raise ValueError("non-file runtime payload")
            members[name] = member
        if prefix not in members or not members[prefix].isdir():
            raise ValueError("missing runtime root")
        for name in members:
            for parent in PurePosixPath(name).parents:
                if str(parent) == ".":
                    continue
                if str(parent) not in members or not members[str(parent)].isdir():
                    raise ValueError("runtime parent is not a directory")

        def resolve_link(name, seen=()):
            if name in seen or len(seen) > 40 or name not in members:
                raise ValueError("dangling or cyclic runtime link")
            member = members[name]
            if not (member.issym() or member.islnk()):
                return name
            if PurePosixPath(member.linkname).is_absolute():
                raise ValueError("absolute runtime link")
            target = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname)
                                        if member.issym() else member.linkname)
            if target != prefix and not target.startswith(prefix + "/"):
                raise ValueError("runtime link escapes root")
            return resolve_link(target, (*seen, name))

        for name in members:
            resolve_link(name)
        for name, member in sorted(members.items(), key=lambda item: len(PurePosixPath(item[0]).parts)):
            destination = root / name
            if member.isdir():
                destination.mkdir(mode=0o755)
            elif member.isfile():
                stream = tar.extractfile(member)
                if stream is None:
                    raise ValueError("missing runtime bytes")
                with stream, destination.open("xb") as out:
                    remaining = member.size
                    while remaining:
                        free_guard(root)
                        block = stream.read(min(MIB, remaining))
                        if not block:
                            raise ValueError("truncated runtime member")
                        out.write(block)
                        remaining -= len(block)
                destination.chmod(member.mode & 0o777)
        for name, member in members.items():
            destination = root / name
            if member.issym():
                os.symlink(member.linkname, destination)
            elif member.islnk():
                os.link(root / resolve_link(name), destination)
        for name, member in members.items():
            if member.isdir():
                (root / name).chmod(member.mode & 0o777)
    return total


def prepare(output):
    root = runtime_root()
    result = {"status": "incomplete", "runtimes": []}
    try:
        result["free_before"] = free_guard(root.parent, 9 * GIB)
        preparation_deadline = deadline(1200)
        root.mkdir()
        for label, version, filename, size, sha in PINS:
            row = {"label": label, "version": version, "archive": filename,
                   "expected_bytes": size, "expected_sha256": sha}
            result["runtimes"].append(row)
            url = ("https://github.com/adoptium/temurin17-binaries/releases/download/jdk-"
                   + urllib.parse.quote(version, safe="") + "/" + filename)
            row["official_url"] = url
            path = root / filename
            start, count, digest = time.monotonic(), 0, hashlib.sha256()
            with urllib.request.urlopen(url, timeout=30) as response, path.open("xb") as out:
                host = urllib.parse.urlsplit(response.url).hostname
                if response.status != 200 or host not in {
                        "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}:
                    raise ValueError("unexpected runtime download origin")
                row["download_host"] = host
                while True:
                    free_guard(root)
                    if time.monotonic() - start > 600 or time.monotonic() >= preparation_deadline:
                        raise TimeoutError("runtime download deadline")
                    block = response.read(MIB)
                    if not block:
                        break
                    count += len(block)
                    if count > size:
                        raise ValueError("runtime archive size limit")
                    digest.update(block)
                    out.write(block)
            row.update(actual_bytes=count, actual_sha256=digest.hexdigest())
            if count != size or digest.hexdigest() != sha:
                raise ValueError("runtime archive identity mismatch")
            row["expanded_regular_bytes"] = extract_runtime(path, root, version)
            java = root / ("jdk-" + version)
            release = (java / "release").read_text()
            if ('IMPLEMENTOR="Eclipse Adoptium"' not in release
                    or 'JAVA_RUNTIME_VERSION="' + version + '"' not in release):
                raise ValueError("runtime release identity mismatch")
            row["release"] = release
            total = sum(p.stat().st_size for p in root.rglob("*") if p.is_file())
            if total > INSTALL_CAP:
                raise ValueError("combined runtime workspace size limit")
            row["workspace_bytes_after"] = total
            receipt(output / "runtime-preparation.json", result)
        result.update(status="prepared-not-yet-executed", free_after=free_guard(root))
    except BaseException as error:
        result.update(status="failed-or-incomplete", error=type(error).__name__ + ": " + str(error))
        raise
    finally:
        preserve(output / "runtime-preparation.json", result)


def schedule() -> list[dict]:
    suites = [{"pair": None, "label": label, "warmup": True} for label in ("A", "B1")]
    for pair, order in enumerate(itertools.permutations(("A", "B1", "B2"))):
        suites.extend({"pair": pair, "label": label, "warmup": False} for label in order)
    return [dict(row, suite=index) for index, row in enumerate(suites)]


def grade(rows):
    expected = [(suite, name) for suite in schedule() for name in R.NAMES]
    if len(rows) != len(expected):
        raise ValueError("all 120 scheduled rows are required")
    for row, (suite, name) in zip(rows, expected):
        if (any(type(row.get(k)) is not type(v) or row.get(k) != v for k, v in suite.items())
                or row.get("command") != name or type(row.get("returncode")) is not int
                or row["returncode"] != 0 or type(row.get("contract_failures")) is not list
                or row["contract_failures"] or type(row.get("seconds")) not in (int, float)
                or not math.isfinite(row["seconds"]) or row["seconds"] <= 0):
            raise ValueError("schedule, command or finite observation mismatch")
    noise, contrast, old_range, budgets = {}, {}, {}, {label: {} for label in ("A", "B1", "B2")}
    for name in (*R.NAMES, "suite_total"):
        values = {label: [sum(r["seconds"] for r in rows if not r["warmup"]
                              and r["pair"] == pair and r["label"] == label
                              and (name == "suite_total" or r["command"] == name))
                          for pair in range(6)] for label in budgets}
        noise[name] = R.statistic(values["B1"], values["B2"])
        old_range[name] = {"max_min_ratio": max(values["A"]) / min(values["A"]),
                           "passed": max(values["A"]) / min(values["A"]) <= 1.10}
        averaged = [(a + b) / 2 for a, b in zip(values["B1"], values["B2"])]
        contrast[name] = R.statistic(values["A"], averaged)
        for label in budgets:
            budgets[label][name] = statistics.median(values[label])
    for values in budgets.values():
        values["sum_of_command_medians"] = sum(values[name] for name in R.NAMES)
    return {"noise": noise, "runtime_contrast_descriptive_only": contrast,
            "old_runtime_range_checks": old_range,
            "noise_qualified": all(v["passed"] for v in [*noise.values(), *old_range.values()]),
            "diagnostic_medians_not_mandatory_three_pass_gate": budgets}


def pidfd_handle(pid: int) -> int:
    function = getattr(os, "pidfd_open", None)
    if not callable(function):
        raise RuntimeError("Linux pidfd_open is required; no numeric-PID fallback")
    fd = function(pid, 0)
    if type(fd) is not int or fd < 0:
        raise RuntimeError("invalid owned process handle")
    return fd


def signal_handle(fd):
    function = getattr(signal, "pidfd_send_signal", None)
    if not callable(function):
        raise RuntimeError("Linux pidfd_send_signal is required; no numeric-PID fallback")
    function(fd, signal.SIGKILL, None, 0)


class Guards:
    def __init__(self, output):
        self.output, self.raw_bytes = output, 0
        self.owned = {}
        self.last_progress = time.monotonic()

    def observe(self, pid, root, row, parent=None, group=False):
        proc = Path("/proc") / str(pid)
        fd = None
        try:
            stat = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
            if group and (int(stat[2]) != root or int(stat[3]) != root):
                raise ValueError("owned process group/session changed")
            if parent is not None and int(stat[1]) != parent:
                row["descendant_disappeared_between_samples"] = True
                return 0, []
            birth = stat[19]
            if pid in self.owned and self.owned[pid][0] == birth:
                fd = self.owned[pid][1]
            else:
                if len(self.owned) >= 128:
                    raise ValueError("owned process handle limit")
                fd = pidfd_handle(pid)
            # open한 handle의 process와 읽은 /proc identity가 같은지 재검증한다.
            after = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
            if after[19] != birth or (parent is not None and int(after[1]) != parent):
                raise ValueError("owned process identity changed during handle acquisition")
            if group and (int(after[2]) != root or int(after[3]) != root):
                raise ValueError("owned group changed during handle acquisition")
            if pid in self.owned and self.owned[pid][1] != fd:
                os.close(self.owned[pid][1])
            self.owned[pid] = (birth, fd)
            rss = int((proc / "statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
            final = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
            if final[19] != birth:
                raise ValueError("owned process changed during RSS observation")
            tasks = list((proc / "task").iterdir())
            if len(tasks) > 1024:
                raise ValueError("owned task count limit")
            children = set()
            for task in tasks:
                try:
                    children.update(int(child) for child in (task / "children").read_text().split())
                except FileNotFoundError:
                    row["task_disappeared_between_samples"] = True
            return rss, sorted(children)
        except FileNotFoundError:
            if pid == root:
                raise ValueError("owned root disappeared before reap")
            row["descendant_disappeared_between_samples"] = True
            return 0, []
        finally:
            if fd is not None and (pid not in self.owned or self.owned[pid][1] != fd):
                os.close(fd)

    def __call__(self, pid, row, stdout, stderr):
        free_guard(self.output)
        if self.raw_bytes + stdout.stat().st_size + stderr.stat().st_size > RAW_CAP:
            raise ValueError("aggregate raw capture size limit")
        pending, seen, rss = [(pid, None)], set(), 0
        while pending:
            current, parent = pending.pop()
            if current in seen:
                continue
            if len(seen) >= 128:
                raise ValueError("owned process tree limit")
            seen.add(current)
            memory, children = self.observe(current, pid, row, parent, group=current == pid)
            rss += memory
            pending.extend((child, current) for child in children)
        self.record_peak(row, rss)
        if time.monotonic() - self.last_progress >= 60:
            print(json.dumps({"owned_pid": pid, "observed_tree_rss_bytes": rss,
                              "free_bytes": shutil.disk_usage(self.output).free}), flush=True)
            self.last_progress = time.monotonic()

    @staticmethod
    def record_peak(row, rss):
        row["observed_tree_rss_peak_bytes"] = max(row.get("observed_tree_rss_peak_bytes", 0), rss)
        row["tree_sampling_interval_seconds"] = .05
        if rss > RSS_CAP:
            raise ValueError("observed process tree RSS limit")

    def census(self, root, row):
        # root WNOWAIT lease가 유지된 동안 그 group/session의 놓친 후손도 채택한다.
        processes = list(Path("/proc").iterdir())
        if len(processes) > 4096:
            raise ValueError("bounded process census limit")
        rss, counted = 0, set()
        for proc in processes:
            if not proc.name.isdigit():
                continue
            try:
                stat = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
                if int(stat[2]) == root and int(stat[3]) == root:
                    pid = int(proc.name)
                    memory, _ = self.observe(pid, root, row, group=True)
                    rss += memory
                    counted.add(pid)
            except FileNotFoundError:
                continue
        for pid, (birth, _) in list(self.owned.items()):
            if pid in counted:
                continue
            proc = Path("/proc") / str(pid)
            try:
                stat = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
                if stat[19] == birth:
                    memory = int((proc / "statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
                    after = (proc / "stat").read_text().rsplit(") ", 1)[1].split()
                    if after[19] != birth:
                        raise ValueError("owned process changed during final RSS observation")
                    rss += memory
            except FileNotFoundError:
                continue
        self.record_peak(row, rss)

    def settle(self, root, row):
        import select
        self.census(root, row)
        stop = time.monotonic() + 5
        while True:
            alive = []
            for _, fd in self.owned.values():
                poll = select.poll()
                poll.register(fd, select.POLLIN)
                if not poll.poll(0):
                    alive.append(fd)
            if not alive:
                return
            if time.monotonic() >= stop:
                row["live_descendant_handles_after_root_exit"] = len(alive)
                raise RuntimeError("owned descendants remain after root exit")
            time.sleep(.05)
            self.census(root, row)

    def abort(self, root, row, *, lease=True):
        primary = None
        if lease:
            try:
                self.census(root, row)
            except BaseException as error:
                row["cleanup_census_error"] = type(error).__name__ + ": " + str(error)
                primary = error
        for _, fd in self.owned.values():
            try:
                signal_handle(fd)
            except ProcessLookupError:
                continue
        if primary is not None:
            raise primary

    def close(self):
        errors = []
        for _, fd in self.owned.values():
            try:
                os.close(fd)
            except OSError as error:
                errors.append(type(error).__name__)
        self.owned.clear()
        if errors:
            raise RuntimeError("owned handle close failure: " + ",".join(errors))


def build(repo, output):
    temporary = output / "tmp"
    temporary.mkdir()
    env = R.cli_environment(os.environ, temporary)
    env["GRADLE_USER_HOME"] = os.environ["GRADLE_USER_HOME"]
    guards = Guards(output)
    row: dict = {"scope": "Build preparation, outside measurement"}
    capture = GuardedCapture(output, repo, env, deadline(1800), guard=guards)
    try:
        capture.run(["./gradlew", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process",
                     ":cli:installDist", ":gradle-plugin:classes"], "setup", row,
                    timeout=1800, limits=(32 * MIB, 8 * MIB))
        if row["max_rss_kib"] * 1024 > RSS_CAP:
            raise ValueError("build direct child RSS limit")
        row["free_after"] = free_guard(output, 9 * GIB)
        row["status"] = "complete"
    except BaseException as error:
        row.update(status="failed-or-incomplete", error=type(error).__name__ + ": " + str(error))
        raise
    finally:
        primary = sys.exc_info()[1]
        preserve(output / "build.json", row)



class GuardedCapture:
    def __init__(self, output, cwd, env, deadline, *, guard):
        self.output, self.cwd, self.env, self.deadline = output, cwd, env, deadline
        self.unreaped = False
        self.guard = guard

    def remaining(self):
        value = self.deadline - time.monotonic()
        if value <= 0:
            raise TimeoutError("overall deadline")
        return value

    def run(self, argv, stem, row, timeout=120, limits=R.STREAM_LIMITS):
        if sys.platform != "linux" or not all(callable(getattr(module, name, None)) for module, name in (
                (os, "waitid"), (os, "pidfd_open"), (signal, "pidfd_send_signal"))):
            raise RuntimeError("Linux WNOWAIT/pidfd supervision is required before spawning")
        if signal.getsignal(signal.SIGCHLD) == signal.SIG_IGN:
            raise RuntimeError("automatic child reaping is incompatible with the root lease")
        stdout, stderr = (self.output / (stem + suffix) for suffix in (".stdout", ".stderr"))
        row.update(argv=argv, stdout=stdout.name, stderr=stderr.name)
        process, reaped, primary = None, False, None
        lease = False
        def lease_status(pid):
            nonlocal lease
            try:
                return os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            except OSError:
                lease = False
                row["root_lease_lost"] = True
                raise RuntimeError("owned root lease is unavailable; no numeric cleanup") from None
        try:
            with stdout.open("xb") as out, stderr.open("xb") as err:
                allowed = min(timeout, self.remaining())
                started = time.monotonic()
                process = subprocess.Popen(argv, cwd=self.cwd, env=self.env,
                                           stdout=out, stderr=err, start_new_session=True)
                lease = True
                last_size_check = 0.0
                while True:
                    exited = lease_status(process.pid)
                    if exited is not None:
                        # root를 reap하지 않은 동안만 그 group을 census/정리한다.
                        row["seconds"] = time.monotonic() - started
                        self.guard.settle(process.pid, row)
                        try:
                            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                        except OSError:
                            lease = False
                            row["root_lease_lost"] = True
                            raise
                        if pid != process.pid:
                            lease = False
                            row["root_lease_lost"] = True
                            raise RuntimeError("owned root lease changed")
                        reaped = True
                        lease = False
                        process.returncode = os.waitstatus_to_exitcode(status)
                        row.update(returncode=process.returncode,
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
                        self.guard(process.pid, row, stdout, stderr)
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
                    if lease:
                        try:
                            lease_status(process.pid)
                        except RuntimeError as error:
                            row["cleanup_lease_error"] = str(error)
                    if lease:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    try:
                        self.guard.abort(process.pid, row, lease=lease)
                    except BaseException as error:
                        row["descendant_cleanup_error"] = type(error).__name__ + ": " + str(error)
                        if primary is None:
                            primary = error
                    cleanup_deadline = time.monotonic() + 5
                    while lease and time.monotonic() < cleanup_deadline:
                        try:
                            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                        except OSError:
                            lease = False
                            row["root_lease_lost"] = True
                            raise
                        if pid:
                            reaped = True
                            lease = False
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
            try:
                self.guard.close()
            except BaseException as error:
                row["handle_cleanup_error"] = type(error).__name__ + ": " + str(error)
                if primary is None:
                    primary = error
            for name, path, cap in zip(("stdout", "stderr"), (stdout, stderr), limits):
                try:
                    size = path.stat().st_size
                    row[name + "_bytes"] = size
                    row[name + "_overflow"] = size > cap
                    if size <= cap:
                        row[name + "_sha256"] = R.digest(path)
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

def measure(repo, target, output):
    if target != TARGET or sys.platform != "linux":
        raise ValueError("runtime pair requires its fixed reviewed Linux target")
    output.mkdir()
    result = {"status": "incomplete", "target_sha": target, "schedule": schedule(),
              "rows": [], "metadata": [], "suites": [], "scope": "Within-VM JDK-only diagnostic; not CI acceptance"}
    def terminate(signum, frame):
        raise KeyboardInterrupt("termination requested")
    old_handler = signal.signal(signal.SIGTERM, terminate)
    capture = None
    try:
        root = runtime_root()
        homes = {label: root / ("jdk-" + version) for label, version, *_ in PINS}
        homes["B1"] = homes["B2"] = homes.pop("B")
        fixture = output / "fixture"
        (fixture / "res").mkdir(parents=True)
        (fixture / "AndroidManifest.xml").write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
            '    <application/>\n</manifest>\n')
        temporary = output / "tmp"
        temporary.mkdir()
        env = R.cli_environment(os.environ, temporary)
        envs = {label: dict(env, JAVA_HOME=str(home), PATH=str(home / "bin") + ":" + env["PATH"])
                for label, home in homes.items()}
        if envs["B1"] != envs["B2"]:
            raise ValueError("same-runtime controls differ")
        result["environments"] = envs
        result["runner"] = {k: os.environ.get(k) for k in (
            "ImageOS", "ImageVersion", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")}
        def cpu_model():
            return [line for line in Path("/proc/cpuinfo").read_text().splitlines()
                    if line.startswith("model name")]
        result["cpu_model"] = cpu_model()
        harness = Path(__file__).resolve().parents[1]
        harness_sha = os.environ["GITHUB_SHA"]
        def identities():
            base = R.snapshot(repo, fixture, homes["A"], target)
            for key, expected in EXPECTED.items():
                if canonical(base[key]) != expected:
                    raise ValueError("frozen public input/install mismatch: " + key)
            base["all_root_files_and_metadata"] = {m: file_tree(repo / m / "build/classes/kotlin/main")
                                                    for m in R.MODULES}
            base["installation_metadata"] = file_tree(repo / "cli/build/install/kartograph")
            base["runtimes"] = {label: file_tree(homes[label]) for label in ("A", "B1")}
            base["harness_source"] = R.source_identity(harness, harness_sha)
            return base
        result["before"] = identities()
        guards = Guards(output)
        capture = GuardedCapture(output, repo, envs["A"], deadline(900), guard=guards)
        policy = R.resources()["policy"]
        result["resource_policy"] = policy
        declared = R.commands(repo, fixture)
        result["commands"] = declared
        spec = importlib.util.spec_from_file_location("smoke", repo / "Scripts/verify-analysis-smoke-gate.py")
        if spec is None or spec.loader is None:
            raise RuntimeError("smoke contract module is unavailable")
        smoke = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(smoke)
        references = {}
        def run(command, stem, row, **kwargs):
            free_guard(output)
            before = R.resources()
            if before["policy"] != policy:
                raise ValueError("resource policy changed")
            row["resources_before"] = before
            try:
                files = capture.run(command, stem, row, **kwargs)
                if row["max_rss_kib"] * 1024 > RSS_CAP:
                    raise ValueError("direct child peak RSS limit")
                return files
            finally:
                primary = sys.exc_info()[1]
                try:
                    row["resources_after"] = R.resources()
                    if row["resources_after"]["policy"] != policy:
                        raise ValueError("resource policy changed")
                    guards.raw_bytes += sum(row.get(k + "_bytes", 0) for k in ("stdout", "stderr"))
                    if guards.raw_bytes > RAW_CAP:
                        raise ValueError("aggregate raw capture size limit")
                    if cpu_model() != result["cpu_model"]:
                        raise ValueError("CPU model changed")
                except BaseException as error:
                    row["post_capture_error"] = type(error).__name__ + ": " + str(error)
                    if primary is None:
                        raise
                finally:
                    preserve(output / "result.json", result)
        def metadata(endpoint):
            for label, version, *_ in PINS:
                arm = "A" if label == "A" else "B1"
                capture.env = envs[arm]
                for kind, flags in (("flags", ["-XX:+PrintFlagsFinal"]), ("system", ["-XshowSettings:system"])):
                    row = {"endpoint": endpoint, "label": arm, "kind": kind}
                    result["metadata"].append(row)
                    stdout, stderr = run([str(homes[arm] / "bin/java"), *flags, "-version"],
                                         f"{endpoint}-{arm}-{kind}", row, timeout=10, limits=(MIB, MIB))
                    text = stdout.read_text() + stderr.read_text()
                    if "Temurin-" + version not in text:
                        raise ValueError("executed runtime version/vendor mismatch")
        metadata("before")
        for suite in schedule():
            capture.env = envs[suite["label"]]
            suite_row: dict = dict(suite)
            result["suites"].append(suite_row)
            suite_start = time.monotonic()
            suite_rows = []
            for name, command, contract in declared:
                row: dict = dict(suite, command=name)
                result["rows"].append(row)
                suite_rows.append(row)
                stdout, stderr = run(command, f"suite-{suite['suite']:02d}-{suite['label']}-{name}", row)
                identity = (row["stdout_sha256"], row["stderr_sha256"])
                if references.setdefault(name, identity) != identity:
                    raise ValueError("raw output parity failed: " + name)
                observation, failures = {}, []
                smoke.check_contract(name, contract, subprocess.CompletedProcess(
                    command, row["returncode"], stdout.read_text(), stderr.read_text()), observation, failures)
                row.update(contract=observation, contract_failures=failures)
                if failures:
                    raise ValueError("semantic contract failed: " + name)
                receipt(output / "result.json", result)
            suite_row.update(sum_command_seconds=sum(r["seconds"] for r in suite_rows),
                             enclosing_seconds=time.monotonic() - suite_start)
            receipt(output / "result.json", result)
        metadata("after")
        result["after"] = identities()
        if result["before"] != result["after"]:
            raise ValueError("source/input/install/runtime/harness identity changed")
        result["score"] = grade(result["rows"])
        result["status"] = "noise-qualified" if result["score"]["noise_qualified"] else "not-qualified"
        capture.remaining()
    except BaseException as error:
        result.update(status="failed-or-incomplete", error=type(error).__name__ + ": " + str(error),
                      owned_child_unreaped=bool(capture and capture.unreaped))
        raise
    finally:
        result["finished_utc_ns"] = time.time_ns()
        preserve(output / "result.json", result)
        signal.signal(signal.SIGTERM, old_handler)
    print(json.dumps({"status": result["status"], "cli_calls": len(result["rows"]),
                      "metadata_calls": len(result["metadata"])}))
    return 0 if result["status"] == "noise-qualified" else 1


def package(output, destination):
    free_guard(output)
    paths = []
    for p in sorted(output.rglob("*")):
        if "tmp" in p.relative_to(output).parts or p.name == "failure-evidence.json":
            continue
        if p.is_symlink() or not (p.is_file() or p.is_dir()):
            raise ValueError("unsafe evidence type")
        if p.is_file():
            paths.append(p)
    if not paths or sum(p.stat().st_size for p in paths) > RAW_CAP:
        raise ValueError("aggregate evidence size limit")
    rows = R.file_rows(output, paths)
    manifest = output / "package-manifest.json"
    with manifest.open("x") as stream:
        json.dump({"files": rows, "complete_raw_capture": True, "includes_incomplete_outcomes": True}, stream, indent=2)
    paths.append(manifest)
    temporary = destination.with_suffix(".part")
    with temporary.open("xb") as stream:
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for p in paths:
                with p.open("rb") as source, archive.open(str(p.relative_to(output)), "w") as target:
                    while True:
                        free_guard(output)
                        block = source.read(MIB)
                        if not block:
                            break
                        target.write(block)
                        if stream.tell() > ZIP_CAP:
                            raise ValueError("compressed evidence size limit")
        if stream.tell() > ZIP_CAP:
            raise ValueError("compressed evidence size limit")
    with zipfile.ZipFile(temporary) as archive:
        for row in [*rows, {"path": manifest.name, "bytes": manifest.stat().st_size,
                            "sha256": R.digest(manifest)}]:
            digest, count = hashlib.sha256(), 0
            with archive.open(row["path"]) as source:
                while True:
                    block = source.read(MIB)
                    if not block:
                        break
                    digest.update(block)
                    count += len(block)
            if count != row["bytes"] or digest.hexdigest() != row["sha256"]:
                raise ValueError("lossless evidence verification failed")
    if destination.exists():
        raise ValueError("evidence destination already exists")
    temporary.rename(destination)
    print(json.dumps({"file": destination.name, "bytes": destination.stat().st_size,
                      "sha256": R.digest(destination), "raw_files_verified": len(paths)}))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("build", "prepare", "measure", "package"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path)
    parser.add_argument("--target-sha")
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args(argv)
    if args.stage == "build":
        if args.repo_root is None:
            parser.error("build requires repo-root")
        build(args.repo_root.resolve(strict=True), args.output.resolve(strict=True))
    elif args.stage == "prepare":
        prepare(args.output.resolve(strict=True))
    elif args.stage == "measure":
        if args.repo_root is None or args.target_sha != TARGET:
            parser.error("measure requires its fixed reviewed target and repo-root")
        return measure(args.repo_root.resolve(strict=True), args.target_sha, args.output.resolve())
    else:
        if args.destination is None:
            parser.error("package requires destination")
        package(args.output.resolve(strict=True), args.destination.resolve())
    return 0


if __name__ == "__main__":
    def terminate(signum, frame):
        raise KeyboardInterrupt("termination requested")
    signal.signal(signal.SIGTERM, terminate)
    sys.exit(main())
