#!/usr/bin/env python3
"""공식 Gradle 8.13에서 실제 SafeArgs task를 관측한다. 제품 지원/AGP 증명이 아니다."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import subprocess
import time
import urllib.request
import zipfile

VERSION = "8.13"
DISTRIBUTION = f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip"
DISTRIBUTION_SHA = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
PINNED_DISTRIBUTIONS = {
    "8.13": DISTRIBUTION_SHA,
    "9.7.1": "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a",
}
PLUGIN_SHA = "5e585dfe55474ce5361f2759faf9afd4a12a7e6a8259c7371bab1b7e8a846c56"
GENERATOR_SHA = "d4eb204430ce5e350799981cbac0941cea76cf6feeffd959878363bfc13b5c3d"
ACTION = "org.gradle.api.internal.project.taskfactory.IncrementalTaskAction"
TASK = "androidx.navigation.safeargs.gradle.ArgumentsGenerationTask"
MAX_ARCHIVE = 200 * 1024 * 1024
MAX_EXPANDED = 512 * 1024 * 1024
MAX_JSON = 64 * 1024
EXPECTED_SOURCES = {"p/StartFragmentDirections.java", "p/StartFragmentArgs.java", "p/EndFragmentArgs.java"}

NAVIGATION = '''<navigation xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
 android:id="@+id/graph" app:startDestination="@id/start">
 <fragment android:id="@+id/start" android:name="p.StartFragment">
  <argument android:name="seed" app:argType="integer" android:defaultValue="11" />
  <action android:id="@+id/go" app:destination="@id/end" />
  <action android:id="@+id/idle" app:destination="@id/end" />
 </fragment>
 <fragment android:id="@+id/end" android:name="p.EndFragment"><argument android:name="value" app:argType="integer" /></fragment>
</navigation>
'''

# Android plugin을 적용하지 않고, 공개된 실제 task와 Gradle InputChanges를 실행한다.
BUILD = r'''import androidx.navigation.safeargs.gradle.ArgumentsGenerationTask
import androidx.navigation.safe.args.generator.NavSafeArgsGenerator
import groovy.json.JsonOutput
import java.security.MessageDigest
buildscript {
 repositories { google(); mavenCentral() }
 dependencies { classpath 'androidx.navigation:navigation-safe-args-gradle-plugin:2.10.2' }
}
def generation = tasks.register('generateSafeArgsDebug', ArgumentsGenerationTask) {
 applicationId.set('p.safeargs'); getRFilePackage().set('p')
 useAndroidX.set(true); generateKotlin.set(false)
 navigationFiles.from(layout.projectDirectory.file('src/debug/res/navigation/graph.xml'))
 outputDir.set(layout.buildDirectory.dir('generated'))
 incrementalFolder.set(layout.buildDirectory.dir('mapping'))
}
tasks.register('observe') {
 dependsOn(generation)
 doLast {
  def task = generation.get()
  def fingerprint = { Class type ->
   def location = type.protectionDomain.codeSource?.location
   if (location == null || location.protocol != 'file') throw new GradleException('missing file CodeSource')
   def file = new File(location.toURI())
   if (!file.isFile() || file.length() > 32L * 1024 * 1024) throw new GradleException('bounded artifact unavailable')
   [type:type.name, name:file.name, bytes:file.length(), sha256:MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()]
  }
  def relative = { File file ->
   def root = projectDir.toPath().toRealPath(); def path = file.toPath().toRealPath()
   if (!path.startsWith(root)) throw new GradleException('provider outside fixture')
   root.relativize(path).toString().replace('\\','/')
  }
  def actions = task.actions.collect { action ->
   def row = fingerprint(action.class)
   def source = new File(action.class.protectionDomain.codeSource.location.toURI()).toPath().toRealPath()
   def home = gradle.gradleHomeDir.toPath().toRealPath()
   if (!source.startsWith(home)) throw new GradleException('action outside official Gradle distribution')
   row.distributionPath = home.relativize(source).toString().replace('\\','/')
   row
  }
  def state = task.state
  def value = [gradle:gradle.gradleVersion, java:JavaVersion.current().toString(),
   javaRuntimeVersion:System.getProperty('java.runtime.version'), javaVendor:System.getProperty('java.vendor'), task:task.path,
   taskType:ArgumentsGenerationTask.name, artifacts:[plugin:fingerprint(ArgumentsGenerationTask), generator:fingerprint(NavSafeArgsGenerator)],
   actions:actions, state:[executed:state.executed, skipped:state.skipped, upToDate:state.upToDate,
    didWork:state.didWork, failure:state.failure == null ? null : state.failure.class.name, skipMessage:state.skipMessage],
   providers:[navigationFiles:task.navigationFiles.files.collect(relative).sort(),
    allInputFiles:task.inputs.files.files.collect(relative).sort(), outputDir:relative(task.outputDir.get().asFile),
    incrementalFolder:relative(task.incrementalFolder.get().asFile), applicationId:task.applicationId.get(),
    rFilePackage:task.getRFilePackage().get(), useAndroidX:task.useAndroidX.get(), generateKotlin:task.generateKotlin.get()]]
  def file = layout.buildDirectory.file('observation.json').get().asFile
  file.parentFile.mkdirs(); file.setText(JsonOutput.toJson(value), 'UTF-8')
 }
}
'''


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def portable(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value or ":" in value:
        raise ValueError("nonportable path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(p in (".", "..", "") for p in value.split("/")):
        raise ValueError("escaping path")
    return value


def read_json(path: Path) -> object:
    if path.is_symlink() or path.stat().st_size > MAX_JSON:
        raise ValueError("observation JSON exceeds bound or is a link")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)


def distribution_entries(z: zipfile.ZipFile, *, version: str = VERSION) -> list[zipfile.ZipInfo]:
    """두 고정 fixture가 공유하는 archive 경계 검사다. 인증 SHA 검사는 호출자가 유지한다."""
    if version not in PINNED_DISTRIBUTIONS:
        raise ValueError("unregistered Gradle fixture version")
    entries = z.infolist()
    if len(entries) > 2000 or sum(i.file_size for i in entries) > MAX_EXPANDED:
        raise ValueError("expanded Gradle distribution exceeds bound")
    names = set()
    for info in entries:
        name = portable(info.filename.rstrip("/"))
        if name in names or not (name.startswith(f"gradle-{version}/") or (name == f"gradle-{version}" and info.is_dir())):
            raise ValueError("unexpected or duplicate distribution entry")
        names.add(name)
        mode = info.external_attr >> 16
        if stat.S_ISLNK(mode) or info.flag_bits & 1:
            raise ValueError("linked or encrypted distribution entry")
    return entries


def unpack(archive: Path, destination: Path, expected_sha: str = DISTRIBUTION_SHA, *, version: str = VERSION) -> Path:
    if archive.is_symlink() or archive.stat().st_size > MAX_ARCHIVE or digest(archive) != expected_sha:
        raise ValueError("official Gradle distribution checksum mismatch or oversized archive")
    with zipfile.ZipFile(archive) as z:
        entries = distribution_entries(z, version=version)
        destination.mkdir(exist_ok=False)
        for info in entries:
            target = destination / info.filename
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as source, target.open("xb") as output:
                    for part in iter(lambda: source.read(1024 * 1024), b""):
                        output.write(part)
                if os.name != "nt":
                    target.chmod(0o755 if (info.external_attr >> 16) & 0o111 else 0o644)
    return destination / f"gradle-{version}"


def download(archive: Path, *, version: str = VERSION) -> None:
    if version not in PINNED_DISTRIBUTIONS:
        raise ValueError("unregistered Gradle fixture version")
    url = DISTRIBUTION if version == VERSION else f"https://services.gradle.org/distributions/gradle-{version}-bin.zip"
    with urllib.request.urlopen(url, timeout=60) as response, archive.open("xb") as target:
        size = 0
        for part in iter(lambda: response.read(1024 * 1024), b""):
            size += len(part)
            if size > MAX_ARCHIVE:
                raise ValueError("Gradle download exceeds bound")
            target.write(part)


def inventory(root: Path) -> dict:
    result = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("linked output")
        if path.is_file():
            if len(result) >= 16 or path.stat().st_size > 1024 * 1024:
                raise ValueError("generated output exceeds bound")
            name = portable(path.relative_to(root).as_posix())
            result[name] = {"sha256": digest(path), "bytes": path.stat().st_size}
    return result


def validate_observation(value: object, gradle_home: Path, phase: str) -> None:
    if phase not in ("cold", "warm"):
        raise ValueError("unknown observation phase")
    if not isinstance(value, dict):
        raise ValueError("observation must be a JSON object")
    if value["gradle"] != VERSION or value["java"] != "17" or value["task"] != ":generateSafeArgsDebug" or value["taskType"] != TASK:
        raise ValueError("wrong observed toolchain or task")
    if any(not isinstance(value[k], str) or not 0 < len(value[k]) <= 128 for k in ("javaRuntimeVersion", "javaVendor")):
        raise ValueError("missing bounded Java runtime identity")
    for role, expected, kind in [("plugin", PLUGIN_SHA, TASK), ("generator", GENERATOR_SHA, "androidx.navigation.safe.args.generator.NavSafeArgsGenerator")]:
        item = value["artifacts"][role]
        if item["sha256"] != expected or item["type"] != kind or not 0 < item["bytes"] <= 32 * 1024 * 1024:
            raise ValueError("wrong actual generator artifact")
        portable(item["name"])
    actions = value["actions"]
    if len(actions) != 1 or actions[0]["type"] != ACTION:
        raise ValueError("unexpected original generator actions")
    action = actions[0]
    relative = portable(action["distributionPath"])
    artifact = gradle_home / relative
    if artifact.is_symlink() or not artifact.is_file() or artifact.stat().st_size != action["bytes"] or digest(artifact) != action["sha256"] or artifact.name != action["name"]:
        raise ValueError("action CodeSource does not match official distribution bytes")
    with zipfile.ZipFile(artifact) as jar:
        if ACTION.replace(".", "/") + ".class" not in jar.namelist():
            raise ValueError("action class absent from observed CodeSource")
    expected_state = {"executed": True, "skipped": phase == "warm", "upToDate": phase == "warm", "didWork": phase == "cold", "failure": None, "skipMessage": "UP-TO-DATE" if phase == "warm" else None}
    if value["state"] != expected_state:
        raise ValueError("actual generator lifecycle did not match required phase")
    expected_providers = {"navigationFiles": ["src/debug/res/navigation/graph.xml"], "allInputFiles": ["src/debug/res/navigation/graph.xml"], "outputDir": "build/generated", "incrementalFolder": "build/mapping", "applicationId": "p.safeargs", "rFilePackage": "p", "useAndroidX": True, "generateKotlin": False}
    if value["providers"] != expected_providers:
        raise ValueError("unexpected actual provider/input selection")


def capture_outputs(fixture: Path) -> dict:
    sources = inventory(fixture / "build/generated")
    mapping_file = fixture / "build/mapping/file_mappings.json"
    mapping = read_json(mapping_file)
    if set(sources) != EXPECTED_SOURCES or not isinstance(mapping, list) or len(mapping) != 1:
        raise ValueError("incomplete actual generation inventory")
    row = mapping[0]
    if set(row) != {"navFile", "javaFiles"} or row["navFile"].replace("\\", "/") != "src/debug/res/navigation/graph.xml" or sorted(row["javaFiles"]) != sorted(name[:-5].replace("/", ".") for name in EXPECTED_SOURCES):
        raise ValueError("unexpected actual source mapping")
    return {"sources": sources, "mapping": mapping, "mappingSha256": digest(mapping_file), "navigationSha256": digest(fixture / "src/debug/res/navigation/graph.xml")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--distribution", type=Path, help="Existing official archive; identical pinned SHA remains mandatory")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    output = args.output.resolve()
    report = {"status": "running", "scope": "actual SDK-less published task and Gradle action observation; no AGP/compiler/runtime or product-support claim", "productAdmission": False, "distributionUrl": DISTRIBUTION, "distributionSha256": DISTRIBUTION_SHA, "runs": []}
    def save():
        text = json.dumps(report, indent=2) + "\n"
        if len(text.encode()) > MAX_JSON:
            raise ValueError("final observation exceeds bound")
        (output / "receipt.json").write_text(text, encoding="utf-8")
    save()
    try:
        archive = args.distribution.resolve() if args.distribution else output / "gradle-8.13-bin.zip"
        if not args.distribution:
            download(archive)
        home = unpack(archive, output / "distribution")
        fixture = output / "fixture"; fixture.mkdir()
        (fixture / "settings.gradle").write_text("rootProject.name='safeargs-gradle813-observation'\n", encoding="utf-8")
        (fixture / "gradle.properties").write_text("org.gradle.jvmargs=-Xmx384m\norg.gradle.workers.max=1\n", encoding="utf-8")
        (fixture / "build.gradle").write_text(BUILD, encoding="utf-8")
        nav = fixture / "src/debug/res/navigation/graph.xml"; nav.parent.mkdir(parents=True); nav.write_text(NAVIGATION, encoding="utf-8")
        report["fixtureSha256"] = digest(fixture / "build.gradle")
        for phase in ("cold", "warm"):
            started = time.perf_counter()
            argv = [str(home / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")), "--no-daemon", "observe", "--no-configuration-cache", "--no-build-cache", "--console=plain", "--stacktrace"]
            with (output / f"{phase}.stdout.log").open("wb") as stdout, (output / f"{phase}.stderr.log").open("wb") as stderr:
                result = subprocess.run(argv, cwd=fixture, stdout=stdout, stderr=stderr, timeout=480)
            row = {"phase": phase, "exitCode": result.returncode, "elapsedSeconds": time.perf_counter() - started}; report["runs"].append(row); save()
            if result.returncode != 0:
                raise ValueError(f"Gradle {phase} execution failed; inspect preserved synthetic fixture log")
            value = read_json(fixture / "build/observation.json")
            row["observation"] = value; row["validation"] = "unverified"; save()
            validate_observation(value, home, phase)
            row["outputs"] = capture_outputs(fixture); row["validation"] = "matched-to-observed-inputs"; save()
        if report["runs"][0]["outputs"] != report["runs"][1]["outputs"]:
            raise ValueError("unchanged warm generation bytes differ from cold")
        cold, warm = (r["observation"] for r in report["runs"])
        if any(cold[k] != warm[k] for k in ("artifacts", "actions", "providers")):
            raise ValueError("warm action or provider identity changed")
        report["status"] = "success"; save()
        print(json.dumps({"status": "success", "gradle": VERSION, "safeArgs": "2.10.2", "action": cold["actions"][0], "generatedSources": len(EXPECTED_SOURCES), "productAdmission": False}))
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        report["status"] = "failed"; report["errorType"] = type(error).__name__
        report["failureReason"] = str(error).replace(str(output), "<observation-root>")[:1024]; save()
        print("SafeArgs Gradle 8.13 observation failed; retained receipt and synthetic logs", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
