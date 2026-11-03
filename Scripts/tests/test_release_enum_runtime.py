"""readiness의 실제 enum Python 진입점이 빌드가 정한 Kotlin runtime을 엄격히 소비하는지 검증한다."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "Scripts/verify-release-readiness.sh"

# compiler/CLI 실행만 대체하고 실제 heredoc, artifact 선택과 해시 봉인은 실행한다.
DRIVER = r'''
import json
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
import enum_decisions
import run

def comparison(javac, java, collector, classpath, stdlib, evidence, *unused):
    with Path("calls.jsonl").open("a") as output:
        output.write(json.dumps({"stdlib": str(stdlib), "classpath": classpath}) + "\n")
    for language in ("javac", "kotlin"):
        folder = evidence / ("snapshot-" + language)
        folder.mkdir()
        for name in ("base.json", "current.json", "current-source-uses.json", "current-combined.json", "external.json"):
            (folder / name).write_text("{}\n")

enum_decisions._snapshot_comparison = comparison
run.java_home = lambda: Path.cwd() / "jdk"
script = Path(sys.argv[2])
source = script.read_text().split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
sys.argv = ["-", *sys.argv[3:]]
exec(compile(source, str(script) + ":packaged-enum", "exec"), {"__name__": "__main__"})
'''


class ReleaseEnumRuntimeTest(unittest.TestCase):
    def run_entrypoint(self, version="2.4.20", stdlib_versions=("2.4.20",), compiler_versions=("2.4.20",)):
        temporary = tempfile.TemporaryDirectory(prefix="release-enum-runtime-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)

        def artifact(name):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
            return path

        runtime = [artifact("runtime/kotlin-compiler-embeddable-" + value + ".jar") for value in compiler_versions]
        runtime += [artifact("runtime/kotlin-stdlib-" + value + ".jar") for value in stdlib_versions]
        manifest = root / "runtime.json"
        manifest.write_text(json.dumps({"kotlinVersion": version, "files": list(map(str, runtime))}))
        collector = artifact("collector.jar")
        artifact("compiler-collectors/build/distributions/kartograph-compiler-collectors-1.2.3.zip")
        for label in ("zip", "tar"):
            artifact("cli/build/distributions/kartograph-1.2.3." + label)
            artifact(label + "/bin/kartograph")
        reports = root / "reports"
        reports.mkdir()
        result = subprocess.run([sys.executable, "-c", DRIVER, str(ROOT / "compiler-collectors/tests"),
                                 str(SCRIPT), "1.2.3", str(collector), str(root / "zip/bin/kartograph"),
                                 str(root / "tar/bin/kartograph"), str(manifest), str(reports)],
                                cwd=root, text=True, capture_output=True, timeout=10)
        calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()] if (root / "calls.jsonl").exists() else []
        return result, calls, reports

    def test_current_build_version_reaches_both_packaged_snapshot_comparisons(self):
        result, calls, reports = self.run_entrypoint()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))
        self.assertTrue(all(Path(call["stdlib"]).name == "kotlin-stdlib-2.4.20.jar" for call in calls))
        for label in ("zip", "tar"):
            receipt = json.loads((reports / ("packaged-enum-" + label) / "verification.json").read_text())
            self.assertEqual("passed", receipt["status"])
            self.assertEqual("2.4.20", receipt["kotlinVersion"])
            self.assertEqual(2, len(receipt["artifacts"]["compilerRuntimeSha256"]))

    def test_version_is_taken_from_the_build_manifest(self):
        result, calls, reports = self.run_entrypoint("2.4.21", ("2.4.21",), ("2.4.21",))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, len(calls))
        self.assertTrue(all(Path(call["stdlib"]).name == "kotlin-stdlib-2.4.21.jar" for call in calls))

    def test_missing_wrong_prefix_or_duplicate_artifacts_fail_before_compilation(self):
        cases = [((), ("2.4.20",)), (("2.4.10",), ("2.4.20",)),
                 (("2.4.20-extra",), ("2.4.20",)), (("2.4.20", "2.4.21"), ("2.4.20",)),
                 (("2.4.20",), ()), (("2.4.20",), ("2.4.10",)),
                 (("2.4.20",), ("2.4.20", "2.4.21"))]
        for stdlib, compiler in cases:
            with self.subTest(stdlib=stdlib, compiler=compiler):
                result, calls, reports = self.run_entrypoint(stdlib_versions=stdlib, compiler_versions=compiler)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)
                self.assertFalse((reports / "packaged-enum-zip").exists())
