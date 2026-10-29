"""리소스 프로세스 실패 로그의 범위·비밀 제거·실패 전 출력 계약을 검증한다."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import unittest
from unittest import mock

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] / "resource_process_diagnostics.py"))


class ResourceProcessDiagnosticsTest(unittest.TestCase):
    def test_success_and_expected_status_do_not_emit_failure(self):
        for status, expected in ((0, 0), (64, 64)):
            with self.subTest(status=status), contextlib.redirect_stdout(io.StringIO()) as output:
                MODULE["emit_failure"]("resource-query", status, b"ordinary output", b"", expected)
            self.assertEqual("", output.getvalue())

    def test_failure_block_is_bounded_and_redacts_paths_and_environment_secrets(self):
        log = "UNRELATED_ENV=not-for-output\n* What went wrong:\n"
        log += (" > " + "한" * 1000 + "\n") * 50
        log += "Execution failed for task ':kartographSnapshotDebug'.\n"
        log += "> /home/runner/private/project/file.xml and C:\\private\\file.xml\n"
        log += "> secret-token=must-not-appear\n"
        log += "> known-value abcdef-sensitive-value\n"
        log += "* Try:\nUNRELATED_ENV=not-for-output\n"
        with mock.patch.dict(MODULE["os"].environ, {"SOME_TOKEN": "abcdef-sensitive-value"}, clear=True):
            value = MODULE["summary"]("producer-tamper-snapshot", 1, log, "")
        encoded = json.dumps(value)
        self.assertNotIn("UNRELATED_ENV", encoded)
        self.assertNotIn("must-not-appear", encoded)
        self.assertNotIn("abcdef-sensitive-value", encoded)
        self.assertNotIn("/home/runner", encoded)
        self.assertNotIn("private", encoded)
        self.assertLessEqual(len(value["diagnosticLines"]), MODULE["MAX_LINES"])
        self.assertTrue(all(len(line) <= MODULE["MAX_LINE"] for line in value["diagnosticLines"]))
        self.assertLessEqual(len(json.dumps(value["diagnosticLines"]).encode("utf-8")), MODULE["MAX_BYTES"])
        self.assertEqual(hashlib.sha256(log.encode() + b"\n").hexdigest(), value["fullLogSha256"])
        self.assertEqual("raw-stdout-plus-LF-plus-raw-stderr", value["hashBasis"])

    def test_small_failure_keeps_specific_reason_but_not_raw_paths(self):
        value = MODULE["summary"]("producer-tamper-snapshot", 1,
            "* What went wrong:\n> snapshot compiler inputs are stale: changed-android-resources; /tmp/input.xml\n* Try:\n", "")
        self.assertIn("changed-android-resources", " ".join(value["diagnosticLines"]))
        self.assertIn("<path>", " ".join(value["diagnosticLines"]))
        self.assertFalse(value["unsupportedLogShape"])

    def test_unknown_log_shape_exposes_hash_only_and_invalid_phase_fails(self):
        value = MODULE["summary"]("verify", 1, b"arbitrary environment output", b"\xff")
        self.assertEqual([], value["diagnosticLines"])
        self.assertTrue(value["unsupportedLogShape"])
        self.assertEqual(hashlib.sha256(b"arbitrary environment output\n\xff").hexdigest(), value["fullLogSha256"])
        with self.assertRaises(ValueError):
            MODULE["summary"]("/private/command", 1, b"", b"")

    def test_structured_verification_exposes_only_bounded_status_and_reason_fields(self):
        source = json.dumps({"status": "stale", "reasons": ["changed-android-resources"],
                             "environment": {"PRIVATE": "do-not-print"}, "path": "/private/fixture"})
        value = MODULE["summary"]("verify", 2, source, "")
        self.assertEqual({"status": "stale", "reasons": ["changed-android-resources"]}, value["structuredStatus"])
        self.assertNotIn("do-not-print", json.dumps(value))
        self.assertNotIn("/private", json.dumps(value))
        self.assertFalse(value["unsupportedLogShape"])

    def test_runner_emits_failure_receipt_before_return_and_preserves_exit(self):
        result = subprocess.CompletedProcess(["fixture"], 1, b"* What went wrong:\n> changed-android-resources\n", b"")
        with mock.patch.object(MODULE["subprocess"], "run", return_value=result) as called:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                returned = MODULE["run"](["fixture"], cwd=".", timeout=600, phase="tamper")
                receipt = json.loads(output.getvalue())
        self.assertEqual(1, returned.returncode)
        self.assertEqual("tamper", receipt["phase"])
        self.assertEqual(1, receipt["returncode"])
        self.assertIn("changed-android-resources", returned.stdout)
        self.assertEqual(600, called.call_args.kwargs["timeout"])

    def test_timeout_emits_partial_failure_receipt_then_raises(self):
        timeout = subprocess.TimeoutExpired(["fixture"], 600, output=b"FAILURE: timed out", stderr=b"")
        with mock.patch.object(MODULE["subprocess"], "run", side_effect=timeout):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(subprocess.TimeoutExpired):
                    MODULE["run"](["fixture"], cwd=".", timeout=600, phase="rebuild")
        receipt = json.loads(output.getvalue())
        self.assertTrue(receipt["timedOut"])
        self.assertIsNone(receipt["returncode"])


if __name__ == "__main__":
    unittest.main()
