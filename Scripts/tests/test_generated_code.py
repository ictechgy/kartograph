"""생성 annotation의 실제 javac 산출물을 보고 집합과 양방향 대조한다."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_check_pr import BINARY, ROOT


class GeneratedCodeTest(unittest.TestCase):
    def test_generated_factory_and_nested_classes_are_excluded_but_user_lookalike_remains(self):
        with tempfile.TemporaryDirectory(prefix="kartograph-generated-") as temporary:
            project = Path(temporary)
            sources = sorted((ROOT / "fixtures/generated-code-corpus/src").rglob("*.java"))
            subprocess.run(["javac", "-g", "-d", str(project / "classes"), *map(str, sources)], check=True)
            (project / "res").mkdir()
            (project / "AndroidManifest.xml").write_text("<manifest><application/></manifest>")
            (project / "keep.pro").write_text("-keep interface dagger.internal.DaggerGenerated\n")
            result = subprocess.run([
                str(BINARY), "dead", "--classes", str(project / "classes"), "--project", str(project),
                "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture",
                "--keep-rules", "keep.pro", "--report-format", "json", "--strict",
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)
            document = json.loads(result.stdout)
            self.assertEqual([item["nodeId"] for item in document["diagnostics"]],
                             ["class:User_Factory"])
            self.assertTrue(any("generation markers" in item for item in document["limitations"]))
            self.assertTrue(any("annotation values" in item for item in document["limitations"]))


if __name__ == "__main__":
    unittest.main()
