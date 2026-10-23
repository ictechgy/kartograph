"""권한 회귀 결과가 없거나 생략된 XML을 성공 근거로 쓰지 않는다."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("permission_evidence", Path(__file__).resolve().parents[1] / "verify-posix-permission-tests.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class PosixPermissionEvidenceTest(unittest.TestCase):
    def write_results(self, root, outcome=None):
        for suite_name, case_name in probe.REQUIRED.items():
            suite = ET.Element("testsuite", name=suite_name, tests="1", skipped="0", failures="0", errors="0")
            case = ET.SubElement(suite, "testcase", name=case_name, classname=suite_name)
            if outcome in ("skipped", "failure", "error"):
                ET.SubElement(case, outcome)
            elif outcome == "absent":
                suite.remove(case)
            elif outcome == "duplicate":
                ET.SubElement(suite, "testcase", name=case_name, classname=suite_name)
            elif outcome == "wrong-class":
                case.set("classname", "different.Class")
            (root / f"TEST-{suite_name}.xml").write_bytes(ET.tostring(suite))

    def test_exact_executed_cases_produce_digest_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_results(root)
            results = probe.verify(root)
            self.assertEqual(2, len(results))
            self.assertTrue(all(item["executed"] == 1 and len(item["xmlSha256"]) == 64 for item in results))

    def test_missing_or_nonexecuted_cases_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(FileNotFoundError):
                probe.verify(root)
            for outcome in ("skipped", "failure", "error", "absent", "duplicate", "wrong-class"):
                with self.subTest(outcome=outcome):
                    self.write_results(root, outcome)
                    with self.assertRaises(ValueError):
                        probe.verify(root)


if __name__ == "__main__":
    unittest.main()
