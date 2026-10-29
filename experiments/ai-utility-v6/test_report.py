import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from report import product_observations

SCRIPT=Path(__file__).with_name('report.py')


def write(path,value):
    path.write_text(json.dumps(value))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ReportIntegrityTest(unittest.TestCase):
    def test_handshakes_are_not_product_tool_uses_and_document_is_read(self):
        rows=[
            dict(channel='to_product',message=dict(id=1,method='initialize',params={})),
            dict(channel='from_product',message=dict(id=1,result={'protocolVersion':'2025-11-25'})),
            dict(channel='to_product',message=dict(id=2,method='tools/list',params={})),
            dict(channel='from_product',message=dict(id=2,result={'tools':[]})),
            dict(channel='from_client',message=dict(id=3,method='tools/call',params={'name':'freshness'})),
            dict(channel='to_product',message=dict(id=4,method='tools/call',params={'name':'freshness'})),
            dict(channel='from_product',message=dict(id=4,result={'structuredContent':{'document':{'status':'matched'}}})),
        ]
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'trace.jsonl';path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
            observations=product_observations(path)
            self.assertEqual(observations['requested'],{'freshness':1})
            self.assertEqual(observations['processed'],{'freshness':1})
            self.assertEqual(observations['agentFreshness'],['matched'])
            self.assertEqual(len(observations['responses']),1)

    def fixture(self,root,missing_cost=False):
        cases=[];schedule=[];rows=[]
        for index in range(4):
            identity=f'method:C{index}#f()V';oracle=root/f'oracle-{index}.json'
            write(oracle,dict(declarations=[dict(id=identity,sourceAddressable=True)],targets=[identity],
                              direct=[identity],indirect=[],executedRuleSuiteAnchors=[]))
            case=dict(id=f'case-{index}',module=f'module-{index}',inventory=str(oracle),inventorySha256=digest(oracle))
            cases.append(case)
            for repeat in range(2):
                for arm in (['source','mcp'] if repeat==0 else ['mcp','source']):
                    trial=dict(case=case['id'],arm=arm,repeat=repeat);schedule.append(trial)
                    directory=root/f'{len(rows):02d}-{arm}';directory.mkdir()
                    for name in ['source-manifest.json','product-command.json','mcp-config.json','stream.jsonl','stderr','tools.jsonl','answer.json']:
                        (directory/name).write_text('' if name.endswith('jsonl') or name=='stderr' else '{}')
                    grading=dict(valid=True,error=None,covered=[identity],primaryRecall=1.0,
                                 ambiguousPredictions=[],duplicatePredictions=[],outsideOracle=[])
                    rows.append(dict(trial,directory=directory.name,grading=grading,seconds=1,
                        providerCostUsd=None if missing_cost and not rows else 0.1,
                        providerSubtype='success',structuredOutputError=None,infrastructureErrors=[],proxyTraceAvailable=True,
                        toolUses=[dict(name='StructuredOutput')],informationToolUses=[],outputToolUses=[dict(name='StructuredOutput')],
                        evidenceSha256={file.name:digest(file) for file in directory.iterdir()}))
        write(root/'manifest.json',dict(cases=cases,schedule=schedule))
        write(root/'results.json',rows)
        write(root/'execution.json',dict(status='completed',trials=16,manifestSha256=digest(root/'manifest.json'),
                                        resultsSha256=digest(root/'results.json')))
        return rows

    def report(self,root):
        return subprocess.run([sys.executable,str(SCRIPT),'--run',str(root),'--output',str(root/'report.json')],capture_output=True,text=True)

    def test_output_finalization_is_separate_from_information_tools(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);self.fixture(root);result=self.report(root)
            self.assertEqual(result.returncode,0,result.stderr)
            report=json.loads((root/'report.json').read_text())
            self.assertEqual(report['trialDetails'][0]['informationToolRequests'],0)
            self.assertEqual(report['trialDetails'][0]['outputToolRequests'],1)

    def test_missing_cost_is_not_silently_zero(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);self.fixture(root,missing_cost=True);result=self.report(root)
            self.assertEqual(result.returncode,0,result.stderr)
            report=json.loads((root/'report.json').read_text())
            self.assertIsNone(report['modelCostUsd'])
            self.assertEqual(report['missingCostTrials'],1)
            self.assertAlmostEqual(report['knownModelCostUsd'],1.5)

    def test_changed_raw_answer_or_scored_results_are_rejected(self):
        for target in ['00-source/answer.json','results.json']:
            with self.subTest(target=target),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);self.fixture(root);path=root/target;path.write_text(path.read_text()+' ')
                self.assertNotEqual(self.report(root).returncode,0)

    def test_changed_oracle_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);self.fixture(root);path=root/'oracle-0.json';path.write_text(path.read_text()+' ')
            self.assertNotEqual(self.report(root).returncode,0)

    def test_partial_schedule_cannot_claim_completed_comparison(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);rows=self.fixture(root);write(root/'results.json',rows[:-1])
            self.assertNotEqual(self.report(root).returncode,0)

    def test_unavailable_trace_remains_explicit_in_complete_schedule(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);rows=self.fixture(root);row=rows[0]
            row.update(proxyTraceAvailable=False,infrastructureErrors=['proxy-trace-unavailable'])
            row['grading'].update(valid=False,error='infrastructure-error',covered=[],primaryRecall=0)
            del row['evidenceSha256']['tools.jsonl'];(root/row['directory']/'tools.jsonl').unlink()
            write(root/'results.json',rows)
            execution=json.loads((root/'execution.json').read_text());execution['resultsSha256']=digest(root/'results.json');write(root/'execution.json',execution)
            result=self.report(root);self.assertEqual(result.returncode,0,result.stderr)
            report=json.loads((root/'report.json').read_text())
            self.assertEqual(report['trialDetails'][0]['product']['status'],'unavailable')
            self.assertEqual(report['responseValidityByArm']['source']['valid'],7)


if __name__=='__main__':unittest.main()
