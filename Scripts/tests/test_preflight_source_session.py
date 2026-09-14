"""모델 비교의 공통 source 표면과 실제 제품 응답 전달 경계를 검증한다."""
import importlib.util
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('preflight_session', ROOT / 'experiments/preflight-evaluation/session_mcp.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
run_spec = importlib.util.spec_from_file_location('preflight_run', ROOT / 'experiments/preflight-evaluation/run.py')
runner = importlib.util.module_from_spec(run_spec)
run_spec.loader.exec_module(runner)


class PreflightSourceSessionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        (self.root / 'A.java').write_text('class A {\n  int value;\n  int valueAgain;\n}\n')
        (self.root / 'hidden.patch').write_text('must not be exposed')
        subprocess.run(['git', 'init', '-q', self.root], check=True)
        subprocess.run(['git', '-C', self.root, 'add', 'A.java', 'hidden.patch'], check=True)
        self.session = module.Session(self.root, io.StringIO(), 10)

    def tearDown(self):
        self.session.close()
        self.temp.cleanup()

    def test_read_and_literal_search_have_stable_pages(self):
        self.assertEqual(['A.java'], self.session.source_call('source_list', {})['results'])
        read = self.session.source_call('source_read', {'file': 'A.java', 'startLine': 2, 'limit': 1})
        self.assertEqual('2:   int value;', read['text'])
        self.assertTrue(read['hasNext'])
        result = self.session.source_call('source_search', {'text': 'value', 'offset': 1, 'limit': 1})
        self.assertEqual(2, result['total'])
        self.assertEqual(3, result['results'][0]['line'])
        self.assertFalse(result['hasNext'])

    def test_hidden_outside_untracked_and_symlink_files_are_rejected(self):
        (self.root / 'Untracked.java').write_text('class Untracked {}')
        for name in ('hidden.patch', '../outside.java', '/etc/passwd', 'Untracked.java'):
            self.assertTrue(self.session.call('source_read', {'file': name})['isError'])
        original = self.root / 'A.java'
        original.unlink()
        outside = Path(self.temp.name) / 'outside.java'
        outside.write_text('class Hidden {}')
        original.symlink_to(outside)
        self.assertTrue(self.session.call('source_read', {'file': 'A.java'})['isError'])

    def test_shared_budget_and_product_response_are_preserved(self):
        self.session.remaining = 2
        product_result = {'content': [{'type': 'text', 'text': '{"status":"notFound"}'}],
                          'structuredContent': {'status': 'notFound'}}
        calls = []
        self.session.product_tools = {'query_symbol': {'name': 'query_symbol'}}
        def forward(method, params):
            calls.append((method, params))
            return {'result': product_result}
        self.session.product_rpc = forward
        self.assertFalse(self.session.call('source_list', {}).get('isError', False))
        self.assertIs(product_result, self.session.call('query_symbol', {'symbol': 'Missing'}))
        self.assertEqual([('tools/call', {'name': 'query_symbol', 'arguments': {'symbol': 'Missing'}})], calls)
        self.assertTrue(self.session.call('source_read', {'file': 'A.java'})['isError'])

    def test_invalid_arguments_do_not_read_source(self):
        for args in ({'file': 'A.java', 'limit': True}, {'file': 'A.java', 'startLine': 0},
                     {'file': 'A.java', 'command': 'ignored'}):
            self.assertTrue(self.session.call('source_read', args)['isError'])

    def test_fixed_source_hash_rejects_content_changes(self):
        raw = (self.root / 'A.java').read_bytes()
        fixed = {'A.java': hashlib.sha256(raw).hexdigest()}
        session = module.Session(self.root, io.StringIO(), 12, source_hashes=fixed)
        try:
            (self.root / 'A.java').write_text('class Changed {}\n')
            with self.assertRaisesRegex(ValueError, 'changed after the fixed manifest'):
                session.read_file('A.java')
        finally:
            session.close()

    def test_product_startup_failure_closes_the_spawned_process(self):
        marker = Path(self.temp.name) / 'product-closed'
        fake = Path(self.temp.name) / 'fake-product.py'
        fake.write_text('''import json, pathlib, sys
request = json.loads(sys.stdin.readline())
print(json.dumps({"jsonrpc":"2.0","id":request["id"],"error":{"code":-1,"message":"failed"}}), flush=True)
for _ in sys.stdin: pass
pathlib.Path(sys.argv[1]).write_text("closed")
''')
        with self.assertRaisesRegex(RuntimeError, 'product startup failed'):
            module.Session(self.root, io.StringIO(), 12, [sys.executable, str(fake), str(marker)])
        deadline = time.monotonic() + 2
        while not marker.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual('closed', marker.read_text())

    def test_source_only_proxy_main_preserves_three_tool_surface(self):
        manifest = Path(self.temp.name) / 'source-manifest.json'
        manifest.write_text(json.dumps({'A.java': hashlib.sha256((self.root / 'A.java').read_bytes()).hexdigest()}))
        trace = Path(self.temp.name) / 'trace.jsonl'
        requests = [
            {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {}},
            {'jsonrpc': '2.0', 'method': 'notifications/initialized'},
            {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list', 'params': {}},
            {'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call',
             'params': {'name': 'source_read', 'arguments': {'file': 'A.java', 'limit': 1}}},
        ]
        completed = subprocess.run(
            [sys.executable, str(ROOT / 'experiments/preflight-evaluation/session_mcp.py'),
             '--repository', str(self.root), '--trace', str(trace), '--source-manifest', str(manifest), '--max-tools', '12'],
            input=''.join(json.dumps(request) + '\n' for request in requests), text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        self.assertEqual(0, completed.returncode, completed.stderr)
        responses = [json.loads(line) for line in completed.stdout.splitlines()]
        tools = responses[1]['result']['tools']
        self.assertEqual(['source_list', 'source_read', 'source_search'], [tool['name'] for tool in tools])
        self.assertEqual('A.java', responses[2]['result']['structuredContent']['file'])
        self.assertTrue(trace.is_file())


class PreflightRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.cases = self.root / 'cases'
        self.cases.mkdir()
        cohort = []
        for index in range(4):
            folder = self.cases / f'case-{index}'
            base = folder / 'base'
            base.mkdir(parents=True)
            (base / 'A.java').write_text(f'class A{index} {{}}\n')
            subprocess.run(['git', 'init', '-q', base], check=True)
            subprocess.run(['git', '-C', base, 'add', 'A.java'], check=True)
            subprocess.run(['git', '-C', base, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                            'commit', '-qm', 'base'], check=True)
            head = subprocess.check_output(['git', '-C', base, 'rev-parse', 'HEAD'], text=True).strip()
            (folder / 'production-change.patch').write_text(f'diff --git a/A.java b/A.java\n+case {index}\n')
            (folder / 'snapshot.json').write_text('{}\n')
            (folder / 'bindings.json').write_text('{}\n')
            cohort.append({'id': f'case-{index}', 'repository': f'public/repo-{index}', 'base': head})
        self.cohort = self.root / 'cohort.json'
        self.cohort.write_text(json.dumps({'primary': cohort}))
        self.oracle = self.root / 'oracle.json'
        self.oracle.write_text('{"fixed":true}\n')
        distribution = self.root / 'distribution'
        (distribution / 'bin').mkdir(parents=True)
        (distribution / 'lib').mkdir()
        self.binary = distribution / 'bin' / 'kartograph'
        self.binary.write_bytes(b'fixed binary')
        (distribution / 'lib' / 'kartograph.jar').write_bytes(b'fixed library')
        self.output = self.root / 'evidence'
        self.args = SimpleNamespace(cohort=self.cohort, cases=self.cases, oracle=self.oracle,
                                    output=self.output, binary=self.binary)
        self.proxy = ROOT / 'experiments/preflight-evaluation/session_mcp.py'

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self):
        manifest = runner.build_manifest(self.args, self.proxy, self.binary)
        self.output.mkdir()
        runner.write_json(self.output / 'manifest.json', manifest, exclusive=True)
        return manifest

    def test_prepared_manifest_is_validated_without_overwrite(self):
        manifest = self.prepare()
        original = (self.output / 'manifest.json').read_bytes()
        runner.validate_prepared(self.args, manifest, self.proxy, self.binary)
        patch = self.cases / 'case-0' / 'production-change.patch'
        patch.write_text('changed after prepare\n')
        with self.assertRaisesRegex(runner.EvaluationError, 'case bytes changed'):
            runner.validate_prepared(self.args, manifest, self.proxy, self.binary)
        self.assertEqual(original, (self.output / 'manifest.json').read_bytes())

    def test_prepared_manifest_rejects_changed_model_budget_or_prompt(self):
        manifest = self.prepare()
        for key, value in [('model', 'different-model'), ('effort', 'high'), ('wallSeconds', 901),
                           ('maxBudgetUsd', 4), ('snapshotMaxMiB', 64), ('systemPrompt', 'changed')]:
            altered = dict(manifest, **{key: value})
            with self.assertRaises(runner.EvaluationError, msg=key):
                runner.validate_prepared(self.args, altered, self.proxy, self.binary)
        altered = json.loads(json.dumps(manifest))
        altered['cohort'][0]['prompt'] = 'Changed question with a recomputed self hash'
        altered['cohort'][0]['promptSha256'] = hashlib.sha256(altered['cohort'][0]['prompt'].encode()).hexdigest()
        with self.assertRaises(runner.EvaluationError):
            runner.validate_prepared(self.args, altered, self.proxy, self.binary)

    def test_preparation_rejects_source_changed_from_the_frozen_commit(self):
        manifest = self.prepare()
        (self.cases / 'case-0/base/A.java').write_text('modified before preparation\n')
        with self.assertRaises(runner.EvaluationError):
            runner.build_manifest(self.args, self.proxy, self.binary)
        with self.assertRaises(runner.EvaluationError):
            runner.validate_prepared(self.args, manifest, self.proxy, self.binary)
        subprocess.run(['git', '-C', str(self.cases / 'case-0/base'), 'add', 'A.java'], check=True)
        with self.assertRaises(runner.EvaluationError):
            runner.build_manifest(self.args, self.proxy, self.binary)

    def test_untracked_build_adapter_does_not_change_frozen_source(self):
        (self.cases / 'case-0/base/alternate.preflight-build-pom.xml').write_text('<project/>\n')
        manifest = self.prepare()
        runner.validate_prepared(self.args, manifest, self.proxy, self.binary)

    def test_preparation_rejects_a_source_file_that_search_cannot_read(self):
        base = self.cases / 'case-0/base'
        (base / 'Large.java').write_bytes(b' ' * (1024 * 1024 + 1))
        subprocess.run(['git', '-C', str(base), 'add', 'Large.java'], check=True)
        subprocess.run(['git', '-C', str(base), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                        'commit', '-qm', 'large input'], check=True)
        cohort = json.loads(self.cohort.read_text())
        cohort['primary'][0]['base'] = subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip()
        self.cohort.write_text(json.dumps(cohort))
        with self.assertRaisesRegex(runner.EvaluationError, 'one MiB'):
            runner.build_manifest(self.args, self.proxy, self.binary)

    def test_partial_event_stream_is_retained_and_classified(self):
        stream = self.root / 'stream.jsonl'
        stream.write_bytes(
            (json.dumps({'type': 'system', 'subtype': 'init', 'tools': sorted(runner.SOURCE_TOOLS),
                         'mcp_servers': [{'name': 'preflight', 'status': 'connected'}]}) + '\n').encode() +
            b'{"type":"assistant"\n' +
            (json.dumps({'type': 'result', 'is_error': True}) + '\n').encode())
        events, result, uses, initial, diagnostics = runner.parse_events(stream)
        self.assertEqual(2, len(events))
        self.assertTrue(result['is_error'])
        self.assertEqual([], uses)
        self.assertEqual('system', initial['type'])
        self.assertEqual(['malformed-event'], [item['kind'] for item in diagnostics])

    def test_unexpected_tool_surface_records_once_and_forbids_retry(self):
        manifest = self.prepare()

        def fake_provider(command, prompt, environment, run, wall_seconds):
            event = {'type': 'system', 'subtype': 'init', 'tools': [],
                     'mcp_servers': [{'name': 'preflight', 'status': 'connected'}]}
            (run / 'stream.jsonl').write_text(json.dumps(event) + '\n' + json.dumps({'type': 'result', 'is_error': False}) + '\n')
            (run / 'stderr').write_text('')
            (run / 'tools.jsonl').write_text('')
            return {'started': True, 'timedOut': False, 'streamOversized': False,
                    'stderrOversized': False, 'captureFailures': [], 'exit': 0}

        with mock.patch.object(runner, 'run_provider', fake_provider):
            runner.execute(self.args, manifest, self.proxy, self.binary)
        progress = json.loads((self.output / 'progress.json').read_text())
        self.assertEqual(1, len(progress))
        self.assertEqual(['unexpected-claude-tool-surface'], progress[0]['infrastructureErrors'])
        self.assertEqual('aborted', json.loads((self.output / 'execution.json').read_text())['status'])
        with self.assertRaisesRegex(runner.EvaluationError, 'selective retry is forbidden'):
            runner.execute(self.args, manifest, self.proxy, self.binary)

    def test_timeout_malformed_stream_and_provider_failure_retain_all_trials(self):
        manifest = self.prepare()

        def failed_provider(command, prompt, environment, run, wall_seconds):
            arm = 'mcp' if '--product-command' in json.loads((run / 'mcp-config.json').read_text())['mcpServers']['preflight']['args'] else 'source'
            initial = {'type': 'system', 'subtype': 'init', 'tools': sorted(runner.expected_tools(arm)),
                       'mcp_servers': [{'name': 'preflight', 'status': 'connected'}]}
            (run / 'stream.jsonl').write_bytes((json.dumps(initial) + '\n').encode() + b'{partial\n' +
                (json.dumps({'type': 'result', 'is_error': True}) + '\n').encode())
            (run / 'stderr').write_text('bounded provider failure')
            (run / 'tools.jsonl').write_text('')
            return {'started': True, 'timedOut': True, 'streamOversized': False,
                    'stderrOversized': False, 'captureFailures': [], 'exit': -15}

        with mock.patch.object(runner, 'run_provider', failed_provider):
            runner.execute(self.args, manifest, self.proxy, self.binary)
        progress = json.loads((self.output / 'progress.json').read_text())
        self.assertEqual(16, len(progress))
        for record in progress:
            self.assertTrue({'provider-timeout', 'provider-exit', 'provider-result-error', 'malformed-event'}
                            .issubset(record['infrastructureErrors']))
            self.assertEqual(64, len(record['inputHashes']['manifest']))
            self.assertEqual(64, len(record['evidenceHashes']['stream']))
        self.assertEqual('completed', json.loads((self.output / 'execution.json').read_text())['status'])

    def test_prepare_only_then_execute_prepared_keeps_manifest_bytes(self):
        common = ['run.py', '--cohort', str(self.cohort), '--cases', str(self.cases), '--oracle', str(self.oracle),
                  '--output', str(self.output), '--binary', str(self.binary)]
        with mock.patch.object(sys, 'argv', common + ['--prepare-only']):
            self.assertEqual(0, runner.main())
        prepared = (self.output / 'manifest.json').read_bytes()

        def wrong_tools(command, prompt, environment, run, wall_seconds):
            (run / 'stream.jsonl').write_text(json.dumps({'type': 'system', 'subtype': 'init', 'tools': [],
                'mcp_servers': [{'name': 'preflight', 'status': 'connected'}]}) + '\n')
            (run / 'stderr').write_text('')
            (run / 'tools.jsonl').write_text('')
            return {'started': True, 'timedOut': False, 'streamOversized': False,
                    'stderrOversized': False, 'captureFailures': [], 'exit': 0}

        with mock.patch.object(runner, 'run_provider', wrong_tools), \
                mock.patch.object(sys, 'argv', common + ['--execute-prepared']):
            self.assertEqual(0, runner.main())
        self.assertEqual(prepared, (self.output / 'manifest.json').read_bytes())
        self.assertEqual(1, len(json.loads((self.output / 'progress.json').read_text())))

    def test_fake_provider_timeout_is_bounded(self):
        run = self.root / 'provider-run'
        run.mkdir()
        outcome = runner.run_provider([sys.executable, '-c', 'import time; time.sleep(5)'], 'prompt', os.environ.copy(), run, 0.05)
        self.assertTrue(outcome['timedOut'])
        self.assertLessEqual((run / 'stream.jsonl').stat().st_size, runner.MAX_STREAM)
        self.assertLessEqual((run / 'stderr').stat().st_size, runner.MAX_STDERR)
