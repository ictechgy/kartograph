#!/usr/bin/env python3
"""v5/v6 기록을 보존하는 새 source 표기 대응과 분리된 영향 채점이다."""
import importlib.util
import copy
from pathlib import Path
import re

spec = importlib.util.spec_from_file_location('v7_base_grade', Path(__file__).resolve().parents[1] / 'ai-utility-v5/grade.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def normalize_selector(value):
    """함수 타입의 선택적 인자 이름만 지운다. 타입·순서·backtick 선언 이름은 보존한다."""
    value = value.strip()
    if value.startswith(('method:', 'field:', 'class:')):
        return value
    tokens = list(re.finditer(r'`[^`]*`|->|[A-Za-z_]\w*|[^\s]', value))
    words = [token.group() for token in tokens]
    stack, pairs = [], []
    for index, word in enumerate(words):
        if word == '`':
            return base.normalize_selector(value)
        if word == '(':
            stack.append(index)
        elif word == ')':
            if not stack:
                return base.normalize_selector(value)
            pairs.append((stack.pop(), index))
    if stack:
        return base.normalize_selector(value)
    removals = []
    for start, end in pairs:
        if end + 1 >= len(words) or words[end + 1] != '->':
            continue
        nested, argument = [], start + 1
        for index in range(start + 1, end + 1):
            word = words[index]
            if index == end or word == ',' and not nested:
                if (argument + 2 < index and words[argument + 1] == ':' and
                        re.fullmatch(r'[A-Za-z_]\w*|`[^`]+`', words[argument])):
                    removals.append((tokens[argument].start(), tokens[argument + 1].end()))
                argument = index + 1
            elif word in ('(', '[', '<'):
                nested.append(word)
            elif word in (')', ']', '>'):
                if not nested or nested.pop() != {')': '(', ']': '[', '>': '<'}[word]:
                    return base.normalize_selector(value)
    for start, end in sorted(set(removals), reverse=True):
        value = value[:start] + value[end:]
    return base.normalize_selector(value)


class Inventory(base.Inventory):
    """동치 표기를 양쪽에 적용하며 모호한 overload를 임의로 선택하지 않는다."""
    def __init__(self, document):
        canonical = copy.deepcopy(document)
        for row in canonical.get('declarations', []):
            if isinstance(row.get('aliases'), list):
                row['aliases'] = [normalize_selector(alias) if isinstance(alias, str) else alias
                                  for alias in row['aliases']]
        super().__init__(canonical)
        self.call_oracle = set(document.get('direct', [])) | set(document.get('indirect', []))
        self.behavior_positive = set(document.get('behaviorPositive', []))
        self.behavior_negative = set(document.get('behaviorNegative', []))
        if 'behaviorPositive' in document:
            if (self.oracle != self.call_oracle | self.behavior_positive or
                    self.behavior_positive & self.behavior_negative or
                    self.call_oracle & (self.behavior_positive | self.behavior_negative) or
                    not self.behavior_negative <= self.by_id.keys() or
                    set(document.get('executedTestMethods', [])) != self.behavior_positive | self.behavior_negative):
                raise ValueError('inconsistent typed oracle partitions')
            for identity in self.behavior_negative:
                row = self.by_id[identity]
                if not row['sourceAddressable'] or not any(self.by_alias[(row['file'], alias)] == {identity}
                                                           for alias in row['aliases']):
                    raise ValueError('negative control requires a unique source alias')

    def resolve(self, prediction):
        return super().resolve(dict(prediction, symbol=normalize_selector(prediction['symbol'])))


def grade(text, inventory):
    """선언 매칭 진단을 보존하되 호출·동작 점수는 답변 kind를 확인해 별도로 계산한다."""
    result = base.grade(text, inventory)
    answer, _ = base.decode(text)
    callers, tests, mismatches = set(), set(), 0
    for row in result['predictions']:
        if row['status'] not in ('in-oracle', 'outside-oracle-unadjudicated'):
            continue
        identity = row['candidates'][0]
        prediction = answer['targets'][row['index']]
        test_file = inventory.by_id[identity]['file'].startswith('src/test/')
        is_test = prediction['kind'] == 'test'
        if is_test != test_file:
            mismatches += 1
            continue
        (tests if is_test else callers).add(identity)
    call_covered = callers & inventory.call_oracle
    behavior_covered = tests & inventory.behavior_positive
    negatives = tests & inventory.behavior_negative
    adjudicated = len(behavior_covered) + len(negatives)
    result.update(callCovered=sorted(call_covered), callOracleSize=len(inventory.call_oracle),
        callRecall=len(call_covered) / len(inventory.call_oracle) if inventory.call_oracle else None,
        behaviorCovered=sorted(behavior_covered), behaviorPositiveSize=len(inventory.behavior_positive),
        behaviorRecall=len(behavior_covered) / len(inventory.behavior_positive) if inventory.behavior_positive else None,
        negativeControlPredictions=sorted(negatives), behaviorNegativeSize=len(inventory.behavior_negative),
        observedTestPrecision=len(behavior_covered) / adjudicated if adjudicated else None,
        observedTestPrecisionMeaning='Within-suite overprediction for this fixed patch and executed tests, including controls that do not reach the changed method; unresolved predictions reported separately.',
        productionPredictionsOutsideOracle=sorted(callers - inventory.call_oracle),
        testPredictionsOutsideControls=sorted(tests - inventory.behavior_positive - inventory.behavior_negative),
        kindMismatches=mismatches)
    return result
