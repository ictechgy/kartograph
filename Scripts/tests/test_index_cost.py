"""격리 비교의 byte 계약·입력 identity·실제 javac/JVM 진입을 검증한다."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("index_cost", ROOT / "Scripts/verify-index-cost.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class IndexCostTest(unittest.TestCase):
    def test_exact_bytes_preserve_order_duplicates_diagnostics_and_exit(self):
        left = {"exit": 0, "stdout": b"a\na\nb\n", "stderr": b"diagnostic\n", "wallNanos": 1}
        self.assertTrue(probe.equivalent(left, {**left, "wallNanos": 99}))
        for key, value in (("stdout", b"a\nb\na\n"), ("stdout", b"a\nb\n"),
                           ("stdout", b"a\ra\rb\r"), ("stderr", b""), ("exit", 1)):
            self.assertFalse(probe.equivalent(left, {**left, key: value}))

    def test_identical_unsuccessful_exits_do_not_pass(self):
        for exit_code in (1, 2, 64):
            result = {"exit": exit_code, "stdout": b"", "stderr": b"same failure"}
            self.assertTrue(probe.equivalent(result, result))
            self.assertFalse(probe.successful_pair(result, result))
        result = {"exit": 0, "stdout": b"ok", "stderr": b""}
        self.assertTrue(probe.successful_pair(result, result))

    def test_alternation_is_three_samples_per_profile_and_arm(self):
        rows = probe.schedule()
        self.assertEqual(18, len(rows))
        for profile in probe.PROFILES:
            selected = [(sample, arm) for p, sample, arm in rows if p == profile]
            self.assertEqual([(1, "baseline"), (1, "candidate"), (2, "candidate"),
                              (2, "baseline"), (3, "baseline"), (3, "candidate")], selected)

    def test_inventory_detects_same_size_byte_change_and_mtime_change(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "A.class"
            path.write_bytes(b"abc")
            before = probe.inventory(root)
            original = path.stat()
            path.write_bytes(b"abd")
            os.utime(path, ns=(original.st_atime_ns, original.st_mtime_ns))
            after = probe.inventory(root)
            self.assertNotEqual(before, after)
            with self.assertRaises(probe.DiagnosticError):
                probe.require_equal(before, after, "changed")
            os.utime(path, ns=(original.st_atime_ns, original.st_mtime_ns + 1000000000))
            self.assertNotEqual(after, probe.inventory(root))

    def test_missing_empty_and_symlink_inventory_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for directory in (root, root / "absent"):
                with self.assertRaises(probe.DiagnosticError):
                    probe.inventory(directory)
            (root / "target").write_bytes(b"a")
            (root / "alias").symlink_to("target")
            with self.assertRaises(probe.DiagnosticError):
                probe.inventory(root)

    def test_frozen_copy_preserves_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, frozen = root / "source", root / "frozen"
            source.mkdir()
            (source / "A.class").write_bytes(b"class")
            shutil.copytree(source, frozen, copy_function=shutil.copy2)
            self.assertEqual(probe.inventory(source), probe.inventory(frozen))

    def test_real_process_timeout_and_output_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-timeout"):
                probe.run([sys.executable, "-c", "import time; time.sleep(5)"], temporary,
                          dict(os.environ), timeout=0.1)
            with self.assertRaisesRegex(probe.DiagnosticError, "child-output-limit"):
                probe.run([sys.executable, "-c", "print('a' * 100000)"], temporary,
                          dict(os.environ), limit=1024)
            result = probe.run([sys.executable, "-c",
                                "import sys; sys.stdout.buffer.write(b'a\\r\\nb'); sys.stderr.write('x'); sys.exit(3)"],
                               temporary, dict(os.environ))
            self.assertEqual((3, b"a\r\nb", b"x"), (result["exit"], result["stdout"], result["stderr"]))

    def test_wait4_failure_kills_and_reaps_owned_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.object(probe.os, "wait4", side_effect=OSError("synthetic")):
                with mock.patch.object(probe, "kill_group", wraps=probe.kill_group) as kill:
                    with self.assertRaisesRegex(probe.DiagnosticError, "child-wait-failed") as caught:
                        probe.run([sys.executable, "-c", "import time; time.sleep(5)"],
                                  temporary, dict(os.environ), phase="test-wait")
                    process = kill.call_args.args[0]
                    self.assertIsNotNone(process.returncode)
                    self.assertEqual("test-wait", caught.exception.receipt["phase"])

    def test_structural_count_change_is_rejected(self):
        rows = [dict(profile=profile, nodes=1, edges=2, externalCalls=3, classFiles=4,
                     parsedClasses=4, cacheHits=0, cacheMisses=0)
                for profile, _, _ in probe.schedule()]
        probe.check_profile_counts(rows)
        rows[1]["externalCalls"] += 1
        with self.assertRaisesRegex(probe.DiagnosticError, "profile-structural-counts-changed"):
            probe.check_profile_counts(rows)

    def test_summary_whitelists_and_bounds_log_fields(self):
        report = {"format": "kartograph-index-cost-v1", "status": "FAIL", "scope": "diagnostic",
                  "residualScope": "not-query-only", "reason": "test", "rawPath": "/private/not-logged",
                  "failure": {"phase": "baseline-build", "exit": 1,
                              "stdout": {"bytes": 3, "sha256": probe.digest(b"abc")}}}
        result = json.loads(probe.public_summary(report))
        self.assertNotIn("rawPath", result)
        self.assertEqual("baseline-build", result["failure"]["phase"])
        report["profiles"] = ["x" * 70000]
        with self.assertRaisesRegex(probe.DiagnosticError, "public-summary-too-large"):
            probe.public_summary(report)

    def test_six_cli_commands_share_exact_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            roots = [project / module for module in probe.MODULES]
            left = probe.cli_commands("baseline", roots, project)
            right = probe.cli_commands("candidate", roots, project)
            self.assertEqual(["graph", "dead", "dead_private", "cycles", "rules", "metrics"],
                             [name for name, _ in left])
            for (_, a), (_, b) in zip(left, right):
                self.assertEqual(a[1:], b[1:])
                self.assertEqual(6, a.count("--classes"))
            self.assertIn("--include-private-members", left[2][1])
            self.assertIn("--strict", left[1][1])

    def test_missing_candidate_is_failure_not_skip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jdk = root / "jdk"
            for name in ("bin/java", "bin/javac", "release", "lib/modules"):
                path = jdk / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"identity")
            with self.assertRaisesRegex(probe.DiagnosticError, "missing-candidate-distribution"):
                probe.experiment(root, root, {"JAVA_HOME": str(jdk)}, {})

    def test_real_java_probe_against_installed_distribution(self):
        # CI의 선행 installDist/JDK가 없으면 skip 대신 실패한다.
        java_home = Path(os.environ["JAVA_HOME"]).resolve(strict=True)
        distribution = ROOT / "cli/build/install/kartograph"
        self.assertTrue((distribution / "lib").is_dir())
        env = dict(os.environ)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classes = root / "classes"
            classes.mkdir()
            source = root / "Tiny.java"
            source.write_text("public final class Tiny { public static int value() { return 7; } }\n")
            probe.successful([java_home / "bin/javac", "-d", classes, source], root, env)
            classpath = probe.compile_probe(ROOT, distribution, root / "probe", java_home, env)
            for profile in probe.PROFILES:
                result = probe.successful([java_home / "bin/java", "-cp", classpath,
                                           "IndexCostProbe", profile, classes], root, env)
                value = probe.validate_probe(result, profile)
                self.assertEqual(profile, value["profile"])
                self.assertEqual(1, value["classFiles"])
                self.assertEqual(1, value["parsedClasses"])
                self.assertEqual(0, value["cacheHits"])
                self.assertGreater(value["nodes"], 0)
                self.assertGreaterEqual(value["totalNanos"], 0)
                self.assertGreaterEqual(value["entryUptimeMillis"], 0)
                self.assertEqual(b"", result["stderr"])
                for key, invalid in (("nodes", -1), ("totalNanos", float("nan")),
                                     ("currentThreadAllocatedBytes", -1), ("profile", "other"),
                                     ("entryUptimeMillis", True)):
                    altered = {**value, key: invalid}
                    with self.assertRaisesRegex(probe.DiagnosticError, "invalid-probe-output"):
                        probe.validate_probe({**result, "stdout": json.dumps(altered).encode()}, profile)


if __name__ == "__main__":
    unittest.main()
