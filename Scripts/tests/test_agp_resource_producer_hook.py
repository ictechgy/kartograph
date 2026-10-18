"""생산자 의존성 뒤 snapshot 진입 시점에만 XML을 바꾸는 fixture hook 계약이다."""
import hashlib
from pathlib import Path
import runpy
import unittest

MODULE = runpy.run_path(str(Path(__file__).resolve().parents[1] / "verify-agp-8-resource-producer.py"))


class ResourceProducerHookTest(unittest.TestCase):
    def test_hook_is_scoped_to_snapshot_and_keeps_normal_producer_dependencies(self):
        sha = hashlib.sha256(b"fixture XML").hexdigest()
        script = MODULE["injection_script"](sha)
        self.assertIn("rootProject.name == 'kartograph-agp-8-smoke'", script)
        self.assertIn("tasks.named('kartographSnapshotDebug')", script)
        self.assertIn("outputs.upToDateWhen { false }", script)
        self.assertIn("doFirst {", script)
        self.assertNotIn("onlyIf", script)
        self.assertNotIn("enabled = false", script)
        self.assertNotIn("__ORIGINAL_SHA__", script)
        self.assertIn(sha, script)
        self.assertLess(script.index("def witnessHash"), script.index("xml.withOutputStream"))
        self.assertLess(script.index("xml.withOutputStream"), script.index("println 'KARTOGRAPH_FIXTURE"))
        self.assertIn("witnessSha256: witnessHash, jarSha256: jarHashes", script)
        self.assertIn("originalXmlSha256: sha, mutatedXmlSha256: fingerprint(xml)", script)
        self.assertIn("stream.write('\\n<!-- producer-only XML tamper -->\\n'.getBytes('UTF-8'))", script)

    def test_non_digest_interpolation_is_refused(self):
        for invalid in ("", "0" * 63, "A" * 64, "'; throw new Exception(); //"):
            with self.subTest(value=invalid), self.assertRaises(RuntimeError):
                MODULE["injection_script"](invalid)


if __name__ == "__main__":
    unittest.main()
