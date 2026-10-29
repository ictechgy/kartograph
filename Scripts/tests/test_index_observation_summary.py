"""기존 관측을 공개할 때 경로·예산·누락·실패 상태가 보존되는지 검사한다."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("index_cost_observations", ROOT / "Scripts/verify-index-cost.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def report_fixture():
    samples = {kind: {"events": 50, "weight": 50 if kind == "jdk.ExecutionSample" else 5000,
                     "inclusive": [[f"dev/kartograph/index/Parser.method{i:02}", 30 - i] for i in range(30)],
                     "nearest": [[f"dev/kartograph/index/Parser.method{i:02}", 30 - i] for i in range(30)]}
               for kind in probe.JFR_EVENTS}
    snapshot = {"loadAverage": [1.25, 2, 3.5], "logicalCpus": 4, "monotonicNanos": 100}
    names = ("graph", "dead", "dead_private", "cycles", "rules", "metrics")
    return {"format": "kartograph-index-cost-v1", "status": "PASS", "scope": "fixture",
            "residualScope": "not-query-only",
            "jfr": {"samples": samples, "command": "dead", "outputMatchesUnprofiledCandidate": True,
                    "scope": "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance",
                    "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
                    "configurationSha256": "a" * 64,
                    "recording": {"size": 10, "sha256": "b" * 64, "path": "/private/recording"},
                    "tool": {"path": "/private/jdk"}, "environment": "PRIVATE"},
            "cliLoad": [{"command": names[index], "sample": sample, "arm": arm,
                         "before": copy.deepcopy(snapshot), "after": copy.deepcopy(snapshot)}
                        for index, sample, order in probe.cli_schedule() for arm in order]}


class ObservationSummaryTest(unittest.TestCase):
    def test_existing_samples_and_all_load_rows_are_bound_without_paths_or_execution(self):
        report = report_fixture()
        before = copy.deepcopy(report)
        with mock.patch.object(probe, "run", side_effect=AssertionError("must not execute")):
            encoded = probe.public_observation_summary(report)
        result = json.loads(encoded)
        self.assertEqual(before, report)
        self.assertEqual(probe.identity_digest(report), result["reportIdentitySha256"])
        self.assertEqual(report["cliLoad"], result["cliLoad"]["rows"])
        self.assertEqual("observed", result["cliLoad"]["status"])
        for kind in probe.JFR_EVENTS:
            selected = result["jfr"]["samples"][kind]
            self.assertEqual(report["jfr"]["samples"][kind]["weight"], selected["weight"])
            self.assertEqual(10, len(selected["nearest"]))
            self.assertEqual(20, selected["nearestOmittedFromStoredTop30"])
        self.assertIn("inclusive-rows-overlap", result["jfr"]["rankingScope"])
        self.assertEqual("not-recorded", result["jfr"]["gcJitAttribution"])
        for forbidden in ("/private", "PRIVATE", "environment", "tool"):
            self.assertNotIn(forbidden, encoded)

    def test_partial_or_absent_evidence_remains_explicit_and_failure_does_not_pass(self):
        report = report_fixture()
        report["status"] = "FAIL"
        del report["jfr"]
        report["cliLoad"] = report["cliLoad"][:3]
        result = json.loads(probe.public_observation_summary(report))
        self.assertEqual("FAIL", result["diagnosticStatus"])
        self.assertEqual({"status": "unavailable"}, result["jfr"])
        self.assertEqual(("partial", 3, 36), tuple(result["cliLoad"][key] for key in
                                                 ("status", "observedRows", "expectedRows")))
        del report["cliLoad"]
        self.assertEqual({"status": "unavailable"}, json.loads(probe.public_observation_summary(report))["cliLoad"])

    def test_method_safety_and_bounds_apply_even_to_rows_not_printed(self):
        for value in ("/private/name", "dev/kartograph/../../secret.run", "dev/kartograph/Foo.run\nPRIVATE",
                      "dev/kartograph/Foo." + "x" * 1024, "dev/kartograph/Foo.run::warning::"):
            report = report_fixture()
            report["jfr"]["samples"]["jdk.ExecutionSample"]["nearest"][-1][0] = value
            with self.subTest(value=value), self.assertRaisesRegex(probe.DiagnosticError, "invalid-public-observation"):
                probe.public_observation_summary(report)

    def test_unknown_events_duplicates_order_and_invalid_weights_are_rejected(self):
        alterations = (
            lambda report: report["jfr"]["samples"].update({"jdk.InitialEnvironmentVariable": {}}),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"].update(events=True),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"].update(weight=51),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"]["nearest"].append(["dev/kartograph/Foo.run", 1]),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"]["nearest"].reverse(),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"]["nearest"][0].__setitem__(1, -1),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"]["nearest"][0].__setitem__(1, 100),
            lambda report: report["jfr"]["recording"].update(sha256="invalid"),
            lambda report: report["jfr"]["recording"].update(size=probe.JFR_LIMIT + 1),
            lambda report: report["jfr"].update(outputMatchesUnprofiledCandidate=False),
            lambda report: report["jfr"].update(scope="unknown-capture"),
            lambda report: report["jfr"]["samples"]["jdk.ExecutionSample"]["nearest"][1].__setitem__(0, "dev/kartograph/index/Parser.method00"),
        )
        for index, alter in enumerate(alterations):
            report = report_fixture()
            alter(report)
            with self.subTest(index=index), self.assertRaisesRegex(probe.DiagnosticError, "invalid-public-observation"):
                probe.public_observation_summary(report)

    def test_load_rejects_extra_rows_duplicates_nonfinite_and_invalid_identity(self):
        alterations = (
            lambda rows: rows.append(copy.deepcopy(rows[0])),
            lambda rows: rows.__setitem__(1, copy.deepcopy(rows[0])),
            lambda rows: rows[0].update(command="/private-command"),
            lambda rows: rows[0].update(sample=True),
            lambda rows: rows[0].update(arm="other"),
            lambda rows: rows[0]["before"].update(loadAverage=[float("nan"), 0, 0]),
            lambda rows: rows[0]["before"].update(loadAverage=[float("inf"), 0, 0]),
            lambda rows: rows[0]["before"].update(loadAverage=[True, 0, 0]),
            lambda rows: rows[0]["before"].update(logicalCpus=0),
            lambda rows: rows[0]["after"].update(monotonicNanos=99),
        )
        for index, alter in enumerate(alterations):
            report = report_fixture()
            alter(report["cliLoad"])
            with self.subTest(index=index), self.assertRaisesRegex(probe.DiagnosticError, "invalid-public-observation"):
                probe.public_observation_summary(report)

    def test_worst_allowed_names_and_load_fit_the_independent_log_budget(self):
        report = report_fixture()
        for kind in probe.JFR_EVENTS:
            for field in ("inclusive", "nearest"):
                for index, row in enumerate(report["jfr"]["samples"][kind][field]):
                    prefix = "dev/kartograph/" + "a" * 980 + ".method"
                    row[0] = prefix + str(index).zfill(1024 - len(prefix))
        encoded = probe.public_observation_summary(report)
        self.assertLessEqual(len(encoded.encode("utf-8")), 65536)

    def test_write_retains_full_report_and_emits_two_independently_parseable_documents(self):
        report = report_fixture()
        with tempfile.TemporaryDirectory() as name, contextlib.redirect_stdout(io.StringIO()) as output:
            path = Path(name) / "result.json"
            self.assertEqual(0, probe.write_report(report, path))
            stored = json.loads(path.read_text())
        documents = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(2, len(documents))
        self.assertEqual("kartograph-index-cost-v1", documents[0]["format"])
        self.assertEqual("kartograph-index-observations-v1", documents[1]["format"])
        self.assertEqual(report, stored)
        self.assertEqual(probe.identity_digest(stored), documents[1]["reportIdentitySha256"])

    def test_invalid_public_observation_is_a_failed_diagnostic_with_raw_evidence_retained(self):
        report = report_fixture()
        report["cliLoad"][0]["before"]["logicalCpus"] = -1
        with tempfile.TemporaryDirectory() as name, contextlib.redirect_stdout(io.StringIO()) as output:
            path = Path(name) / "result.json"
            self.assertEqual(1, probe.write_report(report, path))
            stored = json.loads(path.read_text())
        documents = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual("FAIL", stored["status"])
        self.assertEqual(-1, stored["cliLoad"][0]["before"]["logicalCpus"])
        self.assertEqual("FAIL", documents[0]["status"])
        self.assertEqual("invalid-public-observation", documents[1]["reason"])

    def test_existing_failure_reason_survives_a_second_summary_failure(self):
        report = report_fixture()
        report.update(status="FAIL", reason="source-commit-or-tree-changed")
        report["cliLoad"][0]["before"]["logicalCpus"] = -1
        with tempfile.TemporaryDirectory() as name, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, probe.write_report(report, Path(name) / "result.json"))
        self.assertEqual("source-commit-or-tree-changed", report["reason"])
        self.assertEqual("invalid-public-observation", report["observationSummaryFailure"])


if __name__ == "__main__":
    unittest.main()
