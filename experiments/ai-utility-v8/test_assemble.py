import importlib.util
import hashlib
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('v8_assemble_tests', Path(__file__).with_name('assemble.py'))
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


class ExecutionScopeTest(unittest.TestCase):
    def data(self):
        return dict(declarations=[dict(id='method:p/LocalTest#positive()V', sourceAddressable=True),
            dict(id='method:p/LocalTest#negative()V', sourceAddressable=True)], classSha256={'p/LocalTest': 'hash'})

    def behavior(self):
        return dict(positive=['method:p/LocalTest#positive()V'], negative=['method:p/LocalTest#negative()V', 'method:dependency/BaseTest#inherited()V'],
            executed=['method:p/LocalTest#positive()V', 'method:p/LocalTest#negative()V', 'method:dependency/BaseTest#inherited()V'])

    def test_stable_external_inherited_test_is_preserved_outside_source_scope(self):
        result = study.source_scope(self.data(), self.behavior())
        self.assertEqual(result['outOfScope'], ['method:dependency/BaseTest#inherited()V'])
        self.assertEqual(result['negative'], ['method:p/LocalTest#negative()V'])

    def test_unmapped_method_in_a_supplied_class_is_never_dropped(self):
        data = self.data(); data['declarations'][1]['sourceAddressable'] = False
        with self.assertRaises(ValueError): study.source_scope(data, self.behavior())

    def test_behavior_positive_outside_source_scope_invalidates_case(self):
        behavior = self.behavior(); behavior['positive'].append(behavior['negative'].pop())
        with self.assertRaises(ValueError): study.source_scope(self.data(), behavior)

    def test_shadowed_compiled_owners_cannot_choose_the_first_copy(self):
        data = self.data(); data['shadowedOwners'] = ['p/LocalTest']
        with self.assertRaisesRegex(ValueError, 'shadowed'): study.source_scope(data, self.behavior())

    def test_baseline_rewrite_and_unsuccessful_capture_are_rejected(self):
        clean = dict(exit=0, matched=True, dirtyPatchSha256=hashlib.sha256(b'').hexdigest())
        changed = dict(exit=1, dirtyPatchSha256='proposed')
        study.validate_native_records(clean, changed, 'proposed')
        for replacement in [dict(dirtyPatchSha256='rewritten'), dict(matched=False), dict(exit=1)]:
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                study.validate_native_records(dict(clean, **replacement), changed, 'proposed')

    def test_diff_scope_uses_real_module_and_rejects_experiment_id(self):
        patch = 'diff --git a/module/src/A.kt b/module/src/A.kt\n--- a/module/src/A.kt\n+++ b/module/src/A.kt\n@@ -1 +1 @@\n-fun f() = 1\n+fun f() = 2\n'
        self.assertEqual(set(study.previous.diff.changed_lines(patch, 'module')), {'src/A.kt'})
        with self.assertRaises(ValueError): study.previous.diff.changed_lines(patch, 'experiment-case-id')


if __name__ == '__main__': unittest.main()
