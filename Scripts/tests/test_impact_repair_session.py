"""안전한 로컬 subprocess fixture로 RepairSession 제어기 계약을 검증한다."""

import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import textwrap
import unittest

from Scripts.impact_repair_session import RepairSession


class RepairSessionTest(unittest.TestCase):
    def make_fixture(self, root: Path) -> Path:
        script = root / "fake-kartograph"
        script.write_text("#!/usr/bin/env python3\n" + textwrap.dedent("""
            import json
            import os
            import sys
            import time

            log = os.environ.get("FIXTURE_LOG")
            if log:
                with open(log, "a", encoding="utf-8") as handle:
                    handle.write(json.dumps(sys.argv[1:]) + "\\n")
            if sys.argv[1:] and sys.argv[1] == "snapshot":
                print(json.dumps({"format": "fixture", "affected": [{"usr": "class:Caller"}],
                                  "observedAffected": 1, "unresolved": [],
                                  "truncated": {"results": False}, "limitations": []}))
            elif sys.argv[1:] and sys.argv[1] == "impact":
                affected = [{"usr": "class:Caller"}]
                observed = 1
                if os.environ.get("BIG_IMPACT"):
                    affected = [{"usr": "class:" + str(index)} for index in range(50)]
                    observed = 50
                print(json.dumps({"format": "kartograph-impact", "affected": affected,
                                  "observedAffected": observed, "unresolved": [{"reason": "unknown"}],
                                  "truncated": {"results": True, "budget": True}, "limitations": ["potential"]}))
            elif sys.argv[1:] and sys.argv[1] == "sleep":
                time.sleep(10)
        """).lstrip())
        script.chmod(script.stat().st_mode | stat.S_IXUSR)
        return script

    def session(self, root: Path, **kwargs) -> RepairSession:
        return RepairSession(
            workspace=root,
            arm=kwargs.pop("arm", "impact"),
            read_paths=kwargs.pop("read_paths", ["src"]),
            edit_paths=kwargs.pop("edit_paths", ["src/Caller.java"]),
            compile_command=kwargs.pop("compile_command", []),
            test_command=kwargs.pop("test_command", []),
            build_env=kwargs.pop("build_env", {}),
            analysis_env=kwargs.pop("analysis_env", {}),
            binary=kwargs.pop("binary", None),
            snapshot=kwargs.pop("snapshot", root / "trial.json"),
            snapshot_arguments=kwargs.pop("snapshot_arguments", ["--classes", "classes"]),
            **kwargs,
        )

    def test_inventory_read_search_pagination_and_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "src/A.java").write_text("class A {\n  void call() {}\n}\n" + "// padding\n" * 300)
            (root / "src/B.java").write_text("class B {\n  void call() {}\n}\n")
            (root / ".env").write_text("TOKEN=do-not-read\n")
            (root / "outside.txt").write_text("outside\n")
            (root / "src/link.java").symlink_to(root / "outside.txt")
            session = self.session(root, read_paths=["src", ".env", "src/link.java"], max_output_chars=512)

            listed = session.execute({"action": "list", "limit": 1})
            self.assertEqual(["src/A.java"], listed["paths"])
            self.assertEqual(2, listed["total"])
            self.assertTrue(listed["truncated"])
            self.assertEqual([], session.inventory()[2:])

            read = session.execute({"action": "read", "path": "src/A.java"})
            self.assertTrue(read["truncated"])
            self.assertLessEqual(len(json.dumps(read, ensure_ascii=False)), 512)
            self.assertNotIn("do-not-read", json.dumps(read))
            self.assertFalse(session.execute({"action": "read", "path": "../outside.txt"})["ok"])
            self.assertFalse(session.execute({"action": "read", "path": ".env"})["ok"])
            self.assertFalse(session.execute({"action": "read", "path": "src/link.java"})["ok"])
            found = session.execute({"action": "search", "query": "void", "limit": 1})
            self.assertEqual(2, found["total"])
            self.assertEqual(1, found["returned"])
            self.assertTrue(found["truncated"])

    def test_source_arm_denies_impact_and_edits_only_exact_allowed_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "src/Caller.java").write_text("class Caller { void old() {} }\n")
            (root / "src/CallerTest.java").write_text("class CallerTest {}\n")
            session = self.session(root, arm="source", edit_paths=["src/Caller.java"])
            self.assertFalse(session.execute({"action": "impact", "symbol": "class:Caller"})["ok"])
            replaced = session.execute({"action": "replace", "path": "src/Caller.java",
                                        "old": "old", "new": "new"})
            self.assertTrue(replaced["ok"])
            self.assertIn("new", (root / "src/Caller.java").read_text())
            self.assertFalse(session.execute({"action": "replace", "path": "src/Caller.java",
                                              "old": "missing", "new": "new"})["ok"])
            self.assertFalse(session.execute({"action": "replace", "path": "src/CallerTest.java",
                                              "old": "CallerTest", "new": "Changed"})["ok"])

    def test_refresh_uses_configured_commands_then_impact_uses_cli_and_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "src/Caller.java").write_text("class Caller {}\n")
            (root / "classes").mkdir()
            binary = self.make_fixture(root)
            log = root / "calls.log"
            session = self.session(root, binary=binary, build_env={"FIXTURE_LOG": str(log)},
                                    analysis_env={"FIXTURE_LOG": str(log)},
                                    compile_command=[sys.executable, "-c", "print('compiled')"],
                                    snapshot_arguments=["--classes", "classes", "--scope", "fixture:main"])
            refreshed = session.execute({"action": "refresh"})
            self.assertTrue(refreshed["ok"], refreshed)
            self.assertTrue((root / "trial.json").is_file())
            result = session.execute({"action": "impact", "symbol": "class:Caller",
                                      "module": ["feature"], "relation": "direct", "limit": 3})
            self.assertTrue(result["ok"], result)
            self.assertEqual("kartograph-impact", result["result"]["format"])
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual("snapshot", calls[0][0])
            self.assertEqual("impact", calls[1][0])
            self.assertIn("--module", calls[1])
            self.assertEqual(1, result["result"]["observedAffected"])
            self.assertEqual(len(session.source_hashes()), refreshed["sourceHashCount"])
            self.assertNotIn("sourceHashes", refreshed)

    def test_failed_refresh_invalidates_snapshot_and_source_changes_make_query_stale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            source = root / "src/Caller.java"
            source.write_text("class Caller {}\n")
            binary = self.make_fixture(root)
            compile = root / "compile.py"
            compile.write_text("""import pathlib, sys
if pathlib.Path('fail').exists(): sys.exit(3)
if pathlib.Path('mutate').exists(): pathlib.Path('src/Caller.java').write_text('class Caller { int during; }\\n')
""")
            session = self.session(root, binary=binary, compile_command=[sys.executable, str(compile)])
            self.assertTrue(session.execute({"action": "refresh"})["ok"])
            source.write_text("class Caller { int changed; }\n")
            stale = session.execute({"action": "impact", "symbol": "class:Caller"})
            self.assertFalse(stale["ok"])
            self.assertEqual("stale_source", stale["error"])
            source.write_text("class Caller {}\n")
            (root / "mutate").write_text("x")
            during = session.execute({"action": "refresh"})
            self.assertFalse(during["ok"])
            self.assertEqual("source_changed_during_refresh", during["error"])
            (root / "mutate").unlink()
            source.write_text("class Caller {}\n")
            (root / "fail").write_text("x")
            failed = session.execute({"action": "refresh"})
            self.assertFalse(failed["ok"])
            self.assertFalse((root / "trial.json").exists())
            self.assertFalse(session.execute({"action": "impact", "symbol": "class:Caller"})["ok"])

    def test_source_arm_refresh_can_compile_without_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "src/Caller.java").write_text("class Caller {}\n")
            session = self.session(root, arm="source", compile_command=[sys.executable, "-c", "print('compiled')"],
                                   binary=None, snapshot=None)
            result = session.execute({"action": "refresh"})
            self.assertTrue(result["ok"], result)
            self.assertEqual("compile_only", result["snapshotFreshness"])

    def test_impact_projection_keeps_native_counts_uncertainty_and_limitations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "src/Caller.java").write_text("class Caller {}\n")
            binary = self.make_fixture(root)
            session = self.session(root, binary=binary, compile_command=[sys.executable, "-c", "pass"],
                                   analysis_env={"BIG_IMPACT": "1"}, max_output_chars=256)
            self.assertTrue(session.execute({"action": "refresh"})["ok"])
            result = session.execute({"action": "impact", "symbol": "class:Caller"})
            self.assertTrue(result["ok"], result)
            self.assertTrue(result["result"]["projection"]["truncated"])
            self.assertEqual(50, result["result"]["observedAffected"])
            self.assertEqual({"results": True, "budget": True}, result["result"]["truncated"])
            self.assertEqual([{"reason": "unknown"}], result["result"]["unresolved"])
            self.assertEqual(["potential"], result["result"]["limitations"])

    def test_invalid_calls_timeouts_and_metrics_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "src/Caller.java").write_text("class Caller {}\n")
            compile = [sys.executable, "-c", "print('x' * 10000)"]
            session = self.session(root, arm="source", compile_command=compile, test_command=compile,
                                   max_calls=3, command_timeout=0.1, max_output_chars=512)
            invalid = session.execute({"action": "unknown"})
            self.assertFalse(invalid["ok"])
            tested = session.execute({"action": "test"})
            self.assertTrue(tested["ok"])
            self.assertTrue(tested["truncated"])
            timeout = self.session(root, arm="source", test_command=[sys.executable, "-c", "import time; time.sleep(2)"],
                                   command_timeout=0.05)
            outcome = timeout.execute({"action": "test"})
            self.assertFalse(outcome["ok"])
            self.assertEqual("timeout", outcome["error"])
            self.assertLess(outcome["seconds"], 1)
            timeout.execute({"action": "invalid"})
            self.assertEqual(2, timeout.metrics()["calls"])

    def test_output_budget_must_fit_an_explicit_error_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "at least 256"):
                self.session(Path(directory), max_output_chars=24)


if __name__ == "__main__":
    unittest.main()
