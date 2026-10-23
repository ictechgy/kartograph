import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('product_verifier', ROOT / 'Scripts/verify-safeargs-product-test.py')
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class SafeArgsProductVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.evidence = Path(self.temporary.name)
        (self.evidence / 'product').mkdir()
        rows = []
        for variant in ['Debug', 'Release']:
            snapshot = json.dumps({'version': 4, 'generationEvidence': {'receipts': []}}).encode()
            (self.evidence / f'product/{variant}-cold-snapshot.json').write_bytes(snapshot)
            self.write(f'public-{variant}.json', {'tool': {'version': '0.25.0'}})
            for prefix in ['public', 'query']:
                self.write(f'{prefix}-{variant}-command.json', {'exit': 0, 'timeout': False, 'jars': {'cli.jar': 'a' * 64}})
            rows.append(dict(variant=variant, sources=3, publicVersion='0.25.0', publicGenerated=False,
                             queryExit=0, coldGenerated=True, manualUnchanged=True, callsUnchanged=True,
                             retentionUnchanged=True, liveGraph=True, liveDead=True, snapshotSha256=hashlib.sha256(snapshot).hexdigest()))
            self.live(f'live-{variant}', 'current-cold-success/SUCCEEDED')
        self.write('receipt.json', {'variants': rows})
        (self.evidence / 'product/Debug-warm-snapshot.json').write_bytes(snapshot)
        self.write('warm.json', dict(originBasis='historical-byte-equivalence', currentGenerationStatus='UP_TO_DATE',
                   currentAttempt=None, sameOrigin=True, newInvocation=True, queryExit=0, liveGraph=True, liveDead=True,
                   snapshotSha256=hashlib.sha256(snapshot).hexdigest()))
        self.live('live-warm', 'historical-byte-equivalence/UP_TO_DATE')
        self.write('live-extra-graph.json', {'nodes': [{'usr': 'class:p/StartFragmentArgs', 'synthesized': False}],
                                          'limitations': ['generation-live-unverified: fixture']})
        (self.evidence / 'product/live-disabled.txt').write_text('snapshots disabled: no snapshot, generation verification or unit-test task dependency\n', encoding='utf-8')
        self.write('query-warm-command.json', {'exit': 0, 'timeout': False, 'jars': {'cli.jar': 'a' * 64}})
        (self.evidence / 'product/warm-extra.txt').write_text('whole inventory changed; generation-unverified\n', encoding='utf-8')
        self.capture()

    def write(self, name, value):
        (self.evidence / 'product' / name).write_text(json.dumps(value), encoding='utf-8')

    def live(self, prefix, state):
        generated = ['p/StartFragmentArgs', 'p/StartFragmentDirections', 'p/StartFragmentDirections$Idle']
        manual = ['p/ManualArgs', 'p/ManualDirections', 'other/StartFragmentArgs']
        limitations = [f'generation-live-observation: {state}; liveRevalidated=true']
        self.write(prefix + '-graph.json', {'nodes': [{'usr': 'class:' + name, 'synthesized': name in generated}
                                                     for name in generated + manual], 'limitations': limitations})
        self.write(prefix + '-dead.json', {'diagnostics': [{'nodeId': 'class:' + name} for name in manual], 'limitations': limitations})

    def capture(self, omit=None):
        rows = []
        for path in sorted((self.evidence / 'product').iterdir()):
            if path.name == omit:
                continue
            raw = path.read_bytes()
            rows.append(f'{hashlib.sha256(raw).hexdigest()}\t{len(raw)}\tproduct/{path.name}')
        (self.evidence / 'capture.tsv').write_text('\n'.join(rows) + '\n', encoding='utf-8')

    def test_complete_bounded_synthetic_receipt_passes(self):
        self.assertTrue(VERIFIER.verify(self.evidence)['complete'])

    def test_present_but_not_captured_evidence_is_rejected(self):
        self.capture(omit='public-Debug.json')
        with self.assertRaisesRegex(ValueError, 'outside the capture inventory'):
            VERIFIER.verify(self.evidence)

    def test_snapshot_changed_after_capture_is_rejected(self):
        (self.evidence / 'product/Debug-cold-snapshot.json').write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'capture size mismatch|capture content mismatch'):
            VERIFIER.verify(self.evidence)

    def test_failed_cli_is_rejected_even_when_inventory_matches(self):
        self.write('query-Debug-command.json', {'exit': 2, 'timeout': False, 'jars': {'cli.jar': 'a' * 64}})
        self.capture()
        with self.assertRaisesRegex(ValueError, 'CLI process failed'):
            VERIFIER.verify(self.evidence)

    def test_missing_public_comparison_and_warm_success_are_rejected(self):
        receipt = json.loads((self.evidence / 'product/receipt.json').read_text())
        receipt['variants'][0]['publicVersion'] = None
        self.write('receipt.json', receipt)
        self.capture()
        with self.assertRaisesRegex(ValueError, 'public/current CLI comparison'):
            VERIFIER.verify(self.evidence)
        receipt['variants'][0]['publicVersion'] = '0.25.0'
        self.write('receipt.json', receipt)
        warm = json.loads((self.evidence / 'product/warm.json').read_text())
        warm['currentGenerationStatus'] = 'SUCCEEDED'
        self.write('warm.json', warm)
        self.capture()
        with self.assertRaisesRegex(ValueError, 'warm historical origin/current status'):
            VERIFIER.verify(self.evidence)

    def test_live_handwritten_promotion_is_rejected(self):
        graph = json.loads((self.evidence / 'product/live-Debug-graph.json').read_text())
        next(row for row in graph['nodes'] if row['usr'] == 'class:p/ManualArgs')['synthesized'] = True
        self.write('live-Debug-graph.json', graph); self.capture()
        with self.assertRaisesRegex(ValueError, 'live handwritten'):
            VERIFIER.verify(self.evidence)

    def test_warm_live_current_status_is_required(self):
        self.live('live-warm', 'current-cold-success/SUCCEEDED'); self.capture()
        with self.assertRaisesRegex(ValueError, 'live generation current status'):
            VERIFIER.verify(self.evidence)

    def test_changed_output_must_not_be_live_generated(self):
        self.write('live-extra-graph.json', {'nodes': [{'usr': 'class:p/StartFragmentArgs', 'synthesized': True}],
                                          'limitations': ['generation-live-unverified: fixture']})
        self.capture()
        with self.assertRaisesRegex(ValueError, 'changed output inventory promoted'):
            VERIFIER.verify(self.evidence)


if __name__ == '__main__':
    unittest.main()
