"""부가 관측의 범위·실패 격리를 작은 fixture/mock으로 검사하며 제품 빌드는 실행하지 않는다."""

import contextlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "verify-analysis-smoke-gate.py"


def load_module():
    spec = importlib.util.spec_from_file_location("smoke_diagnostics_test", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SmokeDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.mod = load_module()

    def test_wall_boundaries_and_exact_subprocess_contract_with_and_without_cpu(self):
        for enabled in (False, True):
            with self.subTest(enabled=enabled):
                events = []
                clock = iter([100.0, 102.25, 110.0, 110.125])
                env = {"JAVA_HOME": "/selected/jdk", "JAVA_OPTS": "secret-never-rewritten"}
                commands = [("a", ["launcher", "a", "argument with spaces"], None),
                            ("b", ["launcher", "b"], None)]
                samples, counters, failures = {"a": [], "b": []}, {}, []
                diagnostics = Mock()
                diagnostics.cpu_snapshot.side_effect = lambda: events.append("cpu") or (1.0, 2.0)
                diagnostics.after_child.side_effect = lambda *args: events.append("record")

                def run(*args, **kwargs):
                    events.append("child")
                    self.assertIs(kwargs["env"], env)
                    return subprocess.CompletedProcess(args[0], 0, "")

                with patch.object(self.mod.time, "perf_counter", side_effect=lambda: events.append("clock") or next(clock)), \
                        patch.object(self.mod.subprocess, "run", side_effect=run) as child:
                    result = self.mod.run_measurement_pass(
                        commands, env, samples, counters, failures, diagnostics if enabled else None)
                self.assertIsNone(result)
                self.assertEqual(samples, {"a": [2.25], "b": [0.125]})
                self.assertEqual(counters, {"a": [{"returncode": 0}], "b": [{"returncode": 0}]})
                self.assertEqual(failures, [])
                self.assertEqual(events, (["cpu", "clock", "child", "clock", "cpu", "record"] if enabled
                                          else ["clock", "child", "clock"]) * 2)
                self.assertEqual(child.call_args_list, [
                    unittest.mock.call(cmd, capture_output=True, text=True, env=env, timeout=120)
                    for _, cmd, _ in commands])
                if enabled:
                    self.assertEqual([call.args[-2:] for call in diagnostics.after_child.call_args_list],
                                     [(1, "a"), (1, "b")])

    def test_counter_hook_errors_do_not_replace_child_exits_or_timeout(self):
        diagnostics = Mock()
        diagnostics.cpu_snapshot.side_effect = OSError("private-counter-path")
        diagnostics.after_child.side_effect = ValueError("private-counter-path")
        for outcome in (2, 64, subprocess.TimeoutExpired("private-child", 120)):
            with self.subTest(outcome=outcome):
                stderr = io.StringIO()
                response = subprocess.CompletedProcess(["launcher"], outcome, "") if isinstance(outcome, int) else None
                with patch.object(self.mod.subprocess, "run", return_value=response,
                                  side_effect=None if response else outcome), \
                        patch.object(self.mod.time, "perf_counter", side_effect=[10.0, 11.0]), \
                        contextlib.redirect_stderr(stderr):
                    code = self.mod.run_measurement_pass(
                        [("graph", ["launcher"], None)], {}, {"graph": []}, {}, [], diagnostics)
                self.assertEqual(code, outcome if isinstance(outcome, int) else 2)
                self.assertNotIn("private", stderr.getvalue())

    def test_only_direct_file_entry_is_cpu_eligible(self):
        with patch.object(self.mod, "__name__", "__main__"), \
                patch.object(self.mod.sys, "orig_argv", [sys.executable, str(SCRIPT)]), \
                patch.object(self.mod.threading, "active_count", return_value=1):
            self.assertTrue(self.mod.dedicated_entry())
            for original in ([sys.executable, "-c", "runpy code"], [sys.executable, "-m", "module"], []):
                with patch.object(self.mod.sys, "orig_argv", original):
                    self.assertFalse(self.mod.dedicated_entry())
            with patch.object(self.mod.threading, "active_count", return_value=2):
                self.assertFalse(self.mod.dedicated_entry())

    def fixture_gate(self, root, durations, *, as_json=True, returncode=0, fault=None,
                     observation_fault=False, warmup=False, runs=3, stage_fault=None):
        mod = self.mod
        binary = root / "kartograph"
        binary.write_text("unused mock fixture")
        for module in mod.MODULES:
            directory = root / module / "build/classes/kotlin/main"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "Fixture.class").write_bytes(b"fixture")
        (root / ".kartograph-self.pro").write_text("")
        (root / ".kartograph.yml").write_text("")
        project = root / "temporary-project"
        project.mkdir(exist_ok=True)
        # 기존 임시 fixture 생성은 실제로 수행하되 명령 비교를 위해 경로를 고정한다.
        resources = project / "res"
        if resources.exists():
            resources.rmdir()
        env = {"JAVA_HOME": "/selected/jdk", "JAVA_OPTS": "secret-selected-value"}
        calls = []
        snapshots = []
        clocks = iter(value for duration in durations for value in (100.0, 100.0 + duration))

        def child(cmd, **kwargs):
            calls.append((cmd, kwargs))
            self.assertIs(kwargs["env"], env)
            if fault:
                raise fault
            command = cmd[1]
            stdout = {"graph": "x [shape=box];\n" * 2000, "dead": "",
                      "cycles": "0 findings", "rules": "0 findings, 0 unassigned",
                      "metrics": "module\tCa\n" + "row\n" * 6}[command]
            return subprocess.CompletedProcess(cmd, returncode, stdout, "private-secret-stderr")

        def snapshot(selected):
            self.assertIs(selected, env)
            snapshots.append("context")
            if observation_fault:
                raise OSError("private-secret-path")
            return {"fixture": "safe"}

        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(mod.os, "access", return_value=True), \
                patch.object(mod, "check_java_environment", return_value=env), \
                patch.object(mod, "has_working_java", return_value=True), \
                patch.object(mod.tempfile, "TemporaryDirectory", return_value=contextlib.nullcontext(str(project))), \
                patch.object(mod, "context_snapshot", side_effect=snapshot), \
                patch.object(mod.time, "perf_counter", side_effect=lambda: next(clocks)), \
                patch.object(mod.subprocess, "run", side_effect=child), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), contextlib.ExitStack() as extra:
            if stage_fault == "initialize":
                extra.enter_context(patch.object(mod, "SmokeDiagnostics", side_effect=OSError("private-init-path")))
            elif stage_fault == "finish":
                extra.enter_context(patch.object(mod.SmokeDiagnostics, "finish", side_effect=OSError("private-finish-path")))
            status = mod.verify_self_analysis(binary, root, 5.0, 20.0,
                                              warmup=warmup, as_json=as_json, runs=runs)
        return status, stdout.getvalue(), stderr.getvalue(), calls, snapshots

    def test_unchanged_receipt_acceptance_rounding_and_command_schedule(self):
        cases = [([5, 3, 3, 3, 3, 3], 0), ([5.0001, 3, 3, 3, 3, 3], 1),
                 ([4, 4, 4, 4, 2, 2.0001], 1)]
        for durations, expected_status in cases:
            with self.subTest(durations=durations), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                passing = self.fixture_gate(root, durations * 3, warmup=True)
                broken = self.fixture_gate(root, durations * 3, warmup=True, observation_fault=True)
                self.assertEqual(passing[0], expected_status)
                self.assertEqual(broken[0], expected_status)
                first, second = json.loads(passing[1]), json.loads(broken[1])
                first.pop("diagnostics")
                diagnostics = second.pop("diagnostics")
                self.assertEqual(diagnostics["version"], 1)
                self.assertEqual(first, second)
                self.assertEqual(diagnostics["value"]["before"], self.mod.unavailable("observation-error"))
                self.assertEqual(diagnostics["value"]["after"], self.mod.unavailable("observation-error"))
                self.assertNotIn("private-secret", broken[1] + broken[2])
                self.assertEqual(passing[2], "")
                self.assertEqual(passing[4], ["context", "context"])
                self.assertEqual(passing[3], broken[3])
                calls = passing[3]
                self.assertEqual(len(calls), 19)
                self.assertEqual(calls[0][1], {"capture_output": True, "env": calls[0][1]["env"],
                                               "timeout": 120, "check": False})
                names = ["graph", "dead", "dead", "cycles", "rules", "metrics"]
                self.assertEqual([cmd[1] for cmd, _ in calls[1:]], names * 3)
                class_args = [value for module in self.mod.MODULES
                              for value in ("--classes", str(root / module / "build/classes/kotlin/main"))]
                binary = str(root / "kartograph")
                project = root / "temporary-project"
                dead = [binary, "dead", *class_args, "--project", str(project), "--manifest",
                        str(project / "AndroidManifest.xml"), "--resources", str(project / "res"),
                        "--namespace", "dev.kartograph", "--keep-rules", str(root / ".kartograph-self.pro")]
                expected = [[binary, "graph", *class_args, "--format", "dot"],
                            [*dead, "--strict"], [*dead, "--include-private-members", "--strict"],
                            [binary, "cycles", *class_args, "--strict"],
                            [binary, "rules", *class_args, "--config", str(root / ".kartograph.yml"), "--strict"],
                            [binary, "metrics", *class_args]]
                self.assertEqual([cmd for cmd, _ in calls], [expected[0], *(expected * 3)])
                for _, kwargs in calls[1:]:
                    self.assertEqual(kwargs, {"capture_output": True, "text": True,
                                              "env": kwargs["env"], "timeout": 120})
                for index, name in enumerate(("graph", "dead", "dead_private", "cycles", "rules", "metrics")):
                    self.assertEqual(first["measurements"][name]["samples"], [round(durations[index], 3)] * 3)
                self.assertEqual(first["perCommandBudget"], 5.0)
                self.assertEqual(first["totalBudget"], 20.0)

    def test_contract_failure_and_early_tool_usage_timeout_paths_survive_observation_errors(self):
        for code in (1, 2, 64):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as tmp:
                result = self.fixture_gate(Path(tmp), [1] * 18, returncode=code, observation_fault=True)
                self.assertEqual(result[0], code)
                if code == 1:
                    data = json.loads(result[1])
                    self.assertEqual(data["status"], "FAIL")
                    self.assertEqual(len(data["contractFailures"]), 6)
                    self.assertEqual(len(result[3]), 18)
                else:
                    self.assertEqual(result[1], "")
                    self.assertEqual(len(result[3]), 1)
                    self.assertIn(f"(exit {code})", result[2])
                self.assertNotIn("private-secret", result[1] + result[2])
        for warmup in (False, True):
            with self.subTest(warmup=warmup), tempfile.TemporaryDirectory() as tmp:
                result = self.fixture_gate(Path(tmp), [1] * 18, warmup=warmup, observation_fault=True,
                                           fault=subprocess.TimeoutExpired("private-secret-command", 120))
                self.assertEqual(result[:3], (2, "", "error: warmup run timed out\n" if warmup
                                              else "error: command 'graph' timed out\n"))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(OSError, "original-launch-error"):
                self.fixture_gate(Path(tmp), [1] * 18, observation_fault=True,
                                  fault=OSError("original-launch-error"))

    def test_collector_initialization_and_finish_failures_preserve_original_result(self):
        for stage in ("initialize", "finish"):
            for returncode in (0, 1):
                with self.subTest(stage=stage, returncode=returncode), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    original = self.fixture_gate(root, [1] * 18, returncode=returncode)
                    failed = self.fixture_gate(root, [1] * 18, returncode=returncode, stage_fault=stage)
                    first, second = json.loads(original[1]), json.loads(failed[1])
                    first.pop("diagnostics")
                    diagnostics = second.pop("diagnostics")
                    self.assertEqual(first, second)
                    self.assertEqual(original[0], failed[0])
                    self.assertEqual(diagnostics["reason"], "observation-error")
                    self.assertEqual(diagnostics["version"], 1)
                    self.assertNotIn("private-init-path", failed[1] + failed[2])
                    self.assertNotIn("private-finish-path", failed[1] + failed[2])

    def test_plain_text_is_unchanged_and_does_not_observe(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.fixture_gate(Path(tmp), [1] * 6, as_json=False, runs=1, observation_fault=True)
        self.assertEqual(result[0], 0)
        self.assertEqual(result[4], [])
        self.assertEqual(result[2], "")
        expected = "[SMOKE GATE PASS] All self-analysis contracts satisfied within performance budget (median of 1 runs):\n"
        for name in ("graph", "dead", "dead_private", "cycles", "rules", "metrics"):
            expected += f"  - {name:15}: 1.000s (budget: 5.000s; samples: 1.000)\n"
        expected += "  Total analysis time: 6.000s (budget: 20.000s; per-run totals: 6.000)\n"
        self.assertEqual(result[1], expected)
        with patch.object(self.mod, "__name__", "__main__"), \
                patch.object(self.mod, "dedicated_entry") as entry, \
                patch.object(self.mod, "verify_self_analysis", return_value=0):
            self.assertEqual(self.mod.main([]), 0)
            entry.assert_not_called()
        for args in (["--unknown"], ["--runs", "0"], ["--total-budget", "0"]):
            stderr = io.StringIO()
            with patch.object(self.mod, "context_snapshot") as snapshot, contextlib.redirect_stderr(stderr):
                self.assertEqual(self.mod.main(args), 64)
                snapshot.assert_not_called()

    def diagnostics(self, owned=False):
        self.mod._CPU_OWNER_PID = os.getpid() if owned else None
        self.mod._CPU_CLAIMED = False
        with patch.object(self.mod, "context_snapshot", return_value={}):
            return self.mod.SmokeDiagnostics({})

    def test_import_concurrency_reentry_and_pid_changes_omit_cpu(self):
        imported = self.diagnostics()
        with patch.dict(sys.modules, {"resource": Mock()}) as modules:
            self.assertEqual(self.mod.observe(imported.cpu_snapshot), self.mod.unavailable("ownership-unavailable"))
            modules["resource"].getrusage.assert_not_called()
        self.assertFalse(self.mod.dedicated_entry())
        owned = self.diagnostics(owned=True)
        with patch.object(self.mod.threading, "active_count", return_value=2):
            self.assertEqual(self.mod.observe(owned.cpu_snapshot), self.mod.unavailable("ownership-unavailable"))
        self.assertEqual(self.mod.observe(owned.cpu_snapshot), self.mod.unavailable("ownership-unavailable"))
        owned = self.diagnostics(owned=True)
        with patch.object(self.mod, "context_snapshot", return_value={}):
            second = self.mod.SmokeDiagnostics({})
        self.assertEqual(second.cpu_reason, "ownership-unavailable")
        with patch.object(self.mod.os, "getpid", return_value=-1):
            self.assertEqual(self.mod.observe(owned.cpu_snapshot), self.mod.unavailable("ownership-unavailable"))

    def test_cpu_zero_positive_nonfinite_negative_and_missing_resource(self):
        diagnostics = self.diagnostics(owned=True)
        resource = SimpleNamespace(RUSAGE_CHILDREN="children", getrusage=Mock())
        for user, system, expected in ((1.0, 2.0, "observed"), (math.inf, 0.0, "unavailable"),
                                       (math.nan, 0.0, "unavailable"), (-1.0, 0.0, "unavailable"),
                                       (True, 0.0, "unavailable")):
            # ru_maxrss 접근은 실패하도록 값 자체를 제공하지 않는다.
            resource.getrusage.return_value = SimpleNamespace(ru_utime=user, ru_stime=system)
            with patch.dict(sys.modules, {"resource": resource}):
                result = self.mod.observe(diagnostics.cpu_snapshot)
            self.assertEqual(result["status"], expected)
        before = {"status": "observed", "value": (1.0, 2.0)}
        for end, expected in (((1.0, 2.0), "observed"), ((1.25, 2.5), "observed"),
                              ((0.5, 2.0), "unavailable"), ((math.inf, 2.0), "unavailable")):
            diagnostics.after_child(before, {"status": "observed", "value": end}, 1, "graph")
            row = diagnostics.document["childCpu"]["observations"][-1]
            self.assertEqual(row["status"], expected)
            if expected == "observed":
                self.assertEqual(row["userSeconds"], end[0] - 1.0)
                self.assertEqual(row["systemSeconds"], end[1] - 2.0)
            self.assertEqual((row["run"], row["command"]), (1, "graph"))
        for error in (OSError("private-path"), ImportError("resource")):
            resource.getrusage.side_effect = error
            with patch.dict(sys.modules, {"resource": resource}):
                result = self.mod.observe(diagnostics.cpu_snapshot)
            self.assertEqual(result["status"], "unavailable")
            self.assertNotIn("private-path", json.dumps(result))
        with patch.dict(sys.modules, {"resource": None}):
            self.assertEqual(self.mod.observe(diagnostics.cpu_snapshot), self.mod.unavailable("unsupported"))

    def test_observation_failures_are_typed_and_interrupts_propagate(self):
        for error, reason in ((FileNotFoundError("private-path"), "missing"),
                              (PermissionError("private-path"), "denied"),
                              (ValueError("secret-text"), "malformed"),
                              (NotImplementedError(), "unsupported"), (OSError("secret"), "observation-error"),
                              (self.mod.ObservationUnavailable("private-path"), "observation-error")):
            self.assertEqual(self.mod.observe(Mock(side_effect=error)), self.mod.unavailable(reason))
        for error in (KeyboardInterrupt(), SystemExit(7)):
            with self.assertRaises(type(error)):
                self.mod.observe(Mock(side_effect=error))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bounded"
            path.write_bytes(b"x" * 4096)
            self.assertEqual(len(self.mod.bounded_read(path)), 4096)
            path.write_bytes(b"x" * 4097)
            self.assertEqual(self.mod.observe(self.mod.bounded_read, path), self.mod.unavailable("oversized"))

    def test_context_allowlist_and_selected_java_release_do_not_dump_secrets_or_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp) / "private-jdk"
            (home / "bin").mkdir(parents=True)
            (home / "bin/java").write_text("not executed")
            (home / "release").write_text('JAVA_VERSION="17.0.16"\nIMPLEMENTOR="Eclipse Adoptium"\n'
                                          'OS_ARCH="x86_64"\nUNRELATED_SECRET="never-output-this"\n')
            env = {"JAVA_HOME": str(home), "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
                   "ImageOS": "ubuntu24", "ImageVersion": "20261014.1.0", "GITHUB_SHA": "a" * 40,
                   "GITHUB_RUN_ID": "1234", "GITHUB_RUN_ATTEMPT": "1", "UNRELATED_SECRET": "never-output-this"}
            for key in ("JAVA_OPTS", "KARTOGRAPH_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                env[key] = "never-output-this"
            with patch.object(self.mod.sys, "platform", "darwin"), \
                    patch.object(self.mod.subprocess, "run", side_effect=AssertionError("no children")):
                result = self.mod.context_snapshot(env)
            encoded = json.dumps(result)
            self.assertNotIn("never-output-this", encoded)
            self.assertNotIn(str(home), encoded)
            self.assertNotIn("UNRELATED_SECRET", encoded)
            self.assertEqual(result["java"]["value"]["basename"], "java")
            self.assertEqual(result["java"]["value"]["release"]["JAVA_VERSION"]["value"], "17.0.16")
            self.assertEqual(result["linux"], self.mod.unavailable("unsupported"))
            self.assertTrue(all(result["javaOptionPresence"].values()))
            env.pop("JAVA_HOME")
            self.assertEqual(self.mod.observe(self.mod.java_identity, env),
                             self.mod.unavailable("selected-java-home-unavailable"))
            env.update(RUNNER_OS="Linux/private-secret", ImageOS="token-value", GITHUB_SHA="z" * 40,
                       GITHUB_RUN_ID="1" * 21)
            runner = self.mod.runner_context(env)
            for key in ("RUNNER_OS", "ImageOS", "GITHUB_SHA", "GITHUB_RUN_ID"):
                self.assertEqual(runner[key], self.mod.unavailable("malformed"))
            self.assertNotIn("private-secret", json.dumps(runner))

    def test_cgroup_rebinding_leaf_limits_and_path_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, quota in (("first", "100000 100000"), ("second", "max 100000")):
                leaf = root / name
                leaf.mkdir()
                for filename, data in {"cpu.max": quota, "cpuset.cpus.effective": "0-3,5", "memory.max": "max",
                                       "cpu.stat": "usage_usec 10\nuser_usec 7\nsystem_usec 3\nignored_secret 99\n",
                                       "memory.events": "low 0\nhigh 1\nmax 0\noom 0\noom_kill 0\n"}.items():
                    (leaf / filename).write_text(data)
            real_read = self.mod.bounded_read
            real_path = Path
            membership = ["0::/first\n", "0::/second\n", "0::/../outside\n", "0:://first\n", "0::/./first\n"]

            def read(path, limit=4096):
                return membership.pop(0).encode() if str(path) == "/proc/self/cgroup" else real_read(path, limit)

            def mapped_path(value):
                return root if value == "/sys/fs/cgroup" else real_path(value)

            with patch.object(self.mod, "Path", side_effect=mapped_path), \
                    patch.object(self.mod, "bounded_read", side_effect=read):
                first, second = self.mod.cgroup_context(), self.mod.cgroup_context()
                self.assertNotEqual(first["identitySha256"], second["identitySha256"])
                self.assertEqual(first["cpu.max"]["value"], {"quotaMicros": 100000, "periodMicros": 100000})
                self.assertEqual(second["cpu.max"]["value"]["quotaMicros"], "max")
                self.assertEqual(first["cpu.stat"]["value"]["nr_throttled"], self.mod.unavailable("missing"))
                for _ in range(3):
                    self.assertEqual(self.mod.observe(self.mod.cgroup_context), self.mod.unavailable("unsafe-path"))
                membership.append("0::/escape\n")
                (root / "escape").symlink_to(root.parent, target_is_directory=True)
                self.assertEqual(self.mod.observe(self.mod.cgroup_context), self.mod.unavailable("unsafe-path"))
            self.assertNotIn(str(root), json.dumps(first))
            self.assertNotIn("ignored_secret", json.dumps(first))
        for text in ("0 100000", "max 0", "max 100000 extra", "-1 100000"):
            self.assertEqual(self.mod.observe(self.mod.cpu_policy, text)["status"], "unavailable")
        for text in ("3-1", "0-2,2", "0,,1", "-1", "0-4294967296"):
            self.assertEqual(self.mod.observe(self.mod.cpuset_policy, text)["status"], "unavailable")
        self.assertEqual(self.mod.observe(self.mod.selected_numbers, "MemTotal: 10 MB", ("MemTotal",), "kB"),
                         self.mod.unavailable("malformed"))


if __name__ == "__main__":
    unittest.main()
