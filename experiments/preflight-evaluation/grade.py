#!/usr/bin/env python3
"""동결 기준 coverage와 실행 비용을 계산하고 조건명을 뺀 수동 검토 자료를 만든다."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics


def decode_answer(result):
    if not result: return None, 'missing-result'
    if result.get('is_error'): return None, 'provider-error'
    text = result.get('result', '')
    if not isinstance(text, str): return None, 'invalid-json'
    text = text.strip()
    if text.startswith('```') and text.endswith('```'):
        text = '\n'.join(text.splitlines()[1:-1])
    try: value = json.loads(text)
    except (ValueError, TypeError): return None, 'invalid-json'
    if not isinstance(value, dict) or not isinstance(value.get('targets'), list) or not isinstance(value.get('unknowns'), list):
        return None, 'invalid-answer-shape'
    if len(value['targets']) > 12: return None, 'too-many-targets'
    return value, None


def parse_answer(result):
    return decode_answer(result)[0]


def target_credit(prediction, target):
    if not isinstance(prediction, dict): return 0.0
    file = re.sub(r':\d+(?:-\d+)?$', '', str(prediction.get('file', '')).removeprefix('./'))
    if file != target['file']: return 0.0
    raw_symbol = str(prediction.get('symbol', '')).strip()
    symbol = raw_symbol.replace('`', '')
    if symbol == target['id']: return 1.0
    if symbol.startswith('method:'): return 0.0
    if target['kind'] == 'override':
        # 기존 코퍼스의 정확한 double overload만 지원한다. 설명 키워드로 타입을 추측하지 않는다.
        if re.search(r'(?:^|[.#])value\s*\(\s*(?:double|D)(?:\s+\w+)?\s*\)$', symbol): return 1.0
    else:
        name = target['name']
        # 메서드 자리와 끝을 확인해 긴 Kotlin 이름의 접두어나 설명 속 단어를 별도 메서드로 세지 않는다.
        quoted = [value for value in re.findall(r'(?:^|[.#/])\s*`([^`]+)`(?!\.)', raw_symbol)
                  if any(character.isspace() for character in value)]
        if quoted:
            matched = any(re.search(r'(?:^|[.#])'+re.escape(name)+r'$', value) for value in quoted)
        else:
            matched = re.search(r'(?:^|[.#/])\s*'+re.escape(name)+r'(?:\s*\(.*\))?(?=$|\s*/)', symbol)
        if matched:
            return 1.0
    stem = Path(file).stem
    # 클래스 뒤의 범위 설명은 메서드 이름이 아니다. 다른 메서드 표기는 부분 점수로 바꾸지 않는다.
    class_symbol = re.split(r'\s+(?=[(\[])', symbol, maxsplit=1)[0]
    if not class_symbol or class_symbol == stem or class_symbol.endswith('.'+stem) or class_symbol.endswith('/'+stem): return 0.5
    return 0.0


def event_summary(path):
    events = []
    malformed = 0
    for line in path.read_text().splitlines():
        try: events.append(json.loads(line))
        except ValueError: malformed += 1
    results = [value for value in events if value.get('type') == 'result']
    uses = [content for event in events if event.get('type') == 'assistant'
            for content in event.get('message', {}).get('content', []) if content.get('type') == 'tool_use']
    bytes_seen = 0
    for event in events:
        if event.get('type') == 'user':
            for content in event.get('message', {}).get('content', []):
                if content.get('type') == 'tool_result':
                    bytes_seen += len(json.dumps(content.get('content'), ensure_ascii=False).encode('utf-8'))
    return results[-1] if results else None, uses, bytes_seen, malformed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--oracle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists()
    args.output.mkdir(parents=True)
    (args.output/'blind').mkdir()
    manifest = json.loads((args.run/'manifest.json').read_text())
    assert hashlib.sha256(args.oracle.read_bytes()).hexdigest() == manifest['oracleSha256']
    oracles = {case['id']:case for case in json.loads(args.oracle.read_text())['cases']}
    progress = json.loads((args.run/'progress.json').read_text())
    rows=[];blind_key={}
    for trial in progress:
        directory=Path(trial['directory']);result,uses,bytes_seen,malformed=event_summary(directory/'stream.jsonl')
        answer, invalid_reason=decode_answer(result);targets=oracles[trial['case']]['targets']
        credits=[max((target_credit(item,target) for item in answer['targets']),default=0) if answer else 0 for target in targets]
        additional=sum(not any(target_credit(item,target)>0 for target in targets) for item in answer['targets']) if answer else 0
        row={**trial,'answerValid':answer is not None,'answerInvalidReason':invalid_reason,'anchorCount':len(targets),'anchorCredits':dict(zip([x['id'] for x in targets],credits)),
             'coverage':sum(credits)/len(targets),'targetCount':len(answer['targets']) if answer else 0,
             'additionalTargets':additional,'observedToolContentBytes':bytes_seen,'malformedStreamLines':malformed,
             'informationCalls':len(uses),'graphCalls':sum(value['name'].rsplit('__',1)[-1] in ('query_symbol','impact','freshness') for value in uses),
             'costUsd':result.get('total_cost_usd') if result else None,'usage':result.get('usage') if result else None}
        rows.append(row)
        blind=hashlib.sha256((trial['case']+str(trial['repeat'])+trial['arm']).encode()).hexdigest()[:12]
        blind_key[blind]={'case':trial['case'],'repeat':trial['repeat'],'arm':trial['arm'],'directory':trial['directory']}
        (args.output/'blind'/(blind+'.json')).write_text(json.dumps({'case':trial['case'],'answer':answer},indent=2)+'\n')
    summaries=[]
    for case in oracles:
        for arm in ('source','mcp'):
            chosen=[row for row in rows if row['case']==case and row['arm']==arm]
            summaries.append({'case':case,'arm':arm,'trials':len(chosen),
                'coverageMean':statistics.mean(row['coverage'] for row in chosen) if chosen else None,
                'secondsMean':statistics.mean(row['seconds'] for row in chosen) if chosen else None,
                'toolBytesMean':statistics.mean(row['observedToolContentBytes'] for row in chosen) if chosen else None,
                'graphCalls':sum(row['graphCalls'] for row in chosen)})
    (args.output/'scores.json').write_text(json.dumps({'trials':rows,'summary':summaries,'plannedTrials':len(manifest['schedule']),
        'manualReviewPending':True,'warning':'Coverage is against known independent review anchors, not complete runtime impact. Additional targets require source/evidence review. No benefit verdict before all trials/manual fidelity and safety assessment.'},indent=2)+'\n')
    (args.output/'blind-key.json').write_text(json.dumps(blind_key,indent=2)+'\n')
    print(json.dumps(summaries,indent=2))


if __name__=='__main__':main()
