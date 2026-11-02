"""자체 컴파일 그래프의 분석 불변식 및 시간 smoke 게이트 스크립트를 검증한다."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "Scripts/verify-analysis-smoke-gate.py"
BINARY = ROOT / "cli/build/install/kartograph/bin/kartograph"


def run_gate_process(*args):
    """게이트 스크립트를 실제 하위 프로세스로 실행한다. JAVA_HOME이 없으면 알려진 JDK 경로를 찾는다."""
    env = dict(os.environ)
    if "JAVA_HOME" not in env or not (Path(env["JAVA_HOME"]) / "bin/java").is_file():
        for c in [
            Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"),
            Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
            Path("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
        ]:
            if (c / "bin/java").is_file():
                env["JAVA_HOME"] = str(c)
                env["PATH"] = f"{c}/bin:{env.get('PATH', '')}"
                break

    cmd = [sys.executable, str(SCRIPT), *args]
    return subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=120)


class AnalysisSmokeGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not BINARY.is_file():
            subprocess.run(
                [str(ROOT / "gradlew"), "--no-daemon", ":cli:installDist", ":gradle-plugin:classes"],
                cwd=ROOT,
                check=True,
                capture_output=True,
            )

    def run_gate(self, *args):
        return run_gate_process(*args)

    def test_smoke_gate_passes_on_production_classes(self):
        # 출력 형식·계약 확인용이다. 기본 예산 판정(중앙값 3회)은 JSON 테스트와 CI 게이트 단계가 맡으므로
        # 여기서는 1회만 돌리고 예산을 넉넉히 줘 단일 표본 흔들림으로 실패하지 않게 한다.
        res = self.run_gate("--runs", "1", "--per-command-budget", "60", "--total-budget", "120")
        self.assertEqual(
            res.returncode,
            0,
            f"expected gate to pass, got exit {res.returncode}\nstdout: {res.stdout}\nstderr: {res.stderr}",
        )
        self.assertIn("[SMOKE GATE PASS]", res.stdout)
        self.assertIn("(median of 1 runs)", res.stdout)
        self.assertIn("Total analysis time:", res.stdout)

    def test_smoke_gate_json_output(self):
        # 단독 모드의 기본 예산은 절대 안전 상한(10초/40초)이다. 예전 5초/20초 판정은 같은 tree도 runner CPU
        # 종류에 따라 갈렸으므로 이 단계에서 다시 판정하지 않고, 판정 없는 recordedBudget 기록으로만 확인한다.
        res = self.run_gate("--json")
        self.assertEqual(res.returncode, 0, f"stdout: {res.stdout}\nstderr: {res.stderr}")
        data = json.loads(res.stdout)
        self.assertEqual(data["status"], "PASS")
        self.assertEqual(data["mode"], "standalone")
        # 저장소가 커져도 DOT 간선을 정점으로 잘못 세지 않는지 독립 JSON 출력과 비교한다.
        self.assertGreaterEqual(data["measurements"]["graph"]["nodes"], 2000)
        command = [str(BINARY), "graph", "--format", "json"]
        for module in ("core", "index", "analysis", "export", "cli", "gradle-plugin"):
            command.extend(["--classes", str(ROOT / module / "build/classes/kotlin/main")])
        graph = subprocess.run(command, capture_output=True, text=True, timeout=60, check=True)
        self.assertEqual(data["measurements"]["graph"]["nodes"], len(json.loads(graph.stdout)["nodes"]))
        self.assertEqual(data["measurements"]["dead"]["findings"], 0)
        self.assertEqual(data["measurements"]["dead_private"]["findings"], 0)
        self.assertGreaterEqual(data["measurements"]["metrics"]["rows"], 6)
        self.assertLessEqual(data["totalSeconds"], data["totalBudget"])
        # 기본은 3회 반복이며 seconds는 명령별 중앙값, 총합은 중앙값의 합이다.
        self.assertEqual(data["runs"], 3)
        self.assertEqual(data["totalBudget"], 40.0)
        self.assertEqual(data["perCommandBudget"], 10.0)
        self.assertEqual(data["budgetFailures"], [])
        recorded = data["recordedBudget"]
        self.assertEqual(recorded["scope"], "observation-only; never-used-for-gate-acceptance")
        self.assertEqual((recorded["perCommandBudget"], recorded["totalBudget"]), (5.0, 20.0))
        self.assertEqual(recorded["candidate"]["totalSeconds"], data["totalSeconds"])
        self.assertIsInstance(recorded["candidate"]["exceeded"], list)
        self.assertEqual(len(data["runTotals"]), 3)
        for name, measurement in data["measurements"].items():
            self.assertEqual(len(measurement["samples"]), 3, name)
            self.assertEqual(measurement["seconds"], sorted(measurement["samples"])[1], name)
        # totalSeconds는 원시 중앙값의 합이라 명령별 반올림 오차(최대 0.5ms x 6)만큼 차이를 허용한다.
        self.assertAlmostEqual(
            data["totalSeconds"],
            sum(m["seconds"] for m in data["measurements"].values()),
            delta=0.005,
        )

    def test_smoke_gate_fails_when_budget_exceeded(self):
        res = self.run_gate("--runs", "1", "--per-command-budget", "0.001")
        self.assertEqual(res.returncode, 1)
        self.assertIn("budget failures:", res.stderr)
        self.assertIn("(median of 1 runs), exceeding budget of 0.001s", res.stderr)

    def test_smoke_gate_rejects_non_positive_runs(self):
        res = self.run_gate("--runs", "0")
        self.assertEqual(res.returncode, 64)
        self.assertIn("--runs must be at least 1", res.stderr)

    def load_module(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("smoke_gate", SCRIPT)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_summarize_uses_median_so_single_spike_is_ignored(self):
        mod = self.load_module()
        samples = {"graph": [2.0, 9.0, 2.2], "dead": [1.0, 1.1, 1.2]}
        counters = {
            "graph": [{"returncode": 0, "nodes": 2100}] * 3,
            "dead": [{"returncode": 0, "findings": 0}] * 3,
        }
        failures = []
        measurements = mod.summarize_measurements(samples, counters, failures)
        self.assertEqual(measurements["graph"]["seconds"], 2.2)
        self.assertEqual(measurements["graph"]["samples"], [2.0, 9.0, 2.2])
        self.assertEqual(measurements["graph"]["nodes"], 2100)
        self.assertEqual(measurements["dead"]["seconds"], 1.1)
        self.assertEqual(failures, [])

    def test_summarize_flags_counts_that_vary_across_runs(self):
        mod = self.load_module()
        samples = {"graph": [1.0, 1.0, 1.0]}
        counters = {"graph": [{"returncode": 0, "nodes": n} for n in (2100, 2100, 2099)]}
        failures = []
        mod.summarize_measurements(samples, counters, failures)
        self.assertEqual(failures, ["'graph' nodes varied across runs: [2100, 2100, 2099]"])

    def test_known_input_hints_preserve_incomplete_analysis_without_findings(self):
        mod = self.load_module()
        hints = list(mod.SELF_ANALYSIS_INPUT_HINTS)
        limitation = "limitation\tDYNAMIC_REGISTRATION\tmanifest-absent runtime component registration is not resolved"
        result = subprocess.CompletedProcess([], 0, "\n".join(hints + [limitation]) + "\n", "")
        measurement = {"returncode": 0}
        failures = []
        mod.check_contract("dead", "zero_findings", result, measurement, failures)
        self.assertEqual(failures, [])
        self.assertEqual(measurement["findings"], 0)
        self.assertEqual(measurement["inputHints"], hints)
        self.assertEqual(measurement["limitations"], [limitation])
        summary = mod.summarize_measurements({"dead": [1.0]}, {"dead": [measurement]}, failures)
        self.assertEqual(summary["dead"]["inputHints"], hints)
        self.assertEqual(summary["dead"]["limitations"], [limitation])

    def test_input_hints_cannot_hide_findings_unknown_output_or_failure(self):
        mod = self.load_module()
        hints = "\n".join(mod.SELF_ANALYSIS_INPUT_HINTS) + "\n"
        for extra in ("unreachable\tjvm://fixture.Example#unused()V\n",
                      "input-hint\tunknown-kind\tnew evidence\n",
                      "input-hint\tmissing-classpath\tchanged message\n",
                      "input-hint\tmissing-classpath\n", "unexpected output\n"):
            with self.subTest(extra=extra):
                failures, measurement = [], {}
                mod.check_contract("dead", "zero_findings", subprocess.CompletedProcess([], 0, hints + extra, ""),
                                   measurement, failures)
                self.assertEqual(measurement["findings"], 1)
                self.assertEqual(len(failures), 1)
        for code, output in ((2, hints), (0, hints + mod.SELF_ANALYSIS_INPUT_HINTS[0] + "\n")):
            failures = []
            mod.check_contract("dead", "zero_findings", subprocess.CompletedProcess([], code, output, ""), {}, failures)
            self.assertEqual(len(failures), 1)
        failures = []
        mod.summarize_measurements({"dead": [1.0, 1.0]}, {"dead": [
            {"returncode": 0, "findings": 0, "inputHints": list(mod.SELF_ANALYSIS_INPUT_HINTS)},
            {"returncode": 0, "findings": 0, "inputHints": []}]}, failures)
        self.assertEqual(failures, ["'dead' inputHints varied across runs"])

    def test_smoke_gate_fails_on_missing_classes(self):
        with tempfile.TemporaryDirectory(prefix="kartograph-empty-repo-") as tmpdir:
            res = self.run_gate("--repo-root", tmpdir, "--binary", str(BINARY))
            self.assertEqual(res.returncode, 2)
            self.assertIn("missing compiled class root", res.stderr)

    def test_smoke_gate_preserves_cli_tool_failure_exit_code(self):
        with tempfile.TemporaryDirectory(prefix="kartograph-mock-bin-") as tmpdir:
            mock_bin = Path(tmpdir) / "kartograph"
            mock_bin.write_text("#!/bin/sh\necho 'error: sensitive path /private/secret' >&2\nexit 2\n")
            mock_bin.chmod(0o755)
            res = self.run_gate("--binary", str(mock_bin), "--no-warmup")
            self.assertEqual(res.returncode, 2)
            self.assertIn("failed with tool failure (exit 2)", res.stderr)
            self.assertNotIn("/private/secret", res.stderr)

    def test_smoke_gate_checks_contracts_on_every_run_without_duplicates(self):
        # 계약 위반은 매 반복 검사하되, 같은 문구는 한 번만 보고한다.
        with tempfile.TemporaryDirectory(prefix="kartograph-mock-bin-") as tmpdir:
            mock_bin = Path(tmpdir) / "kartograph"
            mock_bin.write_text("#!/bin/sh\nexit 1\n")
            mock_bin.chmod(0o755)
            res = self.run_gate("--binary", str(mock_bin), "--no-warmup", "--json")
            self.assertEqual(res.returncode, 1)
            data = json.loads(res.stdout)
            self.assertEqual(data["status"], "FAIL")
            self.assertEqual(data["contractFailures"].count("'graph' exited with code 1, expected 0"), 1)
            self.assertEqual(len(data["contractFailures"]), 6)
            self.assertEqual(len(data["measurements"]["graph"]["samples"]), 3)

    def test_smoke_gate_accepts_valid_shim_java_on_path(self):
        real_java = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java"
        if not Path(real_java).is_file():
            self.skipTest("openjdk@17 not installed at Homebrew path")
        with tempfile.TemporaryDirectory(prefix="kartograph-shim-") as tmpdir:
            shim = Path(tmpdir) / "shims/java"
            shim.parent.mkdir()
            shim.write_text(f'#!/bin/sh\nexec "{real_java}" "$@"\n')
            shim.chmod(0o755)
            custom_env = dict(os.environ)
            custom_env.pop("JAVA_HOME", None)
            custom_env["PATH"] = f"{shim.parent}:/usr/bin:/bin"

            # Verify shim is selected without inferring JAVA_HOME
            import importlib.util
            from unittest.mock import patch

            spec = importlib.util.spec_from_file_location("smoke_gate", SCRIPT)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            with patch.dict(os.environ, custom_env, clear=True):
                selected = mod.check_java_environment()
                self.assertNotIn("JAVA_HOME", selected)
                self.assertTrue(mod.has_working_java(selected))

            cmd = [sys.executable, str(SCRIPT), "--json", "--runs", "1"]
            res = subprocess.run(cmd, capture_output=True, text=True, env=custom_env, timeout=120)
            self.assertEqual(res.returncode, 0)

    def test_smoke_gate_clears_invalid_java_home_and_uses_path_shim(self):
        real_java = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java"
        if not Path(real_java).is_file():
            self.skipTest("openjdk@17 not installed at Homebrew path")
        with tempfile.TemporaryDirectory(prefix="kartograph-stale-home-") as tmpdir:
            shim = Path(tmpdir) / "shims/java"
            shim.parent.mkdir()
            shim.write_text(f'#!/bin/sh\nexec "{real_java}" "$@"\n')
            shim.chmod(0o755)
            custom_env = dict(os.environ)
            custom_env["JAVA_HOME"] = "/nonexistent-jdk"
            custom_env["PATH"] = f"{shim.parent}:/usr/bin:/bin"

            import importlib.util
            from unittest.mock import patch

            spec = importlib.util.spec_from_file_location("smoke_gate", SCRIPT)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            with patch.dict(os.environ, custom_env, clear=True):
                selected = mod.check_java_environment()
                self.assertNotIn("JAVA_HOME", selected)
                self.assertTrue(mod.has_working_java(selected))

            cmd = [sys.executable, str(SCRIPT), "--json", "--runs", "1"]
            res = subprocess.run(cmd, capture_output=True, text=True, env=custom_env, timeout=120)
            self.assertEqual(res.returncode, 0)

    def test_smoke_gate_fails_cleanly_when_no_java_available(self):
        import importlib.util
        from unittest.mock import patch

        spec = importlib.util.spec_from_file_location("smoke_gate", SCRIPT)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with patch.object(mod, "is_working_java", return_value=False), patch("subprocess.run", return_value=subprocess.CompletedProcess([], 1, "")):
            res_code = mod.verify_self_analysis(
                binary=BINARY,
                repo_root=ROOT,
                per_command_budget=5.0,
                total_budget=20.0,
                warmup=False,
            )
            self.assertEqual(res_code, 2)

    def test_smoke_gate_fails_on_invalid_arguments(self):
        res = self.run_gate("--unsupported-flag")
        self.assertEqual(res.returncode, 64)
        self.assertIn("error: invalid argument:", res.stderr)


class AnalysisSmokeFailureJsonTest(unittest.TestCase):
    NAMES = ["graph", "dead", "dead_private", "cycles", "rules", "metrics"]

    def run_default_gate(self, durations):
        """기본 옵션의 단독 모드를 반복별 고정 시간으로 실행한다. 실제 분석기와 시계는 쓰지 않는다."""
        import contextlib
        import importlib.util
        import io
        from unittest.mock import patch

        spec = importlib.util.spec_from_file_location("smoke_gate_failure", SCRIPT)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        observed_passes = []

        def measure(commands, env, samples, counters, failures, diagnostics=None):
            duration = durations[len(observed_passes)]
            observed_passes.append([name for name, _, _ in commands])
            for name, _, _ in commands:
                samples[name].append(duration)
                counters.setdefault(name, []).append({"returncode": 0})

        with tempfile.TemporaryDirectory(prefix="kartograph-smoke-json-") as tmp:
            root = Path(tmp)
            binary = root / "kartograph"
            binary.write_text("unused fixture")
            for module in mod.MODULES:
                classes = root / module / "build/classes/kotlin/main"
                classes.mkdir(parents=True)
                (classes / "Fixture.class").write_bytes(b"fixture")
            (root / ".kartograph-self.pro").write_text("")
            (root / ".kartograph.yml").write_text("")
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(mod.os, "access", return_value=True), \
                    patch.object(mod, "check_java_environment", return_value={}), \
                    patch.object(mod, "has_working_java", return_value=True), \
                    patch.object(mod, "run_measurement_pass", side_effect=measure), \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                status = mod.main(["--json", "--no-warmup", "--repo-root", str(root),
                                   "--binary", str(binary)])
        self.assertEqual(observed_passes, [self.NAMES] * 3)
        self.assertEqual(stderr.getvalue(), "")
        return status, json.loads(stdout.getvalue())

    def test_default_cap_failure_retains_all_samples_and_exit_one(self):
        status, result = self.run_default_gate((7.0, 7.5, 8.0))
        self.assertEqual(status, 1)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["runs"], 3)
        self.assertEqual(result["perCommandBudget"], 10.0)
        self.assertEqual(result["totalBudget"], 40.0)
        self.assertEqual(result["totalSeconds"], 45.0)
        self.assertEqual(result["runTotals"], [42.0, 45.0, 48.0])
        self.assertEqual(list(result["measurements"]), self.NAMES)
        for measurement in result["measurements"].values():
            self.assertEqual(measurement, {"seconds": 7.5, "samples": [7.0, 7.5, 8.0],
                                           "returncode": 0})
        self.assertEqual(result["contractFailures"], [])
        self.assertEqual(result["budgetFailures"], [
            "total analysis time was 45.000s (sum of per-command medians over 3 runs), exceeding total budget of 40.000s"
        ])
        # 기록 예산은 같은 측정을 5초/20초와 비교해 남기며 판정 결과를 바꾸지 않는다.
        recorded = result["recordedBudget"]["candidate"]
        self.assertEqual(recorded["totalSeconds"], 45.0)
        self.assertEqual(len(recorded["exceeded"]), 7)

    def test_recorded_budget_exceedance_does_not_gate_default_run(self):
        # 예전 20초 예산을 넘는 21초도 안전 상한 안이면 통과하고, 초과 사실은 recordedBudget에만 남는다.
        status, result = self.run_default_gate((3.0, 3.5, 4.0))
        self.assertEqual(status, 0)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["totalSeconds"], 21.0)
        self.assertEqual(result["budgetFailures"], [])
        self.assertEqual(result["recordedBudget"], {
            "scope": "observation-only; never-used-for-gate-acceptance",
            "perCommandBudget": 5.0,
            "totalBudget": 20.0,
            "candidate": {"totalSeconds": 21.0, "exceeded": [
                "total analysis time was 21.000s (sum of per-command medians over 3 runs), exceeding total budget of 20.000s"
            ]},
        })


class FakeAnalyzer:
    """subprocess.run 대역이다. 실행 파일 경로로 arm을 구분하고 지정한 시간만큼 가짜 시계를 진행한다.

    시간은 2진 분수만 써서 시계 차이가 정확히 표현되게 한다. 호출 번호는 warm-up을 포함해 (arm, 명령)별로 센다.
    second_position_penalty는 같은 명령 쌍의 두 번째 실행에만 붙는 위치 효과를 흉내 낸다.
    """

    OUTPUTS = {
        "graph": "x [shape=box];\n" * 2000,
        "dead": "",
        "dead_private": "",
        "cycles": "0 findings\n",
        "rules": "0 findings, 0 unassigned\n",
        "metrics": "module\tCa\n" + "row\n" * 6,
    }

    def __init__(self, arms, durations, events=None, outputs=None, second_position_penalty=0.0):
        self.arms = arms
        self.durations = durations
        self.events = events or {}
        self.outputs = outputs or {}
        self.second_position_penalty = second_position_penalty
        self.now = 0.0
        self.calls = []

    def perf_counter(self):
        return self.now

    def run(self, cmd, **kwargs):
        arm = self.arms[cmd[0]]
        name = "dead_private" if cmd[1] == "dead" and "--include-private-members" in cmd else cmd[1]
        index = sum(1 for call in self.calls if call["arm"] == arm and call["name"] == name)
        previous = self.calls[-1] if self.calls else None
        second = (previous is not None and previous["name"] == name and previous["arm"] != arm
                  and previous["index"] == index)
        self.calls.append({"arm": arm, "name": name, "index": index, "cmd": list(cmd),
                           "text": kwargs.get("text", False), "timeout": kwargs.get("timeout")})
        event = self.events.get((arm, name, index), {})
        if isinstance(event, BaseException):
            raise event
        seconds = event.get("seconds", self.durations[arm][name])
        self.now += seconds * (1 + self.second_position_penalty) if second else seconds
        stdout = event.get("stdout", self.outputs.get((arm, name), self.OUTPUTS[name]))
        return subprocess.CompletedProcess(cmd, event.get("returncode", 0), stdout, "secret-stderr-text")


class ReferenceModeTest(unittest.TestCase):
    """기준 비교 모드의 판정·실행 순서·JSON 계약을 가짜 시계로 결정적으로 검사한다."""

    NAMES = ["graph", "dead", "dead_private", "cycles", "rules", "metrics"]
    BASE = {"graph": 4.0, "dead": 4.0, "dead_private": 4.0, "cycles": 3.0, "rules": 3.0, "metrics": 3.0}
    RULES_FAILURE = "'rules' median 3.500s is 1.1667x the reference median 3.000s, exceeding 1.1500x"

    def setUp(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("smoke_gate_reference", SCRIPT)
        self.mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.mod)
        temporary = tempfile.TemporaryDirectory(prefix="kartograph-smoke-reference-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        for module in self.mod.MODULES:
            classes = self.root / module / "build/classes/kotlin/main"
            classes.mkdir(parents=True)
            (classes / "Fixture.class").write_bytes(b"fixture")
        (self.root / ".kartograph-self.pro").write_text("")
        (self.root / ".kartograph.yml").write_text("")
        self.binaries = {}
        for arm in ("reference", "candidate"):
            binary = self.root / arm / "kartograph"
            binary.parent.mkdir()
            binary.write_text("#!/bin/sh\nexit 99\n")
            binary.chmod(0o755)
            self.binaries[arm] = binary

    def scaled(self, factor, **overrides):
        return {name: overrides.get(name, seconds * factor) for name, seconds in self.BASE.items()}

    def gate(self, candidate=None, *args, reference=None, events=None, outputs=None, as_json=True, penalty=0.0):
        import contextlib
        import io
        from unittest.mock import patch

        fake = FakeAnalyzer({str(path): arm for arm, path in self.binaries.items()},
                            {"reference": reference or dict(self.BASE), "candidate": candidate or dict(self.BASE)},
                            events, outputs, penalty)
        argv = ["--repo-root", str(self.root), "--binary", str(self.binaries["candidate"]),
                "--reference-binary", str(self.binaries["reference"]), *args]
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(self.mod, "check_java_environment", return_value={}), \
                patch.object(self.mod, "has_working_java", return_value=True), \
                patch.object(self.mod.subprocess, "run", side_effect=fake.run), \
                patch.object(self.mod.time, "perf_counter", side_effect=fake.perf_counter), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = self.mod.main((["--json"] if as_json else []) + argv)
        document = json.loads(stdout.getvalue()) if as_json and stdout.getvalue() else stdout.getvalue()
        return status, document, stderr.getvalue(), fake

    def usage(self, *args):
        import contextlib
        import io

        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            status = self.mod.main(list(args))
        return status, stderr.getvalue()

    def test_equal_arms_pass_and_record_comparison_document(self):
        status, data, stderr, _ = self.gate()
        self.assertEqual(status, 0, stderr)
        self.assertEqual(data["status"], "PASS")
        self.assertEqual(data["mode"], "reference")
        self.assertEqual((data["runs"], data["perCommandBudget"], data["totalBudget"]), (6, 10.0, 40.0))
        self.assertEqual((data["totalTolerance"], data["commandTolerance"]), (0.05, 0.15))
        self.assertEqual((data["contractFailures"], data["budgetFailures"], data["relativeFailures"]), ([], [], []))
        self.assertEqual(list(data["measurements"]), self.NAMES)
        self.assertEqual(list(data["reference"]["measurements"]), self.NAMES)
        for name, seconds in self.BASE.items():
            self.assertEqual(data["measurements"][name]["samples"], [seconds] * 6, name)
            self.assertEqual(data["reference"]["measurements"][name],
                             {"seconds": seconds, "samples": [seconds] * 6, "returncode": 0, "returncodes": [0] * 6})
            self.assertEqual(data["comparison"]["commands"][name],
                             {"referenceSeconds": seconds, "candidateSeconds": seconds, "ratio": 1.0,
                              "limit": 1.15, "exceeded": False})
        self.assertEqual(data["measurements"]["graph"]["nodes"], 2000)
        self.assertEqual(data["measurements"]["dead"]["findings"], 0)
        self.assertEqual((data["totalSeconds"], data["reference"]["totalSeconds"]), (21.0, 21.0))
        self.assertEqual((data["runTotals"], data["reference"]["runTotals"]), ([21.0] * 6, [21.0] * 6))
        self.assertEqual(data["referenceDiagnosticExits"], 0)
        self.assertEqual(data["revisions"], {"reference": None, "candidate": None})
        comparison = data["comparison"]
        self.assertEqual((comparison["totalRatio"], comparison["totalLimit"], comparison["totalExceeded"]),
                         (1.0, 1.05, False))
        self.assertEqual(comparison["roundRatios"], [1.0] * 6)
        self.assertEqual(data["schedule"]["warmupSuitesPerArm"], 1)
        self.assertRegex(data["schedule"]["sharedArgumentsSha256"], "^[0-9a-f]{64}$")
        # 기존 20초 예산 초과(21초)는 두 arm 모두 기록되지만 판정은 통과다.
        total_message = ("total analysis time was 21.000s (sum of per-command medians over 6 runs), "
                         "exceeding total budget of 20.000s")
        self.assertEqual(data["recordedBudget"], {
            "scope": "observation-only; never-used-for-gate-acceptance", "perCommandBudget": 5.0, "totalBudget": 20.0,
            "reference": {"totalSeconds": 21.0, "exceeded": [total_message]},
            "candidate": {"totalSeconds": 21.0, "exceeded": [total_message]},
        })
        self.assertEqual(data["diagnostics"]["scope"], "observation-only; never-used-for-gate-acceptance")
        self.assertNotIn("secret-stderr-text", json.dumps(data) + stderr)

    def test_total_ratio_beyond_tolerance_fails_without_single_command_failure(self):
        # 모든 명령이 6.25% 느리면 명령별 허용치(15%) 안이어도 합계 허용치(5%)를 넘는다.
        status, data, stderr, _ = self.gate(self.scaled(1.0625))
        self.assertEqual(status, 1)
        self.assertEqual(data["status"], "FAIL")
        self.assertEqual(data["relativeFailures"], [
            "total 22.312s (sum of per-command medians) is 1.0625x the reference 21.000s, exceeding 1.0500x"])
        self.assertTrue(data["comparison"]["totalExceeded"])
        self.assertEqual(data["comparison"]["totalRatio"], 1.0625)
        self.assertFalse(any(row["exceeded"] for row in data["comparison"]["commands"].values()))
        self.assertEqual((data["contractFailures"], data["budgetFailures"]), ([], []))
        # JSON 모드는 단독 모드처럼 실패 목록을 문서에만 남긴다.
        self.assertEqual(stderr, "")

    def test_single_command_ratio_beyond_tolerance_fails(self):
        # rules만 3.5/3.0(약 16.7%) 느리면 합계는 2.4%라 통과해도 명령별 판정이 잡는다.
        status, data, _, _ = self.gate(self.scaled(1.0, rules=3.5))
        self.assertEqual(status, 1)
        self.assertEqual(data["relativeFailures"], [self.RULES_FAILURE])
        self.assertFalse(data["comparison"]["totalExceeded"])
        self.assertEqual(data["comparison"]["totalRatio"], 1.0238)
        self.assertEqual([name for name, row in data["comparison"]["commands"].items() if row["exceeded"]], ["rules"])

    def test_tolerance_limits_are_inclusive(self):
        at_limit = self.scaled(1.125)
        status, data, _, _ = self.gate(at_limit, "--total-tolerance", "0.125", "--command-tolerance", "0.125")
        self.assertEqual((status, data["relativeFailures"]), (0, []))
        self.assertEqual(data["comparison"]["totalRatio"], 1.125)
        above = dict(at_limit, rules=at_limit["rules"] + 1 / 64)
        status, data, _, _ = self.gate(above, "--total-tolerance", "0.125", "--command-tolerance", "0.125")
        self.assertEqual(status, 1)
        self.assertEqual(len(data["relativeFailures"]), 2)

    def test_median_resists_a_fast_outlier_and_uses_every_round(self):
        # 한 번만 빠른 후보 표본은 중앙값을 바꾸지 않는다. 최솟값으로 판정하면 이 회귀를 놓친다.
        status, data, _, _ = self.gate(self.scaled(1.0, rules=3.5), "--no-warmup",
                                       events={("candidate", "rules", 2): {"seconds": 3.0}})
        self.assertEqual(status, 1)
        self.assertEqual(data["measurements"]["rules"]["samples"], [3.5, 3.5, 3.0, 3.5, 3.5, 3.5])
        self.assertEqual(data["relativeFailures"], [self.RULES_FAILURE])
        # 마지막 라운드도 표본이다. 여기서는 마지막 4초 표본이 있어야 중앙값이 3.5초가 된다.
        events = {("candidate", "rules", index): {"seconds": 4.0} for index in (2, 3, 5)}
        status, data, _, _ = self.gate(None, "--no-warmup", events=events)
        self.assertEqual(status, 1)
        self.assertEqual(data["measurements"]["rules"]["samples"], [3.0, 3.0, 4.0, 4.0, 3.0, 4.0])
        self.assertEqual(data["runTotals"][-1], 22.0)
        self.assertEqual(data["relativeFailures"], [self.RULES_FAILURE])

    def test_even_default_rounds_cancel_a_second_position_penalty(self):
        # 쌍의 두 번째 실행만 1/32 느린 위치 효과가 있어도 짝수 라운드 ABBA는 명령마다 두 arm에 똑같이 나눈다.
        status, data, _, _ = self.gate(None, "--no-warmup", penalty=1 / 32)
        self.assertEqual(status, 0)
        self.assertEqual({name: row["ratio"] for name, row in data["comparison"]["commands"].items()},
                         {name: 1.0 for name in self.NAMES})
        self.assertEqual(data["comparison"]["totalRatio"], 1.0)

    def test_safety_cap_applies_even_when_arms_match(self):
        slow = {name: 7.0 for name in self.NAMES}
        status, data, _, _ = self.gate(dict(slow), reference=dict(slow))
        self.assertEqual(status, 1)
        self.assertEqual(data["relativeFailures"], [])
        self.assertEqual(data["comparison"]["totalRatio"], 1.0)
        self.assertEqual(data["budgetFailures"], [
            "total analysis time was 42.000s (sum of per-command medians over 6 runs), exceeding total budget of 40.000s"])
        spike = self.scaled(1.0, graph=10.5)
        status, data, _, _ = self.gate(dict(spike), reference=dict(spike))
        self.assertEqual(status, 1)
        self.assertEqual(data["budgetFailures"], [
            "'graph' took 10.500s (median of 6 runs), exceeding budget of 10.000s"])
        status, data, _, _ = self.gate(dict(spike), "--per-command-budget", "11", reference=dict(spike))
        self.assertEqual((status, data["perCommandBudget"], data["budgetFailures"]), (0, 11.0, []))

    def test_reference_failure_fails_closed_with_distinct_reason(self):
        cases = [
            # 측정 라운드 2의 rules(warm-up 포함 호출 번호 3)에서 기준이 도구 실패(2)로 끝난 경우
            ({("reference", "rules", 3): {"returncode": 2}},
             {"phase": "measurement", "round": 2, "command": "rules", "returncode": 2}),
            ({("reference", "dead", 0): {"returncode": 2}},
             {"phase": "warmup", "round": None, "command": "dead", "returncode": 2}),
            ({("reference", "cycles", 0): {"returncode": 64}},
             {"phase": "warmup", "round": None, "command": "cycles", "returncode": 64}),
            ({("reference", "dead_private", 6): {"returncode": 3}},
             {"phase": "measurement", "round": 5, "command": "dead_private", "returncode": 3}),
            ({("reference", "metrics", 1): subprocess.TimeoutExpired("secret-command", 120)},
             {"phase": "measurement", "round": 0, "command": "metrics", "timedOut": True}),
            ({("reference", "graph", 0): PermissionError(13, "secret-launch-path")},
             {"phase": "warmup", "round": None, "command": "graph", "launchError": "PermissionError"}),
        ]
        for events, details in cases:
            with self.subTest(details=details):
                status, data, stderr, fake = self.gate(events=events)
                self.assertEqual(status, 2)
                self.assertEqual(data["status"], "ERROR")
                self.assertEqual(data["error"], {"kind": "reference-failed", "arm": "reference", **details})
                self.assertNotIn("relativeFailures", data)
                self.assertIn(f"reference command '{details['command']}'", stderr)
                self.assertNotIn("secret-", stderr + json.dumps(data))
                # 실패한 기준 실행 뒤에는 더 측정하지 않는다.
                failed = fake.calls[-1]
                self.assertEqual((failed["arm"], failed["name"]), ("reference", details["command"]))
                if "returncode" in details:
                    receipt = json.loads(stderr.splitlines()[0])
                    self.assertEqual((receipt["arm"], receipt["command"], receipt["returncode"]),
                                     ("reference", details["command"], details["returncode"]))
        self.binaries["reference"].unlink()
        status, data, stderr, fake = self.gate()
        self.assertEqual((status, data["status"], data["error"]["kind"]), (2, "ERROR", "reference-unavailable"))
        self.assertIn("reference kartograph binary not executable", stderr)
        self.assertEqual(fake.calls, [])

    def test_reference_exit_1_without_findings_output_fails_closed(self):
        # JVM 시작 실패·시작 script의 die()는 분석 전에 stdout 없이 1로 끝난다. 즉시 끝난 기준을 strict 진단으로
        # 재면 후보가 몇 배 느린 회귀로 보이므로 판정 불가로 닫는다.
        cases = [
            ({("reference", "graph", 0): {"returncode": 1, "stdout": "", "seconds": 0.0625}},
             {"phase": "warmup", "round": None, "command": "graph"}),
            ({("reference", "cycles", 3): {"returncode": 1, "stdout": "\n  \n", "seconds": 0.0625}},
             {"phase": "measurement", "round": 2, "command": "cycles"}),
        ]
        for events, details in cases:
            with self.subTest(details=details):
                status, data, stderr, fake = self.gate(events=events)
                self.assertEqual(status, 2)
                self.assertEqual(data["status"], "ERROR")
                self.assertEqual(data["error"], {"kind": "reference-failed", "arm": "reference", **details,
                                                 "returncode": 1, "reason": "exit-1-without-findings-output"})
                self.assertNotIn("relativeFailures", data)
                receipt = json.loads(stderr.splitlines()[0])
                self.assertEqual((receipt["arm"], receipt["command"], receipt["returncode"]),
                                 ("reference", details["command"], 1))
                self.assertIn(f"reference command '{details['command']}' exited with code 1 without findings output",
                              stderr)
                self.assertNotIn("secret-", stderr + json.dumps(data))
                self.assertEqual((fake.calls[-1]["arm"], fake.calls[-1]["name"]), ("reference", details["command"]))

    def test_reference_diagnostic_exit_is_measured_and_counted(self):
        # 기준 분석기가 후보 입력에서 strict 진단(1)을 내도 분석은 끝났으므로 시간은 비교한다. 실제 CLI는 그때
        # 항상 finding 행이나 요약을 stdout에 쓴다(dead 행·limitation, cycles/rules 요약).
        finding = "unreachable\tclass:dev/kartograph/NewlyRetained\tNew.kt:1\n"
        events = {("reference", "rules", 0): {"returncode": 1, "stdout": "rules: 1 findings, 0 unassigned\n"},
                  ("reference", "rules", 2): {"returncode": 1, "stdout": finding},
                  ("reference", "dead", 4): {"returncode": 1, "stdout": finding}}
        status, data, stderr, fake = self.gate(events=events)
        self.assertEqual(status, 0, stderr)
        self.assertEqual(data["status"], "PASS")
        self.assertEqual(data["referenceDiagnosticExits"], 2)
        self.assertEqual(data["reference"]["measurements"]["rules"]["returncodes"], [0, 1, 0, 0, 0, 0])
        self.assertEqual(data["reference"]["measurements"]["dead"]["returncodes"], [0, 0, 0, 1, 0, 0])
        self.assertEqual(data["contractFailures"], [])
        self.assertEqual(len(fake.calls), 2 * 6 * 7)
        self.assertEqual(stderr, "")
        # 후보가 같은 진단을 내면 후보 계약은 그대로 실패한다.
        status, data, _, _ = self.gate(events={("candidate", "rules", 2): {"returncode": 1}})
        self.assertEqual((status, data["contractFailures"]), (1, ["'rules' exited with code 1, expected 0"]))

    def test_candidate_contracts_still_apply_on_every_candidate_run(self):
        finding = "unreachable\tclass:dev/kartograph/Unused\tUnused.kt:1\n"
        cases = [
            ({}, {("candidate", "dead"): finding},
             "'dead' reported unreachable findings: ['unreachable\\tclass:dev/kartograph/Unused\\tUnused.kt:1']"),
            ({("candidate", "graph", 4): {"stdout": "x [shape=box];\n" * 2001}}, {},
             "'graph' nodes varied across runs: [2000, 2000, 2000, 2001, 2000, 2000]"),
            ({("candidate", "cycles", 2): {"returncode": 1}}, {}, "'cycles' exited with code 1, expected 0"),
            ({}, {("candidate", "metrics"): "module\tCa\nrow\n"}, "'metrics' produced 1 metric rows, expected >= 6"),
        ]
        for events, outputs, failure in cases:
            with self.subTest(failure=failure):
                status, data, _, _ = self.gate(events=events, outputs=outputs)
                self.assertEqual(status, 1)
                self.assertEqual(data["status"], "FAIL")
                self.assertEqual(data["contractFailures"], [failure])
                self.assertEqual(data["relativeFailures"], [])
        # 기준이 같은 출력을 내도 기준 출력은 계약 대상이 아니며 후보만 판정한다.
        status, data, _, _ = self.gate(outputs={("reference", "dead"): finding})
        self.assertEqual((status, data["contractFailures"]), (0, []))
        for code, expected in ((2, 2), (64, 64)):
            with self.subTest(code=code):
                status, data, stderr, _ = self.gate(events={("candidate", "rules", 1): {"returncode": code}})
                self.assertEqual(status, expected)
                self.assertEqual(data["status"], "ERROR")
                self.assertEqual(data["error"], {"kind": "candidate-failed", "arm": "candidate",
                                                 "phase": "measurement", "round": 0, "command": "rules"})
                self.assertIn(f"(exit {code})", stderr)
        status, data, stderr, _ = self.gate(events={("candidate", "graph", 0): subprocess.TimeoutExpired("x", 120)})
        self.assertEqual((status, data["error"]["kind"], data["error"]["phase"]), (2, "candidate-failed", "warmup"))
        # 실행되지 못한 후보도 traceback 대신 판정 불가 문서로 닫고 원문 경로를 남기지 않는다.
        launches = [
            ({("candidate", "graph", 0): PermissionError(13, "secret-launch-path")},
             {"phase": "warmup", "command": "graph", "launchError": "PermissionError"}),
            ({("candidate", "dead", 1): OSError(8, "secret exec format")},
             {"phase": "measurement", "round": 0, "command": "dead", "launchError": "OSError"}),
        ]
        for events, details in launches:
            with self.subTest(details=details):
                status, data, stderr, _ = self.gate(events=events)
                self.assertEqual(status, 2)
                self.assertEqual(data["status"], "ERROR")
                self.assertEqual(data["error"], {"kind": "candidate-failed", "arm": "candidate", **details})
                self.assertIn(f"candidate command '{details['command']}' could not be launched", stderr)
                self.assertNotIn("secret", stderr + json.dumps(data))

    def test_schedule_pairs_identical_arguments_and_alternates_first_arm(self):
        import hashlib

        _, data, _, fake = self.gate()
        expected = []
        for index in range(-1, 6):  # -1은 측정에서 빼는 warm-up 라운드다.
            for position, name in enumerate(self.NAMES):
                first, second = ("reference", "candidate") if (max(index, 0) + position) % 2 == 0 \
                    else ("candidate", "reference")
                expected.extend([(first, name), (second, name)])
        self.assertEqual([(call["arm"], call["name"]) for call in fake.calls], expected)
        # 쌍마다 실행 파일만 다르고 나머지 인자는 같다. zip이 남는 호출을 버리지 않도록 길이부터 확인한다.
        self.assertEqual(len(fake.calls[0::2]), len(fake.calls[1::2]))
        for first, second in zip(fake.calls[0::2], fake.calls[1::2]):
            self.assertEqual(first["name"], second["name"])
            self.assertEqual(first["cmd"][1:], second["cmd"][1:])
            for call in (first, second):
                self.assertEqual(call["cmd"][0], str(self.binaries[call["arm"]]))
        # 공유 인자는 단독 모드와 같은 고정 명령이다: 여섯 class root 전부, strict, 같은 설정 파일.
        class_args = [value for module in self.mod.MODULES
                      for value in ("--classes", str(self.root / module / "build/classes/kotlin/main"))]
        candidate = {call["name"]: call["cmd"] for call in fake.calls if call["arm"] == "candidate"}
        project = Path(candidate["dead"][candidate["dead"].index("--project") + 1])
        binary = str(self.binaries["candidate"])
        dead = [binary, "dead", *class_args, "--project", str(project), "--manifest",
                str(project / "AndroidManifest.xml"), "--resources", str(project / "res"),
                "--namespace", "dev.kartograph", "--keep-rules", str(self.root / ".kartograph-self.pro")]
        self.assertEqual(candidate, {
            "graph": [binary, "graph", *class_args, "--format", "dot"],
            "dead": [*dead, "--strict"],
            "dead_private": [*dead, "--include-private-members", "--strict"],
            "cycles": [binary, "cycles", *class_args, "--strict"],
            "rules": [binary, "rules", *class_args, "--config", str(self.root / ".kartograph.yml"), "--strict"],
            "metrics": [binary, "metrics", *class_args],
        })
        shared = [[name, *candidate[name][1:]] for name in self.NAMES]
        self.assertEqual(data["schedule"]["sharedArgumentsSha256"],
                         hashlib.sha256(json.dumps(shared, separators=(",", ":")).encode("utf-8")).hexdigest())
        # 두 arm의 warm-up과 측정 호출 모두 같은 120초 timeout으로 묶인다.
        self.assertEqual({(call["arm"], call["timeout"]) for call in fake.calls},
                         {("reference", 120), ("candidate", 120)})
        # warm-up 후보 호출만 기존 단독 warm-up처럼 출력을 해석하지 않는다.
        self.assertEqual([call["text"] for call in fake.calls[:12]],
                         [call["arm"] == "reference" for call in fake.calls[:12]])
        self.assertTrue(all(call["text"] for call in fake.calls[12:]))
        self.assertEqual(data["schedule"]["firstArm"][:2], [
            ["reference", "candidate", "reference", "candidate", "reference", "candidate"],
            ["candidate", "reference", "candidate", "reference", "candidate", "reference"]])
        self.assertEqual(len(data["schedule"]["firstArm"]), 6)
        _, data, _, fake = self.gate(None, "--no-warmup", "--runs", "2")
        self.assertEqual(len(fake.calls), 2 * 2 * 6)
        self.assertEqual((data["runs"], data["schedule"]["warmupSuitesPerArm"]), (2, 0))
        self.assertEqual(len(data["measurements"]["graph"]["samples"]), 2)

    def test_arm_argument_mismatch_fails_closed_before_running(self):
        from unittest.mock import patch

        original = self.mod.analysis_commands
        reference = str(self.binaries["reference"])

        def extra_reference_work(binary, *args):
            commands = original(binary, *args)
            if str(binary) == reference:
                name, cmd, kind = commands[1]
                commands[1] = (name, [*cmd, "--explain"], kind)
            return commands

        def wrong_executable(binary, *args):
            commands = original(binary, *args)
            if str(binary) != reference:
                name, cmd, kind = commands[3]
                commands[3] = (name, [reference, *cmd[1:]], kind)
            return commands

        for skew, command in ((extra_reference_work, "dead"), (wrong_executable, "cycles")):
            with self.subTest(command=command), patch.object(self.mod, "analysis_commands", side_effect=skew):
                status, data, stderr, fake = self.gate()
                self.assertEqual(status, 2)
                self.assertEqual(data["status"], "ERROR")
                self.assertEqual(data["error"], {"kind": "arguments-differ", "command": command})
                self.assertIn(f"'{command}'", stderr)
                self.assertEqual(fake.calls, [])

    def test_single_slow_launch_does_not_fail_and_stays_visible(self):
        status, data, _, _ = self.gate(None, "--no-warmup",
                                       events={("candidate", "graph", 1): {"seconds": 6.0}})
        self.assertEqual(status, 0)
        self.assertEqual(data["measurements"]["graph"]["samples"], [4.0, 6.0, 4.0, 4.0, 4.0, 4.0])
        self.assertEqual(data["comparison"]["commands"]["graph"]["ratio"], 1.0)
        self.assertEqual(data["comparison"]["roundRatios"][1], round(23.0 / 21.0, 4))

    def test_text_output_reports_ratios_and_failures(self):
        status, output, stderr, _ = self.gate(self.scaled(1.0, rules=3.5), "--runs", "1", "--no-warmup",
                                              as_json=False, events={("reference", "dead", 0): {
                                                  "returncode": 1, "stdout": "unreachable\tclass:dev/kartograph/Old\tOld.kt:1\n"}})
        self.assertEqual(status, 1)
        self.assertIn("[SMOKE GATE FAIL] Candidate compared with the reference build on this machine "
                      "(medians of 1 interleaved rounds):", output)
        self.assertIn("  - rules          : 3.500s vs reference 3.000s (ratio 1.1667; limit 1.1500)", output)
        self.assertIn("Recorded 5.000s/20.000s budget (not gating): 1 candidate and 1 reference exceedance(s)", output)
        self.assertIn("Reference exited 1 (strict diagnostics) in 1 measured run(s); its time is still compared.",
                      output)
        self.assertIn("relative failures:\n  - " + self.RULES_FAILURE, stderr)

    def test_revisions_are_validated_and_recorded(self):
        reference, candidate = "704ff3c9d3c373b6a0ec657d345bd7989cb31822", "4c3828b91e8aa3a53b10926e216834d2eac4b3c5"
        status, data, _, _ = self.gate(None, "--reference-revision", reference, "--candidate-revision", candidate)
        self.assertEqual((status, data["revisions"]), (0, {"reference": reference, "candidate": candidate}))
        for bad in ("704ff3c", "G" * 40, reference.upper(), reference + "0", ""):
            with self.subTest(bad=bad):
                status, stderr = self.usage("--reference-binary", str(self.binaries["reference"]),
                                            "--reference-revision", bad)
                self.assertEqual(status, 64)
                self.assertIn("full lowercase commit SHA", stderr)

    def test_step_summary_reports_totals_ratios_and_fail_closed_reason(self):
        summary = self.root / "step-summary.md"
        summary.write_text("earlier step\n")
        reference = "704ff3c9d3c373b6a0ec657d345bd7989cb31822"
        status, _, _, _ = self.gate(None, "--summary", str(summary), "--reference-revision", reference)
        self.assertEqual(status, 0)
        text = summary.read_text()
        self.assertTrue(text.startswith("earlier step\n"))
        self.assertIn("### Relative analysis performance: PASS", text)
        self.assertIn("reference `704ff3c9d3c3`", text)
        self.assertIn("| graph | 4.000 | 4.000 | 1.0000 | 1.1500 |", text)
        self.assertIn("| **total** | 21.000 | 21.000 | 1.0000 | 1.0500 |", text)
        self.assertIn("Recorded 5 s / 20 s budget (not gating): candidate 21.000 s (exceeded), "
                      "reference 21.000 s (exceeded).", text)
        status, _, _, _ = self.gate(self.scaled(1.0, rules=3.5), "--summary", str(summary))
        self.assertEqual(status, 1)
        self.assertIn("### Relative analysis performance: FAIL", summary.read_text())
        self.assertIn("- " + self.RULES_FAILURE, summary.read_text())
        status, _, _, _ = self.gate(None, "--summary", str(summary), events={("reference", "rules", 3): {"returncode": 2}})
        self.assertEqual(status, 2)
        self.assertIn("### Relative analysis performance: ERROR (`reference-failed`)", summary.read_text())
        self.assertIn("command rules, phase measurement, round 2, returncode 2", summary.read_text())
        self.assertEqual(summary.read_text().count("### Relative analysis performance:"), 3)

    def test_step_summary_write_failure_keeps_the_result_and_warns(self):
        # summary는 관측 기록이다. 쓰지 못해도(여기서는 디렉터리 경로) 판정 문서와 종료 코드는 그대로다.
        directory = self.root / "summary-directory"
        directory.mkdir()
        warning = "warning: could not append the job summary (IsADirectoryError); the gate result is unchanged"
        status, data, stderr, _ = self.gate(None, "--summary", str(directory))
        self.assertEqual((status, data["status"]), (0, "PASS"))
        self.assertIn(warning, stderr)
        status, data, stderr, _ = self.gate(None, "--summary", str(directory),
                                            events={("reference", "rules", 3): {"returncode": 2}})
        self.assertEqual((status, data["status"], data["error"]["kind"]), (2, "ERROR", "reference-failed"))
        self.assertIn(warning, stderr)
        self.assertEqual(list(directory.iterdir()), [])

    def test_non_positive_reference_median_fails_closed(self):
        # 실제 시계로는 생기지 않지만 0으로 나눠 통과·실패를 잘못 정하지 않도록 판정 불가로 닫는다.
        with self.assertRaises(self.mod.GateAbort) as raised:
            self.mod.compare_to_reference({"graph": [0.0, 0.0]}, {"graph": [1.0, 1.0]}, 0.05, 0.15)
        self.assertEqual((raised.exception.code, raised.exception.kind), (2, "reference-failed"))
        self.assertEqual(raised.exception.details, {"arm": "reference", "phase": "comparison"})

    def test_reference_binary_must_differ_from_candidate(self):
        candidate = self.binaries["candidate"]
        symbolic = self.root / "symbolic-kartograph"
        symbolic.symlink_to(candidate)
        hard = self.root / "hard-kartograph"
        os.link(candidate, hard)
        for reference in (candidate, symbolic, hard):
            with self.subTest(reference=reference.name):
                status, stderr = self.usage("--binary", str(candidate), "--reference-binary", str(reference))
                self.assertEqual(status, 64)
                self.assertIn("--reference-binary must name a different executable from --binary", stderr)

    def test_reference_mode_options_are_validated(self):
        for args in (["--total-tolerance", "-0.01"], ["--command-tolerance", "nan"], ["--total-tolerance", "inf"],
                     ["--runs", "0"], ["--per-command-budget", "inf"], ["--total-budget", "nan"]):
            with self.subTest(args=args):
                status, stderr = self.usage("--reference-binary", str(self.binaries["reference"]), *args)
                self.assertEqual(status, 64)
                self.assertTrue(stderr.startswith("error: "))
        for args in (["--total-tolerance", "0.1"], ["--command-tolerance", "0.1"], ["--summary", "summary.md"],
                     ["--reference-revision", "a" * 40], ["--candidate-revision", "a" * 40]):
            with self.subTest(args=args):
                status, stderr = self.usage(*args)
                self.assertEqual(status, 64)
                self.assertIn("require --reference-binary", stderr)


class ReferenceModeProcessTest(unittest.TestCase):
    """작은 가짜 실행 파일을 실제 하위 프로세스로 실행해 CLI 종료 코드와 JSON 문서를 확인한다.

    시간 판정을 검사하지 않으므로 허용치를 크게 주어 프로세스 시작 시간의 흔들림과 무관하게 한다.
    """

    SCRIPT_TEMPLATE = """#!/bin/sh
{prelude}
case "$1" in
  graph) yes 'n [shape=box];' | head -n 2000 ;;
  dead) : ;;
  cycles) echo '0 findings' ;;
  rules) echo '0 findings, 0 unassigned' ;;
  metrics) printf 'module\\tCa\\nr1\\nr2\\nr3\\nr4\\nr5\\nr6\\n' ;;
esac
{failure}
exit 0
"""

    def fixture(self, root, failure="", prelude=""):
        for module in ("core", "index", "analysis", "export", "cli", "gradle-plugin"):
            classes = root / module / "build/classes/kotlin/main"
            classes.mkdir(parents=True, exist_ok=True)
            (classes / "Fixture.class").write_bytes(b"fixture")
        (root / ".kartograph-self.pro").write_text("")
        (root / ".kartograph.yml").write_text("")
        binaries = {}
        for arm in ("reference", "candidate"):
            binary = root / arm / "kartograph"
            binary.parent.mkdir(exist_ok=True)
            reference = arm == "reference"
            binary.write_text(self.SCRIPT_TEMPLATE.format(failure=failure if reference else "",
                                                          prelude=prelude if reference else ""))
            binary.chmod(0o755)
            binaries[arm] = binary
        return binaries

    def run_gate(self, root, binaries):
        return run_gate_process("--json", "--repo-root", str(root), "--binary", str(binaries["candidate"]),
                                "--reference-binary", str(binaries["reference"]), "--runs", "2",
                                "--total-tolerance", "1000", "--command-tolerance", "1000")

    def test_real_processes_pass_with_candidate_contracts(self):
        with tempfile.TemporaryDirectory(prefix="kartograph-reference-process-") as tmp:
            root = Path(tmp)
            res = self.run_gate(root, self.fixture(root))
        self.assertEqual(res.returncode, 0, f"stdout: {res.stdout}\nstderr: {res.stderr}")
        data = json.loads(res.stdout)
        self.assertEqual((data["status"], data["mode"], data["runs"]), ("PASS", "reference", 2))
        self.assertEqual(data["measurements"]["graph"]["nodes"], 2000)
        self.assertEqual(data["measurements"]["metrics"]["rows"], 6)
        self.assertEqual(len(data["reference"]["measurements"]["rules"]["samples"]), 2)
        self.assertEqual(data["referenceDiagnosticExits"], 0)

    def test_real_reference_diagnostic_exit_is_measured(self):
        with tempfile.TemporaryDirectory(prefix="kartograph-reference-process-") as tmp:
            root = Path(tmp)
            res = self.run_gate(root, self.fixture(root, 'if [ "$1" = "rules" ]; then exit 1; fi'))
        self.assertEqual(res.returncode, 0, f"stdout: {res.stdout}\nstderr: {res.stderr}")
        data = json.loads(res.stdout)
        self.assertEqual((data["status"], data["referenceDiagnosticExits"]), ("PASS", 2))
        self.assertEqual(data["reference"]["measurements"]["rules"]["returncodes"], [1, 1])

    def test_real_reference_jvm_startup_failure_fails_closed(self):
        # 실제 JVM 시작 실패처럼 stdout 없이 stderr만 쓰고 바로 1로 끝나는 기준은 판정 불가다.
        prelude = ('echo "Error: Could not create the Java Virtual Machine." >&2\n'
                   'echo "Error: A fatal exception has occurred. Program will exit." >&2\nexit 1')
        with tempfile.TemporaryDirectory(prefix="kartograph-reference-process-") as tmp:
            root = Path(tmp)
            res = self.run_gate(root, self.fixture(root, prelude=prelude))
        self.assertEqual(res.returncode, 2, f"stdout: {res.stdout}\nstderr: {res.stderr}")
        data = json.loads(res.stdout)
        self.assertEqual(data["status"], "ERROR")
        self.assertEqual(data["error"], {"kind": "reference-failed", "arm": "reference", "phase": "warmup",
                                         "round": None, "command": "graph", "returncode": 1,
                                         "reason": "exit-1-without-findings-output"})
        self.assertNotIn("Could not create the Java Virtual Machine", res.stdout + res.stderr)

    def test_real_reference_failure_exits_two_with_reason(self):
        failure = 'if [ "$1" = "rules" ]; then echo "error: /private/secret" >&2; exit 2; fi'
        with tempfile.TemporaryDirectory(prefix="kartograph-reference-process-") as tmp:
            root = Path(tmp)
            res = self.run_gate(root, self.fixture(root, failure))
        self.assertEqual(res.returncode, 2, f"stdout: {res.stdout}\nstderr: {res.stderr}")
        data = json.loads(res.stdout)
        self.assertEqual(data["status"], "ERROR")
        self.assertEqual(data["error"], {"kind": "reference-failed", "arm": "reference", "phase": "warmup",
                                         "round": None, "command": "rules", "returncode": 2})
        self.assertNotIn("/private/secret", res.stdout + res.stderr)


if __name__ == "__main__":
    unittest.main()
