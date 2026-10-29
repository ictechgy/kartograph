import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('v7_assemble_tests', Path(__file__).with_name('assemble.py'))
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


class BehavioralOracleTest(unittest.TestCase):
    def test_only_observed_assertion_change_is_positive(self):
        before = {'boundary': 'pass', 'sameCallerDifferentValue': 'pass', 'otherOverload': 'pass'}
        after = dict(before, boundary='assertion-failure')
        self.assertEqual(study.behavioral_partition(before, after),
                         (['boundary'], ['otherOverload', 'sameCallerDifferentValue']))

    def test_baseline_failure_cannot_be_an_impact_anchor(self):
        with self.assertRaises(ValueError):
            study.behavioral_partition({'x': 'assertion-failure'}, {'x': 'assertion-failure'})

    def test_execution_errors_and_skips_are_not_silently_excluded(self):
        for status in ['error', 'skip', 'not-run', 'compile-failed']:
            with self.subTest(status=status), self.assertRaises(ValueError):
                study.behavioral_partition({'x': 'pass', 'y': 'pass'}, {'x': 'assertion-failure', 'y': status})

    def test_missing_or_added_test_fails_qualification(self):
        for after in [{}, {'x': 'assertion-failure', 'extra': 'pass'}]:
            with self.assertRaises(ValueError):
                study.behavioral_partition({'x': 'pass'}, after)

    def test_positive_and_negative_controls_are_required(self):
        for after in [{}, {'x': 'pass'}, {'x': 'assertion-failure'}]:
            with self.assertRaises(ValueError):
                study.behavioral_partition({x: 'pass' for x in after}, after)


if __name__ == '__main__': unittest.main()
