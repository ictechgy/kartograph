"""JUnit 검증은 생략/누락/실패를 실제 processor 실행 성공으로 바꾸지 않는다."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "verify-databinding-processor-test.py"
SPEC = importlib.util.spec_from_file_location("binding_test_evidence", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DataBindingProcessorEvidenceTest(unittest.TestCase):
    def fixture(self, root):
        suite = ET.Element("testsuite", name=MODULE.SUITE, tests="1", skipped="0", failures="0", errors="0")
        ET.SubElement(suite, "testcase", name=MODULE.CASE, classname=MODULE.SUITE)
        ET.SubElement(suite, "system-out").text = "\n".join(MODULE.MARKERS)
        return suite

    def save(self, root, suite):
        ET.ElementTree(suite).write(root / f"TEST-{MODULE.SUITE}.xml", encoding="utf-8")

    def test_complete_case_preserves_xml_and_observations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.save(root, self.fixture(root))
            result = MODULE.verify(root)
            self.assertEqual(1, result["executed"])
            self.assertEqual(64, len(result["xmlSha256"]))
            self.assertEqual(list(MODULE.MARKERS), result["observations"])
            self.assertIn("full default-retention closure and Activity-entry-only closure are separate", result["scope"])

    def test_absent_skipped_failed_duplicate_or_missing_observation_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(FileNotFoundError):
                MODULE.verify(root)
            for change in (
                lambda s: s.set("skipped", "1"),
                lambda s: s.set("failures", "1"),
                lambda s: ET.SubElement(s.find("testcase"), "skipped"),
                lambda s: s.find("testcase").set("name", "unrelated"),
                lambda s: ET.SubElement(s, "testcase", name=MODULE.CASE, classname=MODULE.SUITE),
                lambda s: setattr(s.find("system-out"), "text", MODULE.MARKERS[0]),
                lambda s: setattr(s.find("system-out"), "text", "\n".join(MODULE.MARKERS + (MODULE.MARKERS[0],))),
                lambda s: setattr(s.find("system-out"), "text", "\n".join(MODULE.MARKERS).replace(" scope=activity-entry", "")),
                lambda s: setattr(s.find("system-out"), "text", "\n".join(MODULE.MARKERS[:2] + MODULE.MARKERS[3:])),
            ):
                suite = self.fixture(root)
                change(suite)
                self.save(root, suite)
                with self.assertRaises(ValueError):
                    MODULE.verify(root)
