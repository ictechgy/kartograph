#!/usr/bin/env python3
"""실제 javac/KAPT/KSP source·class·resource·직접 쓰기 및 실패 대조를 실행한다."""
from __future__ import annotations
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('witness', ROOT / 'processor_output_witness.py')
witness = importlib.util.module_from_spec(spec); spec.loader.exec_module(witness)


def quote(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'") + "'"


def command(args, cwd, log):
    with log.open('wb') as output:
        value = subprocess.run(list(map(str, args)), cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=600)
    if value.returncode:
        raise AssertionError('fixture preparation failed; inspect local build log')


def rejected(action):
    try:
        action()
    except (witness.EvidenceError, OSError):
        return
    raise AssertionError('invalid evidence was accepted')


def archive_compiler_inputs(config, project, saved_project, reports):
    """관찰은 유지하고 동일 bytes를 보존한 경로로 로컬 binding만 다시 연결한다."""
    source = project / config['compilerInputs']
    shutil.copyfile(source, reports / (config['kind'] + '-compiler-inputs-original-local.tsv'))
    rows = []
    for row in source.read_text().splitlines():
        fields = row.split('\t')
        if fields[0] == 'file':
            name, original, kind, fingerprint = witness.decode(fields[1]), Path(witness.decode(fields[2])), fields[3], fields[4]
            target = reports / 'compiler-input-artifacts' / fingerprint if name.startswith('external/') else saved_project / name.removeprefix('project/')
            if kind != 'missing': target.parent.mkdir(parents=True, exist_ok=True)
            if kind != 'missing' and target.exists(): assert witness.inventory(target) == fingerprint
            elif kind == 'directory': shutil.copytree(original, target)
            elif kind == 'file': shutil.copyfile(original, target)
            elif name.startswith('external/'): target = reports / 'missing-inputs' / config['kind'] / name
            if kind != 'missing': assert witness.inventory(target) == fingerprint
            fields[2] = base64.urlsafe_b64encode(str(target).encode()).decode().rstrip('=')
        rows.append('\t'.join(fields))
    target = saved_project / config['compilerInputs']; target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('\n'.join(rows) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collector', type=Path, default=ROOT / 'build/libs/kartograph-compiler-collectors.jar')
    parser.add_argument('--reports', type=Path, default=os.environ.get('KARTOGRAPH_OUTPUT_REPORTS', ROOT / 'build/reports/output-attribution'))
    parser.add_argument('--snapshot-cli', type=Path, default=os.environ.get('KARTOGRAPH_SNAPSHOT_CLI'))
    args = parser.parse_args()
    reports = args.reports.resolve(); reports.mkdir(parents=True, exist_ok=True)
    (reports / 'results.json').unlink(missing_ok=True)
    collector = args.collector.resolve()
    gradle = ROOT.parent / 'gradlew'
    scratch = Path(tempfile.mkdtemp(prefix='kartograph-processor-outputs-')).resolve()
    # 실패 때도 원본 입력과 단계 로그를 보존한다. build/cache는 최종 증거 수집 후 별도 정리한다.
    (reports / 'fixture-location-local.txt').write_text(str(scratch))
    processor = scratch / 'processor'; (processor / 'src/main/java/fixture').mkdir(parents=True)
    (processor / 'src/main/kotlin/fixture').mkdir(parents=True)
    for name in ('OutputProcessor.java', 'OutputBinary.java'):
        shutil.copyfile(ROOT / 'tests/fixtures' / name, processor / 'src/main/java/fixture' / name)
    shutil.copyfile(ROOT / 'tests/fixtures/OutputProvider.kt', processor / 'src/main/kotlin/fixture/OutputProvider.kt')
    (processor / 'settings.gradle').write_text("rootProject.name='processor-fixture'\n")
    (processor / 'build.gradle').write_text("plugins { id 'org.jetbrains.kotlin.jvm' version '2.4.10' }\nrepositories { mavenCentral() }\nkotlin { jvmToolchain(17) }\ndependencies { compileOnly 'com.google.devtools.ksp:symbol-processing-api:2.3.12' }\n")
    command([gradle, '--no-daemon', 'jar'], processor, reports / 'processor-build.log')
    processor_jar = processor / 'build/libs/processor-fixture.jar'
    saved_artifacts = reports / 'processor-artifacts'; saved_artifacts.mkdir(exist_ok=True)
    shutil.copyfile(collector, saved_artifacts / 'collector.jar')
    shutil.copyfile(processor_jar, saved_artifacts / 'processor.jar')
    selector = scratch / 'selector.jar'
    with zipfile.ZipFile(selector, 'w') as archive:
        archive.writestr('META-INF/services/javax.annotation.processing.Processor', 'dev.kartograph.collectors.OutputRecordingProcessor\n')
    results = []
    for kind in ('javac', 'kapt', 'ksp'):
        project = scratch / kind
        sources = project / ('src/main/java/fixture' if kind == 'javac' else 'src/main/kotlin/fixture')
        sources.mkdir(parents=True); (project / 'direct').mkdir()
        (project / 'direct/Handwritten.txt').write_text('unchanged handwritten control')
        source = sources / ('Input.java' if kind == 'javac' else 'Input.kt')
        source.write_text('package fixture; class Input { int value(){return Generated.value();} }\n' if kind == 'javac' else 'package fixture\nclass Input { fun value() = Generated.value() }\n')
        (project / 'settings.gradle').write_text("pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name='output-attribution'\n")
        plugins = "id 'java'" if kind == 'javac' else "id 'org.jetbrains.kotlin.jvm' version '2.4.10'; " + ("id 'org.jetbrains.kotlin.kapt' version '2.4.10'" if kind == 'kapt' else "id 'com.google.devtools.ksp' version '2.3.12'")
        values = {'kartograph.processor': 'fixture.OutputProvider' if kind == 'ksp' else 'fixture.OutputProcessor',
                  'kartograph.outputs.kind': kind, 'kartograph.outputs.root': project.as_uri(),
                  'kartograph.outputs.output': (project / '.evidence/outputs.tsv').as_uri(),
                  'kartograph.outputs.token': (project / '.evidence/token.pending').as_uri(),
                  'kartograph.outputs.directRoot': str(project / 'direct')}
        files = ','.join(map(quote, [collector, processor_jar, selector]))
        if kind == 'javac':
            setup = "tasks.named('compileJava') { options.annotationProcessorPath=files(" + files + "); options.incremental=false; options.compilerArgs.addAll(" + ','.join(quote('-A' + key + '=' + value) for key, value in values.items()) + "); options.compilerArgs.add('-Afixture.mode='+providers.gradleProperty('fixtureMode').getOrElse('normal')) }\n"
        else:
            method = 'kapt' if kind == 'kapt' else 'ksp'
            setup = 'dependencies { ' + method + ' files(' + files + ') }\n'
            arguments = ';'.join('arg(' + quote(key) + ',' + quote(value) + ')' for key, value in values.items()) + ";arg('fixture.mode',providers.gradleProperty('fixtureMode').getOrElse('normal'))"
            setup += 'kapt { includeCompileClasspath=false; correctErrorTypes=true; useBuildCache=true; arguments { ' + arguments + ' } }\n' if kind == 'kapt' else 'ksp { ' + arguments + ' }\n'
        cache_task = {'javac': 'compileJava', 'kapt': 'kaptKotlin', 'ksp': 'kspKotlin'}[kind]
        # 수동 runner 입력 밖의 실제 compile classpath도 새 task inventory가 추적해야 한다.
        shutil.copyfile(processor_jar, project / 'compiler-only.jar')
        setup += "dependencies { compileOnly files('compiler-only.jar') }\n"
        (project / 'external').mkdir()
        (project / 'external/compiler-input-0').write_text('project file sharing the external slot spelling')
        setup += "tasks.configureEach { task -> if (task.name == " + quote(cache_task) + ") { task.inputs.file('external/compiler-input-0').withPropertyName('projectExternalInput') } }\n"
        shutil.copyfile(ROOT / 'processor_output_cache.gradle', project / 'processor-output-cache.gradle')
        setup += "apply from: 'processor-output-cache.gradle'\nregisterProcessorOutputCache(" + quote(cache_task) + ", file('witness-config-local.json'))\n"
        (project / 'build.gradle').write_text('plugins { ' + plugins + " }\nrepositories { mavenCentral() }\njava { toolchain { languageVersion=JavaLanguageVersion.of(17) } }\n" + setup)
        (project / 'gradle.properties').write_text('kapt.incremental.apt=false\nksp.incremental=false\n')
        config = {'project': str(project), 'scope': 'fixture:main', 'kind': kind, 'processor': values['kartograph.processor'],
                  'collectorJar': str(collector), 'processorJar': str(processor_jar), 'inputs': ['src', 'build.gradle', 'settings.gradle', 'gradle.properties', 'processor-output-cache.gradle'],
                  'outputRoots': ['build', 'direct'], 'token': '.evidence/token.pending', 'observations': '.evidence/outputs.tsv', 'receipt': '.evidence/receipt.json',
                  'cacheOutputs': ['direct/direct.txt'],
                  'compilerInputs': '.evidence/compiler-inputs.tsv',
                  'command': [str(gradle), '--no-daemon', '--build-cache', '--configuration-cache', 'classes']}
        config_file = project / 'witness-config-local.json'; config_file.write_text(json.dumps(config))
        value = witness.record(config_file, reports / kind)
        assert witness.verify(config_file)['status'] == 'matched'
        assert value['version'] == 3
        assert any(item['path'] == 'project/compiler-only.jar' for item in value['observation']['compilerInputs']['files'])
        assert any(item['path'] == 'project/external/compiler-input-0' for item in value['observation']['compilerInputs']['files'])
        assert any(item['path'] == 'external/compiler-input-0' for item in value['observation']['compilerInputs']['files'])
        outputs = value['observation']['outputs']
        assert {(row['kind'], row['observation']) for row in outputs} == {('source', 'api'), ('class', 'api'), ('resource', 'api'), ('file', 'callback-scope')}
        assert len(outputs) == 4 and all('Handwritten' not in row['path'] for row in outputs)
        direct = next(row for row in outputs if row['observation'] == 'callback-scope')
        assert config['cacheOutputs'] == [direct['path']]
        # 실제 native task cache에서 raw와 4종 출력을 함께 복원해야 한다.
        for row in outputs:
            (project / row['path']).unlink()
        restored = witness.record(config_file, reports / (kind + '-cache-restored'))
        assert restored == value
        cache_log = (reports / (kind + '-cache-restored/build.stdout')).read_text()
        assert f':{cache_task} FROM-CACHE' in cache_log
        assert 'Reusing configuration cache.' in cache_log or 'Configuration cache entry reused.' in cache_log
        assert (project / 'direct/Handwritten.txt').read_text() == 'unchanged handwritten control'
        if args.snapshot_cli:
            roots = [path for path in [project / 'build/classes/java/main', project / 'build/classes/kotlin/main',
                                      project / 'build/tmp/kapt3/classes/main', project / 'build/generated/ksp/main/classes'] if path.is_dir()]
            binary_output = next(row for row in outputs if row['kind'] == 'class')
            roots.append((project / binary_output['path']).parent.parent)
            snapshot_command = [str(args.snapshot_cli.resolve()), 'snapshot', '--project', str(project), '--scope', config['scope']]
            for path in dict.fromkeys(roots): snapshot_command += ['--classes', str(path)]
            baseline = subprocess.run(snapshot_command, capture_output=True, text=True, timeout=120)
            assert baseline.returncode == 0, 'baseline snapshot failed'
            captured = subprocess.run(snapshot_command + ['--processor-output-config', str(config_file)], capture_output=True, text=True, timeout=120)
            assert captured.returncode == 0, 'processor output snapshot failed'
            document = json.loads(captured.stdout)
            assert document['graph'] == json.loads(baseline.stdout)['graph']
            assert document['retention'] == json.loads(baseline.stdout)['retention']
            assert document['processorOutputs'][0]['outputs'] == outputs
            assert document['processorOutputs'][0]['scope'] == config['scope']
            assert document['processorOutputs'][0]['compilerInputs'] == value['observation']['compilerInputs']
            declarations = document['processorOutputs'][0]['declarations']
            assert len(declarations) == 1 and declarations[0]['owner'] == 'class:fixture/OutputBinary'
            assert any(symbol.startswith('method:fixture/OutputBinary#') for symbol in declarations[0]['symbols'])
            (reports / (kind + '-snapshot.json')).write_text(captured.stdout)
            changed = project / outputs[0]['path']; original_output = changed.read_bytes(); changed.write_bytes(b'stale')
            stale = subprocess.run(snapshot_command + ['--processor-output-config', str(config_file)], capture_output=True, text=True, timeout=120)
            assert stale.returncode == 2 and not stale.stdout
            changed.write_bytes(original_output)
        binary = next(row for row in outputs if row['kind'] == 'class')
        assert (project / binary['path']).read_bytes()[:4] == b'\xca\xfe\xba\xbe'
        resource = next(row for row in outputs if row['kind'] == 'resource')
        assert (project / resource['path']).read_text() == 'processor-resource'
        original = source.read_bytes(); source.write_bytes(original + b'\n// changed input\n')
        rejected(lambda: witness.verify(config_file)); source.write_bytes(original)
        dependency = project / 'compiler-only.jar'; original = dependency.read_bytes()
        stamp = dependency.stat(); dependency.write_bytes(original + b'changed classpath bytes'); os.utime(dependency, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        rejected(lambda: witness.verify(config_file))
        rejected(lambda: witness.record(config_file, reports / (kind + '-classpath-cache-rejected')))
        assert not (project / config['receipt']).exists()
        assert f':{cache_task} FROM-CACHE' in (reports / (kind + '-classpath-cache-rejected/build.stdout')).read_text()
        dependency.write_bytes(original)
        assert witness.record(config_file, reports / (kind + '-classpath-restored')) == value
        compiler_report = project / config['compilerInputs']; original = compiler_report.read_text()
        compiler_report.write_text(original.replace('propertiesSha256\t', 'unknownProperties\t'))
        rejected(lambda: witness.verify(config_file)); compiler_report.write_text(original)
        output = project / binary['path']; original = output.read_bytes(); output.write_bytes(original + b'x')
        rejected(lambda: witness.verify(config_file)); output.write_bytes(original)
        raw = project / config['observations']; original = raw.read_bytes(); raw.write_bytes(original.replace(b'processorArtifact\t', b'unknownArtifact\t'))
        rejected(lambda: witness.verify(config_file)); raw.write_bytes(original)
        config['scope'] = 'fixture:other'; config_file.write_text(json.dumps(config))
        rejected(lambda: witness.verify(config_file)); config['scope'] = 'fixture:main'; config_file.write_text(json.dumps(config))
        assert witness.verify(config_file)['status'] == 'matched'
        shutil.copyfile(project / config['receipt'], reports / (kind + '-receipt.json'))
        # 뒤의 실패 빌드가 생성물을 덮어쓰기 전에 성공한 원본 bytes를 보존한다.
        saved_project = reports / (kind + '-success')
        for row in outputs:
            saved = saved_project / row['path']; saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(project / row['path'], saved)
            assert witness.digest(saved) == row['sha256']
        for name in [*config['inputs'], config['observations'], config['receipt']]:
            saved = saved_project / name; saved.parent.mkdir(parents=True, exist_ok=True)
            if (project / name).is_dir(): shutil.copytree(project / name, saved, dirs_exist_ok=True)
            else: shutil.copyfile(project / name, saved)
        archive_compiler_inputs(config, project, saved_project, reports)
        archived_config = reports / (kind + '-success-config-local.json')
        archived_config.write_text(json.dumps({**config, 'project': str(saved_project),
            'collectorJar': str(saved_artifacts / 'collector.jar'), 'processorJar': str(saved_artifacts / 'processor.jar')}))
        assert witness.verify(archived_config)['status'] == 'matched'
        for mode in ('unclosed', 'broken'):
            config['command'].append('-PfixtureMode=' + mode); config_file.write_text(json.dumps(config))
            rejected(lambda: witness.record(config_file, reports / (kind + '-' + mode)))
            assert not (project / config['receipt']).exists()
            config['command'].pop()
        assert witness.verify(archived_config)['status'] == 'matched'
        results.append({'kind': kind, 'outputs': 4, 'sourceClassResourceDirect': True, 'handwrittenExcluded': True,
                        'staleControls': ['source', 'output', 'raw', 'scope', 'declared-classpath', 'normalized-cache-classpath', 'compiler-properties'], 'failedBuildControls': ['unclosed', 'broken'],
                        'nativeTaskRestoredFromCache': cache_task, 'configurationCacheReused': True,
                        'declaredCompilerInputs': len(value['observation']['compilerInputs']['files']),
                        'snapshotMetadataVerified': args.snapshot_cli is not None})
        print(json.dumps(results[-1]), flush=True)
    (reports / 'results.json').write_text(json.dumps({'cases': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
