import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('v8_collect_tests', Path(__file__).with_name('collect.py'))
study = importlib.util.module_from_spec(spec)
spec.loader.exec_module(study)


class SourceIdentityTest(unittest.TestCase):
    def test_javap_declared_method_whitespace_is_not_trimmed(self):
        path = Path('/tmp/C.class').resolve()
        text = '''Classfile /tmp/C.class
{
  public final void trailing ();
    descriptor: ()V
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
  public final void  leading();
    descriptor: ()V
    flags: (0x0011) ACC_PUBLIC, ACC_FINAL
}
SourceFile: "C.kt"
'''
        rows = study.read_javap(text, {path: 'p/C'})[0]['members']
        self.assertEqual([row['id'] for row in rows], ['method:p/C#trailing ()V', 'method:p/C# leading()V'])

    def test_non_identifier_names_require_backticks_and_keep_spaces(self):
        fun = dict(owners=['C'], name='trailing ', package='p', receiver=None, types=[], namedParameters=[])
        aliases = study.source_aliases(fun)
        self.assertIn('p.C.`trailing `()', aliases)
        self.assertNotIn('p.C.trailing ()', aliases)
        self.assertTrue(all(value == value.strip() for value in aliases))


legacy_spec = importlib.util.spec_from_file_location('v5_collector_regressions', Path(__file__).resolve().parents[1] / 'ai-utility-v5/test_collect.py')
legacy = importlib.util.module_from_spec(legacy_spec)
legacy_spec.loader.exec_module(legacy)
legacy.read_javap, legacy.matches_source, legacy.name_after_type = study.read_javap, study.matches_source, study.name_after_type
IndependentDeclarationsTest = legacy.IndependentDeclarationsTest

if __name__ == '__main__': unittest.main()
