"""임시 Git 및 JUnit fixture만 사용하는 결정적 grader 테스트다."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


MODULE_SPEC = importlib.util.spec_from_file_location(
    "grade_impact_repair_target",
    Path(__file__).resolve().parents[1] / "grade-impact-repair.py",
)
MODULE = importlib.util.module_from_spec(MODULE_SPEC)

sys.modules[MODULE_SPEC.name] = MODULE
assert MODULE_SPEC.loader is not None
MODULE_SPEC.loader.exec_module(MODULE)


class GradeImpactRepairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self._git("init", "-q")
        self._git("config", "user.email", "fixture@example.invalid")
        self._git("config", "user.name", "fixture")
        (self.repo / "src/main").mkdir(parents=True)
        (self.repo / "src/test").mkdir(parents=True)
        (self.repo / "src/main/value.txt").write_text("bad\n", encoding="utf-8")
        (self.repo / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
        driver = self.repo / "test-driver.py"
        driver.write_text(
            "#!/usr/bin/env python3\n"
            "import os, pathlib, sys, time\n"
            "if os.environ.get('TEST_MODE') == 'sleep': time.sleep(10)\n"
            "root = pathlib.Path('reports'); root.mkdir(parents=True, exist_ok=True)\n"
            "fixed = pathlib.Path('src/main/value.txt').read_text() == 'fixed\\n'\n"
            "mode = os.environ.get('TEST_MODE', '')\n"
            "visible_status = 'skipped' if mode == 'skip-pass' else 'passed'\n"
            "hidden_status = 'passed' if fixed else 'failed'\n"
            "rows = [('VisiblePass', 'works', visible_status), ('VisibleBaseline', 'legacy', 'failed'), ('GoldSkipped', 'unchanged', 'skipped')]\n"
            "if mode == 'public-identities': rows[0] = ('VisiblePass', 'UTC https://example.invalid/a?x=1 &lt;!-- XML comment --&gt; /tmp/public/path', visible_status)\n"
            "if mode != 'missing': rows.append(('HiddenPass', 'regression', hidden_status))\n"
            "items = []\n"
            "for klass, name, status in rows:\n"
            "    child = '<testcase classname=\\\"' + klass + '\\\" name=\\\"' + name + '\\\">'\n"
            "    if status == 'failed': child += '<failure message=\\\"failed\\\" />'\n"
            "    elif status == 'skipped': child += '<skipped />'\n"
            "    items.append(child + '</testcase>')\n"
            "pathlib.Path('reports/TEST-suite.xml').write_text('<testsuite>' + ''.join(items) + '</testsuite>')\n"
            "sys.exit(1 if any(status == 'failed' for _, _, status in rows) else 0)\n",
            encoding="utf-8",
        )
        driver.chmod(0o700)
        self._git("add", ".")
        self._git("commit", "-qm", "base")
        self.revision = self._git("rev-parse", "HEAD").strip()
        self.test_patch = self.root / "hidden-tests.patch"
        self.test_patch.write_text(
            "diff --git a/src/test/HiddenCase.txt b/src/test/HiddenCase.txt\n"
            "new file mode 100644\n--- /dev/null\n+++ b/src/test/HiddenCase.txt\n"
            "@@ -0,0 +1 @@\n+hidden\n",
            encoding="utf-8",
        )
        self.native_patch = self.root / "native-build.patch"
        self.native_patch.write_text(
            "diff --git a/build.gradle.kts b/build.gradle.kts\n"
            "--- a/build.gradle.kts\n+++ b/build.gradle.kts\n"
            "@@ -1 +1 @@\n-plugins {}\n+plugins { id(\"fixture\") }\n",
            encoding="utf-8",
        )
        self.native_scope_patch = self.root / "native-scope.patch"
        self.native_scope_patch.write_text(
            "diff --git a/src/main/value.txt b/src/main/value.txt\n"
            "--- a/src/main/value.txt\n+++ b/src/main/value.txt\n"
            "@@ -1 +1 @@\n-bad\n+native\n",
            encoding="utf-8",
        )
        self.native_conflict_patch = self.root / "native-conflict.patch"
        self.native_conflict_patch.write_text(
            "diff --git a/build.gradle.kts b/build.gradle.kts\n"
            "--- a/build.gradle.kts\n+++ b/build.gradle.kts\n"
            "@@ -1 +1 @@\n-plugins { wrong }\n+plugins { id(\"fixture\") }\n",
            encoding="utf-8",
        )
        self.test_conflict_patch = self.root / "test-conflict.patch"
        self.test_conflict_patch.write_text(
            "diff --git a/src/main/value.txt b/src/main/value.txt\n"
            "--- a/src/main/value.txt\n+++ b/src/main/value.txt\n"
            "@@ -1 +1 @@\n-missing\n+hidden\n",
            encoding="utf-8",
        )
        self.expected = self.root / "gold.json"
        self.expected.write_text(json.dumps({
            "exit": 1,
            "tests": [
                {"report": "reports/TEST-suite.xml", "class": "VisiblePass", "name": "works", "status": "passed"},
                {"report": "reports/TEST-suite.xml", "class": "HiddenPass", "name": "regression", "status": "passed"},
                {"report": "reports/TEST-suite.xml", "class": "GoldSkipped", "name": "unchanged", "status": "skipped"},
            ],
        }), encoding="utf-8")
        self.valid_patch = self.root / "valid.patch"
        self.valid_patch.write_text(
            "diff --git a/src/main/value.txt b/src/main/value.txt\n"
            "--- a/src/main/value.txt\n+++ b/src/main/value.txt\n"
            "@@ -1 +1 @@\n-bad\n+fixed\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self.temp.cleanup()

    def _git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.repo, check=True, capture_output=True, text=True).stdout

    def spec(self, **changes):
        value = {
            "id": "fixture",
            "repository": str(self.repo),
            "revision": self.revision,
            "nativeBuildPatch": str(self.native_patch),
            "proposedPatch": str(self.valid_patch),
            "editPaths": ["src/main/value.txt"],
            "testPatch": str(self.test_patch),
            "testCommand": ["./test-driver.py"],
            "testEnv": {},
            "reportsGlobs": ["reports/TEST-*.xml"],
            "expectedGoldTests": str(self.expected),
            "knownBaselineFailures": [{
                "report": "reports/TEST-suite.xml", "class": "VisibleBaseline",
                "name": "legacy", "status": "failed",
            }],
        }
        value.update(changes)
        return MODULE.GradeSpec.from_json(value)

    def test_valid_fix_passes_while_retaining_known_baseline_failure(self):
        result = MODULE.grade(self.spec(), output=self.root / "valid")
        self.assertTrue(result["verdict"], result)
        self.assertEqual("passed_known_baseline_failures", result["outcome"])
        self.assertEqual(2, result["tests"]["comparison"]["expectedPassing"])
        self.assertEqual(1, len(result["tests"]["comparison"]["knownBaselineFailures"]))
        self.assertEqual("exact", result["inputs"]["testIdentityPolicy"])
        self.assertEqual("exact", result["tests"]["comparison"]["testIdentityPolicy"])
        self.assertTrue((self.root / "valid/test.stdout").is_file())
        self.assertEqual(["build.gradle.kts"], result["patch"]["nativeBuildPaths"])

    def test_regression_is_rejected_from_actual_hidden_junit(self):
        wrong = self.root / "wrong.patch"
        wrong.write_text(self.valid_patch.read_text(encoding="utf-8").replace("fixed", "wrong"), encoding="utf-8")
        result = MODULE.grade(self.spec(proposedPatch=str(wrong)), output=self.root / "regression")
        self.assertFalse(result["verdict"])
        self.assertEqual("tests_rejected", result["outcome"])
        hidden = next(row for row in result["tests"]["rows"] if row["class"] == "HiddenPass")
        self.assertEqual("failed", hidden["status"])
        self.assertEqual(1, len(result["tests"]["comparison"]["newFailures"]))

    def test_missing_gold_test_is_rejected(self):
        result = MODULE.grade(self.spec(testEnv={"TEST_MODE": "missing"}), output=self.root / "missing")
        self.assertFalse(result["verdict"])
        self.assertEqual("tests_rejected", result["outcome"])
        self.assertEqual(1, len(result["tests"]["comparison"]["missingExpected"]))

    def test_gold_passing_test_becoming_skipped_fails_as_non_passing_expected(self):
        result = MODULE.grade(self.spec(testEnv={"TEST_MODE": "skip-pass"}), output=self.root / "skipped-pass")
        self.assertFalse(result["verdict"])
        self.assertEqual("tests_rejected", result["outcome"])
        self.assertEqual(1, len(result["tests"]["comparison"]["nonPassingExpected"]))
        self.assertEqual([], result["tests"]["comparison"]["newFailures"])

    def test_forbidden_proposed_test_path_is_rejected_before_driver(self):
        forbidden = self.root / "forbidden.patch"
        forbidden.write_text(
            "diff --git a/src/test/Forbidden.txt b/src/test/Forbidden.txt\n"
            "new file mode 100644\n--- /dev/null\n+++ b/src/test/Forbidden.txt\n"
            "@@ -0,0 +1 @@\n+forbidden\n",
            encoding="utf-8",
        )
        result = MODULE.grade(self.spec(proposedPatch=str(forbidden)), output=self.root / "forbidden")
        self.assertFalse(result["verdict"])
        self.assertEqual("patch_violation", result["error"])
        self.assertFalse((self.root / "forbidden/test.stdout").exists())

    def test_timeout_kills_test_driver_and_records_timeout(self):
        result = MODULE.grade(self.spec(testEnv={"TEST_MODE": "sleep"}), output=self.root / "timeout", timeout_seconds=0.05)
        self.assertFalse(result["verdict"])
        self.assertEqual("test_timeout", result["outcome"])
        self.assertTrue(result["process"]["test"]["timedOut"])

    def test_compile_failure_is_rejected_before_test_execution(self):
        result = MODULE.grade(
            self.spec(compileCommand=[sys.executable, "-c", "raise SystemExit(7)" ]), output=self.root / "compile-failure",
        )
        self.assertFalse(result["verdict"])
        self.assertEqual("compile_failed", result["outcome"])
        self.assertFalse((self.root / "compile-failure/test.stdout").exists())

    def test_trusted_native_scope_failure_is_grader_input_failure(self):
        result = MODULE.grade(self.spec(nativeBuildPatch=str(self.native_scope_patch)), output=self.root / "native-scope")
        self.assertFalse(result["verdict"])
        self.assertEqual("grader_input_failure", result["outcome"])
        self.assertEqual("grader_input_failure", result["error"])
        self.assertFalse((self.root / "native-scope/test.stdout").exists())

    def test_trusted_native_apply_failure_is_grader_input_failure(self):
        result = MODULE.grade(self.spec(nativeBuildPatch=str(self.native_conflict_patch)), output=self.root / "native-conflict")
        self.assertFalse(result["verdict"])
        self.assertEqual("grader_input_failure", result["outcome"])
        self.assertFalse((self.root / "native-conflict/test.stdout").exists())

    def test_trusted_test_patch_conflict_is_oracle_application_failure(self):
        result = MODULE.grade(self.spec(testPatch=str(self.test_conflict_patch)), output=self.root / "test-conflict")
        self.assertFalse(result["verdict"])
        self.assertEqual("oracle_application_failure", result["outcome"])
        self.assertEqual("oracle_application_failure", result["error"])
        self.assertFalse((self.root / "test-conflict/test.stdout").exists())

    def test_duplicate_identity_is_reported_and_counted(self):
        rows = [
            {"report": "reports/a.xml", "class": "C", "name": "n", "status": "passed"},
            {"report": "reports/a.xml", "class": "C", "name": "n", "status": "passed"},
        ]
        expected = [{"report": "reports/a.xml", "class": "C", "name": "n", "status": "passed"}]
        comparison = MODULE.compare_tests(rows, expected, [])
        self.assertEqual(1, len(comparison["duplicateIdentities"]))
        self.assertFalse(comparison["ok"])

    def test_reportless_gold_record_binds_only_to_one_report(self):
        rows = [{"report": "reports/a.xml", "class": "C", "name": "n", "status": "passed"}]
        comparison = MODULE.compare_tests(rows, [{"class": "C", "name": "n", "status": "passed"}], [])
        self.assertTrue(comparison["ok"])

    def test_xml_whitespace_remains_part_of_the_test_identity(self):
        name = "Given a declaration\nwith a line break\tand a tab\r"
        suite = ET.Element("testsuite")
        ET.SubElement(suite, "testcase", {"classname": "ExampleSpec", "name": name})
        report = self.root / "TEST-example.xml"
        ET.ElementTree(suite).write(report, encoding="utf-8")
        rows = MODULE._junit_rows(self.root, [report])
        expected, _, skipped = MODULE._normalise_expected([
            {"report": report.name, "class": "ExampleSpec", "name": name, "status": "passed"},
        ])
        self.assertEqual(name, rows[0]["name"])
        self.assertTrue(MODULE.compare_tests(rows, expected, [], skipped)["ok"])

    def test_display_name_whitespace_is_not_normalized_into_a_different_test(self):
        expected, _, skipped = MODULE._normalise_expected([
            {"report": "TEST-example.xml", "class": "ExampleSpec", "name": "a\nb", "status": "passed"},
        ])
        rows = [{"report": "TEST-example.xml", "class": "ExampleSpec", "name": "a b", "status": "passed"}]
        self.assertFalse(MODULE.compare_tests(rows, expected, [], skipped)["ok"])

    def test_nul_is_still_invalid_in_a_test_identity(self):
        with self.assertRaises(MODULE.GradeSpecError):
            MODULE._normalise_expected([{"class": "ExampleSpec", "name": "a\0b", "status": "passed"}])

    def test_junit_identity_policy_is_exact_by_default(self):
        expected = [{
            "report": "detekt-core/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full#20Qualified#20names$general#20cases.xml",
            "class": "ExampleSpec",
            "name": "[1] org.jetbrains.kotlin.resolve.BindingContext$1@abc",
            "status": "passed",
        }]
        rows = [dict(expected[0], name=expected[0]["name"].replace("abc", "def"))]
        comparison = MODULE.compare_tests(rows, expected, [])
        self.assertFalse(comparison["ok"])

    def test_detekt_policy_matches_all_six_a_occurrences_and_keeps_multiplicity(self):
        report = "detekt-core/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full#20Qualified#20names$general#20cases.xml"
        klass = "io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full Qualified names$general cases"
        names = [
            "[1] org.jetbrains.kotlin.resolve.BindingTraceContext$1@abc",
            "[2] org.jetbrains.kotlin.resolve.BindingContext$1@abc",
        ] * 3
        expected = [{"report": report, "class": klass, "name": name, "status": "passed"} for name in names]
        rows = [{**row, "name": row["name"].replace("abc", f"{index + 1:03x}")}
                for index, row in enumerate(expected)]
        comparison = MODULE.compare_tests(rows, expected, [], policy="detekt-junit-object-identities-v1")
        self.assertTrue(comparison["ok"], comparison)
        self.assertEqual(6, comparison["expectedPassing"])
        self.assertEqual([], comparison["missingExpected"])
        self.assertEqual([], comparison["expectedCountMismatch"])

        comparison = MODULE.compare_tests(rows[:-1], expected, [], policy="detekt-junit-object-identities-v1")
        self.assertFalse(comparison["ok"])
        self.assertEqual(1, len(comparison["missingExpected"]))

    def test_detekt_policy_scopes_context_and_retains_wrapper_type(self):
        report = "detekt-formatting/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.formatting.WrapperSmokeTestSpec.xml"
        klass = "io.gitlab.arturbosch.detekt.formatting.WrapperSmokeTestSpec"
        expected = [{
            "report": report,
            "class": klass,
            "name": "for rule: io.gitlab.arturbosch.detekt.formatting.wrappers.IndentationRule@abc",
            "status": "passed",
        }]
        rows = [dict(expected[0], name=expected[0]["name"].replace("abc", "def"))]
        comparison = MODULE.compare_tests(rows, expected, [], policy="detekt-junit-object-identities-v1")
        self.assertTrue(comparison["ok"], comparison)

        wrong_type = [dict(rows[0], name=rows[0]["name"].replace("IndentationRule", "SpacingRule"))]
        self.assertFalse(MODULE.compare_tests(wrong_type, expected, [], policy="detekt-junit-object-identities-v1")["ok"])
        other_context = [dict(rows[0], report="other/TEST-wrapper.xml")]
        self.assertFalse(MODULE.compare_tests(other_context, expected, [], policy="detekt-junit-object-identities-v1")["ok"])

    def test_detekt_policy_keeps_literal_hex_and_tagged_names_distinct(self):
        report = "detekt-core/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full#20Qualified#20names$general#20cases.xml"
        klass = "io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full Qualified names$general cases"
        volatile = "[2] org.jetbrains.kotlin.resolve.BindingContext$1@abc"
        literal = "detekt-junit-object-identities-v1:[2] org.jetbrains.kotlin.resolve.BindingContext$1"
        expected = [
            {"report": report, "class": klass, "name": volatile, "status": "passed"},
            {"report": report, "class": klass, "name": literal, "status": "passed"},
        ]
        rows = [
            {**expected[0], "name": volatile.replace("abc", "def")},
            expected[1],
        ]
        comparison = MODULE.compare_tests(rows, expected, [], policy="detekt-junit-object-identities-v1")
        self.assertTrue(comparison["ok"], comparison)
        self.assertEqual("[2] org.jetbrains.kotlin.resolve.BindingContext$1@def", rows[0]["name"])

        annotation = [{
            "report": "other/TEST.xml", "class": "Other", "name": "annotation @deadbeef", "status": "passed",
        }]
        self.assertFalse(MODULE.compare_tests(annotation, [dict(annotation[0], name="annotation @cafebabe")], [], policy="detekt-junit-object-identities-v1")["ok"])

    def test_detekt_policy_requires_explicit_expected_reports(self):
        with self.assertRaises(MODULE.GradeSpecError):
            MODULE._normalise_expected([
                {"class": "ExampleSpec", "name": "[1] org.jetbrains.kotlin.resolve.BindingContext$1@abc", "status": "passed"},
            ], policy="detekt-junit-object-identities-v1")
        with self.assertRaises(MODULE.GradeSpecError):
            MODULE.compare_tests(
                [{"report": "reports/a.xml", "class": "ExampleSpec", "name": "Stable", "status": "passed"}],
                [{"class": "ExampleSpec", "name": "Stable", "status": "passed"}],
                [], policy="detekt-junit-object-identities-v1",
            )
        with self.assertRaises(MODULE.GradeSpecError):
            MODULE._normalise_baseline([
                {"class": "ExampleSpec", "name": "Stable", "status": "failed"},
            ], policy="detekt-junit-object-identities-v1")

    def test_unknown_test_identity_policy_is_rejected(self):
        with self.assertRaises(MODULE.GradeSpecError):
            self.spec(testIdentityPolicy="unknown")

    def test_detekt_policy_preserves_new_failure_name(self):
        report = "detekt-formatting/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.formatting.WrapperSmokeTestSpec.xml"
        klass = "io.gitlab.arturbosch.detekt.formatting.WrapperSmokeTestSpec"
        expected = [{"report": report, "class": klass, "name": "Stable", "status": "passed"}]
        failure_name = "for rule: io.gitlab.arturbosch.detekt.formatting.wrappers.BadRule@abc"
        comparison = MODULE.compare_tests([
            {"report": report, "class": klass, "name": "Stable", "status": "passed"},
            {"report": report, "class": klass, "name": failure_name, "status": "failed"},
        ], expected, [], policy="detekt-junit-object-identities-v1")
        self.assertEqual(failure_name, comparison["newFailures"][0]["name"])
        self.assertFalse(comparison["ok"])

    def test_existing_output_is_not_overwritten(self):
        output = self.root / "existing"
        output.mkdir()
        marker = output / "marker.txt"
        marker.write_text("keep", encoding="utf-8")
        result = MODULE.grade(self.spec(), output=output)
        self.assertFalse(result["verdict"])
        self.assertEqual("keep", marker.read_text(encoding="utf-8"))

    def test_artifact_scrubbing_preserves_public_identity_text_and_redacts_known_paths(self):
        public_name = "UTC https://example.invalid/a?x=1 <!-- XML comment --> /tmp/public/path"
        expected = json.dumps({
            "tests": [
                {"report": "reports/TEST-suite.xml", "class": "VisiblePass", "name": public_name, "status": "passed"},
                {"report": "reports/TEST-suite.xml", "class": "VisibleBaseline", "name": "legacy", "status": "failed"},
                {"report": "reports/TEST-suite.xml", "class": "GoldSkipped", "name": "unchanged", "status": "skipped"},
            ],
        })
        output = self.root / "public-identities"
        result = MODULE.grade(self.spec(testEnv={"TEST_MODE": "public-identities"}, expectedGoldTests=expected), output=output)
        self.assertTrue(result["verdict"], result)
        visible = next(row for row in result["tests"]["rows"] if row["class"] == "VisiblePass")
        self.assertEqual(public_name, visible["name"])
        artifact = json.loads((output / "result.json").read_text(encoding="utf-8"))
        artifact_visible = next(row for row in artifact["tests"]["rows"] if row["class"] == "VisiblePass")
        self.assertEqual(public_name, artifact_visible["name"])

        private = self.root / "private" / "repo"
        scrubbed = MODULE._scrub(
            f"{private}/file UTC https://example.invalid /tmp/public/path",
            [private, self.root / "output", Path.home(), "/tmp/jdk", "UTC"],
        )
        self.assertEqual("<local>/file UTC https://example.invalid /tmp/public/path", scrubbed)


if __name__ == "__main__":
    unittest.main()
