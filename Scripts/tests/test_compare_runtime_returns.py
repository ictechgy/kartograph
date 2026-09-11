"""실험 준비 실패가 도구 실행이나 원시 traceback으로 이어지지 않는지 검사한다."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReturnComparisonPreparationTest(unittest.TestCase):
    def test_missing_java_home_reports_actionable_usage_without_traceback(self):
        script = Path(__file__).resolve().parents[1] / "compare-runtime-returns.py"
        environment = dict(os.environ)
        environment.pop("JAVA_HOME", None)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            result = subprocess.run([sys.executable, str(script), "--baseline", str(script),
                "--binary", str(script), "--searchdeadcode", str(script), "--r8", str(script),
                "--output", str(output)], env=environment, capture_output=True, text=True, timeout=20)
            self.assertEqual(2, result.returncode)
            self.assertIn("JAVA_HOME", result.stderr)
            self.assertNotIn("Traceback", result.stderr)
            self.assertFalse(output.exists())
