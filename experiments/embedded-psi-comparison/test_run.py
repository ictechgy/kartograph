#!/usr/bin/env python3
"""실제 JVM 없이 중단·timeout의 process-group 정리와 실패 기록을 검사한다."""
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import run


class RunnerCleanupTest(unittest.TestCase):
    def invoke(self, first_wait, expected_exception):
        process = Mock(pid=12345)
        process.wait.side_effect = [first_wait, 0]
        with tempfile.TemporaryDirectory(prefix='psi-runner-unit-test-') as directory:
            output = Path(directory) / 'new-run'
            with patch.object(run, 'required_tool', return_value=Path('/mock/tool')), \
                 patch.object(run.subprocess, 'Popen', return_value=process), \
                 patch.object(run.os, 'killpg') as kill, \
                 patch.object(run.sys, 'argv', ['run.py', '--output', str(output)]):
                with self.assertRaises(expected_exception):
                    run.main()
                kill.assert_called_once_with(12345, signal.SIGTERM)
                return json.loads((output / 'stages.json').read_text())

    def test_interrupt_stops_group_and_preserves_interrupt(self):
        stages = self.invoke(KeyboardInterrupt(), KeyboardInterrupt)
        self.assertEqual(stages[0]['status'], 'interrupted')
        self.assertEqual(stages[0]['exception'], 'KeyboardInterrupt')
        self.assertIsNone(stages[0]['exit'])

    def test_timeout_stops_group_and_records124(self):
        stages = self.invoke(subprocess.TimeoutExpired('mock', 1), RuntimeError)
        self.assertEqual(stages, [{'stage': 'dependencies', 'exit': 124}])


if __name__ == '__main__':
    unittest.main(verbosity=2)
