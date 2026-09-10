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
        if "JAVA_HOME" not in env:
            # Detect JAVA_HOME if available
            try:
                java_path = subprocess.run(
                    ["which", "java"], capture_output=True, text=True, check=True
                ).stdout.strip()
                java_real = Path(java_path).resolve()
                if java_real.parent.name == "bin":
                    env["JAVA_HOME"] = str(java_real.parent.parent)
            except (subprocess.CalledProcessError, OSError):
                pass

        cmd = [sys.executable, str(SCRIPT), *args]
        return subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=120)

    def test_smoke_gate_passes_on_production_classes(self):
        res = self.run_gate()
        self.assertEqual(
            res.returncode,
            0,
            f"expected gate to pass, got exit {res.returncode}\nstdout: {res.stdout}\nstderr: {res.stderr}",
        )
        self.assertIn("[SMOKE GATE PASS]", res.stdout)
        self.assertIn("Total analysis time:", res.stdout)

    def test_smoke_gate_json_output(self):
        res = self.run_gate("--json")
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout)
        self.assertEqual(data["status"], "PASS")
        # Ensure graph counts actual vertices (around 2,900), not DOT lines including edges (7,600+)
        self.assertGreaterEqual(data["measurements"]["graph"]["nodes"], 2000)
        self.assertLessEqual(data["measurements"]["graph"]["nodes"], 5000)
        self.assertEqual(data["measurements"]["dead"]["findings"], 0)
        self.assertEqual(data["measurements"]["dead_private"]["findings"], 0)
        self.assertGreaterEqual(data["measurements"]["metrics"]["rows"], 6)
        self.assertLessEqual(data["totalSeconds"], data["totalBudget"])

    def test_smoke_gate_fails_when_budget_exceeded(self):
        res = self.run_gate("--per-command-budget", "0.001")
        self.assertEqual(res.returncode, 1)
        self.assertIn("budget failures:", res.stderr)
        self.assertIn("exceeding budget of 0.001s", res.stderr)

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

    def test_smoke_gate_fails_on_invalid_arguments(self):
        res = self.run_gate("--unsupported-flag")
        self.assertEqual(res.returncode, 64)
        self.assertIn("error: invalid argument:", res.stderr)


if __name__ == "__main__":
    unittest.main()
