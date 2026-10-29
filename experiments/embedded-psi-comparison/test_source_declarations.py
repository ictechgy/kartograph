#!/usr/bin/env python3
"""가짜 graph는 위치 checker의 엄격성만 검증하며 제품 실행 근거가 아니다."""
import copy
import json
from pathlib import Path
import unittest
from check_source_declarations import check

HERE = Path(__file__).resolve().parent


class SourceDeclarationGateTest(unittest.TestCase):
    def setUp(self):
        self.expected = json.loads((HERE / 'source-location-expectations.json').read_text())
        self.graph = {'nodes': []}
        for row in self.expected['methods']:
            anchor = {key: row[key] for key in ['path', 'line', 'column', 'offsetUtf16']}
            anchor.update(sourceSha256=self.expected['sourceSha256'][row['path']], coordinateBasis='rawDecodedUtf16',
                          origin='sourceHeader', pathKind='projectRelative')
            self.graph['nodes'].append({'usr': row['usr'], 'sourceDeclaration': anchor,
                                        'location': {'path': row['path'], 'line': row['line']}})

    def result(self, baseline=None):
        return check(self.graph, self.expected, HERE / 'fixture', baseline)

    def test_all_exact_anchors_pass(self):
        self.assertTrue(self.result()['qualified'])

    def test_missing_overload_anchor_fails(self):
        del self.graph['nodes'][0]['sourceDeclaration']
        self.assertFalse(self.result()['qualified'])
        self.assertEqual(self.result()['exactAnchors'], 7)

    def test_swapped_overload_anchors_fail(self):
        a, b = self.graph['nodes'][:2]
        a['sourceDeclaration'], b['sourceDeclaration'] = b['sourceDeclaration'], a['sourceDeclaration']
        self.assertFalse(self.result()['qualified'])
        self.assertEqual(self.result()['exactAnchors'], 6)

    def test_stale_source_hash_fails(self):
        self.graph['nodes'][0]['sourceDeclaration']['sourceSha256'] = '0' * 64
        self.assertFalse(self.result()['qualified'])

    def test_bytecode_location_cannot_replace_source_anchor(self):
        node = self.graph['nodes'][0]
        node['location'] = node.pop('sourceDeclaration')
        self.assertFalse(self.result()['qualified'])

    def test_changed_bytecode_location_fails_separate_baseline_check(self):
        baseline = copy.deepcopy(self.graph)
        self.graph['nodes'][0]['location']['line'] += 1
        result = self.result(baseline)
        self.assertFalse(result['bytecodeLocationsUnchanged'])
        self.assertFalse(result['qualified'])
        self.assertEqual(result['exactAnchors'], 8)


if __name__ == '__main__':
    unittest.main(verbosity=2)
