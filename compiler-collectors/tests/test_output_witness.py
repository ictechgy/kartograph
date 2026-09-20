import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('witness', Path(__file__).resolve().parents[1] / 'processor_output_witness.py')
witness = importlib.util.module_from_spec(spec); spec.loader.exec_module(witness)


class OutputWitnessTests(unittest.TestCase):
    def test_file_cannot_impersonate_directory_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input'; path.mkdir()
            child = path / 'a'; child.write_bytes(b'x')
            before = witness.inventory(path)
            encoded = json.dumps([['a', hashlib.sha256(b'x').hexdigest()]], sort_keys=True, separators=(',', ':')).encode()
            child.unlink(); path.rmdir(); path.write_bytes(encoded)
            self.assertNotEqual(before, witness.inventory(path))

    def test_symlink_cannot_escape_declared_project(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory).resolve(); root = base / 'project'; root.mkdir()
            target = base / 'outside'; target.write_text('outside fixture')
            (root / 'link').symlink_to(target)
            with self.assertRaises(witness.EvidenceError):
                witness.locate(root, 'link')
            with self.assertRaises(witness.EvidenceError):
                witness.inventory(root)
            self.assertEqual(target.read_text(), 'outside fixture')

    def test_failure_and_zero_exit_without_processor_leave_no_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); (root / 'src').mkdir(); (root / 'src/Input.java').write_text('class Input {}')
            artifact = root / 'fixture.jar'; artifact.write_bytes(b'fixture-input')
            config = {'project': str(root), 'scope': 'fixture:main', 'kind': 'javac', 'processor': 'fixture.Processor',
                      'inputs': ['src'], 'outputRoots': ['generated'], 'collectorJar': str(artifact), 'processorJar': str(artifact),
                      'token': '.evidence/token', 'observations': '.evidence/outputs.tsv', 'receipt': '.evidence/receipt.json'}
            path = root / 'config.json'; receipt = root / config['receipt']; receipt.parent.mkdir()
            for status in (2, 0):
                with self.subTest(exit=status):
                    receipt.write_text('previous completed receipt')
                    config['command'] = [sys.executable, '-c', f'raise SystemExit({status})']
                    path.write_text(json.dumps(config))
                    with self.assertRaises(witness.EvidenceError):
                        witness.record(path, root / 'logs')
                    self.assertFalse(receipt.exists())
                    self.assertFalse((root / config['token']).exists())


if __name__ == '__main__':
    unittest.main()
