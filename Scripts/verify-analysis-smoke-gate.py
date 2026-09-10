#!/usr/bin/env python3
"""자체 컴파일 그래프의 분석 불변식(contracts)과 실행 시간(time budget) smoke 게이트를 검증한다."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


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


def verify_self_analysis(
    binary: Path,
    repo_root: Path,
    per_command_budget: float,
    total_budget: float,
    warmup: bool = True,
    as_json: bool = False,
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

        measurements = {}
        total_time = 0.0
        budget_failures = []
        contract_failures = []

        for name, cmd, contract_kind in commands:
            t0 = time.perf_counter()
            try:
                res = subprocess.run(
                    cmd, capture_output=True, text=True, env=env, timeout=120
                )
            except subprocess.TimeoutExpired:
                print(f"error: command '{name}' timed out", file=sys.stderr)
                return 2

            dt = time.perf_counter() - t0
            total_time += dt
            measurements[name] = {"seconds": round(dt, 3), "returncode": res.returncode}

            if res.returncode == 2:
                print(
                    f"error: command '{name}' failed with tool failure (exit 2)",
                    file=sys.stderr,
                )
                return 2
            elif res.returncode == 64:
                print(
                    f"error: command '{name}' failed with usage error (exit 64)",
                    file=sys.stderr,
                )
                return 64
            elif res.returncode != 0:
                contract_failures.append(
                    f"'{name}' exited with code {res.returncode}, expected 0"
                )
            elif contract_kind == "node_count":
                node_lines = [l for l in res.stdout.splitlines() if 'shape=' in l and ' -> ' not in l]
                measurements[name]["nodes"] = len(node_lines)
                if len(node_lines) < 2000:
                    contract_failures.append(
                        f"'{name}' produced {len(node_lines)} nodes, expected >= 2000"
                    )
            elif contract_kind == "zero_findings":
                findings = [
                    l
                    for l in res.stdout.splitlines()
                    if l.startswith("unreachable\t")
                    or (l and not l.startswith("limitation\t"))
                ]
                measurements[name]["findings"] = len(findings)
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
                measurements[name]["rows"] = len(metric_rows)
                if len(metric_rows) < 6:
                    contract_failures.append(
                        f"'{name}' produced {len(metric_rows)} metric rows, expected >= 6"
                    )

            if dt > per_command_budget:
                budget_failures.append(
                    f"'{name}' took {dt:.3f}s, exceeding budget of {per_command_budget:.3f}s"
                )

        if total_time > total_budget:
            budget_failures.append(
                f"total analysis time was {total_time:.3f}s, exceeding total budget of {total_budget:.3f}s"
            )

        passed = not contract_failures and not budget_failures

        if as_json:
            result_doc = {
                "status": "PASS" if passed else "FAIL",
                "perCommandBudget": per_command_budget,
                "totalBudget": total_budget,
                "totalSeconds": round(total_time, 3),
                "measurements": measurements,
                "contractFailures": contract_failures,
                "budgetFailures": budget_failures,
            }
            print(json.dumps(result_doc, indent=2))
        else:
            if passed:
                print(
                    "[SMOKE GATE PASS] All self-analysis contracts satisfied within performance budget:"
                )
                for name, data in measurements.items():
                    print(
                        f"  - {name:15}: {data['seconds']:.3f}s (budget: {per_command_budget:.3f}s)"
                    )
                print(
                    f"  Total analysis time: {total_time:.3f}s (budget: {total_budget:.3f}s)"
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
        help="maximum allowed time in seconds for each analysis command (default: 5.0)",
    )
    parser.add_argument(
        "--total-budget",
        type=float,
        default=15.0,
        help="maximum allowed total analysis time in seconds (default: 15.0)",
    )
    parser.add_argument(
        "--no-warmup",
        action="store_true",
        help="disable the initial warmup run",
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

    return verify_self_analysis(
        binary=binary,
        repo_root=repo_root,
        per_command_budget=options.per_command_budget,
        total_budget=options.total_budget,
        warmup=not options.no_warmup,
        as_json=options.json,
    )


if __name__ == "__main__":
    sys.exit(main())
