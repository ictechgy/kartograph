"""실제 javac/javap로 독립 oracle의 입력 경계와 overload 선택을 검증한다."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
COLLECTOR = ROOT / 'experiments/preflight-evaluation/collect_direct_oracle.py'


class PreflightOracleTest(unittest.TestCase):
    def test_native_references_keep_exact_overload_and_reject_outside_class_root(self):
        configured = os.environ.get('JAVA_HOME')
        javac = str(Path(configured) / 'bin/javac') if configured else shutil.which('javac')
        javap = str(Path(configured) / 'bin/javap') if configured else shutil.which('javap')
        if not javac or not javap:
            self.skipTest('JDK javac and javap are required')
        with tempfile.TemporaryDirectory(prefix='preflight-oracle-') as temporary:
            case = Path(temporary) / 'case'
            base = case / 'base'
            source = base / 'src/main/java/p'
            classes = base / 'classes'
            source.mkdir(parents=True)
            classes.mkdir()
            (case / 'oracle').mkdir()
            api = source / 'Api.java'
            caller = source / 'Caller.java'
            api.write_text('package p; public class Api { public static void select(int v) {} public static void select(Object v) {} }')
            caller.write_text('package p; public class Caller { public void integerCaller() { Api.select(1); } public void objectCaller() { Api.select(new Object()); } }')
            subprocess.run([javac, '-g', '-d', str(classes), str(api), str(caller)], check=True, capture_output=True, timeout=30)
            subprocess.run(['git', 'init', str(base)], check=True, capture_output=True, timeout=30)
            subprocess.run(['git', '-C', str(base), 'add', 'src'], check=True, capture_output=True, timeout=30)
            inputs = case / 'inputs.json'
            inputs.write_text(json.dumps({'classRoots': ['classes']}))
            targets = case / 'targets.json'
            targets.write_text(json.dumps({'p/Api': ['select:(I)V']}))
            command = [sys.executable, str(COLLECTOR), '--case', 'synthetic', '--directory', str(case),
                       '--inputs', str(inputs), '--targets', str(targets), '--javap', javap]
            subprocess.run(command, check=True, capture_output=True, timeout=30)
            result = json.loads((case / 'oracle/direct-invocations.json').read_text())
            self.assertEqual(2, result['classCount'])
            self.assertEqual(['method:p/Caller#integerCaller()V'], [row['caller'] for row in result['references']])
            self.assertEqual(['src/main/java/p/Caller.java'], result['references'][0]['sourceCandidates'])
            no_source = source / 'ZCaller.java'
            no_source.write_text('package p; public class ZCaller { public void call() { Api.select(1); } }')
            subprocess.run([javac, '-g:none', '-cp', str(classes), '-d', str(classes), str(no_source)], check=True, capture_output=True, timeout=30)
            subprocess.run(['git', '-C', str(base), 'add', 'src'], check=True, capture_output=True, timeout=30)
            subprocess.run(command, check=True, capture_output=True, timeout=30)
            result = json.loads((case / 'oracle/direct-invocations.json').read_text())
            unknown_source = next(row for row in result['references'] if row['caller'] == 'method:p/ZCaller#call()V')
            self.assertEqual([], unknown_source['sourceCandidates'])
            inputs.write_text(json.dumps({'classRoots': []}))
            self.assertEqual(2, subprocess.run(command, capture_output=True, timeout=30).returncode)
            outside = Path(temporary) / 'outside'
            outside.mkdir()
            inputs.write_text(json.dumps({'classRoots': [str(outside)]}))
            rejected = subprocess.run(command, capture_output=True, text=True, timeout=30)
            self.assertEqual(2, rejected.returncode)
            self.assertIn('inside the frozen checkout', rejected.stderr)
