"""고정 A/A 일정·판정·원래 smoke argv·작은 자식의 실패 보존을 검증한다."""

import importlib.util
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("repeatability", ROOT / "Scripts/verify-cli-repeatability.py")
R = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(R)


def passing_rows():
    return [dict(suite, command=name, returncode=0, contract_failures=[], seconds=3.0)
            for suite in R.schedule() for name in R.NAMES]


class RepeatabilityStatisticsTest(unittest.TestCase):
    def test_exact_schedule_has_two_warmups_and_six_balanced_suite_pairs(self):
        suites = R.schedule()
        self.assertEqual(14, len(suites))
        self.assertEqual(["A", "B"], [r["label"] for r in suites[:2]])
        self.assertEqual(["AB", "BA", "BA", "AB", "AB", "BA"],
                         ["".join(r["label"] for r in suites if r["pair"] == pair)
                          for pair in range(6)])
        self.assertEqual(84, len(passing_rows()))

    def test_perfect_same_executable_observations_qualify(self):
        score = R.grade(passing_rows())
        self.assertTrue(score["qualified"])
        self.assertEqual([18.0] * 6, score["statistics"]["suite_total"]["A"])
        self.assertEqual([0.0] * 6, score["statistics"]["graph"]["signed_symmetric_differences"])

    def test_pair_direction_uses_labels_even_when_B_runs_first(self):
        rows = passing_rows()
        for row in rows:
            if not row["warmup"] and row["label"] == "B":
                row["seconds"] = 3.1
        score = R.grade(rows)
        expected = 2 * (3.1 - 3.0) / (3.1 + 3.0)
        for value in score["statistics"]["graph"]["signed_symmetric_differences"]:
            self.assertAlmostEqual(expected, value)
        self.assertFalse(score["qualified"])

    def test_one_noisy_pair_is_not_removed(self):
        rows = passing_rows()
        next(r for r in rows if r["pair"] == 2 and r["label"] == "B"
             and r["command"] == "graph")["seconds"] = 3.3
        score = R.grade(rows)
        graph = score["statistics"]["graph"]
        self.assertFalse(graph["checks"]["all_pairs_within_5_percent"])
        self.assertTrue(graph["checks"]["median_imbalance_within_2_5_percent"])
        self.assertFalse(score["qualified"])

    def test_inclusive_limits_and_independent_range_condition(self):
        score = R.statistic([39.0] * 6, [41.0] * 6)
        self.assertTrue(score["checks"]["all_pairs_within_5_percent"])
        self.assertFalse(score["checks"]["median_imbalance_within_2_5_percent"])
        self.assertTrue(R.statistic([79.0] * 6, [81.0] * 6)["passed"])
        values = [10.0, 11.0, 10.0, 11.0, 10.0, 11.0]
        self.assertTrue(R.statistic(values, values)["passed"])
        values[1] = 11.001
        self.assertFalse(R.statistic(values, values)["checks"]["full_range_at_most_1_10"])

    def test_missing_reordered_nonfinite_failed_or_nonpositive_rows_refuse(self):
        cases = []
        cases.append(passing_rows()[:-1])
        reordered = passing_rows()
        reordered[12], reordered[13] = reordered[13], reordered[12]
        cases.append(reordered)
        for value in (math.nan, math.inf, 0.0, -1.0):
            rows = passing_rows()
            rows[0]["seconds"] = value
            cases.append(rows)
        failed = passing_rows()
        failed[20]["returncode"] = 2
        cases.append(failed)
        contract = passing_rows()
        contract[20]["contract_failures"] = ["unexpected finding"]
        cases.append(contract)
        for rows in cases:
            with self.subTest(rows=len(rows)), self.assertRaises(ValueError):
                R.grade(rows)

    def test_bool_and_wrong_typed_schedule_exit_and_time_values_refuse(self):
        for key, value in (("seconds", True), ("returncode", False), ("suite", False),
                           ("warmup", 1), ("contract_failures", False), ("label", 65)):
            rows = passing_rows()
            rows[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                R.grade(rows)
        rows = passing_rows()
        rows[12]["pair"] = False
        with self.assertRaises(ValueError):
            R.grade(rows)
        with self.assertRaises(ValueError):
            R.statistic([True] * 6, [1.0] * 6)


class RepeatabilitySetupTest(unittest.TestCase):
    def test_environment_is_complete_allowlist_without_jvm_or_ci_injection(self):
        parent = dict.fromkeys(R.ENV_KEYS, "declared-value")
        parent.update(JAVA_OPTS="-Xmx9g", JAVA_TOOL_OPTIONS="-agentlib:fake",
                      JDK_JAVA_OPTIONS="-Xmx8g", _JAVA_OPTIONS="-Xmx7g",
                      KARTOGRAPH_OPTS="-Xmx6g", GH_TOKEN="not-a-real-token", TMPDIR="old")
        env = R.cli_environment(parent, Path("/owned/tmp"))
        self.assertEqual(set(R.ENV_KEYS) | {"TMPDIR"}, set(env))
        self.assertEqual("/owned/tmp", env["TMPDIR"])

    def test_declared_commands_equal_existing_smoke_commands(self):
        spec = importlib.util.spec_from_file_location("real_smoke", ROOT / "Scripts/verify-analysis-smoke-gate.py")
        smoke = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(smoke)
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            for module in R.MODULES:
                root = repo / module / "build/classes/kotlin/main"
                root.mkdir(parents=True)
                (root / "present.class").write_bytes(b"fixture")
            binary = repo / "cli/build/install/kartograph/bin/kartograph"
            binary.parent.mkdir(parents=True)
            binary.write_text("fixture, never executed")
            binary.chmod(0o700)
            (repo / ".kartograph.yml").write_text("layers: []")
            (repo / ".kartograph-self.pro").write_text("")
            captured = []
            def observe(commands, *unused):
                captured.extend(commands)
                return 2
            with patch.object(smoke, "check_java_environment", return_value={}), \
                 patch.object(smoke, "has_working_java", return_value=True), \
                 patch.object(smoke, "run_measurement_pass", side_effect=observe):
                self.assertEqual(2, smoke.verify_self_analysis(binary, repo, 5, 20, warmup=False))
            project = Path(captured[1][1][captured[1][1].index("--project") + 1])
            self.assertEqual(captured, R.commands(repo, project))

    def test_small_read_refuses_oversize(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "data"
            path.write_bytes(b"12345")
            self.assertEqual({"unavailable": "size-limit"}, R.read_small(path, 4))

    def test_resource_snapshots_detect_cgroup_migration(self):
        with patch.object(R, "cgroup_directory", side_effect=[Path("/leaf-a"), Path("/leaf-b")]), \
             patch.object(R, "read_small", return_value="unchanged"), \
             patch.object(R.os, "sched_getaffinity", return_value={0, 1}):
            before, after = R.resources(), R.resources()
        self.assertNotEqual(before["policy"], after["policy"])
        self.assertEqual("/leaf-a", before["policy"]["observed_leaf_cgroup"])


@unittest.skipUnless(hasattr(os, "wait4"), "requires POSIX wait4")
class RepeatabilityCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.capture = R.Capture(self.output, self.output, dict(os.environ), time.monotonic() + 10)

    def test_success_retains_direct_child_usage_and_raw_streams(self):
        row = {}
        self.capture.run([sys.executable, "-c", "print('retained')"], "success", row)
        self.assertEqual(0, row["returncode"])
        self.assertEqual(b"retained\n", (self.output / row["stdout"]).read_bytes())
        self.assertIn("user_seconds", row)
        self.assertIn("max_rss_kib", row)
        self.assertFalse(self.capture.unreaped)

    def test_nonzero_preserves_actual_error_and_stderr(self):
        row = {}
        with self.assertRaisesRegex(RuntimeError, "child returned 2"):
            self.capture.run([sys.executable, "-c", "import sys; print('failed',file=sys.stderr);sys.exit(2)"],
                             "failed", row)
        self.assertEqual(2, row["returncode"])
        self.assertEqual(b"failed\n", (self.output / row["stderr"]).read_bytes())

    def test_post_exit_overflow_retains_size_without_hashing(self):
        row = {}
        with self.assertRaises(ValueError):
            self.capture.run([sys.executable, "-c", "print('x'*1000)"], "overflow", row, limits=(32, 32))
        self.assertTrue(row["stdout_overflow"])
        self.assertGreater(row["stdout_bytes"], 32)
        self.assertNotIn("stdout_sha256", row)

    def test_timeout_still_reaps_when_kill_reports_exit_race(self):
        row, owned = {}, []
        original = os.killpg
        def exit_race(pid, sig):
            owned.append(pid)
            original(pid, sig)
            raise ProcessLookupError("simulated post-signal exit race")
        with patch.object(R.os, "killpg", side_effect=exit_race), self.assertRaises(TimeoutError):
            self.capture.run([sys.executable, "-c", "import time;time.sleep(5)"], "timeout", row, timeout=.05)
        self.assertEqual(-signal.SIGKILL, row["returncode"])
        self.assertFalse(self.capture.unreaped)
        with self.assertRaises(ChildProcessError):
            os.waitpid(owned[0], os.WNOHANG)

    def test_capture_failure_cannot_replace_primary_timeout(self):
        row = {}
        with patch.object(R, "digest", side_effect=OSError("secondary hash error")), \
             self.assertRaises(TimeoutError):
            self.capture.run([sys.executable, "-c", "import time;time.sleep(5)"], "primary", row, timeout=.05)
        self.assertIn("TimeoutError", row["error"])
        self.assertIn("OSError", row["stdout_capture_error"])
        self.assertFalse(self.capture.unreaped)

    def test_expired_overall_deadline_starts_no_child(self):
        self.capture.deadline = time.monotonic() - 1
        with patch.object(R.subprocess, "Popen") as popen, self.assertRaises(TimeoutError):
            self.capture.run([sys.executable, "-c", "raise AssertionError()"], "never", {})
        popen.assert_not_called()

    def test_completed_child_cannot_escape_per_child_deadline(self):
        row = {}
        self.capture.deadline = 100.0
        usage = SimpleNamespace(ru_utime=.01, ru_stime=.01, ru_maxrss=10)
        child = SimpleNamespace(pid=12345, returncode=None)
        with patch.object(R.subprocess, "Popen", return_value=child), \
             patch.object(R.os, "wait4", return_value=(12345, 0, usage)), \
             patch.object(R.time, "monotonic", side_effect=[10.0, 10.0, 10.2]), \
             self.assertRaisesRegex(TimeoutError, "completed child"):
            self.capture.run(["mock-child"], "late-success", row, timeout=.1)
        self.assertEqual(0, row["returncode"])
        self.assertAlmostEqual(.2, row["seconds"])
        self.assertFalse(self.capture.unreaped)


if __name__ == "__main__":
    unittest.main()
