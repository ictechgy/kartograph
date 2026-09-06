"""실제 Git·javac·배포 CLI로 PR gate의 억제 경계를 검증한다."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(os.environ.get("KARTOGRAPH_BINARY", ROOT / "cli/build/install/kartograph/bin/kartograph")).resolve()
SCRIPT = Path(os.environ.get("KARTOGRAPH_PR_SCRIPT", ROOT / "Scripts/check-pr.py")).resolve()
TEST_ENV = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}


class CheckPrTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kartograph-pr-test-")
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.run_command("git", "init", "-q")
        self.run_command("git", "config", "user.name", "Fixture")
        self.run_command("git", "config", "user.email", "fixture@example.invalid")
        (self.project / ".gitignore").write_text("classes/\n")
        (self.project / "Entry.java").write_text(
            "public class Entry { public void run() { new Helper(); } }\n"
        )
        (self.project / "Helper.java").write_text("public class Helper {}\n")
        (self.project / "Existing.java").write_text("public class Existing {}\n")
        (self.project / "keep.pro").write_text("-keep class Entry { *; }\n")
        (self.project / "AndroidManifest.xml").write_text("<manifest><application/></manifest>\n")
        (self.project / "res").mkdir()
        self.compile()
        self.capture_baseline()
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "fixture base")
        self.base = self.run_command("git", "rev-parse", "HEAD").stdout.strip()

    def run_command(self, *args):
        return subprocess.run(args, cwd=self.project, text=True, capture_output=True, check=True, env=TEST_ENV)

    def compile(self):
        self.run_command("javac", "-g", "-d", "classes", "Entry.java", "Helper.java", "Existing.java")

    def capture_baseline(self):
        self.run_command(str(BINARY), "baseline", "--write", ".kartograph-baseline.json",
                         "--project", str(self.project), "--classes", "classes", "--keep-rules", "keep.pro",
                         "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture")

    def gate(self, *extra, base=None, helper_options=(), environment=TEST_ENV):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--binary", str(BINARY), "--project", str(self.project),
             "--base", base or self.base, *helper_options, "--", "--classes", "classes", "--keep-rules", "keep.pro",
             "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture",
             "--report-format", "json", *extra], text=True, capture_output=True, env=environment,
        )

    def test_existing_debt_is_suppressed(self):
        result = self.gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["diagnostics"], [])
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_caller_removal_reports_untouched_helper_despite_pr_baseline_update(self):
        (self.project / "Entry.java").write_text("public class Entry { public void run() {} }\n")
        self.compile()
        self.capture_baseline()
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "remove caller and expand baseline")
        result = self.gate()
        self.assertEqual(result.returncode, 1, result.stderr)
        document = json.loads(result.stdout)
        self.assertEqual([item["nodeId"] for item in document["diagnostics"]], ["class:Helper"])
        self.assertEqual(document["suppressedCount"], 1)

    def test_missing_base_fails_without_raw_ref_or_project_path(self):
        result = self.gate(base="missing-private-ref")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("missing-private-ref", result.stderr)
        self.assertNotIn(str(self.project), result.stderr)
        self.assertIn("resolve base commit", result.stderr)

    def test_missing_baseline_is_not_silently_empty(self):
        self.run_command("git", "rm", ".kartograph-baseline.json")
        self.run_command("git", "commit", "-qm", "remove baseline")
        result = self.gate(base="HEAD")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("read base baseline", result.stderr)

    def test_invalid_baseline_is_tool_failure(self):
        (self.project / ".kartograph-baseline.json").write_text("{}")
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "invalid baseline")
        result = self.gate(base="HEAD")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("baseline fingerprints", result.stderr)

    def test_gate_overrides_and_capture_are_rejected(self):
        for option in ("--baseline", "--since", "--project", "--write-baseline", "--explain", "--help",
                       "-h", "--baseline=ignored", "--version", "-b"):
            with self.subTest(option=option):
                result = self.gate(option, "ignored")
                self.assertEqual(result.returncode, 64, result.stderr)

    def test_custom_baseline_path_with_spaces(self):
        self.run_command("git", "mv", ".kartograph-baseline.json", "baseline with spaces.json")
        self.run_command("git", "commit", "-qm", "custom baseline")
        result = self.gate(base="HEAD", helper_options=("--baseline-path", "baseline with spaces.json"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_missing_required_cli_value_preserves_usage_failure(self):
        self.assertEqual(self.gate("--classes").returncode, 64)

    def test_invalid_cli_report_format_preserves_usage_failure(self):
        result = self.gate("--report-format", "invalid")
        self.assertEqual(result.returncode, 64)
        self.assertIn("invalid report format", result.stderr)

    def test_test_classes_option_is_forwarded_without_rejection(self):
        result = self.gate("--test-classes", "classes")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_inherited_git_override_is_not_forwarded_to_cli(self):
        binary = self.project / "environment-cli"
        binary.write_text("#!/usr/bin/env python3\nimport os, sys\nsys.exit(7 if 'GIT_DIR' in os.environ else 0)\n")
        binary.chmod(0o700)
        result = self.gate(helper_options=("--binary", str(binary)),
                           environment={**TEST_ENV, "GIT_DIR": "/missing-fixture-git-dir"})
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_non_root_project_is_rejected(self):
        result = self.gate(helper_options=("--project", str(self.project / "res")))
        self.assertEqual(result.returncode, 64, result.stderr)

    def test_cli_timeout_is_bounded_tool_failure(self):
        binary = self.project / "slow-cli"
        binary.write_text("#!/usr/bin/env python3\nimport time\ntime.sleep(60)\n")
        binary.chmod(0o700)
        result = self.gate(helper_options=("--binary", str(binary), "--timeout", "1"))
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("timed out", result.stderr)

    def test_unexpected_cli_exit_maps_to_tool_failure(self):
        binary = self.project / "failing-cli"
        binary.write_text("#!/bin/sh\nexit 7\n")
        binary.chmod(0o700)
        result = self.gate(helper_options=("--binary", str(binary)))
        self.assertEqual(result.returncode, 2, result.stderr)


if __name__ == "__main__":
    unittest.main()
