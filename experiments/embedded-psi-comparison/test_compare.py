#!/usr/bin/env python3
"""가짜 입력은 grader 계약만 검사한다. PSI나 제품 실행 근거로 저장하지 않는다."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('comparison_grader', HERE / 'compare.py')
grader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(grader)


class ComparisonGateTest(unittest.TestCase):
    def setUp(self):
        reverse = {usr: {'file': file, 'nameStartUtf16': offset} for (file, offset), usr in grader.anchors().items()}
        self.psi = [{'kind': 'method', 'engine': 'kotlin-aa' if 'PsiKotlin#caller' in caller else 'java-psi',
                     'resolved': True, 'caller': reverse[caller], 'target': reverse[target]}
                    for caller, target in sorted(grader.EXPECTED)]
        self.graph = {'nodes': [{'usr': usr} for usr in sorted(grader.DECLARATIONS.values())],
                      'edges': [{'source': caller, 'target': target, 'kind': 'call'}
                                for caller, target in sorted(grader.EXPECTED)]}

    def invoke(self, graph):
        with tempfile.TemporaryDirectory(prefix='psi-grader-unit-test-') as directory:
            root = Path(directory)
            (root / 'psi.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in self.psi))
            (root / 'graph.json').write_text(json.dumps(graph))
            run = subprocess.run([sys.executable, str(HERE / 'compare.py'), '--psi', str(root / 'psi.jsonl'),
                                  '--graph', str(root / 'graph.json'), '--output', str(root / 'result.json')],
                                 capture_output=True, text=True, timeout=10)
            return run.returncode, json.loads((root / 'result.json').read_text())

    def test_exact_primary_graph_passes(self):
        code, result = self.invoke(self.graph)
        self.assertEqual(code, 0)
        self.assertTrue(result['comparisonQualified'])
        self.assertEqual(result['kartograph']['declaredMethodsFound'], 8)
        self.assertEqual(result['kartograph']['sourceFileAndLineMatches'], 0)

    def test_missing_primary_edge_fails(self):
        graph = copy.deepcopy(self.graph)
        graph['edges'].pop()
        code, result = self.invoke(graph)
        self.assertNotEqual(code, 0)
        self.assertFalse(result['comparisonQualified'])
        self.assertEqual(len(result['kartograph']['missing']), 1)

    def test_missing_unused_method_fails(self):
        graph = copy.deepcopy(self.graph)
        graph['nodes'] = [node for node in graph['nodes'] if node['usr'] != 'method:probe/PsiJava#unused()I']
        code, result = self.invoke(graph)
        self.assertNotEqual(code, 0)
        self.assertTrue(result['crossEnginePrimaryEdgeEquality'])
        self.assertFalse(result['comparisonQualified'])
        self.assertEqual(result['kartograph']['declaredMethodsMissing'], ['method:probe/PsiJava#unused()I'])

    def test_unexpected_opposite_overload_fails(self):
        graph = copy.deepcopy(self.graph)
        graph['edges'].append({'source': 'method:probe/PsiKotlin#caller()I',
                               'target': 'method:probe/PsiJava#leaf(Ljava/lang/String;)Ljava/lang/String;',
                               'kind': 'call'})
        code, result = self.invoke(graph)
        self.assertNotEqual(code, 0)
        self.assertFalse(result['comparisonQualified'])
        self.assertEqual(len(result['kartograph']['unexpected']), 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
