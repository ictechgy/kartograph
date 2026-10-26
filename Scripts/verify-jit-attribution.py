#!/usr/bin/env python3
"""최대 비용 CLI 한 쌍의 JIT/OSR 귀속을 관측한다. 성능 합격을 판정하지 않는다.

H1: 큰 메서드의 OSR/재컴파일이 종료 직전까지 계속된다. compileId·완료 시각·OSR와
동일 OS TID의 /proc CPU를 대조한다. 컴파일 이벤트가 짧거나 초기에 끝나면 이 설명을
낮춘다. H2: GC/스케줄 대기가 지배한다. GC pause union·GC CPU·runqueue를 별도 수치로
보존한다. 겹친 compilation wall duration의 합을 CPU나 제거 가능한 wall time으로 쓰지 않는다.
"""

import argparse
import collections
import datetime
import gzip
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import resource
import shutil
import signal
import struct
import sys
import time
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("jit_runtime", HERE / "verify-linux-runtime-cost.py")
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)
p, MIB = r.p, r.MIB
EVENTS = ("jdk.Compilation", "jdk.CompilationFailure", "jdk.Deoptimization", "jdk.ExecutionSample",
          "jdk.NativeMethodSample", "jdk.ThreadCPULoad", "jdk.CPULoad", "jdk.GCPhasePause",
          "jdk.GCCPUTime", "jdk.ActiveSetting", "jdk.ActiveRecording", "jdk.DataLoss")
MAX_EVENTS = 100000


def boolean(value):
    if type(value) is not bool:
        r.fail("invalid-jit-boolean")
    return value


def timestamp(value):
    if not isinstance(value, str):
        r.fail("invalid-jit-timestamp")
    match = re.fullmatch(r"(20\d\d-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)", value)
    if not match:
        r.fail("invalid-jit-timestamp")
    zone = "+00:00" if match[3] == "Z" else match[3]
    try:
        seconds = int(datetime.datetime.fromisoformat(match[1] + zone).timestamp())
    except ValueError:
        r.fail("invalid-jit-timestamp")
    return seconds * 1_000_000_000 + int((match[2] or "").ljust(9, "0"))


def token(value, maximum=80):
    if not isinstance(value, str) or not 0 < len(value) <= maximum or not re.fullmatch(r"[A-Za-z0-9_$ .:+<>/-]+", value):
        r.fail("invalid-jit-token")
    return value


def signed(value, lower=-1, upper=2**31 - 1):
    if type(value) is not int or not lower <= value <= upper:
        r.fail("invalid-jit-signed-number")
    return value


def method(value):
    label = r.method_label(value)
    if label == "<unclassified>":
        return {"label": label, "descriptor": "<unclassified>"}
    descriptor = value.get("descriptor")
    if (not isinstance(descriptor, str) or not 0 < len(descriptor) <= 2048
            or not re.fullmatch(r"[A-Za-z0-9_$/;()\[<>-]+", descriptor)):
        r.fail("invalid-jit-descriptor")
    # 허용한 런타임/공개 제품 패키지 외의 descriptor 이름은 보존하지 않는다.
    for owner in re.findall(r"L([^;]+);", descriptor):
        if r.method_label({"type": {"name": owner}, "name": "x"}) == "<unclassified>":
            return {"label": label, "descriptor": "<unclassified>"}
    return {"label": label, "descriptor": descriptor}


def thread(value):
    if value is None:
        return {"osThreadId": None, "role": "unknown"}
    if not isinstance(value, dict):
        r.fail("invalid-jit-thread")
    name = value.get("javaName") or value.get("osName") or ""
    if not isinstance(name, str) or len(name) > 256:
        r.fail("invalid-jit-thread")
    return {"osThreadId": r.number(value["osThreadId"], 2**31 - 1), "role": r.role(name)}


def frame_type(value):
    if value not in ("Interpreted", "JIT compiled", "Inlined", "Native", "C1 compiled", "C2 compiled"):
        r.fail("unknown-jfr-frame-type")
    return value


def chunk_coverage(data):
    """완료된 단일 dumponexit recording의 모든 chunk와 종료 표지를 검사한다."""
    if not 68 <= len(data) <= 32 * MIB:
        r.fail("jit-recording-size-limit")
    chunks, offset = [], 0
    while offset < len(data):
        if len(chunks) >= 64 or len(data) - offset < 68:
            r.fail("invalid-jfr-chunk-header")
        magic, major, minor, size, pool, metadata, start, duration, ticks, frequency, features = struct.unpack_from(
            ">4sHHQQQQQQQI", data, offset)
        if (magic != b"FLR\x00" or major != 2 or minor != 1 or not 68 <= size <= len(data) - offset
                or not 68 <= metadata < size or not 0 <= pool < size or frequency <= 0
                or not 0 <= duration <= 130_000_000_000):
            r.fail("invalid-jfr-chunk-header")
        state, flags = data[offset + 64], data[offset + 67]
        if state != 0:
            r.fail("jfr-chunk-not-finalized")
        chunks.append({"startNs": start, "endNs": start + duration, "bytes": size,
                       "fileState": state, "flags": flags, "headerFlagsWord": features,
                       "finalChunk": bool(flags & 2)})
        offset += size
    if offset != len(data):
        r.fail("truncated-jfr-chunk")
    if not chunks[-1]["finalChunk"]:
        r.fail("jfr-terminal-chunk-not-final")
    return chunks


def interval_union(chunks):
    intervals = []
    for start, stop in sorted((chunk["startNs"], chunk["endNs"]) for chunk in chunks):
        if intervals and start <= intervals[-1][1]:
            intervals[-1][1] = max(intervals[-1][1], stop)
        else:
            intervals.append([start, stop])
    return intervals


def elapsed_outside_intervals(start, stop, intervals):
    # 서로 겹치는 이벤트가 있으므로 이 값을 이벤트 간에 합쳐 CPU/wall 분해로 쓰지 않는다.
    covered = sum(max(0, min(stop, high) - max(start, low)) for low, high in intervals)
    return stop - start - covered


def child_coverage(summary, observations):
    """프로세스 실행을 둘러싼 anchor와 recording 범위의 차이를 관측값으로만 남긴다."""
    coverage = summary["coverage"]
    launch = r.number(observations["launchAnchors"]["epochNs"])
    returned = r.number(observations["returnEpochNs"])
    coverage["childAnchors"] = {"launchEpochNs": launch, "returnEpochNs": returned,
                                "epochOrderConsistent": launch <= returned,
                                "launchToChunkStartNs": coverage["chunkStartEpochNs"] - launch,
                                "chunkEndToReturnNs": returned - coverage["chunkEndEpochNs"],
                                "scope": "anchors bracket supervisor and launcher; not exact OS child start/exit; bootstrap and shutdown coverage remain partial"}


def expected_settings(config):
    root = ET.fromstring(config)
    return {event.attrib["name"]: {setting.attrib["name"]: setting.text for setting in event}
            for event in root if event.tag == "event"}


def normalize_setting(value):
    if value in ("true", "false", "everyChunk", "beginChunk", "endChunk"):
        return value
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,8} ?(?:ns|us|ms|s)", value):
        count, unit = re.fullmatch(r"([0-9]+) ?(ns|us|ms|s)", value).groups()
        return str(int(count) * {"ns": 1, "us": 1000, "ms": 1000000, "s": 1000000000}[unit]) + "ns"
    r.fail("invalid-active-setting")


def sanitize(document, ids, settings, chunks):
    events = document.get("recording", {}).get("events")
    if not isinstance(events, list) or not events or len(events) > MAX_EVENTS:
        r.fail("jit-event-count-limit")
    intervals = interval_union(chunks)
    origin, end = intervals[0][0], intervals[-1][1]
    ledger, counts, pauses, active, recordings = [], collections.Counter(), [], {}, []
    for event in events:
        kind, values = event.get("type"), event.get("values")
        if kind not in EVENTS or not isinstance(values, dict):
            r.fail("unexpected-jit-event")
        at = timestamp(values["startTime"])
        if not any(low <= at <= high for low, high in intervals):
            r.fail("jfr-event-outside-chunk-coverage")
        row = {"event": kind, "atNs": at - origin}
        counts[kind] += 1
        if kind in ("jdk.Compilation", "jdk.Deoptimization", "jdk.CompilationFailure"):
            row.update(compileId=r.number(values["compileId"], 2**32 - 1), thread=thread(values.get("eventThread")))
            if kind != "jdk.CompilationFailure":
                compiler = values["compiler"]
                if compiler not in ("c1", "c2", "jvmci"):
                    r.fail("unknown-jit-compiler")
                row.update(compiler=compiler, method=method(values["method"]))
            if kind == "jdk.Compilation":
                duration = r.duration(values["duration"])
                row.update(durationNs=duration, endNs=at - origin + duration,
                           elapsedOutsideHeaderIntervalsNs=elapsed_outside_intervals(at, at + duration, intervals),
                           level=r.number(values["compileLevel"], 4), isOsr=boolean(values["isOsr"]),
                           success=boolean(values["succeded"]), codeSize=r.number(values["codeSize"]),
                           inlinedBytes=r.number(values["inlinedBytes"]))
            elif kind == "jdk.Deoptimization":
                row.update(bci=signed(values["bci"]), reason=token(values["reason"]), action=token(values["action"]))
            else:
                row["failureMessage"] = "<not-exported>"
        elif kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
            stack = values.get("stackTrace") or {}
            frames = stack.get("frames", [])
            if not isinstance(frames, list) or len(frames) > 32:
                r.fail("jit-stack-limit")
            row.update(thread=thread(values.get("sampledThread")), truncated=boolean(stack.get("truncated", False)))
            row["frames"] = [{"method": method(frame.get("method")), "type": frame_type(frame["type"]),
                              "bci": signed(frame.get("bytecodeIndex", -1))} for frame in frames]
        elif kind in ("jdk.ThreadCPULoad", "jdk.CPULoad"):
            if kind == "jdk.ThreadCPULoad":
                row["thread"] = thread(values.get("eventThread"))
            for field in (("user", "system") if kind == "jdk.ThreadCPULoad" else ("jvmUser", "jvmSystem", "machineTotal")):
                value = values[field]
                if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1:
                    r.fail("invalid-jit-cpu-load")
                row[field] = value
        elif kind == "jdk.GCPhasePause":
            elapsed = r.duration(values["duration"])
            row.update(durationNs=elapsed, gcId=r.number(values["gcId"], 2**32 - 1),
                       elapsedOutsideHeaderIntervalsNs=elapsed_outside_intervals(at, at + elapsed, intervals))
            pauses.append((at, at + elapsed))
        elif kind == "jdk.GCCPUTime":
            row["gcId"] = r.number(values["gcId"], 2**32 - 1)
            for field in ("userTime", "systemTime", "realTime"):
                row[field + "Ns"] = r.duration(values[field])
        elif kind == "jdk.ActiveSetting":
            identity = r.number(values["id"])
            name = values["name"]
            event_name = ids.get(identity)
            if event_name not in settings or name not in settings[event_name]:
                # 식별되지 않은 설정은 값·이름도 내보내지 않는다.
                continue
            setting = normalize_setting(values["value"])
            key = (event_name, name)
            if key in active and active[key] != setting:
                r.fail("active-setting-changed")
            active[key] = setting
            row.update(targetEvent=event_name, setting=name, value=setting)
        elif kind == "jdk.ActiveRecording":
            if values["maxSize"] != 0 or values["maxAge"] not in ("PT0S", "0 s", 0):
                r.fail("circular-or-age-limited-recording")
            start = timestamp(values["recordingStart"])
            # 첫 chunk가 recording 시작 이후이면 앞부분이 보존되지 않은 것이다.
            if start + 1_000_000 < origin or start > end:
                r.fail("recording-head-coverage-lost")
            row.update(recordingStartRelativeNs=start - origin, maxSize=0, maxAge=0)
            recordings.append(row)
        else:
            row.update(amount=r.number(values["amount"]), total=r.number(values["total"]))
            if row["amount"] or row["total"]:
                r.fail("recording-data-loss")
        ledger.append(row)
    for event_name, requirements in settings.items():
        for name, value in requirements.items():
            if active.get((event_name, name)) != normalize_setting(value):
                r.fail("required-active-setting-not-observed")
    if not recordings:
        r.fail("active-recording-not-observed")
    return ledger, {"eventCounts": dict(counts), "gcPauseUnionNs": r.pause_union(pauses),
                    "coverage": {"chunkCount": len(chunks), "durationNs": end - origin,
                                 "chunkStartEpochNs": origin, "chunkEndEpochNs": end,
                                 "chunks": chunks,
                                 "intervalUnionRelativeNs": [[low - origin, high - origin] for low, high in intervals],
                                 "coveredIntervalUnionNs": sum(high - low for low, high in intervals),
                                 "uncoveredEnvelopeNs": end - origin - sum(high - low for low, high in intervals),
                                 "adjacentChunkStartMinusPreviousEndNs": [after["startNs"] - before["endNs"] for before, after in zip(chunks, chunks[1:])],
                                 "activeRecordingStartEpochNs": [origin + row["recordingStartRelativeNs"] for row in recordings],
                                 "durationEventsOutsideHeaderIntervals": sum(row.get("elapsedOutsideHeaderIntervalsNs", 0) > 0 for row in ledger),
                                 "firstEventNs": min(x["atNs"] for x in ledger),
                                 "lastEventNs": max(x.get("endNs", x["atNs"]) for x in ledger),
                                 "noncircular": True, "dataLoss": 0},
                    "limits": "VM bootstrap before recording start is not observed; chunk gaps are unobserved; event durations may span chunks or gaps and overlap; JFR samples skip compiler threads",
                    "cpuLoadDenominator": "JFR ThreadCPULoad is normalized by active processor count; use /proc CPU for compiler-to-child fraction"}


class TimelineObserver(r.Observer):
    """스레드 이름 없이 OS TID와 starttime epoch만 lease 안에서 보존한다."""
    def __init__(self, deadline, private, collect=True):
        super().__init__(deadline, private, collect)
        self.timeline, self.start = [], time.monotonic_ns()
        self.epoch = time.time_ns()
        self.cells = 0

    def sample(self, pid, terminal):
        before = self.samples
        super().sample(pid, terminal)
        if self.collect and self.samples != before:
            if len(self.timeline) >= 2402:
                r.fail("jit-proc-timeline-limit")
            compiler = [{"osThreadId": int(tid), "startTicks": epoch, "counters": values}
                        for (tid, epoch), (kind, values) in sorted(self.rows.items()) if kind == "compiler"]
            self.cells += len(compiler)
            if len(compiler) > 32 or self.cells > 10000:
                r.fail("jit-proc-timeline-cell-limit")
            self.timeline.append({"atNs": time.monotonic_ns() - self.start, "compilerThreads": compiler})

    def report(self):
        result = super().report()
        result["compilerTimeline"] = self.timeline
        result["timelineClock"] = {"monotonicNs": self.start, "epochNs": self.epoch}
        epochs = collections.defaultdict(set)
        for tid, epoch in self.rows:
            epochs[int(tid)].add(epoch)
        result["ambiguousOsThreadIds"] = sorted(tid for tid, values in epochs.items() if len(values) > 1)
        result["observedOsThreadIdCount"] = len(epochs)
        result["tidJoin"] = "exclude ambiguousOsThreadIds across all observed roles; join numeric osThreadId only with one observed startTicks incarnation; unobserved reuse remains unknown; never join Java threadId"
        return result


def checked(arguments, cwd, env, deadline, private, phase, collect=False):
    observer = TimelineObserver(deadline, private, collect)
    anchors = {"epochNs": time.time_ns(), "monotonicNs": time.monotonic_ns()}
    try:
        remaining = min(120, deadline - time.monotonic())
        if remaining < 1:
            r.fail("controller-deadline")
        result = p.run(arguments, cwd, env, timeout=remaining, phase=phase, observer=observer)
        if result["rss"].get("status") != "observed" or result["rss"].get("peakBytes", 0) > 3 * 1024 * MIB:
            raise p.DiagnosticError("owned-final-rss-limit-or-unavailable", p.summary(result))
        if result["exit"] != 0:
            raise p.DiagnosticError("required-child-failed", p.summary(result))
        observed = observer.report()
        observed["launchAnchors"] = anchors
        observed["returnEpochNs"] = time.time_ns()
        return result, observed
    finally:
        observer.close()


TYPE_IDS_SOURCE = '''import java.nio.file.Path;
import jdk.jfr.consumer.RecordingFile;
public class JitEventIds {
  public static void main(String[] args) throws Exception {
    try (var recording = new RecordingFile(Path.of(args[0]))) {
      for (var type : recording.readEventTypes()) {
        if (type.getName().startsWith("jdk.")) System.out.println(type.getId() + " " + type.getName());
      }
    }
  }
}
'''


def event_ids(data):
    if len(data) > 128 * 1024:
        r.fail("event-type-id-limit")
    result = {}
    for line in data.decode("ascii").splitlines():
        match = re.fullmatch(r"([0-9]{1,12}) (jdk\.[A-Za-z0-9_]{1,80})", line)
        if not match or int(match[1]) in result:
            r.fail("invalid-event-type-id")
        result[int(match[1])] = match[2]
    if not set(EVENTS).issubset(set(result.values())):
        r.fail("required-event-type-id-absent")
    return result


def run_pair(arguments, project, env, deadline, private, work, report):
    before = resource.getrusage(resource.RUSAGE_SELF)
    ordinary, observations = checked(arguments, project, env, deadline, private, "reference-dead-private", True)
    report["reference"] = {"process": p.summary(ordinary), "observations": observations}
    profile_env = dict(env, JAVA_OPTS=("-Xlog:jfr+startup=off -XX:FlightRecorderOptions=stackdepth=32,repository=" + str(work / "repository") +
                       " -XX:StartFlightRecording=settings=" + str(work / "capture.jfc") + ",filename=" + str(work / "capture.jfr") + ",maxsize=0,maxage=0,dumponexit=true"))
    profiled, observations = checked(arguments, project, profile_env, deadline, private, "profile-dead-private", True)
    report["profile"] = {"process": p.summary(profiled), "observations": observations}
    if not p.equivalent(ordinary, profiled):
        r.fail("profiled-cli-output-mismatch")
    report["exactOutputEqual"] = True
    after = resource.getrusage(resource.RUSAGE_SELF)
    report["controllerCPUIncludingSampling"] = {"userSeconds": after.ru_utime - before.ru_utime,
                                               "systemSeconds": after.ru_stime - before.ru_stime,
                                               "scope": "both observed child intervals and private-file census; not child CPU"}


def capture(repo, output, private, reference, report, deadline):
    env = {key: os.environ[key] for key in ("JAVA_HOME", "PATH", "HOME", "LANG", "LC_ALL", "TZ") if key in os.environ}
    env["TMPDIR"] = str(private)
    jdk, frozen, project = Path(env["JAVA_HOME"]).resolve(strict=True), private / "input", private / "project"
    distribution = repo / "cli/build/install/kartograph"
    for module in p.MODULES:
        relative = Path(module) / "build/classes/kotlin/main"
        r.reject_aliases(repo / relative)
        shutil.copytree(repo / relative, frozen / relative, copy_function=shutil.copy2)
    r.reject_aliases(distribution)
    project.mkdir()
    (project / "res").mkdir()
    (project / "AndroidManifest.xml").write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n    <application/>\n</manifest>\n')
    for name in (".kartograph-self.pro", ".kartograph.yml"):
        shutil.copy2(repo / name, project / name)
    observed = r.identities(repo, frozen, project, distribution, jdk)
    report["observedIdentity"] = observed
    if observed != reference["canonicalSha256"] or r.seal(p.root_inventory(repo)) != observed["roots"]:
        r.fail("original-linux-identity-mismatch")
    for directory, expected in ((repo, [reference["origin"]["checkout"], reference["origin"]["tree"]]), (HERE.parent, None)):
        source, _ = checked(["git", "rev-parse", "HEAD", "HEAD^{tree}"], directory, env, deadline, private, "source-identity")
        identity = source["stdout"].decode().splitlines()
        if (expected is not None and identity != expected) or len(identity) != 2:
            r.fail("source-identity-mismatch")
        if expected is None:
            report["harnessSource"] = identity
            if identity[0] != os.environ.get("GITHUB_SHA"):
                r.fail("harness-source-mismatch")
        clean, _ = checked(["git", "-c", "core.fsmonitor=false", "status", "--porcelain=v1", "--untracked-files=no"],
                           directory, dict(env, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1"), deadline, private, "source-cleanliness")
        if clean["stdout"]:
            r.fail("tracked-source-dirty")
    metadata, _ = checked([jdk / "bin/jfr", "metadata", "--events", ",".join(EVENTS)], private, env, deadline, private, "jfr-metadata")
    if set(re.findall(r'@Name\("([^"]+)"\)', metadata["stdout"].decode())) != set(EVENTS):
        r.fail("required-jfr-event-unavailable")
    report["metadataSha256"] = p.digest(metadata["stdout"])
    jfr_tool = r.canonical(p.file_record(jdk / "bin/jfr", "bin/jfr"))
    report["jfrTool"] = jfr_tool
    work = private / "profile"
    work.mkdir()
    config = (HERE / "JitAttributionCapture.jfc").read_bytes()
    report["configurationSha256"] = p.digest(config)
    (work / "capture.jfc").write_bytes(config)
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(work)):
        r.fail("unsafe-jfr-option-path")
    roots = [frozen / module / "build/classes/kotlin/main" for module in p.MODULES]
    commands = dict(p.cli_commands(distribution / "bin/kartograph", roots, project))
    arguments = commands["dead_private"]
    report["hostBefore"] = r.host_snapshot()
    run_pair(arguments, project, env, deadline, private, work, report)
    recording = work / "capture.jfr"
    if recording.is_symlink() or not 0 < recording.stat().st_size <= 32 * MIB:
        r.fail("jit-recording-size-limit")
    raw = recording.read_bytes()
    chunks = chunk_coverage(raw)
    report["privateRecording"] = {"bytes": len(raw), "sha256": p.digest(raw), "published": False}
    helper = work / "JitEventIds.java"
    helper.write_text(TYPE_IDS_SOURCE)
    ids_result, _ = checked([jdk / "bin/java", helper, recording], private, env, deadline, private, "recorded-event-ids")
    ids = event_ids(ids_result["stdout"])
    listing, _ = checked([jdk / "bin/jfr", "summary", recording], private, env, deadline, private, "recording-summary")
    counts = {}
    for line in listing["stdout"].decode().splitlines():
        match = re.fullmatch(r"\s*(\S+)\s+(\d+)\s+\d+\s*", line)
        if match and int(match[2]):
            counts[match[1]] = int(match[2])
    if not counts or set(counts) - {*EVENTS, "jdk.CheckPoint", "jdk.Metadata"}:
        r.fail("unexpected-recording-event")
    printed, _ = checked([jdk / "bin/jfr", "print", "--json", "--stack-depth", "32", "--events", ",".join(EVENTS), recording],
                         private, env, deadline, private, "decode-recording")
    ledger, summary = sanitize(json.loads(printed["stdout"]), ids, expected_settings(config), chunks)
    child_coverage(summary, report["profile"]["observations"])
    if any(summary["eventCounts"].get(name, 0) != counts.get(name, 0) for name in EVENTS):
        r.fail("recording-event-count-mismatch")
    data = r.encoded(ledger)
    if len(data) > 16 * MIB:
        r.fail("sanitized-ledger-limit")
    compressed = gzip.compress(data, mtime=0)
    if len(compressed) > 4 * MIB:
        r.fail("sanitized-gzip-limit")
    (output / "events-dead-private.json.gz").write_bytes(compressed)
    report.update(jfr=summary, ledger={"path": "events-dead-private.json.gz", "sha256": p.digest(compressed), "bytes": len(compressed)})
    report["hostAfter"] = r.host_snapshot()
    if observed != r.identities(repo, frozen, project, distribution, jdk) or r.seal(p.root_inventory(repo)) != observed["roots"]:
        r.fail("identity-changed-during-capture")
    report["identityUnchanged"] = True
    if jfr_tool != r.canonical(p.file_record(jdk / "bin/jfr", "bin/jfr")):
        r.fail("jfr-tool-changed")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    reference = json.loads((HERE / "LinuxRuntimeReference.json").read_text())
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"format": "kartograph-jit-attribution-v1", "status": "FAIL", "origin": reference["origin"],
              "command": "dead_private", "acceptanceEvaluated": False,
              "limits": {"controllerSeconds": 420, "childSeconds": 120, "childCpuSeconds": 120,
                         "childRssBytes": 3 * 1024 * MIB, "privateBytes": 256 * MIB, "privateFiles": 4096,
                         "jfrBytes": 32 * MIB, "decodedJsonBytes": p.LIMIT, "events": MAX_EVENTS, "stackDepth": 32},
              "scope": "one adjacent no-JFR/JFR pair, both /proc-observed; not uninstrumented acceptance; no native compiler C-stack attribution",
              "hypotheses": ["late OSR/recompilation explains compiler CPU", "GC or scheduling dominates wall cost"],
              "falsification": ["compilations short/early or not OSR lowers first hypothesis", "small GC/scheduling counters lower second; incomplete observations remain explicit"]}
    def interrupted(signum, frame):
        r.INTERRUPTED = True
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        if (sys.platform != "linux" or os.uname().machine != "x86_64" or not hasattr(resource, "prlimit")
                or not os.environ.get("RUNNER_TEMP") or not os.environ.get("JAVA_HOME")):
            r.fail("required-linux-runner-unavailable")
        if args.target_sha != reference["origin"]["checkout"]:
            r.fail("unreviewed-target")
        if any(os.environ.get(k) for k in ("CLASSPATH", "JAVA_OPTS", "KARTOGRAPH_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
            r.fail("injected-java-options")
        if len(os.sched_getaffinity(0)) != 4:
            r.fail("original-runner-cpu-count-mismatch")
        report["harnessFiles"] = {name: p.digest((HERE / name).read_bytes()) for name in
            ("verify-jit-attribution.py", "JitAttributionCapture.jfc", "verify-linux-runtime-cost.py", "verify-index-cost.py", "LinuxRuntimeReference.json")}
        r.private_workspace(output, report, lambda private: capture(args.repo_root.resolve(strict=True), output, private, reference, report, time.monotonic() + 420))
        report["status"] = "PASS"
    except p.DiagnosticError as error:
        report["reason"] = str(error)
        if error.receipt is not None:
            report["failure"] = error.receipt
    except Exception:
        report["reason"] = "unexpected-diagnostic-error"
    data = r.encoded(report)
    if len(data) > 2 * MIB:
        report = {"format": "kartograph-jit-attribution-v1", "status": "FAIL", "reason": "report-size-limit"}
        data = r.encoded(report)
    (output / "result.json").write_bytes(data + b"\n")
    print(json.dumps({"status": report["status"], "reason": report.get("reason"), "acceptanceEvaluated": False}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
