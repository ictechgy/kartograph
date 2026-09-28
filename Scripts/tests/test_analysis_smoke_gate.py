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
        res = self.run_gate("--json")
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertEqual(data["status"], "PASS")
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
        self.assertEqual(data["totalBudget"], 20.0)
        self.assertEqual(data["perCommandBudget"], 5.0)
        self.assertEqual(len(data["runTotals"]), 3)
        for name, measurement in data["measurements"].items():
            self.assertEqual(len(measurement["samples"]), 3, name)
            self.assertAlmostEqual(measurement["seconds"], sorted(measurement["samples"])[1], places=3, msg=name)
        self.assertAlmostEqual(
            data["totalSeconds"],
            sum(m["seconds"] for m in data["measurements"].values()),
            places=3,
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


if __name__ == "__main__":
    unittest.main()
