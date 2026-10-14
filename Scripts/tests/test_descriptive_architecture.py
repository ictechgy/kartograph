"""고정 기술 비교의 산술·신원·거부·소유 Python 자식 제어만 검사한다."""

from copy import deepcopy
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("descriptive", ROOT / "Scripts/verify-descriptive-architecture.py")
D = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)


def passing_rows():
    rows = []
    for identity in D.expected_rows():
        wall = .1 if identity["block"] != "main" else (
            .6 if identity["label"] == "B" and identity["command"] not in D.UNCHANGED else 1.)
        row = dict(identity, seconds=wall, returncode=0, contract_failures=[],
                   max_rss_kib=100, user_seconds=.1, system_seconds=0.)
        for stream in ("stdout", "stderr"):
            row.update({stream + "_bytes": 0, stream + "_sha256": "0" * 64, stream + "_overflow": False})
        rows.append(row)
    return rows


def source_fixture():
    shared = {"path": "shared.kt", "sha256": "1" * 64, "bytes": 10, "mode": 0o644}
    base = {"head": D.PRODUCT_SHA, "tree": D.PRODUCT_TREE, "staged": [], "unstaged": [], "untracked": [],
            "files": [shared, dict(D.CHANGED_FILES[0], sha256="2" * 64, mode=0o644)]}
    candidate = dict(deepcopy(base), staged=[("M" if i == 0 else "A", r["path"]) for i, r in enumerate(D.CHANGED_FILES)],
                     files=[deepcopy(shared), *[dict(r, mode=0o644) for r in D.CHANGED_FILES]])
    return {"A": base, "B": candidate}


def install_fixture():
    a = {"directories": ["bin", "lib"], "files": [
        {"path": "bin/kartograph", "sha256": "1" * 64, "bytes": 20, "mode": 0o755},
        {"path": D.CLI_JAR, "sha256": "2" * 64, "bytes": 30, "mode": 0o644},
        {"path": "lib/index.jar", "sha256": "3" * 64, "bytes": 40, "mode": 0o644}]}
    b = deepcopy(a)
    b["files"][1]["sha256"] = "4" * 64
    return {"A": a, "B": b}


def write_xml(root, module, names, skipped=()):
    suite = ET.Element("testsuite", name=module, tests=str(len(names)), skipped=str(len(skipped)), failures="0", errors="0")
    for name in names:
        case = ET.SubElement(suite, "testcase", name=name,
                             classname="dev.kartograph.cli.ArchitectureDispatchSelectionTest" if module == "cli" else "OtherTest")
        if name in skipped:
            ET.SubElement(case, "skipped")
    directory = root / module / "build/test-results/test"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "TEST-owned.xml"
    ET.ElementTree(suite).write(path, encoding="utf-8")
    return path


class DescriptiveArithmeticTest(unittest.TestCase):
    def test_exact_fixed_schedule_and_group_membership(self):
        suites = D.schedule()
        self.assertEqual(42, len(suites))
        self.assertEqual(172, len(D.expected_rows()))
        self.assertEqual(156, sum(r["block"] == "main" for r in D.expected_rows()))
        self.assertEqual(["A", "B"], [s["label"] for s in suites[:2]])
        self.assertEqual(["AB", "BA", "BA", "AB", "AB", "BA", "BA", "AB", "AB", "BA", "BA", "AB"],
                         ["".join(s["label"] for s in suites if s["block"] == "main" and s["pair"] == i) for i in range(12)])
        self.assertEqual(["small_1"] * 8 + ["small_17"] * 8, [s["block"] for s in suites[26:]])
        self.assertEqual([12, 6, 6, 6, 6, 3, 3, 3, 3], [len(v) for v in D.groups().values()])
        for block in ("small_1", "small_17"):
            self.assertEqual(["AB", "BA", "AB"], ["".join(s["label"] for s in suites if s["block"] == block and s["pair"] == i) for i in range(3)])

    def test_geometric_ratio_is_neither_arithmetic_ratio_nor_median_endpoint(self):
        a, b = [1.] * 12, [.5, 2.] * 6
        summary = D.paired_summary(a, b)
        self.assertAlmostEqual(1., summary["groups"]["all"]["geometric_mean_ratio"])
        self.assertEqual(1.25, summary["groups"]["all"]["B"]["median"])
        self.assertEqual(b, summary["paired_ratios"])
        self.assertAlmostEqual(-2 / 3, summary["signed_symmetric_differences"][0])
        self.assertAlmostEqual(2 / 3, summary["signed_symmetric_differences"][1])

    def test_complete_material_directional_screen_continues(self):
        score = D.grade(passing_rows())
        self.assertEqual("continue-to-exact-head-validation", score["status"])
        self.assertEqual([6.] * 12, score["statistics"]["suite_total"]["A"])
        self.assertAlmostEqual(.8, score["statistics"]["suite_total"]["groups"]["all"]["geometric_mean_ratio"])
        self.assertEqual([100] * 12, score["cpu_and_rss"]["suite_total"]["all"]["max_rss_kib"]["A"]["samples"])
        self.assertEqual([], score["unmet_continuation_predicates"])

    def test_material_reduction_boundary_and_near_miss(self):
        for changed, expected in ((.8, True), (.800000001, False)):
            rows = passing_rows()
            for row in rows:
                if row["block"] == "main" and row["label"] == "B" and row["command"] not in D.UNCHANGED:
                    row["seconds"] = changed
            score = D.grade(rows)
            self.assertEqual(expected, score["continuation_predicates"]["suite_total:all_at_most_0_90"])
            self.assertEqual("continue-to-exact-head-validation" if expected else "inconclusive-for-release-headroom-justification", score["status"])

    def test_group_reversal_is_inconclusive_even_if_pooled_effect_is_large(self):
        rows = passing_rows()
        for row in rows:
            if not row["warmup"] and row["block"] == "main" and row["label"] == "B" and row["command"] not in D.UNCHANGED:
                row["seconds"] = 1. if row["pair"] < 6 else .2
        score = D.grade(rows)
        self.assertTrue(score["continuation_predicates"]["suite_total:all_at_most_0_90"])
        self.assertFalse(score["continuation_predicates"]["suite_total:early_below_1_00"])
        self.assertEqual("inconclusive-for-release-headroom-justification", score["status"])

    def test_command_and_unchanged_control_boundaries(self):
        for value, expected in ((.95, True), (.9499999, False), (1.05, True), (1.0500001, False)):
            rows = passing_rows()
            for row in rows:
                if row["label"] == "B" and row["command"] == "graph":
                    row["seconds"] = value
            score = D.grade(rows)
            self.assertEqual(expected, score["continuation_predicates"]["graph:unchanged_all_within_0_95_1_05"])
            self.assertEqual(value <= 1.05, score["continuation_predicates"]["graph:all_at_most_1_05"])
        for value, expected in ((1.10, True), (1.1000001, False)):
            rows = passing_rows()
            for row in rows:
                if row["command"] == "cycles" and row["label"] == "B" and not row["warmup"]:
                    row["seconds"] = value if row["pair"] < 6 else .5
            self.assertEqual(expected, D.grade(rows)["continuation_predicates"]["cycles:early_at_most_1_10"])

    def test_small_timing_boundary_is_inconclusive_while_rss_is_rejected(self):
        limit = 1.2 * .1 + .020
        for value, expected in ((limit, True), (math.nextafter(limit, math.inf), False)):
            rows = passing_rows()
            for row in rows:
                if row["command"] == "small_1" and row["label"] == "B":
                    row["seconds"] = value
            score = D.grade(rows)
            self.assertEqual(expected, score["continuation_predicates"]["small_1:wall_at_most_1_20_plus_20ms"])
            if not expected:
                self.assertEqual("inconclusive-for-release-headroom-justification", score["status"])
        for rss, expected in ((125, True), (126, False)):
            rows = passing_rows()
            for row in rows:
                if row["command"] == "small_17" and row["label"] == "B":
                    row["max_rss_kib"] = rss
            score = D.grade(rows)
            self.assertEqual(expected, score["mandatory_checks"]["small_17:median_rss_at_most_1_25"])
            if not expected:
                self.assertEqual("rejected", score["status"])
        rows = passing_rows()
        rows[0]["max_rss_kib"] = D.RSS_LIMIT_KIB
        self.assertTrue(D.grade(rows)["mandatory_checks"]["all_invocations_rss_at_most_1_2_gib"])
        rows[0]["max_rss_kib"] = math.nextafter(D.RSS_LIMIT_KIB, math.inf)
        self.assertEqual("rejected", D.grade(rows)["status"])

    def test_incomplete_reordered_failed_and_nonfinite_observations_refuse(self):
        for key, value in (("seconds", math.nan), ("seconds", math.inf), ("seconds", 0), ("seconds", True),
                           ("returncode", False), ("returncode", 2), ("pair", False), ("suite", False),
                           ("warmup", 1), ("contract_failures", False), ("contract_failures", ["failure"]),
                           ("max_rss_kib", -1), ("user_seconds", math.nan), ("system_seconds", True),
                           ("stdout_bytes", True), ("stderr_bytes", D.AA.STREAM_LIMITS[1] + 1),
                           ("stdout_sha256", "invalid"), ("stdout_overflow", 0), ("error", "failure")):
            rows = passing_rows()
            rows[12][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                D.grade(rows)
        with self.assertRaises(ValueError):
            D.grade(passing_rows()[:-1])
        rows = passing_rows()
        rows[12], rows[13] = rows[13], rows[12]
        with self.assertRaises(ValueError):
            D.grade(rows)
        rows = passing_rows()
        rows[12]["stdout_sha256"] = "1" * 64
        with self.assertRaisesRegex(ValueError, "parity"):
            D.grade(rows)
        with self.assertRaises(ValueError):
            D.paired_summary([True] * 12, [1.] * 12)


class DescriptiveIdentityTest(unittest.TestCase):
    def test_both_labels_use_exact_common_argv_tail(self):
        baseline, candidate, fixture = Path("/baseline"), Path("/candidate"), Path("/fixture")
        declared = D.commands(baseline, candidate, fixture)
        for name in (*D.NAMES, "small_1", "small_17"):
            a, b = declared["A"][name], declared["B"][name]
            self.assertEqual(a[0][1:], b[0][1:])
            self.assertEqual(a[1], b[1])
            self.assertTrue(a[0][0].startswith("/baseline/"))
            self.assertTrue(b[0][0].startswith("/candidate/"))
            if name in D.NAMES:
                classes = [a[0][i + 1] for i, value in enumerate(a[0]) if value == "--classes"]
                self.assertEqual([str(baseline / module / "build/classes/kotlin/main") for module in D.AA.MODULES], classes)
        env = D.AA.cli_environment(dict(JAVA_HOME="/jdk", PATH="/bin", JAVA_OPTS="-Xmx1g", JDK_JAVA_OPTIONS="bad"), Path("/tmp/owned"))
        self.assertEqual({"JAVA_HOME": "/jdk", "PATH": "/bin", "TMPDIR": "/tmp/owned"}, env)

    def test_exact_source_patch_and_full_noncandidate_equivalence(self):
        D.validate_sources(source_fixture())
        mutations = (
            lambda s: s["A"].update(head="a" * 40),
            lambda s: s["B"].update(tree="b" * 40),
            lambda s: s["A"].update(untracked=["extra.kt"]),
            lambda s: s["B"].update(unstaged=[D.CHANGED_FILES[0]["path"]]),
            lambda s: s["B"]["staged"].append(("M", "extra.kt")),
            lambda s: s["B"]["files"][0].update(sha256="8" * 64),
            lambda s: s["B"]["files"][1].update(sha256="8" * 64),
            lambda s: s["B"]["files"][1].update(mode=0o755),
        )
        for mutate in mutations:
            sources = source_fixture()
            mutate(sources)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                D.validate_sources(sources)

    def test_only_cli_jar_difference_is_admitted(self):
        self.assertEqual([D.CLI_JAR], D.validate_installs(install_fixture()))
        for mutate in (
                lambda i: i["B"]["files"][0].update(sha256="9" * 64),
                lambda i: i["B"]["files"][2].update(sha256="9" * 64),
                lambda i: i["B"]["files"].pop(),
                lambda i: i["B"]["directories"].append("extra"),
                lambda i: i["B"]["files"][1].update(mode=0o755),
                lambda i: i.update(B=deepcopy(i["A"]))):
            installs = install_fixture()
            mutate(installs)
            with self.assertRaises(ValueError):
                D.validate_installs(installs)

    def test_membership_content_and_symlink_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.class").write_bytes(b"old")
            before = D.tree_identity(root)
            (root / "empty").mkdir()
            self.assertNotEqual(before, D.tree_identity(root))
            (root / "empty").rmdir()
            (root / "a.class").write_bytes(b"new")
            self.assertNotEqual(before, D.tree_identity(root))
            (root / "linked").symlink_to(root / "a.class")
            with self.assertRaisesRegex(ValueError, "linked"):
                D.tree_identity(root)
            source_link = D.file_identity(root, [root / "linked"])[0]
            self.assertEqual("symlink", source_link["kind"])
            self.assertEqual(hashlib.sha256(str(root / "a.class").encode()).hexdigest(), source_link["sha256"])

    def test_root_alias_nesting_and_wrong_patch_refuse(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            a, b, out = root / "a", root / "b", root / "out"
            a.mkdir()
            b.mkdir()
            D.validate_roots(a, b, out)
            for args in ((a, a, out), (a, b, a / "out"), (a, a / "nested", out), (a, b, root), (a, root / "missing", out)):
                with self.assertRaises(ValueError):
                    D.validate_roots(*args)
            directory = root / "experiments/descriptive-architecture"
            directory.mkdir(parents=True)
            (directory / "candidate-source.json").write_text("{}")
            with self.assertRaises(ValueError):
                D.validate_binding(root)
        self.assertEqual(D.HELPER_SHA256, D.AA.digest(D.HELPER))

    def test_actual_xml_counts_and_all_required_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            index = write_xml(root, "index", ["passed", "optional"], skipped=["optional"])
            cli = write_xml(root, "cli", [name + "(Path)" for name in D.REQUIRED_TESTS])
            receipts = D.correctness_receipts(root)
            self.assertEqual({"tests": 2, "skipped": 1, "failures": 0, "errors": 0}, receipts["index"]["totals"])
            self.assertEqual(11, receipts["cli"]["totals"]["tests"])
            self.assertEqual(11, len(receipts["required_architecture_cases"]))
            original = cli.read_bytes()
            for kind in ("skipped", "failure", "missing", "duplicate", "renamed"):
                xml = ET.fromstring(original)
                if kind in ("skipped", "failure"):
                    ET.SubElement(xml.find("testcase"), kind)
                    xml.set("skipped" if kind == "skipped" else "failures", "1")
                elif kind == "missing":
                    xml.remove(xml.find("testcase"))
                    xml.set("tests", "10")
                elif kind == "duplicate":
                    xml.append(deepcopy(xml.find("testcase")))
                    xml.set("tests", "12")
                else:
                    xml.find("testcase").set("name", "unreviewed(Path)")
                ET.ElementTree(xml).write(cli)
                with self.subTest(kind=kind), self.assertRaises(ValueError):
                    D.correctness_receipts(root)
            cli.write_bytes(original)
            index.write_text('<testsuite tests="2" skipped="0" failures="0" errors="0"/>')
            with self.assertRaisesRegex(ValueError, "inconsistent"):
                D.correctness_receipts(root)


class DescriptiveReceiptTest(unittest.TestCase):
    def run_mocked(self, behavior):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        baseline, candidate, output = root / "a", root / "b", root / "out"
        (baseline / "Scripts").mkdir(parents=True)
        candidate.mkdir()
        (baseline / "Scripts/verify-analysis-smoke-gate.py").write_bytes((ROOT / "Scripts/verify-analysis-smoke-gate.py").read_bytes())
        calls, state = [], {"expired": False}
        class OwnedFakeCapture:
            def __init__(self, output, cwd, env, deadline):
                self.output, self.unreaped = output, False
                self.cwd, self.env = cwd, env
            def remaining(self):
                if state["expired"]:
                    raise TimeoutError("grading crossed deadline")
                return 100
            def run(self, argv, stem, row, **kwargs):
                calls.append((argv, self.cwd, self.env))
                if behavior == "unreaped":
                    self.unreaped = True
                    raise TimeoutError("owned child cleanup incomplete")
                if behavior == "cancelled":
                    raise KeyboardInterrupt("termination requested")
                name = row.get("command")
                if behavior == "correctness":
                    stdout = b"invalid graph"
                elif name == "graph" or (name and name.startswith("small_")):
                    count = 2000 if name == "graph" else 4 * int(name.split("_")[1])
                    stdout = b'  "node" [shape=box];\n' * count
                else:
                    stdout = {"cycles": b"cycles: 0 findings\n", "rules": b"rules: 0 findings, 0 unassigned\n",
                              "metrics": b"data\n" * 6}.get(name, b"")
                row.update(returncode=0, seconds=.01, max_rss_kib=100, user_seconds=0., system_seconds=0.)
                paths = []
                for stream, data in (("stdout", stdout), ("stderr", b"")):
                    path = self.output / (stem + "." + stream)
                    path.write_bytes(data)
                    row.update({stream: path.name, stream + "_bytes": path.stat().st_size,
                                stream + "_sha256": D.AA.digest(path), stream + "_overflow": False})
                    paths.append(path)
                return paths
        snapshot = {"sources": source_fixture(), "installs": install_fixture(), "correctness": {"passed": True}}
        def source(root):
            return source_fixture()["A" if root == baseline else "B"]
        original_grade = D.grade
        def grade(rows):
            if behavior == "grade-timeout":
                state["expired"] = True
                return {"status": "continue-to-exact-head-validation"}
            return original_grade(rows)
        endpoints = [snapshot, ValueError("endpoint drift") if behavior == "correctness" else snapshot]
        with patch.dict(os.environ, {"JAVA_HOME": str(root)}), \
             patch.object(D, "source_identity", side_effect=source), \
             patch.object(D, "correctness_receipts", return_value={"passed": True}), \
             patch.object(D, "fixture_setup"), \
             patch.object(D, "snapshot", side_effect=endpoints) as snapshot_mock, \
             patch.object(D, "grade", side_effect=grade), \
             patch.object(D.AA, "Capture", OwnedFakeCapture), \
             patch.object(D.AA, "resources", return_value={"policy": {"affinity": [0]}}), \
             patch.object(D.AA, "read_small", return_value="unavailable"):
            rc = D.main(["--baseline-root", str(baseline), "--candidate-root", str(candidate), "--output", str(output)])
        self.assertTrue(all(cwd == baseline for _, cwd, _ in calls))
        return rc, json.loads((output / "result.json").read_text()), calls, snapshot_mock.call_count

    def test_first_correctness_failure_stops_and_keeps_primary_through_endpoint_failure(self):
        rc, result, calls, endpoints = self.run_mocked("correctness")
        self.assertEqual(2, rc)
        self.assertEqual("rejected", result["status"])
        self.assertEqual(1, len(result["rows"]))
        self.assertEqual(3, len(calls))
        self.assertEqual(2, endpoints)
        self.assertIn("semantic contract failed", result["error"])
        self.assertIn("endpoint drift", result["endpoint_error"])
        self.assertFalse(result["owned_child_unreaped"])
        self.assertEqual("graph", result["rows"][0]["command"])
        self.assertEqual(hashlib.sha256(b"invalid graph").hexdigest(), result["rows"][0]["stdout_sha256"])

    def test_unreaped_child_is_incomplete_and_endpoint_inputs_are_not_read(self):
        rc, result, calls, endpoints = self.run_mocked("unreaped")
        self.assertEqual(2, rc)
        self.assertEqual("incomplete", result["status"])
        self.assertEqual(1, endpoints)
        self.assertEqual(1, len(calls))
        self.assertTrue(result["owned_child_unreaped"])
        self.assertIn("not be read concurrently", result["endpoint_skipped"])
        self.assertNotIn("after", result)

    def test_cancellation_preserves_incomplete_terminal_receipt(self):
        rc, result, calls, endpoints = self.run_mocked("cancelled")
        self.assertEqual(2, rc)
        self.assertEqual("incomplete", result["status"])
        self.assertIn("KeyboardInterrupt", result["error"])
        self.assertEqual(2, endpoints)
        self.assertEqual(1, len(calls))

    def test_grading_deadline_cannot_escape_as_continuation(self):
        rc, result, calls, endpoints = self.run_mocked("grade-timeout")
        self.assertEqual(2, rc)
        self.assertEqual(172, len(result["rows"]))
        self.assertEqual("incomplete", result["status"])
        self.assertIn("grading crossed deadline", result["error"])
        self.assertEqual(176, len(calls))
        self.assertEqual(2, endpoints)

    def test_wrong_helper_is_refused_before_its_module_code_executes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            driver = root / "verify-descriptive-architecture.py"
            driver.write_bytes((ROOT / "Scripts/verify-descriptive-architecture.py").read_bytes())
            (root / "verify-cli-repeatability.py").write_text("raise AssertionError('tampered helper executed')\n")
            spec = importlib.util.spec_from_file_location("tampered_import_control", driver)
            module = importlib.util.module_from_spec(spec)
            with self.assertRaisesRegex(ValueError, "before import"):
                spec.loader.exec_module(module)


@unittest.skipUnless(hasattr(os, "wait4"), "requires POSIX wait4")
class DescriptiveOwnedChildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.capture = D.AA.Capture(self.output, self.output, dict(os.environ), time.monotonic() + 10)

    def test_reused_capture_retains_complete_raw_hashes_and_usage(self):
        row = {}
        self.capture.run([sys.executable, "-c", "import sys;print('raw');print('error',file=sys.stderr)"], "success", row)
        self.assertEqual(hashlib.sha256(b"raw\n").hexdigest(), row["stdout_sha256"])
        self.assertEqual(hashlib.sha256(b"error\n").hexdigest(), row["stderr_sha256"])
        self.assertGreater(row["max_rss_kib"], 0)
        self.assertFalse(self.capture.unreaped)

    def test_cap_and_nonzero_exit_refuse_without_claiming_complete_oversize_hash(self):
        row = {}
        with self.assertRaises(ValueError):
            self.capture.run([sys.executable, "-c", "print('x'*1000)"], "cap", row, limits=(32, 32))
        self.assertTrue(row["stdout_overflow"])
        self.assertNotIn("stdout_sha256", row)
        row = {}
        with self.assertRaisesRegex(RuntimeError, "returned 2"):
            self.capture.run([sys.executable, "-c", "import sys;print('failure',file=sys.stderr);sys.exit(2)"], "failure", row)
        self.assertEqual(2, row["returncode"])
        self.assertEqual(hashlib.sha256(b"failure\n").hexdigest(), row["stderr_sha256"])

    def test_timeout_reaps_owned_child_and_expired_budget_launches_none(self):
        row, owned = {}, []
        original = D.AA.os.killpg
        def kill(pid, signum):
            owned.append(pid)
            original(pid, signum)
        with patch.object(D.AA.os, "killpg", side_effect=kill), self.assertRaises(TimeoutError):
            self.capture.run([sys.executable, "-c", "import time;time.sleep(5)"], "timeout", row, timeout=.05)
        self.assertEqual(-signal.SIGKILL, row["returncode"])
        self.assertFalse(self.capture.unreaped)
        with self.assertRaises(ChildProcessError):
            os.waitpid(owned[0], os.WNOHANG)
        self.capture.deadline = time.monotonic() - 1
        with patch.object(D.AA.subprocess, "Popen") as popen, self.assertRaises(TimeoutError):
            self.capture.run([sys.executable, "-c", "raise AssertionError()"], "not-started", {})
        popen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
