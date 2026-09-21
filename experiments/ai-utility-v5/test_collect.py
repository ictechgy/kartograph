import unittest
from pathlib import Path
from collect import read_javap, matches_source, name_after_type


class IndependentDeclarationsTest(unittest.TestCase):
    def test_generic_method_type_parameters_do_not_become_part_of_name(self):
        self.assertEqual(name_after_type('public static final <T extends java.lang.Object> java.util.List<T> values'), 'values')

    def function(self):
        return dict(file='src/Rule.kt', name='visit', owners=['Rule'], types=['Int', 'String'],
                    namedParameters=['n: Int', 'text: String'], receiver=None, local=False,
                    startLine=10, endLine=20, suspend=False)

    def test_non_synthetic_lambda_is_not_the_source_function(self):
        member = dict(name='visit$lambda$0', descriptor='(ILjava/lang/String;)V', lines=[12])
        self.assertFalse(matches_source(member, self.function(), ['Rule'], {'module'}, 'src/Rule.kt'))

    def test_only_known_module_mangling_matches(self):
        member = dict(name='visit$module', descriptor='(ILjava/lang/String;)V', lines=[12])
        self.assertTrue(matches_source(member, self.function(), ['Rule'], {'module'}, 'src/Rule.kt'))
        self.assertFalse(matches_source(member, self.function(), ['Rule'], {'other'}, 'src/Rule.kt'))

    def test_generated_short_overload_cannot_share_full_source_signature(self):
        member = dict(name='visit', descriptor='(I)V', lines=[12])
        self.assertFalse(matches_source(member, self.function(), ['Rule'], {'module'}, 'src/Rule.kt'))

    def test_javap_method_name_with_parentheses_and_exact_call_descriptor(self):
        path = Path('/virtual/Rule.class').resolve()
        text = f'''Classfile {path}
public final class p.Rule
{{
  public final void Given a call f(x) works();
    descriptor: ()V
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
    Code:
         1: invokevirtual #12 // Method p/Target.call:(I)V
      LineNumberTable:
        line 12: 0
}}
SourceFile: "Rule.kt"
'''
        cls = read_javap(text, {path: 'p/Rule'})[0]
        member = cls['members'][0]
        self.assertEqual(member['id'], 'method:p/Rule#Given a call f(x) works()V')
        self.assertEqual(member['calls'], ['method:p/Target#call(I)V'])
        self.assertEqual(cls['source'], 'Rule.kt')


if __name__ == '__main__':
    unittest.main()
