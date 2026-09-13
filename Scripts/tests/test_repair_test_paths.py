"""실행기·controller·grader의 테스트 경계가 일반 production 이름을 거부하지 않는지 검증한다."""
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class RepairTestPathTest(unittest.TestCase):
    def test_production_words_and_real_test_conventions(self):
        launcher = load("repair_path_launcher", "evaluate-impact-repair.py")
        grader = load("repair_path_grader", "grade-impact-repair.py")
        session = load("repair_path_session", "impact_repair_session.py")
        classifiers = [launcher._is_test_path, grader._is_test_path, session.RepairSession._is_test_path]
        production = ["src/main/Contest.kt", "src/main/Latest.java", "src/main/fastest.kt", "src/main/Testament.java"]
        tests = ["src/test/Contest.kt", "src/androidTest/Latest.java", "src/main/WidgetTest.kt",
                 "src/main/WidgetTests.java", "src/main/TestWidget.java", "src/main/widget_test.kt",
                 "src/main/widget.test.java", "src/main/test_widget.kt"]
        for classify in classifiers:
            for path in production:
                with self.subTest(classifier=classify.__qualname__, path=path):
                    self.assertFalse(classify(path))
            for path in tests:
                with self.subTest(classifier=classify.__qualname__, path=path):
                    self.assertTrue(classify(path))
