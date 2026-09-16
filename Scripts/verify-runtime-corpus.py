#!/usr/bin/env python3
"""실제 compiler와 런타임을 대조해 고정된 사각지대의 복원 및 미해결 경계를 검사한다."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

if not __debug__:
    sys.exit('Runtime corpus verification requires Python assertions; remove -O/PYTHONOPTIMIZE.')
if not os.environ.get('JAVA_HOME'):
    sys.exit('Set JAVA_HOME to a JDK before runtime corpus verification.')

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "fixtures/runtime-corpus"
BINARY = ROOT / "cli/build/install/kartograph/bin/kartograph"
JDK = Path(os.environ["JAVA_HOME"])
INJECT = ROOT / "cli/build/runtime-corpus/javax.inject-1.jar"


def run(command, cwd=None, expected=0, timeout=180):
    result = subprocess.run([str(arg) for arg in command], cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if result.returncode != expected:
        raise RuntimeError(f"corpus command failed: {Path(str(command[0])).name}, expected {expected}, actual {result.returncode}")
    return result.stdout


def query(project, classes, symbol, expected_state, *extra):
    document = json.loads(run([BINARY, "query", symbol, "--classes", classes,
        "--project", project, "--keep-rules", "keep.pro", *extra]))
    assert document["status"] == "found", symbol
    assert document["result"]["reachability"]["state"] == expected_state, (symbol, document["result"]["reachability"])
    return document


def verify_instance_helpers(temporary, kotlin_version):
    cases = [("java_final", "java", True), ("java_private", "java", True),
             ("java_override", "java", False), ("java_state", "java", False),
             ("kotlin_object", "kotlin", True), ("kotlin_companion", "kotlin", True)]
    stdlib = next((BINARY.parent.parent / "lib").glob("kotlin-stdlib-*.jar"))
    kotlin_root = temporary / "instance-helpers"
    kotlin_root.mkdir()
    (kotlin_root / "settings.gradle.kts").write_text('include(":kotlin_object", ":kotlin_companion")\n')
    (kotlin_root / "build.gradle.kts").write_text('''import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
plugins { kotlin("jvm") version "''' + kotlin_version + '''" apply false }
subprojects {
 apply(plugin = "org.jetbrains.kotlin.jvm")
 repositories { mavenCentral() }
 extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
}
''')
    for case, language, _ in cases:
        project = kotlin_root / case
        relative = "src/probe/Entry.java" if language == "java" else "src/main/kotlin/probe/Entry.kt"
        source = project / relative
        source.parent.mkdir(parents=True)
        source.write_bytes((FIXTURES / ("helper_" + case) / relative).read_bytes())
        (project / "keep.pro").write_text("-keep class probe.Entry { *; }\n")
        if language == "java":
            classes = project / "classes"
            classes.mkdir()
            run([JDK / "bin/javac", "-g", "-d", classes, source])
    (kotlin_root / "gradle").mkdir()
    (kotlin_root / "gradle/verification-metadata.xml").write_bytes((ROOT / "gradle/verification-metadata.xml").read_bytes())
    run([ROOT / "gradlew", "--offline", "--no-daemon", "-p", kotlin_root, "classes"], cwd=ROOT, timeout=600)
    for case, language, positive in cases:
        project = kotlin_root / case
        classes = project / ("classes" if language == "java" else "build/classes/kotlin/main")
        classpath = [classes] + ([stdlib] if language == "kotlin" else [])
        arguments = ["probe.Used"] if case == "java_state" else []
        assert run([JDK / "bin/java", "-cp", os.pathsep.join(map(str, classpath)), "probe.Entry", *arguments]).strip() == "USED"
        used = query(project, classes, "class:probe/Used", "reachable" if positive else "unreachable")
        query(project, classes, "class:probe/Unused", "unreachable")
        if not positive:
            assert any(item.startswith("reflection-strings:") for item in used["limitations"])
        print("Instance helper verified:", case, flush=True)


def main():
    run([ROOT / "gradlew", "--offline", "--no-daemon", ":cli:installDist", ":cli:runtimeCorpusDependencies"], cwd=ROOT, timeout=600)
    version = re.search(r'kotlin\("jvm"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    java_cases = {
        "java_constants": [("Constants", "retained"), ("UnusedConstants", "retained")],
        "forname_literal": [("Target", "reachable")], "forname_local": [("Target", "reachable")],
        "forname_three_args": [("Target", "reachable")], "reflective_constructor": [("ConstructorBody", "reachable")],
        "load_class": [("Target", "reachable")], "external_dispatch": [("CallbackBody", "reachable")],
        "service_loader": [("Provider", "retained")], "annotation_default": [("DefaultTarget", "reachable")],
        "inject_unused": [("DormantService", "retained"), ("DormantDependency", "reachable")],
    }
    with tempfile.TemporaryDirectory(prefix="kartograph-runtime-corpus-") as directory:
        temporary = Path(directory)
        for case, expectations in java_cases.items():
            project = temporary / case
            source = project / "src/probe/Entry.java"
            source.parent.mkdir(parents=True)
            source.write_text((FIXTURES / case / "src/probe/Entry.java").read_text())
            control = source.with_name("UnusedControl.java")
            control.write_text("package probe; public class UnusedControl {}")
            classes = project / "classes"
            classes.mkdir()
            (project / "keep.pro").write_text("-keep class probe.Entry { *; }\n")
            run([JDK / "bin/javac", "-g", "-cp", INJECT, "-d", classes, source, control])
            if case == "service_loader":
                registry = classes / "META-INF/services/java.lang.Runnable"
                registry.parent.mkdir(parents=True)
                registry.write_text("probe.Entry$Provider\n")
            run([JDK / "bin/java", "-cp", str(classes) + os.pathsep + str(INJECT), "probe.Entry"])
            for name, state in expectations:
                query(project, classes, "class:probe/Entry$" + name, state)
            query(project, classes, "class:probe/UnusedControl", "unreachable")
            print("Java verified:", case, flush=True)
        library_classes = temporary / "library-classes"
        library_classes.mkdir()
        run([JDK / "bin/javac", "-d", library_classes, FIXTURES / "support/lib/Callbacks.java"])
        library = temporary / "callbacks.jar"
        run([JDK / "bin/jar", "--create", "--file", library, "-C", library_classes, "."])
        kotlin = temporary / "kotlin"
        kotlin.mkdir()
        (kotlin / "settings.gradle.kts").write_text('pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }\ninclude(":indy", ":class", ":named")\n')
        (kotlin / "build.gradle.kts").write_text('''import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
plugins { kotlin("jvm") version "''' + version + '''" apply false }
subprojects {
 apply(plugin = "org.jetbrains.kotlin.jvm")
 repositories { mavenCentral() }
 val backend = if (name == "class") "class" else "indy"
 extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17); compilerOptions { freeCompilerArgs.add("-Xsam-conversions=" + backend) } }
 dependencies { add("implementation", files(rootProject.file("../callbacks.jar"))) }
}
''')
        (kotlin / "gradle").mkdir()
        (kotlin / "gradle/verification-metadata.xml").write_bytes((ROOT / "gradle/verification-metadata.xml").read_bytes())
        for backend in ["indy", "class", "named"]:
            project = kotlin / backend
            source = project / "src/main/kotlin/probe/KotlinEntry.kt"
            source.parent.mkdir(parents=True)
            source.write_text((FIXTURES / ("kotlin_" + backend) / "src/main/kotlin/probe/KotlinEntry.kt").read_text())
            source.with_name("UnusedControl.kt").write_text("package probe\nclass UnusedControl")
            (project / "keep.pro").write_text("-keep class probe.KotlinEntry { *; }\n")
        run([ROOT / "gradlew", "--offline", "--no-daemon", "-p", kotlin, "classes"], cwd=ROOT, timeout=600)
        stdlib = next((BINARY.parent.parent / "lib").glob("kotlin-stdlib-*.jar"))
        for backend in ["indy", "class", "named"]:
            project = kotlin / backend
            classes = project / "build/classes/kotlin/main"
            run([JDK / "bin/java", "-cp", os.pathsep.join(map(str, [classes, library, stdlib])), "probe.KotlinEntry"])
            target = "NamedDependency" if backend == "named" else "CallbackBody"
            query(project, classes, "class:probe/" + target, "reachable", "--classpath", str(library))
            query(project, classes, "class:probe/UnusedControl", "unreachable")
            if backend != "named":
                query(project, classes, "field:probe/ConstantObject#USED:I", "retained")
                query(project, classes, "class:probe/UnusedConstantObject", "retained")
            print("Kotlin verified:", backend, flush=True)
        verify_instance_helpers(temporary, version)
    print("Runtime corpus verified: 19 compiler/runtime cases, unused and unresolved controls preserved")


if __name__ == "__main__":
    main()
