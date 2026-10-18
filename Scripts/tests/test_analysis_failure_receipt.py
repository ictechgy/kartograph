"""실패한 analyzer의 제한된 receipt는 계약·시간 gate를 바꾸지 않는다."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from contextlib import redirect_stderr
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "verify-analysis-smoke-gate.py"
SPEC = importlib.util.spec_from_file_location("smoke_failure_receipt", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AnalysisFailureReceiptTest(unittest.TestCase):
    def result(self, stdout="", stderr="", code=1):
        return subprocess.CompletedProcess(["fixture"], code, stdout, stderr)

    def test_success_has_no_receipt(self):
        self.assertIsNone(MODULE.analysis_failure_receipt("dead", self.result(code=0)))

    def test_identity_only_receipt_omits_paths_and_stderr(self):
        stdout = "unreachable\tclass:dev/kartograph/index/Example\t/private/secret.kt:8\n"
        stderr = "token=secret-value /home/private"
        receipt = MODULE.analysis_failure_receipt("dead", self.result(stdout, stderr))
        self.assertEqual(receipt["identities"], ["class:dev/kartograph/index/Example"])
        self.assertEqual(receipt["observedUnreachableRows"], 1)
        self.assertEqual(receipt["returncode"], 1)
        self.assertEqual(receipt["stdoutSha256"], hashlib.sha256(stdout.encode()).hexdigest())
        self.assertEqual(receipt["stderrSha256"], hashlib.sha256(stderr.encode()).hexdigest())
        self.assertEqual(receipt["hashBasis"], "decoded-stream-utf8")
        self.assertNotIn("secret", json.dumps(receipt))
        self.assertNotIn("/private", json.dumps(receipt))

    def test_malformed_foreign_and_oversized_identities_fail_closed(self):
        stdout = "\n".join("unreachable\t" + value for value in [
            "/private/secret", "class:outside/Secret", "class:dev/kartograph/Bad name",
            "class:dev/kartograph/" + "A" * 513,
        ])
        receipt = MODULE.analysis_failure_receipt("dead", self.result(stdout))
        self.assertEqual(receipt["rejectedIdentities"], 4)
        self.assertEqual(receipt["identities"], [])

    def test_receipt_caps_scanned_text_and_identity_count(self):
        stdout = "".join(f"unreachable\tclass:dev/kartograph/Type{i}\tignored\n" for i in range(11))
        receipt = MODULE.analysis_failure_receipt("dead", self.result(stdout))
        self.assertEqual(len(receipt["identities"]), 10)
        self.assertTrue(receipt["identitiesTruncated"])
        receipt = MODULE.analysis_failure_receipt("dead", self.result("x" * (256 * 1024 + 1)))
        self.assertTrue(receipt["inputTruncated"])
        self.assertTrue(receipt["unsupportedOutputShape"])
        self.assertLess(len(json.dumps(receipt)), 1024)

    def test_generic_nonzero_commands_preserve_exit_and_do_not_print_raw_output(self):
        for code, expected in [(1, None), (2, 2), (64, 64)]:
            stderr = io.StringIO()
            samples = {"graph": []}
            counters, failures = {}, []
            with patch.object(MODULE.subprocess, "run", return_value=self.result("private payload", "private error", code)), redirect_stderr(stderr):
                actual = MODULE.run_measurement_pass([("graph", ["fixture"], "node_count")],
                    {}, samples, counters, failures)
            self.assertEqual(actual, expected)
            receipt = json.loads(stderr.getvalue().splitlines()[0])
            self.assertEqual(receipt["command"], "graph")
            self.assertEqual(receipt["returncode"], code)
            self.assertTrue(receipt["unsupportedOutputShape"])
            self.assertNotIn("private", stderr.getvalue())
            self.assertEqual(len(samples["graph"]), 1)
            if code == 1:
                self.assertEqual(failures, ["'graph' exited with code 1, expected 0"])


if __name__ == "__main__":
    unittest.main()
