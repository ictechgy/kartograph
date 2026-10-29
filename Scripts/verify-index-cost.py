#!/usr/bin/env python3
"""고정 baseline과 이미 빌드한 후보를 같은 입력으로 관측한다. smoke 판정은 바꾸지 않는다."""

import gzip
from collections import Counter
import ctypes
import errno
import hashlib
from itertools import zip_longest
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import statistics
import subprocess
import sys
import tarfile
import tempfile
import threading
import time

# 이번 간선 묶기의 추가 효과를 같은 runner에서 비교한다. 이전 bb13/5a 실험의 기록은 별도 보존한다.
SOURCE_BASELINE = "33f8ce1b15af5be6dc01573e1209017cb1999f1d"
BASELINE = "33f8ce1b15af5be6dc01573e1209017cb1999f1d"
MODULES = ("core", "index", "analysis", "export", "cli", "gradle-plugin")
PROFILES = ("graph", "dead", "architecture")
LIMIT = 32 * 1024 * 1024
RAW_LIMIT = 256 * 1024 * 1024
JFR_LIMIT = 16 * 1024 * 1024
JFR_EVENTS = ("jdk.ExecutionSample", "jdk.ObjectAllocationSample")
JFR_RUNTIME_EVENTS = ("jdk.GCHeapConfiguration", "jdk.GCConfiguration", "jdk.CompilerConfiguration",
                      "jdk.Compilation", "jdk.GarbageCollection", "jdk.DataLoss")


class DiagnosticError(Exception):
    """원문 경로나 child 로그를 노출하지 않는 실패 코드다."""

    def __init__(self, reason, receipt=None):
        super().__init__(reason)
        self.receipt = receipt


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_record(path, relative):
    before = path.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size > 1024 * 1024 * 1024:
        raise DiagnosticError("invalid-inventory-file")
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise DiagnosticError("inventory-changed-during-read")
    return {"path": relative, "size": after.st_size, "mtimeNs": after.st_mtime_ns,
            "sha256": checksum.hexdigest()}


def inventory(directory):
    if not directory.is_dir() or directory.is_symlink():
        raise DiagnosticError("missing-inventory-directory")
    rows = []
    for path in sorted(directory.rglob("*")):
        relative = path.relative_to(directory).as_posix()
        if len(relative) > 1024 or not re.fullmatch(r"[A-Za-z0-9_.$/()+@, =-]+", relative):
            raise DiagnosticError("invalid-inventory-name")
        if path.is_symlink():
            raise DiagnosticError("symlink-in-inventory")
        if path.is_dir():
            continue
        rows.append(file_record(path, relative))
        if len(rows) > 100000:
            raise DiagnosticError("inventory-too-large")
    if not rows:
        raise DiagnosticError("empty-inventory")
    return rows


def root_inventory(root):
    result = {}
    for module in MODULES:
        rows = inventory(root / module / "build/classes/kotlin/main")
        if not any(row["path"].endswith(".class") for row in rows):
            raise DiagnosticError("missing-classes")
        result[module] = rows
    return result


def require_equal(before, after, reason):
    if before != after:
        raise DiagnosticError(reason)


def java_identity(home):
    # 선택된 JDK의 실행 파일·runtime image·release를 해시한다. 전체 OS identity는 아니다.
    return [file_record(home / name, name)
            for name in ("bin/java", "bin/javac", "release", "lib/modules")]


def kill_group(process):
    """호출자가 unreaped child 소유권 lock을 잡은 동안에만 group에 신호를 보낸다."""
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def nonreaping_waiter():
    """PID를 고정한 채 종료만 관측한다. 지원을 증명하지 못하면 spawn 전에 거부한다."""
    names = ("P_PID", "WEXITED", "WNOHANG", "WNOWAIT", "CLD_EXITED", "CLD_KILLED", "CLD_DUMPED")
    if (os.name != "posix" or sys.platform not in ("linux", "darwin") or not hasattr(os, "wait4") or
            any(not hasattr(os, name) for name in names) or
            signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL):
        raise DiagnosticError("child-ownership-not-supported")
    options = os.WEXITED | os.WNOHANG | os.WNOWAIT
    if callable(getattr(os, "waitid", None)):
        def observe(pid):
            result = os.waitid(os.P_PID, pid, options)
            if result is None:
                return False
            if (result.si_pid != pid or result.si_signo != signal.SIGCHLD or
                    result.si_code not in (os.CLD_EXITED, os.CLD_KILLED, os.CLD_DUMPED)):
                raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
            return True
        return observe
    if sys.platform != "darwin":
        raise DiagnosticError("child-ownership-not-supported")
    # macOS system Python 3.9는 waitid를 노출하지 않는다. Darwin 64-bit의
    # sys/signal.h siginfo_t / sys/wait.h waitid(idtype_t, id_t, siginfo_t *, int) ABI다.
    class Sigval(ctypes.Union):
        _fields_ = [("integer", ctypes.c_int), ("pointer", ctypes.c_void_p)]
    class Siginfo(ctypes.Structure):
        _fields_ = [("signo", ctypes.c_int), ("error", ctypes.c_int), ("code", ctypes.c_int),
                    ("pid", ctypes.c_int), ("uid", ctypes.c_uint), ("status", ctypes.c_int),
                    ("address", ctypes.c_void_p), ("value", Sigval), ("band", ctypes.c_long),
                    ("padding", ctypes.c_ulong * 7)]
    if (ctypes.sizeof(ctypes.c_void_p), ctypes.sizeof(ctypes.c_long), ctypes.sizeof(Siginfo),
            Siginfo.pid.offset, os.P_PID, os.WEXITED, os.WNOHANG, os.WNOWAIT) != (8, 8, 104, 12, 1, 4, 1, 32):
        raise DiagnosticError("child-ownership-not-supported")
    try:
        waitid = ctypes.CDLL(None, use_errno=True).waitid
        waitid.argtypes = [ctypes.c_int, ctypes.c_uint, ctypes.POINTER(Siginfo), ctypes.c_int]
        waitid.restype = ctypes.c_int
    except (AttributeError, OSError):
        raise DiagnosticError("child-ownership-not-supported")
    def observe(pid):
        information = Siginfo()
        if waitid(os.P_PID, pid, ctypes.byref(information), options) != 0:
            raise OSError(ctypes.get_errno(), "nonreaping-wait-failed")
        if information.pid == 0:
            return False
        if (information.pid != pid or information.signo != signal.SIGCHLD or
                information.code not in (os.CLD_EXITED, os.CLD_KILLED, os.CLD_DUMPED)):
            raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
        return True
    return observe


def group_census():
    """Darwin의 waitable 좀비만 남은 그룹을 bounded PID 목록으로 확인한다."""
    if sys.platform != "darwin":
        return lambda pid: True  # Linux는 zombie-only group 신호의 ESRCH를 처리한다.
    try:
        list_group = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True).proc_listpgrppids
        # SDK libproc.h: int proc_listpgrppids(pid_t, void *, int); 반환값은 PID 개수다.
        list_group.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
        list_group.restype = ctypes.c_int
    except (AttributeError, OSError):
        raise DiagnosticError("child-ownership-not-supported")
    def has_other_members(pid):
        members = (ctypes.c_int * 4096)()
        ctypes.set_errno(0)
        count = list_group(pid, members, ctypes.sizeof(members))
        error = ctypes.get_errno()
        if (count < 0 or count >= len(members) or
                (count == 0 and error not in (0, errno.ESRCH)) or
                any(member <= 0 for member in members[:max(0, count)])):
            raise OSError(error or errno.EIO, "owned-group-census-failed")
        return any(member != pid for member in members[:count])
    return has_other_members


class OwnedProcessGroup:
    """reader 신호·종료 관측·최종 신호 폐쇄를 같은 lock으로 직렬화한다."""

    def __init__(self, process, observe, has_other_members):
        self.process, self.observe = process, observe
        self.has_other_members = has_other_members
        self.lock = threading.Lock()
        self.signal_open, self.lost = True, False

    def ready(self):
        with self.lock:
            if not self.signal_open or self.process.returncode is not None:
                self.signal_open, self.lost = False, True
                raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
            try:
                return self.observe(self.process.pid)
            except InterruptedError:
                return False
            except OSError:
                self.signal_open, self.lost = False, True
                raise

    def signal_group(self):
        with self.lock:
            self._signal_locked()

    def _signal_locked(self):
        if not self.signal_open:
            return
        if self.process.returncode is not None:
            self.signal_open, self.lost = False, True
            return
        try:
            terminal = self.observe(self.process.pid)
        except OSError:
            self.signal_open, self.lost = False, True
            raise
        # Darwin은 zombie-only group에 EPERM을 반환한다. EPERM을 무시하지 않고,
        # WNOWAIT lease와 같은 그룹의 다른 PID가 없다는 조회를 함께 증명한 때만 생략한다.
        if terminal and not self.has_other_members(self.process.pid):
            return
        kill_group(self.process)

    def close_signals(self):
        # zombie 또는 살아 있는 direct child가 아직 PID를 소유한다. cleanup 후 모든
        # reader 신호를 먼저 폐쇄하고, 이 메서드가 반환된 뒤에만 wait4로 최종 reap한다.
        with self.lock:
            try:
                self._signal_locked()
            finally:
                self.signal_open = False


class SpooledOutput:
    """소유한 임시 출력의 끝까지 읽되 메모리에는 한 chunk만 올린다."""

    def __init__(self, path):
        self.path = Path(path)
        information = self.path.lstat()
        if not stat.S_ISREG(information.st_mode) or information.st_size > RAW_LIMIT:
            raise DiagnosticError("invalid-spooled-output")
        self.identity = (information.st_dev, information.st_ino,
                         information.st_size, information.st_mtime_ns)

    def __len__(self):
        return self.identity[2]

    def chunks(self):
        descriptor = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "rb") as source:
            def unchanged():
                information = os.fstat(source.fileno())
                return (information.st_dev, information.st_ino, information.st_size,
                        information.st_mtime_ns) == self.identity
            if not unchanged():
                raise DiagnosticError("spooled-output-changed")
            for chunk in iter(lambda: source.read(65536), b""):
                yield chunk
            if not unchanged():
                raise DiagnosticError("spooled-output-changed")


def output_chunks(data):
    if isinstance(data, SpooledOutput):
        yield from data.chunks()
    else:
        for offset in range(0, len(data), 65536):
            yield data[offset:offset + 65536]


def output_digest(data):
    checksum = hashlib.sha256()
    for chunk in output_chunks(data):
        checksum.update(chunk)
    return checksum.hexdigest()


def output_equal(left, right):
    if isinstance(left, bytes) and isinstance(right, bytes):
        return left == right
    return len(left) == len(right) and all(
        a == b for a, b in zip_longest(output_chunks(left), output_chunks(right)))


def run(arguments, cwd, env, timeout=120, limit=LIMIT, phase="child",
        stdout_path=None, stdout_limit=RAW_LIMIT):
    """각 child의 두 stream을 제한하고 timeout에는 소유한 process group을 끝낸다."""
    if not re.fullmatch(r"[a-z-]{1,64}", phase):
        raise DiagnosticError("invalid-process-phase")
    observe = nonreaping_waiter()
    has_other_members = group_census()
    if type(stdout_limit) is not int or not 0 < stdout_limit <= RAW_LIMIT:
        raise DiagnosticError("invalid-spooled-output-limit")
    destination = Path(stdout_path).open("xb") if stdout_path is not None else None
    started = time.monotonic_ns()
    try:
        process = subprocess.Popen(list(map(str, arguments)), cwd=cwd, env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=True)
    except BaseException:
        if destination is not None:
            destination.close()
        raise
    owned = OwnedProcessGroup(process, observe, has_other_members)
    streams = [bytearray(), bytearray()]
    counts = [0, 0]
    overflow = threading.Event()
    failures = []
    def drain(index, stream):
        try:
            while True:
                chunk = stream.read(65536)
                if not chunk:
                    break
                stream_limit = stdout_limit if index == 0 and destination is not None else limit
                if counts[index] + len(chunk) > stream_limit:
                    overflow.set()
                    owned.signal_group()
                    break
                if index == 0 and destination is not None:
                    destination.write(chunk)
                else:
                    streams[index].extend(chunk)
                counts[index] += len(chunk)
        except OSError:
            failures.append("stream-read-failed")
            owned.signal_group()
        finally:
            stream.close()
    readers = [threading.Thread(target=drain, args=(i, stream), daemon=True)
               for i, stream in enumerate((process.stdout, process.stderr))]
    for reader in readers:
        reader.start()
    timed_out = False
    wait_failed = False
    reader_unclosed = False
    rss = {"status": "unavailable", "reason": "owned-wait4-rusage-not-observed"}
    def wait_owned():
        deadline = time.monotonic() + timeout
        while True:
            if owned.ready():
                return
            if time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired(arguments, timeout)
            time.sleep(0.005)
    try:
        wait_owned()
    except subprocess.TimeoutExpired:
        timed_out = True
    except OSError:
        wait_failed = True
    finally:
        try:
            owned.close_signals()
        except OSError:
            wait_failed = True
        if not owned.lost:
            # close_signals 이후에는 main·reader 어느 쪽도 numeric PGID를 사용하지 않는다.
            deadline = time.monotonic() + 10
            try:
                while True:
                    try:
                        pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                    except InterruptedError:
                        if time.monotonic() >= deadline:
                            raise ChildProcessError(errno.ECHILD, "final-child-wait-failed")
                        continue
                    if pid == process.pid:
                        process.returncode = os.waitstatus_to_exitcode(status)
                        if sys.platform in ("linux", "darwin") and 0 < usage.ru_maxrss < 2**63:
                            rss = {"status": "observed", "peakBytes": int(usage.ru_maxrss) *
                                   (1024 if sys.platform == "linux" else 1),
                                   "scope": "owned-wait4-child-peak-rss; kernel-accounting-not-live-heap"}
                        else:
                            rss = {"status": "unavailable", "reason": "unknown-rusage-units"}
                        break
                    if pid != 0 or time.monotonic() >= deadline:
                        raise ChildProcessError(errno.ECHILD, "final-child-wait-failed")
                    time.sleep(0.005)
            except OSError as error:
                wait_failed = True
                if error.errno != errno.ECHILD:
                    try:
                        process.wait(timeout=10)
                    except (OSError, subprocess.TimeoutExpired):
                        wait_failed = True  # 실패 receipt를 보존하며 신호 경로를 다시 열지 않는다.
        for reader in readers:
            reader.join(timeout=10)
            if reader.is_alive():
                reader_unclosed = True  # 이후 늦게 종료해도 부분 snapshot을 성공으로 바꾸지 않는다.
    if destination is not None:
        destination.close()
    result = {"exit": process.returncode,
              "stdout": SpooledOutput(stdout_path) if destination is not None else bytes(streams[0]),
              "stderr": bytes(streams[1]),
              "wallNanos": time.monotonic_ns() - started, "rss": rss, "phase": phase}
    reason = ("child-stream-not-closed" if reader_unclosed else
              "child-timeout" if timed_out else "child-output-limit" if overflow.is_set() else
              "child-stream-failed" if failures else "child-wait-failed" if wait_failed else None)
    if reason:
        raise DiagnosticError(reason, summary(result))
    return result


def summary(result):
    return {"phase": result.get("phase", "child"), "exit": result["exit"], "wallNanos": result["wallNanos"], "rss": result["rss"],
            **{stream: {"bytes": len(result[stream]), "sha256": output_digest(result[stream])}
               for stream in ("stdout", "stderr")}}


def successful(arguments, cwd, env, timeout=120, phase="child"):
    result = run(arguments, cwd, env, timeout, phase=phase)
    if result["exit"] != 0:
        raise DiagnosticError("required-child-failed", summary(result))
    return result


def validate_probe(result, profile):
    try:
        data = json.loads(result["stdout"])
    except (ValueError, UnicodeError):
        raise DiagnosticError("invalid-probe-output")
    if not isinstance(data, dict):
        raise DiagnosticError("invalid-probe-output")
    numeric = ("entryUptimeMillis", "invocationNanos", "currentThreadAllocatedBytes",
               "nodes", "locatedNodes", "edges", "externalCalls", "classFiles", "parsedClasses", "cacheHits",
               "cacheMisses", "readNanos", "cacheReadNanos", "parseNanos", "assemblyNanos",
               "hierarchyNanos", "runtimeNanos", "dispatchNanos", "cacheWriteNanos", "totalNanos")
    expected_keys = set(numeric) | {"profile", "allocationScope"}
    if (set(data) != expected_keys or data.get("profile") != profile or result["stderr"]
            or data.get("allocationScope") !=
            "index-invocation-current-thread-only; excludes-other-threads-and-startup"
            or any(not (type(data[key]) is int and 0 <= data[key] < 2**63)
                   for key in numeric if not (key == "currentThreadAllocatedBytes" and data[key] is None))):
        raise DiagnosticError("invalid-probe-output")
    return data


def check_profile_counts(records):
    for profile in PROFILES:
        values = [tuple(row[key] for key in ("nodes", "locatedNodes", "edges", "externalCalls", "classFiles",
                                            "parsedClasses", "cacheHits", "cacheMisses"))
                  for row in records if row["profile"] == profile]
        if len(values) != 6 or any(value != values[0] for value in values):
            raise DiagnosticError("profile-structural-counts-changed")


def successful_pair(left, right):
    return left["exit"] == 0 and right["exit"] == 0 and equivalent(left, right)


def source_identity(repo, ref, env):
    result = successful(["git", "rev-parse", "--verify", ref + "^{commit}"], repo, env)
    commit = result["stdout"].decode("ascii").strip()
    result = successful(["git", "rev-parse", "--verify", ref + "^{tree}"], repo, env)
    tree = result["stdout"].decode("ascii").strip()
    if not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (commit, tree)):
        raise DiagnosticError("invalid-source-identity")
    return {"commit": commit, "tree": tree}


def require_baseline_source(repo, source, baseline, env):
    """보조 baseline이 원본의 실행 소스·빌드 스크립트를 바꾸지 않았는지 Git tree로 확인한다."""
    changed = successful(["git", "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only",
                          source, baseline, "--"], repo, env, phase="baseline-source-diff")
    expected = b"" if source == baseline else b"gradle/verification-metadata.xml\n"
    require_equal(changed["stdout"], expected, "baseline-executable-source-changed")


def identity_digest(value):
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def public_summary(report):
    # 경로·환경·원문 stream 없이 고정 계수와 identity hash만 log에 남긴다.
    result = {"format": report["format"], "status": report["status"],
              "reason": report.get("reason"), "scope": report["scope"],
              "residualScope": report["residualScope"]}
    if "failure" in report:
        result["failure"] = report["failure"]
    if "sourcesBefore" in report:
        result["sources"] = report["sourcesBefore"]
    result["identitySha256"] = {key: identity_digest(report[key]) for key in (
        "rootsBefore", "projectBefore", "libsBefore", "launchersBefore", "jdkBefore",
        "probeSourceBefore", "semanticSourceBefore", "probesBefore") if key in report}
    result["profiles"] = report.get("profiles", [])
    result["cli"] = report.get("cli", [])
    if "cliTiming" in report:
        result["cliTiming"] = report["cliTiming"]
    if "semantic" in report:
        result["semantic"] = report["semantic"]
    encoded = json.dumps(result, sort_keys=True)
    if len(encoded.encode("utf-8")) > 65536:
        raise DiagnosticError("public-summary-too-large")
    return encoded


def public_observation_summary(report):
    """이미 수집한 관측만 별도 제한 로그로 공개한다. 경로·환경·원시 stack은 복사하지 않는다."""
    def integer(value, maximum=2**63 - 1):
        if type(value) is not int or not 0 <= value <= maximum:
            raise DiagnosticError("invalid-public-observation")
        return value

    def checksum(value):
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
            raise DiagnosticError("invalid-public-observation")
        return value

    def load(value):
        averages = value["loadAverage"]
        if (not isinstance(averages, list) or len(averages) != 3 or any(
                type(number) not in (int, float) or not math.isfinite(number)
                or not 0 <= number <= 1_000_000 for number in averages)):
            raise DiagnosticError("invalid-public-observation")
        cpus = value["logicalCpus"]
        if cpus is not None and integer(cpus, 65536) == 0:
            raise DiagnosticError("invalid-public-observation")
        return {"loadAverage": averages, "logicalCpus": cpus,
                "monotonicNanos": integer(value["monotonicNanos"])}

    result = {"format": "kartograph-index-observations-v1", "diagnosticStatus": report["status"],
              "reportIdentitySha256": identity_digest(report),
              "scope": "already-collected-observations; no-new-processes; no-performance-acceptance",
              "jfr": {"status": "unavailable"}, "cliLoad": {"status": "unavailable"}}
    try:
        if report["status"] not in ("PASS", "FAIL"):
            raise DiagnosticError("invalid-public-observation")
        if "jfr" in report:
            original = report["jfr"]
            samples = original["samples"]
            if (set(samples) != set(JFR_EVENTS) or original["command"] != "dead"
                    or original["outputMatchesUnprofiledCandidate"] is not True
                    or original["scope"] != "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance"
                    or original["launcher"] != "direct-java-same-main-class-and-arguments; startup-not-equivalent"):
                raise DiagnosticError("invalid-public-observation")
            selected = {}
            for kind in JFR_EVENTS:
                row = samples[kind]
                total = integer(row["weight"])
                count = integer(row["events"], 20000)
                if kind == "jdk.ExecutionSample" and total != count:
                    raise DiagnosticError("invalid-public-observation")
                selected[kind] = {"events": count, "weight": total}
                for field in ("inclusive", "nearest"):
                    ranks = row[field]
                    if not isinstance(ranks, list) or len(ranks) > 30:
                        raise DiagnosticError("invalid-public-observation")
                    checked = []
                    for name, weight in ranks:
                        if (not isinstance(name, str) or len(name) > 1024 or not re.fullmatch(
                                r"dev/kartograph/(?:[A-Za-z0-9_$-]+/)*[A-Za-z0-9_$-]+\.(?:[A-Za-z0-9_$-]+|<init>|<clinit>)", name)):
                            raise DiagnosticError("invalid-public-observation")
                        checked.append([name, integer(weight, total)])
                    if (len({name for name, _ in checked}) != len(checked)
                            or checked != sorted(checked, key=lambda item: (-item[1], item[0]))):
                        raise DiagnosticError("invalid-public-observation")
                    selected[kind][field] = checked[:10]
                    selected[kind][field + "OmittedFromStoredTop30"] = max(0, len(checked) - 10)
            recording = original["recording"]
            size = integer(recording["size"], JFR_LIMIT)
            if size == 0:
                raise DiagnosticError("invalid-public-observation")
            result["jfr"] = {
                "status": "observed", "command": "dead", "samples": selected,
                "scope": "single-profiled-candidate; all-thread-samples; not-wall-time-or-total-heap",
                "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
                "rankingScope": "top-10-of-stored-top-30-product-methods; full-tail-count-not-recorded; inclusive-rows-overlap",
                "gcJitAttribution": "not-recorded", "outputMatchesUnprofiledCandidate": True,
                "configurationSha256": checksum(original["configurationSha256"]),
                "recording": {"size": size, "sha256": checksum(recording["sha256"])}}
            if "runtime" in original:
                runtime = original["runtime"]
                if (not isinstance(runtime, dict) or runtime.get("format") != "kartograph-jfr-runtime-v1"
                        or len(json.dumps(runtime, allow_nan=False).encode("utf-8")) > 32768):
                    raise DiagnosticError("invalid-public-observation")
                # 구성·기간은 보존된 원본에만 둔다. 기간 합계는 비계측 gate의 GC/JIT 비용 귀속이 아니다.
                result["jfr"]["gcJitAttribution"] = "not-available-for-unprofiled-gate"
                result["jfr"]["runtimeDetails"] = {"status": "stored-not-projected",
                    "format": "kartograph-jfr-runtime-v1", "sha256": identity_digest(runtime)}
        if "cliLoad" in report:
            rows = report["cliLoad"]
            if not isinstance(rows, list) or len(rows) > 36:
                raise DiagnosticError("invalid-public-observation")
            selected, seen = [], set()
            for row in rows:
                key = (row["command"], row["sample"], row["arm"])
                if (key[0] not in ("graph", "dead", "dead_private", "cycles", "rules", "metrics")
                        or type(key[1]) is not int or key[1] not in (1, 2, 3)
                        or key[2] not in ("baseline", "candidate") or key in seen):
                    raise DiagnosticError("invalid-public-observation")
                seen.add(key)
                before, after = load(row["before"]), load(row["after"])
                if before["monotonicNanos"] > after["monotonicNanos"]:
                    raise DiagnosticError("invalid-public-observation")
                selected.append({"command": key[0], "sample": key[1], "arm": key[2],
                                 "before": before, "after": after})
            result["cliLoad"] = {"status": "observed" if len(rows) == 36 else "partial",
                                 "scope": "same-runner-sequential-children; host-load-observed-not-controlled; not-process-cpu-or-gc",
                                 "expectedRows": 36, "observedRows": len(rows), "rows": selected}
        encoded = json.dumps(result, sort_keys=True, allow_nan=False)
    except (ValueError, KeyError, TypeError, AttributeError, OverflowError):
        raise DiagnosticError("invalid-public-observation") from None
    if len(encoded.encode("utf-8")) > 65536:
        raise DiagnosticError("public-observations-too-large")
    return encoded


def write_report(report, output):
    # 관측 공개 오류도 실패로 남기며 원본 관측은 result.json에서 보존한다.
    try:
        observations = public_observation_summary(report)
    except DiagnosticError as error:
        report["status"] = "FAIL"
        report.setdefault("reason", str(error))
        report["observationSummaryFailure"] = str(error)
        observations = json.dumps({"format": "kartograph-index-observations-v1",
                                   "diagnosticStatus": "FAIL", "status": "invalid",
                                   "reason": str(error)}, sort_keys=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(public_summary(report))
    print(observations)
    return 0 if report["status"] == "PASS" else 1


def equivalent(left, right):
    # 순서·중복·진단·줄바꿈·종료 코드를 있는 그대로 비교한다.
    return left["exit"] == right["exit"] and all(
        output_equal(left[key], right[key]) for key in ("stdout", "stderr"))


def schedule():
    return [(profile, sample, arm) for profile in PROFILES
            for sample, order in enumerate((("baseline", "candidate"), ("candidate", "baseline"),
                                            ("baseline", "candidate")), 1) for arm in order]


def cli_schedule():
    # 각 명령을 세 쌍씩 관측하고 첫 실행 위치도 번갈아 배치한다. 성공 표본을 고르지 않는다.
    return [(index, sample, ("baseline", "candidate") if (index + sample) % 2 else
             ("candidate", "baseline")) for sample in range(1, 4) for index in range(6)]


def host_load_snapshot():
    # 실행 인자·환경·다른 프로세스 신원은 수집하지 않는다. 동일 부하를 보장하는 값도 아니다.
    return {"loadAverage": list(os.getloadavg()), "logicalCpus": os.cpu_count(),
            "monotonicNanos": time.monotonic_ns()}


class RawStreams:
    """제한된 공개 fixture 출력만 손실 없이 보존한다. 기존 실행의 파일은 덮어쓰지 않는다."""

    def __init__(self, directory):
        self.directory = directory
        directory.mkdir()
        self.bytes = 0

    def retain(self, command, sample, arm, result):
        records = {}
        for stream in ("stdout", "stderr"):
            data = result[stream]
            if self.bytes + len(data) > RAW_LIMIT:
                raise DiagnosticError("raw-evidence-limit")
            name = f"{command}-{sample}-{arm}-{stream}.gz"
            with (self.directory / name).open("xb") as destination:
                with gzip.GzipFile(fileobj=destination, mode="wb", mtime=0, filename="") as archive:
                    for chunk in output_chunks(data):
                        archive.write(chunk)
            self.bytes += len(data)
            records[stream] = {"path": "raw/" + name, "bytes": len(data), "sha256": output_digest(data)}
        return records


def paired_cli(commands, project, env, located_nodes, report, raw):
    all_accepted = True
    report["cli"] = []
    report["cliLoad"] = []
    for index, sample, order in cli_schedule():
        name = commands["baseline"][index][0]
        with tempfile.TemporaryDirectory(prefix="cli-streams-", dir=raw.directory.parent) as temporary:
            results, retained = {}, {}
            for arm in order:
                before = host_load_snapshot()
                results[arm] = run(commands[arm][index][1], project, env,
                                   phase="cli-" + name.replace("_", "-"),
                                   stdout_path=Path(temporary) / arm)
                after = host_load_snapshot()
                retained[arm] = raw.retain(name, sample, arm, results[arm])
                report["cliLoad"].append({"command": name, "sample": sample, "arm": arm,
                                          "before": before, "after": after})
            left, right = results["baseline"], results["candidate"]
            accepted = successful_pair(left, right)
            all_accepted = all_accepted and accepted
            report["cli"].append({"command": name, "sample": sample, "order": list(order),
                                  "exactEqual": equivalent(left, right),
                                  "expectedContractDelta": False, "contractAccepted": accepted,
                                  "comparisonScope": "exact-byte-comparison",
                                  "raw": retained, **{arm: summary(result) for arm, result in results.items()}})
    report["cliTiming"] = {
        "scope": "three-paired-fresh-processes-per-command; no-performance-pass-or-budget-change",
        "loadScope": "same-runner-sequential-children; host-load-observed-not-controlled",
        "arms": {arm: {
            "medianSeconds": {name: statistics.median(
                row[arm]["wallNanos"] / 1e9 for row in report["cli"] if row["command"] == name)
                for name, _ in commands[arm]},
        } for arm in ("baseline", "candidate")},
        "pairedDeltasNanos": [{"command": row["command"], "sample": row["sample"],
                               "candidateMinusBaseline": row["candidate"]["wallNanos"] - row["baseline"]["wallNanos"]}
                              for row in report["cli"]],
    }
    for values in report["cliTiming"]["arms"].values():
        values["sumMedianSeconds"] = sum(values["medianSeconds"].values())
    if not all_accepted:
        raise DiagnosticError("cli-byte-mismatch-or-unsuccessful-exit")


def cli_commands(binary, roots, project):
    classes = [value for root in roots for value in ("--classes", str(root))]
    dead = ["dead", *classes, "--project", str(project),
            "--manifest", str(project / "AndroidManifest.xml"), "--resources", str(project / "res"),
            "--namespace", "dev.kartograph", "--keep-rules", str(project / ".kartograph-self.pro")]
    return [
        ("graph", [binary, "graph", *classes, "--format", "dot"]),
        ("dead", [binary, *dead, "--strict"]),
        ("dead_private", [binary, *dead, "--include-private-members", "--strict"]),
        ("cycles", [binary, "cycles", *classes, "--strict"]),
        ("rules", [binary, "rules", *classes, "--config", str(project / ".kartograph.yml"), "--strict"]),
        ("metrics", [binary, "metrics", *classes]),
    ]


def compile_probe(repo, distribution, destination, java_home, env):
    destination.mkdir()
    successful([java_home / "bin/javac", "-cp", str(distribution / "lib/*"), "-d", destination,
                repo / "Scripts/IndexCostProbe.java"], repo, env, phase="compile-probe")
    return str(destination) + os.pathsep + str(distribution / "lib/*")


def compare_semantic_outputs(outputs, report):
    """engine 지문은 보존하고 v10 raw payload와 두 전체 graph 형식의 바이트를 비교한다."""
    records, payloads, graphs = {}, {}, {}
    for arm, directory in outputs.items():
        files = {}
        for name in ("raw-facts.json", "graphs.json"):
            path = directory / name
            if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= LIMIT:
                raise DiagnosticError("semantic-output-bound")
            files[name] = file_record(path, name)
        path = directory / "raw-facts.json"
        if path.stat().st_size > LIMIT:
            raise DiagnosticError("semantic-facts-bound")
        try:
            document = json.loads(path.read_bytes())
            graph = json.loads((directory / "graphs.json").read_bytes())
            if (not isinstance(graph, dict) or set(graph) != {"json", "ndjson"} or any(
                    not isinstance(row, dict) or set(row) != {"bytes", "sha256"} or type(row["bytes"]) is not int
                    or not 0 < row["bytes"] <= 512 * 1024 * 1024 or not isinstance(row["sha256"], str)
                    or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) for row in graph.values())):
                raise ValueError()
            if (set(document) != {"format", "engine", "payloads"}
                    or document["format"] != "class-facts-v10-payload-sha256"
                    or not isinstance(document["engine"], str)
                    or not re.fullmatch(r"[0-9a-f]{64}", document["engine"])
                    or not isinstance(document["payloads"], dict) or not document["payloads"]
                    or any(not re.fullmatch(r"[0-9a-f]{64}", key) or not isinstance(value, str)
                           or not re.fullmatch(r"[0-9a-f]{64}", value)
                           for key, value in document["payloads"].items())):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise DiagnosticError("invalid-semantic-facts") from None
        payloads[arm] = document["payloads"]
        graphs[arm] = graph
        records[arm] = {"engine": document["engine"], "entries": len(payloads[arm]), "files": files, "graphs": graph}
    left, right = records["baseline"], records["candidate"]
    equal_graphs = graphs["baseline"] == graphs["candidate"]
    report["semantic"] = {"scope": "non-timed-full-graph-JSON-NDJSON-and-v10-raw-payload; graph-bytes-not-retained",
                          "arms": records, "graphsEqual": equal_graphs,
                          "rawPayloadsEqual": payloads["baseline"] == payloads["candidate"],
                          "enginesDiffer": left["engine"] != right["engine"],
                          "engineScope": "recorded separately; implementation JAR identities change; each cache consumed by its own reader"}
    if not equal_graphs or payloads["baseline"] != payloads["candidate"]:
        raise DiagnosticError("semantic-graph-or-raw-payload-mismatch")
    if left["engine"] == right["engine"]:
        raise DiagnosticError("semantic-engine-did-not-change")


def semantic_oracle(repo, distributions, roots, java_home, env, work, report, raw):
    outputs = {}
    report["semanticProcesses"] = {}
    for arm, distribution in distributions.items():
        classes = work / ("semantic-probe-" + arm)
        classes.mkdir()
        successful([java_home / "bin/javac", "-encoding", "UTF-8", "-cp", str(distribution / "lib/*"), "-d", classes,
                    repo / "Scripts/IndexEquivalenceProbe.java"], repo, env, phase="compile-semantic-probe")
        outputs[arm] = work / ("semantic-output-" + arm)
        result = successful([java_home / "bin/java", "-cp", str(classes) + os.pathsep + str(distribution / "lib/*"),
                             "IndexEquivalenceProbe", outputs[arm], *roots], work, env, phase="semantic-oracle")
        report["semanticProcesses"][arm] = summary(result)
        if (outputs[arm] / "raw-facts.json").stat().st_size > LIMIT:
            raise DiagnosticError("semantic-facts-bound")
        raw.retain("semantic-facts", 1, arm,
                   {"stdout": (outputs[arm] / "raw-facts.json").read_bytes(), "stderr": result["stdout"]})
    compare_semantic_outputs(outputs, report)


def summarize_jfr(data):
    """샘플의 가중치를 실제 heap 크기나 실행 시간으로 바꾸지 않는다."""
    result = {name: {"events": 0, "weight": 0, "inclusive": Counter(), "nearest": Counter()}
              for name in JFR_EVENTS}
    try:
        events = json.loads(data)["recording"]["events"]
        if not isinstance(events, list) or len(events) > 20000:
            raise ValueError()
        for event in events:
            kind, values = event["type"], event["values"]
            if kind not in result:
                raise ValueError()
            weight = values["weight"] if kind == "jdk.ObjectAllocationSample" else 1
            if type(weight) is not int or weight < 0:
                raise ValueError()
            row = result[kind]
            row["events"] += 1
            row["weight"] += weight
            frames = (values.get("stackTrace") or {}).get("frames", [])
            if len(frames) > 256:
                raise ValueError()
            product = []
            for frame in frames:
                method = frame["method"]
                owner = method["type"]["name"]
                name = owner + "." + method["name"]
                if owner.startswith("dev/kartograph/"):
                    if len(name) > 1024 or not re.fullmatch(r"[A-Za-z0-9_$/.<>-]+", name):
                        raise ValueError()
                    product.append(name)
            for name in set(product):
                row["inclusive"][name] += weight
            if product:
                row["nearest"][product[0]] += weight
        return {name: {**row, **{field: sorted(row[field].items(), key=lambda item: (-item[1], item[0]))[:30]
                                 for field in ("inclusive", "nearest")}} for name, row in result.items()}
    except (ValueError, KeyError, TypeError, AttributeError):
        raise DiagnosticError("invalid-jfr-events") from None


def summarize_runtime_jfr(data):
    """구성·계측된 기간만 보존한다. 병렬 JIT 기간을 wall/CPU 시간으로 해석하지 않는다."""
    configuration_fields = {
        "jdk.GCHeapConfiguration": {"minSize": int, "maxSize": int, "initialSize": int,
                                     "usesCompressedOops": bool},
        "jdk.GCConfiguration": {"youngCollector": str, "oldCollector": str,
                                 "parallelGCThreads": int, "concurrentGCThreads": int,
                                 "usesDynamicGCThreads": bool},
        "jdk.CompilerConfiguration": {"threadCount": int, "tieredCompilation": bool},
    }

    def integer(value):
        if type(value) is not int or not 0 <= value <= 2**63 - 1:
            raise ValueError()
        return value

    def nanos(value):
        if not isinstance(value, str) or len(value) > 64:
            raise ValueError()
        match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?", value)
        if not match or all(part is None for part in match.groups()):
            raise ValueError()
        hours, minutes, seconds, fraction = match.groups()
        total = (int(hours or 0) * 3600 + int(minutes or 0) * 60 + int(seconds or 0)) * 10**9
        total += int((fraction or "").ljust(9, "0"))
        if total > 3600 * 10**9:
            raise ValueError()
        return total

    try:
        events = json.loads(data)["recording"]["events"]
        if not isinstance(events, list) or len(events) > 20000:
            raise ValueError()
        counts = Counter()
        configurations = {kind: [] for kind in configuration_fields}
        compilation = {"events": 0, "succeeded": 0, "durationNanosSum": 0,
                       "maximumDurationNanos": 0, "byLevel": Counter()}
        garbage = {"events": 0, "durationNanosSum": 0, "pauseNanosSum": 0,
                   "maximumPauseNanos": 0}
        for event in events:
            kind, values = event["type"], event["values"]
            if kind not in (*JFR_EVENTS, *JFR_RUNTIME_EVENTS) or not isinstance(values, dict):
                raise ValueError()
            if kind in JFR_EVENTS:
                continue
            counts[kind] += 1
            if kind in configuration_fields:
                row = {}
                for name, expected_type in configuration_fields[kind].items():
                    value = values[name]
                    if type(value) is not expected_type:
                        raise ValueError()
                    if expected_type is int:
                        value = integer(value)
                    elif expected_type is str and not re.fullmatch(r"[A-Za-z0-9 _-]{1,64}", value):
                        raise ValueError()
                    row[name] = value
                if row not in configurations[kind]:
                    if len(configurations[kind]) >= 8:
                        raise ValueError()
                    configurations[kind].append(row)
            elif kind == "jdk.Compilation":
                level = integer(values["compileLevel"])
                if level > 4 or type(values["succeded"]) is not bool:
                    raise ValueError()
                duration = nanos(values["duration"])
                compilation["events"] += 1
                compilation["succeeded"] += int(values["succeded"])
                compilation["durationNanosSum"] += duration
                compilation["maximumDurationNanos"] = max(compilation["maximumDurationNanos"], duration)
                compilation["byLevel"][str(level)] += 1
            elif kind == "jdk.GarbageCollection":
                garbage["events"] += 1
                garbage["durationNanosSum"] += nanos(values["duration"])
                garbage["pauseNanosSum"] += nanos(values["sumOfPauses"])
                garbage["maximumPauseNanos"] = max(garbage["maximumPauseNanos"], nanos(values["longestPause"]))
            elif integer(values["amount"]) or integer(values["total"]):
                raise DiagnosticError("jfr-data-loss")
        missing = [kind for kind, rows in configurations.items() if not rows]
        return {"format": "kartograph-jfr-runtime-v1", "status": "partial" if missing else "observed",
                "scope": "single-profiled-clone-configuration-and-duration-observations; not-unprofiled-gate-or-cpu-attribution; JIT durations may overlap",
                "missingConfigurations": missing, "configurations": configurations,
                "compilation": {**compilation, "byLevel": dict(sorted(compilation["byLevel"].items()))},
                "garbageCollection": garbage, "eventCounts": {kind: counts[kind] for kind in JFR_RUNTIME_EVENTS}}
    except (ValueError, KeyError, TypeError, AttributeError, OverflowError):
        raise DiagnosticError("invalid-jfr-runtime-events") from None


def capture_jfr(java_home, arguments, env, work, output, config, expected):
    """별도 단회 실행이며 paired 중앙값·정식 gate 예산에는 포함하지 않는다."""
    work.mkdir()
    configuration = config.read_bytes()
    (work / "capture.jfc").write_bytes(configuration)
    tool = java_home / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")
    tool_before = file_record(tool, "bin/jfr")
    result = successful([java_home / "bin/java", "-Xlog:jfr+startup=off",
                         "-XX:StartFlightRecording=settings=capture.jfc,filename=capture.jfr,maxsize=16m,dumponexit=true",
                         *arguments], work, env, phase="jfr-candidate")
    if not equivalent(result, expected):
        raise DiagnosticError("jfr-cli-byte-mismatch", summary(result))
    recording = work / "capture.jfr"
    if not recording.is_file() or recording.is_symlink() or not 0 < recording.stat().st_size <= JFR_LIMIT:
        raise DiagnosticError("jfr-recording-size")
    # 샘플·숫자/고정 구성·기간 event만 허용한다. 환경·인자 등 예상 밖 event는 공개하지 않는다.
    listing = successful([tool, "summary", recording], work, env, phase="jfr-summary")
    counts = {}
    for line in listing["stdout"].decode("utf-8").splitlines():
        match = re.fullmatch(r"\s*(\S+)\s+(\d+)\s+\d+\s*", line)
        if match and int(match[2]):
            counts[match[1]] = int(match[2])
    if not counts or set(counts) - {*JFR_EVENTS, *JFR_RUNTIME_EVENTS, "jdk.CheckPoint", "jdk.Metadata"}:
        raise DiagnosticError("unexpected-jfr-event-type")
    events = successful([tool, "print", "--json", "--events", ",".join((*JFR_EVENTS, *JFR_RUNTIME_EVENTS)), recording],
                        work, env, phase="jfr-events")
    runtime = summarize_runtime_jfr(events["stdout"])
    selected = json.loads(events["stdout"])["recording"]["events"]
    samples = summarize_jfr(json.dumps({"recording": {"events": [
        event for event in selected if event["type"] in JFR_EVENTS]}}))
    for name in JFR_EVENTS:
        if samples[name]["events"] != counts.get(name, 0):
            raise DiagnosticError("jfr-event-count-mismatch")
    if not sum(row["events"] for row in samples.values()):
        raise DiagnosticError("jfr-no-samples")
    if any(runtime["eventCounts"][kind] != counts.get(kind, 0) for kind in JFR_RUNTIME_EVENTS):
        raise DiagnosticError("jfr-runtime-event-count-mismatch")
    require_equal(tool_before, file_record(tool, "bin/jfr"), "jfr-tool-changed")
    require_equal(configuration, config.read_bytes(), "jfr-configuration-changed")
    output.mkdir()
    with (output / "candidate.jfr").open("xb") as destination:
        destination.write(recording.read_bytes())
    return {"scope": "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance",
            "command": "dead", "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
            "configurationSha256": digest(configuration), "tool": tool_before,
            "recording": file_record(output / "candidate.jfr", "jfr/candidate.jfr"),
            "eventCounts": counts, "samples": samples, "runtime": runtime, "process": summary(result),
            "outputMatchesUnprofiledCandidate": True}


def experiment(repo, work, env, report, raw_directory=None):
    java_home = Path(env["JAVA_HOME"]).resolve(strict=True)
    jdk_before = java_identity(java_home)
    report["jdkBefore"] = jdk_before
    candidate = repo / "cli/build/install/kartograph"
    if not (candidate / "bin/kartograph").is_file() or not os.access(candidate / "bin/kartograph", os.X_OK):
        raise DiagnosticError("missing-candidate-distribution")
    candidate_before = inventory(candidate / "lib")
    launchers_candidate_before = inventory(candidate / "bin")
    candidate_source = source_identity(repo, "HEAD", env)
    probe_source_before = file_record(repo / "Scripts/IndexCostProbe.java", "IndexCostProbe.java")
    report["probeSourceBefore"] = probe_source_before
    semantic_source_before = file_record(repo / "Scripts/IndexEquivalenceProbe.java", "IndexEquivalenceProbe.java")
    report["semanticSourceBefore"] = semantic_source_before
    source_before = root_inventory(repo)
    frozen = work / "input"
    for module in MODULES:
        relative = Path(module) / "build/classes/kotlin/main"
        shutil.copytree(repo / relative, frozen / relative, copy_function=shutil.copy2)
    frozen_before = root_inventory(frozen)
    require_equal(source_before, frozen_before, "frozen-copy-mismatch")
    report["rootsBefore"] = frozen_before
    project = work / "project"
    project.mkdir()
    (project / "res").mkdir()
    (project / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
        '    <application/>\n</manifest>\n', encoding="utf-8")
    for name in (".kartograph-self.pro", ".kartograph.yml"):
        shutil.copy2(repo / name, project / name)
    project_before = inventory(project)
    report["projectBefore"] = project_before
    baseline = work / "baseline"
    baseline.mkdir()
    # shallow checkout에서도 요청한 고정 object만 확보한다.
    for ref in (SOURCE_BASELINE, BASELINE):
        found = run(["git", "cat-file", "-e", ref + "^{commit}"], repo, env, phase="baseline-presence")
        if found["exit"] != 0:
            successful(["git", "fetch", "--no-tags", "--depth=1", "origin", ref], repo, env, phase="baseline-fetch")
    require_baseline_source(repo, SOURCE_BASELINE, BASELINE, env)
    baseline_source = source_identity(repo, BASELINE, env)
    if baseline_source["commit"] != BASELINE:
        raise DiagnosticError("baseline-resolution-mismatch")
    sources_before = {"baselineSource": source_identity(repo, SOURCE_BASELINE, env),
                      "baseline": baseline_source, "candidate": candidate_source}
    report["sourcesBefore"] = sources_before
    archive = work / "baseline.tar"
    successful(["git", "archive", "--format=tar", "--output", archive, BASELINE], repo, env, phase="baseline-archive")
    with tarfile.open(archive) as source:
        source.extractall(baseline, filter="data")
    for name in ("gradlew", "gradle/wrapper/gradle-wrapper.jar",
                 "gradle/wrapper/gradle-wrapper.properties", "gradle/verification-metadata.xml"):
        require_equal((repo / name).read_bytes(), (baseline / name).read_bytes(), "build-tool-identity-mismatch")
    report["baselinePreparation"] = summary(successful(
        [baseline / "gradlew", "--no-daemon", ":cli:installDist"], baseline, env, timeout=600, phase="baseline-build"))
    distributions = {"baseline": baseline / "cli/build/install/kartograph", "candidate": candidate}
    libs_before = {arm: inventory(dist / "lib") for arm, dist in distributions.items()}
    require_equal(candidate_before, libs_before["candidate"], "candidate-rebuilt-or-changed")
    report["libsBefore"] = libs_before
    launchers_before = {arm: inventory(dist / "bin") for arm, dist in distributions.items()}
    require_equal(launchers_candidate_before, launchers_before["candidate"], "candidate-launchers-changed")
    report["launchersBefore"] = launchers_before
    require_equal(source_before, root_inventory(repo), "source-roots-changed-before-arms")
    require_equal(frozen_before, root_inventory(frozen), "frozen-roots-changed-before-arms")
    require_equal(jdk_before, java_identity(java_home), "selected-jdk-changed-before-arms")
    classpaths = {arm: compile_probe(repo, dist, work / ("probe-" + arm), java_home, env)
                  for arm, dist in distributions.items()}
    probes_before = {arm: inventory(work / ("probe-" + arm)) for arm in distributions}
    report["probesBefore"] = probes_before
    roots = [frozen / module / "build/classes/kotlin/main" for module in MODULES]
    report["profiles"] = []
    report["cli"] = []
    try:
        for profile, sample, arm in schedule():
            result = successful([java_home / "bin/java", "-cp", classpaths[arm], "IndexCostProbe",
                                 profile, *roots], project, env, phase="index-probe")
            data = validate_probe(result, profile)
            report["profiles"].append({"arm": arm, "sample": sample, **data, "process": summary(result)})
        check_profile_counts(report["profiles"])
        commands = {arm: cli_commands(dist / "bin/kartograph", roots, project)
                    for arm, dist in distributions.items()}
        located_nodes = next(row["locatedNodes"] for row in report["profiles"] if row["profile"] == "dead")
        raw = RawStreams(raw_directory if raw_directory is not None else work / "raw")
        paired_cli(commands, project, env, located_nodes, report, raw)
        # 이미 보존한 paired 출력과 비교한다. 새 unprofiled 성공 표본을 만들지 않는다.
        prior = next(row for row in report["cli"] if row["command"] == "dead")
        expected = {"exit": prior["candidate"]["exit"]}
        for stream in ("stdout", "stderr"):
            receipt = prior["raw"]["candidate"][stream]
            value = gzip.decompress((raw.directory / Path(receipt["path"]).name).read_bytes())
            if len(value) != receipt["bytes"] or digest(value) != receipt["sha256"]:
                raise DiagnosticError("jfr-reference-changed")
            expected[stream] = value
        report["jfr"] = capture_jfr(java_home,
            ["-cp", str(candidate / "lib/*"), "dev.kartograph.cli.MainKt", *commands["candidate"][1][1][1:]],
            env, work / "jfr-capture", raw.directory.parent / "jfr", repo / "Scripts/IndexCostCapture.jfc", expected)
        semantic_oracle(repo, distributions, roots, java_home, env, work, report, raw)
    finally:
        report["sourcesAfter"] = {"baselineSource": source_identity(repo, SOURCE_BASELINE, env),
                                  "baseline": source_identity(repo, BASELINE, env),
                                  "candidate": source_identity(repo, "HEAD", env)}
        report["launchersAfter"] = {arm: inventory(dist / "bin") for arm, dist in distributions.items()}
        report["probeSourceAfter"] = file_record(repo / "Scripts/IndexCostProbe.java", "IndexCostProbe.java")
        report["semanticSourceAfter"] = file_record(repo / "Scripts/IndexEquivalenceProbe.java", "IndexEquivalenceProbe.java")
        report["probesAfter"] = {arm: inventory(work / ("probe-" + arm)) for arm in distributions}
        require_equal(sources_before, report["sourcesAfter"], "source-commit-or-tree-changed")
        require_equal(launchers_before, report["launchersAfter"], "distribution-launchers-changed")
        require_equal(probe_source_before, report["probeSourceAfter"], "probe-source-changed")
        require_equal(semantic_source_before, report["semanticSourceAfter"], "semantic-source-changed")
        require_equal(probes_before, report["probesAfter"], "compiled-probes-changed")
        report["rootsAfter"] = root_inventory(frozen)
        report["libsAfter"] = {arm: inventory(dist / "lib") for arm, dist in distributions.items()}
        report["jdkAfter"] = java_identity(java_home)
        report["projectAfter"] = inventory(project)
        require_equal(source_before, root_inventory(repo), "source-roots-changed")
        require_equal(frozen_before, report["rootsAfter"], "frozen-roots-changed")
        require_equal(libs_before, report["libsAfter"], "distribution-libs-changed")
        require_equal(jdk_before, report["jdkAfter"], "selected-jdk-changed")
        require_equal(project_before, report["projectAfter"], "shared-project-changed")


def main():
    repo = Path(__file__).resolve().parents[1]
    output = repo / "build/reports/index-cost/result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {"format": "kartograph-index-cost-v1", "baselineSource": SOURCE_BASELINE, "baseline": BASELINE, "status": "FAIL",
              "scope": "frozen-class-and-config-diagnostic; original-source-tree-omitted; separate-from-mandatory-smoke",
              "residualScope": "process-minus-index-includes-startup-loading-probe-and-shutdown; not-query-only"}
    try:
        env = dict(os.environ)
        if sys.platform != "linux" or not env.get("JAVA_HOME") or not env.get("RUNNER_TEMP"):
            raise DiagnosticError("required-runner-input-missing")
        if any(env.get(name) for name in ("CLASSPATH", "JAVA_OPTS", "KARTOGRAPH_OPTS", "JAVA_TOOL_OPTIONS",
                                         "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
            raise DiagnosticError("injected-java-options")
        with tempfile.TemporaryDirectory(prefix="kartograph-index-cost-", dir=env["RUNNER_TEMP"]) as name:
            experiment(repo, Path(name), env, report, output.parent / "raw")
        report["status"] = "PASS"
    except DiagnosticError as error:
        report["reason"] = str(error)
        if error.receipt is not None:
            report["failure"] = error.receipt
    except Exception:
        report["reason"] = "unexpected-diagnostic-error"
    return write_report(report, output)


if __name__ == "__main__":
    sys.exit(main())
