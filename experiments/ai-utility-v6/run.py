#!/usr/bin/env python3
"""새 cohort와 구조화 응답 계약을 고정하고 읽기 전용 source/MCP 16회를 보존한다."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
V5 = ROOT / "experiments/ai-utility-v5"
old_path = ROOT / 'experiments/preflight-evaluation/run.py'


def load_module(name, path):
    """고정한 파일에서만 helper를 읽어 sys.path나 이미 로드된 동명 모듈의 영향을 막는다."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


transport = load_module('frozen_transport', old_path)
grader = load_module('frozen_v5_grade', V5 / 'grade.py')
structured = load_module('v6_structured', HERE / 'structured.py')
Inventory, grade = grader.Inventory, grader.grade
SCHEMA, validate_result = structured.SCHEMA, structured.validate_result

PROXY = ROOT / 'experiments/preflight-evaluation/session_mcp.py'
TEMPLATE = '''Review this proposed production change before applying it to {repository} at base {base}.
Your source and compiled-graph scope is the {module} module. All file selectors and answer paths are relative to that module. Other modules and runtime consumers may be unavailable.
Identify up to 64 EXISTING test methods and direct/indirect caller methods worth reviewing. Include concrete evidence and explain why each merits review; do not fill unused slots. Use exact JVM USRs or source declaration signatures with the owner and parameter types. Keep overloads distinct, preserve Kotlin backtick names, and list each declaration once. Put file-only leads and unavailable evidence in unknowns. The supplied production change is NOT applied to source.
Selector examples: source p.Writer.write(String), Kotlin tests TestClass.`descriptive test name`(), or JVM method:p/Writer#write(Ljava/lang/String;)V. Do not invent a hybrid selector.
You have a shared budget of 12 information-tool calls. Use source/graph tools as useful; no information tool is mandatory. Do not edit or run builds/tests. Do not claim runtime safety or guaranteed test failure from static evidence.
Use StructuredOutput to return the final object in this shape:
{{"targets":[{{"file":"module-relative source path","symbol":"exact method USR or source declaration signature","kind":"test|caller|override","reason":"why review","evidence":"observed source lines or graph path"}}],"unknowns":["limitations"],"summary":"short conclusion"}}
<proposed-production-change>
{patch}
</proposed-production-change>
'''


def digest(path):
    return transport.digest(Path(path))


def save(path, data, exclusive=False):
    transport.write_json(path, data, exclusive=exclusive)


def render_prompt(case, patch):
    """실제 UTF-8 크기를 확인한 뒤에만 동결 prompt를 만든다."""
    prompt = TEMPLATE.format(repository=case['repository'], base=case['base'], module=case['module'], patch=patch)
    if len(prompt.encode('utf-8')) > transport.MAX_PROMPT:
        raise ValueError('prepared prompt exceeds byte limit')
    return prompt


def verify(case, binary, java_home):
    command = [str(binary), 'verify-snapshot', '--graph-file', case['snapshot'], '--project', case['sourceRoot'],
               '--input-bindings', case['bindings'], '--snapshot-max-mib', '128']
    result = subprocess.run(command, env=dict(os.environ, JAVA_HOME=java_home), capture_output=True, text=True, timeout=180)
    if result.returncode != 0:
        raise ValueError('snapshot verification failed; model trial not started')
    data = json.loads(result.stdout)
    if data.get('status') != 'matched':
        raise ValueError('matched compiler evidence is required')
    return data


def tooling(binary, plugin):
    provider = shutil.which('claude')
    if provider is None:
        raise ValueError('Claude CLI is required')
    files = [Path(__file__).resolve(), HERE / 'structured.py', HERE / 'output-schema.json',
             HERE / 'assemble.py', HERE / 'report.py', HERE / 'PROTOCOL.md', HERE / 'INTAKE.md',
             HERE / 'cohort.json', V5 / 'grade.py', V5 / 'collect.py', V5 / 'SourceDeclarations.java',
             old_path, PROXY, Path(provider).resolve(), Path(binary), Path(plugin),
             *sorted((Path(binary).parent.parent / 'lib').glob('*.jar'))]
    return {str(path): digest(path) for path in files}


def tooling_error(initial, arm, model):
    """schema 완료 도구 한 개 외에는 기존 정보 도구만 허용한다."""
    if not isinstance(initial, dict):
        return 'missing-claude-init-event'
    if initial.get('model') != model:
        return 'unexpected-provider-model'
    tools = initial.get('tools')
    if not isinstance(tools, list) or tools.count('StructuredOutput') != 1:
        return 'unexpected-claude-tool-surface'
    return transport.tooling_error(dict(initial, tools=[tool for tool in tools if tool != 'StructuredOutput']), arm)


def prepare(config, output):
    output.mkdir(parents=True, exist_ok=False)
    entries = []
    for source in config['cases']:
        case = dict(source)
        base = Path(case['checkout']); module = Path(case['sourceRoot'])
        if module.resolve() != (base / case['module']).resolve():
            raise ValueError('source scope does not match selected module')
        if subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip() != case['base']:
            raise ValueError('base revision mismatch')
        setup = subprocess.check_output(['git', '-C', str(base), 'diff', '--binary', 'HEAD'])
        if hashlib.sha256(setup).hexdigest() != case['setupPatchSha256']:
            raise ValueError('unapproved setup change')
        inventory = Inventory(json.loads(Path(case['inventory']).read_text()))
        case['source'] = transport.source_manifest(module)
        case['snapshotSha256'] = digest(case['snapshot'])
        if json.loads(Path(case['snapshot']).read_text()).get('scope') != case['scope']:
            raise ValueError('snapshot scope label does not match selected module')
        case['bindingsSha256'] = digest(case['bindings'])
        case['inventorySha256'] = digest(case['inventory'])
        case['patchSha256'] = digest(case['patch'])
        case['preparedFreshness'] = verify(case, config['binary'], config['javaHome'])
        patch = Path(case['patch']).read_text()
        case['prompt'] = render_prompt(case, patch)
        case['promptSha256'] = hashlib.sha256(case['prompt'].encode()).hexdigest()
        case['oracleSize'] = len(inventory.oracle)
        case['primaryOracleSize'] = len(inventory.source_oracle)
        entries.append(case)
    if not entries or len({case['id'] for case in entries}) != len(entries):
        raise ValueError('distinct cases required')
    mode = config.get('mode', 'trials')
    if (mode == 'trials' and (len(entries) != 4 or config['repeats'] != 2) or
            mode == 'smoke' and (len(entries) != 1 or config['repeats'] != 1) or mode not in ('trials', 'smoke')):
        raise ValueError('trials require four cases and two repeats; smoke requires one case and one repeat')
    if mode == 'trials':
        cohort = json.loads((HERE / 'cohort.json').read_text())['primary']
        if [(case['id'], case['base']) for case in entries] != [(case['id'], case['base']) for case in cohort]:
            raise ValueError('cases do not match the selected unused cohort')
    # 반대 순서 반복으로 provider 시간 순서와 조건을 완전히 겹치지 않게 한다.
    schedule = [dict(case=case['id'], arm=arm, repeat=repeat) for case in entries for repeat in range(config['repeats'])
                for arm in (['source', 'mcp'] if repeat == 0 else ['mcp', 'source'])]
    manifest = dict(format='kartograph-ai-utility-v6-manifest', version=1, createdAt=time.time(),
                    binary=config['binary'], plugin=config['plugin'], javaHome=config['javaHome'],
                    model='claude-opus-5[1m]', effort='low', maxTools=12, maxPredictions=64, wallSeconds=900,
                    maxBudgetUsd=3, systemPrompt=transport.SYSTEM + ' Final output must be one JSON object only, with no introductory prose or Markdown fences.',
                    outputSchema=SCHEMA, outputChannel='structured_output', outputTool='StructuredOutput',
                    providerCliVersion=subprocess.check_output(['claude', '--version'], text=True, timeout=30).strip(),
                    toolHashes=tooling(config['binary'], config['plugin']),
                    cases=entries, schedule=schedule, mode=config.get('mode', 'trials'),
                    scoring='primary source-addressable oracle recall; incomplete-oracle overlap is not precision; duplicates/ambiguity explicit')
    save(output / 'manifest.json', manifest, exclusive=True)
    return manifest


def check(manifest, selected=None):
    expected = dict(model='claude-opus-5[1m]', effort='low', maxTools=12, maxPredictions=64, wallSeconds=900, maxBudgetUsd=3)
    if any(manifest.get(k) != v for k, v in expected.items()):
        raise ValueError('frozen experiment budget/model contract changed')
    if (manifest.get('format') != 'kartograph-ai-utility-v6-manifest' or manifest.get('outputSchema') != SCHEMA or
            manifest.get('outputChannel') != 'structured_output' or manifest.get('outputTool') != 'StructuredOutput'):
        raise ValueError('frozen structured output contract changed')
    if subprocess.check_output(['claude', '--version'], text=True, timeout=30).strip() != manifest['providerCliVersion']:
        raise ValueError('frozen provider CLI version changed')
    if manifest['toolHashes'] != tooling(manifest['binary'], manifest['plugin']):
        raise ValueError('frozen tooling changed')
    for case in manifest['cases']:
        if selected is not None and case['id'] != selected:
            continue
        if subprocess.check_output(['git', '-C', case['checkout'], 'rev-parse', 'HEAD'], text=True).strip() != case['base']:
            raise ValueError('base revision changed')
        for key in ['snapshot', 'bindings', 'inventory', 'patch']:
            if digest(case[key]) != case[key + 'Sha256']:
                raise ValueError('frozen case artifact changed')
        if transport.source_manifest(Path(case['sourceRoot'])) != case['source']:
            raise ValueError('frozen source changed')
        setup = subprocess.check_output(['git', '-C', case['checkout'], 'diff', '--binary', 'HEAD'])
        if hashlib.sha256(setup).hexdigest() != case['setupPatchSha256']:
            raise ValueError('frozen build setup changed')
        verify(case, manifest['binary'], manifest['javaHome'])


def execute(manifest, output):
    if sorted(p.name for p in output.iterdir()) != ['manifest.json']:
        raise ValueError('execution already started; selective retry is forbidden')
    check(manifest)
    manifest_hash = digest(output / 'manifest.json')
    save(output / 'execution.json', dict(status='running', startedAt=time.time(), trials=0, manifestSha256=manifest_hash), exclusive=True)
    records = []
    try:
        for number, trial in enumerate(manifest['schedule']):
            case = next(c for c in manifest['cases'] if c['id'] == trial['case'])
            if digest(output / 'manifest.json') != manifest_hash:
                raise ValueError('manifest changed during execution')
            check(manifest, case['id'])
            inventory = Inventory(json.loads(Path(case['inventory']).read_text()))
            run = output / f'{number:02d}-{case["id"]}-{trial["arm"]}-{trial["repeat"]}'
            run.mkdir()
            save(run / 'source-manifest.json', case['source'], exclusive=True)
            product = [manifest['binary'], 'mcp', '--graph-file', case['snapshot'], '--project', case['sourceRoot'],
                       '--input-bindings', case['bindings'], '--snapshot-max-mib', '128']
            save(run / 'product-command.json', product, exclusive=True)
            proxy = [str(PROXY), '--repository', case['sourceRoot'], '--trace', str(run / 'tools.jsonl'),
                     '--source-manifest', str(run / 'source-manifest.json'), '--max-tools', '12']
            if trial['arm'] == 'mcp':
                proxy += ['--product-command', str(run / 'product-command.json')]
            config = dict(mcpServers=dict(preflight=dict(command=sys.executable, args=proxy, env=dict(JAVA_HOME=manifest['javaHome']))))
            save(run / 'mcp-config.json', config, exclusive=True)
            command = ['claude', '--print', '--restricted', '--setting-sources', '', '--model', manifest['model'],
                       '--effort', manifest['effort'], '--tools', '', '--disable-slash-commands', '--strict-mcp-config',
                       '--mcp-config', json.dumps(config), '--allowedTools', 'mcp__preflight__*,StructuredOutput', '--no-session-persistence',
                       '--output-format', 'stream-json', '--verbose', '--max-budget-usd', str(manifest['maxBudgetUsd']),
                       '--json-schema', json.dumps(manifest['outputSchema']), '--system-prompt', manifest['systemPrompt']]
            env = dict(os.environ, CLAUDE_CODE_DISABLE_CLAUDE_MDS='1', CLAUDE_CODE_DISABLE_AUTO_MEMORY='1')
            start = time.monotonic()
            outcome = transport.run_provider(command, case['prompt'], env, run, manifest['wallSeconds'])
            elapsed = time.monotonic() - start
            events, result, uses, initial, parse_errors = transport.parse_events(run / 'stream.jsonl')
            errors = []
            surface_error = tooling_error(initial, trial['arm'], manifest['model'])
            allowed = transport.expected_tools(trial['arm']) | {'StructuredOutput'}
            if any(use.get('name') not in allowed for use in uses):
                surface_error = 'unexpected-provider-tool-call'
            if surface_error:
                errors.append(surface_error)
            errors += [e['kind'] for e in parse_errors] + transport.proxy_trace_errors(run / 'tools.jsonl') + outcome['captureFailures']
            for key in ['providerStartFailed', 'timedOut', 'streamOversized', 'stderrOversized']:
                if outcome.get(key):
                    errors.append(key)
            if outcome.get('exit') != 0:
                errors.append('provider-exit')
            if result is None or result.get('is_error'):
                errors.append('provider-result-error-or-missing')
            if len([event for event in events if event.get('type') == 'result']) != 1:
                errors.append('result-event-count')
            answer = result.get('result', '') if result else ''
            document, output_error = validate_result(result)
            integrity_error = None
            try:
                check(manifest, case['id'])
            except Exception as error:
                integrity_error = type(error).__name__
                errors.append('post-trial-verification-failed')
            if output_error or errors:
                scoring = grade('', inventory)
                scoring['error'] = output_error or 'infrastructure-error'
            else:
                scoring = grade(json.dumps(document, ensure_ascii=False), inventory)
            save(run / 'answer.json', dict(text=answer,
                 structuredOutput=result.get('structured_output') if result else None, grading=scoring), exclusive=True)
            record = dict(trial, seconds=elapsed, exit=outcome.get('exit'), infrastructureErrors=sorted(set(errors)),
                          proxyTraceAvailable=(run / 'tools.jsonl').is_file(),
                          postTrialFailureType=integrity_error,
                          providerSubtype=result.get('subtype') if result else None,
                          structuredOutputError=output_error,
                          providerCostUsd=result.get('total_cost_usd') if result else None,
                          usage=result.get('usage') if result else None,
                          toolUses=[dict(name=u.get('name'), input=u.get('input')) for u in uses],
                          informationToolUses=[dict(name=u.get('name'), input=u.get('input')) for u in uses if u.get('name') != 'StructuredOutput'],
                          outputToolUses=[dict(name=u.get('name'), input=u.get('input')) for u in uses if u.get('name') == 'StructuredOutput'],
                          grading=scoring, directory=run.name,
                          evidenceSha256={p.name: digest(p) for p in run.iterdir() if p.is_file()})
            records.append(record); save(output / 'results.json', records)
            print(number, case['id'], trial['arm'], trial['repeat'], 'seconds', round(elapsed, 1),
                  'calls', len(uses), 'valid', scoring['valid'], 'infra', errors, flush=True)
            if surface_error or outcome.get('providerStartFailed') or integrity_error:
                raise ValueError('provider/tool surface invalid; no selective restart')
        save(output / 'execution.json', dict(status='completed', completedAt=time.time(), trials=len(records),
             manifestSha256=manifest_hash, resultsSha256=digest(output / 'results.json')))
    except Exception:
        save(output / 'execution.json', dict(status='failed', completedAt=time.time(), trials=len(records), manifestSha256=manifest_hash))
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args(); output = args.output.resolve()
    if args.execute:
        execute(json.loads((output / 'manifest.json').read_text()), output)
    else:
        if not args.config:
            parser.error('--config is required to prepare')
        prepare(json.loads(args.config.read_text()), output)
        print('Prepared; no model launched')


if __name__ == '__main__':
    main()
