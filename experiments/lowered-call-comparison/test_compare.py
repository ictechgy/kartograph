"""합성 입력의 gate/parser 음성 대조군이며 실제 PSI/bytecode 근거가 아니다."""
import copy
import json
from pathlib import Path
import unittest
import compare

HERE = Path(__file__).resolve().parent


class FixtureComparisonTest(unittest.TestCase):
    def setUp(self):
        self.expected = json.loads((HERE / 'source-call-expectations.json').read_text())
        self.lowering = json.loads((HERE / 'lowering-expectations.json').read_text())
        self.rows = [row['expectedAdapterRow'] for row in self.expected['internalCallSites'] + self.expected['externalBoundarySites']]
        self.methods = {row['usr']: {'calls': [], 'lambdaImplementationHandles': [], 'synthetic': False}
                        for row in self.expected['declarations']}
        self.edges = []
        self.nodes = [{'usr': usr} for usr in self.methods]
        def call(source, target):
            self.methods.setdefault(source, {'calls': [], 'lambdaImplementationHandles': [], 'synthetic': False})['calls'].append(target)
            self.edges.append({'kind': 'call', 'source': source, 'target': target})
        for row in self.lowering['preservedDirectSourcePairs']:
            call(row['source'], row['target'])
        for row in self.lowering['transformedSites']:
            if row['site'] == 'k-inline-call':
                call(row['rawCaller'], row['expandedBodyTarget'])
            elif row['site'] == 'k-default-call':
                call(row['rawCaller'], row['expectedBridge'])
                call(row['expectedBridge'], row['sourceTarget'])
                self.nodes.append({'usr': row['expectedBridge'], 'kotlinDefaultBridge': {
                    **row['existingAssociationExpected'], 'basis': 'kotlinMetadataAndBytecode'}})
            elif row['site'] == 'k-default-expression':
                call(row['expectedRawOwner'], row['target'])
            else:
                caller = row['lexicalSourceCaller']
                body = caller.split('#')[0] + '#testOnlyGeneratedBody(I)I'
                call(body, row['target'])
                self.methods[caller]['lambdaImplementationHandles'].append(body)
                self.edges.append({'kind': 'call', 'source': caller, 'target': body})
                self.nodes.append({'usr': body})
        self.graph = {'nodes': self.nodes, 'edges': self.edges}

    def readback(self):
        return compare.readback(self.graph, self.methods, self.expected, self.lowering)

    def test_exact_source_rows_pass(self):
        self.assertTrue(compare.qualify_source(self.rows, self.expected)['qualified'])

    def test_missing_duplicate_wrong_target_unresolved_or_extra_each_fail(self):
        variants = [self.rows[:-1], self.rows + [self.rows[0]]]
        for field, value in [('target', None), ('resolved', False), ('callStartUtf16', -1)]:
            changed = copy.deepcopy(self.rows)
            changed[0][field] = value
            variants.append(changed)
        variants.append(self.rows + [{'kind': 'constructor'}])
        for rows in variants:
            self.assertFalse(compare.qualify_source(rows, self.expected)['qualified'])

    def test_all_fixture_relations_require_both_engines(self):
        result = self.readback()
        self.assertTrue(result['qualified'], result)

    def test_missing_product_edge_is_unclassified(self):
        self.edges.pop(0)
        result = self.readback()
        self.assertFalse(result['qualified'])
        self.assertEqual(result['siteObservations'][0]['classification'], 'unclassified')

    def test_wrong_default_association_is_unclassified(self):
        bridge = next(row for row in self.nodes if 'kotlinDefaultBridge' in row)
        bridge['kotlinDefaultBridge']['target'] = 'wrong'
        self.assertFalse(self.readback()['qualified'])

    def test_missing_bytecode_evidence_is_unclassified(self):
        next(row for row in self.methods.values() if row['calls'])['calls'].clear()
        self.assertFalse(self.readback()['qualified'])

    def test_no_bootstrap_handle_is_unclassified(self):
        for facts in self.methods.values():
            facts['lambdaImplementationHandles'].clear()
        self.assertFalse(self.readback()['qualified'])

    def test_unused_incoming_raw_call_fails(self):
        unused = next(row['usr'] for row in self.expected['declarations'] if row['id'] == 'K.unused')
        self.edges.append({'kind': 'call', 'source': self.nodes[0]['usr'], 'target': unused})
        self.assertFalse(self.readback()['qualified'])

    def test_extra_wrong_authored_product_call_fails(self):
        ids = [row['usr'] for row in self.expected['declarations']]
        self.edges.append({'kind': 'call', 'source': ids[0], 'target': ids[1]})
        self.assertFalse(self.readback()['qualified'])

    def test_javap_parser_recovers_instruction_and_actual_bootstrap_target(self):
        # 이름은 fixture의 실제 compiler 출력에서 가져온 것이 아니다.
        text = '''Classfile /test/Fake.class
  this_class: #1 // lowered/Fake
{
  public int caller();
    descriptor: ()I
    flags: (0x0001) ACC_PUBLIC
    Code:
         0: invokedynamic #9,  0 // InvokeDynamic #0:applyAsInt:()Ljava/util/function/IntUnaryOperator;
         5: invokestatic #12 // Method lowered/LoweredJava.acceptKotlinLambda:(Ljava/util/function/IntUnaryOperator;)I
         8: ireturn
  private static int generated(int);
    descriptor: (I)I
    flags: (0x100a) ACC_PRIVATE, ACC_STATIC, ACC_SYNTHETIC
    Code:
         0: invokestatic #13 // Method lowered/LoweredJava.lambdaLeaf:(I)I
         3: ireturn
}
BootstrapMethods:
  0: #10 REF_invokeStatic java/lang/invoke/LambdaMetafactory.metafactory:()V
    Method arguments:
      #11 (I)I
      #12 REF_invokeStatic lowered/Fake.generated:(I)I
      #11 (I)I
'''
        result = compare.read_javap(text)
        caller = result['method:lowered/Fake#caller()I']
        self.assertEqual(caller['lambdaImplementationHandles'], ['method:lowered/Fake#generated(I)I'])
        self.assertEqual(caller['calls'], ['method:lowered/LoweredJava#acceptKotlinLambda(Ljava/util/function/IntUnaryOperator;)I'])
        self.assertTrue(result['method:lowered/Fake#generated(I)I']['synthetic'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
