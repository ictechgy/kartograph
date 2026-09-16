"""실행 전에 정한 기준이 새 테스트나 다른 overload에 점수를 주지 않는지 검증한다."""
import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('preflight_grade',ROOT/'experiments/preflight-evaluation/grade.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class PreflightGradeTest(unittest.TestCase):
    def test_exact_existing_method_and_file_level_have_distinct_credit(self):
        target={'id':'method:p/WriterTest#testDoubles()V','kind':'test','file':'src/test/WriterTest.java','name':'testDoubles'}
        self.assertEqual(1,module.target_credit({'file':target['file'],'symbol':target['id']},target))
        self.assertEqual(1,module.target_credit({'file':target['file']+':42','symbol':'WriterTest.testDoubles'},target))
        self.assertEqual(.5,module.target_credit({'file':target['file'],'symbol':'WriterTest'},target))
        self.assertEqual(0,module.target_credit({'file':target['file'],'symbol':'testDoublesWhenLenient'},target))
        self.assertEqual(0,module.target_credit({'file':'other/WriterTest.java','symbol':target['id']},target))

    def test_wrong_overload_does_not_get_credit_from_reason_keyword(self):
        target={'id':'method:p/TreeWriter#value(D)Lp/Writer;','kind':'override','file':'TreeWriter.java','name':'value'}
        self.assertEqual(1,module.target_credit({'file':target['file'],'symbol':'TreeWriter.value(double)'},target))
        self.assertEqual(0,module.target_credit({'file':target['file'],'symbol':'TreeWriter.value(Number)','reason':'Related to double serialization'},target))

    def test_class_level_annotation_preserves_partial_credit_without_matching_a_method(self):
        target={'id':'method:p/Factory#build()V','kind':'registration','file':'Factory.kt','name':'build'}
        self.assertEqual(.5,module.target_credit({'file':'Factory.kt','symbol':'Factory (registration site, line 18)'},target))
        self.assertEqual(.5,module.target_credit({'file':'Factory.kt','symbol':'p.Factory [file-level only]'},target))
        self.assertEqual(0,module.target_credit({'file':'Factory.kt','symbol':'Factory.other (registration site)'},target))

    def test_oversized_or_unstructured_answers_are_not_inferred(self):
        self.assertIsNone(module.parse_answer({'result':'Review WriterTest'}))
        self.assertIsNone(module.parse_answer({'result':'{"targets":[],"unknowns":[]}', 'is_error':True}))
        self.assertEqual([],module.parse_answer({'result':'```json\n{"targets":[],"unknowns":[]}\n```'})['targets'])

    def test_long_kotlin_method_does_not_match_its_prefix_anchor(self):
        target={'id':'method:p/Tests#keeps body separate()V','kind':'test','file':'Tests.kt','name':'keeps body separate'}
        self.assertEqual(0,module.target_credit({'file':'Tests.kt','symbol':'Tests.`keeps body separate, when configured`'},target))
        self.assertEqual(0,module.target_credit({'file':'Tests.kt','symbol':'Tests.`keeps body separate (lenient)`'},target))
        self.assertEqual(1,module.target_credit({'file':'Tests.kt','symbol':'Tests.`keeps body separate`'},target))
        self.assertEqual(1,module.target_credit({'file':'Tests.kt','symbol':'Tests.`several cases`.`keeps body separate`'},target))

    def test_method_word_inside_class_annotation_only_gets_class_credit(self):
        target={'id':'method:p/Tests#test()V','kind':'test','file':'Tests.java','name':'test'}
        self.assertEqual(.5,module.target_credit({'file':'Tests.java','symbol':'Tests (test class)'},target))
        self.assertEqual(1,module.target_credit({'file':'Tests.java','symbol':'Tests.test()'},target))
        self.assertEqual(0,module.target_credit({'file':'Tests.java','symbol':'method:p/Tests#test(I)V'},target))

    def test_exact_method_lists_and_trailing_callsite_details_keep_credit(self):
        for name, symbol in [('getRuleProviders', 'Factory.getRuleProviders (RuleProvider { Rule() })'),
                             ('acceptAny', 'Factory.acceptAny/… line 450'),
                             ('test1', 'Factory.test / test1')]:
            target={'id':'method:p/Factory#'+name+'()V','kind':'caller','file':'Factory.java','name':name}
            self.assertEqual(1,module.target_credit({'file':'Factory.java','symbol':symbol},target),symbol)

    def test_invalid_answer_reasons_separate_syntax_shape_and_limit(self):
        self.assertEqual('invalid-json',module.decode_answer({'result':None})[1])
        self.assertEqual('invalid-json',module.decode_answer({'result':'not JSON'})[1])
        self.assertEqual('invalid-answer-shape',module.decode_answer({'result':'{}'})[1])
        import json
        self.assertEqual('too-many-targets',module.decode_answer({'result':json.dumps({'targets':[{}]*13,'unknowns':[]})})[1])
