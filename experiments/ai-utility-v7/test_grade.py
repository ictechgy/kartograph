import gzip
import importlib.util
import json
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('v7_grade_tests', HERE / 'grade.py')
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


def inventory(aliases):
    return dict(declarations=[dict(id='method:p/C#f(Lkotlin/jvm/functions/Function2;)V',
                file='C.kt', aliases=aliases, sourceAddressable=True)],
                targets=['method:p/C#f(Lkotlin/jvm/functions/Function2;)V'])


class SourceEquivalenceTest(unittest.TestCase):
    def test_optional_function_parameter_names_do_not_change_identity(self):
        data = inventory(['C.f((offset: Int, errorMessage: String) -> Unit)'])
        before = json.dumps(data)
        oracle = study.Inventory(data)
        self.assertEqual(oracle.resolve(dict(file='C.kt', symbol='C.f((Int, String) -> Unit)'))[0], 'resolved')
        self.assertEqual(json.dumps(data), before)

    def test_nested_nullable_receiver_and_suspend_function_types(self):
        named = 'C.f(suspend String.(first: List<(item: Int) -> String>, next: ((flag: Boolean) -> Unit)?) -> Unit)'
        bare = 'C.f(suspend String.(List<(Int)->String>, ((Boolean)->Unit)?) -> Unit)'
        self.assertEqual(study.normalize_selector(named), study.normalize_selector(bare))

    def test_backtick_parameter_label_is_optional_but_method_name_is_literal(self):
        a = 'C.`accept(x: Int) -> Unit`((`x y`: Int) -> Unit)'
        b = 'C.`accept(x: Int) -> Unit`((Int) -> Unit)'
        self.assertEqual(study.normalize_selector(a), study.normalize_selector(b))
        self.assertNotEqual(study.normalize_selector(a), study.normalize_selector('C.`accept(Int) -> Unit`((Int)->Unit)'))

    def test_type_order_nullability_and_arity_are_not_erased(self):
        oracle = study.Inventory(inventory(['C.f((offset: Int, errorMessage: String) -> Unit)']))
        for selector in ['C.f((String, Int)->Unit)', 'C.f((Int?, String)->Unit)',
                         'C.f((Int)->Unit)', 'C.f((Int, String)->Boolean)',
                         'C.f((Int, String)->Unit)', 'C.f((x: Int, y: String)->Unit)']:
            status, _ = oracle.resolve(dict(file='C.kt', symbol=selector))
            self.assertEqual(status, 'resolved' if selector in ['C.f((Int, String)->Unit)', 'C.f((x: Int, y: String)->Unit)'] else 'unresolved')

    def test_usr_file_scope_and_outer_parameter_names_keep_existing_rules(self):
        oracle = study.Inventory(inventory(['C.f((offset: Int, errorMessage: String) -> Unit)']))
        identity = next(iter(oracle.oracle))
        self.assertEqual(study.normalize_selector(identity), identity)
        self.assertEqual(oracle.resolve(dict(file='other.kt', symbol=identity))[0], 'file-outside-inventory')
        self.assertEqual(oracle.resolve(dict(file='C.kt', symbol='C.f(callback: (Int, String)->Unit)'))[0], 'unresolved')

    def test_equivalent_overload_collision_is_not_silently_chosen(self):
        data = inventory(['C.f((a: Int, b: String)->Unit)', 'C.unique()'])
        row = dict(data['declarations'][0], id='method:p/C#g()V', aliases=['C.f((x: Int, y: String)->Unit)'])
        data['declarations'].append(row)
        status, candidates = study.Inventory(data).resolve(dict(file='C.kt', symbol='C.f((Int,String)->Unit)'))
        self.assertEqual(status, 'ambiguous')
        self.assertEqual(len(candidates), 2)

    def test_v6_observed_caller_forms_resolve_without_changing_frozen_oracle(self):
        path = HERE.parent / 'ai-utility-v6/oracles/pinterest_ktlint-2554.json.gz'
        raw = path.read_bytes()
        data = json.loads(gzip.decompress(raw))
        expected = 'method:com/pinterest/ktlint/ruleset/standard/rules/SpacingAroundCurlyRule#beforeVisitChildNodes(Lorg/jetbrains/kotlin/com/intellij/lang/ASTNode;ZLkotlin/jvm/functions/Function3;)V'
        row = next(d for d in data['declarations'] if d['id'] == expected)
        for prefix in ['SpacingAroundCurlyRule', 'com.pinterest.ktlint.ruleset.standard.rules.SpacingAroundCurlyRule']:
            prediction = dict(file=row['file'], symbol=prefix + '.beforeVisitChildNodes(ASTNode, Boolean, (Int, String, Boolean) -> Unit)')
            self.assertEqual(study.base.Inventory(data).resolve(prediction)[0], 'unresolved')
            self.assertEqual(study.Inventory(data).resolve(prediction), ('resolved', [expected]))
        self.assertEqual(path.read_bytes(), raw)


class TypedScoringTest(unittest.TestCase):
    def setUp(self):
        self.data = dict(declarations=[dict(id='method:p/C#' + name + '()V',
            file=('src/test/T.kt' if name != 'caller' else 'src/main/C.kt'),
            aliases=['C.' + name + '()'], sourceAddressable=True) for name in ['caller', 'positive', 'negative']],
            targets=['method:p/C#caller()V', 'method:p/C#positive()V'],
            direct=['method:p/C#caller()V'], indirect=[], behaviorPositive=['method:p/C#positive()V'],
            behaviorNegative=['method:p/C#negative()V'], executedTestMethods=['method:p/C#positive()V', 'method:p/C#negative()V'])
        self.oracle = study.Inventory(self.data)

    def answer(self, pairs):
        return json.dumps(dict(targets=[dict(file='src/main/C.kt' if name == 'caller' else 'src/test/T.kt',
            symbol='C.' + name + '()', kind=kind, reason='reason', evidence='source') for name, kind in pairs],
            unknowns=[], summary='review'))

    def test_wrong_kind_does_not_receive_either_coverage(self):
        result = study.grade(self.answer([('caller', 'test'), ('positive', 'caller')]), self.oracle)
        self.assertEqual(result['callRecall'], 0)
        self.assertEqual(result['behaviorRecall'], 0)
        self.assertEqual(result['kindMismatches'], 2)

    def test_same_suite_negative_is_counted_and_not_given_credit(self):
        result = study.grade(self.answer([('caller', 'caller'), ('positive', 'test'), ('negative', 'test')]), self.oracle)
        self.assertEqual(result['callRecall'], 1)
        self.assertEqual(result['behaviorRecall'], 1)
        self.assertEqual(result['negativeControlPredictions'], ['method:p/C#negative()V'])
        self.assertEqual(result['observedTestPrecision'], 0.5)

    def test_duplicate_cannot_repair_initial_wrong_kind(self):
        result = study.grade(self.answer([('positive', 'caller'), ('positive', 'test')]), self.oracle)
        self.assertEqual(result['behaviorRecall'], 0)
        self.assertEqual(result['duplicatePredictions'], 1)

    def test_invalid_output_stays_in_denominator_with_zero_coverage(self):
        result = study.grade('invalid', self.oracle)
        self.assertFalse(result['valid'])
        self.assertEqual(result['callRecall'], 0)
        self.assertEqual(result['behaviorRecall'], 0)

    def test_negative_controls_require_unique_source_aliases_too(self):
        self.data['declarations'][1]['aliases'].append('C.uniquePositive()')
        self.data['declarations'][2]['aliases'] = ['C.positive()']
        with self.assertRaisesRegex(ValueError, 'negative control'):
            study.Inventory(self.data)


identity_spec = importlib.util.spec_from_file_location('v5_identity_regressions', HERE.parent / 'ai-utility-v5/test_grade.py')
identity_tests = importlib.util.module_from_spec(identity_spec)
identity_spec.loader.exec_module(identity_tests)
# 기존 선언 대응 계약을 새 Inventory에 적용하되 v5의 optional kind 응답은 identity 검사에만 쓴다.
identity_tests.Inventory = study.Inventory
identity_tests.grade = study.base.grade
IdentityGradeTest = identity_tests.IdentityGradeTest


if __name__ == '__main__':
    unittest.main()
