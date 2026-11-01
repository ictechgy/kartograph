"""Linux 진단의 lease·입력 식별·수치 의미·원문 비노출을 검증한다."""
import copy
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("linux_runtime_cost", ROOT / "Scripts/verify-linux-runtime-cost.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("module specification with a loader is required")
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


def event(kind, **values):
    return {"type": "jdk." + kind, "values": {"startTime": "2026-10-25T19:00:00.000000001Z", **values}}


class RuntimeCostTest(unittest.TestCase):
    def test_identity_ignores_only_mtime_and_detects_content_path_size_and_extra_rows(self):
        original = [{"path": "A.class", "size": 1, "mtimeNs": 10, "sha256": "a" * 64}]
        changed = copy.deepcopy(original)
        changed[0]["mtimeNs"] = 99
        self.assertEqual(runtime.seal(original), runtime.seal(changed))
        for key, value in (("path", "B.class"), ("size", 2), ("sha256", "b" * 64)):
            changed = copy.deepcopy(original)
            changed[0][key] = value
            self.assertNotEqual(runtime.seal(original), runtime.seal(changed))
        self.assertNotEqual(runtime.seal(original), runtime.seal(original * 2))

    def test_aliases_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "actual").write_bytes(b"x")
            (root / "alias").symlink_to(root / "actual")
            with self.assertRaisesRegex(runtime.p.DiagnosticError, "identity-symlink"):
                runtime.reject_aliases(root)

    def test_complete_input_seal_is_checked_before_any_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, private, output = [root / x for x in ("repo", "private", "output")]
            for directory in (repo, private, output):
                directory.mkdir()
            for module in runtime.p.MODULES:
                path = repo / module / "build/classes/kotlin/main"
                path.mkdir(parents=True)
                (path / "One.class").write_bytes(b"different class")
            (repo / ".kartograph.yml").write_text("rules: []\n")
            (repo / ".kartograph-self.pro").write_text("-keep class X\n")
            with mock.patch.dict(os.environ, {"JAVA_HOME": str(root)}), \
                    mock.patch.object(runtime, "identities", return_value={"roots": "changed"}), \
                    mock.patch.object(runtime.p, "run") as run:
                with self.assertRaisesRegex(runtime.p.DiagnosticError, "original-linux-identity-mismatch"):
                    runtime.capture(repo, output, private, {"canonicalSha256": {"roots": "original"}}, {}, 999999999)
                run.assert_not_called()

    def test_proc_stat_handles_spaces_parentheses_and_preserves_epoch(self):
        fields = ["S"] + ["0"] * 30
        for i, value in {7: 3, 9: 4, 11: 12, 12: 6, 17: 5, 19: 999, 21: 100}.items():
            fields[i] = str(value)
        row = runtime.parse_stat("12 (private ) token) " + " ".join(fields))
        self.assertEqual((12, 6, 999, "unknown"), (row["userTicks"], row["systemTicks"], row["startTicks"], row["role"]))
        self.assertNotIn("private", json.dumps(row))
        for invalid in ("", "1 (x) S 1", "1 (x) " + " ".join(fields).replace("999", "-1")):
            with self.assertRaises(runtime.p.DiagnosticError):
                runtime.parse_stat(invalid)

    def test_same_tid_different_start_times_are_distinct_and_raw_ids_are_not_reported(self):
        observer = runtime.Observer(999999999, Path("/tmp"))
        observer.rows = {("12345", 1): ("compiler", [1, 2, 3, 4, 5]),
                         ("12345", 2): ("unknown", [2, 3, 4, 5, 6])}
        result = observer.report()
        self.assertEqual(2, result["threadIncarnations"])
        self.assertNotIn("12345", json.dumps(result))
        self.assertEqual(4, result["threadLifetimeObservedLowerBounds"]["compiler"][3])

    def test_zero_exit_above_final_rss_cap_is_rejected_after_run_cleanup(self):
        result = {"exit": 0, "stdout": b"", "stderr": b"", "wallNanos": 1,
                  "rss": {"status": "observed", "peakBytes": 3 * 1024 * runtime.MIB + 1}, "phase": "reference-dead"}
        with mock.patch.object(runtime.p, "run", return_value=result) as run:
            with self.assertRaisesRegex(runtime.p.DiagnosticError, "owned-final-rss-limit") as caught:
                runtime.checked([], Path("/tmp"), {}, 999999999, Path("/tmp"), "reference-dead")
            self.assertEqual(0, caught.exception.receipt["exit"])
            run.assert_called_once()

    def test_private_cleanup_preserves_primary_and_cleanup_only_failure_is_not_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            fake = types.SimpleNamespace(name=str(Path(temporary) / "private"), cleanup=mock.Mock(side_effect=OSError("private secret")))
            original = runtime.p.DiagnosticError("original-child-failure", {"exit": 2})
            report = {}
            def action(_):
                raise original
            with mock.patch.dict(os.environ, {"RUNNER_TEMP": temporary}), \
                    mock.patch.object(runtime.tempfile, "TemporaryDirectory", return_value=fake):
                with self.assertRaises(runtime.p.DiagnosticError) as caught:
                    runtime.private_workspace(Path(temporary) / "public", report, action)
                self.assertIs(original, caught.exception)
                self.assertTrue(report["privateCleanupFailure"])
                self.assertNotIn("secret", json.dumps(report))
                with self.assertRaisesRegex(runtime.p.DiagnosticError, "private-cleanup-failed"):
                    runtime.private_workspace(Path(temporary) / "public", {}, lambda _: None)

    def test_graph_uses_current_spool_for_complete_output_above_ordinary_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture = runtime.RuntimeGraphCapture(Path(temporary))
            self.assertEqual(64 * runtime.MIB, capture.limit)
            self.assertEqual(32 * runtime.MIB, runtime.p.LIMIT)
            chunk = b"x" * 65536
            expected = hashlib.sha256()
            for _ in range(528):
                expected.update(chunk)
            expected.update(b"tail")
            try:
                result = runtime.p.run([sys.executable, "-c",
                    "import sys; [sys.stdout.buffer.write(b'x'*65536) for _ in range(528)]; sys.stdout.buffer.write(b'tail')"],
                    temporary, dict(os.environ), stdout_path=capture.path, stdout_limit=capture.limit)
                self.assertEqual(0, result["exit"])
                self.assertIsInstance(result["stdout"], runtime.p.SpooledOutput)
                self.assertEqual(528 * 65536 + 4, len(result["stdout"]))
                self.assertEqual(expected.hexdigest(), runtime.p.output_digest(result["stdout"]))
            finally:
                capture.cleanup()
            self.assertFalse(capture.directory.exists())
            capture.cleanup()

    def test_graph_spool_rejects_output_above_64mib_and_cleans_owned_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            capture = runtime.RuntimeGraphCapture(Path(temporary))
            try:
                with self.assertRaisesRegex(runtime.p.DiagnosticError, "child-output-limit") as caught:
                    runtime.p.run([sys.executable, "-c",
                        "import sys; [sys.stdout.buffer.write(b'x'*65536) for _ in range(1025)]"],
                        temporary, dict(os.environ), stdout_path=capture.path, stdout_limit=capture.limit)
                self.assertLessEqual(caught.exception.receipt["stdout"]["bytes"], capture.limit)
            finally:
                capture.cleanup()
            self.assertFalse(capture.directory.exists())

    def test_checked_passes_spool_identity_and_cap_to_current_owned_runner(self):
        result = {"exit": 0, "stdout": b"", "stderr": b"", "wallNanos": 1,
                  "rss": {"status": "observed", "peakBytes": 1}, "phase": "reference-graph"}
        with tempfile.TemporaryDirectory() as temporary:
            capture = runtime.RuntimeGraphCapture(Path(temporary))
            try:
                with mock.patch.object(runtime.p, "run", return_value=result) as run:
                    runtime.checked([], Path(temporary), {}, 999999999, Path(temporary), "reference-graph", capture)
                self.assertEqual(capture.path, run.call_args.kwargs["stdout_path"])
                self.assertEqual(64 * runtime.MIB, run.call_args.kwargs["stdout_limit"])
                self.assertIsInstance(run.call_args.kwargs["observer"], runtime.Observer)
                self.assertNotIn("capture", run.call_args.kwargs)
            finally:
                capture.cleanup()

    def test_public_worker_names_mangled_methods_and_leaf_product_frames_survive(self):
        for name in ("kartograph-index-worker", "kartograph-index-worker-2", "kartograph-inde"):
            self.assertEqual("application-including-native", runtime.role(name))
        frames = [{"method": {"type": {"name": "java/util/HashMap"}, "name": "getNode"}},
                  {"method": {"type": {"name": "dev/kartograph/core/NodeId"}, "name": "hashCode-impl"}}]
        item = event("ExecutionSample", sampledThread={"javaName": "kartograph-index-worker"}, stackTrace={"frames": frames})
        ledger, summary = runtime.sanitize_events({"recording": {"events": [item]}})
        self.assertEqual("java/util/HashMap.getNode", ledger[0]["leafMethod"])
        self.assertEqual("dev/kartograph/core/NodeId.hashCode-impl", ledger[0]["nearestProductMethod"])
        self.assertEqual("application-including-native", ledger[0]["role"])
        self.assertIn("hashCode-impl", json.dumps(summary))
        self.assertEqual("dev/kartograph/index/Facts.methodId-re0_fAI", runtime.method_label(
            {"type": {"name": "dev/kartograph/index/Facts"}, "name": "methodId-re0_fAI"}))

    def test_observer_error_does_not_claim_loss_of_child_ownership(self):
        process = types.SimpleNamespace(pid=123, returncode=None)
        owned = runtime.p.OwnedProcessGroup(process, lambda _: False, lambda _: False)
        observer = types.SimpleNamespace(sample=mock.Mock(side_effect=runtime.p.DiagnosticError("observer-limit")))
        with self.assertRaisesRegex(runtime.p.DiagnosticError, "observer-limit"):
            owned.ready(observer)
        self.assertFalse(owned.lost)
        self.assertTrue(owned.signal_open)

    def test_real_child_is_observed_before_reap_and_observer_failure_still_reaps(self):
        records = []
        class Observation:
            def __init__(self, reject=False):
                self.stopped, self.reject = False, reject
            def sample(self, pid, terminal):
                self.assert_live()
                records.append(("sample", terminal))
                if self.reject:
                    raise runtime.p.DiagnosticError("observer-limit")
            def assert_live(self):
                if self.stopped:
                    raise AssertionError("sample after reap")
            def reaped(self, usage):
                self.stopped = True
                records.append(("reaped", usage.ru_utime >= 0))
        with tempfile.TemporaryDirectory() as temporary:
            result = runtime.p.run([sys.executable, "-c", "print('exact')"], temporary, dict(os.environ), observer=Observation())
            self.assertEqual(b"exact\n", result["stdout"])
            self.assertEqual("reaped", records[-1][0])
            records.clear()
            with self.assertRaisesRegex(runtime.p.DiagnosticError, "observer-limit"):
                runtime.p.run([sys.executable, "-c", "import time;time.sleep(10)"], temporary, dict(os.environ), observer=Observation(True))
            self.assertEqual("reaped", records[-1][0])

    def test_stopped_observer_never_reads_proc(self):
        observer = runtime.Observer(999999999, Path("/tmp"))
        observer.stopped = True
        with mock.patch.object(os, "open") as opened:
            with self.assertRaisesRegex(runtime.p.DiagnosticError, "observer-after-terminal"):
                observer.sample(123, False)
            opened.assert_not_called()

    def test_observer_failure_keeps_reap_callback_failure_and_child_is_reaped(self):
        class Observation:
            pid = None
            def sample(self, pid, terminal):
                self.pid = pid
                raise runtime.p.DiagnosticError("observer-primary", {"stage": "sample"})
            def reaped(self, usage):
                raise runtime.p.DiagnosticError("observer-secondary", {"stage": "reaped"})
        observation = Observation()
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(runtime.p.DiagnosticError, "observer-primary") as caught:
                runtime.p.run([sys.executable, "-c", "import time;time.sleep(10)"], temporary,
                              dict(os.environ), observer=observation)
        self.assertEqual({"stage": "sample"}, caught.exception.receipt["observerFailure"])
        self.assertEqual({"reason": "observer-secondary", "receipt": {"stage": "reaped"}},
                         caught.exception.receipt["observerReapFailure"])
        pid = observation.pid
        if not isinstance(pid, int):
            self.fail("observer did not receive the owned child pid")
        with self.assertRaises(ChildProcessError):
            os.waitpid(pid, os.WNOHANG)

    def test_proc_sampler_tracks_reused_tid_epoch_and_disappearing_thread(self):
        def stat_text(epoch, user=12):
            fields = ["S"] + ["0"] * 30
            for i, value in {11: user, 12: 2, 17: 1, 19: epoch, 21: 10}.items():
                fields[i] = str(value)
            return "42 (C2 CompilerThread0) " + " ".join(fields)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            proc = root / "proc"
            thread = proc / "task/42"
            thread.mkdir(parents=True)
            (proc / "stat").write_text(stat_text(1))
            (thread / "stat").write_text(stat_text(1))
            (thread / "schedstat").write_text("300 40 6")
            original = os.open
            def opened(path, *args, **kwargs):
                return original(proc if path == "/proc/42" else path, *args, **kwargs)
            observer = runtime.Observer(999999999, root)
            try:
                with mock.patch.object(os, "open", side_effect=opened), \
                        mock.patch.object(runtime.resource, "prlimit", return_value=(-1, -1), create=True):
                    observer.sample(42, False)
                    self.assertEqual(1, len(observer.rows))
                    (thread / "stat").write_text(stat_text(2, 3))
                    observer.next = 0
                    observer.sample(42, False)
                    self.assertEqual(2, len(observer.rows))
                    (thread / "stat").unlink()
                    observer.next = 0
                    observer.sample(42, True)
                    self.assertEqual(1, observer.missing)
                    self.assertTrue(observer.stopped)
            finally:
                observer.close()

    def test_jfr_is_reconstructed_without_raw_strings_and_numerics_do_not_become_cpu(self):
        private = "private-customer-token"
        method = {"type": {"name": "private/customer/Secret"}, "name": private, "descriptor": private}
        events = [event("Compilation", method=method, duration="PT0.002S", compileLevel=4, succeded=True, eventThread={"javaName": private}),
                  event("NativeMethodSample", sampledThread={"javaName": private}, stackTrace={"truncated": True, "frames": [{"method": method}]}),
                  event("ThreadCPULoad", user=.2, system=.1, eventThread={"javaName": private}),
                  event("GCPhasePause", duration="PT0.001S", name=private),
                  event("DataLoss", amount=0, total=0, secret=private)]
        ledger, result = runtime.sanitize_events({"recording": {"events": events}})
        data = runtime.encoded([ledger, result])
        self.assertNotIn(private.encode(), data)
        self.assertNotIn(b"private/customer", data)
        self.assertEqual(2_000_000, result["compilationEventWallNs"])
        self.assertEqual(1_000_000, result["gcPauseUnionNs"])
        self.assertEqual(1, result["truncatedStackSamples"])
        self.assertIn("unavailable", result["nativeCStackAttribution"])
        self.assertNotIn("nativeCpuSeconds", result)

    def test_overlapping_pause_intervals_are_unioned_not_added(self):
        self.assertEqual(15, runtime.pause_union([(0, 10), (5, 15), (7, 9)]))

    def test_jfr_schema_bounds_refuse_invalid_events(self):
        invalid = [event("JVMInformation", jvmArguments="secret"),
                   event("Compilation", method={}, duration="PT1S", compileLevel=4, succeeded=True),
                   event("ThreadCPULoad", user=float("nan"), system=0),
                   event("GCPhasePause", duration="PT121S"),
                   event("ExecutionSample", stackTrace={"frames": [{}] * 33})]
        for row in invalid:
            with self.subTest(kind=row["type"]):
                with self.assertRaises((runtime.p.DiagnosticError, KeyError)):
                    runtime.sanitize_events({"recording": {"events": [row]}})
        with self.assertRaisesRegex(runtime.p.DiagnosticError, "jfr-event-limit"):
            runtime.sanitize_events({"recording": {"events": [event("DataLoss", amount=0, total=0)] * 20001}})

    def test_duration_and_timestamp_are_exact_and_negative_or_oversized_refuse(self):
        self.assertEqual(1, runtime.duration("PT0.000000001S"))
        self.assertEqual(1, runtime.timestamp("2026-10-25T00:00:00.000000001Z") % 1_000_000_000)
        for value in ("PT-1S", "PT", "PT121S", "secret"):
            with self.assertRaises(runtime.p.DiagnosticError):
                runtime.duration(value)

    def test_cli_rejects_unsealed_target_before_any_child_or_private_capture(self):
        import io
        for target in ("0" * 40, "not-a-commit"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary) / "public"
                with mock.patch.object(runtime.sys, "platform", "linux"), \
                        mock.patch.object(runtime.os, "uname", return_value=types.SimpleNamespace(machine="x86_64")), \
                        mock.patch.object(runtime.resource, "prlimit", create=True), \
                        mock.patch.dict(os.environ, {"JAVA_HOME": temporary, "RUNNER_TEMP": temporary}), \
                        mock.patch.object(runtime.signal, "signal"), \
                        mock.patch.object(runtime, "capture") as capture, \
                        mock.patch.object(runtime.p, "run") as run, \
                        mock.patch("sys.stdout", io.StringIO()):
                    code = runtime.main(["--repo-root", temporary, "--target-sha", target, "--output", str(output)])
                self.assertEqual(1, code)
                self.assertEqual("unreviewed-target", json.loads((output / "result.json").read_text())["reason"])
                capture.assert_not_called()
                run.assert_not_called()
                self.assertEqual(["result.json"], [path.name for path in output.iterdir()])


class ObserverFailurePriorityTest(unittest.TestCase):
    class FailingReap:
        def __init__(self, sample_error=None, reap_error=None):
            self.pid, self.reap_calls = None, 0
            self.sample_error, self.reap_error = sample_error, reap_error

        def sample(self, pid, terminal):
            self.pid = pid
            if self.sample_error is not None:
                raise self.sample_error

        def reaped(self, usage):
            self.reap_calls += 1
            raise self.reap_error if self.reap_error is not None else runtime.p.DiagnosticError(
                "secondary-reap-failure", {"stage": "reaped"})

    def record(self, error, stdout=None):
        print(json.dumps({"test": self.id(), "reason": str(error), "receipt": error.receipt,
                          "retainedStdoutBase64": base64.b64encode(stdout).decode() if stdout is not None else None}))

    def assert_secondary(self, error, reason):
        self.assertEqual(reason, str(error))
        self.assertEqual({"reason": "secondary-reap-failure", "receipt": {"stage": "reaped"}},
                         error.receipt["observerReapFailure"])

    def assert_reaped(self, observer):
        pid = observer.pid
        if not isinstance(pid, int):
            self.fail("observer did not receive the owned child pid")
        self.assertEqual(1, observer.reap_calls)
        with self.assertRaises(ChildProcessError):
            os.waitpid(pid, os.WNOHANG)

    def test_real_timeout_preserves_child_reason_output_and_secondary_reap_failure(self):
        observer = self.FailingReap()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "stdout.bin"
            with self.assertRaises(runtime.p.DiagnosticError) as caught:
                runtime.p.run([sys.executable, "-S", "-c",
                    "import os,time;os.write(1,b'before-timeout');os.write(2,b'error');time.sleep(10)"],
                    temporary, dict(os.environ), timeout=0.1, stdout_path=path, observer=observer)
            raw = path.read_bytes()
            self.record(caught.exception, raw)
            self.assert_secondary(caught.exception, "child-timeout")
            self.assertEqual(b"before-timeout", raw)
            self.assertEqual({"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()},
                             caught.exception.receipt["stdout"])
            self.assertEqual(hashlib.sha256(b"error").hexdigest(), caught.exception.receipt["stderr"]["sha256"])
        self.assert_reaped(observer)

    def test_real_overflow_preserves_child_reason_spool_and_secondary_reap_failure(self):
        observer = self.FailingReap()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "stdout.bin"
            with self.assertRaises(runtime.p.DiagnosticError) as caught:
                runtime.p.run([sys.executable, "-S", "-c",
                    "import os,time;os.write(2,b'error');os.write(1,b'A'*65536);os.write(1,b'B'*65536);time.sleep(10)"],
                    temporary, dict(os.environ), stdout_path=path, stdout_limit=65536, observer=observer)
            raw = path.read_bytes()
            self.record(caught.exception, raw)
            self.assert_secondary(caught.exception, "child-output-limit")
            self.assertEqual(b"A" * 65536, raw)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), caught.exception.receipt["stdout"]["sha256"])
            self.assertEqual(hashlib.sha256(b"error").hexdigest(), caught.exception.receipt["stderr"]["sha256"])
        self.assert_reaped(observer)

    def controlled_child(self, observer, stdout=None, wait_error=False, late_reader=False, limit=runtime.p.LIMIT):
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=stdout or io.BytesIO(b"kept"),
                                      stderr=io.BytesIO(b"error"))
        usage = types.SimpleNamespace(ru_maxrss=4096)
        class Reader:
            def __init__(self, target, args, daemon):
                self.target, self.args = target, args
            def start(self):
                self.target(*self.args)
            def join(self, timeout):
                pass
            def is_alive(self):
                return late_reader
        with mock.patch.object(runtime.p.subprocess, "Popen", return_value=child), \
                mock.patch.object(runtime.p, "nonreaping_waiter", return_value=lambda pid: True), \
                mock.patch.object(runtime.p, "group_census", return_value=lambda pid: True), \
                mock.patch.object(runtime.p, "kill_group"), \
                mock.patch.object(runtime.p.os, "wait4", return_value=(child.pid, 0, usage)), \
                mock.patch.object(runtime.p.threading, "Thread", Reader):
            if wait_error:
                with mock.patch.object(runtime.p.OwnedProcessGroup, "ready", side_effect=OSError("controlled-wait")):
                    runtime.p.run(["controlled-child"], ROOT, {}, observer=observer, limit=limit)
            else:
                runtime.p.run(["controlled-child"], ROOT, {}, observer=observer, limit=limit)

    def test_read_failure_preserves_prior_bytes_and_secondary_reap_failure(self):
        class FailedRead:
            first = True
            def read(self, size):
                if self.first:
                    self.first = False
                    return b"before-read-failure"
                raise OSError("controlled-read")
            def close(self):
                pass
        with self.assertRaises(runtime.p.DiagnosticError) as caught:
            self.controlled_child(self.FailingReap(), stdout=FailedRead())
        self.record(caught.exception, b"before-read-failure")
        self.assert_secondary(caught.exception, "child-stream-failed")
        self.assertEqual(hashlib.sha256(b"before-read-failure").hexdigest(),
                         caught.exception.receipt["stdout"]["sha256"])

    def test_wait_observation_failure_is_primary_when_final_reap_callback_also_fails(self):
        with self.assertRaises(runtime.p.DiagnosticError) as caught:
            self.controlled_child(self.FailingReap(), wait_error=True)
        self.record(caught.exception, b"kept")
        self.assert_secondary(caught.exception, "child-wait-failed")
        self.assertEqual("observed", caught.exception.receipt["rss"]["status"])

    def test_final_wait4_failure_never_supplies_unobserved_usage_to_reap_callback(self):
        observer, children = self.FailingReap(), []
        original_popen = runtime.p.subprocess.Popen
        def spawn(*arguments, **options):
            child = original_popen(*arguments, **options)
            children.append(child)
            return child
        with tempfile.TemporaryDirectory() as temporary, \
                mock.patch.object(runtime.p.subprocess, "Popen", side_effect=spawn), \
                mock.patch.object(runtime.p.os, "wait4", side_effect=OSError("controlled-final-wait")):
            with self.assertRaises(runtime.p.DiagnosticError) as caught:
                runtime.p.run([sys.executable, "-S", "-c", "print('before-wait-failure')"],
                              temporary, dict(os.environ), observer=observer)
        self.record(caught.exception, b"before-wait-failure\n")
        self.assertEqual("child-wait-failed", str(caught.exception))
        self.assertEqual(0, observer.reap_calls)
        self.assertEqual(0, children[0].returncode)
        self.assertNotIn("observerReapFailure", caught.exception.receipt)
        self.assertEqual("unavailable", caught.exception.receipt["rss"]["status"])
        self.assertEqual(hashlib.sha256(b"before-wait-failure\n").hexdigest(),
                         caught.exception.receipt["stdout"]["sha256"])

    def test_unclosed_reader_cannot_be_hidden_by_reap_callback_failure(self):
        with self.assertRaises(runtime.p.DiagnosticError) as caught:
            self.controlled_child(self.FailingReap(), late_reader=True)
        self.record(caught.exception, b"kept")
        self.assert_secondary(caught.exception, "child-stream-not-closed")

    def test_sample_error_after_existing_overflow_stays_secondary_to_child_failure(self):
        observer = self.FailingReap(sample_error=runtime.p.DiagnosticError("late-sample-failure", {"stage": "sample"}))
        with self.assertRaises(runtime.p.DiagnosticError) as caught:
            self.controlled_child(observer, limit=1)
        self.record(caught.exception, b"")
        self.assert_secondary(caught.exception, "child-output-limit")
        self.assertEqual({"reason": "late-sample-failure", "receipt": {"stage": "sample"}},
                         caught.exception.receipt["observerSampleFailure"])

    def test_reap_failure_is_primary_only_when_child_and_streams_succeeded(self):
        with self.assertRaises(runtime.p.DiagnosticError) as caught:
            self.controlled_child(self.FailingReap())
        self.record(caught.exception, b"kept")
        self.assert_secondary(caught.exception, "secondary-reap-failure")
        self.assertEqual({"stage": "reaped"}, caught.exception.receipt["observerFailure"])

    def test_sample_initiated_termination_remains_primary_over_later_cleanup_overflow(self):
        gate = threading.Event()
        class LateOverflow:
            def read(self, size):
                if not gate.wait(2):
                    raise OSError("controlled-reader-gate")
                return b"overflow"
            def close(self):
                pass
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=LateOverflow(), stderr=io.BytesIO())
        def reap(pid, flags):
            gate.set()
            return pid, 0, types.SimpleNamespace(ru_maxrss=4096)
        observer = self.FailingReap(sample_error=runtime.p.DiagnosticError("sample-primary", {"stage": "sample"}))
        with mock.patch.object(runtime.p.subprocess, "Popen", return_value=child), \
                mock.patch.object(runtime.p, "nonreaping_waiter", return_value=lambda pid: True), \
                mock.patch.object(runtime.p, "group_census", return_value=lambda pid: True), \
                mock.patch.object(runtime.p, "kill_group") as kill, \
                mock.patch.object(runtime.p.os, "wait4", side_effect=reap):
            with self.assertRaises(runtime.p.DiagnosticError) as caught:
                runtime.p.run(["controlled-child"], ROOT, {}, observer=observer, limit=1)
        self.record(caught.exception, b"")
        self.assert_secondary(caught.exception, "sample-primary")
        self.assertEqual({"reason": "child-output-limit"}, caught.exception.receipt["childFailure"])
        self.assertEqual(1, kill.call_count)  # 늦은 reader는 reap 뒤 numeric PGID를 신호하지 못한다.

    def test_unexpected_callback_errors_still_reap_close_spool_and_preserve_prior_timeout(self):
        for stage, failure, expected in (("sample", ValueError("private-value"), "observer-sample-failed"),
                                         ("reaped", ValueError("private-value"), "child-timeout"),
                                         ("reaped", OSError("private-value"), "child-timeout")):
            with self.subTest(stage=stage, kind=type(failure).__name__), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "stdout.bin"
                observer = self.FailingReap(sample_error=failure if stage == "sample" else None,
                                            reap_error=failure if stage == "reaped" else None)
                destinations, original_open = [], Path.open
                def tracked_open(selected, *arguments, **options):
                    handle = original_open(selected, *arguments, **options)
                    if selected == path and arguments == ("xb",):
                        destinations.append(handle)
                    return handle
                with mock.patch.object(Path, "open", autospec=True, side_effect=tracked_open):
                    with self.assertRaises(runtime.p.DiagnosticError) as caught:
                        runtime.p.run([sys.executable, "-S", "-c", "import time;time.sleep(10)"], temporary,
                                      dict(os.environ), timeout=0.05, stdout_path=path, observer=observer)
                self.record(caught.exception, path.read_bytes())
                self.assertEqual(expected, str(caught.exception))
                self.assertNotIn("private-value", json.dumps(caught.exception.receipt))
                self.assertEqual(1, len(destinations))
                self.assertTrue(destinations[0].closed)
                self.assert_reaped(observer)


if __name__ == "__main__":
    unittest.main()
