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



def xml_report():
    names = ("child", "parent")
    namespace = MODULE.PACKAGE.replace("/", ".")
    definitions = [{"id": name, "path": "src/main/res/layout/" + name + ".xml", "sourceSha256": "c" * 64,
                    "key": {"namespace": namespace, "type": "layout", "name": name}} for name in names]
    fields = sorted(MODULE.field(name) for name in names)
    affected = []
    for name in names:
        target, caller, test = MODULE.field(name), MODULE.accessor(name), MODULE.test_method(name)
        for usr, nodes, kinds in ((caller, [caller, target], ["fieldAccess"]),
                                 (test, [test, caller, target], ["call", "fieldAccess"])):
            affected.append({"usr": usr, "paths": [{"revision": "current", "nodes": nodes, "edges": [
                {"source": nodes[index], "target": nodes[index + 1], "kind": kind,
                 "origin": "bytecode", "traversal": "dependency"} for index, kind in enumerate(kinds)]}]})
    selection = {"status": "admitted", "coverage": "partial", "scope": "::debug", "namespace": namespace,
                 "files": [{"requested": "src/main/res/layout/child.xml", "status": "matched", "definitions": [definitions[0]]}],
                 "resources": [{"key": row["key"], "definitions": [row], "field": MODULE.field(row["key"]["name"]), "unavailable": None}
                               for row in definitions],
                 "includes": [{"sourceDefinitionId": "parent", "kind": "literal", "target": definitions[0]["key"]}],
                 "selectedFields": fields, "binding": {"producerArtifact": ":processDebugResources",
                     "resourceFactsSha256": "a" * 64, "producerSha256": "b" * 64, "output": {"role": "classes"}}}
    return {"format": "kartograph-impact", "version": 1, "status": "found", "unresolved": [],
            "changed": [{"usr": value} for value in fields], "affected": affected,
            "selectedByRevision": {"current": fields}, "resourceSelections": {"current": selection}}

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
                 "xmlImpact": [{"file": "src/main/res/layout/screen.xml", "sourceSha256": "b" * 64}],
                 "resourceBindings": {"status": "captured", "scope": "::debug", "namespace": "dev.kartograph.compat",
                                      "boundFields": [MODULE.field(name) for name in MODULE.LAYOUTS]},
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
        self.assertEqual(summary["coverage"]["xmlFileSelection"], "exactCapturedResourceFields")
        self.assertNotIn("private", str(summary))
        self.assertNotIn("sourceSha256", summary["phases"][0])
        after["sourceSha256"]["/host/private/source.java"] = "f" * 64
        self.assertFalse(MODULE.log_summary(report)["sourceHashesUnchanged"])

    def test_binding_receipt_requires_exact_scope_roots_xml_fields_and_include(self):
        namespace = MODULE.PACKAGE.replace("/", ".")
        root = {"role": "android-resources", "path": "src/main/res", "sha256": "a" * 64}
        absent_root = {"role": "android-resources", "path": MODULE.ABSENT_ROOT, "sha256": "d" * 64}
        roots = [absent_root, root]
        namespace_bytes = ("namespace=" + namespace).encode("utf-8")
        options = {"role": "options", "path": "processDebugResources-options",
                   "sha256": MODULE.hashlib.sha256(len(namespace_bytes).to_bytes(4, "big") + namespace_bytes).hexdigest()}
        output = {"role": "classes", "path": "build/R.jar", "sha256": "b" * 64}
        definitions = [{"id": name, "path": "src/main/res/layout/" + name + ".xml",
                        "sourceSha256": "c" * 64, "key": {"namespace": namespace, "type": "layout", "name": name}}
                       for name in MODULE.LAYOUTS]
        facts = {"admission": "unverified", "scope": "::debug", "namespace": namespace,
                 "roots": [{"input": item} for item in roots], "definitions": definitions,
                 "includes": [{"sourceDefinitionId": "parent", "kind": "literal",
                               "target": {"namespace": namespace, "type": "layout", "name": "child"}}]}
        bindings = {"scope": "::debug", "namespace": namespace, "mainRoots": roots, "namespaceOptions": options,
                    "producerArtifact": ":processDebugResources", "output": output,
                    "outcomes": [{"key": row["key"], "field": MODULE.field(row["key"]["name"]), "unavailable": None}
                                 for row in definitions]}
        snapshot = {"scope": "::debug", "androidResources": facts, "androidResourceBindings": bindings,
                    "provenance": {"inputs": [output], "witnesses": [
                        {"scope": "::debug", "compiler": "agp-process-resources", "artifact": ":processDebugResources",
                         "inputs": [*roots, options], "outputs": [output]}]}}
        hashes = {name: "c" * 64 for name in MODULE.LAYOUTS}
        receipt = MODULE.verify_resource_bindings(snapshot, hashes)
        self.assertEqual(receipt["boundFields"], [MODULE.field(name) for name in MODULE.LAYOUTS])
        for mutation in ("absent", "namespace", "scope", "field", "missing-key", "duplicate-key",
                         "xml", "roots", "producer", "output", "include", "uniform-scope",
                         "uniform-artifact", "uniform-key-namespace", "options", "absent-root"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(snapshot)
                binding = value["androidResourceBindings"]
                if mutation == "absent":
                    value["androidResourceBindings"] = None
                elif mutation in ("namespace", "scope"):
                    binding[mutation] = "other"
                elif mutation == "uniform-scope":
                    value["scope"] = binding["scope"] = value["androidResources"]["scope"] = "other"
                    value["provenance"]["witnesses"][0]["scope"] = "other"
                elif mutation == "uniform-artifact":
                    binding["producerArtifact"] = value["provenance"]["witnesses"][0]["artifact"] = ":other"
                elif mutation == "uniform-key-namespace":
                    for row in [*binding["outcomes"], *value["androidResources"]["definitions"]]:
                        row["key"]["namespace"] = "other"
                elif mutation == "options":
                    binding["namespaceOptions"]["sha256"] = "f" * 64
                elif mutation == "absent-root":
                    binding["mainRoots"] = [root]
                    value["androidResources"]["roots"] = [{"input": root}]
                    value["provenance"]["witnesses"][0]["inputs"] = [root, options]
                elif mutation == "field":
                    binding["outcomes"][0]["field"] = "field:other/R$layout#screen:I"
                elif mutation == "missing-key":
                    binding["outcomes"].pop()
                elif mutation == "duplicate-key":
                    binding["outcomes"].append(binding["outcomes"][0])
                elif mutation == "xml":
                    value["androidResources"]["definitions"][0]["sourceSha256"] = "d" * 64
                elif mutation == "roots":
                    binding["mainRoots"] = []
                elif mutation == "producer":
                    value["provenance"]["witnesses"] *= 2
                elif mutation == "output":
                    value["provenance"]["inputs"] = []
                else:
                    value["androidResources"]["includes"] = []
                with self.assertRaises(RuntimeError):
                    MODULE.verify_resource_bindings(value, hashes)

    def test_requires_explicit_legacy_resource_refusal(self):
        path = "src/main/res/layout/screen.xml"
        report = {"format": "kartograph-impact", "status": "notFound",
                  "changed": [], "affected": [], "unresolved": [
                      {"requested": path, "reason": "resourceEvidenceUnavailable", "candidates": [], "revision": "current"}]}
        MODULE.verify_resource_unavailable(report, path)
        for mutation in ("empty", "resolved", "wrong-path"):
            value = copy.deepcopy(report)
            if mutation == "empty":
                value["unresolved"] = []
            elif mutation == "resolved":
                value["status"] = "found"
            else:
                value["unresolved"][0]["requested"] = "other.xml"
            with self.assertRaises(RuntimeError):
                MODULE.verify_resource_unavailable(value, path)

    def test_xml_impact_requires_exact_include_fields_tests_and_source_provenance(self):
        value = xml_report()
        receipt = MODULE.verify_xml_impact(value, "child", "c" * 64)
        self.assertEqual(receipt["selectedFields"], sorted([MODULE.field("child"), MODULE.field("parent")]))
        self.assertTrue(receipt["includeParent"])
        for mutation in ("missing-parent", "wrong-revision", "wrong-scope", "wrong-file", "wrong-hash",
                         "wrong-key", "missing-include", "missing-test", "wrong-origin", "unrelated", "missing-binding"):
            with self.subTest(mutation=mutation):
                report = copy.deepcopy(value)
                selection = report["resourceSelections"]["current"]
                if mutation == "missing-parent":
                    report["changed"].pop()
                elif mutation == "wrong-revision":
                    report["selectedByRevision"] = {"base": report["selectedByRevision"]["current"]}
                elif mutation == "wrong-scope":
                    selection["scope"] = ":other:debug"
                elif mutation == "wrong-file":
                    selection["files"][0]["requested"] = "src/other/res/layout/child.xml"
                elif mutation == "wrong-hash":
                    selection["files"][0]["definitions"][0]["sourceSha256"] = "d" * 64
                elif mutation == "wrong-key":
                    selection["resources"][0]["key"]["namespace"] = "other"
                elif mutation == "missing-include":
                    selection["includes"] = []
                elif mutation == "missing-test":
                    report["affected"].pop()
                elif mutation == "wrong-origin":
                    report["affected"][0]["paths"][0]["edges"][0]["origin"] = "runtimeModel"
                elif mutation == "unrelated":
                    report["affected"].append({"usr": MODULE.test_method("unrelated"), "paths": []})
                else:
                    selection["binding"] = {}
                with self.assertRaises(RuntimeError):
                    MODULE.verify_xml_impact(report, "child", "c" * 64)

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
