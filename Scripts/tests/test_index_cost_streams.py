"""큰 실제 CLI 출력도 끝까지 비교하며 기존 child 보호를 유지한다."""
import gzip
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("index_cost_streams", ROOT / "Scripts/verify-index-cost.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class IndexCostStreamsTest(unittest.TestCase):
    def test_actual_paired_cli_accepts_valid_graph_beyond_memory_capture_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            code = ("import sys\n"
                    "for _ in range(528 if sys.argv[1] == 'graph' else 0): "
                    "sys.stdout.buffer.write(b'x' * 65536)\n"
                    "sys.stdout.buffer.write(b'tail\\r\\n!')\n")
            commands = {arm: [(name, [sys.executable, "-c", code, name])
                              for name, _ in probe.cli_commands(arm, [], root)]
                        for arm in ("baseline", "candidate")}
            report = {}
            probe.paired_cli(commands, root, dict(os.environ), 1, report, probe.RawStreams(root / "raw"))
            self.assertEqual(18, len(report["cli"]))
            self.assertTrue(all(row["contractAccepted"] for row in report["cli"]))
            self.assertEqual(33 * 1024 * 1024 + 7, report["cli"][0]["baseline"]["stdout"]["bytes"])

    def test_complete_large_stdout_is_spooled_and_losslessly_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            size = 33 * 1024 * 1024 + 7
            code = ("import sys\n"
                    "for _ in range(528): sys.stdout.buffer.write(b'x' * 65536)\n"
                    "sys.stdout.buffer.write(b'tail\\r\\n!')\n")
            result = probe.run([sys.executable, "-c", code], root, dict(os.environ),
                               stdout_path=root / "stdout")
            self.assertEqual(size, len(result["stdout"]))
            self.assertEqual(size, probe.summary(result)["stdout"]["bytes"])
            raw = probe.RawStreams(root / "raw")
            receipt = raw.retain("graph", 1, "baseline", result)["stdout"]
            checksum = hashlib.sha256()
            count = 0
            with gzip.open(root / receipt["path"], "rb") as source:
                for chunk in iter(lambda: source.read(65536), b""):
                    count += len(chunk)
                    checksum.update(chunk)
            self.assertEqual(size, count)
            self.assertEqual(checksum.hexdigest(), receipt["sha256"])

    def test_spooled_stdout_still_has_a_hard_cap(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-output-limit"):
                probe.run([sys.executable, "-c", "print('x' * 8192)"], temporary,
                          dict(os.environ), stdout_path=Path(temporary) / "stdout", stdout_limit=4096)

    def test_spooling_does_not_relax_stderr_or_child_timeout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(probe.DiagnosticError, "child-output-limit"):
                probe.run([sys.executable, "-c", "import sys; sys.stderr.write('x' * 8192)"],
                          root, dict(os.environ), limit=1024, stdout_path=root / "stderr-case")
            with self.assertRaisesRegex(probe.DiagnosticError, "child-timeout"):
                probe.run([sys.executable, "-c", "import time; time.sleep(5)"],
                          root, dict(os.environ), timeout=0.1, stdout_path=root / "timeout-case")

    def test_comparison_checks_complete_bytes_and_unsuccessful_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outputs = []
            for index, tail in enumerate((b"a", b"a", b"b")):
                path = root / str(index)
                path.write_bytes(b"x" * 65536 + b"\x00\r\n" + tail)
                outputs.append(probe.SpooledOutput(path))
            left = {"exit": 0, "stdout": outputs[0], "stderr": b"warning"}
            self.assertTrue(probe.equivalent(left, {**left, "stdout": outputs[1]}))
            self.assertFalse(probe.equivalent(left, {**left, "stdout": outputs[2]}))
            self.assertFalse(probe.equivalent(left, {**left, "stderr": b""}))
            self.assertFalse(probe.successful_pair({**left, "exit": 1}, {**left, "exit": 1}))

    def test_retention_keeps_sample_paths_and_the_existing_total_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "output"
            source.write_bytes(b"abc")
            raw = probe.RawStreams(root / "raw")
            with mock.patch.object(probe, "RAW_LIMIT", 6):
                first = raw.retain("graph", 1, "baseline",
                                   {"stdout": probe.SpooledOutput(source), "stderr": b""})
                second = raw.retain("graph", 1, "candidate",
                                    {"stdout": probe.SpooledOutput(source), "stderr": b""})
                self.assertNotEqual(first["stdout"]["path"], second["stdout"]["path"])
                self.assertEqual(b"abc", gzip.decompress((root / second["stdout"]["path"]).read_bytes()))
                with self.assertRaisesRegex(probe.DiagnosticError, "raw-evidence-limit"):
                    raw.retain("graph", 2, "candidate", {"stdout": b"z", "stderr": b""})
            with self.assertRaises(FileExistsError):
                raw.retain("graph", 1, "baseline", {"stdout": b"changed", "stderr": b""})

    def test_existing_or_symlink_spool_is_rejected_before_spawn(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "existing"
            target.write_bytes(b"keep")
            alias = root / "alias"
            alias.symlink_to(target.name)
            for path in (target, alias):
                with mock.patch.object(probe.subprocess, "Popen") as spawn:
                    with self.assertRaises(FileExistsError):
                        probe.run(["must-not-spawn"], root, {}, stdout_path=path)
                    spawn.assert_not_called()
            self.assertEqual(b"keep", target.read_bytes())


if __name__ == "__main__":
    unittest.main()

