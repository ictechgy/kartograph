"""동명 overload·중복·compiler 전용 선언이 평가 결과를 왜곡하지 않는지 검사한다."""
import json
import unittest

from grade import Inventory, grade


class IdentityGradeTest(unittest.TestCase):
    def inventory(self):
        return Inventory(dict(declarations=[
            dict(sourceAddressable=True, id='method:p/Writer#write(I)V', file='src/Writer.java', aliases=['p.Writer.write(int)', 'write(int)', 'write']),
            dict(sourceAddressable=True, id='method:p/Writer#write(Ljava/lang/String;)V', file='src/Writer.java', aliases=['p.Writer.write(String)', 'write(String)', 'write']),
            dict(sourceAddressable=True, id='method:p/Other#write(I)V', file='src/Other.java', aliases=['p.Other.write(int)']),
            dict(sourceAddressable=True, id='method:p/Writer#helper()V', file='src/Writer.java', aliases=['p.Writer.helper()']),
            dict(sourceAddressable=False, id='method:p/Writer#access$0()V', file='src/Writer.java', aliases=[]),
            dict(sourceAddressable=True, id='method:p/Kt#has a space()V', file='src/Kt.kt', aliases=['p.Kt.`has a space`()']),
            dict(sourceAddressable=True, id='method:p/Kt#ext(Ljava/lang/String;I)V', file='src/Kt.kt', aliases=['p.Kt.String.ext(Int)']),
            dict(sourceAddressable=True, id='field:p/Kt#value:I', file='src/Kt.kt', aliases=['p.Kt.value']),
        ], targets=['method:p/Writer#write(I)V', 'method:p/Writer#write(Ljava/lang/String;)V',
                    'method:p/Writer#access$0()V'], changed=['method:p/Writer#helper()V']))

    def score(self, *items):
        return grade(json.dumps(dict(targets=[dict(file=f, symbol=s) for f, s in items], unknowns=[])), self.inventory())

    def test_unqualified_overload_is_ambiguous_and_does_not_credit_both(self):
        r = self.score(('src/Writer.java', 'write'))
        self.assertEqual(r['primaryRecall'], 0)
        self.assertEqual(r['ambiguousPredictions'], 1)
        self.assertEqual(len(r['predictions'][0]['candidates']), 2)

    def test_source_signature_and_usr_get_equal_credit(self):
        source = self.score(('src/Writer.java', 'p.Writer.write(int)'))
        usr = self.score(('src/Writer.java', 'method:p/Writer#write(I)V'))
        self.assertEqual(source['covered'], usr['covered'])
        self.assertEqual(source['primaryRecall'], .5)

    def test_source_punctuation_whitespace_has_equal_credit(self):
        r = self.score(('src/Writer.java', 'p.Writer.write( int )'))
        self.assertEqual(r['primaryRecall'], .5)

    def test_backtick_name_whitespace_is_not_erased(self):
        r = self.score(('src/Kt.kt', 'p.Kt.`has  a space`()'))
        self.assertEqual(r['uniqueResolvedPredictions'], 0)

    def test_signature_does_not_fall_back_to_same_name(self):
        r = self.score(('src/Writer.java', 'p.Writer.write(double)'))
        self.assertEqual(r['covered'], [])

    def test_duplicate_source_and_usr_do_not_inflate_credit(self):
        r = self.score(('src/Writer.java', 'write(int)'), ('src/Writer.java', 'method:p/Writer#write(I)V'))
        self.assertEqual(r['uniqueResolvedPredictions'], 1)
        self.assertEqual(r['duplicatePredictions'], 1)
        self.assertEqual(r['primaryRecall'], .5)

    def test_wrong_file_cannot_match_exact_usr(self):
        r = self.score(('src/Other.java', 'method:p/Writer#write(I)V'))
        self.assertEqual(r['covered'], [])

    def test_ambiguity_is_checked_against_all_declarations_not_only_oracle(self):
        r = Inventory(dict(declarations=[
            dict(sourceAddressable=True, id='method:p/C#f(I)V', file='C.java', aliases=['f', 'f(int)']),
            dict(sourceAddressable=True, id='method:p/C#f(J)V', file='C.java', aliases=['f', 'f(long)']),
        ], targets=['method:p/C#f(I)V']))
        result = grade(json.dumps(dict(targets=[dict(file='C.java', symbol='f')], unknowns=[])), r)
        self.assertEqual(result['ambiguousPredictions'], 1)
        self.assertEqual(result['covered'], [])

    def test_compiler_only_targets_are_separate_from_primary_recall(self):
        r = self.score(('src/Writer.java', 'method:p/Writer#access$0()V'))
        self.assertEqual(r['primaryRecall'], 0)
        self.assertAlmostEqual(r['allDeclarationRecall'], 1 / 3)

    def test_primary_oracle_requires_a_unique_source_alias(self):
        with self.assertRaises(ValueError):
            Inventory(dict(declarations=[
                dict(sourceAddressable=True, id='method:C#f(I)V', file='C.java', aliases=['f']),
                dict(sourceAddressable=True, id='method:C#f(J)V', file='C.java', aliases=['f']),
            ], targets=['method:C#f(I)V']))

    def test_invented_path_is_not_accepted_as_file_level_evidence(self):
        r = self.score(('other/Writer.java', 'Writer (file-level)'))
        self.assertEqual(r['fileLevelPredictions'], 0)
        self.assertEqual(r['predictions'][0]['status'], 'file-outside-inventory')

    def test_additional_changed_declaration_is_unadjudicated_not_false_positive(self):
        r = self.score(('src/Writer.java', 'p.Writer.helper()'))
        self.assertEqual(r['predictions'][0]['status'], 'outside-oracle-unadjudicated')
        self.assertTrue(r['predictions'][0]['changedDeclaration'])

    def test_kotlin_backtick_extension_and_property_aliases(self):
        r = self.score(('src/Kt.kt', 'p.Kt.`has a space`()'), ('src/Kt.kt', 'p.Kt.String.ext(Int)'), ('src/Kt.kt', 'p.Kt.value'))
        self.assertEqual(r['uniqueResolvedPredictions'], 3)
        self.assertEqual(r['ambiguousPredictions'], 0)

    def test_blank_symbol_invalidates_response_instead_of_inflating_file_level(self):
        r = self.score(('src/Writer.java', '   '))
        self.assertFalse(r['valid'])
        self.assertEqual(r['fileLevelPredictions'], 0)

    def test_one_unmapped_overload_cannot_silently_shrink_primary_denominator(self):
        with self.assertRaises(ValueError):
            Inventory(dict(declarations=[
                dict(sourceAddressable=True, id='method:C#f(I)V', file='C.java', aliases=['f', 'f(int)']),
                dict(sourceAddressable=True, id='method:C#f(J)V', file='C.java', aliases=['f']),
            ], targets=['method:C#f(I)V', 'method:C#f(J)V']))

    def test_file_level_is_explicit(self):
        self.assertEqual(self.score(('src/Writer.java', 'Writer (file-level)'))['fileLevelPredictions'], 1)

    def test_invalid_output_remains_zero_recall_failure(self):
        for raw in ['', 'Explanation before {"targets": [], "unknowns": []}', '{"targets": []}']:
            r = grade(raw, self.inventory())
            self.assertFalse(r['valid'])
            self.assertEqual(r['primaryRecall'], 0)
            self.assertIsNone(r['oracleAgreement'])

    def test_single_json_fence_is_accepted_but_preface_is_not(self):
        answer = json.dumps(dict(targets=[dict(file='src/Writer.java', symbol='write(int)')], unknowns=[]))
        fenced = '```json\n' + answer + '\n```'
        self.assertEqual(grade(fenced, self.inventory())['primaryRecall'], .5)
        self.assertFalse(grade('Explanation\n' + fenced, self.inventory())['valid'])

    def test_oracle_over_response_limit_is_rejected_before_trials(self):
        declarations = [dict(sourceAddressable=True, id=f'method:C#f{i}()V', file='C.java', aliases=[f'f{i}()']) for i in range(65)]
        with self.assertRaises(ValueError):
            Inventory(dict(declarations=declarations, targets=[d['id'] for d in declarations]))

    def test_output_over_response_limit_is_invalid(self):
        text = json.dumps(dict(targets=[dict(file='src/Writer.java', symbol='write(int)')] * 65, unknowns=[]))
        self.assertEqual(grade(text, self.inventory())['error'], 'too-many-targets')

    def test_duplicate_inventory_id_is_rejected(self):
        row = dict(sourceAddressable=True, id='method:C#f()V', file='C.java', aliases=['f()'])
        with self.assertRaises(ValueError):
            Inventory(dict(declarations=[row, row], targets=[row['id']]))


if __name__ == '__main__':
    unittest.main()
