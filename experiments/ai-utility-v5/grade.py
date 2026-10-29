#!/usr/bin/env python3
"""독립 선언 목록으로 예측을 일대일 대응한다. oracle 밖 대상을 오탐으로 단정하지 않는다."""
import argparse
import json
from pathlib import Path, PurePosixPath
import re


MAX_PREDICTIONS = 64


def normalize_selector(value):
    """source 문법의 구두점 공백만 정규화하고 USR·backtick 이름의 내용은 보존한다."""
    value = value.strip()
    if value.startswith(('method:', 'field:', 'class:')):
        return value
    parts = value.split('`')
    for i in range(0, len(parts), 2):
        parts[i] = re.sub(r'\s*([().,:<>\[\]?])\s*', r'\1', re.sub(r'\s+', ' ', parts[i]))
    return '`'.join(parts)


def portable(value):
    """source identity의 파일은 정규화된 프로젝트 상대경로여야 한다."""
    return (isinstance(value, str) and bool(value) and not value.startswith('/') and '\\' not in value
            and ':' not in value and not any(ord(ch) < 32 for ch in value)
            and all(part not in ('', '.', '..') for part in value.split('/')))


class Inventory:
    """제품 질의 전에 javap·원본 source에서 고정한 전체 선언 목록이다."""
    def __init__(self, document):
        self.by_id = {}
        self.by_alias = {}
        if not isinstance(document, dict) or not isinstance(document.get('declarations'), list):
            raise ValueError('declaration inventory is required')
        for row in document['declarations']:
            if (not isinstance(row, dict) or not isinstance(row.get('id'), str) or
                    not row['id'].startswith(('method:', 'field:', 'class:')) or
                    not (portable(row.get('file')) or row.get('file') is None and row.get('sourceAddressable') is False and row.get('aliases') == []) or
                    not isinstance(row.get('sourceAddressable'), bool) or
                    not isinstance(row.get('aliases'), list) or
                    any(not isinstance(x, str) or not x.strip() or x != x.strip() for x in row['aliases'])):
                raise ValueError('invalid declaration inventory row')
            if row['id'] in self.by_id:
                raise ValueError('duplicate declaration identity')
            self.by_id[row['id']] = row
            for alias in {normalize_selector(x) for x in row['aliases']}:
                self.by_alias.setdefault((row['file'], alias), set()).add(row['id'])
        self.oracle = document.get('targets')
        if (not isinstance(self.oracle, list) or not self.oracle or len(self.oracle) != len(set(self.oracle)) or
                any(x not in self.by_id for x in self.oracle) or len(self.oracle) > MAX_PREDICTIONS):
            raise ValueError('oracle must contain 1..64 distinct inventory identities')
        self.oracle = set(self.oracle)
        self.files = {row['file'] for row in self.by_id.values() if row['file'] is not None}
        for identity in self.oracle:
            row = self.by_id[identity]
            unique = any(self.by_alias[(row['file'], normalize_selector(alias))] == {identity} for alias in row['aliases'])
            if row['sourceAddressable'] and not unique:
                raise ValueError('source-addressable oracle identity requires a unique source alias')
            if not row['sourceAddressable'] and row['aliases']:
                raise ValueError('non-source-addressable oracle identity must not have authored source aliases')
        self.source_oracle = {x for x in self.oracle if self.by_id[x]['sourceAddressable']}
        if not self.source_oracle:
            raise ValueError('primary oracle requires source-addressable declarations')
        self.changed = set(document.get('changed', []))
        if not self.changed <= self.by_id.keys():
            raise ValueError('changed identity is absent from inventory')

    def resolve(self, prediction):
        file, symbol = prediction['file'], normalize_selector(prediction['symbol'])
        if not portable(file):
            return 'invalid-file', []
        if file not in self.files:
            return 'file-outside-inventory', []
        if symbol.startswith(('method:', 'field:', 'class:')):
            row = self.by_id.get(symbol)
            ids = [symbol] if row and row['file'] == file else []
        else:
            ids = sorted(self.by_alias.get((file, symbol), set()))
        if len(ids) == 1:
            return 'resolved', ids
        if len(ids) > 1:
            return 'ambiguous', ids
        # 파일 수준 답변은 선언 점수로 승격하지 않는다.
        stem = PurePosixPath(file).stem
        if symbol in (stem, normalize_selector(stem + ' (file-level)')):
            return 'file-level', []
        return 'unresolved', []


def decode(text):
    """고정 JSON 응답 계약을 검사한다. 무효·초과 응답도 결과 분모에서 제외하지 않는다."""
    if not isinstance(text, str) or not text.strip():
        return None, 'missing-result'
    text = text.strip()
    if text.startswith(('```json\n', '```\n')) and text.endswith('\n```'):
        text = '\n'.join(text.splitlines()[1:-1])
    try:
        answer = json.loads(text)
    except ValueError:
        return None, 'invalid-json'
    if (not isinstance(answer, dict) or not isinstance(answer.get('targets'), list) or
            not isinstance(answer.get('unknowns'), list) or
            any(not isinstance(x, str) for x in answer['unknowns'])):
        return None, 'invalid-answer-shape'
    if len(answer['targets']) > MAX_PREDICTIONS:
        return None, 'too-many-targets'
    if any(not isinstance(p, dict) or not isinstance(p.get('file'), str) or not p['file'].strip() or
           not isinstance(p.get('symbol'), str) or not p['symbol'].strip() for p in answer['targets']):
        return None, 'invalid-target-shape'
    return answer, None


def grade(text, inventory):
    """한 예측은 최대 한 identity만 덮고 중복 예측은 가점을 늘리지 않는다."""
    answer, error = decode(text)
    resolved, covered, rows = set(), set(), []
    for index, prediction in enumerate(answer['targets'] if answer else []):
        status, candidates = inventory.resolve(prediction)
        row = dict(index=index, status=status, candidates=candidates)
        if status == 'resolved':
            identity = candidates[0]
            row['changedDeclaration'] = identity in inventory.changed
            if identity in resolved:
                row['status'] = 'duplicate'
            else:
                resolved.add(identity)
                row['status'] = 'in-oracle' if identity in inventory.oracle else 'outside-oracle-unadjudicated'
                if identity in inventory.oracle:
                    covered.add(identity)
        rows.append(row)
    primary = covered & inventory.source_oracle
    return dict(valid=error is None, error=error, predictionCount=len(rows),
                oracleSize=len(inventory.oracle), sourceAddressableOracleSize=len(inventory.source_oracle),
                primaryOracle=sorted(inventory.source_oracle), nonPrimaryOracle=sorted(inventory.oracle - inventory.source_oracle),
                covered=sorted(covered), primaryRecall=len(primary) / len(inventory.source_oracle),
                allDeclarationRecall=len(covered) / len(inventory.oracle),
                uniqueResolvedPredictions=len(resolved), duplicatePredictions=sum(r['status'] == 'duplicate' for r in rows),
                ambiguousPredictions=sum(r['status'] == 'ambiguous' for r in rows),
                fileLevelPredictions=sum(r['status'] == 'file-level' for r in rows),
                outsideOracle=sorted(resolved - inventory.oracle),
                oracleAgreement=len(covered) / len(resolved) if resolved else None,
                oracleAgreementMeaning='Overlap with an incomplete oracle, not false-positive precision.',
                predictions=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', required=True, type=Path)
    parser.add_argument('--answer', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.resolve() in {args.inventory.resolve(), args.answer.resolve()}:
        parser.error('output must not replace an input')
    result = grade(args.answer.read_text(), Inventory(json.loads(args.inventory.read_text())))
    with args.output.open('x') as output:
        output.write(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
