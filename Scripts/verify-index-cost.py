#!/usr/bin/env python3
"""고정 baseline과 이미 빌드한 후보를 같은 입력으로 관측한다. smoke 판정은 바꾸지 않는다."""

import gzip
from collections import Counter
import hashlib
import json
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

# Compare this patch with its exact main base on the same frozen candidate input.
SOURCE_BASELINE = "bb13a7d8b8e48a2d61ed090e4c8dfe761685bdb1"
BASELINE = "bb13a7d8b8e48a2d61ed090e4c8dfe761685bdb1"
MODULES = ("core", "index", "analysis", "export", "cli", "gradle-plugin")
PROFILES = ("graph", "dead", "architecture")
LIMIT = 32 * 1024 * 1024
RAW_LIMIT = 256 * 1024 * 1024
JFR_LIMIT = 16 * 1024 * 1024
JFR_EVENTS = ("jdk.ExecutionSample", "jdk.ObjectAllocationSample")


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
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def run(arguments, cwd, env, timeout=120, limit=LIMIT, phase="child"):
    """각 child의 두 stream을 제한하고 timeout에는 소유한 process group을 끝낸다."""
    if not re.fullmatch(r"[a-z-]{1,64}", phase):
        raise DiagnosticError("invalid-process-phase")
    started = time.monotonic_ns()
    process = subprocess.Popen(list(map(str, arguments)), cwd=cwd, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True)
    streams = [bytearray(), bytearray()]
    overflow = threading.Event()
    failures = []
    def drain(index, stream):
        try:
            while True:
                chunk = stream.read(65536)
                if not chunk:
                    break
                if len(streams[index]) + len(chunk) > limit:
                    overflow.set()
                    kill_group(process)
                    break
                streams[index].extend(chunk)
        except OSError:
            failures.append("stream-read-failed")
        finally:
            stream.close()
    readers = [threading.Thread(target=drain, args=(i, stream))
               for i, stream in enumerate((process.stdout, process.stderr))]
    for reader in readers:
        reader.start()
    timed_out = False
    wait_failed = False
    rss = {"status": "unavailable", "reason": "wait4-not-supported"}
    def wait_owned():
        nonlocal rss
        if not hasattr(os, "wait4"):
            process.wait(timeout=timeout)
            return
        deadline = time.monotonic() + timeout
        while True:
            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
            if pid:
                process.returncode = os.waitstatus_to_exitcode(status)
                if sys.platform in ("linux", "darwin") and 0 < usage.ru_maxrss < 2**63:
                    rss = {"status": "observed", "peakBytes": int(usage.ru_maxrss) *
                           (1024 if sys.platform == "linux" else 1),
                           "scope": "owned-wait4-child-peak-rss; kernel-accounting-not-live-heap"}
                else:
                    rss = {"status": "unavailable", "reason": "unknown-rusage-units"}
                return
            if time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired(arguments, timeout)
            time.sleep(0.005)
    try:
        wait_owned()
    except subprocess.TimeoutExpired:
        timed_out = True
        kill_group(process)
        process.wait(timeout=10)
    except OSError:
        wait_failed = True
    finally:
        # 정상 종료 뒤 남은 자손도 pipe를 계속 잡지 못하게 한다.
        kill_group(process)
        if process.returncode is None:
            process.wait(timeout=10)
        for reader in readers:
            reader.join(timeout=10)
    result = {"exit": process.returncode, "stdout": bytes(streams[0]), "stderr": bytes(streams[1]),
              "wallNanos": time.monotonic_ns() - started, "rss": rss, "phase": phase}
    reason = ("child-stream-not-closed" if any(reader.is_alive() for reader in readers) else
              "child-timeout" if timed_out else "child-output-limit" if overflow.is_set() else
              "child-stream-failed" if failures else "child-wait-failed" if wait_failed else None)
    if reason:
        raise DiagnosticError(reason, summary(result))
    return result


def summary(result):
    return {"phase": result.get("phase", "child"), "exit": result["exit"], "wallNanos": result["wallNanos"], "rss": result["rss"],
            **{stream: {"bytes": len(result[stream]), "sha256": digest(result[stream])}
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


def expected_dead_outputs(located_nodes):
    baseline = (
        "limitation\tANNOTATION_VALUE_REFERENCES\tclass references in SOURCE-retention annotations are not available in compiled inputs\n"
        "limitation\tDYNAMIC_REGISTRATION\tmanifest-absent runtime component registration is not resolved\n"
        "limitation\tGENERATED_CODE_COVERAGE\tonly supported generation markers and sibling relationships identify generated code\n"
        "limitation\tINLINE_CONSTANT_REFERENCES\tcompile-time constant owners are retained because inlined use sites are unavailable\n"
        "limitation\tREFLECTION_STRINGS\truntime reflection beyond bounded intra-method class and constructor values is not fully resolved\n"
        "limitation\tTOP_LEVEL_DECLARATIONS\tfile-facade top-level properties and their accessors are conservatively not reported\n"
    )
    candidate = (
        "input-hint\tmissing-classpath\tno dependency classpath inputs were supplied; reachability may be under-measured for dependency supertype and framework annotation retention\n"
        "input-hint\tmanifest-without-components\tthe manifest scan produced no component retention evidence; reachability may be under-measured for declarations rooted at manifest components\n"
        + baseline +
        f"limitation\tEXTERNAL_RETENTIONS\tunavailable-source-paths: {located_nodes} located node(s) have no package-compatible source file; generated, external or omitted sources may be absent\n"
        f"limitation\tEXTERNAL_RETENTIONS\tunresolved-source-paths: {located_nodes} of {located_nodes} located node(s) did not match exactly one project source file\n"
    )
    return baseline.encode(), candidate.encode()


def expected_dead_contract_delta(command, left, right, located_nodes):
    # 빈 source/manifest/classpath 고정 fixture의 정확한 계약 확장만 허용한다. 정규화는 하지 않는다.
    if (command not in ("dead", "dead_private") or type(located_nodes) is not int or located_nodes <= 0
            or SOURCE_BASELINE != "bb13a7d8b8e48a2d61ed090e4c8dfe761685bdb1" or BASELINE != SOURCE_BASELINE):
        return False
    baseline, candidate = expected_dead_outputs(located_nodes)
    return (left["exit"] == right["exit"] == 0 and left["stderr"] == right["stderr"] == b""
            and left["stdout"] == baseline and right["stdout"] == candidate)


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
        "probeSourceBefore", "probesBefore") if key in report}
    result["profiles"] = report.get("profiles", [])
    result["cli"] = report.get("cli", [])
    if "cliTiming" in report:
        result["cliTiming"] = report["cliTiming"]
    encoded = json.dumps(result, sort_keys=True)
    if len(encoded.encode("utf-8")) > 65536:
        raise DiagnosticError("public-summary-too-large")
    return encoded


def equivalent(left, right):
    # 순서·중복·진단·줄바꿈·종료 코드를 있는 그대로 비교한다.
    return all(left[key] == right[key] for key in ("exit", "stdout", "stderr"))


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
                    archive.write(data)
            self.bytes += len(data)
            records[stream] = {"path": "raw/" + name, "bytes": len(data), "sha256": digest(data)}
        return records


def paired_cli(commands, project, env, located_nodes, report, raw):
    all_accepted = True
    report["cli"] = []
    report["cliLoad"] = []
    for index, sample, order in cli_schedule():
        name = commands["baseline"][index][0]
        results, retained = {}, {}
        for arm in order:
            before = host_load_snapshot()
            results[arm] = run(commands[arm][index][1], project, env,
                               phase="cli-" + name.replace("_", "-"))
            after = host_load_snapshot()
            retained[arm] = raw.retain(name, sample, arm, results[arm])
            report["cliLoad"].append({"command": name, "sample": sample, "arm": arm,
                                      "before": before, "after": after})
        left, right = results["baseline"], results["candidate"]
        expected_delta = expected_dead_contract_delta(name, left, right, located_nodes)
        accepted = expected_delta if name in ("dead", "dead_private") else successful_pair(left, right)
        all_accepted = all_accepted and accepted
        report["cli"].append({"command": name, "sample": sample, "order": list(order),
                              "exactEqual": equivalent(left, right),
                              "expectedContractDelta": expected_delta, "contractAccepted": accepted,
                              "comparisonScope": "same-input-intentionally-expanded-output" if expected_delta else "exact-byte-comparison",
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
    # 알려진 두 샘플과 내부 metadata 이외의 event가 있으면 원본을 공개 artifact로 복사하지 않는다.
    listing = successful([tool, "summary", recording], work, env, phase="jfr-summary")
    counts = {}
    for line in listing["stdout"].decode("utf-8").splitlines():
        match = re.fullmatch(r"\s*(\S+)\s+(\d+)\s+\d+\s*", line)
        if match and int(match[2]):
            counts[match[1]] = int(match[2])
    if not counts or set(counts) - {*JFR_EVENTS, "jdk.CheckPoint", "jdk.Metadata"}:
        raise DiagnosticError("unexpected-jfr-event-type")
    events = successful([tool, "print", "--json", "--events", ",".join(JFR_EVENTS), recording],
                        work, env, phase="jfr-events")
    samples = summarize_jfr(events["stdout"])
    for name in JFR_EVENTS:
        if samples[name]["events"] != counts.get(name, 0):
            raise DiagnosticError("jfr-event-count-mismatch")
    if not sum(row["events"] for row in samples.values()):
        raise DiagnosticError("jfr-no-samples")
    require_equal(tool_before, file_record(tool, "bin/jfr"), "jfr-tool-changed")
    require_equal(configuration, config.read_bytes(), "jfr-configuration-changed")
    output.mkdir()
    with (output / "candidate.jfr").open("xb") as destination:
        destination.write(recording.read_bytes())
    return {"scope": "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance",
            "command": "dead", "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
            "configurationSha256": digest(configuration), "tool": tool_before,
            "recording": file_record(output / "candidate.jfr", "jfr/candidate.jfr"),
            "eventCounts": counts, "samples": samples, "process": summary(result),
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
    finally:
        report["sourcesAfter"] = {"baselineSource": source_identity(repo, SOURCE_BASELINE, env),
                                  "baseline": source_identity(repo, BASELINE, env),
                                  "candidate": source_identity(repo, "HEAD", env)}
        report["launchersAfter"] = {arm: inventory(dist / "bin") for arm, dist in distributions.items()}
        report["probeSourceAfter"] = file_record(repo / "Scripts/IndexCostProbe.java", "IndexCostProbe.java")
        report["probesAfter"] = {arm: inventory(work / ("probe-" + arm)) for arm in distributions}
        require_equal(sources_before, report["sourcesAfter"], "source-commit-or-tree-changed")
        require_equal(launchers_before, report["launchersAfter"], "distribution-launchers-changed")
        require_equal(probe_source_before, report["probeSourceAfter"], "probe-source-changed")
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
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(public_summary(report))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
