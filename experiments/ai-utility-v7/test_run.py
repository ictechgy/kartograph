"""고정 v6 controller 회귀를 새 v7 실행기에 적용한다."""
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


study = load('v7_controller_under_test', HERE / 'run.py')
tests = load('v6_controller_regressions', HERE.parent / 'ai-utility-v6/test_run.py')
tests.study = study
StructuredTrialTest = tests.StructuredTrialTest
