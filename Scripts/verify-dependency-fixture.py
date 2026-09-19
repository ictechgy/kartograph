#!/usr/bin/env python3
"""배포 plugin JAR을 별도 Gradle/AGP 소비 프로젝트에 적용해 dependency task 계약을 검증한다."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--gradle', required=True)
parser.add_argument('--plugin-jar', required=True)
parser.add_argument('--android', action='store_true')
parser.add_argument('--agp', default='8.7.3')
parser.add_argument('--output', required=True)
args = parser.parse_args()
gradle = str(Path(args.gradle).resolve())
plugin = Path(args.plugin_jar).resolve()
if not plugin.is_file():
    parser.error('Build the plugin JAR before running this verifier')
output = Path(args.output).resolve()
output.mkdir(parents=True, exist_ok=True)

def literal(value):
    return str(value).replace('\\', '\\\\').replace("'", "\\'")

with tempfile.TemporaryDirectory(prefix='kartograph-dependency-fixture-') as temporary:
    root = Path(temporary)
    def write(name, text):
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    write('settings.gradle', "pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }\nrootProject.name='dependency-consumer'\ninclude 'library'\n")
    write('library/build.gradle', "plugins { id 'java-library' }\ngroup='fixture'; version='1'\njava { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }\n")
    write('library/src/main/java/lib/Api.java', 'package lib; public class Api {}\n')
    if args.android:
        mode = """
            apply plugin: 'com.android.library'
            android {
              namespace 'fixture.consumer'
              compileSdk 35
              defaultConfig { minSdk 23 }
              compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
            }
        """
        agp = "classpath 'com.android.tools.build:gradle:" + args.agp + "'"
        task = 'kartographDependenciesDebug'
        report = 'debug-dependencies.txt'
        write('src/main/AndroidManifest.xml', '<manifest/>\n')
    else:
        mode = "apply plugin: 'java-library'"
        agp = ''
        task = 'kartographDependencies'
        report = 'jvm-dependencies.txt'
    write('build.gradle', """
        buildscript {
          repositories { google(); mavenCentral() }
          dependencies { classpath files('PLUGIN'); AGP }
        }
        MODE
        apply plugin: 'io.github.ictechgy.kartograph'
        repositories { google(); mavenCentral() }
        dependencies { implementation project(':library') }
        kartograph {
          reportFormat = 'json'
          strict = providers.gradleProperty('strictDeps').map { it.toBoolean() }.orElse(false)
        }
        tasks.withType(Test).configureEach { doFirst { throw new GradleException('dependency analysis must not run tests') } }
    """.replace('PLUGIN', literal(plugin)).replace('AGP', agp).replace('MODE', mode))
    write('src/main/java/app/PublicApi.java', 'package app; public class PublicApi { public java.util.List<lib.Api> values; }\n')
    results = []
    def run(name, strict=False, expected=0):
        command = [gradle, '--no-daemon', '--console=plain', '--configuration-cache', '--max-workers=2', '-p', str(root), task]
        if strict:
            command.append('-PstrictDeps=true')
        result = subprocess.run(command, text=True, capture_output=True, timeout=360)
        text = result.stdout + result.stderr
        (output / (name + '.log')).write_text(text.replace(str(root), '<fixture>'))
        if (result.returncode == 0) != (expected == 0):
            raise RuntimeError('dependency fixture step failed: ' + name + '; inspect its log')
        results.append({'step': name, 'exitCode': result.returncode})
        return text
    run('first')
    document = json.loads((root / 'build/reports/kartograph' / report).read_text())
    findings = document['diagnostics']
    assert len(findings) == 1, findings
    assert findings[0]['ruleId'] == 'dependency-scope-mismatch', findings
    assert findings[0]['suggestedScope'] == 'api', findings
    assert findings[0]['evidenceClasses'] == ['lib/Api'], findings
    text = run('cache')
    assert 'Reusing configuration cache' in text
    run('strict', strict=True, expected=1)
    assert (root / 'build/reports/kartograph' / report).is_file()
    build = root / 'build.gradle'
    build.write_text(build.read_text().replace("implementation project(':library')", "api project(':library')"))
    run('changed-scope')
    assert json.loads((root / 'build/reports/kartograph' / report).read_text())['diagnostics'] == []
    (output / 'result.json').write_text(json.dumps({'status': 'passed', 'android': args.android, 'agp': args.agp if args.android else None, 'steps': results}, indent=2) + '\n')
print('Dependency fixture verified: ' + ('Android ' + args.agp if args.android else 'JVM') + ', configuration cache, strict report and scope invalidation')
