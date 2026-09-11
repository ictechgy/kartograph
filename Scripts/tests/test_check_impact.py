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
