#!/usr/bin/env python3
"""error 판정 가능 비율 측정 — 합성 클라이언트 호출을 만들어 isthmus check로 판정하고 actuator mappings 오라클로 정밀도를 확인한다.

사용: measure_judgeable.py <isthmus dist/cli/main.js> <server.json> <mappings.json> <label> <out-dir> [<context-path>] [springdoc-h2]
      [--probe=<probe.txt>]

- --probe: 실행 중인 앱에 보낸 요청의 응답 기록(`<METHOD> <app path> <status>` 줄). 404·500이 아닌 요청은 필터(Spring Security 등)가
  받은 것으로 보고 오라클에 더하고 프레임워크 호출(양성 표본)로도 넣는다 — actuator mappings는 서블릿 필터가 받는 경로를 싣지 않는다.

- 음성 표본: 서버 문서의 정적 root 선언 키마다 없는 경로(`…/zz-missing`)와 선언 경로의 다른 method 호출. 오라클의 프로젝트
  핸들러가 받는 호출은 뺀다.
- 양성 표본: 선언 키마다 맞는 호출과 실제로 서비스되는 프레임워크 경로 호출. error가 나면 거짓 error다.
- 정밀도: error가 난 호출을 오라클의 프로젝트·프레임워크 핸들러·서블릿 매핑 전체와 대조한다.
"""
import json
import re
import subprocess
import sys

probe_args = [a for a in sys.argv[1:] if a.startswith('--probe=')]
argv = [sys.argv[0]] + [a for a in sys.argv[1:] if not a.startswith('--probe=')]
isthmus, server_path, mappings_path, label, out_dir = argv[1:6]
ctx = argv[6] if len(argv) > 6 else ''
server = json.load(open(server_path))
mappings = json.load(open(mappings_path))


def oracle_handlers():
    """(methods set or None, regex) — 프로젝트·프레임워크 핸들러와 서블릿 매핑."""
    handlers = []

    def pattern_regex(pattern):
        out = ''
        i = 0
        while i < len(pattern):
            if pattern.startswith('/**', i) and i + 3 == len(pattern):
                out += '(/.*)?'; i += 3; continue
            m = re.match(r'/\{\*[^}]*\}$', pattern[i:])
            if m:
                out += '(/.*)?'; break
            c = pattern[i]
            if c == '{':
                j = pattern.index('}', i)
                body = pattern[i + 1:j]
                out += '(' + body.split(':', 1)[1] + ')' if ':' in body else '[^/]*'
                i = j + 1; continue
            if c == '*':
                out += '[^/]*'; i += 1; continue
            out += re.escape(c); i += 1
        return re.compile('^' + out + '$')

    for context in mappings['contexts'].values():
        m = context['mappings']
        for lst in m.get('dispatcherServlets', {}).values():
            for e in lst:
                det = e.get('details') or {}
                rc = det.get('requestMappingConditions')
                if rc:
                    methods = set(rc['methods']) or None
                    cls = (det.get('handlerMethod') or {}).get('className', '')
                    project = not cls.startswith(('org.springframework.boot.', 'org.springdoc.'))
                    for p in rc['patterns']:
                        handlers.append((methods, pattern_regex(ctx + p), p, project))
                else:
                    p = e['predicate']
                    if p.startswith('/'):
                        handlers.append(({'GET', 'HEAD'}, pattern_regex(ctx + p), p, False))
        for s in m.get('servlets', []):
            for p in s.get('mappings', []):
                if p == '/':
                    continue
                handlers.append((None, pattern_regex(ctx + p.replace('/*', '/**')), p, False))
    return handlers


HANDLERS = oracle_handlers()
PROBED = []  # (method, app path) — 필터가 응답한 요청
for probe in probe_args:
    for line in open(probe.split('=', 1)[1]):
        method, path, status = line.split()
        if status not in ('404', '500'):
            PROBED.append((method, path))
            HANDLERS.append(({method}, re.compile('^' + re.escape(ctx + path) + '$'), path, False))


def served(method, template, project_only=False):
    """오라클 핸들러가 이 요청을 받을 수 있는지다. project_only면 프로젝트 핸들러만 본다."""
    path = template.replace('{}', '1')
    for methods, regex, _, project in HANDLERS:
        if project_only and not project:
            continue
        if regex.match(path) and (methods is None or method in methods or (method == 'HEAD' and 'GET' in methods)):
            return True
    return False


decls = [f for f in server['facts'] if not f['dynamic'] and f['pathAnchor'] == 'root' and not f.get('testSource') and not f.get('catchAllPrefix')]
keys = sorted({(f['method'], f['channel']) for f in decls})
verbs_by_template = {}
for method, template in keys:
    verbs_by_template.setdefault(template, set()).add(method)

calls = []  # (kind, method, template)
for method, template in keys:
    call_template = template.replace('{**}', 'a/b')
    calls.append(('positive', 'GET' if method == 'ANY' else method, call_template))
    calls.append(('missing-path', 'POST' if method == 'ANY' else method, call_template.rstrip('/') + '/zz-missing'))
for template, verbs in sorted(verbs_by_template.items()):
    if 'ANY' in verbs:
        continue
    other = next(v for v in ['DELETE', 'PATCH', 'PUT', 'POST'] if v not in verbs)
    calls.append(('method-mismatch', other, template.replace('{**}', 'a/b')))
framework_calls = [('GET', '/actuator/health'), ('POST', '/error'), ('GET', '/webjars/app.css'), ('GET', '/favicon.ico')]
framework_calls += PROBED
if len(argv) > 7 and argv[7] == 'springdoc-h2':
    framework_calls += [('GET', '/v3/api-docs'), ('GET', '/swagger-ui/index.html'), ('POST', '/h2-console/login.do')]
for method, template in framework_calls:
    calls.append(('framework', method, ctx + template))

facts = []
for index, (kind, method, template) in enumerate(calls):
    facts.append({
        'kind': 'route-call', 'method': method, 'channel': template, 'dynamic': False, 'pathAnchor': 'root', 'target': 'http',
        'location': {'path': 'client/src/main/kotlin/synthetic/Calls.kt', 'line': index + 1, 'column': 1},
        'symbol': {'qualifiedName': f'synthetic.Calls.{kind.replace("-", "_")}{index}'},
    })
client = {
    'format': 'bridge-facts', 'version': 1, 'tool': {'name': 'synthetic', 'version': '1'}, 'generatedAt': '2026-09-29T00:00:00.000Z',
    'platform': 'kotlin', 'target': 'http', 'project': server['project'], 'facts': facts, 'limitations': [], 'roles': ['client'],
    'sourceSets': {'tests': 'excluded'},
}
client_path = f'{out_dir}/{label}-client.json'
json.dump(client, open(client_path, 'w'), indent=1)
result = subprocess.run(['node', isthmus, 'check', server_path, client_path, '--format', 'json'], capture_output=True, text=True, timeout=120)
if result.returncode not in (0, 1):
    sys.exit(f'isthmus check failed ({result.returncode}): {result.stderr[:500]}')
report = json.loads(result.stdout)
json.dump(report, open(f'{out_dir}/{label}-check.json', 'w'), indent=1)
by_call = {}
for diagnostic in report['issues']:
    if diagnostic.get('target') != 'http':
        continue
    by_call.setdefault((diagnostic.get('method'), diagnostic.get('channel')), []).append((diagnostic['code'], diagnostic['severity']))

rows = {'error': 0, 'unverified': 0, 'matched': 0}
negatives = 0
negatives_get = 0
errors_get = 0
false_errors = []
positive_errors = []
for kind, method, template in calls:
    diagnostics = by_call.get((method, template), [])
    is_error = any(sev == 'error' for _, sev in diagnostics)
    really_served = served(method, template)
    if is_error and really_served:
        false_errors.append((kind, method, template, diagnostics))
    if kind in ('positive', 'framework'):
        if is_error:
            positive_errors.append((kind, method, template, diagnostics))
        continue
    if served(method, template, project_only=True):
        continue  # 프로젝트 선언이 받는 요청은 음성 표본이 아니다
    negatives += 1
    if method in ('GET', 'HEAD'):
        negatives_get += 1
        errors_get += is_error
    if is_error:
        rows['error'] += 1
    elif any(code.endswith('-unverified') for code, _ in diagnostics):
        rows['unverified'] += 1
    else:
        rows['matched'] += 1
errors = sum(1 for d in report['issues'] if d.get('severity') == 'error')
print(json.dumps({
    'label': label, 'calls': len(calls), 'undeclaredNegatives': negatives, 'getHeadNegatives': negatives_get, 'getHeadErrors': errors_get, 'judgeable': rows['error'],
    'ratio': round(rows['error'] / negatives, 4) if negatives else None, 'unverified': rows['unverified'], 'noDiagnostic': rows['matched'],
    'errorsTotal': errors, 'falseErrors': len(false_errors), 'positiveErrors': len(positive_errors),
    'precision': (1.0 if errors and not false_errors else (None if not errors else round(1 - len(false_errors) / errors, 4))),
}))
for item in false_errors + positive_errors:
    print('  FALSE', item)
