"""공통 wrapper 준비와 실패 증거를 검증한다. 합성 wrapper는 실행하지 않는다."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("index_cost_wrapper", Path(__file__).parents[1] / "verify-index-cost.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("test module specification with a loader is required")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("test module specification with a loader is required")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
PATHS = ("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")


class IndexCostWrapperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "candidate"
        self.baseline = self.root / "baseline"
        self.old = (b"launcher\n", b"old wrapper, never executed", b"old properties\n")
        self.new = (b"launcher\n", b"new wrapper, never executed", b"new properties\n")
        for directory, values in [(self.repo, self.new), (self.baseline, self.old)]:
            for name, raw in zip(PATHS, values):
                path = directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
                path.chmod(0o755 if name == "gradlew" else 0o644)
            (directory / "Source.kt").write_text("class OriginalEngine\n")
            (directory / "gradle/verification-metadata.xml").write_text(
                '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
                '<configuration/><components><component group="example" name="fixed" version="1.0">'
                '<artifact name="fixed.jar"><sha256 value="' + 'a' * 64 + '"/></artifact>'
                '</component></components></verification-metadata>')

    def identities(self, values):
        return tuple((len(raw), hashlib.sha256(raw).hexdigest(), 0o755 if name == "gradlew" else 0o644)
                     for name, raw in zip(PATHS, values))

    def known_pair(self):
        # 고정 공개 wrapper tuple 자체는 별도 immutable Git 입력과 대조한다.
        return patch.object(MODULE, "REVIEWED_WRAPPERS", {
            "9.7.1": self.identities(self.old), "9.8.1": self.identities(self.new)})

    def test_exact_reviewed_transition_uses_one_wrapper_and_records_original(self):
        report = {}
        with self.known_pair():
            MODULE.align_build_wrappers(self.repo, self.baseline, report)
        value = report["buildWrapperAlignment"]
        self.assertEqual("9.7.1", value["baselineOriginalVersion"])
        self.assertEqual("9.8.1", value["sharedVersion"])
        self.assertIn("derived", value["scope"])
        self.assertEqual(self.identities(self.old)[1][1], value["baselineOriginal"][1]["sha256"])
        for name, raw in zip(PATHS, self.new):
            self.assertEqual(raw, (self.baseline / name).read_bytes())
            self.assertEqual(raw, (self.repo / name).read_bytes())
        self.assertEqual("class OriginalEngine\n", (self.baseline / "Source.kt").read_text())
        MODULE.verify_aligned_wrappers(self.repo, self.baseline, value)

    def test_reviewed_patch_transition_from_the_current_wrapper_uses_one_wrapper(self):
        reviewed = {"9.7.1": self.identities((b"launcher\n", b"oldest wrapper", b"oldest properties\n")),
                    "9.8.1": self.identities(self.old), "9.8.2": self.identities(self.new)}
        report = {}
        with patch.object(MODULE, "REVIEWED_WRAPPERS", reviewed):
            MODULE.align_build_wrappers(self.repo, self.baseline, report)
        value = report["buildWrapperAlignment"]
        self.assertEqual(("9.8.1", "9.8.2"), (value["baselineOriginalVersion"], value["sharedVersion"]))
        for name, raw in zip(PATHS, self.new):
            self.assertEqual(raw, (self.baseline / name).read_bytes())
        # 검토한 한 단계 전환만 허용한다. 건너뛴 전환은 쓰기 전에 거부한다.
        for name, raw in zip(PATHS, (b"launcher\n", b"oldest wrapper", b"oldest properties\n")):
            (self.baseline / name).write_bytes(raw)
        with patch.object(MODULE, "REVIEWED_WRAPPERS", reviewed), \
                self.assertRaisesRegex(MODULE.DiagnosticError, "build-tool-identity-mismatch"):
            MODULE.align_build_wrappers(self.repo, self.baseline, {})
        self.assertEqual(b"oldest wrapper", (self.baseline / PATHS[1]).read_bytes())

    def test_each_committed_transition_is_accepted_forward_and_rejected_in_reverse(self):
        # 실제 REVIEWED_WRAPPER_TRANSITIONS의 각 쌍(예: 9.8.1→9.8.2)을 그대로 쓴다. 신원만 합성 바이트로 바꾼다.
        def write(baseline_values, candidate_values):
            for directory, values in [(self.baseline, baseline_values), (self.repo, candidate_values)]:
                for name, raw in zip(PATHS, values):
                    (directory / name).write_bytes(raw)

        for old_version, new_version in sorted(MODULE.REVIEWED_WRAPPER_TRANSITIONS):
            reviewed = {old_version: self.identities(self.old), new_version: self.identities(self.new)}
            with self.subTest(direction=f"{old_version}->{new_version}"), \
                    patch.object(MODULE, "REVIEWED_WRAPPERS", reviewed):
                write(self.old, self.new)
                report = {}
                MODULE.align_build_wrappers(self.repo, self.baseline, report)
                self.assertEqual((old_version, new_version), (report["buildWrapperAlignment"]["baselineOriginalVersion"],
                                                              report["buildWrapperAlignment"]["sharedVersion"]))
            with self.subTest(direction=f"{new_version}->{old_version}"), \
                    patch.object(MODULE, "REVIEWED_WRAPPERS", reviewed):
                # baseline이 더 새 wrapper이고 후보가 되돌린 경우다. 쓰기 전에 다른 도구 비교로 거부한다.
                write(self.new, self.old)
                with self.assertRaisesRegex(MODULE.DiagnosticError, "build-tool-identity-mismatch"):
                    MODULE.align_build_wrappers(self.repo, self.baseline, {})
                self.assertEqual(list(self.new), [(self.baseline / name).read_bytes() for name in PATHS])

    def test_committed_wrapper_matches_the_reviewed_identity_of_its_declared_version(self):
        repository = Path(__file__).resolve().parents[2]
        records, _ = MODULE.wrapper_inputs(repository)
        properties = dict(line.split("=", 1) for line in
                          (repository / PATHS[2]).read_text(encoding="utf-8").splitlines() if "=" in line)
        version = properties["distributionUrl"].rsplit("/gradle-", 1)[1].removesuffix("-bin.zip")
        self.assertEqual(MODULE.REVIEWED_WRAPPERS[version],
                         tuple((row["bytes"], row["sha256"], row["mode"]) for row in records))
        self.assertEqual(MODULE.WRAPPER_DISTRIBUTIONS[version], properties["distributionSha256Sum"])

    def test_identical_wrappers_are_not_rewritten(self):
        for name, raw in zip(PATHS, self.new):
            (self.baseline / name).write_bytes(raw)
        before = [(self.baseline / name).stat().st_mtime_ns for name in PATHS]
        report = {}
        MODULE.align_build_wrappers(self.repo, self.baseline, report)
        self.assertFalse(report["buildWrapperAlignment"]["changed"])
        self.assertEqual(before, [(self.baseline / name).stat().st_mtime_ns for name in PATHS])

    def test_unknown_reverse_partial_or_mode_changed_pairs_reject_before_writing(self):
        for case in ("unknown", "reverse", "partial", "mode"):
            with self.subTest(case=case), self.known_pair():
                old, new = self.old, self.new
                if case == "reverse":
                    old, new = new, old
                if case == "partial":
                    new = (new[0], old[1], new[2])
                if case == "unknown":
                    new = (*new[:2], b"unreviewed properties")
                for directory, values in [(self.baseline, old), (self.repo, new)]:
                    for name, raw in zip(PATHS, values):
                        (directory / name).write_bytes(raw)
                        (directory / name).chmod(0o755 if name == "gradlew" else 0o644)
                if case == "mode":
                    (self.repo / PATHS[1]).chmod(0o755)
                with self.assertRaisesRegex(MODULE.DiagnosticError, "build-tool-identity-mismatch"):
                    MODULE.align_build_wrappers(self.repo, self.baseline, {})
                self.assertEqual(list(old), [(self.baseline / name).read_bytes() for name in PATHS])

    def test_symlink_leaf_and_ancestor_are_rejected_without_touching_target(self):
        outside = self.root / "outside"
        outside.write_bytes(b"do not touch")
        leaf = self.repo / PATHS[1]
        leaf.unlink()
        leaf.symlink_to(outside)
        with self.assertRaises(MODULE.DiagnosticError):
            MODULE.align_build_wrappers(self.repo, self.baseline, {})
        self.assertEqual(b"do not touch", outside.read_bytes())
        leaf.unlink()
        leaf.parent.rename(self.repo / "gradle/retained-wrapper")
        leaf.parent.symlink_to(self.repo / "gradle/retained-wrapper", target_is_directory=True)
        with self.assertRaises(MODULE.DiagnosticError):
            MODULE.align_build_wrappers(self.repo, self.baseline, {})

    def test_oversized_and_nonregular_inputs_fail_before_reading(self):
        leaf = self.repo / PATHS[1]
        with leaf.open("wb") as stream:
            stream.truncate(1024 * 1024 + 1)
        with self.assertRaises(MODULE.DiagnosticError):
            MODULE.align_build_wrappers(self.repo, self.baseline, {})
        leaf.unlink()
        leaf.mkdir()
        with self.assertRaises(MODULE.DiagnosticError):
            MODULE.align_build_wrappers(self.repo, self.baseline, {})

    def test_post_alignment_mutation_is_detected_in_both_arms(self):
        report = {}
        with self.known_pair():
            MODULE.align_build_wrappers(self.repo, self.baseline, report)
        for directory in (self.repo, self.baseline):
            leaf = directory / PATHS[1]
            leaf.write_bytes(self.new[1] + b"changed")
            with self.assertRaisesRegex(MODULE.DiagnosticError, "build-wrapper-changed"):
                MODULE.verify_aligned_wrappers(self.repo, self.baseline, report["buildWrapperAlignment"])
            leaf.write_bytes(self.new[1])

    def test_child_failure_and_receipt_survive_a_secondary_integrity_failure(self):
        report = {}
        failure = MODULE.DiagnosticError("baseline-build-failed", {"exit": 3, "stdoutBytes": 123})
        with self.known_pair(), self.assertRaises(MODULE.DiagnosticError) as caught:
            with MODULE.aligned_build_inputs(self.repo, self.baseline, report):
                (self.baseline / PATHS[1]).write_bytes(b"changed during failed build")
                raise failure
        self.assertIs(failure, caught.exception)
        self.assertEqual(failure.receipt, caught.exception.receipt)
        self.assertEqual("baseline-build-failed", report["buildInputIntegrity"]["primaryFailure"]["reason"])
        self.assertFalse(report["buildInputIntegrity"]["unchanged"])
        self.assertEqual("build-wrapper-changed", report["buildInputIntegrity"]["errors"][0]["reason"])

    def test_success_is_rejected_when_wrapper_or_metadata_changed(self):
        for name in (PATHS[1], "gradle/verification-metadata.xml"):
            with self.subTest(name=name), self.known_pair():
                for directory in (self.repo, self.baseline):
                    for path, raw in zip(PATHS, self.new):
                        (directory / path).write_bytes(raw)
                report = {}
                with self.assertRaisesRegex(MODULE.DiagnosticError, "aligned-build-inputs-changed"):
                    with MODULE.aligned_build_inputs(self.repo, self.baseline, report):
                        leaf = self.baseline / name
                        leaf.write_bytes(leaf.read_bytes() + b" ")
                self.assertFalse(report["buildInputIntegrity"]["unchanged"])

    def test_execution_finalization_also_preserves_original_child_failure(self):
        report = {}
        failure = MODULE.DiagnosticError("child-timeout", {"exit": -9, "phase": "index-probe"})
        def changed_source():
            raise MODULE.DiagnosticError("source-roots-changed")
        with self.assertRaises(MODULE.DiagnosticError) as caught:
            with MODULE.input_integrity_guard(report, "executionInputIntegrity", (changed_source,)):
                raise failure
        self.assertIs(failure, caught.exception)
        self.assertEqual(failure.receipt, report["executionInputIntegrity"]["primaryFailure"]["receipt"])
        self.assertEqual([{"reason": "source-roots-changed"}], report["executionInputIntegrity"]["errors"])

    def test_public_summary_retains_derived_wrapper_and_integrity_receipts_without_paths(self):
        report = {"format": "kartograph-index-cost-v1", "status": "FAIL", "scope": "fixed-input", "residualScope": "diagnostic"}
        with self.known_pair():
            with MODULE.aligned_build_inputs(self.repo, self.baseline, report):
                pass
        raw = MODULE.public_summary(report)
        public = json.loads(raw)
        self.assertEqual(report["buildWrapperAlignment"], public["buildWrapperAlignment"])
        self.assertEqual(report["buildInputIntegrity"], public["buildInputIntegrity"])
        self.assertNotIn(str(self.root), raw)


if __name__ == "__main__":
    unittest.main()
