import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('v8_execution_tests', Path(__file__).with_name('execution.py'))
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


def execution(rows):
    return dict(invocations={uid: dict(method=method, status=status) for uid, method, status in rows}, skippedContainers=0)


class ExecutionTest(unittest.TestCase):
    def journal(self, folder, rows, complete=True):
        events = [dict(type='start', version=1, initialTests=len(rows))]
        events += [dict(type='test', uid=uid, method=method, status=status, throwableClass=None) for uid, method, status in rows]
        events += [dict(type='complete', complete=complete, registeredTests=len(rows), terminalTests=len(rows), containerFailures=0, skippedContainers=0)]
        p = folder / 'plan-1-1.jsonl'; p.write_text(''.join(json.dumps(e) + '\n' for e in events))
        p.with_name(p.name + '.complete.json').write_text(json.dumps(dict(journal=p.name, complete=complete, sha256=hashlib.sha256(p.read_bytes()).hexdigest())))
        return p

    def test_identical_display_names_keep_distinct_method_invocations(self):
        rows = [('alpha:1', 'method:C#alpha(I)V', 'pass'), ('beta:1', 'method:C#beta(I)V', 'pass')]
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); self.journal(folder, rows)
            value = study.read_execution(folder)
            self.assertEqual({r['method'] for r in value['invocations'].values()}, {'method:C#alpha(I)V', 'method:C#beta(I)V'})

    def test_unsealed_incomplete_modified_and_duplicate_journals_fail_closed(self):
        for mode in ['unsealed', 'incomplete', 'modified', 'duplicate']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                folder = Path(temp)
                rows = [('test:1', 'method:C#one()V', 'pass')] * (2 if mode == 'duplicate' else 1)
                path = self.journal(folder, rows, complete=mode != 'incomplete')
                if mode == 'unsealed': path.with_name(path.name + '.complete.json').unlink()
                if mode == 'modified': path.write_text(path.read_text() + ' ')
                with self.assertRaises(ValueError): study.read_execution(folder)

    def test_parameterized_failures_are_aggregated_once_per_source_method(self):
        before = execution([('a1', 'method:C#a(I)V', 'pass'), ('a2', 'method:C#a(I)V', 'pass'), ('b1', 'method:C#b()V', 'pass')])
        after = execution([('a1', 'method:C#a(I)V', 'assertion-failure'), ('a2', 'method:C#a(I)V', 'pass'), ('b1', 'method:C#b()V', 'pass')])
        result = study.partition(before, after)
        self.assertEqual(result['positive'], ['method:C#a(I)V'])
        self.assertEqual(result['negative'], ['method:C#b()V'])
        self.assertEqual(result['assertionFailures'], 1)

    def test_unidentified_container_skip_cannot_pass_as_a_stable_leaf_skip(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp); path = self.journal(folder, [('a', 'method:C#a()V', 'pass')])
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            rows[-1]['skippedContainers'] = 1
            path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
            seal = path.with_name(path.name + '.complete.json')
            data = json.loads(seal.read_text()); data['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            seal.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'container skip'): study.read_execution(folder)

    def test_same_skips_are_explicit_but_skip_changes_are_not_dropped(self):
        before = execution([('a', 'method:C#a()V', 'pass'), ('b', 'method:C#b()V', 'pass'), ('s', 'method:C#s()V', 'skipped')])
        after = execution([('a', 'method:C#a()V', 'assertion-failure'), ('b', 'method:C#b()V', 'pass'), ('s', 'method:C#s()V', 'skipped')])
        self.assertEqual(study.partition(before, after)['skippedInvocations'], 1)
        after['invocations']['s']['status'] = 'pass'
        with self.assertRaises(ValueError): study.partition(before, after)

    def test_missing_changed_identity_errors_and_baseline_failures_invalidate_case(self):
        before = execution([('a', 'method:C#a()V', 'pass'), ('b', 'method:C#b()V', 'pass')])
        for mode in ['missing', 'identity', 'error', 'aborted', 'baseline']:
            with self.subTest(mode=mode):
                original = json.loads(json.dumps(before)); after = json.loads(json.dumps(before))
                if mode == 'missing': del after['invocations']['b']
                elif mode == 'identity': after['invocations']['a']['method'] = 'method:C#other()V'
                elif mode == 'baseline': original['invocations']['a']['status'] = 'assertion-failure'
                else: after['invocations']['a']['status'] = mode
                with self.assertRaises(ValueError): study.partition(original, after)


if __name__ == '__main__': unittest.main()
