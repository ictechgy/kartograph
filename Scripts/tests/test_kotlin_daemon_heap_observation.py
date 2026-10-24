import importlib.util
import os
from pathlib import Path
import sys
import unittest

SPEC = importlib.util.spec_from_file_location(
    "heap_observation", Path(__file__).resolve().parents[1] / "observe-kotlin-daemon-heap.py")
HEAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HEAP)


class KotlinDaemonHeapObservationTest(unittest.TestCase):
    def test_only_exact_kotlin_daemon_is_inspected_and_only_heap_is_exposed(self):
        calls = []

        def read(args):
            calls.append(args)
            if args[-1] == "-l":
                return "12 " + HEAP.DAEMON + "\n13 GradleDaemon\n14 " + HEAP.DAEMON + "Extra\n", None
            return "12:\n-XX:MaxHeapSize=1073741824 -Dsecret=never-output\n", None

        result = HEAP.observe("/jdk", read)
        self.assertEqual(result["processes"], [{"pid": 12, "status": "observed", "maxHeapBytes": 1073741824}])
        self.assertEqual(result["status"], "observed")
        self.assertEqual(len(calls), 2)
        self.assertNotIn("secret", str(result))

    def test_process_limit_preserves_partial_status(self):
        calls = []

        def read(args):
            calls.append(args)
            if args[-1] == "-l":
                return "\n".join(f"{i} {HEAP.DAEMON}" for i in range(1, 7)), None
            return "-XX:MaxHeapSize=2147483648", None

        result = HEAP.observe("/jdk", read)
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["processesOmitted"], 2)
        self.assertEqual(len(calls), 5)
        self.assertEqual(result["processes"][0]["maxHeapBytes"], 2147483648)

    def test_missing_exited_and_ambiguous_flags_stay_unobserved(self):
        self.assertEqual(HEAP.observe(None)["reason"], "java-home-unavailable")
        self.assertEqual(HEAP.observe("/jdk", lambda _: ("1 Other\n", None))["reason"], "no-live-kotlin-daemon")
        for flags, reason in [(None, "process-exited-or-access-unavailable"), ("", None),
                              ("-XX:MaxHeapSize=1 -XX:MaxHeapSize=2", None)]:
            responses = iter([(f"12 {HEAP.DAEMON}\n", None), (flags, reason)])
            result = HEAP.observe("/jdk", lambda _: next(responses))
            self.assertEqual(result["status"], "unobserved")
            self.assertNotIn("maxHeapBytes", result["processes"][0])

    def test_real_command_output_limit(self):
        value, reason = HEAP.read_command([sys.executable, "-c", "print('x'*200000)"])
        self.assertIsNone(value)
        self.assertEqual(reason, "output-limit")

    def test_real_command_timeout_and_error(self):
        value, reason = HEAP.read_command([sys.executable, "-c", "import time; time.sleep(30)"], timeout=0.05)
        self.assertIsNone(value)
        self.assertEqual(reason, "observation-timeout")
        self.assertEqual(HEAP.read_command([sys.executable, "-c", "raise SystemExit(7)"])[1],
                         "process-exited-or-access-unavailable")

    def test_real_command_success_and_missing_tool(self):
        self.assertEqual(HEAP.read_command([sys.executable, "-c", "print('-XX:MaxHeapSize=1024')"]),
                         ("-XX:MaxHeapSize=1024" + os.linesep, None))
        self.assertEqual(HEAP.read_command([str(Path(__file__).parent / "does-not-exist")])[1], "tool-unavailable")


if __name__ == "__main__":
    unittest.main()
