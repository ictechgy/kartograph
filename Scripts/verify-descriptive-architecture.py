#!/usr/bin/env python3
"""고정 architecture 후보를 기술적으로 비교한다. 추론 통계나 제품 게이트를 대체하지 않는다."""

import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import signal
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


HELPER_SHA256 = "dcb9e015b35d33ac122c4c1b1f98edfc3d99f5751d6711d18ac8413e666be92c"
HELPER = Path(__file__).with_name("verify-cli-repeatability.py")
if hashlib.sha256(HELPER.read_bytes()).hexdigest() != HELPER_SHA256:
    raise ValueError("imported A/A capture helper changed before import")
SPEC = importlib.util.spec_from_file_location("repeatability", HELPER)
AA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AA)
PRODUCT_SHA = "13277fbbfd7d26e14a0c5bf7becf826ccb33f6c4"
PRODUCT_TREE = "86ea1e2c50cd3f4ecb4b6cdac7ecd7ab0eecef73"
PATCH_SHA256 = "ce67faf7531f0aed1bc1f6024afe20e76f4fb6f849e460e3cfd8bf549f32a44c"
PLAN_SHA256 = "d585c8231d7f445ba6e2fbeaa51d444fc3f5d411500ddc20d3ddffb4fa3ac40a"
CHANGED_FILES = [
    {"path": "cli/src/main/kotlin/dev/kartograph/cli/ArchitectureCommand.kt",
     "sha256": "5c136882adf9d44aaa50c29b6922c24eb983dc125948c744ad10b2487ca1f0a2", "bytes": 9543},
    {"path": "cli/src/test/kotlin/dev/kartograph/cli/ArchitectureDispatchSelectionTest.kt",
     "sha256": "6d2358ec8c983e164a8ad7b187037a8a27379c5fe56055e9061ee754da35ef46", "bytes": 30189},
    {"path": "cli/src/test/kotlin/dev/kartograph/cli/FullIndexArchitectureCommandOracle.kt",
     "sha256": "d280d26be1e1b17fbabe039b8ad24439bd50492c30ca5b873db0d456f4b8e8fa", "bytes": 9126},
]
REQUIRED_TESTS = (
    "javac cross package interface and subclass fanout preserves authored dependencies",
    "acyclic javac fanout retains strict success",
    "constant reflection reference retains real cycle rule findings and metric rows",
    "unresolved reflection observations survive without new architecture warnings",
    "rules preserve unassigned allowed denied and exact explanation outcomes",
    "directory jar and duplicate root precedence retain exact old results",
    "missing empty truncated jar service and yaml failures preserve status and bytes",
    "help usage and invalid paths preserve old parser exits",
    "old indexing catch still encloses action but never swallows unrelated public exceptions",
    "architecture leaves later default cold warm graph capture and dead dispatch intact",
    "explicit dispatch selection preserves Kotlin metadata and bridge witnesses",
)
NAMES = AA.NAMES
UNCHANGED = ("graph", "dead", "dead_private")
PAIRS = ("AB", "BA", "BA", "AB", "AB", "BA", "BA", "AB", "AB", "BA", "BA", "AB")
SMALL_PAIRS = ("AB", "BA", "AB")
CLI_JAR = "lib/cli-0.21.0.jar"
RSS_LIMIT_KIB = 1.2 * 1024 * 1024
MAIN_GROUPS = ("early", "late", "AB", "BA")
HARNESS_PATHS = (
    "Scripts/verify-descriptive-architecture.py", "Scripts/tests/test_descriptive_architecture.py",
    "Scripts/verify-cli-repeatability.py", ".github/workflows/descriptive-architecture.yml",
    "experiments/descriptive-architecture/README.md",
    "experiments/descriptive-architecture/candidate.patch",
    "experiments/descriptive-architecture/candidate-source.json",
)


def schedule():
    """주 일정 뒤에 두 작은 블록을 붙이며 라벨의 의미를 고정한다."""
    result = []
    for block, pairs in (("main", PAIRS), ("small_1", SMALL_PAIRS), ("small_17", SMALL_PAIRS)):
        for label in "AB":
            result.append(dict(block=block, pair=None, label=label, warmup=True, suite=len(result)))
        for pair, order in enumerate(pairs):
            for label in order:
                result.append(dict(block=block, pair=pair, label=label, warmup=False, suite=len(result)))
    return result


def expected_rows():
    return [dict(suite, command=name) for suite in schedule()
            for name in (NAMES if suite["block"] == "main" else (suite["block"],))]


def number(value, *, positive=False):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0 or (positive and value == 0):
        raise ValueError("finite numeric observation required")
    return value


def describe(values):
    return {"samples": values, "median": statistics.median(values), "range": [min(values), max(values)]}


def groups():
    result = {"all": list(range(12)), "early": list(range(6)), "late": list(range(6, 12))}
    result.update({order: [i for i, value in enumerate(PAIRS) if value == order] for order in ("AB", "BA")})
    result.update({half + "_" + order: [i for i in result[half] if i in result[order]]
                   for half in ("early", "late") for order in ("AB", "BA")})
    return result


def paired_summary(a, b):
    """모든 원시 쌍과 사전 지정 겹치는 그룹의 기하평균 비율을 보존한다."""
    if len(a) != 12 or len(b) != 12:
        raise ValueError("twelve paired observations required")
    for value in a + b:
        number(value, positive=True)
    ratios = [right / left for left, right in zip(a, b)]
    differences = [2 * (right - left) / (left + right) for left, right in zip(a, b)]
    return {"A": a, "B": b, "paired_ratios": ratios, "signed_symmetric_differences": differences,
            "groups": {name: {"pairs": indices,
                               "geometric_mean_ratio": statistics.geometric_mean([ratios[i] for i in indices]),
                               "A": describe([a[i] for i in indices]), "B": describe([b[i] for i in indices]),
                               "ratios": describe([ratios[i] for i in indices])}
                       for name, indices in groups().items()}}


def classify(mandatory, predicates):
    if not all(mandatory.values()):
        return "rejected"
    return "continue-to-exact-head-validation" if all(predicates.values()) else "inconclusive-for-release-headroom-justification"


def grade(rows):
    """완전한 고정 캡처만 판정하며 메모리 거부와 기술적 불충분을 구분한다."""
    expected = expected_rows()
    if len(rows) != len(expected):
        raise ValueError("all 172 scheduled CLI rows are required")
    for row, identity in zip(rows, expected):
        if any(type(row.get(key)) is not type(value) or row.get(key) != value for key, value in identity.items()):
            raise ValueError("fixed schedule changed")
        if type(row.get("returncode")) is not int or row["returncode"] != 0 \
                or type(row.get("contract_failures")) is not list or row["contract_failures"]:
            raise ValueError("all command contracts must pass")
        for key in ("seconds", "max_rss_kib", "user_seconds", "system_seconds"):
            number(row.get(key), positive=key in ("seconds", "max_rss_kib"))
        for stream, cap in zip(("stdout", "stderr"), AA.STREAM_LIMITS):
            size, sha = row.get(stream + "_bytes"), row.get(stream + "_sha256")
            if type(size) is not int or not 0 <= size <= cap or type(sha) is not str \
                    or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha) \
                    or row.get(stream + "_overflow") is not False:
                raise ValueError("complete bounded stream capture required")
        if any(key in row for key in ("error", "cleanup_error", "stdout_capture_error", "stderr_capture_error")):
            raise ValueError("capture failure cannot be graded")
    for name in (*NAMES, "small_1", "small_17"):
        selected = [r for r in rows if r["command"] == name]
        if len({(r["stdout_sha256"], r["stderr_sha256"]) for r in selected}) != 1:
            raise ValueError("raw output parity failed")
    timed = [r for r in rows if not r["warmup"]]
    summaries, diagnostics = {}, {}
    for name in (*NAMES, "suite_total"):
        samples = {metric: {label: [] for label in "AB"}
                   for metric in ("seconds", "user_seconds", "system_seconds", "max_rss_kib")}
        for pair in range(12):
            for label in "AB":
                selected = [r for r in timed if r["block"] == "main" and r["pair"] == pair
                            and r["label"] == label and (name == "suite_total" or r["command"] == name)]
                for metric in samples:
                    # RSS 합은 의미가 없으므로 suite의 직접 프로세스 최대값만 기술한다.
                    values = [r[metric] for r in selected]
                    samples[metric][label].append(max(values) if metric == "max_rss_kib" else sum(values))
        summaries[name] = paired_summary(samples["seconds"]["A"], samples["seconds"]["B"])
        diagnostics[name] = {group: {metric: {label: describe([values[label][i] for i in indices])
                                             for label in "AB"}
                                    for metric, values in samples.items() if metric != "seconds"}
                             for group, indices in groups().items()}
    mandatory = {"all_invocations_rss_at_most_1_2_gib": max(r["max_rss_kib"] for r in rows) <= RSS_LIMIT_KIB}
    resources, small, predicates = {}, {}, {}
    for name in (*NAMES, "small_1", "small_17"):
        by_label = {label: [r for r in timed if r["command"] == name and r["label"] == label] for label in "AB"}
        resources[name] = {label: describe([r["max_rss_kib"] for r in by_label[label]]) for label in "AB"}
        mandatory[name + ":median_rss_at_most_1_25"] = resources[name]["B"]["median"] <= 1.25 * resources[name]["A"]["median"]
        if name.startswith("small_"):
            small[name] = {label: {metric: describe([r[metric] for r in by_label[label]])
                                   for metric in ("seconds", "user_seconds", "system_seconds", "max_rss_kib")}
                           for label in "AB"}
            predicates[name + ":wall_at_most_1_20_plus_20ms"] = small[name]["B"]["seconds"]["median"] <= 1.2 * small[name]["A"]["seconds"]["median"] + .020
    for name, summary in summaries.items():
        ratios = {group: value["geometric_mean_ratio"] for group, value in summary["groups"].items()}
        if name == "suite_total":
            predicates[name + ":all_at_most_0_90"] = ratios["all"] <= .90
            predicates.update({name + ":" + group + "_below_1_00": ratios[group] < 1.0 for group in MAIN_GROUPS})
        else:
            predicates[name + ":all_at_most_1_05"] = ratios["all"] <= 1.05
            predicates.update({name + ":" + group + "_at_most_1_10": ratios[group] <= 1.10 for group in MAIN_GROUPS})
            if name in UNCHANGED:
                predicates[name + ":unchanged_all_within_0_95_1_05"] = .95 <= ratios["all"] <= 1.05
                predicates.update({name + ":unchanged_" + group + "_within_0_90_1_10": .90 <= ratios[group] <= 1.10
                                   for group in MAIN_GROUPS})
    return {"statistics": summaries, "cpu_and_rss": diagnostics, "rss": resources, "small": small,
            "mandatory_checks": mandatory, "continuation_predicates": predicates,
            "failed_mandatory_checks": [k for k, v in mandatory.items() if not v],
            "unmet_continuation_predicates": [k for k, v in predicates.items() if not v],
            "status": classify(mandatory, predicates)}


def git(repo, *args):
    env = {key: os.environ[key] for key in AA.ENV_KEYS if key in os.environ}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
    return subprocess.check_output(["git", "-c", "core.fsmonitor=false", *args], cwd=repo, env=env, timeout=10)


def file_identity(root, paths):
    """추적 symlink는 따라가지 않고 원문 링크를 기록한다."""
    rows = []
    for path in sorted(paths):
        if path.is_symlink():
            data = os.readlink(path).encode()
            rows.append({"path": str(path.relative_to(root)), "bytes": len(data),
                         "sha256": hashlib.sha256(data).hexdigest(), "kind": "symlink"})
        else:
            rows.extend(dict(row, mode=path.stat().st_mode & 0o777) for row in AA.file_rows(root, [path]))
    return rows


def tree_identity(root):
    if not root.is_dir() or root.is_symlink():
        raise ValueError("missing or linked input directory")
    paths = list(root.rglob("*"))
    if any(path.is_symlink() or not (path.is_file() or path.is_dir()) for path in paths):
        raise ValueError("linked or special input member")
    return {"files": file_identity(root, [p for p in paths if p.is_file()]),
            "directories": sorted(str(p.relative_to(root)) for p in paths if p.is_dir())}


def source_identity(repo):
    paths = git(repo, "ls-files", "-z").decode().split("\0")[:-1]
    changes = git(repo, "diff", "--cached", "--no-renames", "--name-status", "-z", "HEAD", "--").decode().split("\0")[:-1]
    return {"head": git(repo, "rev-parse", "HEAD").decode().strip(),
            "tree": git(repo, "rev-parse", "HEAD^{tree}").decode().strip(),
            "staged": list(zip(changes[::2], changes[1::2])),
            "index_sha256": hashlib.sha256(git(repo, "ls-files", "--stage", "-z")).hexdigest(),
            "unstaged": git(repo, "diff", "--no-ext-diff", "--name-only", "-z", "--").decode().split("\0")[:-1],
            "untracked": git(repo, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0")[:-1],
            "files": file_identity(repo, [repo / name for name in paths])}


def validate_sources(sources):
    expected = [("M" if i == 0 else "A", row["path"]) for i, row in enumerate(CHANGED_FILES)]
    for label in "AB":
        source = sources[label]
        if source["head"] != PRODUCT_SHA or source["tree"] != PRODUCT_TREE or source["unstaged"] or source["untracked"]:
            raise ValueError("source revision or working tree changed")
        if source["staged"] != ([] if label == "A" else expected):
            raise ValueError("candidate staged patch scope changed")
    maps = {label: {r["path"]: r for r in sources[label]["files"]} for label in "AB"}
    changed = {r["path"] for r in CHANGED_FILES}
    if {k: v for k, v in maps["A"].items() if k not in changed} != {k: v for k, v in maps["B"].items() if k not in changed}:
        raise ValueError("noncandidate tracked source differs")
    for row in CHANGED_FILES:
        if maps["B"].get(row["path"]) != dict(row, mode=0o644):
            raise ValueError("candidate body or mode is not the reviewed patch")


def validate_installs(installs):
    a, b = installs["A"], installs["B"]
    maps = {label: {r["path"]: r for r in installs[label]["files"]} for label in "AB"}
    if a["directories"] != b["directories"] or maps["A"].keys() != maps["B"].keys():
        raise ValueError("installation membership differs")
    differences = [name for name in maps["A"] if maps["A"][name] != maps["B"][name]]
    if differences != [CLI_JAR] or maps["A"][CLI_JAR]["mode"] != maps["B"][CLI_JAR]["mode"]:
        raise ValueError("only the reviewed CLI JAR may differ")
    launcher = maps["A"].get("bin/kartograph")
    if not launcher or not launcher["mode"] & 0o111:
        raise ValueError("missing executable CLI launcher")
    return differences


def validate_roots(baseline, candidate, output):
    for left, right in ((baseline, candidate), (baseline, output), (candidate, output)):
        if left.is_relative_to(right) or right.is_relative_to(left):
            raise ValueError("baseline, candidate and output must be disjoint paths")
    if not baseline.is_dir() or not candidate.is_dir():
        raise ValueError("both product checkouts must exist")


def validate_binding(harness):
    if AA.digest(HELPER) != HELPER_SHA256:
        raise ValueError("imported A/A capture helper changed")
    directory = harness / "experiments/descriptive-architecture"
    manifest = json.loads((directory / "candidate-source.json").read_text())
    expected = {"product_sha": PRODUCT_SHA, "product_tree": PRODUCT_TREE, "patch": "candidate.patch",
                "patch_sha256": PATCH_SHA256, "reviewed_plan_sha256": PLAN_SHA256,
                "changed_files": CHANGED_FILES, "unchanged_controls": list(UNCHANGED),
                "changed_commands": ["cycles", "rules", "metrics"]}
    if any(manifest.get(key) != value for key, value in expected.items()) \
            or AA.digest(directory / "candidate.patch") != PATCH_SHA256:
        raise ValueError("fixed candidate patch or declaration changed")


def correctness_receipts(candidate):
    """실제 Gradle XML의 성공·skip 수와 필수 11개 테스트를 타이머 전에 확인한다."""
    result, required = {}, []
    for module in ("index", "cli"):
        root = candidate / module / "build/test-results/test"
        paths = sorted(root.glob("*.xml"))
        if not paths or any(p.is_symlink() or p.stat().st_size > 8 * 1024 * 1024 for p in paths):
            raise ValueError("missing, linked or oversized correctness XML")
        totals = dict(tests=0, skipped=0, failures=0, errors=0)
        for path in paths:
            suite = ET.parse(path).getroot()
            if suite.tag != "testsuite":
                raise ValueError("unexpected correctness XML root")
            counts = {key: int(suite.attrib[key]) for key in totals}
            cases = suite.findall("testcase")
            observed = {"tests": len(cases), "skipped": sum(c.find("skipped") is not None for c in cases),
                        "failures": sum(c.find("failure") is not None for c in cases),
                        "errors": sum(c.find("error") is not None for c in cases)}
            if counts != observed or counts["failures"] or counts["errors"]:
                raise ValueError("failed or inconsistent correctness XML")
            for key in totals:
                totals[key] += counts[key]
            for case in cases:
                if case.get("classname") == "dev.kartograph.cli.ArchitectureDispatchSelectionTest":
                    if module != "cli" or case.find("skipped") is not None:
                        raise ValueError("required architecture correctness test skipped or misplaced")
                    required.append(case.get("name"))
        if totals["tests"] <= totals["skipped"]:
            raise ValueError("no successful correctness tests")
        result[module] = {"totals": totals, "xml": AA.file_rows(root, paths)}
    if sorted(required) != sorted(name + "(Path)" for name in REQUIRED_TESTS):
        raise ValueError("all eleven exact architecture correctness cases must pass once")
    result["required_architecture_cases"] = sorted(required)
    return result


def snapshot(baseline, candidate, fixture, java_home):
    corpus = {module: tree_identity(baseline / module / "build/classes/kotlin/main") for module in AA.MODULES}
    if any(not any(r["path"].endswith(".class") for r in root["files"]) for root in corpus.values()):
        raise ValueError("empty common corpus root")
    return {"sources": {label: source_identity(root) for label, root in (("A", baseline), ("B", candidate))},
            "installs": {label: tree_identity(root / "cli/build/install/kartograph")
                         for label, root in (("A", baseline), ("B", candidate))},
            "common_corpus": corpus, "fixture": tree_identity(fixture),
            "correctness": correctness_receipts(candidate),
            "jdk": AA.file_rows(java_home, [java_home / name for name in ("release", "bin/java", "bin/javac", "lib/server/libjvm.so")])}


def fixture_setup(fixture, java_home, capture, row):
    """기존 pilot의 한 번짜리 javac 준비를 측정 전에 수행한다."""
    (fixture / "res").mkdir(parents=True)
    (fixture / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
        '    <application/>\n</manifest>\n')
    sources, all_classes = fixture / "src", fixture / "all-classes"
    sources.mkdir()
    all_classes.mkdir()
    for n in range(17):
        name = f"Small{n:02d}"
        (sources / (name + ".java")).write_text(
            f'package small; public final class {name} {{ public {name}() {{}} public String value() {{ return "value-{n}"; }} public static {name} make() {{ return new {name}(); }} }}\n')
    capture.run([str(java_home / "bin/javac"), "-J-Xmx512m", "-d", str(all_classes),
                 *[str(p) for p in sorted(sources.glob("*.java"))]], "fixture-javac", row,
                timeout=60, limits=(1024 * 1024,) * 2)
    expected = [f"small/Small{n:02d}.class" for n in range(17)]
    if sorted(r["path"] for r in tree_identity(all_classes)["files"]) != expected:
        raise ValueError("fixture compilation membership changed")
    for count in (1, 17):
        target = fixture / f"classes-{count}" / "small"
        target.mkdir(parents=True)
        for n in range(count):
            name = f"Small{n:02d}.class"
            (target / name).write_bytes((all_classes / "small" / name).read_bytes())


def commands(baseline, candidate, fixture):
    common = AA.commands(baseline, fixture)
    result = {}
    for label, root in (("A", baseline), ("B", candidate)):
        binary = str(root / "cli/build/install/kartograph/bin/kartograph")
        result[label] = {name: ([binary, *argv[1:]], contract) for name, argv, contract in common}
        for count in (1, 17):
            result[label][f"small_{count}"] = ([binary, "graph", "--classes", str(fixture / f"classes-{count}"), "--format", "dot"], count * 4)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline-root", "candidate-root", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args(argv)
    baseline, candidate, output = args.baseline_root.resolve(), args.candidate_root.resolve(), args.output.resolve()
    validate_roots(baseline, candidate, output)
    output.mkdir(parents=True, exist_ok=False)
    harness = Path(__file__).resolve().parents[1]
    result = {"scope": "Fixed descriptive architecture assessment; no population, confidence, independence or release-headroom guarantee",
              "status": "incomplete", "product_sha": PRODUCT_SHA, "patch_sha256": PATCH_SHA256,
              "unchanged_controls": UNCHANGED, "schedule": schedule(), "rows": [], "suites": [], "metadata": [], "setup": []}
    capture, primary, bracket_started = None, None, False
    fixture = output / "fixture"
    java_home = None
    def save():
        (output / "result.json").write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    def safe_save():
        active = sys.exc_info()[1]
        try:
            save()
        except BaseException as error:
            if active is None:
                raise
            result.setdefault("secondary_receipt_errors", []).append(type(error).__name__)
            print("secondary receipt failure: " + type(error).__name__, file=sys.stderr)
    def terminate(signum, frame):
        raise KeyboardInterrupt("termination requested")
    old_handler = signal.signal(signal.SIGTERM, terminate)
    try:
        validate_binding(harness)
        result["harness_before"] = AA.file_rows(harness, [harness / p for p in HARNESS_PATHS])
        # 잘못된 후보를 컴파일러나 JVM 실행 전에 거부한다.
        validate_sources({"A": source_identity(baseline), "B": source_identity(candidate)})
        result["correctness_admission"] = correctness_receipts(candidate)
        env = AA.cli_environment(os.environ, output / "tmp")
        java_home = Path(env["JAVA_HOME"]).resolve()
        (output / "tmp").mkdir()
        result["environment"] = env
        result["runner"] = {key: os.environ.get(key) for key in (
            "RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion", "GITHUB_WORKFLOW_REF", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")}
        result["host"] = {"uname": list(os.uname()), "logical_cpus": os.cpu_count(),
                          "cpu_model": next((line for line in str(AA.read_small(Path("/proc/cpuinfo"))).splitlines()
                                             if line.startswith("model name")), "unavailable")}
        capture = AA.Capture(output, baseline, env, time.monotonic() + 900)
        setup_row = {"kind": "fixture-javac", "timed_cli_sample": False}
        result["setup"].append(setup_row)
        try:
            fixture_setup(fixture, java_home, capture, setup_row)
        finally:
            safe_save()
        result["before"] = snapshot(baseline, candidate, fixture, java_home)
        bracket_started = True
        if result["correctness_admission"] != result["before"]["correctness"]:
            raise ValueError("correctness receipts changed during fixture setup")
        validate_sources(result["before"]["sources"])
        result["install_differences"] = validate_installs(result["before"]["installs"])
        policy = AA.resources()["policy"]
        result["resource_policy"] = policy
        declared = commands(baseline, candidate, fixture)
        result["commands"] = declared
        spec = importlib.util.spec_from_file_location("smoke", baseline / "Scripts/verify-analysis-smoke-gate.py")
        smoke = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(smoke)
        references = {}
        def metadata(endpoint):
            for kind, flags in (("flags", ["-XX:+PrintFlagsFinal"]), ("system", ["-XshowSettings:system"])):
                row = {"endpoint": endpoint, "kind": kind, "timed_cli_sample": False}
                result["metadata"].append(row)
                try:
                    capture.run([str(java_home / "bin/java"), *flags, "-version"], endpoint + "-" + kind,
                                row, timeout=10, limits=(1024 * 1024,) * 2)
                finally:
                    safe_save()
        metadata("before")
        for suite in schedule():
            suite_start = time.monotonic()
            suite_row, suite_rows = dict(suite), []
            result["suites"].append(suite_row)
            for name in NAMES if suite["block"] == "main" else (suite["block"],):
                command, contract = declared[suite["label"]][name]
                row = dict(suite, command=name)
                result["rows"].append(row)
                suite_rows.append(row)
                try:
                    row["resources_before"] = AA.resources()
                    if row["resources_before"]["policy"] != policy:
                        raise ValueError("resource policy changed")
                    stdout, stderr = capture.run(command, f"suite-{suite['suite']:02d}-{suite['label']}-{name}", row)
                    row["resources_after"] = AA.resources()
                    if row["resources_after"]["policy"] != policy:
                        raise ValueError("resource policy changed")
                    if row["max_rss_kib"] > RSS_LIMIT_KIB:
                        raise ValueError("invocation exceeded 1.2 GiB RSS bound")
                    identity = (row["stdout_sha256"], row["stderr_sha256"])
                    if identity != references.setdefault(name, identity):
                        raise ValueError("raw output parity failed: " + name)
                    observation, failures = {}, []
                    if type(contract) is int:
                        observation["nodes"] = sum("shape=" in line and " -> " not in line for line in stdout.read_text().splitlines())
                        if observation["nodes"] != contract:
                            failures.append("small authored declaration count differs")
                    else:
                        smoke.check_contract(name, contract, subprocess.CompletedProcess(command, row["returncode"],
                                             stdout.read_text(), stderr.read_text()), observation, failures)
                    row.update(contract=observation, contract_failures=failures)
                    if failures:
                        raise ValueError("semantic contract failed: " + name)
                finally:
                    safe_save()
            suite_row["sum_command_seconds"] = sum(row["seconds"] for row in suite_rows)
            suite_row["enclosing_seconds"] = time.monotonic() - suite_start
            save()
        metadata("after")
        if AA.resources()["policy"] != policy:
            raise ValueError("resource policy changed")
        capture.remaining()
    except BaseException as error:
        primary = error
        result["error"] = type(error).__name__ + ": " + str(error)
    finally:
        # 실패 뒤에도 추가 workload 없이 읽기 전용 끝점을 남기며 첫 실패를 보존한다.
        if capture and capture.unreaped:
            result["endpoint_skipped"] = "owned child was not reaped; product inputs must not be read concurrently"
            if primary is None:
                primary = RuntimeError("owned child cleanup incomplete")
                result["error"] = str(primary)
        try:
            if bracket_started and not capture.unreaped:
                result["after"] = snapshot(baseline, candidate, fixture, java_home)
                validate_sources(result["after"]["sources"])
                validate_installs(result["after"]["installs"])
                if result["before"] != result["after"]:
                    raise ValueError("source, installation, corpus, fixture or JDK identity changed")
            result["harness_after"] = AA.file_rows(harness, [harness / p for p in HARNESS_PATHS])
            validate_binding(harness)
            if result.get("harness_before") != result["harness_after"]:
                raise ValueError("harness identity changed")
            if primary is None:
                capture.remaining()
                result["score"] = grade(result["rows"])
                capture.remaining()
                result["status"] = result["score"]["status"]
        except BaseException as error:
            if primary is None:
                primary = error
                result["error"] = type(error).__name__ + ": " + str(error)
            else:
                result["endpoint_error"] = type(error).__name__ + ": " + str(error)
        if primary is not None:
            interrupted = isinstance(primary, (TimeoutError, subprocess.TimeoutExpired, KeyboardInterrupt))
            result["status"] = "incomplete" if interrupted or (capture and capture.unreaped) else "rejected"
        result["owned_child_unreaped"] = bool(capture and capture.unreaped)
        result["finished_utc_ns"] = time.time_ns()
        receipt_saved = False
        try:
            save()
            receipt_saved = True
        except BaseException as error:
            print("terminal receipt failure: " + type(error).__name__, file=sys.stderr)
        finally:
            signal.signal(signal.SIGTERM, old_handler)
    if not receipt_saved:
        return 2
    print(json.dumps({"status": result["status"], "commands": len(result["rows"]),
                      "result_sha256": AA.digest(output / "result.json")}))
    return 0 if result["status"] == "continue-to-exact-head-validation" else (2 if result["status"] in ("rejected", "incomplete") else 1)


if __name__ == "__main__":
    sys.exit(main())
