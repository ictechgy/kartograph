"""진단용 임시 baseline의 추가 체크섬만 동등하게 고정한다. 실제 비교 성공을 대신하지 않는다."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import tarfile
import io
import json
import shutil
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("index_cost_metadata", Path(__file__).parents[1] / "verify-index-cost.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("test module specification with a loader is required")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def xml(artifacts, *, signatures="false"):
    rows = ''.join(f'<artifact name="{name}"><sha256 value="{value}" origin="fixed fixture"/></artifact>'
                   for name, value in artifacts)
    return ('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata><verify-signatures>' + signatures +
            '</verify-signatures></configuration><components>'
            '<component group="example" name="library" version="1.0">' + rows +
            '</component></components></verification-metadata>').encode()


class IndexCostMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "candidate"
        self.baseline = self.root / "baseline"
        for directory in [self.repo, self.baseline]:
            (directory / "gradle").mkdir(parents=True)
            (directory / "Source.kt").write_text("class UnchangedSource\n")
        self.old = xml([("library.jar", "a" * 64)])
        self.new = xml([("library.jar", "a" * 64), ("library.module", "b" * 64)])
        self.file(self.repo).write_bytes(self.new)
        self.file(self.baseline).write_bytes(self.old)

    @staticmethod
    def file(directory):
        return directory / "gradle/verification-metadata.xml"

    def test_additive_pins_are_shared_without_changing_policy_or_executable_source(self):
        report = {}
        MODULE.align_verification_metadata(self.repo, self.baseline, report)
        self.assertEqual(self.new, self.file(self.repo).read_bytes())
        self.assertEqual(self.new, self.file(self.baseline).read_bytes())
        self.assertEqual("class UnchangedSource\n", (self.baseline / "Source.kt").read_text())
        value = report["verificationMetadataAlignment"]
        self.assertEqual(hashlib.sha256(self.old).hexdigest(), value["baselineOriginalSha256"])
        self.assertEqual(hashlib.sha256(self.new).hexdigest(), value["sharedSha256"])
        self.assertEqual(1, value["addedArtifacts"])
        MODULE.verify_aligned_metadata(self.repo, self.baseline, value)

    def reject(self, changed):
        self.file(self.repo).write_bytes(changed)
        with self.assertRaises(MODULE.DiagnosticError):
            MODULE.align_verification_metadata(self.repo, self.baseline, {})
        self.assertEqual(self.old, self.file(self.baseline).read_bytes())

    def test_changed_removed_or_alternative_existing_hash_is_rejected(self):
        for changed in [xml([("library.jar", "c" * 64)]), xml([("library.module", "b" * 64)]),
                        self.new.replace(b'</artifact>', b'<sha256 value="' + b'c' * 64 + b'"/></artifact>', 1)]:
            with self.subTest(changed=changed):
                self.reject(changed)

    def test_policy_and_unrecognized_trust_changes_are_rejected(self):
        for changed in [xml([("library.jar", "a" * 64)], signatures="true"),
                        self.new.replace(b'<components>', b'<trusted-artifacts/><components>'),
                        self.new.replace(b'version="1.0"', b'version="1.+"')]:
            with self.subTest(changed=changed):
                self.reject(changed)

    def test_duplicate_artifact_and_document_entities_are_rejected(self):
        self.reject(xml([("library.jar", "a" * 64), ("library.jar", "a" * 64)]))
        self.reject(b'<!DOCTYPE verification-metadata [<!ENTITY x "private">]>' + self.new)
        self.reject(('<!DOCTYPE verification-metadata [<!ENTITY x "unused">]>' + self.new.decode()).encode("utf-16"))
        self.reject(b" " * (2 * 1024 * 1024 + 1))

    def test_deep_and_wide_metadata_fail_with_explicit_structure_limit(self):
        for payload in [b"<nested>" * 1200 + b"</nested>" * 1200, b"<entry/>" * 20_001]:
            with self.subTest(bytes=len(payload)):
                changed = self.new.replace(b"<configuration>", b"<configuration>" + payload)
                self.file(self.repo).write_bytes(changed)
                with self.assertRaisesRegex(MODULE.DiagnosticError, "structure-limit"):
                    MODULE.align_verification_metadata(self.repo, self.baseline, {})
                self.assertEqual(self.old, self.file(self.baseline).read_bytes())

    def test_changes_after_alignment_are_detected_in_both_arms(self):
        report = {}
        MODULE.align_verification_metadata(self.repo, self.baseline, report)
        for directory in [self.repo, self.baseline]:
            self.file(directory).write_bytes(self.new + b' ')
            with self.assertRaisesRegex(MODULE.DiagnosticError, "metadata-changed"):
                MODULE.verify_aligned_metadata(self.repo, self.baseline, report["verificationMetadataAlignment"])
            self.file(directory).write_bytes(self.new)

    def test_identical_metadata_still_records_identity(self):
        self.file(self.repo).write_bytes(self.old)
        report = {}
        MODULE.align_verification_metadata(self.repo, self.baseline, report)
        self.assertEqual(0, report["verificationMetadataAlignment"]["addedArtifacts"])
        self.assertEqual(self.old, self.file(self.baseline).read_bytes())

    def test_experiment_reaches_build_with_additive_pins_and_no_source_substitution(self):
        self.exercise_experiment()

    def test_failed_probe_and_late_metadata_mutation_keep_primary_receipt(self):
        self.exercise_experiment(fail_phase="probe")

    def test_failed_baseline_build_and_late_metadata_mutation_keep_primary_receipt(self):
        self.exercise_experiment(fail_phase="build")

    def test_otherwise_successful_run_rejects_late_metadata_mutation(self):
        report = {}
        MODULE.align_verification_metadata(self.repo, self.baseline, report)
        with self.assertRaisesRegex(MODULE.DiagnosticError, "aligned-build-inputs-changed"):
            with MODULE.input_integrity_guard(report, "metadataInputIntegrity", (
                    lambda: MODULE.verify_aligned_metadata(self.repo, self.baseline, report["verificationMetadataAlignment"]),)):
                self.file(self.baseline).write_bytes(self.new + b' ')
        self.assertFalse(report["metadataInputIntegrity"]["unchanged"])

    def exercise_experiment(self, fail_phase=None):
        # 실제 experiment의 기존 바이트 동일성 거절까지 실행한다. child 실행은 하지 않는다.
        class ReachedBuild(Exception):
            pass
        for module in MODULE.MODULES:
            directory = self.repo / module / "build/classes/kotlin/main"
            directory.mkdir(parents=True)
            (directory / "Fixture.class").write_bytes(b"inventory-only, never executed")
        for name in ["gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                     ".kartograph-self.pro", ".kartograph.yml", "Scripts/IndexCostProbe.java", "Scripts/IndexEquivalenceProbe.java",
                     "cli/build/install/kartograph/bin/kartograph", "cli/build/install/kartograph/lib/fixture.jar"]:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("identity-only fixture\n")
            if name.endswith(("gradlew", "bin/kartograph")):
                path.chmod(0o755)
        java_home = self.root / "jdk"
        java_home.mkdir()
        work = self.root / "work"
        work.mkdir()
        report = {}
        receipt = {"exit": 23, "phase": "baseline-build" if fail_phase == "build" else "index-probe", "stdoutBytes": 123, "stderrBytes": 45}
        def successful(arguments, cwd, env, **kwargs):
            phase = kwargs["phase"]
            if phase == "baseline-source-diff":
                return {"stdout": b""}
            if phase == "baseline-archive":
                archive = Path(arguments[arguments.index("--output") + 1])
                with tarfile.open(archive, "w") as tar:
                    for name in ["gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties", "gradle/verification-metadata.xml", "Source.kt"]:
                        raw = self.old if name.endswith("verification-metadata.xml") else (self.repo / name).read_bytes()
                        info = tarfile.TarInfo(name)
                        info.size = len(raw)
                        info.mode = (self.repo / name).stat().st_mode & 0o777
                        tar.addfile(info, io.BytesIO(raw))
                return {"stdout": b""}
            if phase == "baseline-build":
                self.assertEqual(self.new, (work / "baseline/gradle/verification-metadata.xml").read_bytes())
                self.assertEqual("class UnchangedSource\n", (work / "baseline/Source.kt").read_text())
                if fail_phase is None:
                    raise ReachedBuild()
                if fail_phase == "build":
                    (work / "baseline/gradle/verification-metadata.xml").write_bytes(self.new + b' ')
                    raise MODULE.DiagnosticError("required-child-failed", receipt)
                shutil.copytree(self.repo / "cli/build/install", work / "baseline/cli/build/install")
                return {"exit": 0}
            if phase == "index-probe" and fail_phase == "probe":
                (work / "baseline/gradle/verification-metadata.xml").write_bytes(self.new + b' ')
                raise MODULE.DiagnosticError("required-child-failed", receipt)
            self.fail("unexpected child phase")
        def identity(repo, ref, env):
            return {"commit": MODULE.BASELINE if ref != "HEAD" else "c" * 40, "tree": "d" * 40}
        def compile_probe(repo, distribution, directory, java, env):
            directory.mkdir()
            (directory / "Fixture.class").write_bytes(b"inventory only, never executed")
            return "not executed"
        with patch.object(MODULE, "java_identity", return_value=[]), patch.object(MODULE, "source_identity", side_effect=identity), \
             patch.object(MODULE, "run", return_value={"exit": 0}), patch.object(MODULE, "successful", side_effect=successful), \
             patch.object(MODULE, "compile_probe", side_effect=compile_probe), patch.object(MODULE, "summary", side_effect=lambda value: value):
            if fail_phase is not None:
                with self.assertRaises(MODULE.DiagnosticError) as caught:
                    MODULE.experiment(self.repo, work, {"JAVA_HOME": str(java_home)}, report)
                self.assertEqual("required-child-failed", str(caught.exception))
                self.assertEqual(receipt, caught.exception.receipt)
                self.assertEqual(receipt, report["metadataInputIntegrity"]["primaryFailure"]["receipt"])
                self.assertEqual([{"reason": "verification-metadata-changed"}], report["metadataInputIntegrity"]["errors"])
                if fail_phase == "probe":
                    self.assertTrue(report["executionInputIntegrity"]["unchanged"])
                    self.assertIn("sourcesAfter", report)
                report.update({"format": "kartograph-index-cost-v1", "status": "FAIL", "scope": "fixed-input", "residualScope": "diagnostic"})
                public = json.loads(MODULE.public_summary(report))
                self.assertEqual(receipt, public["metadataInputIntegrity"]["primaryFailure"]["receipt"])
            else:
                with self.assertRaises(ReachedBuild):
                    MODULE.experiment(self.repo, work, {"JAVA_HOME": str(java_home)}, report)


if __name__ == "__main__":
    unittest.main()
