#!/usr/bin/env python3
"""고정 Linux 실패 제품의 여섯 reference/profile 쌍만 관측한다. 시간 합격 판정은 하지 않는다."""

import argparse
import collections
import datetime
import decimal
import gzip
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import resource
import shutil
import signal
import stat
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("runtime_supervisor", HERE / "verify-index-cost.py")
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)
MIB = 1024 * 1024
EVENTS = ("jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.Compilation", "jdk.GarbageCollection",
          "jdk.GCPhasePause", "jdk.ThreadCPULoad", "jdk.CompilerConfiguration", "jdk.GCHeapConfiguration", "jdk.DataLoss")
INTERRUPTED = False


def fail(reason):
    raise p.DiagnosticError(reason)


def canonical(value):
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in sorted(value.items()) if k != "mtimeNs"}
    if isinstance(value, list):
        return [canonical(v) for v in value]
    return value


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def seal(value):
    return p.digest(encoded(canonical(value)))


def number(value, maximum=2**63 - 1):
    if type(value) is not int or not 0 <= value <= maximum:
        fail("invalid-numeric-observation")
    return value


def role(name):
    if re.fullmatch(r"C[12] CompilerTh.*", name):
        return "compiler"
    if name.startswith(("GC Thread", "G1 ")):
        return "gc"
    if (name in ("java", "main", "kartograph-inde") or name.startswith("ForkJoinPool.co")
            or re.fullmatch(r"kartograph-index-worker(?:-[0-9]+)?", name)):
        return "application-including-native"
    if name.startswith(("VM Thread", "VM Periodic")):
        return "vm"
    return "unknown"


def parse_stat(data):
    # comm에는 공백·괄호가 올 수 있다. 원문 이름은 private 분류에만 쓰고 저장하지 않는다.
    end = data.rfind(")")
    start = data.find("(")
    fields = data[end + 1:].split()
    if start < 1 or end <= start or len(fields) < 22 or len(data) > 16384:
        fail("invalid-proc-stat")
    try:
        values = [int(fields[i]) for i in (7, 9, 11, 12, 17, 19, 21)]
    except ValueError:
        fail("invalid-proc-stat")
    if any(x < 0 or x > 2**63 - 1 for x in values):
        fail("invalid-proc-stat")
    return {"minorFaults": values[0], "majorFaults": values[1], "userTicks": values[2], "systemTicks": values[3],
            "threads": values[4], "startTicks": values[5], "rssPages": values[6], "role": role(data[start + 1:end])}


class Observer:
    """동기 poll을 WNOWAIT lock 안에서만 실행하며 reap 뒤에는 /proc를 읽지 않는다."""
    def __init__(self, deadline, private, collect=True):
        self.deadline, self.private, self.collect = deadline, private, collect
        self.fd, self.stopped, self.next = None, False, 0
        self.rows, self.missing, self.samples, self.max_threads = {}, 0, 0, 0
        self.first, self.last, self.max_gap = None, None, 0
        self.usage = None

    def read(self, fd, name):
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
        try:
            data = os.read(descriptor, 16385)
        finally:
            os.close(descriptor)
        if len(data) > 16384:
            fail("proc-read-limit")
        return data.decode("utf-8", errors="strict")

    def sample(self, pid, terminal):
        now = time.monotonic_ns()
        if self.stopped:
            fail("observer-after-terminal")
        if INTERRUPTED or time.monotonic() >= self.deadline:
            fail("controller-interrupted-or-deadline")
        if self.fd is None:
            self.fd = os.open("/proc/" + str(pid), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            if not terminal:
                for kind, maximum in ((resource.RLIMIT_CPU, 120), (resource.RLIMIT_FSIZE, 32 * MIB)):
                    _, hard = resource.prlimit(pid, kind)
                    bounded = maximum if hard == resource.RLIM_INFINITY else min(maximum, hard)
                    resource.prlimit(pid, kind, (bounded, bounded))
        if now < self.next and not terminal:
            return
        self.next = now + 50_000_000
        self.first = self.first if self.first is not None else now
        self.max_gap = max(self.max_gap, now - (self.last or now))
        self.last = now
        own = parse_stat(self.read(self.fd, "stat"))
        if own["rssPages"] * os.sysconf("SC_PAGE_SIZE") > 3 * 1024 * MIB:
            fail("owned-rss-limit")
        self.max_threads = max(self.max_threads, own["threads"])
        if self.max_threads > 256:
            fail("owned-thread-limit")
        if self.collect:
            task = os.open("task", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=self.fd)
            try:
                tids = os.listdir(task)
                if len(tids) > 256 or any(not re.fullmatch(r"[1-9][0-9]{0,9}", tid) for tid in tids):
                    fail("owned-task-list-limit")
                for tid in tids:
                    thread = None
                    try:
                        thread = os.open(tid, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=task)
                        row = parse_stat(self.read(thread, "stat"))
                        sched = self.read(thread, "schedstat").split()
                        if len(sched) != 3 or any(not x.isdecimal() for x in sched):
                            fail("invalid-owned-schedstat")
                        counters = [number(int(x)) for x in sched]
                        key = (tid, row["startTicks"])
                        values = [row["userTicks"], row["systemTicks"], *counters]
                        if key in self.rows and any(a < b for a, b in zip(values, self.rows[key][1])):
                            fail("owned-thread-counter-regression")
                        self.rows[key] = (row["role"], values)
                        if len(self.rows) > 512:
                            fail("owned-thread-incarnation-limit")
                    except FileNotFoundError:
                        self.missing += 1
                    finally:
                        if thread is not None:
                            os.close(thread)
            finally:
                os.close(task)
        total, count = 0, 0
        for root, directories, files in os.walk(self.private, followlinks=False):
            for name in directories + files:
                item = Path(root) / name
                try:
                    metadata = item.lstat()
                except FileNotFoundError:
                    continue  # JVM이 소유한 recording repository의 종료 정리와 경합할 수 있다.
                if stat.S_ISLNK(metadata.st_mode):
                    fail("private-work-symlink")
                count += 1
                total += metadata.st_size if stat.S_ISREG(metadata.st_mode) else 0
                if count > 4096 or total > 256 * MIB:
                    fail("private-work-limit")
        self.samples += 1
        self.stopped = terminal

    def reaped(self, usage):
        self.stopped = True
        self.usage = {"userSeconds": usage.ru_utime, "systemSeconds": usage.ru_stime}

    def close(self):
        self.stopped = True
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def report(self):
        grouped = collections.defaultdict(lambda: [0] * 5)
        for category, values in self.rows.values():
            grouped[category] = [a + b for a, b in zip(grouped[category], values)]
        return {"wait4ChildCPU": self.usage, "ticksPerSecond": os.sysconf("SC_CLK_TCK"),
                "threadLifetimeObservedLowerBounds": dict(grouped),
                "counterColumns": ["userTicks", "systemTicks", "schedulerRuntimeNs", "runqueueWaitNs", "timeslices"],
                "samples": self.samples, "threadIncarnations": len(self.rows), "maxDeclaredThreads": self.max_threads,
                "disappearedThreadReads": self.missing, "maximumPollGapNs": self.max_gap,
                "coverage": "exited threads and their final intervals may be missed; no native-user CPU decomposition"}


def duration(value):
    if not isinstance(value, str):
        fail("invalid-jfr-duration")
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match or not any(match.groups()):
        fail("invalid-jfr-duration")
    seconds = sum(decimal.Decimal(x or 0) * multiplier for x, multiplier in zip(match.groups(), (3600, 60, 1)))
    if not 0 <= seconds <= 120:
        fail("invalid-jfr-duration")
    return int(seconds * 1_000_000_000)


def timestamp(value):
    if not isinstance(value, str):
        fail("invalid-jfr-timestamp")
    match = re.fullmatch(r"(20\d\d-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", value)
    if not match:
        fail("invalid-jfr-timestamp")
    moment = datetime.datetime.fromisoformat(match[1] + "+00:00")
    return int(moment.timestamp()) * 1_000_000_000 + int((match[2] or "").ljust(9, "0"))


def method_label(method):
    if method is not None and not isinstance(method, dict):
        fail("invalid-jfr-method")
    owner, name = (method or {}).get("type", {}).get("name", ""), (method or {}).get("name", "")
    prefixes = ("dev/kartograph/", "java/", "javax/", "jdk/", "sun/", "com/sun/", "org/objectweb/asm/", "kotlin/", "kotlinx/")
    if (not isinstance(owner, str) or not isinstance(name, str) or len(owner) + len(name) > 240
            or not owner.startswith(prefixes) or not re.fullmatch(r"[A-Za-z0-9_$/]+", owner)
            or not re.fullmatch(r"[A-Za-z0-9_$-]+|<init>|<clinit>", name)):
        return "<unclassified>"
    return owner + "." + name


def pause_union(intervals):
    end, total = -1, 0
    for start, stop in sorted(intervals):
        total += max(0, stop - max(start, end))
        end = max(end, stop)
    return total


def sanitize_events(document):
    events = document["recording"]["events"]
    if not isinstance(events, list) or len(events) > 20000:
        fail("jfr-event-limit")
    ledger, counts, pauses = [], collections.Counter(), []
    compilation, samples = collections.Counter(), collections.Counter()
    for event in events:
        kind, values = event["type"], event["values"]
        if kind not in EVENTS or not isinstance(values, dict):
            fail("unexpected-jfr-event")
        at = timestamp(values["startTime"])
        row = {"event": kind, "atNs": at}
        counts[kind] += 1
        if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
            stack = values.get("stackTrace") or {}
            frames = stack.get("frames", [])
            if not isinstance(frames, list) or len(frames) > 32:
                fail("jfr-stack-limit")
            labels = [method_label(frame.get("method")) for frame in frames]
            row["leafMethod"] = labels[0] if labels else "<no-stack>"
            row["nearestProductMethod"] = next((x for x in labels if x.startswith("dev/kartograph/")), "<no-product-frame>")
            thread = values.get("sampledThread") or {}
            thread_name = thread.get("javaName") or thread.get("osName") or ""
            if not isinstance(thread_name, str) or len(thread_name) > 256:
                fail("invalid-jfr-thread")
            row["role"] = role(thread_name)
            truncated = stack.get("truncated", False)
            if type(truncated) is not bool:
                fail("invalid-stack-truncation")
            row["stackTruncated"] = truncated
            samples[(kind, row["leafMethod"], row["nearestProductMethod"])] += 1
        elif kind == "jdk.Compilation":
            row.update(durationNs=duration(values["duration"]), level=number(values["compileLevel"], 4),
                       method=method_label(values["method"]))
            if type(values["succeded"]) is not bool:
                fail("invalid-jfr-compilation")
            row["success"] = values["succeded"]
            compilation[row["method"]] += row["durationNs"]
        elif kind in ("jdk.GarbageCollection", "jdk.GCPhasePause"):
            row["durationNs"] = duration(values["duration"])
            if kind == "jdk.GCPhasePause":
                pauses.append((at, at + row["durationNs"]))
            else:
                row.update(pauseSumNs=duration(values["sumOfPauses"]), longestPauseNs=duration(values["longestPause"]))
        elif kind == "jdk.ThreadCPULoad":
            thread = values.get("eventThread") or {}
            thread_name = thread.get("javaName") or thread.get("osName") or ""
            if not isinstance(thread_name, str) or len(thread_name) > 256:
                fail("invalid-jfr-thread")
            row["role"] = role(thread_name)
            for field in ("user", "system"):
                value = values[field]
                if type(value) not in (float, int) or not math.isfinite(value) or not 0 <= value <= 1:
                    fail("invalid-jfr-cpu-load")
                row[field] = value
        elif kind == "jdk.CompilerConfiguration":
            row["threadCount"] = number(values["threadCount"], 256)
            if type(values["tieredCompilation"]) is not bool:
                fail("invalid-compiler-configuration")
            row["tieredCompilation"] = values["tieredCompilation"]
        elif kind == "jdk.GCHeapConfiguration":
            for field in ("minSize", "maxSize", "initialSize", "objectAlignment", "heapAddressBits"):
                row[field] = number(values[field])
            if type(values["usesCompressedOops"]) is not bool:
                fail("invalid-heap-configuration")
            row["usesCompressedOops"] = values["usesCompressedOops"]
        else:
            row.update(amount=number(values["amount"]), total=number(values["total"]))
        ledger.append(row)
    first = min((row["atNs"] for row in ledger), default=0)
    for row in ledger:
        row["atNs"] -= first
        if row["atNs"] > 130_000_000_000:
            fail("jfr-recording-span-limit")
    if len(compilation) > 10000 or len(samples) > 10000:
        fail("jfr-label-cardinality-limit")
    summary = {"enabledEvents": list(EVENTS), "eventCounts": {k: counts[k] for k in EVENTS},
               "gcPauseUnionNs": pause_union(pauses),
               "compilationEventWallNs": sum(compilation.values()),
               "compilationTop32": compilation.most_common(32),
               "compilationOtherWallNs": sum(compilation.values()) - sum(v for _, v in compilation.most_common(32)),
               "sampleTop50": [[kind, leaf, product, count] for (kind, leaf, product), count in samples.most_common(50)],
               "sampleOtherCount": sum(samples.values()) - sum(v for _, v in samples.most_common(50)),
               "unclassifiedLeafSamples": sum(v for (_, leaf, _), v in samples.items() if leaf == "<unclassified>"),
               "noProductFrameSamples": sum(v for (_, _, product), v in samples.items() if product == "<no-product-frame>"),
               "truncatedStackSamples": sum(row.get("stackTruncated", False) for row in ledger),
               "dataLossBytes": sum(row.get("amount", 0) for row in ledger if row["event"] == "jdk.DataLoss"),
               "settings": [row for row in ledger if row["event"] in ("jdk.CompilerConfiguration", "jdk.GCHeapConfiguration")],
               "coverage": "recording begins after some VM bootstrap; zero observed is not disabled; durations and CPU/sample counters overlap and are not additive",
               "nativeCStackAttribution": "unavailable; native-state Java-stack samples are not native CPU time"}
    return ledger, summary


def checked(arguments, cwd, env, deadline, private, phase, capture=None, collect=False):
    observer = Observer(deadline, private, collect)
    try:
        remaining = min(120, deadline - time.monotonic())
        if remaining < 1:
            fail("controller-deadline")
        result = p.run(arguments, cwd, env, timeout=remaining, phase=phase, capture=capture, observer=observer)
        if result["rss"].get("status") != "observed" or result["rss"].get("peakBytes", 0) > 3 * 1024 * MIB:
            raise p.DiagnosticError("owned-final-rss-limit-or-unavailable", p.summary(result))
        if result["exit"] != 0:
            raise p.DiagnosticError("required-child-failed", p.summary(result))
        return result, observer.report()
    finally:
        observer.close()


def identities(repo, frozen, project, distribution, jdk):
    return {"roots": seal(p.root_inventory(frozen)), "libraries": seal(p.inventory(distribution / "lib")),
            "launchers": seal(p.inventory(distribution / "bin")), "project": seal(p.inventory(project)),
            "jdk": seal(p.java_identity(jdk))}


def reject_aliases(directory):
    for item in directory.rglob("*"):
        if item.is_symlink():
            fail("identity-symlink")


class RuntimeGraphCapture(p.GraphCapture):
    """고정 graph stdout은 32 MiB를 넘으므로 별도의 64 MiB 상한을 적용한다."""
    def append(self, index, chunk):
        if index == 0 and self.size + len(chunk) > 64 * MIB:
            fail("graph-stdout-limit")
        super().append(index, chunk)


def private_workspace(output, report, action):
    temporary = tempfile.TemporaryDirectory(prefix="kartograph-runtime-private-", dir=os.environ["RUNNER_TEMP"])
    primary = None
    try:
        private = Path(temporary.name).resolve()
        if private == output or private in output.parents or output in private.parents:
            fail("raw-public-path-overlap")
        action(private)
    except BaseException as error:
        primary = error
        raise
    finally:
        try:
            temporary.cleanup()
        except OSError:
            report["privateCleanupFailure"] = True
            if primary is None:
                fail("private-cleanup-failed")


def host_snapshot():
    # host 전역 숫자는 다른 작업의 CPU도 포함한다. 프로세스 목록·이름·경로는 수집하지 않는다.
    def bounded(path):
        with path.open("rb") as stream:
            value = stream.read(16385)
        if len(value) > 16384:
            fail("host-counter-limit")
        return value.decode("ascii")
    with Path("/proc/stat").open("rb") as stream:
        first = stream.readline(16385)
    if len(first) > 16384:
        fail("host-counter-limit")
    line = first.decode("ascii").split()
    cpu = [number(int(x)) for x in line[1:]]
    if line[0] != "cpu" or not 8 <= len(cpu) <= 10:
        fail("invalid-host-cpu-stat")
    result = {"cpuTicks": cpu, "cpuTickColumns": ["user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal", "guest", "guestNice"][:len(cpu)],
              "scope": "shared host counters; not owned-process attribution", "cgroupAncestors": []}
    try:
        group = next(x[3:] for x in bounded(Path("/proc/self/cgroup")).splitlines() if x.startswith("0::"))
        root = Path("/sys/fs/cgroup").resolve()
        current = (root / group.lstrip("/")).resolve(strict=True)
        if root != current and root not in current.parents:
            fail("invalid-cgroup-root")
        for depth in range(8):
            quota = bounded(current / "cpu.max").split()
            if len(quota) != 2:
                fail("invalid-cgroup-cpu-max")
            counters = {}
            for row in bounded(current / "cpu.stat").splitlines():
                key, value = row.split()
                if key in ("usage_usec", "user_usec", "system_usec", "nr_periods", "nr_throttled", "throttled_usec"):
                    counters[key] = number(int(value))
            result["cgroupAncestors"].append({"depth": depth, "quotaUs": None if quota[0] == "max" else number(int(quota[0])),
                                                "periodUs": number(int(quota[1])), "counters": counters})
            if current == root:
                break
            current = current.parent
        else:
            result["ancestorCoverage"] = "depth-limit"
    except (OSError, StopIteration):
        result["cgroupAvailability"] = "unavailable-or-partial"
    return result


def capture(repo, output, private, reference, report, deadline):
    env = {key: os.environ[key] for key in ("JAVA_HOME", "PATH", "HOME", "LANG", "LC_ALL", "TZ") if key in os.environ}
    env["TMPDIR"] = str(private)
    jdk = Path(env["JAVA_HOME"]).resolve(strict=True)
    distribution = repo / "cli/build/install/kartograph"
    frozen, project = private / "input", private / "project"
    for module in p.MODULES:
        relative = Path(module) / "build/classes/kotlin/main"
        reject_aliases(repo / relative)
        shutil.copytree(repo / relative, frozen / relative, copy_function=shutil.copy2)
    reject_aliases(distribution)
    project.mkdir()
    (project / "res").mkdir()
    (project / "AndroidManifest.xml").write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n    <application/>\n</manifest>\n')
    for name in (".kartograph-self.pro", ".kartograph.yml"):
        shutil.copy2(repo / name, project / name)
    observed = identities(repo, frozen, project, distribution, jdk)
    report["observedIdentity"] = observed
    if observed != reference["canonicalSha256"] or seal(p.root_inventory(repo)) != observed["roots"]:
        fail("original-linux-identity-mismatch")
    source, _ = checked(["git", "rev-parse", "HEAD", "HEAD^{tree}"], repo, env, deadline, private, "source-identity")
    if source["stdout"].decode().splitlines() != [reference["origin"]["checkout"], reference["origin"]["tree"]]:
        fail("target-checkout-mismatch")
    git_env = dict(env, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
    for directory in (repo, HERE.parent):
        clean, _ = checked(["git", "-c", "core.fsmonitor=false", "status", "--porcelain=v1", "--untracked-files=no"],
                           directory, git_env, deadline, private, "source-cleanliness")
        if clean["stdout"]:
            fail("tracked-source-dirty")
    harness, _ = checked(["git", "rev-parse", "HEAD", "HEAD^{tree}"], HERE.parent, env, deadline, private, "harness-identity")
    report["harnessSource"] = harness["stdout"].decode().splitlines()
    if len(report["harnessSource"]) != 2 or report["harnessSource"][0] != os.environ.get("GITHUB_SHA"):
        fail("harness-source-mismatch")
    jfr_tool = p.file_record(jdk / "bin/jfr", "bin/jfr")
    report["jfrTool"] = canonical(jfr_tool)
    metadata, _ = checked([jdk / "bin/jfr", "metadata", "--events", ",".join(EVENTS)], private, env, deadline, private, "jfr-metadata")
    if set(re.findall(r'@Name\("([^"]+)"\)', metadata["stdout"].decode())) != set(EVENTS):
        fail("required-jfr-event-unavailable")
    config = (HERE / "IndexRuntimeCapture.jfc").read_bytes()
    report["jfrConfigurationSha256"] = p.digest(config)
    roots = [frozen / module / "build/classes/kotlin/main" for module in p.MODULES]
    raw = p.RawStreams(private / "spools")
    commands = p.cli_commands(distribution / "bin/kartograph", roots, project)
    if [name for name, _ in commands] != ["graph", "dead", "dead_private", "cycles", "rules", "metrics"]:
        fail("command-accounting-mismatch")
    for name, arguments in commands:
        row = {"command": name, "status": "RUNNING"}
        report["commands"].append(row)
        work = private / name
        work.mkdir()
        (work / "capture.jfc").write_bytes(config)
        if not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(work)):
            fail("unsafe-jfr-option-path")
        captures = [RuntimeGraphCapture(raw), RuntimeGraphCapture(raw)] if name == "graph" else [None, None]
        primary = None
        try:
            controller_before = resource.getrusage(resource.RUSAGE_SELF)
            row["hostBefore"] = host_snapshot()
            ordinary, cpu = checked(arguments, project, env, deadline, private, "reference-" + name.replace("_", "-"), captures[0], True)
            row["reference"] = {"process": p.summary(ordinary), "observations": cpu}
            profile_env = dict(env, JAVA_OPTS=("-Xlog:jfr+startup=off -XX:FlightRecorderOptions=stackdepth=32,repository=" + str(work / "repository") +
                " -XX:StartFlightRecording=settings=" + str(work / "capture.jfc") + ",filename=" + str(work / "capture.jfr") + ",maxsize=0,dumponexit=true"))
            profiled, cpu = checked(arguments, project, profile_env, deadline, private, "profile-" + name.replace("_", "-"), captures[1], True)
            row["profile"] = {"process": p.summary(profiled), "observations": cpu}
            if not p.equivalent(ordinary, profiled):
                fail("profiled-cli-output-mismatch")
            row["exactOutputEqual"] = True
            recording = work / "capture.jfr"
            if recording.is_symlink() or not 0 < recording.stat().st_size <= 16 * MIB:
                fail("recording-size-limit")
            row["privateRecording"] = {"bytes": recording.stat().st_size, "sha256": p.digest(recording.read_bytes()), "published": False}
            listing, _ = checked([jdk / "bin/jfr", "summary", recording], private, env, deadline, private, "recording-summary")
            counts = {}
            for line in listing["stdout"].decode().splitlines():
                match = re.fullmatch(r"\s*(\S+)\s+(\d+)\s+\d+\s*", line)
                if match and int(match[2]):
                    counts[match[1]] = int(match[2])
            if not counts or set(counts) - {*EVENTS, "jdk.CheckPoint", "jdk.Metadata"}:
                fail("unexpected-recording-event")
            printed, _ = checked([jdk / "bin/jfr", "print", "--json", "--stack-depth", "32", "--events", ",".join(EVENTS), recording],
                                 private, env, deadline, private, "decode-recording")
            ledger, summary = sanitize_events(json.loads(printed["stdout"]))
            if any(summary["eventCounts"][name] != counts.get(name, 0) for name in EVENTS):
                fail("recording-event-count-mismatch")
            data = encoded(ledger)
            if len(data) > 16 * MIB:
                fail("sanitized-ledger-limit")
            compressed = gzip.compress(data, mtime=0)
            if len(compressed) > 4 * MIB:
                fail("sanitized-gzip-limit")
            ledger_name = "events-" + name + ".json.gz"
            (output / ledger_name).write_bytes(compressed)
            row.update(jfr=summary, sanitizedLedger={"path": ledger_name, "bytes": len(compressed), "sha256": p.digest(compressed)}, status="PASS")
            row["hostAfter"] = host_snapshot()
            controller_after = resource.getrusage(resource.RUSAGE_SELF)
            row["controllerCPUIncludingSamplingAndDecode"] = {
                "userSeconds": controller_after.ru_utime - controller_before.ru_utime,
                "systemSeconds": controller_after.ru_stime - controller_before.ru_stime,
                "scope": "includes both child intervals, file census, JFR decode/aggregation; not target CPU"}
            if summary["dataLossBytes"]:
                fail("recording-data-loss")
        except BaseException as error:
            primary = error
            row["status"] = "FAIL"
            raise
        finally:
            cleanup_error = None
            for spool in captures:
                if spool is not None:
                    try:
                        spool.cleanup()
                    except p.DiagnosticError as error:
                        cleanup_error = error
                        row["cleanupFailure"] = True
            if primary is None and cleanup_error is not None:
                raise cleanup_error
    if observed != identities(repo, frozen, project, distribution, jdk) or seal(p.root_inventory(repo)) != observed["roots"]:
        fail("identity-changed-during-capture")
    report["identityUnchanged"] = True
    if canonical(jfr_tool) != canonical(p.file_record(jdk / "bin/jfr", "bin/jfr")):
        fail("jfr-tool-changed")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    reference = json.loads((HERE / "LinuxRuntimeReference.json").read_text())
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"format": "kartograph-linux-runtime-v1", "status": "FAIL", "origin": reference["origin"], "commands": [],
              "buildMode": "in-process; differs from original CI Kotlin-daemon ambient state", "acceptance": "not evaluated; original22.627/20failure retained",
              "limits": {"overallSeconds": 840, "childSeconds": 120, "childCpuSeconds": 120, "childRssBytes": 3 * 1024 * MIB,
                         "ordinaryStreamBytes": p.LIMIT, "graphStdoutBytes": 64 * MIB, "sharedSpoolBytes": p.RAW_LIMIT,
                         "privateWorkBytes": 256 * MIB, "privateFileCount": 4096, "jfrBytes": 16 * MIB, "eventCount": 20000, "stackDepth": 32},
              "host": {"runnerClass": "ubuntu-latest", "samePhysicalHost": False},
              "scope": "six adjacent no-JFR/JFR pairs; both include proc sampling; one each command; raw data never exported; no acceptance medians"}
    def interrupted(signum, frame):
        global INTERRUPTED
        INTERRUPTED = True
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        if (sys.platform != "linux" or os.uname().machine != "x86_64" or not hasattr(resource, "prlimit")
                or not os.environ.get("RUNNER_TEMP") or not os.environ.get("JAVA_HOME")):
            fail("required-linux-runner-unavailable")
        if args.target_sha != reference["origin"]["checkout"]:
            fail("unreviewed-target")
        if any(os.environ.get(k) for k in ("CLASSPATH", "JAVA_OPTS", "KARTOGRAPH_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
            fail("injected-java-options")
        report["host"]["affinityCpuCount"] = len(os.sched_getaffinity(0))
        if report["host"]["affinityCpuCount"] != 4:
            fail("original-runner-cpu-count-mismatch")
        for key in ("ImageOS", "ImageVersion"):
            value = os.environ.get(key)
            report["host"][key] = value if value and re.fullmatch(r"[A-Za-z0-9_.-]{1,60}", value) else "unavailable"
        report["harnessFiles"] = {name: p.digest((HERE / name).read_bytes()) for name in
            ("verify-linux-runtime-cost.py", "verify-index-cost.py", "LinuxRuntimeReference.json", "IndexRuntimeCapture.jfc")}
        private_workspace(output, report, lambda private: capture(
            args.repo_root.resolve(strict=True), output, private, reference, report, time.monotonic() + 840))
        if len(report["commands"]) != 6 or any(row["status"] != "PASS" for row in report["commands"]):
            fail("incomplete-command-accounting")
        report["status"] = "PASS"
    except p.DiagnosticError as error:
        report["reason"] = str(error)
        if error.receipt is not None:
            report["failure"] = error.receipt
    except Exception:
        report["reason"] = "unexpected-diagnostic-error"
    data = encoded(report)
    if len(data) > 2 * MIB:
        report = {"format": "kartograph-linux-runtime-v1", "status": "FAIL", "reason": "report-size-limit"}
        data = encoded(report)
    (output / "result.json").write_bytes(data + b"\n")
    print(json.dumps({"status": report["status"], "reason": report.get("reason"), "commands": len(report.get("commands", [])), "acceptanceEvaluated": False}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
