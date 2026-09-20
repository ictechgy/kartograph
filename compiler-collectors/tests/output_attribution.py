#!/usr/bin/env python3
"""실제 javac/KAPT/KSP source·class·resource·직접 쓰기 및 실패 대조를 실행한다."""
from __future__ import annotations
import argparse
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--collector', type=Path, default=ROOT / 'build/libs/kartograph-compiler-collectors.jar')
    parser.add_argument('--reports', type=Path, default=ROOT / 'build/reports/output-attribution')
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
            setup += 'kapt { includeCompileClasspath=false; correctErrorTypes=true; useBuildCache=false; arguments { ' + arguments + ' } }\n' if kind == 'kapt' else 'ksp { ' + arguments + ' }\n'
        (project / 'build.gradle').write_text('plugins { ' + plugins + " }\nrepositories { mavenCentral() }\njava { toolchain { languageVersion=JavaLanguageVersion.of(17) } }\n" + setup)
        (project / 'gradle.properties').write_text('kapt.incremental.apt=false\nksp.incremental=false\n')
        config = {'project': str(project), 'scope': 'fixture:main', 'kind': kind, 'processor': values['kartograph.processor'],
                  'collectorJar': str(collector), 'processorJar': str(processor_jar), 'inputs': ['src', 'build.gradle', 'settings.gradle', 'gradle.properties'],
                  'outputRoots': ['build', 'direct'], 'token': '.evidence/token.pending', 'observations': '.evidence/outputs.tsv', 'receipt': '.evidence/receipt.json',
                  'command': [str(gradle), '--no-daemon', '--no-build-cache', '--no-configuration-cache', '--rerun-tasks', 'classes']}
        config_file = project / 'witness-config-local.json'; config_file.write_text(json.dumps(config))
        value = witness.record(config_file, reports / kind)
        assert witness.verify(config_file)['status'] == 'matched'
        outputs = value['observation']['outputs']
        assert {(row['kind'], row['observation']) for row in outputs} == {('source', 'api'), ('class', 'api'), ('resource', 'api'), ('file', 'callback-scope')}
        assert len(outputs) == 4 and all('Handwritten' not in row['path'] for row in outputs)
        binary = next(row for row in outputs if row['kind'] == 'class')
        assert (project / binary['path']).read_bytes()[:4] == b'\xca\xfe\xba\xbe'
        resource = next(row for row in outputs if row['kind'] == 'resource')
        assert (project / resource['path']).read_text() == 'processor-resource'
        original = source.read_bytes(); source.write_bytes(original + b'\n// changed input\n')
        rejected(lambda: witness.verify(config_file)); source.write_bytes(original)
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
                        'staleControls': ['source', 'output', 'raw', 'scope'], 'failedBuildControls': ['unclosed', 'broken']})
        print(json.dumps(results[-1]), flush=True)
    (reports / 'results.json').write_text(json.dumps({'cases': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
