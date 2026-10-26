"""Linux 진단의 lease·입력 식별·수치 의미·원문 비노출을 검증한다."""
import copy
import importlib.util
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

    def test_graph_stdout_has_explicit_64mib_boundary(self):
        stream = runtime.RuntimeGraphCapture.__new__(runtime.RuntimeGraphCapture)
        stream.size = 64 * runtime.MIB - 1
        with mock.patch.object(runtime.p.GraphCapture, "append") as append:
            stream.append(0, b"x")
            append.assert_called_once_with(0, b"x")
            stream.size += 1
            with self.assertRaisesRegex(runtime.p.DiagnosticError, "graph-stdout-limit"):
                stream.append(0, b"x")

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

    def test_workflow_restricts_runtime_target_before_checkout_but_leaves_default_mode(self):
        from test_release_workflow import workflow_steps
        steps = workflow_steps(ROOT / ".github/workflows/cli-repeatability.yml")
        validate = next(s for s in steps if s.get("name") == "Validate the requested identity")
        with tempfile.TemporaryDirectory() as temporary:
            for mode, target, expected in (("false", "1" * 40, 0), ("true", "1" * 40, 64),
                                           ("true", "c8d9ab3e3492afc5dc645dcfa50d7401c58796c2", 0)):
                env = dict(os.environ, RUNTIME_PROFILE=mode, JIT_ATTRIBUTION="false", TARGET_SHA=target, RUNNER_TEMP=temporary,
                           GITHUB_ENV=str(Path(temporary) / "environment"))
                result = subprocess.run(["bash", "-c", validate["run"]], env=env, capture_output=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr.decode())

    def test_runtime_upload_set_excludes_all_seeded_raw_files(self):
        from test_release_workflow import workflow_steps
        steps = workflow_steps(ROOT / ".github/workflows/cli-repeatability.yml")
        upload = next(s for s in steps if s.get("name") == "Preserve only allowlisted runtime derivatives")
        original = next(s for s in steps if s.get("name") == "Preserve the complete diagnostic outcome")
        self.assertEqual("${{ always() && !inputs.runtime_profile && !inputs.jit_attribution }}", original["if"])
        self.assertEqual("${{ always() && inputs.runtime_profile }}", upload["if"])
        paths = upload["with"]["path"].splitlines()
        with tempfile.TemporaryDirectory() as temporary:
            public = Path(temporary) / "cli-repeatability/runtime-public"
            public.mkdir(parents=True)
            names = ["result.json", "events-graph.json.gz", "capture.jfr", "events-secret.json.gz", "raw-events.json", "stderr"]
            for name in names:
                (public / name).write_text("private secret seed")
            matched = []
            for path in paths:
                actual = path.replace("${{ runner.temp }}", temporary)
                if Path(actual).is_file():
                    matched.append(Path(actual).name)
            self.assertEqual(["result.json", "events-graph.json.gz"], matched)

    def test_jit_mode_is_separate_sealed_and_uploads_only_two_derivatives(self):
        from test_release_workflow import workflow_steps
        steps = workflow_steps(ROOT / ".github/workflows/cli-repeatability.yml")
        validate = next(s for s in steps if s.get("name") == "Validate the requested identity")
        upload = next(s for s in steps if s.get("name") == "Preserve only allowlisted JIT derivatives")
        sealed = "c8d9ab3e3492afc5dc645dcfa50d7401c58796c2"
        with tempfile.TemporaryDirectory() as temporary:
            for runtime, jit, target, expected in (("false", "true", sealed, 0), ("true", "true", sealed, 64),
                                                   ("false", "true", "1" * 40, 64), ("false", "false", "1" * 40, 0)):
                env = dict(os.environ, RUNTIME_PROFILE=runtime, JIT_ATTRIBUTION=jit, TARGET_SHA=target,
                           RUNNER_TEMP=temporary, GITHUB_ENV=str(Path(temporary) / "environment"))
                result = subprocess.run(["bash", "-c", validate["run"]], env=env, capture_output=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr.decode())
            public = Path(temporary) / "cli-repeatability/jit-public"
            public.mkdir(parents=True)
            for name in ("result.json", "events-dead-private.json.gz", "capture.jfr", "events.json", "stderr"):
                (public / name).write_text("private seed")
            paths = upload["with"]["path"].splitlines()
            matches = [Path(path.replace("${{ runner.temp }}", temporary)).name for path in paths
                       if Path(path.replace("${{ runner.temp }}", temporary)).is_file()]
            self.assertEqual(["result.json", "events-dead-private.json.gz"], matches)
        self.assertEqual("${{ always() && inputs.jit_attribution }}", upload["if"])


if __name__ == "__main__":
    unittest.main()
