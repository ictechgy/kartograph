"""고정 v6 controller 회귀를 새 v8 실행기에 적용한다."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import json
import subprocess
import sys
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


study = load('v8_controller_under_test', HERE / 'run.py')
tests = load('v6_controller_regressions', HERE.parent / 'ai-utility-v6/test_run.py')
tests.study = study
StructuredTrialTest = tests.StructuredTrialTest


class PreparationIntegrityTest(unittest.TestCase):
    def test_real_run_cannot_be_prepared_without_native_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, 'native preparation evidence'):
                study.prepare(dict(mode='trials'), Path(temp) / 'run')

    def test_changed_native_evidence_is_rejected_before_model_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'native.json'; path.write_text('original evidence')
            manifest = dict(format='kartograph-ai-utility-v8-manifest', model='claude-opus-5[1m]', effort='low',
                maxTools=12, maxPredictions=64, wallSeconds=900, maxBudgetUsd=3, outputSchema=study.SCHEMA,
                outputChannel='structured_output', outputTool='StructuredOutput', providerCliVersion='test',
                toolHashes={}, preparationArtifactHashes={str(path):study.digest(path)}, cases=[], binary='unused', plugin='unused')
            with patch.object(study.subprocess, 'check_output', return_value='test\n'), patch.object(study, 'tooling', return_value={}):
                study.check(manifest)
                path.write_text('modified evidence')
                with self.assertRaisesRegex(ValueError, 'preparation evidence'): study.check(manifest)

    def test_real_proxy_rejects_thirteenth_information_request(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / 'C.kt').write_text('class C\n')
            subprocess.run(['git', '-C', str(root), 'add', 'C.kt'], check=True)
            manifest = root / 'source.json'; manifest.write_text(json.dumps(study.transport.source_manifest(root)))
            requests = [dict(jsonrpc='2.0', id=1, method='initialize', params=dict(protocolVersion='2025-11-25',
                capabilities={}, clientInfo=dict(name='budget-control', version='1')))]
            requests += [dict(jsonrpc='2.0', id=i + 2, method='tools/call', params=dict(name='source_list', arguments={})) for i in range(13)]
            result = subprocess.run([sys.executable, str(study.PROXY), '--repository', str(root), '--trace', str(root / 'trace.jsonl'),
                '--source-manifest', str(manifest), '--max-tools', '12'],
                input=''.join(json.dumps(row) + '\n' for row in requests), capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            replies = [json.loads(line) for line in result.stdout.splitlines()]
            self.assertEqual(len(replies), 14)
            self.assertTrue(all(not row['result'].get('isError', False) for row in replies[1:13]))
            self.assertTrue(replies[13]['result']['isError'])
            self.assertIn('Shared tool budget exhausted', replies[13]['result']['content'][0]['text'])
