"""assert로 판정하는 검증 스크립트가 -O/PYTHONOPTIMIZE에서 검사 없이 통과하지 않는지 실제 실행으로 확인한다."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
GUIDANCE = "requires Python assertions; remove -O/PYTHONOPTIMIZE.\n"
VERIFIERS = (
    ("verify-runtime-contracts.py", "Runtime contract verification"),
    ("verify-dependency-fixture.py", "Dependency fixture verification"),
)


def clean_environment(**changes):
    """JAVA_HOME·ANDROID_HOME·PYTHON* 없이 실행한다. 가드가 없으면 환경·인자 오류로 다른 문구가 나온다."""
    environment = {"PATH": os.environ.get("PATH", os.defpath), "LC_ALL": "C"}
    environment.update(changes)
    return environment


def check_heredoc():
    """verify-agp-8-fixture.sh가 python3에 넘기는 CHECK heredoc 본문을 꺼낸다."""
    lines = (SCRIPTS / "verify-agp-8-fixture.sh").read_text(encoding="utf-8").splitlines()
    start = lines.index("python3 - <<'CHECK'")
    end = lines.index("CHECK", start + 1)
    return "\n".join(lines[start + 1:end]) + "\n"


def run_python(arguments, environment, cwd=None, stdin=None):
    return subprocess.run([sys.executable, *arguments], input=stdin, cwd=cwd, env=environment,
                          capture_output=True, text=True, timeout=60)


class AssertionGuardTest(unittest.TestCase):
    def test_verifiers_refuse_optimized_python_before_environment_and_arguments(self):
        for script, label in VERIFIERS:
            for flags, environment in ((["-O"], clean_environment()), ([], clean_environment(PYTHONOPTIMIZE="1"))):
                with self.subTest(script=script, flags=flags, environment=sorted(environment)):
                    result = run_python([*flags, str(SCRIPTS / script)], environment)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", result.stdout)
                    self.assertEqual(f"{label} {GUIDANCE}", result.stderr)

    def test_verifiers_keep_their_own_failures_when_assertions_are_enabled(self):
        for script, _ in VERIFIERS:
            with self.subTest(script=script):
                result = run_python([str(SCRIPTS / script)], clean_environment())
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertNotIn(GUIDANCE.strip(), result.stderr)

    def test_agp_fixture_check_refuses_optimized_python(self):
        check = check_heredoc()
        with tempfile.TemporaryDirectory(prefix="kartograph-assertion-guard-") as temporary:
            reports = Path(temporary) / "fixtures/agp-8-smoke/build/reports/kartograph"
            reports.mkdir(parents=True)
            # 모든 assert를 어기는 보고서다. assert가 살아 있으면 실패해야 하고, 지워지면 가드가 막아야 한다.
            (reports / "debug.txt").write_text("unreachable\tclass:wrong/Finding\n", encoding="utf-8")
            (reports / "debug-graph.json").write_text("{}\n", encoding="utf-8")
            optimized = run_python(["-O", "-"], clean_environment(), cwd=temporary, stdin=check)
            checked = run_python(["-"], clean_environment(), cwd=temporary, stdin=check)

        self.assertNotEqual(0, optimized.returncode)
        self.assertEqual("", optimized.stdout)
        self.assertEqual(f"AGP 8 fixture verification {GUIDANCE}", optimized.stderr)
        self.assertNotEqual(0, checked.returncode)
        self.assertIn("AssertionError", checked.stderr)


if __name__ == "__main__":
    unittest.main()
