#!/usr/bin/env python3
"""자체 컴파일 그래프의 분석 불변식(contracts)과 실행 시간(time budget) smoke 게이트를 검증한다.

--reference-binary를 주면 같은 machine에서 기준 빌드와 교차 측정해 상대 회귀를 판정한다(CI 수용 기준).
"""

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

# 예전 CI 수용 기준(명령별 중앙값 5초·합계 20초)은 같은 tree도 runner CPU 종류에 따라 통과/실패가 갈려
# 판정에서 뺐다. 절대 수치는 계속 보이도록 JSON에 판정하지 않는 기록(recordedBudget)으로만 남긴다.
RECORDED_COMMAND_BUDGET = 5.0
RECORDED_TOTAL_BUDGET = 20.0
# 두 모드 모두 후보에 적용하는 절대 안전 상한(기록 예산의 2배)이다. 같은 runner 상대 비교가 보지 못하는
# 입력 급증이나 기준·후보 동반 급등 같은 치명적 회귀만 막고, 느린 runner의 정상 결과(최대 약 25초)는 통과시킨다.
DEFAULT_COMMAND_CAP = 10.0
DEFAULT_TOTAL_CAP = 40.0
# 단독 모드의 반복 수는 기존과 같다. 기준 비교는 짝수 라운드여야 명령마다 두 arm이 쌍의 앞·뒤 자리를
# 같은 횟수로 맡아(3:3) 위치 효과가 명령별 비율에 남지 않는다. 홀수(5)는 3:2로 남는다.
STANDALONE_RUNS = 3
REFERENCE_RUNS = 6
# 기준 분석기의 strict·임계값 진단(1)은 분석을 끝낸 결과라 시간을 비교한다. 그 밖의 종료는 판정 불가다.
REFERENCE_DIAGNOSTIC_EXIT = 1
# gate 인자에서 cycles·rules가 FINDINGS(1)로 끝날 때 stdout 마지막 줄에 쓰는 요약이다(ArchitectureCommand).
CYCLES_FINDINGS_SUMMARY = re.compile(r"cycles: ([0-9]+) findings")
RULES_FINDINGS_SUMMARY = re.compile(r"rules: ([0-9]+) findings, ([0-9]+) unassigned")
REVISION = re.compile(r"[0-9a-f]{40}")
# 같은 runner A/A 측정의 잡음에서 정한 상대 허용치다(근거: docs/AGENT-WORKFLOW.md).
DEFAULT_TOTAL_TOLERANCE = 0.05
DEFAULT_COMMAND_TOLERANCE = 0.15


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

    def after_child(self, before, after, run, command, arm=None):
        if before["status"] != "observed" or after["status"] != "observed":
            value = before if before["status"] != "observed" else after
        else:
            values = [end - start for start, end in zip(before["value"], after["value"])]
            if any(not math.isfinite(value) or value < 0 for value in values):
                value = unavailable("invalid-delta")
            else:
                value = {"status": "observed", "userSeconds": values[0], "systemSeconds": values[1]}
        label = {"arm": arm} if arm is not None else {}
        self.document["childCpu"]["observations"].append({"run": run, "command": command, **label, **value})

    def finish(self, env):
        self.document["after"] = observe(context_snapshot, env)
        return self.document


class ArmDiagnostics:
    """기준 비교 모드에서 child CPU 관측 행에 arm 이름을 붙인다. 관측 범위와 판정은 그대로다."""

    def __init__(self, diagnostics, arm):
        self.diagnostics = diagnostics
        self.arm = arm

    def cpu_snapshot(self):
        return self.diagnostics.cpu_snapshot()

    def after_child(self, before, after, run, command):
        self.diagnostics.after_child(before, after, run, command, self.arm)


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


def input_problem(binary, repo_root):
    """측정 전에 실행 파일과 자기 분석 입력을 확인하고 첫 문제를 기존 문구로 돌려준다(없으면 None)."""
    if not binary.is_file() or not os.access(binary, os.X_OK):
        return f"kartograph binary not executable at {binary}"
    for root in [repo_root / module / "build/classes/kotlin/main" for module in MODULES]:
        if not root.is_dir():
            return f"missing compiled class root: {root}"
        if not any(root.rglob("*.class")):
            return f"no class files found in {root}"
    self_pro = repo_root / ".kartograph-self.pro"
    if not self_pro.is_file():
        return f"missing keep rules file: {self_pro}"
    rules_yml = repo_root / ".kartograph.yml"
    if not rules_yml.is_file():
        return f"missing layer rules configuration: {rules_yml}"
    return None


def self_analysis_project(project_dir):
    """자체 분석용 빈 manifest/resource 입력을 소유한 임시 디렉터리 안에 만든다."""
    manifest_path = project_dir / "AndroidManifest.xml"
    manifest_path.write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
        '    <application/>\n'
        '</manifest>\n'
    )
    res_dir = project_dir / "res"
    res_dir.mkdir()
    return manifest_path, res_dir


def analysis_commands(binary, repo_root, project_dir, manifest_path, res_dir):
    """측정하는 여섯 명령과 계약 종류를 고정 순서로 만든다. 기준 비교에서도 두 arm은 실행 파일만 다르다."""
    executable = str(binary)
    class_args = []
    for module in MODULES:
        class_args.extend(["--classes", str(repo_root / module / "build/classes/kotlin/main")])
    dead = [executable, "dead", *class_args, "--project", str(project_dir), "--manifest", str(manifest_path),
            "--resources", str(res_dir), "--namespace", "dev.kartograph",
            "--keep-rules", str(repo_root / ".kartograph-self.pro")]
    return [
        ("graph", [executable, "graph", *class_args, "--format", "dot"], "node_count"),
        ("dead", [*dead, "--strict"], "zero_findings"),
        ("dead_private", [*dead, "--include-private-members", "--strict"], "zero_findings"),
        ("cycles", [executable, "cycles", *class_args, "--strict"], "zero_cycles"),
        ("rules", [executable, "rules", *class_args, "--config", str(repo_root / ".kartograph.yml"), "--strict"],
         "zero_rule_violations"),
        ("metrics", [executable, "metrics", *class_args], "valid_metrics"),
    ]


def budget_failures(raw_medians, per_command_budget, total_budget, runs):
    """반올림 전 명령별 중앙값과 그 합을 절대 예산과 비교한다. 경계값이 반올림으로 통과하지 않게 한다."""
    failures = [
        f"'{name}' took {median:.3f}s (median of {runs} runs), exceeding budget of {per_command_budget:.3f}s"
        for name, median in raw_medians.items()
        if median > per_command_budget
    ]
    total_time = sum(raw_medians.values())
    if total_time > total_budget:
        failures.append(
            f"total analysis time was {total_time:.3f}s (sum of per-command medians over {runs} runs), exceeding total budget of {total_budget:.3f}s"
        )
    return failures


def recorded_budget(arms, runs):
    """예전 5초/20초 기준과의 비교를 판정 없이 기록한다. 절대 수치가 계속 보이게 하는 관측값이다."""
    return {
        "scope": "observation-only; never-used-for-gate-acceptance",
        "perCommandBudget": RECORDED_COMMAND_BUDGET,
        "totalBudget": RECORDED_TOTAL_BUDGET,
        **{arm: {"totalSeconds": round(sum(medians.values()), 3),
                 "exceeded": budget_failures(medians, RECORDED_COMMAND_BUDGET, RECORDED_TOTAL_BUDGET, runs)}
           for arm, medians in arms.items()},
    }


def verify_self_analysis(
    binary: Path,
    repo_root: Path,
    per_command_budget: float,
    total_budget: float,
    warmup: bool = True,
    as_json: bool = False,
    runs: int = STANDALONE_RUNS,
):
    problem = input_problem(binary, repo_root)
    if problem:
        print(f"error: {problem}", file=sys.stderr)
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

    with tempfile.TemporaryDirectory(prefix="kartograph-smoke-gate-") as tmpdir:
        project_dir = Path(tmpdir)
        manifest_path, res_dir = self_analysis_project(project_dir)
        commands = analysis_commands(binary, repo_root, project_dir, manifest_path, res_dir)

        if warmup:
            try:
                subprocess.run(
                    commands[0][1],
                    capture_output=True,
                    env=env,
                    timeout=120,
                    check=False,
                )
            except subprocess.TimeoutExpired:
                print("error: warmup run timed out", file=sys.stderr)
                return 2

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
        budget_problems = budget_failures(raw_medians, per_command_budget, total_budget, runs)

        passed = not contract_failures and not budget_problems

        if as_json:
            result_doc = {
                "status": "PASS" if passed else "FAIL",
                "mode": "standalone",
                "perCommandBudget": per_command_budget,
                "totalBudget": total_budget,
                "runs": runs,
                "totalSeconds": round(total_time, 3),
                "runTotals": run_totals,
                "measurements": measurements,
                "contractFailures": contract_failures,
                "budgetFailures": budget_problems,
                "recordedBudget": recorded_budget({"candidate": raw_medians}, runs),
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
                print_failures(contract_failures, budget_problems)

        return 0 if passed else 1


def print_failures(contract_failures, budget_problems, relative_problems=()):
    for title, failures in (("contract failures:", contract_failures), ("budget failures:", budget_problems),
                            ("relative failures:", relative_problems)):
        if failures:
            print(title, file=sys.stderr)
            for failure in failures:
                print(f"  - {failure}", file=sys.stderr)


class GateAbort(Exception):
    """기준 비교를 끝까지 판정할 수 없는 실패를 종료 코드와 고정된 사유(kind)로 전달한다."""

    def __init__(self, code, kind, message, **details):
        super().__init__(kind)
        self.code = code
        self.kind = kind
        self.message = message
        self.details = details


def relative_schedule(rounds, names):
    """기준 비교의 (라운드, 명령, arm 순서) 목록을 만든다.

    같은 명령의 두 arm을 붙여 실행해 runner 부하 변화가 두 arm에 거의 같이 걸리게 한다. 첫 arm은 명령마다,
    라운드마다 번갈아 각 라운드가 ABBA 순서가 되므로 위치 효과와 라운드 안의 선형 drift가 상쇄된다.
    verify-index-cost.py의 paired schedule과 같은 규칙이다.
    """
    return [
        (index, name, ("reference", "candidate") if (index + position) % 2 == 0 else ("candidate", "reference"))
        for index in range(rounds)
        for position, name in enumerate(names)
    ]


def reference_child(name, cmd, env, phase, round_index):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=120)
    except subprocess.TimeoutExpired:
        raise GateAbort(2, "reference-failed",
                        f"reference command '{name}' timed out during {phase}; the comparison cannot be judged",
                        arm="reference", phase=phase, round=round_index, command=name, timedOut=True) from None
    except OSError as error:
        # 실행 자체가 안 되는 기준도 판정 불가다. 경로가 섞일 수 있는 원문 대신 예외 종류만 남긴다.
        raise GateAbort(2, "reference-failed",
                        f"reference command '{name}' could not be launched ({type(error).__name__}) during {phase}; "
                        "the comparison cannot be judged",
                        arm="reference", phase=phase, round=round_index, command=name,
                        launchError=type(error).__name__) from None


def reference_findings_output(name, stdout):
    """기준 명령의 종료 1이 그 명령의 FINDINGS 출력인지, gate 인자에서 실제 CLI가 쓰는 모양 그대로 확인한다.

    JVM 안에서 1은 FINDINGS뿐이지만(Main.kt는 모든 Throwable을 2로 바꾼다) HotSpot은 VM 초기화 실패
    (예: -Xss1k, -XX:MaxHeapSize=1k)를 stdout에 쓰고 1로 끝나므로 stdout 유무로는 가릴 수 없다. gate 인자에서
    1로 끝나는 경로마다 CLI가 쓰는 출력은 이렇다.
    - dead·dead_private: finding이 있을 때만 1이고(DeadCommand) finding마다 `unreachable\\t` 행을 쓴다(AdoptionReporter).
    - cycles: 마지막 줄 `cycles: N findings`, 1이면 N ≥ 1이다.
    - rules: 마지막 줄 `rules: N findings, M unassigned`, 1이면 N + M ≥ 1이다.
    - graph·metrics: 1로 끝나는 FINDINGS 경로가 없다.
    이 판별은 기준 빌드의 텍스트 출력에 묶여 있다. 요약 줄이나 행 접두사를 바꾸는 변경은 기준 빌드가 옛 형식을
    쓰는 동안 옛 형식도 받아들여야 하며, 그러지 않으면 performance job이 reference-failed로 닫혀 사람이 판단한다.
    """
    lines = (stdout or "").splitlines()
    if name in ("dead", "dead_private"):
        return any(line.startswith("unreachable\t") for line in lines)
    summary = lines[-1] if lines else ""
    if name == "cycles":
        match = CYCLES_FINDINGS_SUMMARY.fullmatch(summary)
        return match is not None and int(match.group(1)) >= 1
    if name == "rules":
        match = RULES_FINDINGS_SUMMARY.fullmatch(summary)
        return match is not None and int(match.group(1)) + int(match.group(2)) >= 1
    return False


def reference_completion(name, result, phase, round_index):
    """기준이 분석을 끝냈는지 판단해 종료 코드를 돌려준다. 끝내지 못했으면 통과나 단독 판정 대신 닫는다.

    0과 strict·임계값 진단(1)은 분석을 끝낸 결과라 시간을 비교한다. 후보가 새 규칙으로만 보존되는 코드나
    기준이 모르는 진단을 더하면 기준은 1로 끝날 수 있다. 1은 그 명령의 FINDINGS 출력이 있을 때만 진단으로
    재고(reference_findings_output), 그 밖의 1(VM 초기화 실패·시작 script 오류 등)과 2·64·그 밖의 값은 도구가
    분석을 끝내지 못한 것이다. 즉시 끝난 기준을 진단으로 재면 후보가 몇 배 느린 회귀로 보이기 때문이다.
    """
    if result.returncode == 0:
        return 0
    if result.returncode == REFERENCE_DIAGNOSTIC_EXIT and reference_findings_output(name, result.stdout):
        return result.returncode
    print(json.dumps({"arm": "reference", **analysis_failure_receipt(name, result)}, sort_keys=True), file=sys.stderr)
    if result.returncode == REFERENCE_DIAGNOSTIC_EXIT:
        raise GateAbort(2, "reference-failed",
                        f"reference command '{name}' exited with code 1 without findings output for that command "
                        f"during {phase}; the base build did not complete the analysis (for example a JVM "
                        "initialization or launcher failure), so the comparison cannot be judged",
                        arm="reference", phase=phase, round=round_index, command=name, returncode=result.returncode,
                        reason="exit-1-without-findings-output")
    raise GateAbort(2, "reference-failed",
                    f"reference command '{name}' exited with code {result.returncode} during {phase}; "
                    "the base build cannot complete these inputs, so the comparison cannot be judged",
                    arm="reference", phase=phase, round=round_index, command=name, returncode=result.returncode)


def candidate_launch_failure(name, error, phase, round_index=None):
    """실행되지 못한 후보를 traceback 대신 판정 불가로 닫는다. 경로가 섞일 수 있는 원문 대신 예외 종류만 남긴다."""
    location = {"round": round_index} if phase == "measurement" else {}
    return GateAbort(2, "candidate-failed",
                     f"candidate command '{name}' could not be launched ({type(error).__name__}) during {phase}",
                     arm="candidate", phase=phase, **location, command=name, launchError=type(error).__name__)


def warm_up_arms(arm_commands, env):
    """측정에서 뺀 warm-up으로 arm마다 여섯 명령을 한 번씩 실행해 실행 파일·입력의 page cache를 채운다.

    기준이 끝내지 못하면 바로 닫는다. 후보 결과는 기존 단독 warm-up처럼 판정하지 않고 측정 라운드의 계약 검사가 판정한다.
    """
    for _, name, order in relative_schedule(1, list(arm_commands["candidate"])):
        for arm in order:
            cmd = arm_commands[arm][name][0]
            if arm == "reference":
                reference_completion(name, reference_child(name, cmd, env, "warmup", None), "warmup", None)
                continue
            try:
                subprocess.run(cmd, capture_output=True, env=env, timeout=120, check=False)
            except subprocess.TimeoutExpired:
                raise GateAbort(2, "candidate-failed", f"candidate warmup command '{name}' timed out",
                                arm="candidate", phase="warmup", command=name, timedOut=True) from None
            except OSError as error:
                raise candidate_launch_failure(name, error, "warmup") from None


def measure_reference(name, cmd, env, samples, returncodes, diagnostics, round_index):
    """기준 명령 하나의 wall 시간을 후보와 같은 방식(perf_counter로 child 실행만)으로 잰다."""
    cpu_before = observe(diagnostics.cpu_snapshot) if diagnostics is not None else None
    started = time.perf_counter()
    result = reference_child(name, cmd, env, "measurement", round_index)
    elapsed = time.perf_counter() - started
    if diagnostics is not None:
        cpu_after = observe(diagnostics.cpu_snapshot)
        observe(diagnostics.after_child, cpu_before, cpu_after, len(samples[name]) + 1, name)
    returncodes[name].append(reference_completion(name, result, "measurement", round_index))
    samples[name].append(elapsed)


def shared_arguments(arm_commands, reference_binary, binary):
    """두 arm이 실행 파일만 다르고 같은 인자로 같은 입력을 분석하는지 확인하고 공유 인자 digest를 돌려준다.

    기준에만 일을 더하거나(예: --explain) 입력·strict를 한쪽에서 빼면 비교가 불공정해지므로 실행 전에 닫는다.
    """
    executables = {"reference": str(reference_binary), "candidate": str(binary)}
    names = list(arm_commands["candidate"])
    if list(arm_commands["reference"]) != names:
        raise GateAbort(2, "arguments-differ", "reference and candidate command lists differ", command=None)
    for name in names:
        reference_cmd, candidate_cmd = arm_commands["reference"][name][0], arm_commands["candidate"][name][0]
        if (reference_cmd[0] != executables["reference"] or candidate_cmd[0] != executables["candidate"]
                or reference_cmd[1:] != candidate_cmd[1:]):
            raise GateAbort(2, "arguments-differ",
                            f"reference and candidate arguments for '{name}' differ beyond the executable",
                            command=name)
    shared = [[name, *arm_commands["candidate"][name][0][1:]] for name in names]
    return hashlib.sha256(json.dumps(shared, separators=(",", ":")).encode("utf-8")).hexdigest()


def compare_to_reference(reference, candidate, total_tolerance, command_tolerance):
    """교차 측정한 표본의 명령별 중앙값으로 후보/기준 비율을 구하고 허용치를 넘은 항목을 실패로 돌려준다.

    합계는 기존 예산과 같은 명령별 중앙값의 합이다. 중앙값을 먼저 구하므로 서로 다른 라운드에 흩어진 단발
    지연이 합계 비율로 번지지 않는다. 라운드별 쌍 비율(roundRatios)은 기록만 하고 판정에 쓰지 않는다.
    """
    reference_medians = {name: statistics.median(values) for name, values in reference.items()}
    candidate_medians = {name: statistics.median(candidate[name]) for name in reference}
    if any(not math.isfinite(value) or value <= 0 for value in reference_medians.values()):
        raise GateAbort(2, "reference-failed", "reference produced a non-positive median duration",
                        arm="reference", phase="comparison")
    command_limit = 1 + command_tolerance
    total_limit = 1 + total_tolerance
    failures = []
    commands = {}
    for name, reference_median in reference_medians.items():
        ratio = candidate_medians[name] / reference_median
        commands[name] = {"referenceSeconds": round(reference_median, 3),
                          "candidateSeconds": round(candidate_medians[name], 3),
                          "ratio": round(ratio, 4), "limit": round(command_limit, 4),
                          "exceeded": ratio > command_limit}
        if ratio > command_limit:
            failures.append(
                f"'{name}' median {candidate_medians[name]:.3f}s is {ratio:.4f}x the reference median "
                f"{reference_median:.3f}s, exceeding {command_limit:.4f}x"
            )
    reference_total = sum(reference_medians.values())
    candidate_total = sum(candidate_medians.values())
    total_ratio = candidate_total / reference_total
    if total_ratio > total_limit:
        failures.append(
            f"total {candidate_total:.3f}s (sum of per-command medians) is {total_ratio:.4f}x the reference "
            f"{reference_total:.3f}s, exceeding {total_limit:.4f}x"
        )
    rounds = min(len(values) for values in reference.values())
    round_ratios = [
        round(sum(candidate[name][index] for name in reference) / sum(reference[name][index] for name in reference), 4)
        for index in range(rounds)
    ]
    return failures, {
        "statistic": "ratio-of-per-command-medians; total-is-ratio-of-sums-of-medians",
        "totalTolerance": total_tolerance,
        "commandTolerance": command_tolerance,
        "referenceTotalSeconds": round(reference_total, 3),
        "candidateTotalSeconds": round(candidate_total, 3),
        "totalRatio": round(total_ratio, 4),
        "totalLimit": round(total_limit, 4),
        "totalExceeded": total_ratio > total_limit,
        "commands": commands,
        "roundRatios": round_ratios,
        "roundRatiosScope": "per-round candidate/reference six-command sum; observation-only",
    }


def verify_against_reference(
    binary: Path,
    reference_binary: Path,
    repo_root: Path,
    per_command_budget: float,
    total_budget: float,
    total_tolerance: float = DEFAULT_TOTAL_TOLERANCE,
    command_tolerance: float = DEFAULT_COMMAND_TOLERANCE,
    warmup: bool = True,
    as_json: bool = False,
    runs: int = REFERENCE_RUNS,
    revisions=None,
    summary_path=None,
):
    """후보와 기준 실행 파일을 같은 입력으로 같은 machine에서 교차 측정해 상대 회귀와 절대 상한을 판정한다.

    후보에는 단독 모드와 같은 계약 검사를 매 실행 적용한다. 기준은 모든 명령에서 분석을 끝내야(0 또는 strict
    진단 1) 하며, 끝내지 못하면 통과로 바꾸지 않고 종료 코드 2와 `reference-failed`로 닫는다. 비교는 변경
    하나 단위이므로 허용치 아래 회귀의 누적과 입력 증가는 절대 안전 상한만 막는다. `--json`이면 오류도 JSON
    문서 하나로 남기고, summary_path가 있으면 같은 문서를 markdown으로 덧붙인다.
    """
    settings = {"perCommandBudget": per_command_budget, "totalBudget": total_budget, "runs": runs,
                "totalTolerance": total_tolerance, "commandTolerance": command_tolerance}
    revisions = {"reference": None, "candidate": None, **(revisions or {})}
    state = {"env": None, "init": None, "diagnostics": None, "finished": None}
    try:
        status, document = compare_with_reference(binary, reference_binary, repo_root, settings, warmup, as_json,
                                                  state, revisions)
    except GateAbort as abort:
        if abort.message:
            print(f"error: {abort.message}", file=sys.stderr)
        document = {"status": "ERROR", "mode": "reference", **settings, "revisions": revisions,
                    "error": {"kind": abort.kind, **abort.details}}
        diagnostics = diagnostics_block(state)
        if diagnostics is not None:
            document["diagnostics"] = diagnostics
        if as_json:
            print(json.dumps(document, indent=2))
        status = abort.code
    if summary_path is not None:
        append_summary(summary_path, document)
    return status


def diagnostics_block(state):
    if state["finished"] is None and state["diagnostics"] is not None:
        state["finished"] = observe(state["diagnostics"].finish, state["env"])
    result = state["finished"] if state["finished"] is not None else state["init"]
    if result is None:
        return None
    return {"version": 1, "scope": "observation-only; never-used-for-gate-acceptance", **result}


def compare_with_reference(binary, reference_binary, repo_root, settings, warmup, as_json, state, revisions):
    if not reference_binary.is_file() or not os.access(reference_binary, os.X_OK):
        raise GateAbort(2, "reference-unavailable", f"reference kartograph binary not executable at {reference_binary}",
                        arm="reference")
    problem = input_problem(binary, repo_root)
    if problem:
        raise GateAbort(2, "input-unavailable", problem)
    env = check_java_environment()
    if not has_working_java(env):
        raise GateAbort(2, "input-unavailable",
                        "no working Java runtime found; set JAVA_HOME to a valid JDK installation")
    state["env"] = env
    if as_json:
        state["init"] = observe(SmokeDiagnostics, env)
        if state["init"]["status"] == "observed":
            state["diagnostics"] = state["init"]["value"]
    runs = settings["runs"]

    with tempfile.TemporaryDirectory(prefix="kartograph-smoke-gate-") as tmpdir:
        project_dir = Path(tmpdir)
        manifest_path, res_dir = self_analysis_project(project_dir)
        # 두 arm 모두 후보 저장소의 class root·keep rules·layer 설정을 쓴다. 입력 크기 변화가 아니라 분석기 비용만 비교한다.
        arm_commands = {
            arm: {name: (cmd, kind) for name, cmd, kind in
                  analysis_commands(executable, repo_root, project_dir, manifest_path, res_dir)}
            for arm, executable in (("reference", reference_binary), ("candidate", binary))
        }
        shared_digest = shared_arguments(arm_commands, reference_binary, binary)
        names = list(arm_commands["candidate"])
        if warmup:
            warm_up_arms(arm_commands, env)

        samples = {arm: {name: [] for name in names} for arm in arm_commands}
        reference_returncodes = {name: [] for name in names}
        counters = {}
        contract_failures = []
        labelled = {arm: ArmDiagnostics(state["diagnostics"], arm) if state["diagnostics"] is not None else None
                    for arm in arm_commands}
        schedule = relative_schedule(runs, names)
        for index, name, order in schedule:
            for arm in order:
                cmd, kind = arm_commands[arm][name]
                if arm == "reference":
                    measure_reference(name, cmd, env, samples["reference"], reference_returncodes,
                                      labelled["reference"], index)
                    continue
                # 후보는 단독 모드와 같은 함수로 재고 같은 계약을 매 실행 검사한다.
                try:
                    exit_code = run_measurement_pass([(name, cmd, kind)], env, samples["candidate"], counters,
                                                     contract_failures, labelled["candidate"])
                except OSError as error:
                    raise candidate_launch_failure(name, error, "measurement", index) from None
                if exit_code is not None:
                    raise GateAbort(exit_code, "candidate-failed", None, arm="candidate", phase="measurement",
                                    round=index, command=name)

        diagnostics = diagnostics_block(state)
        measurements = summarize_measurements(samples["candidate"], counters, contract_failures)
        raw_medians = {arm: {name: statistics.median(values) for name, values in arm_samples.items()}
                       for arm, arm_samples in samples.items()}
        cap_failures = budget_failures(raw_medians["candidate"], settings["perCommandBudget"],
                                       settings["totalBudget"], runs)
        relative_failures, comparison = compare_to_reference(samples["reference"], samples["candidate"],
                                                             settings["totalTolerance"], settings["commandTolerance"])
        run_totals = {arm: [round(sum(arm_samples[name][index] for name in names), 3) for index in range(runs)]
                      for arm, arm_samples in samples.items()}
        passed = not contract_failures and not cap_failures and not relative_failures
        recorded = recorded_budget(raw_medians, runs)
        diagnostic_exits = sum(code == 1 for codes in reference_returncodes.values() for code in codes)
        reference_measurements = {}
        for name, values in samples["reference"].items():
            rounded = [round(value, 3) for value in values]
            reference_measurements[name] = {"seconds": round(statistics.median(rounded), 3), "samples": rounded,
                                            "returncode": reference_returncodes[name][-1],
                                            "returncodes": reference_returncodes[name]}

        result_doc = {
            "status": "PASS" if passed else "FAIL",
            "mode": "reference",
            **settings,
            "revisions": revisions,
            "totalSeconds": round(sum(raw_medians["candidate"].values()), 3),
            "runTotals": run_totals["candidate"],
            "measurements": measurements,
            "contractFailures": contract_failures,
            "budgetFailures": cap_failures,
            "relativeFailures": relative_failures,
            # 기준의 strict 진단(1)은 시간을 비교하되 측정 라운드에서 몇 번이었는지 남긴다.
            "referenceDiagnosticExits": diagnostic_exits,
            "reference": {
                "totalSeconds": round(sum(raw_medians["reference"].values()), 3),
                "runTotals": run_totals["reference"],
                "measurements": reference_measurements,
            },
            "comparison": comparison,
            "schedule": {
                "scope": "same-machine; per-command adjacent pairs; first arm alternates by command and round (ABBA)",
                "warmupSuitesPerArm": 1 if warmup else 0,
                "firstArm": [[order[0] for round_index, _, order in schedule if round_index == index]
                             for index in range(runs)],
                "sharedArgumentsSha256": shared_digest,
            },
            "recordedBudget": recorded,
        }
        if diagnostics is not None:
            result_doc["diagnostics"] = diagnostics
        if as_json:
            print(json.dumps(result_doc, indent=2))
        else:
            headline = "[SMOKE GATE PASS]" if passed else "[SMOKE GATE FAIL]"
            print(f"{headline} Candidate compared with the reference build on this machine "
                  f"(medians of {runs} interleaved rounds):")
            for name, row in comparison["commands"].items():
                print(f"  - {name:15}: {row['candidateSeconds']:.3f}s vs reference {row['referenceSeconds']:.3f}s "
                      f"(ratio {row['ratio']:.4f}; limit {row['limit']:.4f})")
            print(f"  Total: {comparison['candidateTotalSeconds']:.3f}s vs reference "
                  f"{comparison['referenceTotalSeconds']:.3f}s (ratio {comparison['totalRatio']:.4f}; "
                  f"limit {comparison['totalLimit']:.4f}); safety cap {settings['perCommandBudget']:.3f}s per command, "
                  f"{settings['totalBudget']:.3f}s total")
            print(f"  Recorded {RECORDED_COMMAND_BUDGET:.3f}s/{RECORDED_TOTAL_BUDGET:.3f}s budget (not gating): "
                  f"{len(recorded['candidate']['exceeded'])} candidate and "
                  f"{len(recorded['reference']['exceeded'])} reference exceedance(s)")
            if diagnostic_exits:
                print(f"  Reference exited 1 (strict diagnostics) in {diagnostic_exits} measured run(s); "
                      "its time is still compared.")
            if not passed:
                print_failures(contract_failures, cap_failures, relative_failures)

        return (0 if passed else 1), result_doc


SUMMARY_DETAILS = ("command", "phase", "round", "returncode", "timedOut", "launchError")


def observed_cpu_model(document):
    try:
        model = document["diagnostics"]["value"]["before"]["value"]["linux"]["cpuModel"]
    except (KeyError, TypeError):
        return "unavailable"
    return model.get("value", "unavailable") if model.get("status") == "observed" else "unavailable"


def performance_summary(document):
    """GitHub job summary용 markdown을 만든다. 판정 문서의 수치를 옮길 뿐 새 판정을 하지 않는다.

    main push마다 기준·후보의 절대 합계와 비율이 남아, 허용치 아래 회귀가 쌓이는 drift를 사람이 볼 수 있다.
    """
    status = document["status"]
    if status == "ERROR":
        error = document.get("error", {})
        details = ", ".join(f"{key} {error[key]}" for key in SUMMARY_DETAILS if error.get(key) is not None)
        lines = [f"### Relative analysis performance: ERROR (`{error.get('kind', 'unknown')}`)", ""]
        if details:
            lines.append(details + ".")
        return "\n".join(lines) + "\n\n"
    revisions = document.get("revisions") or {}
    identity = [f"runner CPU: {observed_cpu_model(document)}", f"rounds: {document['runs']}"]
    identity += [f"{arm} `{revisions[arm][:12]}`" for arm in ("reference", "candidate") if revisions.get(arm)]
    comparison = document["comparison"]
    lines = [f"### Relative analysis performance: {status}", "", " · ".join(identity), "",
             "| command | candidate (s) | reference (s) | ratio | limit |", "|---|---:|---:|---:|---:|"]
    for name, row in comparison["commands"].items():
        lines.append(f"| {name} | {row['candidateSeconds']:.3f} | {row['referenceSeconds']:.3f} | "
                     f"{row['ratio']:.4f} | {row['limit']:.4f} |")
    lines.append(f"| **total** | {comparison['candidateTotalSeconds']:.3f} | {comparison['referenceTotalSeconds']:.3f} | "
                 f"{comparison['totalRatio']:.4f} | {comparison['totalLimit']:.4f} |")
    recorded = document["recordedBudget"]

    def recorded_total(arm):
        return f"{recorded[arm]['totalSeconds']:.3f} s ({'exceeded' if recorded[arm]['exceeded'] else 'within'})"

    lines += ["", f"Recorded {RECORDED_COMMAND_BUDGET:g} s / {RECORDED_TOTAL_BUDGET:g} s budget (not gating): "
                  f"candidate {recorded_total('candidate')}, reference {recorded_total('reference')}. "
                  f"Safety cap: {document['perCommandBudget']:g} s per command, {document['totalBudget']:g} s total. "
                  f"Reference strict-diagnostic exits: {document['referenceDiagnosticExits']}."]
    failures = document["contractFailures"] + document["budgetFailures"] + document["relativeFailures"]
    if failures:
        lines += ["", "Failures:", *(f"- {failure}" for failure in failures)]
    return "\n".join(lines) + "\n\n"


def append_summary(path, document):
    """job summary 파일에 덧붙인다. 쓰기 실패는 경고로 알리고 이미 정한 판정과 종료 코드는 바꾸지 않는다."""
    try:
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(performance_summary(document))
    except OSError as error:
        print(f"warning: could not append the job summary ({type(error).__name__}); the gate result is unchanged",
              file=sys.stderr)


def main(argv=None):
    parser = Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument(
        "--binary",
        default=None,
        help="path to the candidate kartograph executable",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="candidate repository root whose class roots and configuration are analyzed (defaults to auto-detected root)",
    )
    parser.add_argument(
        "--reference-binary",
        default=None,
        help="compare against this kartograph executable (normally the base commit's build) on the same inputs, "
             "interleaved on this machine; CI acceptance uses this mode",
    )
    parser.add_argument(
        "--per-command-budget",
        type=float,
        default=DEFAULT_COMMAND_CAP,
        help="absolute safety cap: maximum candidate median seconds for each analysis command in both modes "
             f"(default: {DEFAULT_COMMAND_CAP}, twice the recorded {RECORDED_COMMAND_BUDGET}s budget)",
    )
    parser.add_argument(
        "--total-budget",
        type=float,
        default=DEFAULT_TOTAL_CAP,
        help="absolute safety cap: maximum candidate sum of per-command medians in both modes "
             f"(default: {DEFAULT_TOTAL_CAP}, twice the recorded {RECORDED_TOTAL_BUDGET}s budget)",
    )
    parser.add_argument(
        "--total-tolerance",
        type=float,
        default=None,
        help="with --reference-binary: maximum fractional increase of the candidate total over the reference "
             f"(default: {DEFAULT_TOTAL_TOLERANCE})",
    )
    parser.add_argument(
        "--command-tolerance",
        type=float,
        default=None,
        help="with --reference-binary: maximum fractional increase of any single command over the reference "
             f"(default: {DEFAULT_COMMAND_TOLERANCE})",
    )
    parser.add_argument(
        "--reference-revision",
        default=None,
        help="with --reference-binary: full lowercase commit SHA of the reference build, recorded in the result",
    )
    parser.add_argument(
        "--candidate-revision",
        default=None,
        help="with --reference-binary: full lowercase commit SHA of the candidate build, recorded in the result",
    )
    parser.add_argument(
        "--summary",
        default=None,
        help="with --reference-binary: append a Markdown summary of the result to this file "
             "(for example $GITHUB_STEP_SUMMARY)",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=None,
        help="number of measured repetitions; budgets and ratios use per-command medians "
             f"(default: {STANDALONE_RUNS}, or {REFERENCE_RUNS} interleaved rounds with --reference-binary)",
    )
    parser.add_argument(
        "--no-warmup",
        action="store_true",
        help="disable the initial warmup (excluded from measurements; one suite per arm with --reference-binary)",
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

    # nan/inf 예산은 비교를 항상 통과시키므로 유한한 양수만 받는다.
    if any(not math.isfinite(value) or value <= 0 for value in (options.per_command_budget, options.total_budget)):
        print("error: time budgets must be positive numbers", file=sys.stderr)
        return 64

    if options.runs is not None and options.runs < 1:
        print("error: --runs must be at least 1", file=sys.stderr)
        return 64

    tolerances = (options.total_tolerance, options.command_tolerance)
    reference_only = (*tolerances, options.summary, options.reference_revision, options.candidate_revision)
    if options.reference_binary is None and any(value is not None for value in reference_only):
        print("error: --total-tolerance, --command-tolerance, --summary, --reference-revision and "
              "--candidate-revision require --reference-binary", file=sys.stderr)
        return 64
    if any(value is not None and (not math.isfinite(value) or value < 0) for value in tolerances):
        print("error: tolerances must be finite non-negative numbers", file=sys.stderr)
        return 64
    for option, value in (("--reference-revision", options.reference_revision),
                          ("--candidate-revision", options.candidate_revision)):
        if value is not None and not REVISION.fullmatch(value):
            print(f"error: {option} must be a full lowercase commit SHA", file=sys.stderr)
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
    reference_binary = Path(options.reference_binary).resolve() if options.reference_binary is not None else None
    # 같은 실행 파일(symlink·hard link 포함)끼리 비교하면 기준 비교가 아니라 자기 자신과의 비교가 된다.
    if reference_binary is not None and (reference_binary == binary.resolve() or (
            reference_binary.exists() and binary.exists() and os.path.samefile(reference_binary, binary))):
        print("error: --reference-binary must name a different executable from --binary", file=sys.stderr)
        return 64

    if options.json and __name__ == "__main__":
        # 부가 관측 실패는 CLI 진입/종료를 막지 않으며 텍스트 경로는 관측하지 않는다.
        global _CPU_OWNER_PID
        if observe(dedicated_entry).get("value") is True:
            _CPU_OWNER_PID = os.getpid()

    if options.reference_binary is None:
        return verify_self_analysis(
            binary=binary,
            repo_root=repo_root,
            per_command_budget=options.per_command_budget,
            total_budget=options.total_budget,
            warmup=not options.no_warmup,
            as_json=options.json,
            runs=STANDALONE_RUNS if options.runs is None else options.runs,
        )
    return verify_against_reference(
        binary=binary,
        reference_binary=reference_binary,
        repo_root=repo_root,
        per_command_budget=options.per_command_budget,
        total_budget=options.total_budget,
        total_tolerance=DEFAULT_TOTAL_TOLERANCE if options.total_tolerance is None else options.total_tolerance,
        command_tolerance=DEFAULT_COMMAND_TOLERANCE if options.command_tolerance is None else options.command_tolerance,
        warmup=not options.no_warmup,
        as_json=options.json,
        runs=REFERENCE_RUNS if options.runs is None else options.runs,
        revisions={"reference": options.reference_revision, "candidate": options.candidate_revision},
        summary_path=options.summary,
    )


if __name__ == "__main__":
    sys.exit(main())
