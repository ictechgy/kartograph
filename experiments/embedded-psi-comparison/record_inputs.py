#!/usr/bin/env python3
"""실행한 runtime과 fixture 바이트를 단계별로 기록하며 기존 근거를 덮어쓰지 않는다."""
import hashlib
import json
import os
from pathlib import Path
import sys

here = Path(__file__).resolve().parent
output = Path(sys.argv[1]).resolve()
stage = sys.argv[2]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

runtime = [Path(value) for value in (output / 'semantic-classpath.txt').read_text().strip().split(os.pathsep)]
record = {'stage': stage,
          'runtimeJars': [{'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)} for path in runtime],
          'fixture': {str(path.relative_to(here / 'fixture')): digest(path) for path in sorted((here / 'fixture').rglob('*')) if path.is_file()},
          'adapterSha256': digest(here / 'semantic/PsiResolvedCalls.kt'),
          'expectationsSha256': digest(here / 'compare.py'),
          'fixtureManifestSha256': digest(here / 'fixture-manifest.json'),
          'compiledFixture': {str(path.relative_to(output / 'fixture-classes')): digest(path)
                              for path in sorted((output / 'fixture-classes').rglob('*')) if path.is_file()}}
with (output / (stage + '-inputs.json')).open('x') as stream:
    json.dump(record, stream, indent=2, sort_keys=True)
    stream.write('\n')
