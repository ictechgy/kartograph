"""실제 Git의 삭제/이름 변경을 두 snapshot의 영향 질의에 연결한다."""
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(os.environ.get("KARTOGRAPH_BINARY", ROOT / "cli/build/install/kartograph/bin/kartograph")).resolve()


class ImpactGateTest(unittest.TestCase):
    def test_snapshot_size_budget_reaches_impact_and_both_freshness_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            subprocess.run(['git', 'init', '-q', str(project)], check=True)
            subprocess.run(['git', '-C', str(project), '-c', 'user.name=Fixture',
                            '-c', 'user.email=fixture@example.invalid', 'commit', '--allow-empty', '-qm', 'base'], check=True)
            base = subprocess.check_output(['git', '-C', str(project), 'rev-parse', 'HEAD'], text=True).strip()
            graph = project / 'graph.json'
            graph.write_text('{}')
            calls = project / 'calls.jsonl'
            binary = project / 'fixture-cli'
            binary.write_text('#!' + sys.executable + '\n' +
                'import json,sys\nfrom pathlib import Path\n' +
                'with Path(' + repr(str(calls)) + ').open("a") as log: log.write(json.dumps(sys.argv[1:])+"\\n")\n' +
                'if sys.argv[1] == "impact":\n' +
                ' print(json.dumps({"format":"kartograph-impact","version":1,"status":"noChanges",' +
                '"inputs":{"base":{"scope":"fixture:main"},"current":{"scope":"fixture:main"}}}))\n' +
                'else: print(json.dumps({"format":"kartograph-freshness","version":1,"status":"matched"}))\n')
            binary.chmod(0o700)
            command = [sys.executable, str(ROOT / 'Scripts/check-impact.py'), '--binary', str(binary),
                       '--project', str(project), '--base-project', str(project), '--base', base,
                       '--base-graph', str(graph), '--graph-file', str(graph), '--snapshot-max-mib', '128']
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            recorded = [json.loads(line) for line in calls.read_text().splitlines()]
            self.assertEqual(['impact', 'verify-snapshot', 'verify-snapshot'], [row[0] for row in recorded])
            for row in recorded:
                self.assertEqual('128', row[row.index('--snapshot-max-mib') + 1])
            default = subprocess.run(command[:-2], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, default.returncode, default.stderr)
            for row in [json.loads(line) for line in calls.read_text().splitlines()][-3:]:
                self.assertNotIn('--snapshot-max-mib', row)
            without_base = command.copy()
            at = without_base.index('--base-project')
            del without_base[at:at + 2]
            self.assertEqual(64, subprocess.run(without_base + ['--base-input', 'external/example=unused'],
                                                capture_output=True, timeout=30).returncode)
            for value in ['0', '129', 'bad']:
                invalid = command[:-1] + [value]
                self.assertEqual(64, subprocess.run(invalid, capture_output=True, timeout=30).returncode)
            self.assertEqual(64, subprocess.run(command + ['--snapshot-max-mib', '64'],
                                                capture_output=True, timeout=30).returncode)

    def test_deleted_source_keeps_old_callers_and_noop_is_explicit(self):
        javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if "JAVA_HOME" in os.environ else shutil.which("javac")
        if not javac:
            self.skipTest("javac is required for the compiler/Git fixture")
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            def run(*args, expected=0):
                result = subprocess.run(list(map(str, args)), cwd=project, capture_output=True, text=True, timeout=30)
                self.assertEqual(expected, result.returncode, result.stderr)
                return result.stdout
            run("git", "init", "-q")
            run("git", "config", "user.email", "fixture@example.invalid")
            run("git", "config", "user.name", "Fixture")
            source = project / "source space"
            source.mkdir()
            (source / "Caller.java").write_text('public class Caller {void run() throws Exception {Class.forName("Changed");}}')
            (source / "Changed.java").write_text("public class Changed {static void run(){}}")
            run("git", "add", "--", "source space")
            run("git", "commit", "-qm", "base")
            base = run("git", "rev-parse", "HEAD").strip()
            classes = project / "classes"
            classes.mkdir()
            run(javac, "-g", "-d", classes, *source.glob("*.java"))
            before = project / "base.json"
            before.write_text(run(BINARY, "snapshot", "--classes", classes, "--project", project,
                "--include-paths", "--revision", base, "--scope", "fixture:main"))
            (source / "Changed.java").unlink()
            run("git", "add", "-u")
            run("git", "commit", "-qm", "remove")
            current = run("git", "rev-parse", "HEAD").strip()
            fresh = project / "fresh"
            fresh.mkdir()
            run(javac, "-g", "-d", fresh, source / "Caller.java")
            after = project / "current.json"
            after.write_text(run(BINARY, "snapshot", "--classes", fresh, "--project", project,
                "--include-paths", "--revision", current, "--scope", "fixture:main"))
            command = [sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY, "--project", project,
                "--base", base, "--base-graph", before, "--graph-file", after]
            report = json.loads(run(*command))
            self.assertIn("class:Changed", [item["usr"] for item in report["changed"]])
            self.assertIn("method:Caller#run()V", [item["usr"] for item in report["affected"]])
            self.assertEqual("found", report["status"])
            self.assertEqual("unverified", report["freshness"]["current"]["status"])
            empty_bindings = {"format": "kartograph-local-input-bindings", "version": 1, "bindings": {}}
            current_bindings = project / "current-bindings.json"
            base_bindings = project / "base-bindings.json"
            current_bindings.write_text(json.dumps(empty_bindings))
            base_bindings.write_text(json.dumps(empty_bindings))
            bound_command = command + ["--base-project", project, "--input-bindings", current_bindings,
                                       "--base-input-bindings", base_bindings, "--snapshot-max-mib", "128"]
            bound = json.loads(run(*bound_command))
            self.assertEqual("unverified", bound["freshness"]["base"]["status"])
            base_bindings.write_text("{}")
            run(*bound_command, expected=2)
            base_bindings.write_text(json.dumps(empty_bindings))
            current_bindings.write_text("{}")
            run(*bound_command, expected=2)
            current_bindings.write_text(json.dumps(empty_bindings))
            strict = json.loads(run(*command, "--strict", expected=1))
            self.assertEqual("found", strict["status"])
            self.assertEqual("unverified", strict["freshness"]["current"]["status"])
            original_class = (fresh / "Caller.class").read_bytes()
            (fresh / "Caller.class").write_bytes(original_class + b"changed")
            stale = json.loads(run(*command))
            self.assertEqual("stale", stale["freshness"]["current"]["status"])
            run(*command, "--strict", expected=1)
            (fresh / "Caller.class").write_bytes(original_class)
            self.assertNotIn(str(project), json.dumps(report))
            noop = command.copy()
            noop[noop.index("--base") + 1] = current
            noop[noop.index("--base-graph") + 1] = after
            self.assertEqual("noChanges", json.loads(run(*noop))["status"])
            mismatch = command.copy()
            mismatch[mismatch.index("--base-graph") + 1] = after
            run(*mismatch, expected=2)
            moved = project / "renamed folder"
            moved.mkdir()
            run("git", "mv", "source space/Caller.java", "renamed folder/Caller.java")
            run("git", "commit", "-qm", "rename")
            renamed = run("git", "rev-parse", "HEAD").strip()
            run(javac, "-g", "-d", fresh, moved / "Caller.java")
            renamed_graph = project / "renamed.json"
            renamed_graph.write_text(run(BINARY, "snapshot", "--classes", fresh, "--project", project,
                "--include-paths", "--revision", renamed, "--scope", "fixture:main"))
            compare = [sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY, "--project", project,
                "--base", current, "--base-graph", after, "--graph-file", renamed_graph]
            renamed_report = json.loads(run(*compare))
            self.assertEqual([], renamed_report["unresolved"])
            self.assertIn("class:Caller", [item["usr"] for item in renamed_report["changed"]])
            (project / "runtime-config.toml").write_text("enabled = false\n")
            run("git", "add", "--", "runtime-config.toml")
            run("git", "commit", "-qm", "config")
            configured = run("git", "rev-parse", "HEAD").strip()
            config_graph = project / "configured.json"
            config_graph.write_text(run(BINARY, "snapshot", "--classes", fresh, "--project", project,
                "--include-paths", "--revision", configured, "--scope", "fixture:main"))
            unresolved = json.loads(run(sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY,
                "--project", project, "--base", renamed, "--base-graph", renamed_graph, "--graph-file", config_graph,
                "--strict", expected=1))
            self.assertEqual("unmappedFile", unresolved["unresolved"][0]["reason"])
            report_only = json.loads(run(sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY,
                "--project", project, "--base", renamed, "--base-graph", renamed_graph, "--graph-file", config_graph))
            self.assertEqual("notFound", report_only["status"])
            self.assertEqual("unmappedFile", report_only["unresolved"][0]["reason"])

    def test_usage_errors_keep_the_usage_exit_code(self):
        result = subprocess.run([sys.executable, str(ROOT / "Scripts/check-impact.py"), "--unknown"],
            capture_output=True, text=True, timeout=20)
        self.assertEqual(64, result.returncode)
        self.assertNotIn("Traceback", result.stderr)
