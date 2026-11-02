#!/usr/bin/env python3
"""공개 fixture에서 compiler 참조 수집과 revision/variant 일치 보강을 비교하는 실험이다."""
import copy
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
VARIANT = "jvm17-experiment"


def canonical(document):
    return (json.dumps(document, sort_keys=True, indent=2) + "\n").encode()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(sources, class_roots, variant):
    return {
        "variant": variant,
        "fixtureMappingSha256": digest(HERE / "fixture-mapping.json"),
        "sources": {p.name: digest(p) for p in sorted(sources)},
        "classes": {f"{ordinal}/{p.relative_to(root).as_posix()}": digest(p)
                    for ordinal, root in enumerate(class_roots) for p in sorted(root.rglob("*.class"))},
    }


KOTLIN_PLUGIN = re.compile(r'kotlin\("jvm"\) version "([^"]+)"')
COMPILER_EMBEDDABLE = re.compile(r'"org\.jetbrains\.kotlin:kotlin-compiler-embeddable:([^"]+)"')


def without_kotlin_comments(text):
    """Kotlin build script에서 `//` 줄 주석과 중첩 가능한 `/* */` 주석만 지우고 문자열은 그대로 둔다.

    문자열(일반·raw)·문자 literal·backtick 식별자와 문자열 안의 `${...}` template을 따라가므로 URL의 `//`나 glob의
    `/*`를 주석으로 보지 않는다. raw string은 Kotlin처럼 3개 이상 따옴표 run 전체로 닫는다. 닫히지 않은 주석은
    끝까지 주석으로 본다. 짝을 확신할 수 없는 입력(닫히지 않은 문자열·template·문자 literal·backtick 식별자,
    multi-dollar 문자열)은 추측하지 않고 ValueError로 실패해, 짝이 뒤집혀 주석 판정이 어긋나는 일을 막는다.
    파일 맨 앞(UTF-8 BOM 뒤)의 shebang 줄은 따옴표가 있어도 문자열이 아니므로 줄을 비우고 줄바꿈만 남긴다.
    줄은 Kotlin lexer처럼 나눈다. `//` 주석과 shebang은 LF에서만 끝나 lone CR을 안에 남기고, 한 줄 안에서 닫혀야
    하는 일반 문자열·문자 literal·backtick 식별자는 LF나 lone CR을 만나면 실패한다. 그러므로 입력은 universal
    newline 변환 없이 읽은 원문이어야 한다.
    """
    if text.startswith("\ufeff"):
        text = text[1:]
    if text.startswith("#!"):
        # Kotlin은 `#!`를 파일 맨 앞에서만 허용한다. 그 밖의 `#!`는 일반 code로 읽어 짝이 맞지 않으면 실패한다.
        # shebang은 `//` 주석처럼 LF까지가 한 줄이고 lone CR에서는 끝나지 않는다.
        end = text.find("\n")
        text = "" if end < 0 else text[end:]
    result, index, length = [], 0, len(text)
    modes = [["code", 0]]  # 맨 위 상태와, code 상태에서 template을 닫을 `}`를 가리기 위한 중괄호 깊이
    while index < length:
        mode = modes[-1]
        char = text[index]
        if mode[0] == "code":
            if text.startswith("//", index):
                end = text.find("\n", index)
                index = length if end < 0 else end
                continue
            if text.startswith("/*", index):
                depth, index = 1, index + 2
                while index < length and depth:
                    if text.startswith("/*", index):
                        depth, index = depth + 1, index + 2
                    elif text.startswith("*/", index):
                        depth, index = depth - 1, index + 2
                    else:
                        index += 1
                result.append(" ")
                continue
            if char == "`":
                # backtick 식별자(`a"b`)는 따옴표를 담을 수 있으므로 같은 줄의 닫는 backtick까지 그대로 둔다.
                end = text.find("`", index + 1)
                if end < 0 or "\n" in text[index:end] or "\r" in text[index:end]:
                    raise ValueError("unterminated backtick identifier")
                result.append(text[index:end + 1])
                index = end + 1
                continue
            if char == "'":
                # 문자 literal('"', '\'' 등)은 같은 줄에서 닫는 따옴표까지 그대로 둔다. Kotlin처럼 LF·lone CR에서
                # 끊고, escape도 줄바꿈을 건너뛰지 못한다.
                end = index + 1
                while end < length and text[end] not in "'\r\n":
                    end += 2 if text[end] == "\\" and text[end + 1:end + 2] not in ("\r", "\n") else 1
                if end >= length or text[end] != "'":
                    raise ValueError("unterminated character literal")
                result.append(text[index:end + 1])
                index = end + 1
                continue
            quote = '"""' if text.startswith('"""', index) else '"' if char == '"' else None
            if quote:
                if index and text[index - 1] == "$":
                    # Kotlin 2.2+ multi-dollar 문자열은 template 시작 규칙이 달라 추적하지 않는다.
                    raise ValueError("multi-dollar string literals are not supported")
                modes.append(["raw" if len(quote) == 3 else "string", 0])
                result.append(quote)
                index += len(quote)
                continue
            if char == "{":
                mode[1] += 1
            elif char == "}":
                if len(modes) > 1 and mode[1] == 0:
                    modes.pop()
                    result.append(char)
                    index += 1
                    continue
                mode[1] -= 1
            result.append(char)
            index += 1
            continue
        # 일반 문자열은 한 줄 안에서 닫혀야 한다. 줄바꿈(LF·lone CR)이나 줄 끝·파일 끝의 escape를 만나면 짝을 추측하지
        # 않는다.
        if mode[0] == "string" and (char in "\r\n" or (char == "\\" and text[index + 1:index + 2] in ("", "\n", "\r"))):
            raise ValueError("unterminated string literal")
        if mode[0] == "string" and char == "\\":
            result.append(text[index:index + 2])
            index += 2
        elif mode[0] == "raw" and text.startswith('"""', index):
            # 4개 이상이면 마지막 3개가 닫는 따옴표이고 나머지는 값이다. run 전체를 소비해 짝을 유지한다.
            run = 3
            while index + run < length and text[index + run] == '"':
                run += 1
            modes.pop()
            result.append(text[index:index + run])
            index += run
        elif mode[0] == "string" and char == '"':
            modes.pop()
            result.append(char)
            index += 1
        elif text.startswith("${", index):
            modes.append(["code", 0])
            result.append("${")
            index += 2
        else:
            result.append(char)
            index += 1
    if len(modes) != 1:
        raise ValueError("unterminated string or template")
    return "".join(result)


def collector_kotlin_version(root=ROOT, here=HERE):
    """collector의 Kotlin plugin·compiler 버전이 루트 plugin과 같은지 확인하고 그 버전을 돌려준다.

    collector와 fixture는 offline으로 빌드되어 루트 빌드가 데운 Gradle 캐시만 쓴다. 버전이 어긋나면 Gradle이
    plugin을 찾지 못한다는 숨은 오류로 늦게 실패하므로, 빌드 전에 저장소 상대 경로와 두 버전으로 실패한다.
    """
    def single(pattern, path, label):
        relative = path.relative_to(root).as_posix()
        try:
            # Kotlin은 lone CR에서 `//` 주석과 shebang을 끝내지 않으므로 universal newline 변환 없이 원문대로 읽는다.
            text = path.read_bytes().decode("utf-8")
        except (OSError, UnicodeError):
            # 원래 오류는 개인 절대경로를 담으므로 연결하지 않고 저장소 상대 경로만 남긴다.
            raise RuntimeError(f"cannot read {relative}; it must declare the {label}") from None
        try:
            stripped = without_kotlin_comments(text)
        except ValueError as problem:
            raise RuntimeError(f"cannot parse {relative}: {problem}") from None
        found = pattern.findall(stripped)
        if len(found) != 1:
            raise RuntimeError(f"expected one {label} in {relative}, found {len(found)}")
        return found[0]
    expected = single(KOTLIN_PLUGIN, root / "build.gradle.kts", "Kotlin JVM plugin version")
    build = here / "collector/build.gradle.kts"
    plugin = single(KOTLIN_PLUGIN, build, "Kotlin JVM plugin version")
    compiler = single(COMPILER_EMBEDDABLE, build, "kotlin-compiler-embeddable version")
    if plugin != expected or compiler != expected:
        raise RuntimeError(f"collector Kotlin plugin {plugin} and compiler {compiler} must equal the root Kotlin JVM "
                           f"plugin {expected}; update {build.relative_to(root).as_posix()}")
    return expected


def command(args, cwd=ROOT):
    result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError(f"experiment command failed: {Path(str(args[0])).name} (exit {result.returncode})")
    return result.stdout


def enrich(graph, sidecar, current):
    if sidecar["inputs"] != current:
        raise ValueError("compiler reference inputs do not match selected revision, variant or class outputs")
    if sidecar["graphSha256"] != hashlib.sha256(canonical(graph)).hexdigest():
        raise ValueError("compiler reference graph does not match")
    ids = {node["usr"] for node in graph["nodes"]}
    result = copy.deepcopy(graph)
    for reference in sidecar["references"]:
        if reference["source"] not in ids or reference["target"] not in ids:
            raise ValueError("compiler reference does not match a compiled declaration")
        result["edges"].append({"source": reference["source"], "target": reference["target"],
                                "kind": "fieldAccess", "origin": "compilerReference", "weight": 1})
    result["edges"].sort(key=lambda edge: (edge["source"], edge["target"], edge["kind"], edge.get("origin", "bytecode")))
    return result


def expect_rejected(graph, sidecar, current):
    try:
        enrich(graph, sidecar, current)
    except ValueError:
        return 1
    raise AssertionError("mismatched compiler references were accepted")


def validate_mapping(mapping, consumed=None):
    if len(set(mapping.values())) != len(mapping):
        raise ValueError("ambiguous fixture mapping")
    if consumed is not None and consumed != set(mapping):
        raise ValueError("stale fixture mapping entries")


def expect_mapping_rejected(mapping, consumed):
    try:
        validate_mapping(mapping, consumed)
    except ValueError:
        return 1
    raise AssertionError("invalid fixture mapping was accepted")


def main():
    start = time.monotonic()
    if not os.environ.get("JAVA_HOME"):
        raise RuntimeError("set JAVA_HOME to JDK 17 before running this experiment")
    jdk = Path(os.environ["JAVA_HOME"])
    java_version = command([jdk / "bin/javac", "-version"]).strip()
    if not java_version.startswith("javac 17."):
        raise RuntimeError("this comparison requires JDK 17")
    kotlin_version = collector_kotlin_version()
    command([ROOT / "gradlew", "--offline", "--no-daemon", ":cli:installDist"])
    command([ROOT / "gradlew", "--offline", "--no-daemon", "-p", HERE / "collector", "jar"])
    collector = HERE / "collector/build/libs/reference-collector.jar"
    sources = sorted((HERE / "fixtures").iterdir())
    before = {p.name: digest(p) for p in sources}
    with tempfile.TemporaryDirectory(prefix="kartograph-reference-experiment-") as temporary:
        work = Path(temporary)
        java_classes, kotlin_classes = work / "java-classes", work / "kotlin/build/classes/kotlin/main"
        javac_collector = work / "collector"
        command([jdk / "bin/javac", "-d", javac_collector, HERE / "JavaReferenceCollector.java"])
        java_rows = work / "java-references.tsv"
        command([jdk / "bin/java", "-cp", javac_collector, "JavaReferenceCollector",
                 HERE / "fixtures/JavaConstants.java", java_classes, java_rows])
        kotlin = work / "kotlin"
        (kotlin / "src/main/kotlin").mkdir(parents=True)
        shutil.copyfile(HERE / "fixtures/Constants.kt", kotlin / "src/main/kotlin/Constants.kt")
        (kotlin / "settings.gradle.kts").write_text(
            'pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = "reference-fixture"\n')
        (kotlin / "build.gradle.kts").write_text(
            f'plugins {{ kotlin("jvm") version "{kotlin_version}" }}\nrepositories {{ mavenCentral() }}\n'
            'kotlin { jvmToolchain(17); compilerOptions { freeCompilerArgs.add("-Xplugin=${providers.gradleProperty("collector").get()}") } }\n')
        command([ROOT / "gradlew", "--offline", "--no-daemon", "-p", kotlin, f"-Pcollector={collector}", "compileKotlin"])
        assert before == {p.name: digest(p) for p in sources}, "sources changed during compilation"
        roots = [java_classes, kotlin_classes]
        graph = json.loads(command([ROOT / "cli/build/install/kartograph/bin/kartograph", "graph",
                                   "--classes", java_classes, "--classes", kotlin_classes, "--format", "json"]))
        mapping = json.loads((HERE / "fixture-mapping.json").read_text())
        validate_mapping(mapping)
        consumed = set()
        references, ir_references, compiler_versions = [], [], []
        for line in java_rows.read_text().splitlines() + (kotlin_classes / "compiler-references.tsv").read_text().splitlines():
            engine, caller, target, offset = line.split("\t")
            if engine == "compiler":
                compiler_versions.append(caller)
                continue
            if engine == "ir":
                if caller in {"probe.readObject", "probe.readTop", "probe.readCompanion"}:
                    ir_references.append((caller, target))
                continue
            if engine == "fir":
                # fixture 전용 명시 매핑이다. JVM 이름 추측을 일반 제품 mapper로 주장하지 않는다.
                if caller not in mapping or target not in mapping:
                    raise ValueError("unmapped or ambiguous compiler symbol")
                consumed.update((caller, target))
                caller, target = mapping[caller], mapping[target]
            references.append({"source": caller, "target": target, "sourceOffset": int(offset), "engine": engine})
        validate_mapping(mapping, consumed)
        stale_mapping = {**mapping, "probe/obsolete": "field:probe/obsolete#VALUE:I"}
        rejected_mappings = expect_mapping_rejected(stale_mapping, consumed)
        ambiguous_mapping = {**mapping, "probe/alias": next(iter(mapping.values()))}
        rejected_mappings += expect_mapping_rejected(ambiguous_mapping, set(ambiguous_mapping))
        assert compiler_versions == [kotlin_version], "compiler version differs from selected plugin"
        references.sort(key=lambda row: (row["source"], row["target"], row["sourceOffset"]))
        assert len(references) == 4, references
        assert len({row["target"] for row in references}) == 4
        assert not ir_references, "IR phase retained a tested use; revisit the comparison conclusion"
        expected_pairs = {(row["source"], row["target"]) for row in references}
        bytecode_uses = expected_pairs.intersection((e["source"], e["target"]) for e in graph["edges"])
        assert not bytecode_uses, "bytecode already contains a tested use"
        assert not any("shadow" in row["source"].lower() or "UNUSED" in row["target"] for row in references)
        controls = {
            "field:probe/JavaConstants#UNUSED:Ljava/lang/String;",
            "field:probe/Constants#UNUSED:Ljava/lang/String;",
            "field:probe/Holder#UNUSED:Ljava/lang/String;",
            "field:probe/NamedConstants#TOP_UNUSED:Ljava/lang/String;",
            "method:probe/JavaConstants#shadow()Ljava/lang/String;",
            "method:probe/NamedConstants#readShadow()Ljava/lang/String;",
        }
        assert controls.issubset(node["usr"] for node in graph["nodes"]), "control declaration was not compiled"
        assert not controls.intersection(row[key] for row in references for key in ("source", "target"))
        inputs = snapshot(sources, roots, VARIANT)
        sidecar = {"format": "experimental-compiler-references", "version": 1, "compiler": {"java": java_version, "kotlin": compiler_versions[0]},
                   "inputs": inputs, "graphSha256": hashlib.sha256(canonical(graph)).hexdigest(),
                   "references": references}
        enriched = enrich(graph, sidecar, snapshot(sources, roots, VARIANT))
        assert expected_pairs.issubset((e["source"], e["target"]) for e in enriched["edges"])
        rejected = expect_rejected(graph, sidecar, snapshot(sources, roots, "other-variant"))
        changed = copy.deepcopy(inputs); changed["sources"]["Constants.kt"] = "different-revision"
        rejected += expect_rejected(graph, sidecar, changed)
        changed = copy.deepcopy(inputs); changed["classes"][next(iter(changed["classes"]))] = "stale-output"
        rejected += expect_rejected(graph, sidecar, changed)
        changed = copy.deepcopy(sidecar); changed["references"][0]["target"] = "field:missing#VALUE:I"
        rejected += expect_rejected(graph, changed, inputs)
        changed_graph = copy.deepcopy(graph); changed_graph["edges"] = []
        rejected += expect_rejected(changed_graph, sidecar, inputs)
        report = ROOT / "build/reports/compiler-references"
        report.mkdir(parents=True, exist_ok=True)
        for name, document in [("sidecar.json", sidecar), ("bytecode.json", graph), ("enriched.json", enriched)]:
            (report / name).write_bytes(canonical(document))
        result = {"references": len(references), "bytecodeUseEdges": len(bytecode_uses), "irUseEdges": len(ir_references),
                  "enrichedUseEdges": len(expected_pairs.intersection((e["source"], e["target"]) for e in enriched["edges"])), "rejectedMismatches": rejected,
                  "rejectedMappings": rejected_mappings, "compiledControlDeclarations": len(controls), "unusedAndShadowControls": "passed"}
        (report / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({**result, "elapsedSeconds": round(time.monotonic() - start, 3)}))


if __name__ == "__main__":
    main()
