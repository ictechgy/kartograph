import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import run as study


def output_result(valid=True):
    value=dict(type='result', subtype='success', is_error=False, total_cost_usd=0.1,
               result='Raw prose remains preserved.', structured_output=dict(
                   targets=[dict(file='C.kt',symbol='C.f()',kind='caller',reason='calls changed method',evidence='C.kt:1')],
                   unknowns=[],summary='Review caller.'))
    if not valid:
        del value['structured_output']
        value['result']='{"targets":[],"unknowns":[],"summary":"truncated"'
    return value


class StructuredTrialTest(unittest.TestCase):
    def test_prompt_limit_counts_utf8_bytes_before_model_execution(self):
        case=dict(repository='toy',base='0'*40,module='.')
        self.assertIn('small patch',study.render_prompt(case,'small patch'))
        with self.assertRaisesRegex(ValueError,'prompt'):
            study.render_prompt(case,'한'*(study.transport.MAX_PROMPT//3+1))

    def test_ambient_schema_module_cannot_replace_the_pinned_validator(self):
        code='''import importlib.util,sys,types
shadow=types.ModuleType('structured')
shadow.SCHEMA={}
shadow.validate_result=lambda result: ({},None)
sys.modules['structured']=shadow
spec=importlib.util.spec_from_file_location('reviewed_run',sys.argv[1])
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
result={'type':'result','subtype':'success','is_error':False,'result':'{}'}
assert module.validate_result(result)==(None,'missing-structured-output')
assert module.SCHEMA['properties']['targets']['maxItems']==64
'''
        result=subprocess.run([sys.executable,'-c',code,str(Path(study.__file__).resolve())],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)

    def test_product_tools_in_source_arm_and_failed_mcp_are_rejected(self):
        initial=dict(model='claude-opus-5[1m]',tools=sorted(study.transport.expected_tools('mcp')|{'StructuredOutput'}),
                     mcp_servers=[dict(name='preflight',status='connected')])
        self.assertEqual(study.tooling_error(initial,'source',initial['model']),'unexpected-claude-tool-surface')
        initial['mcp_servers'][0]['status']='failed'
        self.assertEqual(study.tooling_error(initial,'mcp',initial['model']),'unexpected-claude-mcp-status')

    def test_output_tool_is_allowed_but_extra_execution_tool_is_rejected(self):
        initial=dict(model='claude-opus-5[1m]', tools=sorted(study.transport.expected_tools('source')|{'StructuredOutput'}),
                     mcp_servers=[dict(name='preflight',status='connected')])
        original=copy.deepcopy(initial)
        self.assertIsNone(study.tooling_error(initial,'source','claude-opus-5[1m]'))
        self.assertEqual(initial,original)
        initial['tools'].append('Bash')
        self.assertEqual(study.tooling_error(initial,'source','claude-opus-5[1m]'),'unexpected-claude-tool-surface')

    def test_model_change_and_missing_output_tool_are_rejected(self):
        initial=dict(model='unexpected',tools=sorted(study.transport.expected_tools('mcp')|{'StructuredOutput'}),
                     mcp_servers=[dict(name='preflight',status='connected')])
        self.assertEqual(study.tooling_error(initial,'mcp','claude-opus-5[1m]'),'unexpected-provider-model')
        initial['model']='claude-opus-5[1m]';initial['tools'].remove('StructuredOutput')
        self.assertEqual(study.tooling_error(initial,'mcp','claude-opus-5[1m]'),'unexpected-claude-tool-surface')

    def execute_rows(self, root, results, outcomes=None, duplicate=False, surface=None, real_proxy=False,
                     trace_available=True, post_error=None):
        output=root/'trials';output.mkdir()
        inventory=root/'inventory.json';inventory.write_text(json.dumps(dict(declarations=[dict(id='method:C#f()V',
            file='C.kt',aliases=['C.f()'],sourceAddressable=True)],targets=['method:C#f()V'])))
        case=dict(id='toy',sourceRoot=str(root),inventory=str(inventory),source={},snapshot='snapshot.json',
                  bindings='bindings.json',prompt='Read only')
        if real_proxy:
            subprocess.run(['git','init','-q'],cwd=root,check=True)
            (root/'C.kt').write_text('class C { fun f() {} }\n')
            subprocess.run(['git','add','C.kt'],cwd=root,check=True)
            case['source']=study.transport.source_manifest(root)
        manifest=dict(cases=[case],schedule=[dict(case='toy',arm='source' if i%2==0 else 'mcp',repeat=i//2)
            for i in range(len(results))],binary='not-executed',javaHome='not-used',model='claude-opus-5[1m]',
            effort='low',systemPrompt='Read only',wallSeconds=1,maxBudgetUsd=3,outputSchema=study.SCHEMA)
        (output/'manifest.json').write_text(json.dumps(manifest))
        records=[]
        for result in results:
            events=[result] if not duplicate else [result,result]
            records.append((events,result,[dict(name='StructuredOutput',input={})],{},[]))
        commands=[]
        def fake_provider(command,prompt,environment,directory,wall):
            commands.append(command)
            for name in ['stream.jsonl','stderr']:(directory/name).write_text('')
            if real_proxy:
                server=json.loads(command[command.index('--mcp-config')+1])['mcpServers']['preflight']
                messages=[dict(jsonrpc='2.0',id=1,method='initialize',params=dict(protocolVersion='2025-11-25',capabilities={},clientInfo=dict(name='test',version='1'))),
                          dict(jsonrpc='2.0',id=2,method='tools/list',params={})]
                process=subprocess.run([server['command'],*server['args']],input=''.join(json.dumps(row)+'\n' for row in messages),capture_output=True,text=True,timeout=10)
                self.assertEqual(process.returncode,0,process.stderr)
                responses=[json.loads(line) for line in process.stdout.splitlines()]
                listed=next(row for row in responses if row.get('id')==2)['result']['tools']
                self.assertEqual(len(listed),3)
            elif trace_available:
                (directory/'tools.jsonl').write_text('')
            return outcomes.pop(0) if outcomes else dict(exit=0,captureFailures=[],timedOut=False,streamOversized=False,stderrOversized=False)
        checks=None if post_error is None else [None,None,post_error]
        with patch.object(study,'check',side_effect=checks),patch.object(study.transport,'run_provider',side_effect=fake_provider), \
             patch.object(study.transport,'parse_events',side_effect=records), \
             patch.object(study,'tooling_error',return_value=surface):
            study.execute(manifest,output)
        return output,json.loads((output/'results.json').read_text()),commands

    def test_missing_output_remains_zero_and_complete_schedule_continues(self):
        with tempfile.TemporaryDirectory() as temp:
            output,rows,commands=self.execute_rows(Path(temp),[output_result(False),output_result()])
            self.assertEqual(len(rows),2)
            self.assertEqual(rows[0]['grading']['error'],'missing-structured-output')
            self.assertEqual(rows[0]['grading']['primaryRecall'],0)
            self.assertEqual(rows[1]['grading']['primaryRecall'],1)
            self.assertEqual(rows[1]['informationToolUses'],[])
            self.assertEqual(len(rows[1]['outputToolUses']),1)
            self.assertEqual(json.loads((output/'execution.json').read_text())['status'],'completed')
            answer=json.loads((output/rows[1]['directory']/'answer.json').read_text())
            self.assertEqual(answer['text'],'Raw prose remains preserved.')
            self.assertEqual(answer['structuredOutput'],output_result()['structured_output'])
            for command in commands:
                self.assertEqual(json.loads(command[command.index('--json-schema')+1]),study.SCHEMA)
            with self.assertRaisesRegex(ValueError,'already started'):study.execute({},output)

    def test_timeout_cannot_receive_credit_from_a_well_shaped_partial_result(self):
        outcome=dict(exit=-15,captureFailures=[],timedOut=True,streamOversized=False,stderrOversized=False)
        with tempfile.TemporaryDirectory() as temp:
            _,rows,_=self.execute_rows(Path(temp),[output_result()],[outcome])
            self.assertFalse(rows[0]['grading']['valid'])
            self.assertEqual(rows[0]['grading']['primaryRecall'],0)
            self.assertIn('timedOut',rows[0]['infrastructureErrors'])

    def test_multiple_final_results_are_not_silently_reduced_to_last(self):
        with tempfile.TemporaryDirectory() as temp:
            _,rows,_=self.execute_rows(Path(temp),[output_result()],duplicate=True)
            self.assertEqual(rows[0]['grading']['primaryRecall'],0)
            self.assertIn('result-event-count',rows[0]['infrastructureErrors'])

    def test_provider_budget_failure_is_preserved_in_denominator(self):
        result=output_result();result.update(subtype='error_max_budget_usd',is_error=True)
        with tempfile.TemporaryDirectory() as temp:
            _,rows,_=self.execute_rows(Path(temp),[result,output_result()])
            self.assertEqual(len(rows),2)
            self.assertEqual(rows[0]['providerSubtype'],'error_max_budget_usd')
            self.assertEqual(rows[0]['grading']['primaryRecall'],0)

    def test_invalid_tool_surface_stops_and_preserves_trial(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaisesRegex(ValueError,'surface'):
                self.execute_rows(root,[output_result()],surface='unexpected-claude-tool-surface')
            self.assertEqual(json.loads((root/'trials/execution.json').read_text())['status'],'failed')
            row=json.loads((root/'trials/results.json').read_text())[0]
            self.assertEqual(row['grading']['primaryRecall'],0)

    def test_real_source_proxy_can_create_its_trace_and_connect(self):
        with tempfile.TemporaryDirectory() as temp:
            _,rows,_=self.execute_rows(Path(temp),[output_result()],real_proxy=True)
            self.assertEqual(rows[0]['grading']['primaryRecall'],1)

    def test_missing_proxy_trace_is_not_manufactured_or_scored(self):
        with tempfile.TemporaryDirectory() as temp:
            output,rows,_=self.execute_rows(Path(temp),[output_result()],trace_available=False)
            self.assertEqual(rows[0]['grading']['primaryRecall'],0)
            self.assertFalse(rows[0]['proxyTraceAvailable'])
            self.assertFalse((output/rows[0]['directory']/'tools.jsonl').exists())

    def test_post_verification_timeout_is_not_mislabeled_as_changed_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with self.assertRaises(ValueError):
                self.execute_rows(root,[output_result()],post_error=subprocess.TimeoutExpired('verify',180))
            row=json.loads((root/'trials/results.json').read_text())[0]
            self.assertEqual(row['postTrialFailureType'],'TimeoutExpired')
            self.assertIn('post-trial-verification-failed',row['infrastructureErrors'])
            self.assertNotIn('frozen-inputs-changed-after-trial',row['infrastructureErrors'])
            self.assertEqual(row['grading']['primaryRecall'],0)


if __name__=='__main__':
    unittest.main()
