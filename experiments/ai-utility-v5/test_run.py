import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import run as study


class TrialAccountingTest(unittest.TestCase):
    def test_invalid_format_is_recorded_and_does_not_abort_next_trial(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); output = root / 'trials'; output.mkdir()
            inventory = root / 'inventory.json'
            inventory.write_text(json.dumps(dict(declarations=[dict(id='method:C#f()V', file='C.kt',
                aliases=['C.f()'], sourceAddressable=True)], targets=['method:C#f()V'])))
            case = dict(id='toy', sourceRoot=str(root), inventory=str(inventory), source={},
                        snapshot='snapshot.json', bindings='bindings.json', prompt='Read only')
            manifest = dict(cases=[case], schedule=[dict(case='toy',arm='source',repeat=0),
                dict(case='toy',arm='mcp',repeat=0)], binary='not-executed', javaHome='not-used',
                model='not-executed', effort='low', systemPrompt='Read only', wallSeconds=1)
            (output / 'manifest.json').write_text(json.dumps(manifest))
            valid = json.dumps(dict(targets=[dict(file='C.kt',symbol='C.f()')],unknowns=[]))
            parsed = [([],dict(result='Prose before JSON',total_cost_usd=0),[],{},[]),
                      ([],dict(result=valid,total_cost_usd=0),[],{},[])]
            def fake_provider(command, prompt, environment, directory, wall):
                for name in ['stream.jsonl','stderr','tools.jsonl']:
                    (directory / name).write_text('')
                return dict(exit=0,captureFailures=[],timedOut=False,streamOversized=False,stderrOversized=False)
            with patch.object(study,'check'), patch.object(study.transport,'run_provider',side_effect=fake_provider), \
                    patch.object(study.transport,'parse_events',side_effect=parsed), \
                    patch.object(study.transport,'tooling_error',return_value=None), \
                    patch.object(study.transport,'proxy_trace_errors',return_value=[]):
                study.execute(manifest,output)
            records=json.loads((output/'results.json').read_text())
            self.assertEqual(len(records),2)
            self.assertFalse(records[0]['grading']['valid'])
            self.assertEqual(records[0]['grading']['primaryRecall'],0)
            self.assertTrue(records[1]['grading']['valid'])
            self.assertEqual(json.loads((output/'execution.json').read_text())['status'],'completed')


if __name__=='__main__':
    unittest.main()
