"""독립된 controller 회귀다. 가짜 CLI 결과를 제품 그래프 증명으로 사용하지 않는다."""
import hashlib
import importlib.util
import json
import os
import signal
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("impact_repair_session_audit_target", Path(__file__).resolve().parents[1] / "impact_repair_session.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RepairSessionAuditTest(unittest.TestCase):
    def session(self, root, payload=None, exit_code=0, limit=4096, sources=None):
        (root / "src").mkdir(exist_ok=True)
        sources = sources or {"src/One.java": "class One {}\n", "src/Two.java": "class Two {}\n"}
        for name, value in sources.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(value)
        snapshot = root / "snapshot.json"
        snapshot.write_text("{}")
        cli = root / "fixture-cli"
        cli.write_text("#!" + sys.executable + "\nimport sys\nsys.stdout.write(" + repr(json.dumps(payload or {})) + ")\nsys.exit(" + str(exit_code) + ")\n")
        cli.chmod(0o700)
        return MODULE.RepairSession(
            workspace=root, arm="impact", read_paths=list(sources), edit_paths=list(sources),
            compile_command=[], test_command=[], build_env=dict(os.environ), analysis_env=dict(os.environ),
            binary=cli, snapshot=snapshot, snapshot_arguments=[],
            snapshot_source_sha256={name: hashlib.sha256(value.encode()).hexdigest() for name, value in sources.items()},
            max_output_chars=limit,
        )

    def assert_bounded(self, response, limit):
        self.assertLessEqual(len(json.dumps(response, ensure_ascii=False)), limit, response)

    def payload(self):
        return {"format": "kartograph-impact", "version": 1, "status": "found", "observedAffected": 500,
                "changed": [{"usr": "class:p/Target"}], "affected": [],
                "unresolved": [], "limitations": ["runtime: unresolved external dispatch"],
                "truncated": {"results": True, "depth": False, "budget": False},
                "summary": {"observed": {"candidates": 500}, "filtered": {"candidates": 500}},
                "navigation": {"offset": 0, "limit": 10, "returned": 0, "hasNext": True}, "budgets": {}}

    def test_large_summary_is_bounded_without_hiding_native_counts_or_uncertainty(self):
        payload = self.payload()
        payload["summary"]["observed"]["byFile"] = [{"value": "src/main/java/p/VeryLongFileName" + str(i) + ".java", "count": 1} for i in range(500)]
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory), payload)
            response = session.execute({"action": "impact", "symbol": "Target"})
            self.assertTrue(response["ok"])
            self.assert_bounded(response, 4096)
            self.assertEqual(500, response["result"]["observedAffected"])
            self.assertEqual(500, response["result"]["summary"]["observed"]["candidates"])
            self.assertEqual(payload["limitations"], response["result"]["limitations"])
            self.assertEqual(payload["truncated"], response["result"]["truncated"])
            self.assertTrue(response["result"]["projection"]["truncated"])

    def test_list_bounds_escaped_long_paths_and_keeps_paging_counts(self):
        segment = '0nested"quoted' * 7
        long_path = "/".join(["src"] + [segment] * 8 + ["Long.java"])
        sources = {long_path: "class Long {}\n", "src/zShort.java": "class Short {}\n"}
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory), limit=1024, sources=sources)
            response = session.execute({"action": "list", "limit": 1})
            self.assert_bounded(response, 1024)
            self.assertEqual(2, response["total"])
            self.assertEqual(response["returned"], len(response["paths"]))
            self.assertEqual(0, response["offset"])
            self.assertTrue(response["truncated"])
            self.assertEqual([long_path], response["paths"])

    def test_read_bounds_heavily_escaped_source_without_skipping_cursor_data(self):
        escaped = ''.join('quote="\\\\\\n"; ' for _ in range(180)) + "tail\n"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            session = self.session(root, limit=1024, sources={"src/Escaped.java": escaped})
            actual = ""
            request = {"action": "read", "path": "src/Escaped.java"}
            for _ in range(20):
                response = session.execute(request)
                self.assert_bounded(response, 1024)
                self.assertTrue(response["ok"])
                actual += response["content"]
                if not response["truncated"]:
                    break
                request.update(start=response["nextStart"], column=response["nextColumn"])
            self.assertEqual(escaped, actual)
            self.assertEqual(len(escaped), session.metrics()["sourceCharsExposed"])

    def test_search_bounds_escaped_snippets_and_reports_actual_returned_items(self):
        source = 'needle "quoted" \\\\ slash \\n ' + ("x" * 5000)
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory), limit=1024, sources={"src/Search.java": source})
            response = session.execute({"action": "search", "query": "needle", "limit": 10})
            self.assert_bounded(response, 1024)
            self.assertEqual(1, response["total"])
            self.assertEqual(len(response["matches"]), response["returned"])
            self.assertEqual(1, response["returned"])
            self.assertTrue(response["truncated"])
            self.assertEqual(1, session.metrics()["sourceFilesExposed"])
            self.assertEqual(len(response["matches"][0]["text"]), session.metrics()["sourceCharsExposed"])

    def test_process_output_bounds_whole_test_and_refresh_responses(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            session = self.session(root, limit=1024)
            session._test_command = (sys.executable, "-c", "print('\\\"\\\\\\n' * 10000)")
            tested = session.execute({"action": "test"})
            self.assert_bounded(tested, 1024)
            self.assertTrue(tested["ok"])
            self.assertEqual(0, tested["returnCode"])
            self.assertTrue(tested["truncated"])

            session._test_command = (sys.executable, "-c", "import sys; sys.stdout.write('x' * 10000); sys.exit(7)")
            failed = session.execute({"action": "test"})
            self.assert_bounded(failed, 1024)
            self.assertFalse(failed["ok"])
            self.assertEqual("command_failed", failed["error"])
            self.assertEqual(7, failed["returnCode"])
            self.assertTrue(failed["truncated"])

            source_session = MODULE.RepairSession(
                workspace=root, arm="source", read_paths=["src"], edit_paths=["src"],
                compile_command=[sys.executable, "-c", "print('\\\"\\\\\\n' * 10000)"],
                test_command=[], build_env=dict(os.environ), analysis_env=dict(os.environ),
                binary=None, snapshot=None, snapshot_arguments=[], max_output_chars=1024,
            )
            refreshed = source_session.execute({"action": "refresh"})
            self.assert_bounded(refreshed, 1024)
            self.assertTrue(refreshed["ok"])
            self.assertEqual(0, refreshed["compile"]["returnCode"])
            self.assertTrue(refreshed["compile"]["truncated"])

    def test_projection_never_duplicates_changed_declarations(self):
        payload = self.payload()
        payload["affected"] = [{"usr": "method:p/Caller" + str(i), "detail": "x" * 300} for i in range(100)]
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory), payload, limit=1500)
            response = session.execute({"action": "impact", "symbol": "Target"})
            self.assert_bounded(response, 1500)
            changed = response["result"]["changed"]
            self.assertEqual(payload["changed"], changed)

    def test_summary_metadata_cannot_crowd_out_a_small_affected_candidate(self):
        payload = self.payload()
        payload["changed"] = [{"usr": "class:p/Target" + str(i), "retention": ["detail" * 300]} for i in range(123)]
        payload["summary"]["observed"]["byFile"] = [{"value": "src/File" + str(i), "count": 1} for i in range(500)]
        payload["affected"] = [{"usr": "method:p/Caller#call()V", "observedIn": ["current"], "paths": [], "detail": "x" * 2600}]
        payload["navigation"]["returned"] = 1
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory), payload)
            result = session.execute({"action": "impact", "symbol": "Target"})["result"]
            self.assertEqual(payload["affected"], result["affected"])
            self.assertEqual(1, result["projection"]["nextOffset"])

    def test_search_counts_only_files_actually_shown_as_exposed(self):
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory))
            response = session.execute({"action": "search", "query": "Absent"})
            self.assertEqual(0, response["returned"])
            self.assertEqual(0, session.metrics()["sourceFilesExposed"])
            response = session.execute({"action": "search", "query": "class", "limit": 1})
            self.assertEqual(1, response["returned"])
            self.assertEqual(1, session.metrics()["sourceFilesExposed"])

    def test_read_cursor_covers_the_file_without_skipping_truncated_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            session = self.session(root, limit=256)
            content = "".join("line " + str(i) + " content\n" for i in range(10))
            (root / "src/One.java").write_text(content)
            actual = ""
            request = {"action": "read", "path": "src/One.java"}
            for _ in range(10):
                response = session.execute(request)
                self.assertTrue(response["ok"])
                actual += response["content"]
                if not response["truncated"]:
                    break
                self.assertIn("nextStart", response)
                request.update(start=response["nextStart"], column=response.get("nextColumn", 0))
            self.assertEqual(content, actual)

    def test_selection_error_keeps_structured_disambiguation_evidence(self):
        payload = self.payload()
        payload["status"] = "notFound"
        payload["unresolved"] = [{"requested": "Target", "reason": "ambiguous", "candidates": ["class:p/Target", "class:q/Target"]}]
        with tempfile.TemporaryDirectory() as directory:
            session = self.session(Path(directory), payload, exit_code=64)
            response = session.execute({"action": "impact", "symbol": "Target"})
            self.assertIn("result", response)
            self.assert_bounded(response, 4096)
            self.assertEqual(payload["unresolved"], response["result"]["unresolved"])
            self.assertEqual(64, response["returnCode"])

    @unittest.skipUnless(os.name == "posix", "requires POSIX process groups")
    def test_timeout_reaps_a_child_after_its_leader_has_already_exited(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            session = self.session(root)
            child = ("import json,os,signal,time; from pathlib import Path; "
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                     "Path('child.json').write_text(json.dumps({'pid':os.getpid(),'group':os.getpgrp()})); time.sleep(30)")
            parent = ("import subprocess,sys,time; from pathlib import Path; "
                      "subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); "
                      "\nwhile not Path('child.json').exists(): time.sleep(.01)\n")
            session._test_command = (sys.executable, "-c", parent)
            session._command_timeout = 0.3
            try:
                result = session.execute({"action": "test"})
                self.assertEqual("timeout", result["error"])
                process = json.loads((root / "child.json").read_text())
                status = subprocess.run(["ps", "-p", str(process["pid"]), "-o", "stat="],
                                        capture_output=True, text=True, timeout=5)
                self.assertTrue(status.returncode != 0 or status.stdout.strip().startswith("Z"),
                                "child survived the controller timeout")
            finally:
                if (root / "child.json").exists():
                    process = json.loads((root / "child.json").read_text())
                    try:
                        os.killpg(process["group"], signal.SIGKILL)
                    except ProcessLookupError:
                        pass


if __name__ == "__main__":
    unittest.main()
