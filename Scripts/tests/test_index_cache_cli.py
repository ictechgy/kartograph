"""실제 compiler·배포 CLI에서 증분 파싱과 전체 snapshot의 동등성을 확인한다."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(os.environ.get("KARTOGRAPH_BINARY", ROOT / "cli/build/install/kartograph/bin/kartograph")).resolve()


class IndexCacheCliTest(unittest.TestCase):
    def test_cached_capture_reuses_classes_but_recomputes_runtime_and_deletion(self):
        javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if "JAVA_HOME" in os.environ else shutil.which("javac")
        if not javac:
            self.skipTest("javac is required for the compiled cache fixture")
        with tempfile.TemporaryDirectory(prefix="kartograph-cache-cli-") as directory:
            project = Path(directory)
            source = project / "src"
            source.mkdir()
            classes = project / "classes"
            classes.mkdir()
            (source / "Caller.java").write_text('public class Caller { public static void run() throws Exception { Class.forName(Target.name()).getDeclaredConstructor().newInstance(); } }')
            target = source / "Target.java"
            target.write_text('public class Target { public static String name() { return "Used"; } }')
            for name in ("Used", "Spare"):
                (source / (name + ".java")).write_text('public class ' + name + ' { public ' + name + '() {} }')
            (project / "keep.pro").write_text('-keep class Caller { *; }\n')

            def run(*args, expected=0):
                result = subprocess.run(list(map(str, args)), cwd=project, capture_output=True, text=True, timeout=60)
                self.assertEqual(expected, result.returncode, result.stderr)
                return result

            run(javac, "-g", "-d", classes, *sorted(source.glob("*.java")))
            arguments = ["snapshot", "--classes", classes, "--project", project,
                         "--keep-rules", "keep.pro", "--compact", "--timings"]

            def capture(cached):
                return run(BINARY, *arguments, *(["--index-cache", "cache space"] if cached else []))

            def counter(result, name):
                match = re.search(r"\b" + name + r"=(\d+)\b", result.stderr)
                self.assertIsNotNone(match, result.stderr)
                return int(match.group(1))

            def state(document, symbol, expected=0):
                path = project / "snapshot.json"
                path.write_text(document)
                return json.loads(run(BINARY, "query", symbol, "--graph-file", path, expected=expected).stdout)

            oracle = capture(False)
            first = capture(True)
            self.assertEqual(oracle.stdout, first.stdout)
            self.assertEqual(4, counter(first, "indexParsedClasses"))
            self.assertEqual(0, counter(first, "indexCacheHits"))
            warm = capture(True)
            self.assertEqual(oracle.stdout, warm.stdout)
            self.assertEqual(4, counter(warm, "indexCacheHits"))
            self.assertEqual(0, counter(warm, "indexParsedClasses"))
            self.assertEqual("reachable", state(warm.stdout, "Used")["result"]["reachability"]["state"])
            self.assertEqual("unreachable", state(warm.stdout, "Spare")["result"]["reachability"]["state"])

            output = classes / "Target.class"
            stamp = output.stat()
            target.write_text(target.read_text().replace('"Used"', '"Spare"'))
            run(javac, "-g", "-d", classes, target)
            os.utime(output, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
            updated = capture(True)
            self.assertEqual(capture(False).stdout, updated.stdout)
            self.assertEqual(3, counter(updated, "indexCacheHits"))
            self.assertEqual(1, counter(updated, "indexParsedClasses"))
            self.assertEqual("reachable", state(updated.stdout, "Spare")["result"]["reachability"]["state"])
            self.assertEqual("unreachable", state(updated.stdout, "Used")["result"]["reachability"]["state"])

            (classes / "Spare.class").unlink()
            deleted = capture(True)
            self.assertEqual(capture(False).stdout, deleted.stdout)
            self.assertEqual(3, counter(deleted, "indexCacheHits"))
            self.assertEqual(0, counter(deleted, "indexParsedClasses"))
            self.assertEqual("notFound", state(deleted.stdout, "Spare", expected=64)["status"])
            self.assertNotIn(str(project), deleted.stdout)

    def test_cache_option_is_snapshot_only_and_rejects_duplicate_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            for arguments in [
                ["query", "Name", "--graph-file", "missing.json", "--index-cache", directory],
                ["snapshot", "--classes", directory, "--project", directory,
                 "--index-cache", "one", "--index-cache", "two"],
                ["snapshot", "--classes", directory, "--project", directory, "--index-cache", ""],
            ]:
                result = subprocess.run([str(BINARY), *arguments], capture_output=True, text=True, timeout=30)
                self.assertEqual(64, result.returncode, result.stderr)
