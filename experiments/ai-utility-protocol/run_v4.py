#!/usr/bin/env python3
"""v4 코호트를 동결 입력으로 실행한다. v3 하네스(../preflight-evaluation/run.py)를 최대한 그대로 쓰고
바뀐 부분(v4 입력 경로, transitive prompt, 사전 등록 채점 규약, smoke 모드)만 여기서 정의한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

# v3 하네스를 재사용한다. 복사하지 않고 import해서 두 버전이 갈라지지 않게 한다.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'preflight-evaluation'))
import run as v3  # noqa: E402
from run import (  # noqa: E402
    EvaluationError,
    case_base,
    case_folder,
    digest,
    parse_events,
    proxy_trace_errors,
    read_json,
    run_provider,
    source_manifest,
    tooling_error,
    verify_frozen_checkout,
    write_json,
)

ROOT = Path(__file__).resolve().parents[2]
MAX_PROMPT = v3.MAX_PROMPT

# 시스템 프롬프트는 v1~v3과 같은 문자열을 그대로 쓴다.
SYSTEM = v3.SYSTEM

# transitive가 아닌 두 사례(jackson, ktlint)는 v3 prompt를 바이트 그대로 쓴다.
TEMPLATE_DIRECT = v3.TEMPLATE

# transitive 두 사례(fastjson2, detekt)는 직접 + 2단계 간접 영향까지 요구한다.
# 상한 12개와 JSON 형태는 같고, target마다 선택 필드 "relation"만 추가한다.
TEMPLATE_TRANSITIVE = '''Review this proposed production change before applying it to {repository} at base {base}.
Identify at most12 EXISTING test methods, internal callers, overrides, or registration points worth reviewing. List both the DIRECT references to the changed symbols and their INDIRECT (second-level) callers: existing declarations that reference a direct caller of a changed symbol. Prefer exact methods/callsites with their file; state when evidence is only file-level. Give a short reason and concrete source or graph evidence. Note uncertainty and unavailable consumers. The change is supplied for review and is NOT applied to the checkout.
You have a shared budget of12 information-tool calls. Use the available source/graph tools as useful; no tool is mandatory. Do not edit or run builds/tests.
Return one JSON object only. "relation" is optional; omit it when you cannot tell direct from indirect:
{{"targets":[{{"file":"project-relative path","symbol":"exact method/USR or an explicitly file-level name","kind":"test|caller|override|registration","relation":"direct|indirect","reason":"why review","evidence":"observed source lines or graph path"}}],"unknowns":["limitations"],"summary":"short conclusion"}}
<proposed-production-change>
{patch}
</proposed-production-change>
'''

# 모델 노출 전에 고정하는 채점 규약. 실행 후에 바꾸지 않는다.
SCORING_RULES = {
    'format': 'kartograph-ai-utility-v4-scoring',
    'version': 1,
    'frozenBeforeModelExposure': True,
    'recallDenominator': "The full oracle size of the case: direct targets plus indirect (depth-2) targets. "
                         "Per-case ceiling = min(1, 12 / oracle size) and is reported next to every recall value.",
    'precisionDenominator': "The number of valid targets the model listed. An answer with more than 12 targets stays "
                            "invalid under the v3 rule (too-many-targets) and is not rescored.",
    'cohortMean': "The cohort mean includes all four cases. fasterxml__jackson-core-1016 (oracle 32, ceiling 0.375) "
                  "is additionally reported on its own row so the structural ceiling is never read as a tool effect.",
    'kindBlind': "Matching ignores kind (test/caller/override/registration) and looks only at file plus symbol. "
                 "The v1 Gson-specific override overload heuristic in grade.py is therefore not applied in v4.",
    'overloadMatching': "Symbol matching reuses the v3 grade.py rule unchanged and therefore does not distinguish "
                        "same-named overloads: one prediction naming a method can credit every oracle overload of "
                        "that name in the same file, while an exact USR credits exactly one. In cases with many "
                        "same-named overloads (jackson in particular) recall can come out structurally high, and the "
                        "results section states this limitation. The rule itself is not changed.",
    'oracleTestTargets': {
        'alibaba__fastjson2-2097': 0,
        'detekt_detekt-7625': 2,
        'fasterxml__jackson-core-1016': 9,
        'pinterest_ktlint-2785': 0,
        'note': "fastjson2 and ktlint have zero test targets in the oracle. That is the result of the uniform "
                "evidence rule, not a claim that no related tests exist.",
    },
    'transitiveCaseMeaning': "transitiveCase marks the cases that receive the direct-and-indirect listing question. "
                             "The tie-break is ascending Unicode code point order of the cohort-v4 id string "
                             "(user-confirmed), which selects alibaba__fastjson2-2097 and detekt_detekt-7625.",
    'partialCredit': "A file-level-only match scores 0.5 in grade.py. In v4 it is recorded as a separate partial "
                     "count and never enters recall or precision.",
    'freshness': "Freshness is only observable in the mcp arm. Freshness calls and their status are recorded and "
                 "scored separately, never mixed into recall/precision and never compared across arms.",
    'toolCounts': "query_symbol/impact/freshness call counts are recorded, never converted into a score, and never "
                  "used to claim a causal effect.",
}


# smoke 이후·16회 본 실행 전에 적용한 사전 변경 기록. 규칙 자체는 바꾸지 않았다.
PRE_EXECUTION_CHANGES = [{
    'at': 'after the smoke trial, before the first of the 16 pre-registered trials',
    'modelExposure': 'none of the 16 trials had started; only the excluded smoke-0 trial had run',
    'change': "Added the scoringRules.overloadMatching explanation. The smoke trial showed that the inherited v3 "
              "symbol rule credits every same-named overload in the matched file. Only the wording was added; no "
              "matching rule, oracle entry, prompt, cohort, budget or schedule was changed.",
    'previousScoringRulesSha256': '274bce59b28291044b2e015b1e683158e8a952a746a004c6c3b9c41644ad3086',
    'previousManifestSha256': '54f4999f1df0b54ffb252060e4e63ecc26b135f290ae6f713406bf0ce0724946',
    'oracleSha256Unchanged': 'c05648e7916af7a0ad039cba4bfcf77caa9d5e8779c8fe7d8248679e77a276fb',
}]


def scoring_rules_digest():
    """채점 규약의 정규화 JSON에 대한 sha256. manifest에 함께 남겨 실행 전 고정을 증명한다."""
    canonical = json.dumps(SCORING_RULES, ensure_ascii=True, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def fixed_input(root, name):
    """동결 입력 파일을 symlink 없이 지정 루트 안에서만 연다."""
    unresolved = root / name
    if unresolved.is_symlink():
        raise EvaluationError('fixed input must not be symbolic')
    resolved = unresolved.resolve(strict=True)
    if not resolved.is_relative_to(root.resolve(strict=True)) or not resolved.is_file():
        raise EvaluationError('fixed input escaped its root')
    return resolved


def case_inputs(args, identifier):
    """사례 id로 checkout·production diff·compact snapshot 경로를 만든다."""
    folder = case_folder(args.cases, identifier)
    return (folder, case_base(folder),
            fixed_input(args.diffs, identifier + '.net-production.diff'),
            fixed_input(args.snapshots, identifier + '.compact.json'))


def template_for(case):
    return TEMPLATE_TRANSITIVE if case['transitive'] else TEMPLATE_DIRECT


def current_files(binary, proxy):
    """실행 구현과 제품 바이트를 해시로 고정한다."""
    paths = [Path(__file__).resolve(), proxy,
             ROOT / 'experiments/preflight-evaluation/run.py',
             ROOT / 'experiments/ai-utility-protocol/grade_v4.py',
             ROOT / 'experiments/preflight-evaluation/PROTOCOL.md']
    files = {str(path.relative_to(ROOT)): digest(path) for path in paths}
    files['binary/' + binary.name] = digest(binary)
    libraries = sorted((binary.parent.parent / 'lib').glob('*.jar'))
    if not libraries:
        raise EvaluationError('installed product libraries are unavailable')
    files.update({'binary/' + path.name: digest(path) for path in libraries})
    return files


def cohort_entries(args):
    """cohort-v4와 oracle-v4를 읽어 사례 순서·transitive 배정·oracle 크기를 확정한다."""
    cohort_document = read_json(args.cohort)
    cohort = cohort_document.get('primary') if isinstance(cohort_document, dict) else None
    if not isinstance(cohort, list) or len(cohort) != 4:
        raise EvaluationError('frozen primary cohort must contain exactly 4 cases')
    oracle_document = read_json(args.oracle)
    oracles = {case['id']: case for case in oracle_document.get('cases', [])
               if isinstance(case, dict) and isinstance(case.get('id'), str)}
    if len(oracles) != 4:
        raise EvaluationError('frozen oracle must describe exactly 4 cases')
    entries = []
    for case in cohort:
        if not isinstance(case, dict) or not all(isinstance(case.get(key), str) for key in ('id', 'repository', 'base')):
            raise EvaluationError('frozen cohort entry is invalid')
        oracle = oracles.get(case['id'])
        if oracle is None or oracle.get('base') != case['base']:
            raise EvaluationError('frozen oracle does not match the cohort case')
        size = len(oracle['targets']) + len(oracle['indirectTargets'])
        if oracle['counts']['total'] != size:
            raise EvaluationError('frozen oracle counts disagree with its targets')
        entries.append(dict(case, transitive=bool(oracle.get('transitiveCase')), oracleSize=size,
                            recallCeiling=min(1.0, 12 / size)))
    return entries


def trial_schedule(entries, smoke):
    """사전 등록한 고정 순서: 사례(cohort-v4 primary 순) → 조건(source, mcp) → 반복(0, 1)."""
    if smoke:
        return [{'case': 'alibaba__fastjson2-2097', 'arm': 'mcp', 'repeat': 0, 'label': 'smoke-0'}]
    return [{'case': case['id'], 'arm': arm, 'repeat': repeat, 'label': f'{case["id"]}-{arm}-{repeat}'}
            for case in entries for arm in ('source', 'mcp') for repeat in (0, 1)]


def build_manifest(args, proxy, binary):
    entries, cohort = [], cohort_entries(args)
    for case in cohort:
        folder, base, patch_path, snapshot = case_inputs(args, case['id'])
        verify_frozen_checkout(base, case['base'])
        patch = patch_path.read_text(encoding='utf-8')
        prompt = template_for(case).format(repository=case['repository'], base=case['base'], patch=patch)
        if len(prompt.encode('utf-8')) > MAX_PROMPT:
            raise EvaluationError('frozen prompt exceeds limit')
        entries.append(dict(case=case['id'], repository=case['repository'], base=case['base'],
                            transitive=case['transitive'], oracleSize=case['oracleSize'],
                            recallCeiling=case['recallCeiling'], prompt=prompt,
                            promptSha256=hashlib.sha256(prompt.encode()).hexdigest(),
                            promptTemplate='transitive' if case['transitive'] else 'direct',
                            patchSha256=digest(patch_path), source=source_manifest(base),
                            snapshotSha256=digest(snapshot)))
    schedule = trial_schedule(cohort, args.smoke)
    return dict(format='kartograph-ai-utility-v4-manifest', version=1, mode='smoke' if args.smoke else 'trials',
                model='claude-opus-5[1m]', effort='low', maxTools=12, wallSeconds=900, maxBudgetUsd=3,
                snapshotMaxMiB=128, oracleSha256=digest(args.oracle),
                inputHashes={'cohort': digest(args.cohort), 'oracle': digest(args.oracle)},
                systemPrompt=SYSTEM, scoringRules=SCORING_RULES, scoringRulesSha256=scoring_rules_digest(),
                preExecutionChanges=PRE_EXECUTION_CHANGES,
                scheduleRule='Fixed and pre-registered before execution: case order as in cohort-v4 primary, '
                             'then arm source before mcp, then repeat 0 before 1.',
                cohort=entries, schedule=schedule, files=current_files(binary, proxy),
                isolation=dict(mode='restricted', settingSources=[], builtins=[], skillsDisabled=True,
                               memoryDisabled=True, strictMcp=True, cwd='empty temporary directory outside repository'),
                productCommandNote='No --input-bindings: the v4 snapshots were captured manually, so product '
                                   'freshness stays unverified. That is recorded, not scored as a defect.',
                purpose='Pre-edit review, not repair; no forced graph calls. All trials retained; smoke excluded '
                        'from results.')


def validate_prepared(args, manifest, proxy, binary):
    expected_trials = 1 if manifest.get('mode') == 'smoke' else 16
    if (not isinstance(manifest, dict) or manifest.get('format') != 'kartograph-ai-utility-v4-manifest' or
            manifest.get('version') != 1 or manifest.get('mode') not in ('trials', 'smoke')):
        raise EvaluationError('prepared manifest format is invalid')
    if (manifest.get('maxTools') != 12 or manifest.get('snapshotMaxMiB') != 128 or
            len(manifest.get('schedule', [])) != expected_trials or len(manifest.get('cohort', [])) != 4):
        raise EvaluationError('prepared manifest experiment contract is invalid')
    fixed = {'model': 'claude-opus-5[1m]', 'effort': 'low', 'maxTools': 12, 'wallSeconds': 900,
             'maxBudgetUsd': 3, 'snapshotMaxMiB': 128, 'systemPrompt': SYSTEM, 'scoringRules': SCORING_RULES,
             'scoringRulesSha256': scoring_rules_digest(), 'preExecutionChanges': PRE_EXECUTION_CHANGES}
    if any(type(manifest.get(key)) is not type(value) or manifest.get(key) != value for key, value in fixed.items()):
        raise EvaluationError('prepared model, resource or scoring contract changed')
    oracle_hash = digest(args.oracle)
    if (manifest.get('oracleSha256') != oracle_hash or
            manifest.get('inputHashes') != {'cohort': digest(args.cohort), 'oracle': oracle_hash}):
        raise EvaluationError('prepared manifest input hashes changed')
    if manifest.get('files') != current_files(binary, proxy):
        raise EvaluationError('prepared implementation or product bytes changed')
    cohort = cohort_entries(args)
    entries = manifest['cohort']
    if [entry.get('case') for entry in entries] != [case['id'] for case in cohort]:
        raise EvaluationError('prepared cohort order changed')
    for entry, case in zip(entries, cohort):
        if (entry.get('repository') != case['repository'] or entry.get('base') != case['base'] or
                entry.get('transitive') != case['transitive'] or entry.get('oracleSize') != case['oracleSize']):
            raise EvaluationError('prepared cohort identity changed')
        if hashlib.sha256(entry.get('prompt', '').encode()).hexdigest() != entry.get('promptSha256'):
            raise EvaluationError('prepared prompt changed')
        folder, base, patch_path, snapshot = case_inputs(args, entry['case'])
        verify_frozen_checkout(base, entry['base'])
        if (source_manifest(base) != entry.get('source') or digest(patch_path) != entry.get('patchSha256') or
                digest(snapshot) != entry.get('snapshotSha256')):
            raise EvaluationError('prepared case bytes changed')
        expected_prompt = template_for(case).format(repository=case['repository'], base=case['base'],
                                                    patch=patch_path.read_text(encoding='utf-8'))
        if entry.get('prompt') != expected_prompt:
            raise EvaluationError('prepared task prompt changed')
    if manifest.get('schedule') != trial_schedule(cohort, manifest['mode'] == 'smoke'):
        raise EvaluationError('prepared schedule changed')


def execute(args, manifest, proxy, binary):
    output = args.output
    execution_path = output / 'execution.json'
    if execution_path.exists() or (output / 'progress.json').exists() or any(
            path.name != 'manifest.json' for path in output.iterdir()):
        raise EvaluationError('prepared manifest execution already started; selective retry is forbidden')
    manifest_hash = digest(output / 'manifest.json')
    execution = {'status': 'running', 'mode': manifest['mode'], 'manifestSha256': manifest_hash,
                 'startedAt': time.time(), 'trials': 0}
    write_json(execution_path, execution, exclusive=True)
    entries, schedule, records = manifest['cohort'], manifest['schedule'], []
    fatal = None
    try:
        for number, trial in enumerate(schedule):
            case = next(item for item in entries if item['case'] == trial['case'])
            folder, base, patch_path, snapshot = case_inputs(args, trial['case'])
            run = output / f'{number:02d}-{trial["label"]}'
            run.mkdir()
            source_path = run / 'source-manifest.json'
            write_json(source_path, case['source'], exclusive=True)
            product = [str(binary), 'mcp', '--graph-file', str(snapshot), '--project', str(base),
                       '--snapshot-max-mib', str(manifest['snapshotMaxMiB'])]
            write_json(run / 'product-command.json', product, exclusive=True)
            proxy_args = [str(proxy), '--repository', str(base), '--trace', str((run / 'tools.jsonl').resolve()),
                          '--source-manifest', str(source_path.resolve()), '--max-tools', str(manifest['maxTools'])]
            if trial['arm'] == 'mcp':
                proxy_args += ['--product-command', str((run / 'product-command.json').resolve())]
            config = {'mcpServers': {'preflight': {'command': sys.executable, 'args': proxy_args,
                      'env': {'JAVA_HOME': args.java_home}}}}
            write_json(run / 'mcp-config.json', config, exclusive=True)
            command = ['claude', '--print', '--restricted', '--setting-sources', '', '--model', manifest['model'],
                       '--effort', manifest['effort'], '--tools', '', '--disable-slash-commands', '--strict-mcp-config',
                       '--mcp-config', json.dumps(config), '--allowedTools', 'mcp__preflight__*',
                       '--no-session-persistence', '--output-format', 'stream-json', '--verbose',
                       '--max-budget-usd', str(manifest['maxBudgetUsd']), '--system-prompt', manifest['systemPrompt']]
            environment = dict(os.environ, CLAUDE_CODE_DISABLE_CLAUDE_MDS='1', CLAUDE_CODE_DISABLE_AUTO_MEMORY='1')
            started = time.monotonic()
            outcome = run_provider(command, case['prompt'], environment, run, manifest['wallSeconds'])
            elapsed = time.monotonic() - started
            events, result, uses, initial, parse_errors = parse_events(run / 'stream.jsonl')
            errors = []
            tool_error = tooling_error(initial, trial['arm'])
            if tool_error:
                errors.append(tool_error)
            if outcome.get('providerStartFailed'):
                errors.append('provider-start-failed')
            if outcome['timedOut']:
                errors.append('provider-timeout')
            if outcome['streamOversized']:
                errors.append('provider-stream-oversized')
            if outcome['stderrOversized']:
                errors.append('provider-stderr-oversized')
            errors += outcome['captureFailures'] + [item['kind'] for item in parse_errors]
            errors += proxy_trace_errors(run / 'tools.jsonl')
            if outcome.get('exit') not in (0, None):
                errors.append('provider-exit')
            if result is None:
                errors.append('provider-result-missing')
            elif result.get('is_error'):
                errors.append('provider-result-error')
            try:
                if source_manifest(base) != case['source']:
                    errors.append('source-changed-during-trial')
            except EvaluationError:
                errors.append('source-unavailable-after-trial')
            record = {**trial, 'directory': str(run.resolve()), 'seconds': elapsed, 'exit': outcome.get('exit'),
                      'timedOut': outcome['timedOut'], 'resultPresent': result is not None,
                      'providerError': result.get('is_error') if result else None,
                      'infrastructureErrors': sorted(set(errors)), 'eventParseErrors': parse_errors,
                      'toolSurface': initial.get('tools') if isinstance(initial, dict) else None,
                      'toolUses': [{'name': item.get('name'), 'input': item.get('input')} for item in uses],
                      'toolBudgetExceeded': len(uses) > manifest['maxTools'],
                      'inputHashes': {'manifest': manifest_hash, 'prompt': case['promptSha256'],
                                      'snapshot': case['snapshotSha256'],
                                      'sourceManifest': digest(source_path),
                                      'productCommand': digest(run / 'product-command.json'),
                                      'mcpConfig': digest(run / 'mcp-config.json')},
                      'evidenceHashes': {'stream': digest(run / 'stream.jsonl'), 'stderr': digest(run / 'stderr'),
                                         'tools': digest(run / 'tools.jsonl') if (run / 'tools.jsonl').is_file() else None}}
            records.append(record)
            write_json(output / 'progress.json', records)
            print(number, trial['label'], 'tools', len(uses), 'seconds', round(elapsed, 1),
                  'exit', outcome.get('exit'), 'infra', ','.join(record['infrastructureErrors']), flush=True)
            source_error = next((error for error in errors if error.startswith('source-')), None)
            if tool_error or outcome.get('providerStartFailed') or source_error:
                fatal = tool_error or ('provider-start-failed' if outcome.get('providerStartFailed') else source_error)
                break
        execution.update(status='aborted' if fatal else 'completed', completedAt=time.time(),
                         trials=len(records), fatalInfrastructure=fatal)
        write_json(execution_path, execution)
    except Exception:
        execution.update(status='failed', completedAt=time.time(), trials=len(records),
                         fatalInfrastructure='runner-failure')
        write_json(execution_path, execution)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cohort', type=Path, required=True)
    parser.add_argument('--oracle', type=Path, required=True)
    parser.add_argument('--cases', type=Path, required=True, help='사례별 base checkout이 있는 루트')
    parser.add_argument('--diffs', type=Path, required=True, help='<case>.net-production.diff 루트')
    parser.add_argument('--snapshots', type=Path, required=True, help='<case>.compact.json 루트')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--java-home', default='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
    parser.add_argument('--smoke', action='store_true', help='16회 밖의 단일 smoke 실행')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--execute-prepared', action='store_true')
    args = parser.parse_args()
    if args.prepare_only and args.execute_prepared:
        raise EvaluationError('choose prepare-only or execute-prepared')
    paths = (args.cohort, args.oracle, args.cases, args.diffs, args.snapshots, args.binary, args.output)
    if any(path.is_symlink() for path in paths):
        raise EvaluationError('evaluation paths must not be symbolic links')
    proxy = Path(__file__).resolve().parents[1] / 'preflight-evaluation' / 'session_mcp.py'
    binary = args.binary.resolve(strict=True)
    if args.execute_prepared:
        if not args.output.is_dir():
            raise EvaluationError('prepared evidence directory is unavailable')
        manifest = read_json(args.output / 'manifest.json')
        if args.smoke != (manifest.get('mode') == 'smoke'):
            raise EvaluationError('prepared manifest mode does not match the requested mode')
        validate_prepared(args, manifest, proxy, binary)
        execute(args, manifest, proxy, binary)
        return 0
    if args.output.exists():
        raise EvaluationError('use a new evidence directory; prior trials are immutable')
    args.output.mkdir(parents=True)
    manifest = build_manifest(args, proxy, binary)
    write_json(args.output / 'manifest.json', manifest, exclusive=True)
    if not args.prepare_only:
        execute(args, manifest, proxy, binary)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (EvaluationError, OSError, subprocess.CalledProcessError):
        print('error: ai-utility v4 evaluation setup or execution failed', file=sys.stderr)
        raise SystemExit(2)
