"""한 명령 JIT 진단의 실제 JDK 스키마·경계·정보 최소화를 검증한다."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("jit_attribution", ROOT / "Scripts/verify-jit-attribution.py")
jit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(jit)
AT = "2026-10-26T03:00:00Z"
NS = jit.timestamp(AT)


def event(kind, **values):
    return {"type": "jdk." + kind, "values": {"startTime": AT, **values}}


def recording(**overrides):
    return event("ActiveRecording", recordingStart=AT, maxAge="PT0S", maxSize=0, **overrides)


def method():
    return {"type": {"name": "dev/kartograph/index/Parser"}, "name": "read-impl", "descriptor": "([B)Ljava/lang/String;"}


def compile_event(**overrides):
    values = {"compileId": 51, "compiler": "c2", "method": method(), "compileLevel": 4,
              "isOsr": True, "succeded": True, "duration": "PT0.01S", "codeSize": 2048, "inlinedBytes": 512,
              "eventThread": {"osThreadId": 12345, "javaThreadId": 99999, "javaName": "C2 CompilerThread0"}}
    values.update(overrides)
    return event("Compilation", **values)


def sanitize(events, ids=None, settings=None, chunks=None):
    return jit.sanitize({"recording": {"events": events}}, ids or {}, settings or {},
                        chunks or [{"startNs": NS, "endNs": NS + 1_000_000_000, "bytes": 100}])


class JitAttributionTest(unittest.TestCase):
    def test_compile_identity_osr_tier_end_descriptor_and_os_tid_are_preserved(self):
        rows, _ = sanitize([recording(), compile_event()])
        row = rows[1]
        self.assertEqual((51, "c2", 4, True, True, 10_000_000),
                         (row["compileId"], row["compiler"], row["level"], row["isOsr"], row["success"], row["endNs"]))
        self.assertEqual("([B)Ljava/lang/String;", row["method"]["descriptor"])
        self.assertEqual(12345, row["thread"]["osThreadId"])
        serialized = json.dumps(row)
        self.assertNotIn("99999", serialized)
        self.assertNotIn("CompilerThread0", serialized)

    def test_compile_fields_reject_bad_types_negative_and_unbounded_values(self):
        for key, value in (("compileId", True), ("compileId", -1), ("compiler", "private token"),
                           ("isOsr", "true"), ("succeded", 1), ("compileLevel", 5), ("codeSize", 2**64)):
            with self.subTest(field=key), self.assertRaises(jit.p.DiagnosticError):
                sanitize([recording(), compile_event(**{key: value})])

    def test_unknown_method_and_descriptor_owners_are_redacted(self):
        raw = {"type": {"name": "private/customer/Secret"}, "name": "sensitive", "descriptor": "()V"}
        self.assertEqual({"label": "<unclassified>", "descriptor": "<unclassified>"}, jit.method(raw))
        raw = method()
        raw["descriptor"] = "(Lprivate/customer/Secret;)V"
        self.assertEqual("<unclassified>", jit.method(raw)["descriptor"])
        self.assertNotIn("Secret", json.dumps(jit.method(raw)))
        for descriptor in ("", "x" * 2049, "(private token)"):
            with self.assertRaises(jit.p.DiagnosticError):
                jit.method({**method(), "descriptor": descriptor})

    def test_failure_message_is_omitted_and_deoptimization_keeps_compile_join(self):
        failure = event("CompilationFailure", compileId=51, eventThread=None, failureMessage="secret /absolute/path token")
        deopt = event("Deoptimization", compileId=51, compiler="c2", method=method(), eventThread=None,
                      bci=17, reason="unstable_if", action="reinterpret")
        rows, _ = sanitize([recording(), failure, deopt])
        self.assertEqual(51, rows[2]["compileId"])
        self.assertEqual((17, "unstable_if", "reinterpret"), (rows[2]["bci"], rows[2]["reason"], rows[2]["action"]))
        self.assertNotIn("secret", json.dumps(rows))
        self.assertNotIn("/absolute", json.dumps(rows))

    def test_active_settings_are_matched_to_recorded_event_ids_and_filtered(self):
        ids = {5: "jdk.Compilation"}
        settings = {"jdk.Compilation": {"enabled": "true", "threshold": "0 ms"}}
        events = [recording(), event("ActiveSetting", id=5, name="enabled", value="true"),
                  event("ActiveSetting", id=5, name="threshold", value="0 ns"),
                  event("ActiveSetting", id=999, name="private", value="secret/path")]
        rows, summary = sanitize(events, ids, settings)
        self.assertEqual(3, summary["eventCounts"]["jdk.ActiveSetting"])
        self.assertEqual(3, len(rows))
        self.assertNotIn("secret", json.dumps(rows))
        with self.assertRaisesRegex(jit.p.DiagnosticError, "required-active-setting"):
            sanitize(events[:-2], ids, settings)
        events.append(event("ActiveSetting", id=5, name="enabled", value="false"))
        with self.assertRaisesRegex(jit.p.DiagnosticError, "active-setting-changed"):
            sanitize(events, ids, settings)

    def test_recording_coverage_rejects_circular_loss_missing_start_and_events_outside_chunk(self):
        for values in ({"maxSize": 1}, {"maxAge": "PT1S"}, {"recordingStart": "2026-10-26T02:59:59Z"}):
            raw = recording()
            raw["values"].update(values)
            with self.assertRaises(jit.p.DiagnosticError):
                sanitize([raw])
        with self.assertRaisesRegex(jit.p.DiagnosticError, "active-recording"):
            sanitize([compile_event()])
        with self.assertRaisesRegex(jit.p.DiagnosticError, "data-loss"):
            sanitize([recording(), event("DataLoss", amount=1, total=1)])
        raw = compile_event()
        raw["values"]["startTime"] = "2026-10-26T03:00:02Z"
        with self.assertRaisesRegex(jit.p.DiagnosticError, "outside-chunk"):
            sanitize([recording(), raw])

    def test_chunk_headers_validate_entire_file_and_reject_truncation_and_oversize(self):
        raw = struct.pack(">4sHHQQQQQQQI", b"FLR\0", 2, 1, 80, 0, 69, NS, 1000, 1, 1_000_000_000, 2) + b"\0" * 12
        chunks = jit.chunk_coverage(raw)
        self.assertEqual(NS + 1000, chunks[0]["endNs"])
        for malformed in (raw[:-1], raw + b"trailing", b"not jfr", b"x" * (32 * jit.MIB + 1)):
            with self.assertRaises(jit.p.DiagnosticError):
                jit.chunk_coverage(malformed)

    def test_whole_terminal_chunk_removal_and_unfinalized_headers_are_rejected(self):
        def chunk(start, duration, features):
            return struct.pack(">4sHHQQQQQQQI", b"FLR\0", 2, 1, 80, 0, 69, start, duration, 1, 1_000_000_000, features) + b"\0" * 12
        first, last = chunk(NS, 1000, 0), chunk(NS + 1000, 1000, 2)
        chunks = jit.chunk_coverage(first + last)
        self.assertEqual([False, True], [row["finalChunk"] for row in chunks])
        self.assertEqual([0, 2], [row["flags"] for row in chunks])
        with self.assertRaisesRegex(jit.p.DiagnosticError, "terminal-chunk-not-final"):
            jit.chunk_coverage(first)
        for state in (1, 254, 255):
            with self.subTest(state=state), self.assertRaisesRegex(jit.p.DiagnosticError, "chunk-not-finalized"):
                jit.chunk_coverage(first + chunk(NS + 1000, 1000, state << 24 | 2))
        # 시간의 겹침/순서 자체는 JDK consumer의 byte-complete chunk 계약을 어기지 않는다.
        self.assertEqual(2, len(jit.chunk_coverage(first + chunk(NS - 100, 2000, 2))))

    def test_event_starts_use_interval_union_and_cross_gap_durations_remain_observations(self):
        chunks = [{"startNs": NS, "endNs": NS + 250_000_000, "bytes": 100},
                  {"startNs": NS + 750_000_000, "endNs": NS + 1_000_000_000, "bytes": 100}]
        raw = compile_event(duration="PT0.8S")
        raw["values"]["startTime"] = "2026-10-26T03:00:00.1Z"
        rows, summary = sanitize([recording(), raw], chunks=chunks)
        self.assertEqual(500_000_000, rows[1]["elapsedOutsideHeaderIntervalsNs"])
        self.assertEqual(500_000_000, summary["coverage"]["uncoveredEnvelopeNs"])
        self.assertEqual([500_000_000], summary["coverage"]["adjacentChunkStartMinusPreviousEndNs"])
        raw["values"]["startTime"] = "2026-10-26T03:00:00.5Z"
        with self.assertRaisesRegex(jit.p.DiagnosticError, "outside-chunk"):
            sanitize([recording(), raw], chunks=chunks)
        chunks[1]["startNs"] = NS + 200_000_000
        raw["values"]["startTime"] = "2026-10-26T03:00:00.1Z"
        rows, summary = sanitize([recording(), raw], chunks=chunks)
        self.assertEqual(0, rows[1]["elapsedOutsideHeaderIntervalsNs"])
        self.assertEqual([-50_000_000], summary["coverage"]["adjacentChunkStartMinusPreviousEndNs"])

    def test_recording_boundary_deltas_preserve_unobserved_bootstrap_and_return_interval(self):
        _, summary = sanitize([recording()])
        jit.child_coverage(summary, {"launchAnchors": {"epochNs": NS - 200_000_000},
                                     "returnEpochNs": NS + 1_100_000_000})
        boundaries = summary["coverage"]["childAnchors"]
        self.assertEqual(200_000_000, boundaries["launchToChunkStartNs"])
        self.assertEqual(100_000_000, boundaries["chunkEndToReturnNs"])
        self.assertIn("not exact OS child", boundaries["scope"])

    def test_frames_retain_type_descriptor_and_cap_without_exporting_names(self):
        sample = event("ExecutionSample", sampledThread={"osThreadId": 12, "javaName": "private thread"},
                       stackTrace={"truncated": False, "frames": [{"method": method(), "type": "Inlined", "bytecodeIndex": 4}]})
        rows, _ = sanitize([recording(), sample])
        self.assertEqual("Inlined", rows[1]["frames"][0]["type"])
        self.assertEqual("unknown", rows[1]["thread"]["role"])
        self.assertNotIn("private thread", json.dumps(rows))
        for change in ([{"method": method(), "type": "private token"}], sample["values"]["stackTrace"]["frames"] * 33):
            sample["values"]["stackTrace"]["frames"] = change
            with self.assertRaises(jit.p.DiagnosticError):
                sanitize([recording(), sample])

    def test_timestamp_accepts_jfr_offsets_without_losing_nanoseconds(self):
        self.assertEqual(jit.timestamp("2026-10-26T03:00:00.000000001Z"),
                         jit.timestamp("2026-10-26T12:00:00.000000001+09:00"))
        for invalid in ("2026-10-26", "secret", "2026-99-26T03:00:00Z"):
            with self.assertRaises(jit.p.DiagnosticError):
                jit.timestamp(invalid)

    def test_cpu_load_gc_durations_are_separate_bounded_observations(self):
        rows, summary = sanitize([recording(), event("CPULoad", jvmUser=.5, jvmSystem=.1, machineTotal=.7),
                                   event("GCPhasePause", gcId=1, duration="PT0.01S"),
                                   event("GCCPUTime", gcId=1, userTime="PT0.02S", systemTime="PT0S", realTime="PT0.01S")])
        self.assertEqual(.5, rows[1]["jvmUser"])
        self.assertEqual(10_000_000, summary["gcPauseUnionNs"])
        self.assertEqual(20_000_000, rows[3]["userTimeNs"])
        with self.assertRaisesRegex(jit.p.DiagnosticError, "cpu-load"):
            sanitize([recording(), event("CPULoad", jvmUser=float("nan"), jvmSystem=0, machineTotal=0)])

    def test_event_count_is_enforced_before_iteration(self):
        with self.assertRaisesRegex(jit.p.DiagnosticError, "event-count-limit"):
            sanitize([recording()] * (jit.MAX_EVENTS + 1))

    def test_recorded_event_ids_reject_duplicates_unknown_and_excess(self):
        raw = b"\n".join(str(i).encode() + b" " + name.encode() for i, name in enumerate(jit.EVENTS, 1))
        self.assertEqual(len(jit.EVENTS), len(jit.event_ids(raw)))
        for invalid in (raw + b"\n1 jdk.X", b"1 private.path", b"x" * (128 * 1024 + 1)):
            with self.assertRaises(jit.p.DiagnosticError):
                jit.event_ids(invalid)

    def test_observer_records_numeric_os_tid_start_epoch_and_rejects_unbounded_timeline(self):
        observer = jit.TimelineObserver(999999999, Path("/tmp"))
        def sample(_self, pid, terminal):
            _self.samples += 1
            _self.rows[("42", 10)] = ("compiler", [1, 2, 3, 4, 5])
        with mock.patch.object(jit.r.Observer, "sample", sample):
            observer.sample(42, False)
            self.assertEqual(42, observer.timeline[0]["compilerThreads"][0]["osThreadId"])
            self.assertEqual(10, observer.timeline[0]["compilerThreads"][0]["startTicks"])
            observer.cells = 10000
            with self.assertRaisesRegex(jit.p.DiagnosticError, "timeline-cell-limit"):
                observer.sample(42, False)

    def test_any_role_tid_reuse_is_excluded_from_numeric_compiler_join(self):
        for later_role in ("application-including-native", "compiler"):
            with self.subTest(later_role=later_role):
                observer = jit.TimelineObserver(999999999, Path("/tmp"))
                observer.rows = {("42", 10): ("compiler", [1, 2, 3, 4, 5]),
                                 ("42", 20): (later_role, [2, 3, 4, 5, 6]),
                                 ("43", 30): ("gc", [1, 1, 1, 1, 1])}
                result = observer.report()
                self.assertEqual([42], result["ambiguousOsThreadIds"])
                self.assertEqual(2, result["observedOsThreadIdCount"])
                self.assertIn("exclude ambiguousOsThreadIds", result["tidJoin"])
                self.assertEqual(3 if later_role == "compiler" else 1,
                                 result["threadLifetimeObservedLowerBounds"]["compiler"][0])

    def test_zero_exit_over_final_rss_limit_is_not_pass(self):
        result = {"exit": 0, "stdout": b"", "stderr": b"", "wallNanos": 1, "phase": "reference-dead-private",
                  "rss": {"status": "observed", "peakBytes": 3 * 1024 * jit.MIB + 1}}
        with mock.patch.object(jit.p, "run", return_value=result):
            with self.assertRaisesRegex(jit.p.DiagnosticError, "final-rss"):
                jit.checked([], Path("/tmp"), {}, 999999999, Path("/tmp"), "reference-dead-private")

    def test_cli_pair_uses_identical_argv_and_rejects_output_stderr_or_exit_change(self):
        original = {"exit": 0, "stdout": b"same\n", "stderr": b"", "wallNanos": 1, "rss": {}}
        argv = ["binary", "dead", "--include-private-members"]
        for field, value in (("stdout", b"same\nextra diagnostic\n"), ("stderr", b"new warning"), ("exit", 2)):
            with self.subTest(field=field), mock.patch.object(jit, "checked", side_effect=[(original, {}), ({**original, field: value}, {})]) as checked:
                report = {}
                with self.assertRaisesRegex(jit.p.DiagnosticError, "output-mismatch"):
                    jit.run_pair(argv, Path("/tmp"), {}, 999999999, Path("/tmp"), Path("/tmp/profile"), report)
                self.assertEqual(argv, checked.call_args_list[0].args[0])
                self.assertEqual(argv, checked.call_args_list[1].args[0])
                self.assertNotIn("exactOutputEqual", report)
                self.assertNotIn("extra diagnostic", json.dumps(report))

    def test_same_seal_is_required_before_any_child(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            repo, private, output = [root / n for n in ("repo", "private", "output")]
            for path in (repo, private, output):
                path.mkdir()
            for module in jit.p.MODULES:
                path = repo / module / "build/classes/kotlin/main"
                path.mkdir(parents=True)
                (path / "One.class").write_bytes(b"wrong bytes")
            (repo / ".kartograph-self.pro").write_text("-keep class X\n")
            (repo / ".kartograph.yml").write_text("rules: []\n")
            with mock.patch.dict(os.environ, {"JAVA_HOME": td}), mock.patch.object(jit.r, "identities", return_value={"roots": "new"}), mock.patch.object(jit.p, "run") as run:
                with self.assertRaisesRegex(jit.p.DiagnosticError, "original-linux-identity"):
                    jit.capture(repo, output, private, {"canonicalSha256": {"roots": "sealed"}}, {}, 999999999)
                run.assert_not_called()

    def test_real_stock_jdk_recording_preserves_schema_settings_and_chunk_coverage(self):
        jdk = Path(os.environ.get("JAVA_HOME", "/Users/coden/.local/jdks/jdk-17.0.20+8/Contents/Home"))
        if not all((jdk / "bin" / name).is_file() for name in ("java", "javac", "jfr")):
            self.skipTest("installed JDK with jfr is unavailable")
        with tempfile.TemporaryDirectory(prefix="jit-attribution-schema-") as td:
            path = Path(td)
            source = path / "Burn.java"
            source.write_text('package dev.kartograph.synth; public class Burn { static volatile double sink; static double hot(int n) { double a=0; for(int i=0;i<n;i++) a+=Math.sqrt(i); return a; } public static void main(String[] args) { long end=System.nanoTime()+1200000000L; while(System.nanoTime()<end) sink=hot(10000); System.gc(); } }')
            subprocess.run([str(jdk / "bin/javac"), "-d", td, str(source)], capture_output=True, check=True, timeout=20)
            config = ROOT / "Scripts/JitAttributionCapture.jfc"
            recording_path = path / "capture.jfr"
            subprocess.run([str(jdk / "bin/java"), "-Xlog:jfr+startup=off", "-XX:FlightRecorderOptions=stackdepth=32",
                            "-XX:StartFlightRecording=settings=" + str(config) + ",filename=" + str(recording_path) + ",maxsize=0,maxage=0,dumponexit=true",
                            "-cp", td, "dev.kartograph.synth.Burn"], capture_output=True, check=True, timeout=30)
            raw = recording_path.read_bytes()
            chunks = jit.chunk_coverage(raw)
            self.assertTrue(chunks[-1]["finalChunk"])
            self.assertTrue(all(chunk["fileState"] == 0 for chunk in chunks))
            helper = path / "JitEventIds.java"
            helper.write_text(jit.TYPE_IDS_SOURCE)
            ids = jit.event_ids(subprocess.run([str(jdk / "bin/java"), str(helper), str(recording_path)], capture_output=True, check=True, timeout=20).stdout)
            decoded = subprocess.run([str(jdk / "bin/jfr"), "print", "--json", "--stack-depth", "32", "--events", ",".join(jit.EVENTS), str(recording_path)], capture_output=True, check=True, timeout=20).stdout
            self.assertLess(len(decoded), jit.p.LIMIT)
            ledger, summary = jit.sanitize(json.loads(decoded), ids, jit.expected_settings(config.read_bytes()), chunks)
            compilations = [row for row in ledger if row["event"] == "jdk.Compilation"]
            self.assertTrue(compilations)
            self.assertTrue(any(row["isOsr"] for row in compilations))
            self.assertTrue(any(row["method"]["label"].startswith("dev/kartograph/synth/") for row in compilations))
            self.assertTrue(summary["coverage"]["noncircular"])
            self.assertEqual(0, summary["coverage"]["dataLoss"])
            self.assertNotIn(str(path), json.dumps(ledger))


if __name__ == "__main__":
    unittest.main()
