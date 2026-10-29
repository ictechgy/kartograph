import copy
import unittest

from structured import validate_result


def valid_result():
    return dict(type='result', subtype='success', is_error=False, result='Unstructured prose is not the answer.',
                structured_output=dict(targets=[dict(file='src/Test.kt', symbol='Test.`checks work`()',
                    kind='test', reason='Existing regression test', evidence='src/Test.kt:10')],
                    unknowns=[], summary='Review the existing test.'))


class StructuredResultTest(unittest.TestCase):
    def test_returns_structured_object_and_preserves_original(self):
        result = valid_result(); original = copy.deepcopy(result)
        document, error = validate_result(result)
        self.assertIsNone(error)
        self.assertEqual(document, result['structured_output'])
        self.assertEqual(result, original)

    def test_valid_raw_json_does_not_replace_missing_structured_output(self):
        result = valid_result(); del result['structured_output']
        result['result'] = '{"targets":[],"unknowns":[],"summary":"complete"}'
        self.assertEqual(validate_result(result), (None, 'missing-structured-output'))

    def test_missing_brace_is_not_repaired(self):
        result = valid_result(); del result['structured_output']
        result['result'] = '{"targets":[],"unknowns":[],"summary":"truncated"'
        self.assertEqual(validate_result(result), (None, 'missing-structured-output'))

    def test_provider_failure_rejects_even_a_well_shaped_object(self):
        for subtype in ['error_max_structured_output_retries', 'error_max_budget_usd', 'error_max_turns']:
            with self.subTest(subtype=subtype):
                result = valid_result(); result.update(subtype=subtype, is_error=True)
                self.assertEqual(validate_result(result), (None, 'provider-result-failure'))

    def test_missing_or_ambiguous_provider_success_is_not_accepted(self):
        for result in [None, {}, {'subtype':'success'}, dict(valid_result(), is_error=True)]:
            self.assertIsNotNone(validate_result(result)[1])

    def test_null_string_and_array_are_not_answer_objects(self):
        for value in [None, '[]', [], 1, False]:
            result = valid_result(); result['structured_output'] = value
            self.assertEqual(validate_result(result), (None, 'schema-violation'))

    def test_required_fields_types_and_extra_fields_are_checked(self):
        base = valid_result()['structured_output']
        invalid = [dict(base, extra='ignored'), dict(base, targets={}), dict(base, unknowns=[1]),
                   dict(base, summary=None), {key:value for key,value in base.items() if key!='summary'}]
        for value in invalid:
            result=valid_result();result['structured_output']=value
            self.assertEqual(validate_result(result), (None, 'schema-violation'))

    def test_target_shape_and_enum_are_checked_without_coercion(self):
        target=valid_result()['structured_output']['targets'][0]
        invalid=[dict(target, kind='Test'), dict(target, file=None), dict(target, score=1),
                 {key:value for key,value in target.items() if key!='evidence'}]
        for value in invalid:
            result=valid_result();result['structured_output']['targets']=[value]
            self.assertEqual(validate_result(result), (None, 'schema-violation'))

    def test_prediction_limit_is_not_silently_truncated(self):
        result=valid_result();target=result['structured_output']['targets'][0]
        result['structured_output']['targets']=[target.copy() for _ in range(64)]
        self.assertIsNone(validate_result(result)[1])
        result['structured_output']['targets'].append(target.copy())
        self.assertEqual(validate_result(result), (None, 'schema-violation'))

    def test_empty_predictions_are_valid_and_not_fabricated(self):
        result=valid_result();result['structured_output']['targets']=[]
        self.assertEqual(validate_result(result), (result['structured_output'],None))


if __name__ == '__main__':
    unittest.main()
