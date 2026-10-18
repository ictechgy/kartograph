"""실물 AGP 실험이 빈 입력과 다른 경로를 성공 근거로 쓰지 못하게 한다."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("resource_experiment", ROOT / "Scripts/verify-agp-8-resource-impact.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def field_report():
    target, caller, test = MODULE.field("screen"), MODULE.accessor("screen"), MODULE.test_method("screen")
    def row(usr, nodes, kinds):
        return {"usr": usr, "paths": [{"nodes": nodes, "edges": [
            {"source": nodes[index], "target": nodes[index + 1], "kind": kind,
             "origin": "bytecode", "traversal": "dependency"} for index, kind in enumerate(kinds)
        ]}]}
    return {"format": "kartograph-impact", "version": 1, "status": "found", "unresolved": [],
            "changed": [{"usr": target}], "affected": [
                row(caller, [caller, target], ["fieldAccess"]),
                row(test, [test, caller, target], ["call", "fieldAccess"]),
            ]}


class ResourceExperimentContractTest(unittest.TestCase):
    def test_requires_exact_field_caller_and_test_witnesses(self):
        self.assertEqual(2, len(MODULE.verify_field_path(field_report(), "screen")))
        for mutation in ("empty", "wrong-field", "missing-test", "wrong-edge", "wrong-origin", "wrong-direction"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(field_report())
                if mutation == "empty":
                    value["affected"] = []
                elif mutation == "wrong-field":
                    value["changed"][0]["usr"] = MODULE.field("unrelated")
                elif mutation == "missing-test":
                    value["affected"].pop()
                else:
                    edge = value["affected"][1]["paths"][0]["edges"][1]
                    edge[{"wrong-edge": "kind", "wrong-origin": "origin", "wrong-direction": "source"}[mutation]] = "wrong"
                with self.assertRaises(RuntimeError):
                    MODULE.verify_field_path(value, "screen")

    def test_rejects_other_layout_accessors_and_tests_but_allows_other_graph_nodes(self):
        for other in MODULE.LAYOUTS:
            if other == "screen":
                continue
            for usr in (MODULE.accessor(other), MODULE.test_method(other)):
                with self.subTest(usr=usr):
                    report = field_report()
                    report["affected"].append({"usr": usr, "paths": []})
                    with self.assertRaisesRegex(RuntimeError, "unrelated layout accessor/test"):
                        MODULE.verify_field_path(report, "screen")
        report = field_report()
        report["affected"].append({"usr": "class:dev/kartograph/compat/ResourceAccess", "paths": []})
        self.assertEqual(2, len(MODULE.verify_field_path(report, "screen")))

    def test_log_summary_preserves_evidence_without_host_paths_or_raw_output(self):
        phase = {"phase": "before", "snapshotSha256": "a" * 64, "freshness": "matched",
                 "xmlSha256": {name: "b" * 64 for name in MODULE.LAYOUTS},
                 "sourceSha256": {"/host/private/source.java": "c" * 64},
                 "executedTests": [{"class": "dev.kartograph.compat.ResourceAccessTest", "method": "screen"}],
                 "fieldWitnesses": {"screen": MODULE.verify_field_path(field_report(), "screen")},
                 "unmappedResources": ["src/main/res/layout/screen.xml"],
                 "stdout": "private raw output"}
        after = copy.deepcopy(phase)
        after["phase"] = "after"
        after["xmlSha256"]["child"] = "d" * 64
        report = {"format": "kartograph-agp-resource-impact-experiment", "version": 1,
                  "status": "evidenceEstablishedWithKnownMappingGap", "sourceRevision": "e" * 40,
                  "toolchain": {"agp": "8.7.3"}, "phases": [phase, after],
                  "environment": "private environment"}
        summary = MODULE.log_summary(report)
        self.assertEqual(summary["phases"][0]["fieldWitnesses"], phase["fieldWitnesses"])
        self.assertEqual(summary["phases"][1]["xmlSha256"]["child"], "d" * 64)
        self.assertTrue(summary["sourceHashesUnchanged"])
        self.assertEqual(summary["coverage"]["oldSnapshotAfterXmlEdit"], "stale")
        self.assertEqual(summary["coverage"]["xmlFileSelection"], "unmappedFile")
        self.assertNotIn("private", str(summary))
        self.assertNotIn("sourceSha256", summary["phases"][0])
        after["sourceSha256"]["/host/private/source.java"] = "f" * 64
        self.assertFalse(MODULE.log_summary(report)["sourceHashesUnchanged"])

    def test_requires_explicit_unmapped_selection(self):
        path = "src/main/res/layout/screen.xml"
        report = {"format": "kartograph-impact", "version": 1, "status": "notFound",
                  "changed": [], "affected": [], "unresolved": [{"requested": path, "reason": "unmappedFile"}]}
        MODULE.verify_unmapped(report, path)
        for mutation in ("empty", "resolved", "wrong-path"):
            value = copy.deepcopy(report)
            if mutation == "empty":
                value["unresolved"] = []
            elif mutation == "resolved":
                value["status"] = "found"
            else:
                value["unresolved"][0]["requested"] = "other.xml"
            with self.assertRaises(RuntimeError):
                MODULE.verify_unmapped(value, path)

    def test_requires_executed_unskipped_junit_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(RuntimeError):
                MODULE.verify_junit(root)
            cases = "".join('<testcase classname="dev.kartograph.compat.ResourceAccessTest" name="' + name + '"/>'
                            for name in MODULE.LAYOUTS)
            report = root / "TEST-resource.xml"
            report.write_text("<testsuite>" + cases + "</testsuite>", encoding="utf-8")
            self.assertEqual(4, len(MODULE.verify_junit(root)))
            report.write_text("<testsuite>" + cases.replace('name="screen"/>',
                             'name="screen"><skipped/></testcase>') + "</testsuite>", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                MODULE.verify_junit(root)


if __name__ == "__main__":
    unittest.main()
