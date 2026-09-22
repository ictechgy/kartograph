"""닫힌 JUnit observer 증거에서 invocation과 원본 메서드별 변화만 판정한다."""
import collections
import hashlib
import json


def read_execution(folder):
    """완결 receipt·hash·유일한 UID를 모두 확인한 실제 실행만 수락한다."""
    paths = sorted(folder.glob('plan-*.jsonl'))
    if not paths:
        raise ValueError('observer journals missing')
    invocations, hashes, skipped_containers = {}, {}, 0
    for path in paths:
        seal_path = path.with_name(path.name + '.complete.json')
        if path.is_symlink() or not seal_path.is_file() or seal_path.is_symlink() or path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError('observer journal is unsealed or outside bounds')
        try:
            seal = json.loads(seal_path.read_text())
            raw = path.read_bytes(); digest = hashlib.sha256(raw).hexdigest()
            rows = [json.loads(line) for line in raw.decode().splitlines()]
            if seal != dict(journal=path.name, sha256=digest, complete=True):
                raise ValueError('observer seal/hash mismatch')
            if (len(rows) < 2 or rows[0].get('type') != 'start' or rows[0].get('version') != 1 or
                    rows[-1].get('type') != 'complete' or rows[-1].get('complete') is not True):
                raise ValueError('observer did not complete')
            tests = rows[1:-1]; end = rows[-1]
            if end['skippedContainers'] != 0:
                raise ValueError('container skip identities are not qualified by this observer version')
            if (end['registeredTests'] != len(tests) or end['terminalTests'] != len(tests) or
                    end['containerFailures'] != 0 or not 0 <= rows[0]['initialTests'] <= len(tests)):
                raise ValueError('observer terminal/discovery counts differ')
            for test in tests:
                if (test.get('type') != 'test' or not isinstance(test.get('uid'), str) or not test['uid'] or
                        test['uid'] in invocations or not isinstance(test.get('method'), str) or
                        not test['method'].startswith('method:') or '#' not in test['method'] or
                        test.get('status') not in {'pass', 'assertion-failure', 'error', 'aborted', 'skipped'}):
                    raise ValueError('invalid or duplicate observed invocation')
                invocations[test['uid']] = {key: test[key] for key in ['method', 'status', 'throwableClass']}
            skipped_containers += end['skippedContainers']
            hashes[path.name] = digest
            hashes[seal_path.name] = hashlib.sha256(seal_path.read_bytes()).hexdigest()
        except (KeyError, TypeError, UnicodeError, json.JSONDecodeError) as failure:
            raise ValueError('malformed observer evidence') from failure
    if len(list(folder.glob('plan-*.complete.json'))) != len(paths):
        raise ValueError('orphan observer seal')
    return dict(invocations=invocations, skippedContainers=skipped_containers, evidenceSha256=hashes)


def partition(before, after):
    """같은 invocation의 assertion 변화만 메서드별로 합치며 건너뛴 실행은 별도 보존한다."""
    first, second = before['invocations'], after['invocations']
    if not first or first.keys() != second.keys() or before['skippedContainers'] != after['skippedContainers']:
        raise ValueError('test invocation inventory changed')
    positive, executed, skipped, failed_invocations = set(), set(), 0, []
    for uid, original in first.items():
        current = second[uid]
        if original['method'] != current['method']:
            raise ValueError('test source identity changed')
        if original['status'] == current['status'] == 'skipped':
            skipped += 1
            continue
        if original['status'] != 'pass' or current['status'] not in ['pass', 'assertion-failure']:
            raise ValueError('baseline failure, execution error or changed skip invalidates qualification')
        executed.add(original['method'])
        if current['status'] == 'assertion-failure':
            positive.add(original['method']); failed_invocations.append(uid)
    negative = executed - positive
    if not positive or not negative:
        raise ValueError('both observed positive and negative test methods are required')
    return dict(positive=sorted(positive), negative=sorted(negative), executed=sorted(executed),
        assertionFailures=len(failed_invocations), failingInvocations=sorted(failed_invocations),
        totalInvocations=len(first), executedInvocations=len(first) - skipped, skippedInvocations=skipped,
        skippedContainers=before['skippedContainers'],
        invocationsPerMethod=dict(collections.Counter(row['method'] for row in first.values() if row['status'] == 'pass')))
