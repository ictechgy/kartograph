"""통제 일정·원본 보존·runtime archive 경계와 실제 자식 종료를 대조한다."""

import importlib.util
import io
import json
import math
import os
from pathlib import Path
import signal
import sys
import tarfile
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("runtime_pair", ROOT / "Scripts/verify-jdk-runtime-pair.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("runtime pair helper is unavailable")
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)


def passing_rows():
    return [dict(suite, command=name, seconds=3.0, returncode=0, contract_failures=[])
            for suite in P.schedule() for name in P.R.NAMES]


class StatisticsTest(unittest.TestCase):
    def test_fixed_schedule_balances_every_arm_in_every_position(self):
        suites = P.schedule()
        self.assertEqual(20, len(suites))
        self.assertEqual(["A", "B1"], [s["label"] for s in suites if s["warmup"]])
        orders = [tuple(s["label"] for s in suites if s["pair"] == n) for n in range(6)]
        self.assertEqual(6, len(set(orders)))
        for label in ("A", "B1", "B2"):
            for position in range(3):
                self.assertEqual(2, sum(order[position] == label for order in orders))
        self.assertEqual(120, len(passing_rows()))

    def test_runtime_difference_does_not_fail_the_identical_runtime_control(self):
        rows = passing_rows()
        for row in rows:
            if row["label"] == "A":
                row["seconds"] = 2.0
        score = P.grade(rows)
        self.assertTrue(score["noise_qualified"])
        self.assertEqual([2.0] * 6, score["runtime_contrast_descriptive_only"]["graph"]["A"])
        self.assertEqual([3.0] * 6, score["runtime_contrast_descriptive_only"]["graph"]["B"])
        self.assertEqual(18, score["diagnostic_medians_not_mandatory_three_pass_gate"]["B1"]["sum_of_command_medians"])

    def test_one_noisy_control_is_not_excluded_or_reclassified_as_a_warmup(self):
        rows = passing_rows()
        next(r for r in rows if r["label"] == "B2" and r["pair"] == 3
             and r["command"] == "dead")["seconds"] = 3.4
        score = P.grade(rows)
        self.assertFalse(score["noise_qualified"])
        self.assertFalse(score["noise"]["dead"]["checks"]["all_pairs_within_5_percent"])

    def test_unstable_old_runtime_also_prevents_noise_qualification(self):
        rows = passing_rows()
        next(r for r in rows if r["label"] == "A" and r["pair"] == 4
             and r["command"] == "metrics")["seconds"] = 4.0
        score = P.grade(rows)
        self.assertTrue(all(v["passed"] for v in score["noise"].values()))
        self.assertFalse(score["old_runtime_range_checks"]["metrics"]["passed"])
        self.assertFalse(score["noise_qualified"])

    def test_missing_reordered_failed_boolean_nonfinite_and_contract_rows_refuse(self):
        bad = [passing_rows()[:-1]]
        swapped = passing_rows()
        swapped[12], swapped[18] = swapped[18], swapped[12]
        bad.append(swapped)
        for key, value in (("seconds", True), ("seconds", 0), ("seconds", math.inf),
                           ("seconds", math.nan), ("returncode", False), ("returncode", 2),
                           ("contract_failures", False), ("contract_failures", ["finding"]),
                           ("suite", False), ("warmup", 1), ("label", "B")):
            rows = passing_rows()
            rows[0][key] = value
            bad.append(rows)
        for rows in bad:
            with self.subTest(size=len(rows)), self.assertRaises(ValueError):
                P.grade(rows)


class RuntimeArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "runtime.tar.gz"
        self.output = self.root / "output"
        self.output.mkdir()
        self.prefix = "jdk-fixture"

    def make_archive(self, extras):
        with tarfile.open(self.archive, "w:gz") as archive:
            for name, kind, data in [(self.prefix, "directory", b""), *extras]:
                member = tarfile.TarInfo(name)
                member.mode = 0o755 if kind == "directory" else 0o644
                if kind == "directory":
                    member.type = tarfile.DIRTYPE
                elif kind in ("symlink", "hardlink"):
                    member.type = tarfile.SYMTYPE if kind == "symlink" else tarfile.LNKTYPE
                    member.linkname = data.decode()
                else:
                    member.size = len(data)
                archive.addfile(member, io.BytesIO(data) if kind == "file" else None)

    def extract(self, extras, cap=1024):
        self.make_archive(extras)
        with patch.object(P, "free_guard", return_value=99 * P.GIB):
            return P.extract_runtime(self.archive, self.output, "fixture", cap=cap)

    def test_valid_internal_links_and_complete_bytes_restore(self):
        total = self.extract([("jdk-fixture/data", "file", b"retained"),
                              ("jdk-fixture/symbolic", "symlink", b"data"),
                              ("jdk-fixture/hard", "hardlink", b"jdk-fixture/data")])
        self.assertEqual(8, total)
        self.assertEqual(b"retained", (self.output / "jdk-fixture/symbolic").read_bytes())
        self.assertTrue((self.output / "jdk-fixture/symbolic").is_symlink())
        self.assertEqual((self.output / "jdk-fixture/data").stat().st_ino,
                         (self.output / "jdk-fixture/hard").stat().st_ino)

    def test_unsafe_paths_and_links_refuse_before_extracting_files(self):
        cases = [
            [("../escape", "file", b"x")], [("/absolute", "file", b"x")],
            [("jdk-fixture/a", "file", b"x"), ("jdk-fixture/a", "file", b"y")],
            [("jdk-fixture/a", "symlink", b"../../escape")],
            [("jdk-fixture/a", "symlink", b"/absolute")],
            [("jdk-fixture/a", "symlink", b"b"), ("jdk-fixture/b", "symlink", b"a")],
            [("jdk-fixture/a", "symlink", b"missing")],
            [("jdk-fixture/a", "hardlink", b"elsewhere/file")],
            [("jdk-fixture/a", "file", b"x"), ("jdk-fixture/a/child", "file", b"y")],
        ]
        for extras in cases:
            with self.subTest(extras=extras), self.assertRaises(ValueError):
                self.extract(extras)
            self.assertEqual([], list(self.output.iterdir()))

    def test_expanded_size_refuses_before_writing_and_truncated_gzip_refuses(self):
        with self.assertRaises(ValueError):
            self.extract([("jdk-fixture/data", "file", b"x" * 11)], cap=10)
        self.assertEqual([], list(self.output.iterdir()))
        self.make_archive([("jdk-fixture/data", "file", os.urandom(2048))])
        self.archive.write_bytes(self.archive.read_bytes()[:30])
        with self.assertRaises((EOFError, tarfile.ReadError)):
            P.extract_runtime(self.archive, self.output, "fixture")


class EvidenceTest(unittest.TestCase):
    def test_all_raw_bytes_and_manifest_restore_without_temp_or_runtime_archives(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "capture").mkdir()
            data = b"actual graph\n" * 500
            (evidence / "capture/graph.stdout").write_bytes(data)
            (evidence / "capture/graph.stderr").write_bytes(b"")
            (evidence / "tmp").mkdir()
            (evidence / "tmp/ignored").write_text("private temporary bytes")
            archive = root / "result.zip"
            P.package(evidence, archive)
            with zipfile.ZipFile(archive) as result:
                self.assertEqual(data, result.read("capture/graph.stdout"))
                self.assertNotIn("tmp/ignored", result.namelist())
                manifest = json.loads(result.read("package-manifest.json"))
                self.assertEqual(2, len(manifest["files"]))
                self.assertEqual(P.R.digest(evidence / "capture/graph.stdout"),
                                 next(r for r in manifest["files"] if r["path"].endswith("stdout"))["sha256"])

    def test_evidence_symlink_and_compressed_size_refuse_without_publishing_partial_zip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "link").symlink_to("../outside")
            with self.assertRaises(ValueError):
                P.package(evidence, root / "refused.zip")
            (evidence / "link").unlink()
            (evidence / "raw").write_bytes(os.urandom(5000))
            with patch.object(P, "ZIP_CAP", 1000), self.assertRaises(ValueError):
                P.package(evidence, root / "large.zip")
            self.assertFalse((root / "large.zip").exists())
            self.assertTrue((root / "large.part").exists())

    def test_disk_reserve_is_inclusive_and_lower_free_space_refuses(self):
        with patch.object(P.shutil, "disk_usage") as usage:
            usage.return_value.free = P.FLOOR
            self.assertEqual(P.FLOOR, P.free_guard(Path(".")))
            usage.return_value.free = P.FLOOR - 1
            with self.assertRaises(ValueError):
                P.free_guard(Path("."))


@unittest.skipUnless(sys.platform == "linux" and hasattr(os, "pidfd_open"), "requires Linux pidfd and WNOWAIT")
class CaptureGuardTest(unittest.TestCase):
    def test_guard_failure_kills_only_owned_child_and_preserves_raw_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            owned = []
            class Refuse(P.Guards):
                def __call__(self, pid, row, stdout, stderr):
                    owned.append(pid)
                    raise ValueError("fixture resource stop")
            capture = P.GuardedCapture(root, root, dict(os.environ), time.monotonic() + 10, guard=Refuse(root))
            row = {}
            with self.assertRaisesRegex(ValueError, "fixture resource stop"):
                capture.run([sys.executable, "-c", "import time; print('started',flush=True);time.sleep(5)"],
                            "guarded", row)
            self.assertEqual(-signal.SIGKILL, row["returncode"])
            self.assertFalse(capture.unreaped)
            self.assertIn("fixture resource stop", row["error"])
            self.assertTrue((root / "guarded.stdout").is_file())
            with self.assertRaises(ChildProcessError):
                os.waitpid(owned[0], os.WNOHANG)


class ResourceGuardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.proc = self.root / "proc"
        self.proc.mkdir()
        for pid, parent, pages, children in ((123, 1, 10, "124"), (124, 123, 20, "")):
            directory = self.proc / str(pid)
            (directory / "task" / str(pid)).mkdir(parents=True)
            fields = ["S", str(parent), "123", "123", *(["0"] * 15), "fixed-birth"]
            (directory / "stat").write_text(f'{pid} (owned java) ' + " ".join(fields))
            (directory / "statm").write_text(f'100 {pages}')
            (directory / "task" / str(pid) / "children").write_text(children)
        self.stdout, self.stderr = self.root / "stdout", self.root / "stderr"
        self.stdout.write_bytes(b"raw")
        self.stderr.write_bytes(b"")
        # parent leader가 아닌 worker TID의 children도 포함해야 한다.
        (self.proc / "123/task/123/children").write_text("")
        (self.proc / "123/task/125").mkdir()
        (self.proc / "123/task/125/children").write_text("124")
        self.handles = patch.object(P, "pidfd_handle", side_effect=lambda _: os.open(os.devnull, os.O_RDONLY))
        self.handles.start()
        self.addCleanup(self.handles.stop)

    def paths(self, value):
        return self.proc if value == "/proc" else Path(value)

    def test_owned_tree_peak_counts_address_spaces_once_and_enforces_rss_and_raw_caps(self):
        guard, row = P.Guards(self.root), {}
        self.addCleanup(guard.close)
        with patch.object(P, "Path", side_effect=self.paths):
            guard(123, row, self.stdout, self.stderr)
        self.assertEqual(30 * os.sysconf("SC_PAGE_SIZE"), row["observed_tree_rss_peak_bytes"])
        self.assertEqual({123, 124}, set(guard.owned))
        with patch.object(P, "Path", side_effect=self.paths), patch.object(P, "RSS_CAP", 1), \
             self.assertRaisesRegex(ValueError, "RSS"):
            guard(123, {}, self.stdout, self.stderr)
        guard.raw_bytes = P.RAW_CAP
        with self.assertRaisesRegex(ValueError, "raw capture"):
            guard(123, {}, self.stdout, self.stderr)

    def test_pid_reuse_with_another_parent_is_not_adopted(self):
        stat = self.proc / "124/stat"
        stat.write_text(stat.read_text().replace('S 123 ', 'S 999 '))
        guard, row = P.Guards(self.root), {}
        self.addCleanup(guard.close)
        with patch.object(P, "Path", side_effect=self.paths):
            guard(123, row, self.stdout, self.stderr)
        self.assertEqual({123}, set(guard.owned))
        self.assertTrue(row["descendant_disappeared_between_samples"])

    def test_cleanup_signals_only_the_stable_handle_not_a_numeric_pid(self):
        guard = P.Guards(self.root)
        self.addCleanup(guard.close)
        fd = os.open(os.devnull, os.O_RDONLY)
        guard.owned = {123: ("old-birth", fd)}
        with patch.object(guard, "census"), patch.object(P.os, "kill") as kill, \
             patch.object(P, "signal_handle") as send:
            guard.abort(123, {})
        kill.assert_not_called()
        send.assert_called_once_with(fd)

    def test_expired_deadline_starts_no_fresh_budget(self):
        with patch.dict(P.os.environ, {"PAIR_DEADLINE_MONOTONIC": "0"}):
            with self.assertRaises(TimeoutError):
                P.deadline(900)

    def test_lost_waitid_lease_never_kills_or_censuses_or_reaps_numeric_identity(self):
        guard = P.Guards(self.root)
        capture = P.GuardedCapture(self.root, self.root, {}, time.monotonic() + 10, guard=guard)
        row = {}
        child = SimpleNamespace(pid=12345, returncode=None)
        with patch.object(P.sys, "platform", "linux"), \
             patch.object(P.os, "pidfd_open", return_value=10, create=True), \
             patch.object(P.signal, "pidfd_send_signal", create=True), \
             patch.object(P.os, "waitid", side_effect=ChildProcessError("fixture ECHILD"), create=True), \
             patch.object(P.subprocess, "Popen", return_value=child), \
             patch.object(P.os, "killpg") as group, patch.object(P.os, "wait4") as reap, \
             patch.object(guard, "census") as census:
            with self.assertRaisesRegex(RuntimeError, "lease is unavailable"):
                capture.run(["not-executed"], "lost-lease", row)
        self.assertTrue(row["root_lease_lost"])
        self.assertTrue(capture.unreaped)
        self.assertNotIn("returncode", row)
        group.assert_not_called()
        census.assert_not_called()
        reap.assert_not_called()

    def test_receipt_error_does_not_replace_primary_failure(self):
        try:
            raise TimeoutError("original failure")
        except TimeoutError:
            with patch.object(P, "receipt", side_effect=OSError("secondary failure")):
                P.preserve(self.root / "result.json", {})
        with patch.object(P, "receipt", side_effect=OSError("only failure")), self.assertRaises(OSError):
            P.preserve(self.root / "result.json", {})


if __name__ == "__main__":
    unittest.main()
