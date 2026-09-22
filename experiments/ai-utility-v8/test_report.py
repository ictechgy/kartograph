import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('v8_report_tests', HERE / 'report.py')
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)
study = report.study


class RawReportTest(unittest.TestCase):
    def make_run(self, root):
        inventory = dict(declarations=[dict(id='method:C#' + name + '()V',
            file='src/test/T.kt' if name != 'caller' else 'src/main/C.kt',
            aliases=['C.' + name + '()'], sourceAddressable=True) for name in ['caller', 'positive', 'negative']],
            targets=['method:C#caller()V', 'method:C#positive()V'], direct=['method:C#caller()V'], indirect=[],
            behaviorPositive=['method:C#positive()V'], behaviorNegative=['method:C#negative()V'],
            executedTestMethods=['method:C#positive()V', 'method:C#negative()V'])
        path = root / 'oracle.json'; path.write_text(json.dumps(inventory))
        tool = root / 'grader-input.txt'; tool.write_text('frozen implementation')
        cases = [dict(id=str(i), sourceRoot=str(root), inventory=str(path), inventorySha256=study.digest(path),
            source={}, snapshot='unused', bindings='unused', prompt='test') for i in range(4)]
        schedule = [dict(case=c['id'], arm=arm, repeat=repeat) for c in cases for repeat in range(2)
                    for arm in (['source', 'mcp'] if repeat == 0 else ['mcp', 'source'])]
        manifest = dict(format='kartograph-ai-utility-v8-manifest', cases=cases, schedule=schedule,
            binary='unused', javaHome='unused', model='claude-opus-5[1m]', effort='low', systemPrompt='test',
            wallSeconds=1, maxBudgetUsd=3, outputSchema=study.SCHEMA, toolHashes={str(tool): study.digest(tool)})
        folder = root / 'run'; folder.mkdir()
        (folder / 'manifest.json').write_text(json.dumps(manifest))
        def provider(command, prompt, environment, directory, wall):
            config = json.loads(command[command.index('--mcp-config') + 1])
            arm = 'mcp' if '--product-command' in config['mcpServers']['preflight']['args'] else 'source'
            answer = dict(targets=[dict(file='src/test/T.kt', symbol='C.positive()', kind='test',
                reason='assertion changes', evidence='T.kt:1')], unknowns=[], summary='test')
            events = [dict(type='system', subtype='init', model=manifest['model'],
                tools=sorted(study.transport.expected_tools(arm) | {'StructuredOutput'}),
                mcp_servers=[dict(name='preflight', status='connected')]),
                dict(type='assistant', message=dict(content=[dict(type='tool_use', name='StructuredOutput', input=answer)])),
                dict(type='result', subtype='success', is_error=False, result='raw', structured_output=answer, total_cost_usd=.1)]
            (directory / 'stream.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in events))
            (directory / 'stderr').write_text(''); (directory / 'tools.jsonl').write_text('')
            return dict(exit=0, captureFailures=[])
        with patch.object(study, 'check'), patch.object(study.transport, 'run_provider', side_effect=provider), contextlib.redirect_stdout(io.StringIO()):
            study.execute(manifest, folder)
        return folder

    def test_controller_raw_roundtrip_preserves_separate_metrics(self):
        with tempfile.TemporaryDirectory() as temp:
            result = report.render(self.make_run(Path(temp)))
            self.assertEqual(result['trials'], 16)
            self.assertEqual(result['cases'][0]['arms']['source']['callCoverage'], [0, 0])
            self.assertEqual(result['cases'][0]['arms']['source']['behaviorCoverage'], [1, 1])
            self.assertEqual(result['responseValidityByArm']['mcp']['valid'], 8)

    def test_raw_and_oracle_changes_are_rejected(self):
        for target in ['oracle.json', 'raw']:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); folder = self.make_run(root)
                path = root / 'oracle.json' if target == 'oracle.json' else folder / '00-0-source-0/stream.jsonl'
                path.write_text(path.read_text() + ' ')
                with self.assertRaises(ValueError): report.render(folder)

    def test_regrading_detects_changed_scores_even_with_new_result_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = self.make_run(Path(temp)); path = folder / 'results.json'
            results = json.loads(path.read_text()); results[0]['grading']['behaviorRecall'] = 0
            path.write_text(json.dumps(results))
            execution = json.loads((folder / 'execution.json').read_text())
            execution['resultsSha256'] = study.digest(path)
            (folder / 'execution.json').write_text(json.dumps(execution))
            with self.assertRaisesRegex(ValueError, 'regrading'): report.render(folder)

    def test_partial_schedule_cannot_be_reported_as_complete(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = self.make_run(Path(temp)); path = folder / 'results.json'
            path.write_text(json.dumps(json.loads(path.read_text())[:-1]))
            with self.assertRaisesRegex(ValueError, 'schedule'): report.render(folder)

    def test_changed_grader_is_rejected_before_original_answer_regrading(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); folder = self.make_run(root)
            (root / 'grader-input.txt').write_text('changed implementation')
            with self.assertRaisesRegex(ValueError, 'frozen tooling changed'): report.render(folder)


if __name__ == '__main__': unittest.main()
