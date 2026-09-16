"""재현 입력의 선언 ID를 문자열 구분자로 잘못 자르지 않도록 검증한다."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("impact_replay_support_tested", Path(__file__).resolve().parents[1] / "impact_replay_support.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReplaySupportTest(unittest.TestCase):
    def test_owner_validation_preserves_hash_in_kotlin_class_and_member_names(self):
        ids = ["class:p/Spec$case - #123", "class:p/Spec$case - #456",
               "method:p/Spec$case - #123#method with # in name()V",
               "field:p/Spec$case - #456#field:I"]
        for document in [
            {"version": 1, "graph": {"nodes": [{"usr": value} for value in ids]}},
            {"version": 2, "graph": {"stringTable": ids, "nodes": [[i] for i in range(len(ids))]}},
        ]:
            self.assertEqual({"p/Spec$case - #123", "p/Spec$case - #456"}, MODULE.graph_class_owners(document))


if __name__ == "__main__":
    unittest.main()
