#!/usr/bin/env python3
"""자체 컴파일 그래프의 분석 불변식(contracts)과 실행 시간(time budget) smoke 게이트를 검증한다."""

import argparse
import hashlib
import json
import math
import os
import re
import stat
import statistics
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import threading


MODULES = ["core", "index", "analysis", "export", "cli", "gradle-plugin"]


class UsageError(Exception):
    """CLI 옵션 오류를 명확한 종료 코드로 구분한다."""


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise UsageError(f"invalid argument: {message}")


def is_working_java(java_bin: Path) -> bool:
    if not java_bin.is_file() or not os.access(java_bin, os.X_OK):
        return False
    try:
        res = subprocess.run(
            [str(java_bin), "-version"],
            capture_output=True,
            timeout=5,
            check=False,
        )
        return res.returncode == 0
    except (subprocess.TimeoutExpired, OSError):
        return False


def check_java_environment():
    env = dict(os.environ)
    if "JAVA_HOME" in env:
        candidate = Path(env["JAVA_HOME"]) / "bin/java"
        if is_working_java(candidate):
            return env
        env.pop("JAVA_HOME", None)

    # Check PATH java first before falling back to fixed installation paths
    try:
        which_java = subprocess.run(
            ["which", "java"], capture_output=True, text=True, env=env, timeout=5
        ).stdout.strip()
        if which_java:
            candidate_bin = Path(which_java).resolve()
            if is_working_java(candidate_bin):
                if candidate_bin.parent.name == "bin":
                    env["JAVA_HOME"] = str(candidate_bin.parent.parent)
                return env
    except (subprocess.TimeoutExpired, OSError):
        pass

    candidates = [
        Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"),
        Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
        Path("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
        Path("/usr/lib/jvm/default-java"),
        Path("/usr/lib/jvm/java-17-openjdk"),
        Path("/usr/lib/jvm/java-21-openjdk"),
    ]
    jvm_dir = Path("/Library/Java/JavaVirtualMachines")
    if jvm_dir.is_dir():
        for item in sorted(jvm_dir.glob("*/Contents/Home")):
            candidates.append(item)

    for c in candidates:
        if is_working_java(c / "bin/java"):
            env["JAVA_HOME"] = str(c)
            env["PATH"] = f"{c}/bin:{env.get('PATH', '')}"
            return env

    return env


def has_working_java(env: dict) -> bool:
    if "JAVA_HOME" in env:
        candidate = Path(env["JAVA_HOME"]) / "bin/java"
        if is_working_java(candidate):
            return True
    try:
        which_java = subprocess.run(
            ["which", "java"], capture_output=True, text=True, env=env, timeout=5
        ).stdout.strip()
        if which_java and is_working_java(Path(which_java)):
            return True
    except (subprocess.TimeoutExpired, OSError):
        pass
    return False


# 관측은 판정 입력이 아니다. 읽기 실패에는 원문/경로 대신 고정된 상태만 남긴다.
class ObservationUnavailable(Exception):
    """부가 관측을 생략한 이유를 고정된 코드로 표현한다."""

    def __init__(self, reason):
        codes = ("unsupported", "unsupported-file-type", "oversized", "malformed", "out-of-range",
                 "unavailable-count", "missing", "unsupported-cgroup", "unsafe-path",
                 "selected-java-home-unavailable", "selected-java-layout-unavailable",
                 "ownership-unavailable", "invalid-counter")
        self.reason = reason if type(reason) is str and reason in codes else "observation-error"
        super().__init__(self.reason)


def unavailable(reason):
    return {"status": "unavailable", "reason": reason}


def observe(function, *args):
    """부가 관측 실패를 격리하되 사용자 중단/프로세스 종료는 가로채지 않는다."""
    try:
        return {"status": "observed", "value": function(*args)}
    except ObservationUnavailable as error:
        return unavailable(error.reason)
    except FileNotFoundError:
        return unavailable("missing")
    except PermissionError:
        return unavailable("denied")
    except (NotImplementedError, ImportError):
        return unavailable("unsupported")
    except (ValueError, UnicodeError):
        return unavailable("malformed")
    except Exception:
        return unavailable("observation-error")


def bounded_read(path, limit=4096):
    """고정 파일의 최대 바이트만 읽고 특수 파일/초과 데이터는 생략한다."""
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
    with os.fdopen(descriptor, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ObservationUnavailable("unsupported-file-type")
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ObservationUnavailable("oversized")
    return data


def safe_string(value, pattern):
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise ObservationUnavailable("malformed")
    return value


def unsigned(value):
    safe_string(value, r"[0-9]{1,19}")
    number = int(value)
    if number > 2 ** 63 - 1:
        raise ObservationUnavailable("out-of-range")
    return number


def positive_count(value):
    if type(value) is not int or not 0 < value <= 2 ** 31 - 1:
        raise ObservationUnavailable("unavailable-count")
    return value


def runner_context(env):
    """공개 CI 필드만 형태를 검사하여 기록한다."""
    patterns = {
        "RUNNER_OS": r"Linux|Windows|macOS",
        "RUNNER_ARCH": r"X86|X64|ARM|ARM64",
        "ImageOS": r"(?:ubuntu|macos|win)[0-9]{2,4}(?:-arm64)?",
        "ImageVersion": r"[0-9][0-9.]{0,63}",
        "GITHUB_SHA": r"[0-9a-fA-F]{40}",
        "GITHUB_RUN_ID": r"[0-9]{1,20}",
        "GITHUB_RUN_ATTEMPT": r"[0-9]{1,20}",
    }
    return {key: observe(safe_string, env[key], pattern) if key in env else unavailable("missing")
            for key, pattern in patterns.items()}


def selected_numbers(text, names, units=""):
    rows = {}
    for line in text.splitlines():
        fields = line.replace(":", " ").split()
        if fields and fields[0] in names:
            key = fields[0]
            if key in rows or len(fields) != (3 if units else 2) or (units and fields[-1] != units):
                raise ObservationUnavailable("malformed")
            rows[key] = observe(unsigned, fields[1])
    return {name: rows.get(name, unavailable("missing")) for name in names}


def cpu_model():
    for line in bounded_read(Path("/proc/cpuinfo"), 128 * 1024).decode("utf-8").splitlines():
        key, separator, value = line.partition(":")
        if separator and key.strip() in ("model name", "Hardware", "Processor"):
            return safe_string(value.strip(), r"[A-Za-z0-9 ().@+,_-]{1,160}")
    raise ObservationUnavailable("missing")


def cpu_policy(text):
    fields = text.split()
    if len(fields) != 2:
        raise ObservationUnavailable("malformed")
    quota = fields[0] if fields[0] == "max" else unsigned(fields[0])
    period = unsigned(fields[1])
    if quota == 0 or period == 0:
        raise ObservationUnavailable("out-of-range")
    return {"quotaMicros": quota, "periodMicros": period}


def cpuset_policy(text):
    value = text.strip()
    safe_string(value, r"[0-9,-]{1,4096}")
    previous = -1
    for item in value.split(","):
        bounds = item.split("-")
        if len(bounds) not in (1, 2):
            raise ObservationUnavailable("malformed")
        start, end = unsigned(bounds[0]), unsigned(bounds[-1])
        if not previous < start <= end < 2 ** 31:
            raise ObservationUnavailable("out-of-range")
        previous = end
    return value


def memory_policy(text):
    value = text.strip()
    return value if value == "max" else unsigned(value)


def cgroup_context():
    """매 snapshot마다 unified leaf를 다시 해석한다. 상위 정책/여유 용량을 추정하지 않는다."""
    lines = bounded_read(Path("/proc/self/cgroup")).decode("utf-8").splitlines()
    unified = [line[3:] for line in lines if line.startswith("0::")]
    if len(unified) != 1:
        raise ObservationUnavailable("unsupported-cgroup")
    relative = unified[0]
    if not relative.startswith("/") or "\\" in relative or "\x00" in relative:
        raise ObservationUnavailable("unsafe-path")
    parts = relative[1:].split("/") if relative != "/" else []
    if any(part in ("", ".", "..") for part in parts):
        raise ObservationUnavailable("unsafe-path")
    root = Path("/sys/fs/cgroup").resolve(strict=True)
    leaf = root.joinpath(*parts).resolve(strict=True)
    if not leaf.is_relative_to(root):
        raise ObservationUnavailable("unsafe-path")

    def read_policy(name, parser):
        path = (leaf / name).resolve(strict=True)
        if path.parent != leaf:
            raise ObservationUnavailable("unsafe-path")
        return parser(bounded_read(path).decode("utf-8"))

    return {
        "scope": "observed-unified-leaf-only; ancestors-and-available-capacity-unobserved",
        "identitySha256": hashlib.sha256(relative.encode("utf-8")).hexdigest(),
        "cpu.max": observe(read_policy, "cpu.max", cpu_policy),
        "cpuset.cpus.effective": observe(read_policy, "cpuset.cpus.effective", cpuset_policy),
        "memory.max": observe(read_policy, "memory.max", memory_policy),
        "cpu.stat": observe(read_policy, "cpu.stat", lambda text: selected_numbers(text, (
            "usage_usec", "user_usec", "system_usec", "nr_periods", "nr_throttled", "throttled_usec"))),
        "memory.events": observe(read_policy, "memory.events", lambda text: selected_numbers(text, (
            "low", "high", "max", "oom", "oom_kill", "oom_group_kill"))),
    }


def java_identity(env):
    """검증 후 선택된 JAVA_HOME의 파일만 관측하며 JVM을 추가 실행하지 않는다."""
    home = env.get("JAVA_HOME")
    if not home:
        raise ObservationUnavailable("selected-java-home-unavailable")
    if len(home) > 4096:
        raise ObservationUnavailable("oversized")
    java = (Path(home) / "bin/java").resolve(strict=True)
    if java.name not in ("java", "java.exe") or java.parent.name != "bin":
        raise ObservationUnavailable("selected-java-layout-unavailable")
    release = bounded_read(java.parent.parent / "release")
    patterns = {
        "JAVA_VERSION": r"[0-9][A-Za-z0-9.+_-]{0,63}",
        "JAVA_RUNTIME_VERSION": r"[0-9][A-Za-z0-9.+_-]{0,63}",
        "IMPLEMENTOR": r"[A-Za-z0-9 .()_-]{1,96}",
        "OS_ARCH": r"[A-Za-z0-9_-]{1,32}",
    }
    fields = {}
    for line in release.decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key in patterns:
            if key in fields or not (value.startswith('"') and value.endswith('"')):
                raise ObservationUnavailable("malformed")
            fields[key] = observe(safe_string, value[1:-1], patterns[key])
    return {
        "scope": "selected-java-path-and-release-file-only; not-runtime-distribution-identity",
        "basename": java.name,
        "resolvedPathSha256": hashlib.sha256(os.fsencode(java)).hexdigest(),
        "releaseSha256": hashlib.sha256(release).hexdigest(),
        "release": {key: fields.get(key, unavailable("missing")) for key in patterns},
    }


def affinity_count():
    if not hasattr(os, "sched_getaffinity"):
        raise ObservationUnavailable("unsupported")
    return positive_count(len(os.sched_getaffinity(0)))


def context_snapshot(env):
    """타이머 밖에서 유한한 runner/resource/JDK 사실만 수집한다."""
    result = {
        "runner": runner_context(env),
        "cpuCount": observe(lambda: positive_count(os.cpu_count())),
        "java": observe(java_identity, env),
        "javaOptionPresence": {key: key in env for key in (
            "JAVA_OPTS", "KARTOGRAPH_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")},
    }
    if sys.platform != "linux":
        result["linux"] = unavailable("unsupported")
        return result
    result["linux"] = {
        "affinityCpuCount": observe(affinity_count),
        "kernelRelease": observe(lambda: safe_string(os.uname().release, r"[A-Za-z0-9.+_-]{1,128}")),
        "cpuModel": observe(cpu_model),
        "memoryKiB": observe(lambda: selected_numbers(
            bounded_read(Path("/proc/meminfo")).decode("utf-8"), ("MemTotal", "MemAvailable"), "kB")),
        "cgroup": observe(cgroup_context),
    }
    return result


_CPU_OWNER_PID = None
_CPU_CLAIMED = False


def dedicated_entry():
    """보통의 파일 직접 실행만 허용한다. import/runpy/-c/-m 등의 귀속은 생략한다."""
    original = getattr(sys, "orig_argv", ())
    return (__name__ == "__main__" and len(original) > 1
            and Path(original[1]).resolve() == Path(__file__).resolve()
            and threading.current_thread() is threading.main_thread()
            and threading.active_count() == 1)


class SmokeDiagnostics:
    """관측 receipt를 판정 수치와 분리하고 단독 순차 실행의 waited-child CPU만 기록한다."""

    def __init__(self, env):
        global _CPU_CLAIMED
        self.cpu_reason = None
        if (_CPU_OWNER_PID != os.getpid() or _CPU_CLAIMED
                or threading.current_thread() is not threading.main_thread()
                or threading.active_count() != 1):
            self.cpu_reason = "ownership-unavailable"
        else:
            _CPU_CLAIMED = True
        self.document = {
            "before": observe(context_snapshot, env),
            "childCpu": {
                "scope": "waited-child-tree-cpu-for-gate-owned-launch; includes-launcher-and-os-accounted-descendants",
                "attribution": unavailable(self.cpu_reason) if self.cpu_reason else {"status": "eligible"},
                "observations": [],
            },
            "unobserved": ["per-command-rss", "effective-child-heap", "effective-child-gc",
                           "effective-child-processor-count", "complete-jvm-flags", "measured-input-identity"],
        }

    def cpu_snapshot(self):
        if (self.cpu_reason or _CPU_OWNER_PID != os.getpid()
                or threading.current_thread() is not threading.main_thread()
                or threading.active_count() != 1):
            self.cpu_reason = "ownership-unavailable"
            raise ObservationUnavailable(self.cpu_reason)
        import resource

        if not hasattr(resource, "RUSAGE_CHILDREN") or not hasattr(resource, "getrusage"):
            raise ObservationUnavailable("unsupported")
        usage = resource.getrusage(resource.RUSAGE_CHILDREN)
        values = (usage.ru_utime, usage.ru_stime)
        if any(type(value) not in (float, int) or not math.isfinite(value) or value < 0 for value in values):
            raise ObservationUnavailable("invalid-counter")
        return values

    def after_child(self, before, after, run, command):
        if before["status"] != "observed" or after["status"] != "observed":
            value = before if before["status"] != "observed" else after
        else:
            values = [end - start for start, end in zip(before["value"], after["value"])]
            if any(not math.isfinite(value) or value < 0 for value in values):
                value = unavailable("invalid-delta")
            else:
                value = {"status": "observed", "userSeconds": values[0], "systemSeconds": values[1]}
        self.document["childCpu"]["observations"].append({"run": run, "command": command, **value})

    def finish(self, env):
        self.document["after"] = observe(context_snapshot, env)
        return self.document


# 명령마다 결과에서 세는 수치 필드. 반복마다 값이 달라지면 분석이 비결정적이라는 뜻이다.
COUNTER_FIELDS = ("nodes", "findings", "rows")


def public_jvm_identity(value):
    """공개 자체 코드의 정규 owner와 JVM field/method descriptor만 허용한다."""
    segment = r"[A-Za-z0-9_$-]+"
    owner = segment + r"(?:/" + segment + r")*"
    project_owner = r"dev/kartograph/" + owner
    scalar = r"(?:[BCDFIJSZ]|L" + owner + r";)"
    field_type = r"\[*" + scalar
    method_name = r"(?:<init>|<clinit>|[A-Za-z_$][A-Za-z0-9_$-]*)"
    patterns = (
        r"class:" + project_owner,
        r"field:" + project_owner + r"#[A-Za-z_$][A-Za-z0-9_$-]*:" + field_type,
        r"method:" + project_owner + r"#" + method_name + r"\((?:" + field_type + r")*\)(?:V|" + field_type + r")",
    )
    return len(value) <= 512 and any(re.fullmatch(pattern, value) for pattern in patterns)


def analysis_failure_receipt(name, result):
    """실패한 자체 분석에서 공개 JVM identity만 제한적으로 추출한다. 위치·환경·stderr는 내보내지 않는다."""
    if result.returncode == 0:
        return None
    stdout = result.stdout or ""
    stderr = result.stderr or ""
    maximum_text = 256 * 1024
    maximum_ids = 10
    stdout_bytes = stdout.encode("utf-8")
    inspected = stdout_bytes[:maximum_text].decode("utf-8", errors="ignore")
    if len(stdout_bytes) > maximum_text:
        inspected = inspected.rsplit("\n", 1)[0] if "\n" in inspected else ""
    identities = []
    rejected = 0
    observed = 0
    for line in inspected.splitlines():
        if not line.startswith("unreachable\t"):
            continue
        observed += 1
        fields = line.split("\t", 2)
        identity = fields[1] if len(fields) >= 2 else ""
        if not public_jvm_identity(identity):
            rejected += 1
        elif len(identities) < maximum_ids:
            identities.append(identity)
    return {
        "format": "kartograph-analysis-process-failure",
        "command": name if name in {"graph", "dead", "dead_private", "cycles", "rules", "metrics"} else "unknown",
        "returncode": result.returncode,
        "stderrBytes": len(stderr.encode("utf-8")),
        "stderrSha256": hashlib.sha256(stderr.encode("utf-8")).hexdigest(),
        "stderrContent": "omitted-untrusted-text",
        "unsupportedOutputShape": observed == 0,
        "stdoutSha256": hashlib.sha256(stdout_bytes).hexdigest(),
        "hashBasis": "decoded-stream-utf8",
        "observedUnreachableRows": observed,
        "identities": identities,
        "rejectedIdentities": rejected,
        "inputTruncated": len(stdout_bytes) > maximum_text,
        "identitiesTruncated": observed - rejected > len(identities),
    }


SELF_ANALYSIS_INPUT_HINTS = (
    "input-hint\tmissing-classpath\tno dependency classpath inputs were supplied; reachability may be under-measured for dependency supertype and framework annotation retention",
    "input-hint\tmanifest-without-components\tthe manifest scan produced no component retention evidence; reachability may be under-measured for declarations rooted at manifest components",
)


def check_contract(name, contract_kind, res, measurement, contract_failures):
    """한 명령의 출력이 자체 분석 계약을 만족하는지 검사하고, 센 수치를 measurement에 기록한다."""
    if res.returncode != 0:
        contract_failures.append(f"'{name}' exited with code {res.returncode}, expected 0")
    elif contract_kind == "node_count":
        node_lines = [l for l in res.stdout.splitlines() if 'shape=' in l and ' -> ' not in l]
        measurement["nodes"] = len(node_lines)
        if len(node_lines) < 2000:
            contract_failures.append(
                f"'{name}' produced {len(node_lines)} nodes, expected >= 2000"
            )
    elif contract_kind == "zero_findings":
        # 이 자기 분석 입력의 알려진 힌트만 구분하고, 미지/중복 진단은 실패로 남긴다.
        lines = res.stdout.splitlines()
        hints = [line for line in lines if line in SELF_ANALYSIS_INPUT_HINTS]
        measurement["inputHints"] = hints
        measurement["limitations"] = [line for line in lines if line.startswith("limitation\t")]
        findings = [line for line in lines if line and not line.startswith("limitation\t")
                    and line not in SELF_ANALYSIS_INPUT_HINTS]
        if len(hints) != len(set(hints)):
            contract_failures.append(f"'{name}' repeated known input hints")
        measurement["findings"] = len(findings)
        if findings:
            contract_failures.append(
                f"'{name}' reported unreachable findings: {findings[:3]}"
            )
    elif contract_kind == "zero_cycles":
        if "0 findings" not in res.stdout:
            contract_failures.append(
                f"'{name}' did not confirm zero cycles: {res.stdout.strip()[:100]}"
            )
    elif contract_kind == "zero_rule_violations":
        if "0 findings, 0 unassigned" not in res.stdout:
            contract_failures.append(
                f"'{name}' did not confirm zero rule violations: {res.stdout.strip()[:100]}"
            )
    elif contract_kind == "valid_metrics":
        metric_rows = [
            l for l in res.stdout.splitlines() if l.strip() and not l.startswith("module\t") and not l.startswith("package\t")
        ]
        measurement["rows"] = len(metric_rows)
        if len(metric_rows) < 6:
            contract_failures.append(
                f"'{name}' produced {len(metric_rows)} metric rows, expected >= 6"
            )


def run_measurement_pass(commands, env, samples, counters, contract_failures, diagnostics=None):
    """명령 묶음을 한 번 실행해 소요 시간·수치를 누적한다.

    도구 실패(2)·사용법 오류(64)는 반복을 이어갈 의미가 없으므로 해당 종료 코드를 즉시 돌려주고,
    정상 종료면 None을 돌려준다. 같은 계약 실패 문구는 한 번만 남긴다.
    """
    for name, cmd, contract_kind in commands:
        if diagnostics is not None:
            cpu_before = observe(diagnostics.cpu_snapshot)
        t0 = time.perf_counter()
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=120)
        except subprocess.TimeoutExpired:
            print(f"error: command '{name}' timed out", file=sys.stderr)
            return 2
        samples[name].append(time.perf_counter() - t0)
        if diagnostics is not None:
            cpu_after = observe(diagnostics.cpu_snapshot)
            observe(diagnostics.after_child, cpu_before, cpu_after, len(samples[name]), name)

        if res.returncode != 0:
            print(json.dumps(analysis_failure_receipt(name, res), sort_keys=True), file=sys.stderr)
        if res.returncode == 2:
            print(f"error: command '{name}' failed with tool failure (exit 2)", file=sys.stderr)
            return 2
        if res.returncode == 64:
            print(f"error: command '{name}' failed with usage error (exit 64)", file=sys.stderr)
            return 64
        measurement = {"returncode": res.returncode}
        run_failures = []
        check_contract(name, contract_kind, res, measurement, run_failures)
        contract_failures.extend(f for f in run_failures if f not in contract_failures)
        counters.setdefault(name, []).append(measurement)
    return None


def summarize_measurements(samples, counters, contract_failures):
    """반복 측정을 명령별 중앙값으로 요약한다.

    중앙값은 한 반복에만 튄 runner 지연을 버리면서도 대부분 반복이 느리면 그대로 드러낸다.
    `seconds`는 기존 소비자 호환을 위해 중앙값을 담고, 원시 표본은 `samples`에 남긴다.
    반복 사이에 센 수치가 다르면 비결정적 분석으로 보고 계약 실패로 기록한다.
    """
    measurements = {}
    for name, values in samples.items():
        runs_for_command = counters.get(name, [])
        last = runs_for_command[-1] if runs_for_command else {}
        rounded_samples = [round(value, 3) for value in values]
        data = {
            # 표시용 중앙값은 반올림한 표본에서 구해 samples와 어긋나지 않게 한다(예산 판정은 원시값).
            "seconds": round(statistics.median(rounded_samples), 3),
            "samples": rounded_samples,
            "returncode": last.get("returncode"),
        }
        for field in COUNTER_FIELDS:
            observed = [run[field] for run in runs_for_command if field in run]
            if not observed:
                continue
            data[field] = observed[-1]
            if len(set(observed)) > 1:
                contract_failures.append(
                    f"'{name}' {field} varied across runs: {observed}"
                )
        for field in ("inputHints", "limitations"):
            observed = [run[field] for run in runs_for_command if field in run]
            if observed:
                data[field] = observed[-1]
                if any(value != observed[0] for value in observed):
                    contract_failures.append(f"'{name}' {field} varied across runs")
        measurements[name] = data
    return measurements


def verify_self_analysis(
    binary: Path,
    repo_root: Path,
    per_command_budget: float,
    total_budget: float,
    warmup: bool = True,
    as_json: bool = False,
    runs: int = 3,
):
    if not binary.is_file() or not os.access(binary, os.X_OK):
        print(f"error: kartograph binary not executable at {binary}", file=sys.stderr)
        return 2

    roots = [repo_root / m / "build/classes/kotlin/main" for m in MODULES]
    for root in roots:
        if not root.is_dir():
            print(f"error: missing compiled class root: {root}", file=sys.stderr)
            return 2
        if not any(root.rglob("*.class")):
            print(f"error: no class files found in {root}", file=sys.stderr)
            return 2

    self_pro = repo_root / ".kartograph-self.pro"
    if not self_pro.is_file():
        print(f"error: missing keep rules file: {self_pro}", file=sys.stderr)
        return 2

    rules_yml = repo_root / ".kartograph.yml"
    if not rules_yml.is_file():
        print(f"error: missing layer rules configuration: {rules_yml}", file=sys.stderr)
        return 2

    env = check_java_environment()
    if not has_working_java(env):
        print(
            "error: no working Java runtime found; set JAVA_HOME to a valid JDK installation",
            file=sys.stderr,
        )
        return 2

    # 일반 텍스트 경로는 부가 관측을 전혀 하지 않는다. 선택된 Java 환경을 그대로 읽는다.
    diagnostic_init = observe(SmokeDiagnostics, env) if as_json else None
    diagnostics = diagnostic_init["value"] if diagnostic_init and diagnostic_init["status"] == "observed" else None

    class_args = []
    for r in roots:
        class_args.extend(["--classes", str(r)])

    with tempfile.TemporaryDirectory(prefix="kartograph-smoke-gate-") as tmpdir:
        project_dir = Path(tmpdir)
        manifest_path = project_dir / "AndroidManifest.xml"
        manifest_path.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
            '    <application/>\n'
            '</manifest>\n'
        )
        res_dir = project_dir / "res"
        res_dir.mkdir()

        if warmup:
            try:
                subprocess.run(
                    [str(binary), "graph", *class_args, "--format", "dot"],
                    capture_output=True,
                    env=env,
                    timeout=120,
                    check=False,
                )
            except subprocess.TimeoutExpired:
                print("error: warmup run timed out", file=sys.stderr)
                return 2

        commands = [
            (
                "graph",
                [str(binary), "graph", *class_args, "--format", "dot"],
                "node_count",
            ),
            (
                "dead",
                [
                    str(binary),
                    "dead",
                    *class_args,
                    "--project",
                    str(project_dir),
                    "--manifest",
                    str(manifest_path),
                    "--resources",
                    str(res_dir),
                    "--namespace",
                    "dev.kartograph",
                    "--keep-rules",
                    str(self_pro),
                    "--strict",
                ],
                "zero_findings",
            ),
            (
                "dead_private",
                [
                    str(binary),
                    "dead",
                    *class_args,
                    "--project",
                    str(project_dir),
                    "--manifest",
                    str(manifest_path),
                    "--resources",
                    str(res_dir),
                    "--namespace",
                    "dev.kartograph",
                    "--keep-rules",
                    str(self_pro),
                    "--include-private-members",
                    "--strict",
                ],
                "zero_findings",
            ),
            (
                "cycles",
                [str(binary), "cycles", *class_args, "--strict"],
                "zero_cycles",
            ),
            (
                "rules",
                [str(binary), "rules", *class_args, "--config", str(rules_yml), "--strict"],
                "zero_rule_violations",
            ),
            (
                "metrics",
                [str(binary), "metrics", *class_args],
                "valid_metrics",
            ),
        ]

        # 한 번의 측정은 GitHub runner 부하에 따라 수백 ms씩 흔들리므로, 명령 묶음을 runs회 반복해
        # 명령별 중앙값으로 판정한다. 계약 검사는 매 반복마다 수행해 약화하지 않는다.
        samples = {name: [] for name, _, _ in commands}
        counters = {}
        contract_failures = []
        for _ in range(runs):
            exit_code = run_measurement_pass(commands, env, samples, counters, contract_failures, diagnostics)
            if exit_code is not None:
                return exit_code

        diagnostic_result = observe(diagnostics.finish, env) if diagnostics is not None else diagnostic_init

        measurements = summarize_measurements(samples, counters, contract_failures)
        # 예산은 반올림 전 원시 중앙값으로 판정해 경계값이 반올림으로 통과하지 않게 한다.
        raw_medians = {name: statistics.median(values) for name, values in samples.items()}
        total_time = sum(raw_medians.values())
        run_totals = [
            round(sum(samples[name][index] for name in samples), 3) for index in range(runs)
        ]
        budget_failures = [
            f"'{name}' took {median:.3f}s (median of {runs} runs), exceeding budget of {per_command_budget:.3f}s"
            for name, median in raw_medians.items()
            if median > per_command_budget
        ]
        if total_time > total_budget:
            budget_failures.append(
                f"total analysis time was {total_time:.3f}s (sum of per-command medians over {runs} runs), exceeding total budget of {total_budget:.3f}s"
            )

        passed = not contract_failures and not budget_failures

        if as_json:
            result_doc = {
                "status": "PASS" if passed else "FAIL",
                "perCommandBudget": per_command_budget,
                "totalBudget": total_budget,
                "runs": runs,
                "totalSeconds": round(total_time, 3),
                "runTotals": run_totals,
                "measurements": measurements,
                "contractFailures": contract_failures,
                "budgetFailures": budget_failures,
            }
            result_doc["diagnostics"] = {
                "version": 1,
                "scope": "observation-only; never-used-for-gate-acceptance",
                **diagnostic_result,
            }
            print(json.dumps(result_doc, indent=2))
        else:
            if passed:
                print(
                    f"[SMOKE GATE PASS] All self-analysis contracts satisfied within performance budget (median of {runs} runs):"
                )
                for name, data in measurements.items():
                    sample_text = ", ".join(f"{value:.3f}" for value in data["samples"])
                    print(
                        f"  - {name:15}: {data['seconds']:.3f}s (budget: {per_command_budget:.3f}s; samples: {sample_text})"
                    )
                print(
                    f"  Total analysis time: {total_time:.3f}s (budget: {total_budget:.3f}s; per-run totals: {', '.join(f'{value:.3f}' for value in run_totals)})"
                )
            else:
                if contract_failures:
                    print("contract failures:", file=sys.stderr)
                    for f in contract_failures:
                        print(f"  - {f}", file=sys.stderr)
                if budget_failures:
                    print("budget failures:", file=sys.stderr)
                    for f in budget_failures:
                        print(f"  - {f}", file=sys.stderr)

        return 0 if passed else 1


def main(argv=None):
    parser = Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument(
        "--binary",
        default=None,
        help="path to kartograph executable",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="repository root path (defaults to auto-detected root)",
    )
    parser.add_argument(
        "--per-command-budget",
        type=float,
        default=5.0,
        help="maximum allowed median time in seconds for each analysis command (default: 5.0)",
    )
    parser.add_argument(
        "--total-budget",
        type=float,
        default=20.0,
        help="maximum allowed sum of per-command median times in seconds (default: 20.0)",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=3,
        help="number of measured repetitions; budgets are judged by per-command medians (default: 3)",
    )
    parser.add_argument(
        "--no-warmup",
        action="store_true",
        help="disable the initial warmup run (excluded from measurements)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="output results in JSON format",
    )

    try:
        options = parser.parse_args(argv)
    except UsageError as err:
        print(f"error: {err}", file=sys.stderr)
        return 64

    if options.per_command_budget <= 0 or options.total_budget <= 0:
        print("error: time budgets must be positive numbers", file=sys.stderr)
        return 64

    if options.runs < 1:
        print("error: --runs must be at least 1", file=sys.stderr)
        return 64

    repo_root = (
        Path(options.repo_root).resolve()
        if options.repo_root
        else Path(__file__).resolve().parents[1]
    )
    binary = (
        Path(options.binary).resolve()
        if options.binary
        else repo_root / "cli/build/install/kartograph/bin/kartograph"
    )

    if options.json and __name__ == "__main__":
        # 부가 관측 실패는 CLI 진입/종료를 막지 않으며 텍스트 경로는 관측하지 않는다.
        global _CPU_OWNER_PID
        if observe(dedicated_entry).get("value") is True:
            _CPU_OWNER_PID = os.getpid()

    return verify_self_analysis(
        binary=binary,
        repo_root=repo_root,
        per_command_budget=options.per_command_budget,
        total_budget=options.total_budget,
        warmup=not options.no_warmup,
        as_json=options.json,
        runs=options.runs,
    )


if __name__ == "__main__":
    sys.exit(main())
