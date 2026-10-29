import unittest
from assemble import changed_lines, intersects_function


class ChangedDeclarationTest(unittest.TestCase):
    def test_deleted_signature_first_line_is_changed(self):
        changes = changed_lines('--- a/mod/A.kt\n@@ -1 +1 @@\n-fun f(x: Int) = x\n+fun f(x: Long) = x\n', 'mod')
        self.assertTrue(intersects_function(dict(startLine=1, endLine=1), changes['A.kt']))

    def test_new_declaration_before_an_existing_one_does_not_change_it(self):
        changes = changed_lines('--- a/mod/A.kt\n@@ -1 +1,2 @@\n+fun added() = 1\n fun existing() = 2\n', 'mod')
        self.assertFalse(intersects_function(dict(startLine=1, endLine=1), changes['A.kt']))


if __name__ == '__main__':
    unittest.main()
