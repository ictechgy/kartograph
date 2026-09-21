#!/usr/bin/env python3
"""동결된 v4 점수를 바꾸지 않고 조건명 없는 응답의 다중 점수 부여를 감사한다."""
import argparse
import hashlib
import json
from pathlib import Path

import grade_v4


def audit(base):
    oracle = json.loads((base / 'oracle-v4.json').read_text())
    cases = {case['id']: case for case in oracle['cases']}
    paths = sorted((base / 'results-v4/blind').glob('*.json'))
    if len(paths) != 16:
        raise ValueError('expected all 16 frozen answers')
    rows = []
    for path in paths:
        document = json.loads(path.read_text())
        targets = grade_v4.oracle_targets(cases[document['case']])
        answer = document.get('answer')
        predictions, malformed = grade_v4.valid_predictions(answer)
        row = dict(id=path.stem, case=document['case'], answerPresent=answer is not None,
                   malformedPredictions=malformed, predictions=[])
        for index, prediction in enumerate(predictions):
            full = [target['id'] for target in targets if grade_v4.target_credit(prediction, target) == 1]
            row['predictions'].append(dict(
                index=index, file=prediction['file'], symbol=prediction['symbol'],
                frozenFullCreditIds=full, onePredictionCreditsMultipleOracleIds=len(full) > 1,
                exactUsr=prediction['symbol'] in full,
                fileLevelCreditIds=[target['id'] for target in targets
                                    if grade_v4.target_credit(prediction, target) == .5]))
        rows.append(row)
    files = [base / 'oracle-v4.json', base / 'grade_v4.py', base / 'results-v4/results-v4.json', *paths]
    return dict(format='kartograph-v4-posthoc-matching-audit',
                frozenInputIntegrity='Not determined by this script; compare inputSha256 with an immutable baseline.',
                note='Descriptive audit of the existing matching rule, not a new efficacy score. '
                     'Non-oracle predictions are unadjudicated, not false positives. '
                     'The main reviewer knows published aggregate results; this is not fully blinded.',
                inputSha256={str(path.relative_to(base)): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in files}, answers=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    base = Path(__file__).resolve().parent
    output = args.output.resolve()
    protected = [base / 'oracle-v4.json', base / 'grade_v4.py', base / 'audit_v4.py']
    if output.is_relative_to((base / 'results-v4').resolve()) or output in [p.resolve() for p in protected]:
        parser.error('output must not replace frozen inputs or audit code')
    result = audit(base)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(answers=len(result['answers']),
                          predictions=sum(len(row['predictions']) for row in result['answers']),
                          multiCreditPredictions=sum(prediction['onePredictionCreditsMultipleOracleIds']
                                                     for row in result['answers'] for prediction in row['predictions']))))


if __name__ == '__main__':
    main()
