"""미수거 자식의 종료 관측을 검증한다. 실제 PID에는 신호를 보내지 않는다."""
import errno
import importlib.util
from pathlib import Path
import types
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("wait_observation_probe", Path(__file__).parents[1] / "verify-index-cost.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("module specification with a loader is required")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class WaitObservationTest(unittest.TestCase):
    def observe(self, value):
        with mock.patch.object(probe.os, "waitid", return_value=value, create=True), mock.patch.object(
                probe.signal, "getsignal", return_value=probe.signal.SIG_DFL):
            return probe.nonreaping_waiter()(4242)

    def test_no_terminal_result_keeps_waiting(self):
        self.assertFalse(self.observe(None))

    def test_complete_terminal_result_preserves_ownership(self):
        value = types.SimpleNamespace(si_pid=4242, si_signo=probe.signal.SIGCHLD, si_code=probe.os.CLD_EXITED)
        self.assertTrue(self.observe(value))

    def test_incomplete_result_is_lost_ownership(self):
        for value in (object(), types.SimpleNamespace(), types.SimpleNamespace(si_pid=4242)):
            with self.subTest(value=value), self.assertRaises(ChildProcessError) as raised:
                self.observe(value)
            self.assertEqual(errno.ECHILD, raised.exception.errno)
