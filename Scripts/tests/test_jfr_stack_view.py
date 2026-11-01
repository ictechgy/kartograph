"""JFR 표시 깊이는 녹화의 완전성·rank tail·성능 수치와 별도 근거다."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("stack_view_probe", Path(__file__).parents[1] / "verify-index-cost.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("module specification with a loader is required")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class JfrStackViewTest(unittest.TestCase):
    def original(self):
        frame = {"method": {"type": {"name": "dev/kartograph/index/Observed"}, "name": "run"}}
        events = [{"type": kind, "values": {"stackTrace": {"frames": [frame], "truncated": False},
                    **({"weight": 4096} if kind == "jdk.ObjectAllocationSample" else {})}}
                  for kind in probe.JFR_EVENTS]
        return {"scope": "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance",
                "command": "dead", "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
                "configurationSha256": "a" * 64, "recording": {"size": 128, "sha256": "b" * 64},
                "outputMatchesUnprofiledCandidate": True,
                "samples": probe.summarize_jfr(json.dumps({"recording": {"events": events}}))}

    def projected(self, original):
        return json.loads(probe.public_observation_summary({"status": "PASS", "jfr": original}))

    def test_legacy_missing_depth_stays_unknown_instead_of_assuming_five(self):
        original = self.original()
        view = self.projected(original)["jfr"]["stackTraceView"]
        self.assertEqual("unavailable", view["status"])
        self.assertIsNone(view["requestedDepth"])
        self.assertFalse(view["completeStacksGuaranteed"])
        self.assertNotIn("stackTraceView", original)

    def test_explicit_view_is_additive_without_changing_counts_or_rankings(self):
        original = self.original()
        old = self.projected(original)
        original["stackTraceView"] = {"requestedDepth": 5, "completeStacksGuaranteed": False}
        current = self.projected(original)
        view = current["jfr"]["stackTraceView"]
        self.assertEqual(("observed", 5, False), (view["status"], view["requestedDepth"], view["completeStacksGuaranteed"]))
        self.assertIn("recording", view["scope"])
        self.assertIn("ranking", view["scope"])
        self.assertNotEqual(old.pop("reportIdentitySha256"), current.pop("reportIdentitySha256"))
        old["jfr"].pop("stackTraceView")
        current["jfr"].pop("stackTraceView")
        self.assertEqual(old, current)

    def test_invalid_depth_completeness_or_extra_fields_are_rejected(self):
        invalid = [None, [], {}, {"requestedDepth": 0, "completeStacksGuaranteed": False},
                   {"requestedDepth": 257, "completeStacksGuaranteed": False},
                   {"requestedDepth": True, "completeStacksGuaranteed": False},
                   {"requestedDepth": 5.0, "completeStacksGuaranteed": False},
                   {"requestedDepth": "5", "completeStacksGuaranteed": False},
                   {"requestedDepth": 5, "completeStacksGuaranteed": True},
                   {"requestedDepth": 5, "completeStacksGuaranteed": 0},
                   {"requestedDepth": 5, "completeStacksGuaranteed": False, "privatePath": "/private/value"}]
        for value in invalid:
            with self.subTest(value=value), self.assertRaisesRegex(probe.DiagnosticError, "invalid-public-observation"):
                original = self.original()
                original["stackTraceView"] = value
                self.projected(original)

    def test_largest_supported_view_still_cannot_certify_complete_stacks(self):
        original = self.original()
        original["stackTraceView"] = {"requestedDepth": 256, "completeStacksGuaranteed": False}
        view = self.projected(original)["jfr"]["stackTraceView"]
        self.assertEqual(256, view["requestedDepth"])
        self.assertFalse(view["completeStacksGuaranteed"])

    def test_print_view_can_omit_a_caller_without_recording_truncation_or_losing_events(self):
        frames = [{"method": {"type": {"name": "dev/kartograph/index/Frame" + str(i)}, "name": "run"}}
                  for i in range(7)]
        def summary(selected):
            event = {"type": "jdk.ExecutionSample", "values": {"stackTrace": {"frames": selected, "truncated": False}}}
            return probe.summarize_jfr(json.dumps({"recording": {"events": [event]}}))
        full, cropped = summary(frames), summary(frames[:5])
        self.assertEqual(full["jdk.ExecutionSample"]["events"], cropped["jdk.ExecutionSample"]["events"])
        caller = "dev/kartograph/index/Frame6.run"
        self.assertIn(caller, dict(full["jdk.ExecutionSample"]["inclusive"]))
        self.assertNotIn(caller, dict(cropped["jdk.ExecutionSample"]["inclusive"]))
        original = self.original()
        original["samples"] = cropped
        original["stackTraceView"] = {"requestedDepth": 5, "completeStacksGuaranteed": False}
        public = self.projected(original)["jfr"]
        self.assertEqual(1, public["samples"]["jdk.ExecutionSample"]["events"])
        self.assertFalse(public["stackTraceView"]["completeStacksGuaranteed"])

    def test_invalid_view_report_keeps_original_evidence_and_exits_failure(self):
        original = self.original()
        original["stackTraceView"] = {"requestedDepth": 5, "completeStacksGuaranteed": True}
        report = {"format": "kartograph-index-cost-v1", "status": "PASS", "scope": "fixture",
                  "residualScope": "not-query-only", "jfr": original}
        path = Path(tempfile.mkdtemp(prefix="invalid-view-")) / "result.json"
        output = io.StringIO()
        with mock.patch("sys.stdout", output):
            self.assertEqual(1, probe.write_report(report, path))
        saved = json.loads(path.read_text())
        self.assertEqual("FAIL", saved["status"])
        self.assertEqual("invalid-public-observation", saved["observationSummaryFailure"])
        self.assertTrue(saved["jfr"]["stackTraceView"]["completeStacksGuaranteed"])
        public = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual("invalid", public[1]["status"])

    def test_mock_capture_pins_depth_five_and_records_the_same_view(self):
        # tool/child 호출은 전부 mock이며 새 JVM/JFR/시간 비교를 실행하지 않는다.
        root = Path(tempfile.mkdtemp(prefix="stack-view-"))
        jdk = root / "jdk"
        tool = jdk / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")
        tool.parent.mkdir(parents=True)
        tool.write_bytes(b"fixture tool identity")
        config = root / "capture.jfc"
        config.write_text("<configuration/>")
        expected = {"exit": 0, "stdout": b"same output", "stderr": b""}
        events = [{"type": kind, "values": {"stackTrace": None,
                   **({"weight": 4096} if kind == "jdk.ObjectAllocationSample" else {})}}
                  for kind in probe.JFR_EVENTS]
        commands = []
        def successful(arguments, cwd, env, **kwargs):
            phase = kwargs["phase"]
            if phase == "jfr-candidate":
                (cwd / "capture.jfr").write_bytes(b"fixture recording")
                return {**expected, "wallNanos": 1, "phase": phase, "rss": {}}
            if phase == "jfr-summary":
                return {"stdout": b"jdk.ExecutionSample 1 10\njdk.ObjectAllocationSample 1 10\n"}
            if phase == "jfr-events":
                commands.append(list(arguments))
                return {"stdout": json.dumps({"recording": {"events": events}}).encode()}
            self.fail("unexpected capture phase")
        with mock.patch.object(probe, "successful", side_effect=successful):
            result = probe.capture_jfr(jdk, ["unused"], {}, root / "work", root / "public", config, expected)
        self.assertEqual(1, len(commands))
        self.assertIn("--stack-depth", commands[0])
        index = commands[0].index("--stack-depth")
        self.assertEqual("5", commands[0][index + 1])
        self.assertEqual({"requestedDepth": 5, "completeStacksGuaranteed": False}, result["stackTraceView"])
        self.assertEqual(32 * 1024 * 1024, probe.LIMIT)
        self.assertEqual(256 * 1024 * 1024, probe.RAW_LIMIT)
        self.assertEqual(16 * 1024 * 1024, probe.JFR_LIMIT)


if __name__ == "__main__":
    unittest.main()
