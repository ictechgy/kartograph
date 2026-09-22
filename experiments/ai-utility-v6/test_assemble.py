import unittest
from assemble import changed_lines, intersects_function


class DiffBoundaryTest(unittest.TestCase):
    def test_headers_do_not_create_changes_using_previous_files_line_number(self):
        diff='''diff --git a/mod/A.kt b/mod/A.kt
--- a/mod/A.kt
+++ b/mod/A.kt
@@ -10,3 +10,3 @@
 fun a() {
-  old()
+  new()
 }
diff --git a/mod/B.kt b/mod/B.kt
--- a/mod/B.kt
+++ b/mod/B.kt
@@ -100 +100 @@
-old()
+new()
'''
        actual=changed_lines(diff,'mod')
        self.assertEqual(actual['B.kt']['insertedBefore'],{101})
        self.assertFalse(intersects_function(dict(startLine=12,endLine=14),actual['B.kt']))

    def test_new_file_does_not_contaminate_previous_existing_file(self):
        diff='''diff --git a/mod/A.kt b/mod/A.kt
--- a/mod/A.kt
+++ b/mod/A.kt
@@ -10 +10 @@
-old()
+new()
diff --git a/mod/New.kt b/mod/New.kt
new file mode 100644
--- /dev/null
+++ b/mod/New.kt
@@ -0,0 +1,2 @@
+new class
+new function
'''
        actual=changed_lines(diff,'mod')
        self.assertEqual(actual['A.kt']['insertedBefore'],{11})
        self.assertNotIn('New.kt',actual)


if __name__=='__main__':unittest.main()
