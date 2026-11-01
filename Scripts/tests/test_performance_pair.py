"""고정 쌍 일정·신뢰구간·출력/입력 보호를 합성 대조로 검증한다."""

import argparse
from contextlib import redirect_stdout
import errno
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("performance_pair", ROOT / "Scripts/verify-performance-pair.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("test module loader required")
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)


def rows(candidate=2.0):
    return [dict(suite, command=name, exit=0, contract_failures=[],
                 seconds=3.0 if suite["arm"] == "baseline" else candidate)
            for suite in P.schedule() for name in P.NAMES]


class PairProtocolTests(unittest.TestCase):
    def test_fixed_eight_alternating_pairs_and_exactly_one_warmup_per_arm(self):
        suites = P.schedule()
        self.assertEqual(18, len(suites))
        self.assertEqual(["baseline", "candidate"], [s["arm"] for s in suites[:2]])
        self.assertTrue(all(s["warmup"] for s in suites[:2]))
        for pair in range(8):
            selected = suites[2 + pair * 2:4 + pair * 2]
            self.assertEqual([pair, pair], [s["pair"] for s in selected])
            self.assertEqual(["baseline", "candidate"] if pair % 2 == 0 else ["candidate", "baseline"],
                             [s["arm"] for s in selected])

    def test_predeclared_paired_mean_bootstrap_and_all_medians(self):
        result = P.statistics_for(rows())
        self.assertEqual([-6.0] * 8, result["paired_candidate_minus_baseline_seconds"])
        self.assertEqual([-6.0, -6.0], result["paired_bootstrap"]["interval_seconds"])
        self.assertEqual(20261101, result["paired_bootstrap"]["seed"])
        self.assertEqual(10000, result["paired_bootstrap"]["resamples"])
        self.assertEqual({"baseline": 18.0, "candidate": 12.0}, result["sum_command_medians_seconds"])
        self.assertTrue(result["speedup_demonstrated"])
        self.assertFalse(P.statistics_for(rows(3.0))["speedup_demonstrated"])
        self.assertFalse(P.statistics_for(rows(4.0))["speedup_demonstrated"])

    def test_missing_reordered_failed_and_nonfinite_observations_refuse(self):
        cases = [rows()[:-1]]
        reordered = rows()
        reordered[12], reordered[13] = reordered[13], reordered[12]
        cases.append(reordered)
        for key, value in (("exit", 2), ("exit", False), ("seconds", math.nan),
                           ("seconds", 0.0), ("seconds", True), ("contract_failures", ["changed"]),
                           ("pair", False), ("warmup", 1)):
            modified = rows()
            modified[15][key] = value
            cases.append(modified)
        for value in cases:
            with self.subTest(rows=len(value)), self.assertRaises(ValueError):
                P.statistics_for(value)

    def test_real_smoke_command_expression_is_used_without_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            actual = P.commands(ROOT / "Scripts/verify-analysis-smoke-gate.py", root, root / "project", root / "binary")
            repeated = P.load_module("pair_repeatability_test", ROOT / "Scripts/verify-cli-repeatability.py")
            expected = repeated.commands(root, root / "project")
            for row in expected:
                row[1][0] = str(root / "binary")
            self.assertEqual(expected, actual)
            mutated = root / "smoke.py"
            mutated.write_bytes((ROOT / "Scripts/verify-analysis-smoke-gate.py").read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "evaluator identity"):
                P.commands(mutated, root, root / "project", root / "binary")

    def test_sha_validation_happens_before_qualifier_or_child(self):
        args = ["--baseline-root", "/missing-baseline", "--baseline-sha", "A" * 40,
                "--repo-root", "/missing-candidate", "--target-sha", "b" * 40, "--harness-sha", "c" * 40,
                "--output", "/never-created"]
        with patch.object(P, "qualify") as qualify, self.assertRaises(SystemExit):
            P.main(args)
        qualify.assert_not_called()


class PairSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.owner = P.load_module("pair_owner_test", ROOT / "Scripts/verify-index-cost.py")

    def capture(self, **kwargs):
        return P.Capture(self.root, self.root, {"PATH": os.environ.get("PATH", "")},
                         self.owner, time.monotonic() + 20, **kwargs)

    def test_failed_child_has_complete_bounded_stream_receipts(self):
        row = {}
        paths = self.capture().run([sys.executable, "-c", "import sys; print('out'); print('err',file=sys.stderr); sys.exit(7)"], "failed", row)
        self.assertEqual(7, row["exit"])
        self.assertEqual([b"out\n", b"err\n"], [p.read_bytes() for p in paths])
        P.preserve(paths, row, self.root)
        self.assertTrue(all((self.root / row["streams"][s]["gzip_path"]).is_file() for s in ("stdout", "stderr")))

    def test_capture_stream_and_total_budgets_refuse(self):
        row = {}
        with self.assertRaises(ValueError):
            self.capture(stream_limit=1024, total_limit=2048).run(
                [sys.executable, "-c", "import sys; sys.stdout.write('x'*8192)"], "overflow", row)
        self.assertLessEqual(row["streams"]["stdout"]["bytes"], 1024)
        capture = self.capture(stream_limit=128, total_limit=256)
        capture.run([sys.executable, "-c", "print('one')"], "one", {})
        with patch.object(P.subprocess, "Popen") as launch, self.assertRaisesRegex(ValueError, "reservation"):
            capture.run([sys.executable, "-c", "print('two')"], "two", {})
        launch.assert_not_called()

    def test_timeout_reaps_owned_child_and_retains_output(self):
        row = {}
        with self.assertRaises(TimeoutError):
            self.capture().run([sys.executable, "-c", "import time; print('started',flush=True); time.sleep(60)"],
                               "timeout", row, timeout=.1)
        self.assertLess(row["exit"], 0)
        self.assertNotIn("cleanup_error", row)

    def test_timeout_remains_primary_when_receipt_digest_fails(self):
        row = {}
        with patch.object(P, "digest", side_effect=OSError("fixture digest failure")), \
             self.assertRaises(TimeoutError):
            self.capture().run([sys.executable, "-c", "import time; print('started',flush=True); time.sleep(60)"],
                               "timeout-digest", row, timeout=.1)
        self.assertEqual("TimeoutError", row["capture_error"])
        self.assertEqual("TimeoutError", row["primary_error"]["type"])
        self.assertTrue(any(e["type"] == "OSError" for e in row["secondary_errors"]))
        self.assertEqual(b"started\n", (self.root / row["streams"]["stdout"]["path"]).read_bytes())
        self.assertEqual(8, row["streams"]["stdout"]["bytes"])

    def test_reader_overflow_is_primary_before_secondary_digest_failure(self):
        row = {}
        with patch.object(P, "digest", side_effect=OSError(errno.EIO, "fixture digest failure")), \
             self.assertRaisesRegex(ValueError, "bounded output limit"):
            self.capture(stream_limit=1024, total_limit=2048).run(
                [sys.executable, "-c", "import sys; print('marker',file=sys.stderr,flush=True); sys.stdout.write('x'*8192)"],
                "overflow-digest", row)
        self.assertEqual("ValueError", row["primary_error"]["type"])
        self.assertEqual("bounded output limit", row["primary_error"]["reason"])
        self.assertTrue(any(e["type"] == "OSError" and e["stage"].startswith("stream-receipt")
                            for e in row["secondary_errors"]))
        self.assertTrue((self.root / row["streams"]["stdout"]["path"]).is_file())
        self.assertEqual(b"marker\n", (self.root / row["streams"]["stderr"]["path"]).read_bytes())

    def test_reader_sink_failure_survives_cleanup_failure_and_child_is_reaped(self):
        row, observed = {}, threading.Event()
        primary = OSError(errno.EIO, "fixture reader sink failure")
        original_owner, original_open = self.owner.OwnedProcessGroup, Path.open
        class FaultyCleanup(original_owner):
            def signal_group(self):
                observed.set()
                return super().signal_group()
            def close_signals(self):
                if not observed.wait(timeout=2):
                    raise RuntimeError("reader event did not arrive")
                super().close_signals()
                raise RuntimeError("fixture secondary cleanup failure")
        class FaultySink:
            def __init__(self, stream):
                self.stream = stream
            def write(self, data):
                self.stream.write(data[:1])
                raise primary
            def close(self):
                self.stream.close()
        def open_stream(path, *args, **kwargs):
            stream = original_open(path, *args, **kwargs)
            return FaultySink(stream) if path.name == "reader-cleanup.stdout" and args == ("xb",) else stream
        with patch.object(self.owner, "OwnedProcessGroup", FaultyCleanup), patch.object(Path, "open", new=open_stream), \
             self.assertRaises(OSError) as caught:
            self.capture().run([sys.executable, "-c", "print('reader output')"], "reader-cleanup", row)
        self.assertIs(primary, caught.exception)
        self.assertEqual("OSError", row["capture_error"])
        self.assertEqual("errno-5", row["primary_error"]["reason"])
        self.assertTrue(any(e["stage"] == "owned-signal-close" and e["type"] == "RuntimeError"
                            for e in row["secondary_errors"]))
        self.assertIsInstance(row["exit"], int)
        self.assertEqual(b"r", (self.root / row["streams"]["stdout"]["path"]).read_bytes())

    def test_earlier_main_timeout_survives_later_reader_failure(self):
        row, original_open = {}, Path.open
        class LateFaultySink:
            def __init__(self, stream):
                self.stream = stream
            def write(self, data):
                self.stream.write(data)
                raise OSError(errno.EIO, "reader after child termination")
            def close(self):
                self.stream.close()
        def open_stream(path, *args, **kwargs):
            stream = original_open(path, *args, **kwargs)
            return LateFaultySink(stream) if path.name == "timeout-reader.stdout" and args == ("xb",) else stream
        with patch.object(Path, "open", new=open_stream), self.assertRaises(TimeoutError):
            self.capture().run([sys.executable, "-c", "import time; print('partial',flush=True); time.sleep(60)"],
                               "timeout-reader", row, timeout=.1)
        self.assertEqual("TimeoutError", row["primary_error"]["type"])
        self.assertTrue(any(e["stage"] == "stream-reader" and e["type"] == "OSError"
                            for e in row["secondary_errors"]))
        self.assertEqual(b"partial\n", (self.root / row["streams"]["stdout"]["path"]).read_bytes())

    def test_archival_reservation_and_each_gzip_write_check_disk_stop(self):
        left, right = self.root / "left", self.root / "right"
        left.write_bytes(b"retain original stdout")
        right.write_bytes(b"retain original stderr")
        row = {"streams": {"stdout": {}, "stderr": {}}}
        with patch.object(P.shutil, "disk_usage", return_value=SimpleNamespace(free=P.DISK_STOP + 1)), \
             self.assertRaisesRegex(ValueError, "archive disk reservation"):
            P.preserve([left, right], row, self.root)
        self.assertTrue(left.is_file() and right.is_file())
        with (self.root / "sink").open("wb", buffering=0) as destination:
            sink = P.DiskCheckedArchive(destination, self.root)
            with patch.object(P.shutil, "disk_usage", return_value=SimpleNamespace(free=P.DISK_STOP + 1)), \
                 self.assertRaisesRegex(ValueError, "archive disk stop"):
                sink.write(b"must not cross stop")
        self.assertEqual(0, (self.root / "sink").stat().st_size)

    def test_disk_stop_prevents_launch_and_mutated_seal_refuses(self):
        for free in (P.DISK_STOP - 1, P.DISK_STOP + 1):
            with self.subTest(free=free), \
                 patch.object(P.shutil, "disk_usage", return_value=SimpleNamespace(free=free)), \
                 patch.object(P.subprocess, "Popen") as launch, self.assertRaisesRegex(ValueError, "disk stop"):
                self.capture().run([sys.executable, "-c", "pass"], "disk", {})
            launch.assert_not_called()
        source = self.root / "source"
        source.write_bytes(b"original")
        sealed = P.seal({"input": source})
        source.write_bytes(b"mutated")
        for full in (False, True):
            with self.subTest(full=full), self.assertRaisesRegex(ValueError, "sealed inputs changed"):
                P.verify_seal({"input": source}, sealed, full=full)

    def test_full_raw_parity_includes_tail_and_new_directory_entries(self):
        left, right = self.root / "left", self.root / "right"
        left.write_bytes(b"a" * 70000 + b"left")
        right.write_bytes(b"a" * 70000 + b"right")
        row = {"streams": {"stdout": {}, "stderr": {}}}
        dummy = self.root / "empty"
        dummy.write_bytes(b"")
        P.preserve([left, dummy], row, self.root)
        self.assertFalse(P.equal_raw(self.root / row["streams"]["stdout"]["gzip_path"], right))
        sealed = P.seal({"directory": self.root})
        (self.root / "new").write_bytes(b"unexpected")
        with self.assertRaisesRegex(ValueError, "sealed inputs changed"):
            P.verify_seal({"directory": self.root}, sealed)


class PairFixtureFlowTests(unittest.TestCase):
    """합성 출력/고정 수치만 사용하며 실제 CLI 속도는 측정하지 않는다."""
    def exercise(self, failure=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline, candidate, java = root / "baseline", root / "candidate", root / "jdk"
            for repo in (baseline, candidate):
                for module in P.MODULES:
                    directory = repo / module / "build/classes/kotlin/main"
                    directory.mkdir(parents=True)
                    (directory / "Fixture.class").write_bytes(b"synthetic input; never executed")
                install = repo / "cli/build/install/kartograph"
                (install / "bin").mkdir(parents=True)
                (install / "bin/kartograph").write_bytes(b"synthetic launcher; never executed")
                (install / "lib").mkdir()
                (install / "lib/fixture.jar").write_bytes(b"synthetic runtime; never executed")
                (repo / "Scripts").mkdir()
                (repo / "Scripts/verify-analysis-smoke-gate.py").write_bytes(
                    (ROOT / "Scripts/verify-analysis-smoke-gate.py").read_bytes())
                (repo / ".kartograph.yml").write_text("fixture rules\n")
                (repo / ".kartograph-self.pro").write_text("fixture roots\n")
            for name in ("release", "bin/java", "lib/modules", "lib/server/libjvm.so", "lib/server/libjvm.dylib"):
                path = java / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"synthetic JDK identity; never executed")
            args = argparse.Namespace(baseline_root=baseline, baseline_sha="a" * 40,
                repo_root=candidate, target_sha="b" * 40, harness_sha="c" * 40, output=root / "capture")
            commands = {"graph": "\n".join(f'n{i} [shape=box]' for i in range(2000)) + "\n",
                        "dead": "", "dead_private": "", "cycles": "0 findings\n",
                        "rules": "0 findings, 0 unassigned\n", "metrics": "row\n" * 6}
            launched = []
            class FixtureCapture:
                def __init__(self, output, *unused):
                    self.output = output
                def run(self, argv, stem, row):
                    launched.append(stem)
                    row.update(argv=list(map(str, argv)), exit=2 if failure in ("child", "semantic-archive") and row["arm"] == "candidate" else 0,
                               seconds=3.0 if row["arm"] == "baseline" else 2.0)
                    text = commands[row["command"]]
                    if failure == "output" and row["arm"] == "candidate":
                        text += "mismatch\n"
                    row["streams"] = {}
                    for name, data in (("stdout", text.encode()), ("stderr", b"")):
                        path = self.output / (stem + "." + name)
                        path.write_bytes(data)
                        row["streams"][name] = {"bytes": len(data), "sha256": P.digest(path), "path": path.name}
                    if failure == "mutation":
                        (self.output / "corpus/.kartograph-self.pro").write_text("changed input\n")
            original_load = P.load_module
            environment = original_load("fixture_environment", ROOT / "Scripts/verify-cli-repeatability.py")
            def load(name, path):
                if name == "pair_environment":
                    return SimpleNamespace(cli_environment=environment.cli_environment,
                                           resources=lambda: {"policy": {"fixture": True}})
                return original_load(name, path)
            original_compressor = P.zlib.compressobj
            def compressor(*args, **kwargs):
                if failure == "semantic-archive" and launched and "candidate" in launched[-1]:
                    raise OSError("fixture gzip failure")
                return original_compressor(*args, **kwargs)
            def disk_usage(path):
                return SimpleNamespace(free=P.DISK_STOP - 1 if failure == "last-row-disk" and len(launched) == 108
                                       else 100 * 1024**3)
            with patch.dict(os.environ, {"JAVA_HOME": str(java)}), patch.object(P, "Capture", FixtureCapture), \
                 patch.object(P, "load_module", side_effect=load), \
                 patch.object(P, "source_identity", side_effect=lambda owner, repo, sha, env: {"head": sha, "tree": "c" * 40}), \
                 patch.object(P.sys, "platform", "linux"), patch.object(P.shutil, "disk_usage", side_effect=disk_usage), \
                 patch.object(P.zlib, "compressobj", side_effect=compressor), \
                 redirect_stdout(io.StringIO()):
                if failure:
                    with self.assertRaises(ValueError):
                        P.qualify(args)
                else:
                    self.assertEqual(0, P.qualify(args))
            result = json.loads((args.output / "result.json").read_text())
            self.assertEqual("failed-or-incomplete" if failure else "speedup-demonstrated", result["status"])
            if failure:
                self.assertEqual(108, len(launched)) if failure == "last-row-disk" else self.assertLess(len(launched), 108)
                self.assertNotIn("statistics", result)
            else:
                self.assertEqual(108, len(launched))
                self.assertEqual(216, len(list(args.output.glob("*.gz"))))
                self.assertTrue(result["sealed_inputs_unchanged"])
                self.assertEqual([-6.0] * 8, result["statistics"]["paired_candidate_minus_baseline_seconds"])
            if failure == "mutation":
                self.assertIn("final_integrity_error", result)
            if failure == "semantic-archive":
                row = result["rows"][-1]
                self.assertEqual("failed child or semantic contract", row["primary_error"]["reason"])
                self.assertTrue(any(e["stage"] == "archive" and e["type"] == "OSError" for e in row["secondary_errors"]))
                self.assertTrue((args.output / row["streams"]["stdout"]["path"]).is_file())
            if failure == "last-row-disk":
                row = result["rows"][-1]
                self.assertIn("archive disk reservation", row["primary_error"]["reason"])
                self.assertTrue((args.output / row["streams"]["stdout"]["path"]).is_file())

    def test_complete_synthetic_protocol_preserves_all_rows_and_raw_outputs(self):
        self.exercise()

    def test_output_child_and_input_mutation_controls_abort_without_statistics(self):
        for failure in ("output", "child", "mutation"):
            with self.subTest(failure=failure):
                self.exercise(failure)

    def test_semantic_failure_survives_gzip_failure_and_last_row_disk_stop_cannot_pass(self):
        for failure in ("semantic-archive", "last-row-disk"):
            with self.subTest(failure=failure):
                self.exercise(failure)

    def test_unsupported_host_is_recorded_before_copy_seal_or_measurement(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "capture"
            args = argparse.Namespace(baseline_root=Path("/not-read-baseline"), baseline_sha="a" * 40,
                repo_root=Path("/not-read-candidate"), target_sha="b" * 40, harness_sha="c" * 40, output=output)
            with patch.object(P.sys, "platform", "darwin"), patch.object(P, "copy_input") as copy, \
                 patch.object(P, "seal") as seal, patch.object(P, "Capture") as capture, \
                 patch.object(P, "source_identity") as source, redirect_stdout(io.StringIO()):
                self.assertEqual(2, P.qualify(args))
            for operation in (copy, seal, capture, source):
                operation.assert_not_called()
            result = json.loads((output / "result.json").read_text())
            self.assertEqual("unsupported-os", result["status"])
            self.assertEqual({"required": "linux", "observed": "darwin", "supported": False}, result["platform_preflight"])
            self.assertEqual([], result["rows"])


if __name__ == "__main__":
    unittest.main()
