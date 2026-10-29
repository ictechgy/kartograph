"""격리 비교의 byte 계약·입력 identity·실제 javac/JVM 진입을 검증한다."""
import importlib.util
import gzip
import io
import errno
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("index_cost", ROOT / "Scripts/verify-index-cost.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class IndexCostTest(unittest.TestCase):
    def test_baseline_is_the_exact_preoptimization_product(self):
        self.assertEqual("33f8ce1b15af5be6dc01573e1209017cb1999f1d", probe.SOURCE_BASELINE)
        self.assertEqual(probe.SOURCE_BASELINE, probe.BASELINE)

    def test_baseline_metadata_update_cannot_change_executable_sources(self):
        env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            def execute(arguments, cwd=repo, environment=env, **kwargs):
                result = subprocess.run(arguments, cwd=cwd, env=environment, capture_output=True,
                                        check=True, timeout=30)
                return {"stdout": result.stdout}
            def git(*arguments):
                return execute(["git", *arguments])["stdout"].decode().strip()
            git("init", "-q")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.invalid")
            metadata = repo / "gradle/verification-metadata.xml"
            metadata.parent.mkdir()
            metadata.write_text("original verification pins\n")
            source = repo / "Main.java"
            source.write_text("class Main {}\n")
            git("add", ".")
            git("commit", "-qm", "original measured source")
            original = git("rev-parse", "HEAD")
            metadata.write_text("original and additional verification pins\n")
            git("add", ".")
            git("commit", "-qm", "metadata only")
            auxiliary = git("rev-parse", "HEAD")
            with mock.patch.object(probe, "successful", side_effect=execute):
                probe.require_baseline_source(repo, original, auxiliary, env)
                probe.require_baseline_source(repo, original, original, env)
                git("commit", "--allow-empty", "-qm", "same tree, different revision")
                with self.assertRaisesRegex(probe.DiagnosticError, "baseline-executable-source-changed"):
                    probe.require_baseline_source(repo, auxiliary, git("rev-parse", "HEAD"), env)
                source.write_text("class Main { int changed; }\n")
                git("add", ".")
                git("commit", "-qm", "changed executable source")
                with self.assertRaisesRegex(probe.DiagnosticError, "baseline-executable-source-changed"):
                    probe.require_baseline_source(repo, original, git("rev-parse", "HEAD"), env)

    def test_equal_baseline_refs_still_require_empty_diff(self):
        with mock.patch.object(probe, "successful", return_value={"stdout": b"gradle/verification-metadata.xml\n"}):
            with self.assertRaisesRegex(probe.DiagnosticError, "baseline-executable-source-changed"):
                probe.require_baseline_source(ROOT, probe.BASELINE, probe.BASELINE, {})

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

    def test_semantic_comparison_preserves_engine_and_rejects_fact_or_graph_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            outputs = {arm: Path(temporary) / arm for arm in ("baseline", "candidate")}
            facts = {"format": "class-facts-v10-payload-sha256", "engine": "a" * 64,
                     "payloads": {"c" * 64: "d" * 64}}
            graphs = {name: {"bytes": 100, "sha256": "e" * 64} for name in ("json", "ndjson")}
            for arm, directory in outputs.items():
                directory.mkdir()
                (directory / "raw-facts.json").write_text(json.dumps({**facts, "engine": ("a" if arm == "baseline" else "b") * 64}))
                (directory / "graphs.json").write_text(json.dumps(graphs))
            report = {}
            probe.compare_semantic_outputs(outputs, report)
            self.assertTrue(report["semantic"]["enginesDiffer"])
            self.assertEqual("a" * 64, report["semantic"]["arms"]["baseline"]["engine"])
            candidate = outputs["candidate"]
            for name in ("json", "ndjson"):
                changed = {**graphs, name: {"bytes": 100, "sha256": "f" * 64}}
                (candidate / "graphs.json").write_text(json.dumps(changed))
                with self.assertRaisesRegex(probe.DiagnosticError, "semantic-graph-or-raw-payload-mismatch"):
                    probe.compare_semantic_outputs(outputs, {})
                (candidate / "graphs.json").write_text(json.dumps(graphs))
            for document, reason in (
                ({**facts, "engine": "b" * 64, "payloads": {"c" * 64: "e" * 64}}, "semantic-graph-or-raw-payload-mismatch"),
                (facts, "semantic-engine-did-not-change"),
                ({**facts, "payloads": {}}, "invalid-semantic-facts"),
                ({**facts, "engine": "unknown"}, "invalid-semantic-facts"),
            ):
                (candidate / "raw-facts.json").write_text(json.dumps(document))
                with self.assertRaisesRegex(probe.DiagnosticError, reason):
                    probe.compare_semantic_outputs(outputs, {})

    def test_exact_dead_pair_rejects_an_added_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            commands = {arm: probe.cli_commands(arm, [root / "classes"], root)
                        for arm in ("baseline", "candidate")}
            def execute(arguments, cwd, env, **kwargs):
                arm, command = arguments[:2]
                body = b"same\n" + (b"extra diagnostic\n" if arm == "candidate" and command == "dead" else b"")
                return {"phase": kwargs["phase"], "exit": 0, "stdout": body, "stderr": b"", "wallNanos": 1, "rss": {}}
            report = {}
            with mock.patch.object(probe, "run", side_effect=execute), mock.patch.object(probe, "host_load_snapshot", return_value={}):
                with self.assertRaisesRegex(probe.DiagnosticError, "cli-byte-mismatch-or-unsuccessful-exit"):
                    probe.paired_cli(commands, root, {}, 10, report, probe.RawStreams(root / "raw"))
            self.assertTrue(all(not row["contractAccepted"] and not row["expectedContractDelta"]
                                for row in report["cli"] if row["command"] in ("dead", "dead_private")))

    def test_alternation_is_three_samples_per_profile_and_arm(self):
        rows = probe.schedule()
        self.assertEqual(18, len(rows))
        for profile in probe.PROFILES:
            selected = [(sample, arm) for p, sample, arm in rows if p == profile]
            self.assertEqual([(1, "baseline"), (1, "candidate"), (2, "candidate"),
                              (2, "baseline"), (3, "baseline"), (3, "candidate")], selected)

    def test_cli_pairs_preserve_every_sample_raw_bytes_and_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            commands = {arm: probe.cli_commands(arm, [root / "classes"], root)
                        for arm in ("baseline", "candidate")}
            report = {"format": "kartograph-index-cost-v1", "status": "PASS",
                      "scope": "diagnostic", "residualScope": "not-query-only"}
            calls = []
            def execute(arguments, cwd, env, **kwargs):
                calls.append(arguments[0])
                arm, name = arguments[:2]
                body = b"a\r\nb\x00"
                # 느린 관측도 버리지 않고 중앙값과 전체 원시 값에 함께 남긴다.
                wall = 100_000_000_000 if len(calls) == 2 else 1_000_000_000
                return {"phase": kwargs["phase"], "exit": 0, "stdout": body, "stderr": b"",
                        "wallNanos": wall, "rss": {"status": "unavailable"}}
            with mock.patch.object(probe, "run", side_effect=execute), mock.patch.object(
                    probe, "host_load_snapshot", return_value={"loadAverage": [1, 2, 3]}):
                probe.paired_cli(commands, root, {}, 10, report, probe.RawStreams(root / "raw"))
            self.assertEqual([arm for _, _, order in probe.cli_schedule() for arm in order], calls)
            self.assertEqual(36, len(calls))
            self.assertEqual(18, len(report["cli"]))
            self.assertEqual(36, len(report["cliLoad"]))
            for index in range(6):
                self.assertEqual([(1, ("baseline", "candidate") if index % 2 == 0 else ("candidate", "baseline")),
                                  (2, ("candidate", "baseline") if index % 2 == 0 else ("baseline", "candidate")),
                                  (3, ("baseline", "candidate") if index % 2 == 0 else ("candidate", "baseline"))],
                                 [(sample, order) for command, sample, order in probe.cli_schedule() if command == index])
            self.assertEqual(100_000_000_000, report["cli"][0]["candidate"]["wallNanos"])
            for arm in ("baseline", "candidate"):
                self.assertEqual(6, report["cliTiming"]["arms"][arm]["sumMedianSeconds"])
            for row in report["cli"]:
                self.assertTrue(row["contractAccepted"])
                for arm in ("baseline", "candidate"):
                    for stream in ("stdout", "stderr"):
                        receipt = row["raw"][arm][stream]
                        data = gzip.decompress((root / receipt["path"]).read_bytes())
                        self.assertEqual(len(data), receipt["bytes"])
                        self.assertEqual(probe.digest(data), receipt["sha256"])
                        self.assertEqual(receipt["sha256"], row[arm][stream]["sha256"])
            public = json.loads(probe.public_summary(report))
            self.assertEqual(report["cliTiming"], public["cliTiming"])
            self.assertNotIn("cliLoad", public)

    def test_late_cli_failure_is_not_replaced_by_successful_samples(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            commands = {arm: probe.cli_commands(arm, [root / "classes"], root)
                        for arm in ("baseline", "candidate")}
            count = 0
            def execute(arguments, cwd, env, **kwargs):
                nonlocal count
                count += 1
                arm, name = arguments[:2]
                body = b"x"
                return {"phase": kwargs["phase"], "exit": 1 if count == 35 else 0,
                        "stdout": body, "stderr": b"", "wallNanos": 1, "rss": {}}
            report = {}
            with mock.patch.object(probe, "run", side_effect=execute), mock.patch.object(
                    probe, "host_load_snapshot", return_value={}):
                with self.assertRaisesRegex(probe.DiagnosticError, "cli-byte-mismatch-or-unsuccessful-exit"):
                    probe.paired_cli(commands, root, {}, 10, report, probe.RawStreams(root / "raw"))
            self.assertEqual(36, count)
            self.assertFalse(report["cli"][-1]["contractAccepted"])
            self.assertEqual(72, len(list((root / "raw").glob("*.gz"))))

    def test_raw_retention_has_total_budget_and_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            raw = probe.RawStreams(Path(temporary) / "raw")
            with mock.patch.object(probe, "RAW_LIMIT", 3):
                raw.retain("graph", 1, "baseline", {"stdout": b"abc", "stderr": b""})
                with self.assertRaisesRegex(probe.DiagnosticError, "raw-evidence-limit"):
                    raw.retain("graph", 1, "candidate", {"stdout": b"a", "stderr": b""})
            with self.assertRaises(FileExistsError):
                raw.retain("graph", 1, "baseline", {"stdout": b"changed", "stderr": b""})
            self.assertEqual(b"abc", gzip.decompress((raw.directory / "graph-1-baseline-stdout.gz").read_bytes()))

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

    def test_fake_final_reap_closes_all_numeric_group_signals(self):
        # 실제 PID 재사용이나 타 프로세스 신호 없이 커널 호출의 순서만 계측한다.
        events = []
        child = types.SimpleNamespace(pid=4242, returncode=None,
                                      stdout=io.BytesIO(b"a\r\nb"), stderr=io.BytesIO(b"x"))
        def reap(pid, flags):
            self.assertEqual(child.pid, pid)
            events.append("reap")
            return pid, 0, types.SimpleNamespace(ru_maxrss=4096)
        def signal_group(pid, number):
            self.assertEqual(child.pid, pid)
            events.append("kill-after-reap" if "reap" in events else "kill-owned")
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", return_value=types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED), create=True), mock.patch.object(
                probe.os, "wait4", side_effect=reap), mock.patch.object(probe.os, "killpg", side_effect=signal_group), mock.patch.object(
                probe, "group_census", return_value=lambda pid: True, create=True):
            result = probe.run(["synthetic-child"], ROOT, {}, phase="fake-order")
        self.assertEqual((0, b"a\r\nb", b"x"), (result["exit"], result["stdout"], result["stderr"]))
        self.assertNotIn("kill-after-reap", events)
        self.assertLess(events.index("kill-owned"), events.index("reap"))

    def test_fake_late_reader_overflow_cannot_signal_after_reap(self):
        events, reaped = [], threading.Event()
        class LateStream:
            def read(self, size):
                self.assert_reaped = reaped.wait(timeout=2)
                return b"overflow" if self.assert_reaped else b""
            def close(self):
                pass
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=LateStream(), stderr=io.BytesIO())
        def reap(pid, flags):
            events.append("reap")
            reaped.set()
            return pid, 0, types.SimpleNamespace(ru_maxrss=4096)
        def signal_group(pid, number):
            events.append("kill-after-reap" if reaped.is_set() else "kill-owned")
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", return_value=types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED), create=True), mock.patch.object(
                probe.os, "wait4", side_effect=reap), mock.patch.object(probe.os, "killpg", side_effect=signal_group), mock.patch.object(
                probe, "group_census", return_value=lambda pid: True, create=True):
            with self.assertRaisesRegex(probe.DiagnosticError, "child-output-limit"):
                probe.run(["synthetic-child"], ROOT, {}, limit=1, phase="fake-overflow")
        self.assertTrue(reaped.is_set())
        self.assertNotIn("kill-after-reap", events)

    def test_wait4_failure_reaps_owned_child_without_reopening_signals(self):
        original_popen, children = probe.subprocess.Popen, []
        def spawn(*arguments, **options):
            child = original_popen(*arguments, **options)
            children.append(child)
            return child
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.object(probe.os, "wait4", side_effect=OSError("synthetic")), mock.patch.object(
                    probe.subprocess, "Popen", side_effect=spawn):
                with mock.patch.object(probe, "kill_group", wraps=probe.kill_group) as kill:
                    with self.assertRaisesRegex(probe.DiagnosticError, "child-wait-failed") as caught:
                        probe.run([sys.executable, "-c", "pass"],
                                  temporary, dict(os.environ), phase="test-wait")
                    self.assertEqual(0, children[0].returncode)
                    self.assertTrue(all(call.args[0] is children[0] for call in kill.call_args_list))
                    self.assertEqual("test-wait", caught.exception.receipt["phase"])
                    self.assertEqual("owned-wait4-rusage-not-observed", caught.exception.receipt["rss"]["reason"])

    def test_fake_linux_nonreaping_flags_rss_and_reap_order(self):
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO())
        with mock.patch.object(probe.sys, "platform", "linux"), mock.patch.object(
                probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", side_effect=[None, types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED),
                                                types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED)], create=True) as observe, mock.patch.object(
                probe.os, "wait4", return_value=(child.pid, 0, types.SimpleNamespace(ru_maxrss=4096))), mock.patch.object(
                probe.os, "killpg") as kill:
            result = probe.run(["synthetic-child"], ROOT, {})
        self.assertEqual(3, observe.call_count)
        for call in observe.call_args_list:
            self.assertEqual((probe.os.P_PID, child.pid, probe.os.WEXITED | probe.os.WNOHANG | probe.os.WNOWAIT), call.args)
        kill.assert_called_once()
        self.assertEqual(4096 * 1024, result["rss"]["peakBytes"])
        self.assertEqual("owned-wait4-child-peak-rss; kernel-accounting-not-live-heap", result["rss"]["scope"])

    def test_fake_lost_ownership_closes_signal_and_reap_paths(self):
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO())
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", side_effect=ChildProcessError(errno.ECHILD, "synthetic"), create=True), mock.patch.object(
                probe.os, "wait4") as reap, mock.patch.object(probe.os, "killpg") as kill:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-wait-failed"):
                probe.run(["synthetic-child"], ROOT, {})
        reap.assert_not_called()
        kill.assert_not_called()

    def test_unsupported_observer_fails_before_spawn(self):
        with mock.patch.object(probe.sys, "platform", "unsupported"), mock.patch.object(probe.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-ownership-not-supported"):
                probe.run(["must-not-spawn"], ROOT, {})
        spawn.assert_not_called()

    def test_sigchld_auto_reaping_fails_before_spawn(self):
        with mock.patch.object(probe.signal, "getsignal", return_value=probe.signal.SIG_IGN), mock.patch.object(
                probe.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-ownership-not-supported"):
                probe.run(["must-not-spawn"], ROOT, {})
        spawn.assert_not_called()

    def test_fake_terminal_empty_group_needs_no_signal(self):
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO())
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", return_value=types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED), create=True), mock.patch.object(
                probe.os, "wait4", return_value=(child.pid, 0, types.SimpleNamespace(ru_maxrss=4096))), mock.patch.object(
                probe, "group_census", return_value=lambda pid: False), mock.patch.object(probe.os, "killpg") as kill:
            self.assertEqual(0, probe.run(["synthetic-child"], ROOT, {})["exit"])
        kill.assert_not_called()

    def test_fake_unknown_group_census_fails_without_group_signal(self):
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO())
        def unknown(pid):
            raise OSError(errno.EIO, "synthetic")
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", return_value=types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED), create=True), mock.patch.object(
                probe.os, "wait4", return_value=(child.pid, 0, types.SimpleNamespace(ru_maxrss=4096))), mock.patch.object(
                probe, "group_census", return_value=unknown), mock.patch.object(probe.os, "killpg") as kill:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-wait-failed"):
                probe.run(["synthetic-child"], ROOT, {})
        kill.assert_not_called()

    def test_real_tail_rss_and_owned_descendant_pipe_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            stdout, stderr = b"a\r\n" * 40000, b"z\r\n" * 45000
            result = probe.run([sys.executable, "-c", "import sys; "
                                "sys.stdout.buffer.write(b'a\\r\\n' * 40000); "
                                "sys.stderr.buffer.write(b'z\\r\\n' * 45000); sys.exit(3)"],
                               temporary, dict(os.environ))
            self.assertEqual((3, stdout, stderr), (result["exit"], result["stdout"], result["stderr"]))
            self.assertEqual("observed", result["rss"]["status"])
            self.assertGreater(result["rss"]["peakBytes"], 0)
            self.assertLess(result["rss"]["peakBytes"], 2**63)
            # 새 session 안의 자손만 pipe를 유지한다. PID를 출력하거나 다른 그룹을 조회하지 않는다.
            code = ("import os,sys,time\nchild=os.fork()\n"
                    "if child == 0:\n time.sleep(5)\n os._exit(0)\n"
                    "sys.stdout.buffer.write(b'leader\\r\\n'); sys.stdout.flush()\n")
            result = probe.run([sys.executable, "-c", code], temporary, dict(os.environ))
            self.assertEqual((0, b"leader\r\n", b""), (result["exit"], result["stdout"], result["stderr"]))
            self.assertLess(result["wallNanos"], 2_000_000_000)

    def test_fake_group_census_is_bounded_and_unknown_is_not_empty(self):
        def check(count, error, members):
            def list_group(pid, buffer, size):
                probe.ctypes.set_errno(error)
                for index, member in enumerate(members):
                    buffer[index] = member
                return count
            library = types.SimpleNamespace(proc_listpgrppids=mock.Mock(side_effect=list_group))
            with mock.patch.object(probe.sys, "platform", "darwin"), mock.patch.object(probe.ctypes, "CDLL", return_value=library):
                return probe.group_census()(4242)
        self.assertFalse(check(1, 0, [4242]))
        self.assertTrue(check(2, 0, [4242, 4243]))
        for count, error in ((4096, 0), (0, errno.EACCES), (-1, errno.EIO)):
            with self.assertRaises(OSError):
                check(count, error, [])

    def test_fake_invalid_terminal_observation_loses_lease(self):
        for pid, signo, code in ((4243, probe.signal.SIGCHLD, probe.os.CLD_EXITED),
                                  (4242, 0, probe.os.CLD_EXITED), (4242, probe.signal.SIGCHLD, 0)):
            child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO())
            observation = types.SimpleNamespace(si_pid=pid, si_signo=signo, si_code=code)
            with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                    probe.os, "waitid", return_value=observation, create=True), mock.patch.object(
                    probe.os, "wait4") as reap, mock.patch.object(probe.os, "killpg") as kill:
                with self.assertRaisesRegex(probe.DiagnosticError, "child-wait-failed"):
                    probe.run(["synthetic-child"], ROOT, {})
            reap.assert_not_called()
            kill.assert_not_called()

    def test_fake_final_echild_never_reopens_or_falls_back(self):
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO(), wait=mock.Mock())
        observation = types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED)
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", return_value=observation, create=True), mock.patch.object(
                probe.os, "wait4", side_effect=ChildProcessError(errno.ECHILD, "synthetic")), mock.patch.object(
                probe, "group_census", return_value=lambda pid: True), mock.patch.object(probe.os, "killpg") as kill:
            with self.assertRaisesRegex(probe.DiagnosticError, "child-wait-failed"):
                probe.run(["synthetic-child"], ROOT, {})
        child.wait.assert_not_called()
        kill.assert_called_once()

    def test_reader_late_completion_after_snapshot_cannot_turn_partial_output_into_success(self):
        # stdout snapshot 직후 마지막 append와 thread 종료를 강제한다. 실제 scheduling이나
        # 다른 PID 신호 없이 join의 미완료 관측이 최종 판정까지 유지되는지 확인한다.
        readers, joins = [], []
        class FakeReader:
            def __init__(self, target, args, daemon=False):
                self.alive = args[0] == 0
                readers.append(self)
            def start(self):
                pass
            def join(self, timeout):
                joins.append(timeout)
            def is_alive(self):
                return self.alive
        class LateBuffer(bytearray):
            def __bytes__(self):
                snapshot = bytes(memoryview(self))
                self.extend(b"late")
                readers[0].alive = False
                return snapshot
        stdout, stderr = LateBuffer(b"early"), bytearray()
        child = types.SimpleNamespace(pid=4242, returncode=None, stdout=io.BytesIO(), stderr=io.BytesIO())
        observation = types.SimpleNamespace(si_pid=child.pid, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED)
        with mock.patch.object(probe.subprocess, "Popen", return_value=child), mock.patch.object(
                probe.os, "waitid", return_value=observation, create=True), mock.patch.object(
                probe.os, "wait4", return_value=(child.pid, 0, types.SimpleNamespace(ru_maxrss=4096))), mock.patch.object(
                probe, "group_census", return_value=lambda pid: True), mock.patch.object(probe.os, "killpg"), mock.patch.object(
                probe.threading, "Thread", FakeReader), mock.patch.object(probe, "bytearray", side_effect=[stdout, stderr], create=True):
            with self.assertRaisesRegex(probe.DiagnosticError, "child-stream-not-closed"):
                probe.run(["synthetic-child"], ROOT, {})
        self.assertEqual([10, 10], joins)
        self.assertEqual(b"earlylate", bytes(memoryview(stdout)))
        self.assertFalse(readers[0].alive)

    def test_structural_count_change_is_rejected(self):
        rows = [dict(profile=profile, nodes=1, locatedNodes=1, edges=2, externalCalls=3, classFiles=4,
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

    def test_jfr_summary_preserves_samples_weights_and_recursive_frame_semantics(self):
        def frame(owner, method):
            return {"method": {"type": {"name": owner}, "name": method}}
        frames = [frame("java/util/HashMap", "put"), frame("dev/kartograph/index/Parser", "parse"),
                  frame("dev/kartograph/index/Parser", "parse"), frame("dev/kartograph/index/Index", "index")]
        events = [{"type": "jdk.ExecutionSample", "values": {"stackTrace": {"frames": frames}}},
                  {"type": "jdk.ObjectAllocationSample", "values": {"weight": 4096, "stackTrace": {"frames": frames}}},
                  {"type": "jdk.ObjectAllocationSample", "values": {"weight": 20, "stackTrace": None}}]
        result = probe.summarize_jfr(json.dumps({"recording": {"events": events}}))
        self.assertEqual(2, result["jdk.ObjectAllocationSample"]["events"])
        self.assertEqual(4116, result["jdk.ObjectAllocationSample"]["weight"])
        self.assertEqual([("dev/kartograph/index/Parser.parse", 4096)], result["jdk.ObjectAllocationSample"]["nearest"])
        self.assertEqual({"dev/kartograph/index/Parser.parse": 4096, "dev/kartograph/index/Index.index": 4096},
                         dict(result["jdk.ObjectAllocationSample"]["inclusive"]))
        for changed in [{"type": "jdk.InitialEnvironmentVariable", "values": {}},
                        {"type": "jdk.ObjectAllocationSample", "values": {"weight": -1}},
                        {"type": "jdk.ObjectAllocationSample", "values": {"weight": True}},
                        {"type": "jdk.ExecutionSample", "values": {"stackTrace": {"frames": [frame("dev/kartograph/private\nvalue", "run")]}}}]:
            with self.assertRaisesRegex(probe.DiagnosticError, "invalid-jfr-events"):
                probe.summarize_jfr(json.dumps({"recording": {"events": [changed]}}))

    def test_runtime_jfr_keeps_typed_configuration_and_overlapping_durations_separate(self):
        def event(kind, **values):
            return {"type": kind, "values": values}
        events = [event("jdk.GCHeapConfiguration", minSize=8, maxSize=64, initialSize=16, usesCompressedOops=True),
                  event("jdk.GCConfiguration", youngCollector="G1New", oldCollector="G1Old", parallelGCThreads=2,
                        concurrentGCThreads=1, usesDynamicGCThreads=True),
                  event("jdk.CompilerConfiguration", threadCount=2, tieredCompilation=True),
                  event("jdk.Compilation", compileLevel=4, succeded=True, duration="PT0.125S"),
                  event("jdk.Compilation", compileLevel=3, succeded=False, duration="PT0.000000001S"),
                  event("jdk.GarbageCollection", duration="PT1.25S", sumOfPauses="PT0.1S", longestPause="PT0.09S")]
        result = probe.summarize_runtime_jfr(json.dumps({"recording": {"events": events}}))
        self.assertEqual("observed", result["status"])
        self.assertEqual([], result["missingConfigurations"])
        self.assertEqual(64, result["configurations"]["jdk.GCHeapConfiguration"][0]["maxSize"])
        self.assertEqual({"3": 1, "4": 1}, result["compilation"]["byLevel"])
        self.assertEqual(125000001, result["compilation"]["durationNanosSum"])
        self.assertEqual(1, result["compilation"]["succeeded"])
        self.assertEqual(100000000, result["garbageCollection"]["pauseNanosSum"])
        self.assertEqual(90000000, result["garbageCollection"]["maximumPauseNanos"])
        self.assertIn("may overlap", result["scope"])

    def test_runtime_jfr_missing_configuration_is_partial_not_default_zero(self):
        result = probe.summarize_runtime_jfr('{"recording":{"events":[]}}')
        self.assertEqual("partial", result["status"])
        self.assertEqual(3, len(result["missingConfigurations"]))
        self.assertEqual([], result["configurations"]["jdk.GCHeapConfiguration"])

    def test_runtime_jfr_refuses_private_types_corruption_and_recording_loss(self):
        invalid = [{"type": "jdk.InitialEnvironmentVariable", "values": {}},
                   {"type": "jdk.Compilation", "values": {"compileLevel": True, "succeded": True, "duration": "PT1S"}},
                   {"type": "jdk.Compilation", "values": {"compileLevel": 4, "succeded": True, "duration": "NaN"}},
                   {"type": "jdk.Compilation", "values": {"compileLevel": 4, "succeded": True, "duration": "PT-1S"}},
                   {"type": "jdk.CompilerConfiguration", "values": {"threadCount": -1, "tieredCompilation": True}},
                   {"type": "jdk.GCHeapConfiguration", "values": {"minSize": 0, "maxSize": True, "initialSize": 0, "usesCompressedOops": True}}]
        for event in invalid:
            with self.subTest(event=event):
                with self.assertRaisesRegex(probe.DiagnosticError, "invalid-jfr-runtime-events"):
                    probe.summarize_runtime_jfr(json.dumps({"recording": {"events": [event]}}))
        with self.assertRaisesRegex(probe.DiagnosticError, "jfr-data-loss"):
            probe.summarize_runtime_jfr('{"recording":{"events":[{"type":"jdk.DataLoss","values":{"amount":1,"total":1}}]}}')

    def test_jfr_unexpected_event_refuses_before_public_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "samples.jfc"
            config.write_text("<configuration/>")
            jdk = root / "jdk"
            tool = jdk / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")
            tool.parent.mkdir(parents=True)
            tool.write_bytes(b"fixture tool identity")
            expected = {"exit": 0, "stdout": b"ok", "stderr": b""}
            def execute(arguments, cwd, env, **kwargs):
                if kwargs["phase"] == "jfr-candidate":
                    (cwd / "capture.jfr").write_bytes(b"fixture recording")
                    return expected
                return {"stdout": b"jdk.ExecutionSample 2 100\njdk.InitialEnvironmentVariable 1 80\n"}
            with mock.patch.object(probe, "successful", side_effect=execute):
                with self.assertRaisesRegex(probe.DiagnosticError, "unexpected-jfr-event-type"):
                    probe.capture_jfr(jdk, ["Fixture"], {}, root / "work", root / "public", config, expected)
            self.assertFalse((root / "public").exists())

    def test_jfr_runtime_count_mismatch_refuses_before_public_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "samples.jfc"
            config.write_text("<configuration/>")
            jdk = root / "jdk"
            tool = jdk / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")
            tool.parent.mkdir(parents=True)
            tool.write_bytes(b"fixture tool identity")
            expected = {"exit": 0, "stdout": b"ok", "stderr": b""}
            def execute(arguments, cwd, env, **kwargs):
                if kwargs["phase"] == "jfr-candidate":
                    (cwd / "capture.jfr").write_bytes(b"fixture recording")
                    return expected
                if kwargs["phase"] == "jfr-summary":
                    return {"stdout": b"jdk.ExecutionSample 1 100\njdk.GCHeapConfiguration 1 80\n"}
                return {"stdout": b'{"recording":{"events":[{"type":"jdk.ExecutionSample","values":{"stackTrace":null}}]}}'}
            with mock.patch.object(probe, "successful", side_effect=execute):
                with self.assertRaisesRegex(probe.DiagnosticError, "jfr-runtime-event-count-mismatch"):
                    probe.capture_jfr(jdk, ["Fixture"], {}, root / "work", root / "public", config, expected)
            self.assertFalse((root / "public").exists())

    def test_public_runtime_projection_exposes_only_stored_identity(self):
        samples = {kind: {"events": 1, "weight": 1, "inclusive": [], "nearest": []}
                   for kind in probe.JFR_EVENTS}
        runtime = probe.summarize_runtime_jfr('{"recording":{"events":[]}}')
        original = {"samples": samples, "command": "dead", "outputMatchesUnprofiledCandidate": True,
            "scope": "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance",
            "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
            "recording": {"size": 1, "sha256": "0" * 64}, "configurationSha256": "1" * 64,
            "runtime": runtime}
        result = json.loads(probe.public_observation_summary({"status": "PASS", "jfr": original}))
        self.assertEqual({"status": "stored-not-projected", "format": "kartograph-jfr-runtime-v1",
                          "sha256": probe.identity_digest(runtime)}, result["jfr"]["runtimeDetails"])
        self.assertEqual("not-available-for-unprofiled-gate", result["jfr"]["gcJitAttribution"])
        self.assertNotIn("configurations", result["jfr"])
        for invalid in [{"format": "unexpected"}, {"format": "kartograph-jfr-runtime-v1", "number": float("nan")},
                        {"format": "kartograph-jfr-runtime-v1", "extra": "x" * 32768}]:
            with self.assertRaisesRegex(probe.DiagnosticError, "invalid-public-observation"):
                probe.public_observation_summary({"status": "PASS", "jfr": {**original, "runtime": invalid}})

    def test_runtime_jfr_bounds_configuration_variants(self):
        events = [{"type": "jdk.CompilerConfiguration", "values": {"threadCount": value, "tieredCompilation": True}}
                  for value in range(9)]
        with self.assertRaisesRegex(probe.DiagnosticError, "invalid-jfr-runtime-events"):
            probe.summarize_runtime_jfr(json.dumps({"recording": {"events": events}}))

    def test_real_jfr_capture_has_only_allowed_events_and_unchanged_output(self):
        # 설치된 JDK의 실제 recording/summary/print를 검증한다. 작은 fixture는 성능 근거가 아니다.
        java_home = Path(os.environ["JAVA_HOME"]).resolve(strict=True)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "Sample.java"
            source.write_text('package dev.kartograph.fixture; public class Sample { '
                'static volatile Object value; public static void main(String[] args) { '
                'long until=System.nanoTime()+700000000L; while(System.nanoTime()<until) '
                '{ value=new byte[1024]; } System.out.print("fixture-ok"); }}')
            probe.successful([java_home / "bin/javac", "-d", root, source], root, dict(os.environ))
            report = probe.capture_jfr(java_home, ["-Xmx64m", "-cp", str(root), "dev.kartograph.fixture.Sample"],
                dict(os.environ), root / "work", root / "public", ROOT / "Scripts/IndexCostCapture.jfc",
                {"exit": 0, "stdout": b"fixture-ok", "stderr": b""})
            self.assertTrue(report["outputMatchesUnprofiledCandidate"])
            self.assertGreater(sum(row["events"] for row in report["samples"].values()), 0)
            self.assertGreater(report["recording"]["size"], 0)
            self.assertLessEqual(report["recording"]["size"], probe.JFR_LIMIT)
            self.assertEqual("observed", report["runtime"]["status"])
            self.assertEqual(64 * 1024 * 1024,
                report["runtime"]["configurations"]["jdk.GCHeapConfiguration"][0]["maxSize"])
            self.assertGreater(report["runtime"]["compilation"]["events"], 0)
            self.assertGreater(report["runtime"]["garbageCollection"]["events"], 0)
            self.assertEqual(0, report["runtime"]["eventCounts"]["jdk.DataLoss"])

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

    def test_real_semantic_probe_consumes_cache_and_detects_raw_method_changes(self):
        java_home = Path(os.environ["JAVA_HOME"]).resolve(strict=True)
        distribution = ROOT / "cli/build/install/kartograph"
        self.assertTrue((distribution / "lib").is_dir())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            classes = root / "classes"
            classes.mkdir()
            subprocess.run([java_home / "bin/javac", "-encoding", "UTF-8", "-cp", str(distribution / "lib/*"),
                            "-d", root, ROOT / "Scripts/IndexEquivalenceProbe.java"], check=True, capture_output=True, timeout=30)
            documents = []
            for label in ("before", "after"):
                source = root / "Tiny.java"
                source.write_text('public final class Tiny { public static String value() { return "' + label + '"; } }\n')
                subprocess.run([java_home / "bin/javac", "-g", "-d", classes, source], check=True, capture_output=True, timeout=30)
                output = root / label
                result = subprocess.run([java_home / "bin/java", "-cp", str(root) + os.pathsep + str(distribution / "lib/*"),
                                         "IndexEquivalenceProbe", output, classes], check=True, capture_output=True, timeout=30)
                self.assertEqual({"classes": 1, "cacheHits": 1}, json.loads(result.stdout))
                facts = json.loads((output / "raw-facts.json").read_bytes())
                self.assertEqual(1, len(facts["payloads"]))
                graphs = json.loads((output / "graphs.json").read_bytes())
                self.assertEqual({"json", "ndjson"}, set(graphs))
                self.assertTrue(all(row["bytes"] > 0 and len(row["sha256"]) == 64 for row in graphs.values()))
                documents.append(facts)
            self.assertEqual(documents[0]["engine"], documents[1]["engine"])
            self.assertNotEqual(list(documents[0]["payloads"].values()), list(documents[1]["payloads"].values()))


if __name__ == "__main__":
    unittest.main()
