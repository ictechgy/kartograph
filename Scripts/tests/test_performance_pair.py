"""고정 쌍 일정·신뢰구간·출력/입력 보호를 합성 대조로 검증한다."""

import argparse
from contextlib import redirect_stdout
import errno
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


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
        self.original_owner = self.owner.OwnedProcessGroup

    def cleanup_test_child(self, child):
        if child.returncode is None:
            try:
                lease = self.original_owner(child, self.owner.nonreaping_waiter(), self.owner.group_census())
                lease.close_signals()
                child.wait(timeout=2)
            except ChildProcessError:
                child.returncode = 0
        for stream in (child.stdout, child.stderr):
            if stream is not None:
                stream.close()

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

    def large_stream_program(self, stream):
        return ("import sys\noutput=sys." + stream + ".buffer\n"
                "for _ in range(528): output.write(b'x'*65536)\n"
                "output.write(b'END-EOF\\n')\noutput.flush()")

    def test_graph_stdout_above_32mib_retains_eof_digest_lossless_archive_and_full_parity(self):
        row = P.CaptureReceipt(command="graph")
        capture = self.capture()
        paths = capture.run([sys.executable, "-S", "-c", self.large_stream_program("stdout")],
                            "large-graph", row, stdout_limit=P.GRAPH_STDOUT_LIMIT)
        expected = hashlib.sha256()
        for _ in range(528):
            expected.update(b"x" * 65536)
        expected.update(b"END-EOF\n")
        self.assertEqual(0, row["exit"])
        self.assertEqual(33 * 1024**2 + 8, row["streams"]["stdout"]["bytes"])
        self.assertEqual(expected.hexdigest(), row["streams"]["stdout"]["sha256"])
        with paths[0].open("rb") as source:
            source.seek(-8, os.SEEK_END)
            self.assertEqual(b"END-EOF\n", source.read())
        comparison = self.root / "complete-comparison"
        shutil.copyfile(paths[0], comparison)
        P.preserve(paths, row, self.root)
        self.assertTrue(all(not path.exists() for path in paths))
        archive = self.root / row["streams"]["stdout"]["gzip_path"]
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), row["streams"]["stdout"]["gzip_sha256"])
        self.assertTrue(P.equal_raw(archive, comparison))
        with comparison.open("r+b") as changed:
            changed.seek(-1, os.SEEK_END)
            changed.write(b"!")
        self.assertFalse(P.equal_raw(archive, comparison))
        self.assertEqual(33 * 1024**2 + 8, capture.used)  # 압축/원문 삭제 뒤에도 처리량은 누적한다.
        print(json.dumps({"control": "large-graph-eof", "row": row, "processed_bytes": capture.used}))

    def test_default_stdout_and_graph_stderr_still_reject_above_32mib(self):
        for stream in ("stdout", "stderr"):
            row = P.CaptureReceipt(command="metrics" if stream == "stdout" else "graph")
            options = {} if stream == "stdout" else {"stdout_limit": P.GRAPH_STDOUT_LIMIT}
            with self.subTest(stream=stream), self.assertRaisesRegex(ValueError, "bounded output limit"):
                self.capture().run([sys.executable, "-S", "-c", self.large_stream_program(stream)],
                                   "limited-" + stream, row, **options)
            self.assertEqual(32 * 1024**2, row["streams"][stream]["bytes"])
            self.assertLess(row["exit"], 0)
            self.assertEqual("bounded output limit", row["primary_error"]["reason"])
            print(json.dumps({"control": "default-limit", "stream": stream, "row": row}))

    def test_graph_override_is_exclusive_and_bounded_before_spawn(self):
        for command, limit in (("metrics", 64 * 1024**2), ("graph", 64 * 1024**2 + 1), ("graph", True)):
            with self.subTest(command=command, limit=limit), patch.object(P.subprocess, "Popen") as spawn, \
                 self.assertRaisesRegex(ValueError, "invalid graph stdout limit"):
                self.capture().run(["never-launch"], "invalid-override", {"command": command}, stdout_limit=limit)
            spawn.assert_not_called()

    def test_graph_asymmetric_reservation_and_cumulative_bytes_refuse_before_spawn(self):
        reservation = 96 * 1024**2
        capture = self.capture(total_limit=reservation)
        first = P.CaptureReceipt(command="graph")
        capture.run([sys.executable, "-S", "-c", "import sys;print('one');print('two',file=sys.stderr)"],
                    "first-graph", first, stdout_limit=P.GRAPH_STDOUT_LIMIT)
        self.assertEqual(8, capture.used)
        with patch.object(P.subprocess, "Popen") as spawn, self.assertRaisesRegex(ValueError, "total output reservation"):
            capture.run(["never-launch"], "reserved-graph", {"command": "graph"}, stdout_limit=P.GRAPH_STDOUT_LIMIT)
        spawn.assert_not_called()
        # 같은 잔여 예산에서도 default stdout32 + stderr32는 예약할 수 있다.
        capture.run([sys.executable, "-S", "-c", "print('default')"], "remaining-default", {"command": "metrics"})
        self.assertEqual(16, capture.used)
        with patch.object(P.shutil, "disk_usage", return_value=SimpleNamespace(free=P.DISK_STOP + reservation - 1)):
            with patch.object(P.subprocess, "Popen") as spawn, self.assertRaisesRegex(ValueError, "disk stop"):
                self.capture().run(["never-launch"], "disk-graph", {"command": "graph"}, stdout_limit=P.GRAPH_STDOUT_LIMIT)
            spawn.assert_not_called()
            self.capture().run([sys.executable, "-S", "-c", "print('default')"], "disk-default", {"command": "metrics"})

    def test_timeout_reaps_owned_child_and_retains_output(self):
        row = {}
        with self.assertRaises(TimeoutError):
            self.capture().run([sys.executable, "-c", "import time; print('started',flush=True); time.sleep(60)"],
                               "timeout", row, timeout=.1)
        self.assertLess(row["exit"], 0)
        self.assertNotIn("cleanup_error", row)

    def assert_child_finished(self, child):
        self.assertIsNotNone(child.returncode)
        with self.assertRaises(ChildProcessError):
            os.waitpid(child.pid, os.WNOHANG)
        with self.assertRaises(ProcessLookupError):
            os.killpg(child.pid, 0)
        self.assertTrue(child.stdout.closed and child.stderr.closed)

    def test_owner_constructor_failure_uses_validated_unreaped_child_cleanup(self):
        original_popen, children = P.subprocess.Popen, []
        original_kill = self.owner.kill_group
        primary = RuntimeError("constructor failure")
        def spawn(*args, **kwargs):
            child = original_popen(*args, **kwargs)
            children.append(child)
            self.addCleanup(self.cleanup_test_child, child)
            return child
        for cleanup_failure in (False, True):
            def kill(child):
                original_kill(child)
                if cleanup_failure:
                    raise OSError(errno.EIO, "secondary after owned signal")
            row, before = {}, set(threading.enumerate())
            with self.subTest(cleanup_failure=cleanup_failure), \
                 patch.object(P.subprocess, "Popen", side_effect=spawn), \
                 patch.object(self.owner, "OwnedProcessGroup", side_effect=primary), \
                 patch.object(self.owner, "kill_group", side_effect=kill), \
                 self.assertRaises(RuntimeError) as caught:
                self.capture().run([sys.executable, "-S", "-c", "import time;time.sleep(10)"],
                                   "constructor-" + str(cleanup_failure), row)
            self.assertIs(primary, caught.exception)
            self.assertEqual("constructor failure", row["primary_error"]["reason"])
            self.assert_child_finished(children[-1])
            self.assertEqual(before, set(threading.enumerate()))
            if cleanup_failure:
                self.assertTrue(any(e["stage"] == "handoff-signal-close" for e in row["secondary_errors"]))
            print(json.dumps({"control": "constructor", "cleanup_failure": cleanup_failure, "row": row}))

    def test_real_sigterm_and_sigint_at_popen_assignment_seam_are_deferred_until_owned(self):
        original_popen = P.subprocess.Popen
        for number in (signal.SIGTERM, signal.SIGINT):
            def interrupted(*unused):
                raise KeyboardInterrupt("test termination")
            prior = signal.signal(number, interrupted)
            self.addCleanup(signal.signal, number, prior)
            children, row, before = [], {}, set(threading.enumerate())
            handlers = {n: signal.getsignal(n) for n in (signal.SIGTERM, signal.SIGINT)}
            def spawn(*args, **kwargs):
                child = original_popen(*args, **kwargs)
                children.append(child)
                self.addCleanup(self.cleanup_test_child, child)
                os.kill(os.getpid(), number)  # 실제 반환 전 assignment 공백에 도착한다.
                return child
            with self.subTest(signal=number), patch.object(P.subprocess, "Popen", side_effect=spawn), \
                 self.assertRaises(KeyboardInterrupt):
                self.capture().run([sys.executable, "-S", "-c", "import time;time.sleep(10)"],
                                   "assignment-" + str(number), row)
            self.assertEqual("KeyboardInterrupt", row["primary_error"]["type"])
            self.assert_child_finished(children[0])
            self.assertEqual(before, set(threading.enumerate()))
            self.assertEqual(handlers, {n: signal.getsignal(n) for n in handlers})
            print(json.dumps({"control": "assignment-signal", "signal": number, "row": row}))

    def test_constructor_primary_survives_pending_cancellation_until_fallback_reap(self):
        original_popen, children, row = P.subprocess.Popen, [], {}
        primary = RuntimeError("constructor before deferred interrupt")
        def spawn(*args, **kwargs):
            child = original_popen(*args, **kwargs)
            children.append(child)
            self.addCleanup(self.cleanup_test_child, child)
            return child
        def constructor(*unused):
            os.kill(os.getpid(), signal.SIGINT)
            raise primary
        with patch.object(P.subprocess, "Popen", side_effect=spawn), \
             patch.object(self.owner, "OwnedProcessGroup", side_effect=constructor), self.assertRaises(RuntimeError) as caught:
            self.capture().run([sys.executable, "-S", "-c", "import time;time.sleep(10)"], "constructor-pending", row)
        self.assertIs(primary, caught.exception)
        self.assert_child_finished(children[0])
        self.assertTrue(any(e["stage"] == "deferred-interruption" and e["type"] == "KeyboardInterrupt"
                            for e in row["secondary_errors"]))
        print(json.dumps({"control": "constructor-deferred-interruption", "row": row}))

    def test_constructor_reaped_or_lost_child_refuses_numeric_signal_and_unrelated_child_survives(self):
        original_popen = P.subprocess.Popen
        unrelated = original_popen([sys.executable, "-S", "-c", "import time;time.sleep(10)"], start_new_session=True)
        try:
            for reaped_returncode in (True, False):
                children, row = [], {}
                def spawn(*args, **kwargs):
                    child = original_popen(*args, **kwargs)
                    children.append(child)
                    self.addCleanup(self.cleanup_test_child, child)
                    return child
                def constructor(child, *unused):
                    if reaped_returncode:
                        child.wait(timeout=2)
                    else:
                        os.waitpid(child.pid, 0)  # ECHILD인데 Popen returncode는 아직 None이다.
                    raise RuntimeError("constructor after lease loss")
                with self.subTest(returncode=reaped_returncode), patch.object(P.subprocess, "Popen", side_effect=spawn), \
                     patch.object(self.owner, "OwnedProcessGroup", side_effect=constructor), \
                     patch.object(self.owner, "kill_group") as kill, self.assertRaises(RuntimeError):
                    self.capture().run([sys.executable, "-S", "-c", "pass"], "lost-" + str(reaped_returncode), row)
                kill.assert_not_called()
                self.assertNotIn("exit", row)
                self.assertTrue(any(e["stage"] == "handoff-signal-close" for e in row["secondary_errors"]))
                children[0].returncode = 0
                self.assert_child_finished(children[0])
                self.assertIsNone(unrelated.poll())
        finally:
            lease = self.owner.OwnedProcessGroup(unrelated, self.owner.nonreaping_waiter(), self.owner.group_census())
            lease.close_signals()
            unrelated.wait(timeout=2)

    def test_identity_git_signal_uses_repaired_ownership_and_private_sinks_are_removed(self):
        original_popen, children = P.subprocess.Popen, []
        temporary = self.root / "private"
        temporary.mkdir()
        def interrupted(*unused):
            raise KeyboardInterrupt("identity termination")
        prior = signal.signal(signal.SIGTERM, interrupted)
        self.addCleanup(signal.signal, signal.SIGTERM, prior)
        def spawn(*args, **kwargs):
            child = original_popen(*args, **kwargs)
            children.append(child)
            self.addCleanup(self.cleanup_test_child, child)
            os.kill(os.getpid(), signal.SIGTERM)
            return child
        with patch.object(P.subprocess, "Popen", side_effect=spawn), \
             patch.object(self.owner, "run", side_effect=AssertionError("inherited unsafe launch path")) as inherited, \
             self.assertRaises(KeyboardInterrupt) as caught:
            P.source_identity(self.owner, ROOT, "a" * 40,
                              {"PATH": os.environ.get("PATH", ""), "TMPDIR": str(temporary)}, time.monotonic() + 10)
        inherited.assert_not_called()
        self.assert_child_finished(children[0])
        self.assertEqual([], list(temporary.iterdir()))
        receipt = getattr(caught.exception, "identity_receipt")
        self.assertEqual(["git", "rev-parse", "HEAD"], receipt["argv"])
        self.assertEqual("KeyboardInterrupt", receipt["primary_error"]["type"])
        print(json.dumps({"control": "identity-signal", "row": receipt}))

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


class PairBudgetTests(unittest.TestCase):
    def delayed_setup(self, root, clock, setup_seconds, initially_waiting=False):
        owner = P.load_module("pair_delayed_owner", ROOT / "Scripts/verify-index-cost.py")
        child = SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(b"kept"), stderr=io.BytesIO())
        def wait(**unused):
            child.returncode = 0
            return 0
        child.wait = wait
        observations = []
        def observe(pid):
            observations.append(pid)
            clock[0] = 12
            return not initially_waiting or len(observations) > 1
        def census():
            clock[0] = setup_seconds
            return lambda pid: False
        return owner, child, observe, census

    def test_delayed_setup_cannot_refresh_absolute_deadline_for_waiting_or_ready_child(self):
        for waiting in (False, True):
            with self.subTest(initially_waiting=waiting), tempfile.TemporaryDirectory() as temporary:
                root, clock, row = Path(temporary), [0], {}
                owner, child, observe, census = self.delayed_setup(root, clock, 9, waiting)
                with patch.object(P.time, "monotonic", side_effect=lambda: clock[0]), \
                     patch.object(owner, "nonreaping_waiter", return_value=observe), \
                     patch.object(owner, "group_census", side_effect=census), \
                     patch.object(owner, "kill_group") as kill, patch.object(P.subprocess, "Popen", return_value=child), \
                     self.assertRaises(TimeoutError):
                    try:
                        P.Capture(root, root, {}, owner, 10).run(["synthetic-child"], "delayed", row)
                    finally:
                        print(json.dumps({"control": "absolute-child-deadline", "entry": 0, "setup_complete": 9,
                                          "observed": clock[0], "deadline": 10, "initially_waiting": waiting, "row": row}))
                self.assertEqual("TimeoutError", row["primary_error"]["type"])
                if not waiting:
                    self.assertEqual(3, row["seconds"])  # 측정 원점은 준비 뒤로 유지한다.
                self.assertEqual(b"kept", (root / row["streams"]["stdout"]["path"]).read_bytes())
                self.assertEqual(0, child.returncode)
                self.assertTrue(child.stdout.closed and child.stderr.closed)
                kill.assert_not_called()  # 합성 PID에는 실제 숫자 신호를 보내지 않는다.

    def test_setup_exhausting_command_timeout_refuses_spawn_even_with_driver_time_remaining(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, clock, row = Path(temporary), [0], {}
            owner, child, observe, census = self.delayed_setup(root, clock, 11)
            with patch.object(P.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(owner, "nonreaping_waiter", return_value=observe), \
                 patch.object(owner, "group_census", side_effect=census), patch.object(owner, "kill_group"), \
                 patch.object(P.subprocess, "Popen", return_value=child) as spawn, self.assertRaises(TimeoutError):
                try:
                    P.Capture(root, root, {}, owner, 100).run(["never-launch"], "exhausted-setup", row, timeout=10)
                finally:
                    print(json.dumps({"control": "setup-command-deadline", "entry": 0, "setup_complete": 11,
                                      "driver_deadline": 100, "timeout": 10, "row": row}))
            spawn.assert_not_called()
            self.assertEqual([], list(root.iterdir()))

    def test_expired_budget_prevents_identity_copy_seal_hash_archive_and_child_activity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "input"
            source.write_bytes(b"frozen")
            owner = SimpleNamespace(run=Mock())
            with patch.object(P.time, "monotonic", return_value=10), patch.object(P.subprocess, "Popen") as spawn:
                operations = [lambda: P.source_identity(owner, root, "a" * 40, {}, 10),
                    lambda: P.copy_input(source, root / "copy", 10), lambda: P.seal({"input": source}, 10),
                    lambda: P.digest(source, 10),
                    lambda: P.preserve([source, source], {"streams": {"stdout": {}, "stderr": {}}}, root, 10),
                    lambda: P.Capture(root, root, {}, owner, 10).run(["never-launch"], "expired", {})]
                for operation in operations:
                    with self.assertRaises(TimeoutError):
                        operation()
            owner.run.assert_not_called()
            spawn.assert_not_called()
            self.assertEqual([source], list(root.iterdir()))

    def test_identity_git_calls_use_remaining_absolute_budget_without_refresh(self):
        clock, timeouts = [0], []
        class IdentityCapture:
            def __init__(self, output, *unused, **options):
                self.output = output
            def run(self, argv, stem, row, timeout):
                timeouts.append(timeout)
                clock[0] += 4
                path = self.output / stem
                path.write_bytes(("a" * 40 if argv[1] == "rev-parse" else "").encode())
                row["exit"] = 0
                return [path]
        with patch.object(P.time, "monotonic", side_effect=lambda: clock[0]), \
             patch.object(P, "Capture", IdentityCapture), self.assertRaises(TimeoutError):
            P.source_identity(SimpleNamespace(), Path("."), "a" * 40, {}, 9)
        self.assertEqual([9, 5, 1], timeouts)

    def test_main_origin_precedes_argument_parsing_and_is_forwarded_to_qualifier(self):
        args = ["--baseline-root", "/baseline", "--baseline-sha", "a" * 40,
                "--repo-root", "/candidate", "--target-sha", "b" * 40,
                "--harness-sha", "c" * 40, "--output", "/output"]
        with patch.object(P.time, "monotonic", return_value=123), patch.object(P, "qualify", return_value=0) as qualify:
            self.assertEqual(0, P.main(args))
        self.assertEqual(123, qualify.call_args.kwargs["entered"])


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
            launched, budgets, checkpoints, clock, selected_limits = [], [], [], [100.0], []
            class FixtureCapture:
                def __init__(self, output, *unused, **unused_options):
                    self.output = output
                    budgets.append((unused[-1], unused_options.get("finalization_deadline")))
                def run(self, argv, stem, row, stdout_limit=None):
                    launched.append(stem)
                    selected_limits.append((row["command"], stdout_limit))
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
                    if failure == "row-budget":
                        clock[0] = 941
                        raise TimeoutError("fixture original deadline")
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
            def identity(owner, repo, sha, env, deadline=None):
                if failure == "setup-budget":
                    clock[0] = 941
                return {"head": sha, "tree": "c" * 40}
            original_preserve = P.preserve
            def preserve(paths, row, output, deadline=None):
                if failure == "row-budget":
                    checkpoint = output / "result.json"
                    checkpoints.append(json.loads(checkpoint.read_text()) if checkpoint.exists() else None)
                    clock[0] = 1001
                return original_preserve(paths, row, output) if deadline is None else original_preserve(paths, row, output, deadline)
            with patch.dict(os.environ, {"JAVA_HOME": str(java)}), patch.object(P, "Capture", FixtureCapture), \
                 patch.object(P, "load_module", side_effect=load), \
                 patch.object(P, "source_identity", side_effect=identity), \
                 patch.object(P.time, "monotonic", side_effect=lambda: clock[0]), patch.object(P, "preserve", side_effect=preserve), \
                 patch.object(P.sys, "platform", "linux"), patch.object(P.shutil, "disk_usage", side_effect=disk_usage), \
                 patch.object(P.zlib, "compressobj", side_effect=compressor), \
                 redirect_stdout(io.StringIO()):
                if failure:
                    with self.assertRaises(TimeoutError if failure in ("setup-budget", "row-budget") else ValueError):
                        P.qualify(args)
                else:
                    self.assertEqual(0, P.qualify(args))
            result = json.loads((args.output / "result.json").read_text())
            self.assertEqual(32 * 1024**2, result["protocol"]["default_stdout_limit_bytes"])
            self.assertEqual(64 * 1024**2, result["protocol"]["graph_stdout_limit_bytes"])
            self.assertEqual(32 * 1024**2, result["protocol"]["stderr_limit_bytes"])
            self.assertEqual(2 * 1024**3, result["protocol"]["total_raw_limit_bytes"])
            self.assertTrue(all(limit == (64 * 1024**2 if command == "graph" else None)
                                for command, limit in selected_limits))
            self.assertEqual("failed-or-incomplete" if failure else "speedup-demonstrated", result["status"])
            if failure:
                self.assertEqual(108, len(launched)) if failure == "last-row-disk" else self.assertLess(len(launched), 108)
                self.assertNotIn("statistics", result)
            else:
                self.assertEqual(108, len(launched))
                self.assertEqual(216, len(list(args.output.glob("*.gz"))))
                self.assertTrue(result["sealed_inputs_unchanged"])
                self.assertEqual([-6.0] * 8, result["statistics"]["paired_candidate_minus_baseline_seconds"])
                graph_count = sum(command == "graph" for command, _ in selected_limits)
                self.assertEqual(18, graph_count)
                self.assertGreater(graph_count * 64 * 1024**2, 512 * 1024**2)
                self.assertLess(graph_count * 64 * 1024**2, result["protocol"]["total_raw_limit_bytes"])
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
            if failure == "setup-budget":
                self.assertEqual([], launched)
                self.assertEqual("TimeoutError", result["error"]["type"])
            if failure == "row-budget":
                self.assertEqual(1, len(launched))
                self.assertEqual("fixture original deadline", result["error"]["reason"])
                self.assertIsNotNone(checkpoints[0], "primary receipt must precede archive")
                self.assertEqual("failed-or-incomplete", checkpoints[0]["status"])
                self.assertEqual("fixture original deadline", checkpoints[0]["rows"][-1]["primary_error"]["reason"])
                self.assertIn("final_integrity_error", result)
                self.assertTrue(any(e["stage"] == "archive" for e in result["rows"][-1]["secondary_errors"]))
                self.assertTrue((args.output / result["rows"][-1]["streams"]["stdout"]["path"]).is_file())
            if budgets:
                self.assertEqual([(940, 1000)], budgets)
            self.assertEqual(sys.version, result["python_version"])

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

    def test_setup_counts_against_driver_budget_and_row_failure_precedes_heavy_finalization(self):
        for failure in ("setup-budget", "row-budget"):
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
