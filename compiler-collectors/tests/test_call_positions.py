#!/usr/bin/env python3
"""RED contract for opt-in compiler selector positions."""

from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


JAVA_SOURCE = """\
package demo;

public final class JavaUsers {
    public void use() {
        JavaTargets.direct();
        JavaTargets.overloaded("text");
        JavaTargets.overloaded(42);
        Local local = new Local();
        local.direct();
        java.lang.String.valueOf(1);
    }

    private static final class Local {
        void direct() {}
    }
}
"""

JAVA_TARGETS = """\
package demo;

public final class JavaTargets {
    public static void direct() {}
    public static void overloaded(String value) {}
    public static void overloaded(int value) {}
}
"""

JAVA_BOUNDARIES = """\
package demo;

public final class JavaBoundaries {
    static int hit() { return 1; }

    void outer() {
        hit();
        class Local {
            int initialized = hit();
            int method() { return hit(); }
        }
        java.util.function.IntSupplier action = () -> hit();
    }
}
"""

JAVA_CRLF = (
    "package demo;\r\n"
    "\r\n"
    "final class JavaCrLf {\r\n"
    "    static void hit() {}\r\n"
    "    static void use() { hit(); }\r\n"
    "}\r\n"
)

KOTLIN_SOURCE = """\
package demo

fun useKotlin() {
    KotlinTargets().direct()
}

fun external() {
    java.lang.String.valueOf(1)
}

fun ambiguous() {
    Ambiguous().same()
}

fun explicitDefaultParameter() {
    Ambiguous().same(1)
}

fun readConstant() = CALL_CONSTANT
"""

KOTLIN_TARGETS = """\
package demo

class KotlinTargets {
    fun direct() {}
}

class Ambiguous {
    @JvmOverloads
    fun same(value: Int = 0) {}
}

const val CALL_CONSTANT: Int = 1
"""

KOTLIN_DEPENDENCY = """\
package demo

fun choose(value: String) {}
"""

KOTLIN_PROJECT_TARGET = """\
package demo

fun choose(value: Int) {}
"""

KOTLIN_OVERLOAD_CALLER = """\
package demo

fun useOverloads() {
    choose("dependency")
    choose(1)
}
"""

KOTLIN_JVM_NAME_TARGET = """\
@file:JvmName("CustomTargets")

package demo

@JvmName("renamedJvm")
fun renamed() {}
"""

KOTLIN_BOM_CRLF_CALLER = (
    "package demo\r\n"
    "\r\n"
    "fun useJvmName() {\r\n"
    "    renamed()\r\n"
    "}\r\n"
)

KOTLIN_DESUGARED_TARGET = """\
package demo

class DesugaredTarget {
    operator fun component1(): Int = 1
    operator fun component2(): Int = 2
    operator fun plus(other: DesugaredTarget): DesugaredTarget = this
    operator fun invoke() {}
    operator fun iterator(): Iterator<Int> = throw UnsupportedOperationException()
    fun direct() {}
}
"""

KOTLIN_DESUGARED_CALLER = """\
package demo

fun desugared(target: DesugaredTarget) {
    val (first, second) = target
    target + target
    target()
    for (value in target) { value.hashCode() }
    target.direct()
}
"""

KOTLIN_BOUNDARY_TARGET = """\
package demo

class BoundaryTarget {
    fun hit() {}
}
"""

KOTLIN_BOUNDARY_CALLER = """\
package demo

fun boundaryOuter(target: BoundaryTarget) {
    target.hit()
    class Local {
        val initialized = target.hit()
        fun method() { target.hit() }
    }
    val action = { target.hit() }
}
"""

KOTLIN_GENERIC_CALLS = """\
package demo

open class Base<T> {
    fun inherited(): T? = null
}

class Sub : Base<String>()

class Box<T> {
    fun get(): T? = null
}

open class DeepBase<T> {
    fun deep(): T? = null
}

open class Mid<U> : DeepBase<List<U>>()
class DeepSub : Mid<String>()

interface Left<T> {
    fun intersection(): T
}

interface Right<T> {
    fun intersection(): T
}

class ConcreteBoth : Left<String>, Right<String> {
    override fun intersection(): String = "value"
}

class Foo
typealias Alias = Foo
typealias AliasAgain = Alias

fun useGeneric(box: Box<String>) {
    box.get()
}

fun useInherited(sub: Sub) {
    sub.inherited()
}

fun useAlias() {
    Alias()
}

fun useDeep(value: DeepSub) {
    value.deep()
}

fun useConcrete(value: ConcreteBoth) {
    value.intersection()
}

fun <T> useAbstract(value: T) where T : Left<String>, T : Right<String> {
    value.intersection()
}

fun useAliasAgain() {
    AliasAgain()
}
"""

KOTLIN_AMBIGUITY_CALLS = """\
package demo

class ComparableTarget : Comparable<ComparableTarget> {
    override fun compareTo(other: ComparableTarget): Int = 0
}

interface DefaultTarget {
    fun defaultHit() {}
}

open class SuspendTarget {
    open suspend fun suspendHit() {}
}

class PlainDefault {
    fun plain(value: Int = 0) {}
}

fun useBridge(left: ComparableTarget, right: ComparableTarget) {
    left.compareTo(right)
}

fun useDefault(target: DefaultTarget) {
    target.defaultHit()
}

suspend fun useSuspend(target: SuspendTarget) {
    target.suspendHit()
}

fun usePlain(target: PlainDefault) {
    target.plain()
    target.plain(1)
}
"""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=180)


def call_rows(path: Path) -> list[list[str]]:
    return [row.split("\t") for row in path.read_text(encoding="utf-8").splitlines() if row.startswith("call\t")]


def source_position(source: str, needle: str, occurrence: int = 0) -> tuple[str, str, str, str]:
    start = -1
    for _ in range(occurrence + 1):
        start = source.index(needle, start + 1)
    line = source.count("\n", 0, start) + 1
    line_start = source.rfind("\n", 0, start) + 1
    return str(start), str(start + len(needle)), str(line), str(start - line_start + 1)


def decoded_calls(path: Path) -> set[tuple[str, str, str, str, str, str, str]]:
    return {
        (base64.urlsafe_b64decode(row[1] + "==").decode(),
         base64.urlsafe_b64decode(row[2] + "==").decode(),
         base64.urlsafe_b64decode(row[3] + "==").decode(),
         row[5], row[6], row[7], row[8])
        for row in call_rows(path)
    }


def legacy_v1_view(path: Path) -> bytes:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("callStats\t") or line.startswith("call\t"):
            continue
        if line == "format\tkartograph-compiler-evidence\t3":
            line = "format\tkartograph-compiler-evidence\t1"
        rows.append(line)
    return ("\n".join(rows) + "\n").encode()


class CallPositionCollectorRedTests(unittest.TestCase):
    collector = Path(os.environ.get("KARTOGRAPH_COLLECTOR_JAR", ""))
    kotlin_classpath = os.environ.get("KARTOGRAPH_KOTLIN_CLASSPATH", "")
    jdk17 = Path(os.environ.get(
        "KARTOGRAPH_JDK17_HOME",
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
    ))

    def test_protocol_option_order_caps_and_atomic_rejection(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-protocol-") as temporary:
            root = Path(temporary)
            classes = root / "classes"
            source = Path(__file__).parent / "java/dev/kartograph/collectors/EvidenceProtocolCallPositionsTest.java"
            compiled = run([
                str(self.jdk17 / "bin/javac"), "-cp", str(self.collector), "-d", str(classes), str(source),
            ], root)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            checked = run([
                str(self.jdk17 / "bin/java"), "-cp", os.pathsep.join([str(classes), str(self.collector)]),
                "dev.kartograph.collectors.EvidenceProtocolCallPositionsTest", str(root / "work"),
            ], root)
            self.assertEqual(0, checked.returncode, checked.stderr)

    def test_javac_emits_exact_v3_selector_rows(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-javac-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/java/demo"
            source_dir.mkdir(parents=True)
            users = source_dir / "JavaUsers.java"
            targets = source_dir / "JavaTargets.java"
            users.write_text(JAVA_SOURCE, encoding="utf-8")
            targets.write_text(JAVA_TARGETS, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("1" * 64, encoding="utf-8")

            result = run([
                str(self.jdk17 / "bin/javac"),
                "-g",
                "-proc:none",
                "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={output.as_uri()} token={token.as_uri()} callPositions=true",
                "-d", str(root / "classes"),
                str(users), str(targets),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(output.is_file(), result.stderr)
            self.assert_v3_rows(output, {
                ("method:demo/JavaUsers#use()V", "method:demo/JavaTargets#direct()V", "src/main/java/demo/JavaUsers.java", "90", "96", "5", "21"),
                ("method:demo/JavaUsers#use()V", "method:demo/JavaTargets#overloaded(Ljava/lang/String;)V", "src/main/java/demo/JavaUsers.java", "120", "130", "6", "21"),
                ("method:demo/JavaUsers#use()V", "method:demo/JavaTargets#overloaded(I)V", "src/main/java/demo/JavaUsers.java", "160", "170", "7", "21"),
                ("method:demo/JavaUsers#use()V", "method:demo/JavaUsers$Local#direct()V", "src/main/java/demo/JavaUsers.java", "225", "231", "9", "15"),
            }, {"src/main/java/demo/JavaUsers.java": users, "src/main/java/demo/JavaTargets.java": targets}, {
                ("method:demo/JavaUsers#use()V", "method:demo/JavaTargets#direct()V", "src/main/java/demo/JavaUsers.java", "78", "96", "5", "9"),
                ("method:demo/JavaUsers#use()V", "method:demo/JavaTargets#overloaded(Ljava/lang/String;)V", "src/main/java/demo/JavaUsers.java", "108", "130", "6", "9"),
                ("method:demo/JavaUsers#use()V", "method:demo/JavaTargets#overloaded(I)V", "src/main/java/demo/JavaUsers.java", "148", "170", "7", "9"),
                ("method:demo/JavaUsers#use()V", "method:demo/JavaUsers$Local#direct()V", "src/main/java/demo/JavaUsers.java", "219", "231", "9", "9"),
            }, expected_stats=(8, 4, 4, 0), forbidden_targets={"method:java/lang/String#"})
            default_output = root / "default.tsv"
            default_result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={default_output.as_uri()} token={token.as_uri()}",
                "-d", str(root / "default-classes"), str(users), str(targets),
            ], root)
            self.assertEqual(0, default_result.returncode, default_result.stderr)
            self.assertEqual("format\tkartograph-compiler-evidence\t1", default_output.read_text(encoding="utf-8").splitlines()[0])
            self.assertNotIn("callStats\t", default_output.read_text(encoding="utf-8"))
            explicit_false = root / "explicit-false.tsv"
            false_result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={explicit_false.as_uri()} token={token.as_uri()} callPositions=false",
                "-d", str(root / "explicit-false-classes"), str(users), str(targets),
            ], root)
            self.assertEqual(0, false_result.returncode, false_result.stderr)
            self.assertEqual(default_output.read_bytes(), explicit_false.read_bytes())

            true_false = root / "true-false.tsv"
            true_false_result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={true_false.as_uri()} token={token.as_uri()} "
                "callPositions=true callPositions=false",
                "-d", str(root / "true-false-classes"), str(users), str(targets),
            ], root)
            self.assertEqual(0, true_false_result.returncode, true_false_result.stderr)
            self.assertEqual(default_output.read_bytes(), true_false.read_bytes())

            false_true = root / "false-true.tsv"
            false_true_result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={false_true.as_uri()} token={token.as_uri()} "
                "callPositions=false callPositions=true",
                "-d", str(root / "false-true-classes"), str(users), str(targets),
            ], root)
            self.assertEqual(0, false_true_result.returncode, false_true_result.stderr)
            self.assertEqual("format\tkartograph-compiler-evidence\t3", false_true.read_text(encoding="utf-8").splitlines()[0])

            boundaries = source_dir / "JavaBoundaries.java"
            crlf = source_dir / "JavaCrLf.java"
            boundaries.write_text(JAVA_BOUNDARIES, encoding="utf-8")
            crlf.write_bytes(JAVA_CRLF.encode("utf-8"))
            boundary_output = root / "boundaries.tsv"
            boundary_result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={boundary_output.as_uri()} token={token.as_uri()} callPositions=true",
                "-d", str(root / "boundary-classes"), str(boundaries), str(crlf),
            ], root)
            self.assertEqual(0, boundary_result.returncode, boundary_result.stderr)
            outer = source_position(JAVA_BOUNDARIES, "hit", 1)
            local_method = source_position(JAVA_BOUNDARIES, "hit", 3)
            crlf_call = source_position(JAVA_CRLF, "hit", 1)
            self.assert_v3_rows(boundary_output, {
                ("method:demo/JavaBoundaries#outer()V", "method:demo/JavaBoundaries#hit()I",
                 "src/main/java/demo/JavaBoundaries.java", *outer),
                ("method:demo/JavaBoundaries$1Local#method()I", "method:demo/JavaBoundaries#hit()I",
                 "src/main/java/demo/JavaBoundaries.java", *local_method),
                ("method:demo/JavaCrLf#use()V", "method:demo/JavaCrLf#hit()V",
                 "src/main/java/demo/JavaCrLf.java", *crlf_call),
            }, {
                "src/main/java/demo/JavaBoundaries.java": boundaries,
                "src/main/java/demo/JavaCrLf.java": crlf,
            }, expected_stats=(8, 3, 5, 0))

    def test_kotlin_emits_exact_v3_selector_row(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            users = source_dir / "KotlinUsers.kt"
            targets = source_dir / "KotlinTargets.kt"
            users.write_text(KOTLIN_SOURCE, encoding="utf-8")
            targets.write_text(KOTLIN_TARGETS, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("2" * 64, encoding="utf-8")
            stdlib = next(
                (item for item in self.kotlin_classpath.split(os.pathsep)
                 if Path(item).name.startswith("kotlin-stdlib-2.4.10")),
                None,
            )
            self.assertIsNotNone(stdlib, self.kotlin_classpath)

            result = run([
                str(self.jdk17 / "bin/java"),
                "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-reflect",
                "-jvm-target", "17",
                "-jdk-home", str(self.jdk17),
                "-classpath", str(stdlib),
                f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"),
                str(users), str(targets),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(output.is_file(), result.stderr)
            self.assert_v3_rows(output, {
                ("method:demo/KotlinUsersKt#useKotlin()V", "method:demo/KotlinTargets#<init>()V", "src/main/kotlin/demo/KotlinUsers.kt", "36", "49", "4", "5"),
                ("method:demo/KotlinUsersKt#useKotlin()V", "method:demo/KotlinTargets#direct()V", "src/main/kotlin/demo/KotlinUsers.kt", "52", "58", "4", "21"),
                ("method:demo/KotlinUsersKt#ambiguous()V", "method:demo/Ambiguous#<init>()V", "src/main/kotlin/demo/KotlinUsers.kt", "138", "147", "12", "5"),
                ("method:demo/KotlinUsersKt#ambiguous()V", "method:demo/Ambiguous#same(I)V", "src/main/kotlin/demo/KotlinUsers.kt", "150", "154", "12", "17"),
                ("method:demo/KotlinUsersKt#explicitDefaultParameter()V", "method:demo/Ambiguous#<init>()V", "src/main/kotlin/demo/KotlinUsers.kt", str(KOTLIN_SOURCE.rindex("Ambiguous()")), str(KOTLIN_SOURCE.rindex("Ambiguous()") + len("Ambiguous")), "16", "5"),
                ("method:demo/KotlinUsersKt#explicitDefaultParameter()V", "method:demo/Ambiguous#same(I)V", "src/main/kotlin/demo/KotlinUsers.kt", str(KOTLIN_SOURCE.rindex("same(1)")), str(KOTLIN_SOURCE.rindex("same(1)") + len("same")), "16", "17"),
            }, {"src/main/kotlin/demo/KotlinUsers.kt": users, "src/main/kotlin/demo/KotlinTargets.kt": targets}, {
                ("method:demo/KotlinUsersKt#useKotlin()V", "method:demo/KotlinTargets#direct()V", "src/main/kotlin/demo/KotlinUsers.kt", "36", "58", "4", "5"),
            }, expected_stats=(7, 6, 1, 0))
            default_output = root / "default.tsv"
            default_result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(stdlib), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={default_output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-d", str(root / "default-classes"), str(users), str(targets),
            ], root)
            self.assertEqual(0, default_result.returncode, default_result.stderr)
            self.assertEqual("format\tkartograph-compiler-evidence\t1", default_output.read_text(encoding="utf-8").splitlines()[0])
            self.assertNotIn("callStats\t", default_output.read_text(encoding="utf-8"))
            self.assertEqual(default_output.read_bytes(), legacy_v1_view(output))


    def test_kotlin_omits_desugared_call_positions(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-desugared-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            target = source_dir / "DesugaredTarget.kt"
            caller = source_dir / "DesugaredCaller.kt"
            target.write_text(KOTLIN_DESUGARED_TARGET, encoding="utf-8")
            caller.write_text(KOTLIN_DESUGARED_CALLER, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("8" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(self.kotlin_stdlib()), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(target), str(caller),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            direct = source_position(KOTLIN_DESUGARED_CALLER, "direct")
            self.assert_v3_rows(output, {
                ("method:demo/DesugaredCallerKt#desugared(Ldemo/DesugaredTarget;)V",
                 "method:demo/DesugaredTarget#direct()V", "src/main/kotlin/demo/DesugaredCaller.kt", *direct),
            }, {
                "src/main/kotlin/demo/DesugaredTarget.kt": target,
                "src/main/kotlin/demo/DesugaredCaller.kt": caller,
            }, expected_stats=(10, 1, 9, 0))

    def test_kotlin_respects_caller_boundaries(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-boundaries-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            target = source_dir / "BoundaryTarget.kt"
            caller = source_dir / "BoundaryCaller.kt"
            target.write_text(KOTLIN_BOUNDARY_TARGET, encoding="utf-8")
            caller.write_text(KOTLIN_BOUNDARY_CALLER, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("9" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(self.kotlin_stdlib()), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(target), str(caller),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            outer_hit = source_position(KOTLIN_BOUNDARY_CALLER, "hit", 0)
            initializer_hit = source_position(KOTLIN_BOUNDARY_CALLER, "hit", 1)
            local_method_hit = source_position(KOTLIN_BOUNDARY_CALLER, "hit", 2)
            lambda_hit = source_position(KOTLIN_BOUNDARY_CALLER, "hit", 3)
            outer_identity = "method:demo/BoundaryCallerKt#boundaryOuter(Ldemo/BoundaryTarget;)V"
            target_identity = "method:demo/BoundaryTarget#hit()V"
            self.assert_v3_rows(output, {
                (outer_identity, target_identity, "src/main/kotlin/demo/BoundaryCaller.kt", *outer_hit),
            }, {
                "src/main/kotlin/demo/BoundaryTarget.kt": target,
                "src/main/kotlin/demo/BoundaryCaller.kt": caller,
            }, {
                (outer_identity, target_identity, "src/main/kotlin/demo/BoundaryCaller.kt", *initializer_hit),
                ("method:demo/BoundaryCallerKt$boundaryOuter$Local#method()V", target_identity,
                 "src/main/kotlin/demo/BoundaryCaller.kt", *local_method_hit),
                (outer_identity, target_identity, "src/main/kotlin/demo/BoundaryCaller.kt", *lambda_hit),
            }, expected_stats=(4, 1, 3, 0))

    def test_kotlin_joins_exact_fir_symbols_across_dependency_overload(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-symbol-") as temporary:
            root = Path(temporary)
            dependency_source = root / "dependency/Dependency.kt"
            dependency_source.parent.mkdir(parents=True)
            dependency_source.write_text(KOTLIN_DEPENDENCY, encoding="utf-8")
            dependency = root / "dependency.jar"
            stdlib = self.kotlin_stdlib()
            dependency_result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(stdlib),
                "-d", str(dependency), str(dependency_source),
            ], root)
            self.assertEqual(0, dependency_result.returncode, dependency_result.stderr)

            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            target = source_dir / "ProjectTarget.kt"
            caller = source_dir / "Caller.kt"
            target.write_text(KOTLIN_PROJECT_TARGET, encoding="utf-8")
            caller.write_text(KOTLIN_OVERLOAD_CALLER, encoding="utf-8")
            output = root / "evidence.tsv"
            output.write_text("stale", encoding="utf-8")
            token = root / "token"
            token.write_text("4" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", os.pathsep.join([str(stdlib), str(dependency)]),
                f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(target), str(caller),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            int_start = KOTLIN_OVERLOAD_CALLER.index("choose(1)")
            self.assert_v3_rows(output, {
                ("method:demo/CallerKt#useOverloads()V", "method:demo/ProjectTargetKt#choose(I)V",
                 "src/main/kotlin/demo/Caller.kt", str(int_start), str(int_start + len("choose")), "5", "5"),
            }, {
                "src/main/kotlin/demo/ProjectTarget.kt": target,
                "src/main/kotlin/demo/Caller.kt": caller,
            }, expected_stats=(2, 1, 1, 0))

    def test_kotlin_normalizes_generic_and_typealias_targets(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-generics-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            source = source_dir / "GenericCalls.kt"
            source.write_text(KOTLIN_GENERIC_CALLS, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("c" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(self.kotlin_stdlib()), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(source),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            alias_statement = source_position(KOTLIN_GENERIC_CALLS, "    Alias()")
            alias_again_statement = source_position(KOTLIN_GENERIC_CALLS, "    AliasAgain()")
            alias_call = (str(int(alias_statement[0]) + 4), str(int(alias_statement[0]) + 4 + len("Alias")),
                          alias_statement[2], "5")
            alias_again_call = (str(int(alias_again_statement[0]) + 4),
                                str(int(alias_again_statement[0]) + 4 + len("AliasAgain")),
                                alias_again_statement[2], "5")
            self.assert_v3_rows(output, {
                ("method:demo/GenericCallsKt#useGeneric(Ldemo/Box;)V", "method:demo/Box#get()Ljava/lang/Object;",
                 "src/main/kotlin/demo/GenericCalls.kt", *source_position(KOTLIN_GENERIC_CALLS, "get", 1)),
                ("method:demo/GenericCallsKt#useInherited(Ldemo/Sub;)V", "method:demo/Base#inherited()Ljava/lang/Object;",
                 "src/main/kotlin/demo/GenericCalls.kt", *source_position(KOTLIN_GENERIC_CALLS, "inherited", 1)),
                ("method:demo/GenericCallsKt#useAlias()V", "method:demo/Foo#<init>()V",
                 "src/main/kotlin/demo/GenericCalls.kt", *alias_call),
                ("method:demo/GenericCallsKt#useDeep(Ldemo/DeepSub;)V", "method:demo/DeepBase#deep()Ljava/lang/Object;",
                 "src/main/kotlin/demo/GenericCalls.kt", *source_position(KOTLIN_GENERIC_CALLS, "deep", 1)),
                ("method:demo/GenericCallsKt#useConcrete(Ldemo/ConcreteBoth;)V",
                 "method:demo/ConcreteBoth#intersection()Ljava/lang/String;",
                 "src/main/kotlin/demo/GenericCalls.kt", *source_position(KOTLIN_GENERIC_CALLS, "intersection", 3)),
                ("method:demo/GenericCallsKt#useAbstract(Ldemo/Left;)V",
                 "method:demo/Left#intersection()Ljava/lang/Object;",
                 "src/main/kotlin/demo/GenericCalls.kt", *source_position(KOTLIN_GENERIC_CALLS, "intersection", 4)),
                ("method:demo/GenericCallsKt#useAliasAgain()V", "method:demo/Foo#<init>()V",
                 "src/main/kotlin/demo/GenericCalls.kt", *alias_again_call),
            }, {
                "src/main/kotlin/demo/GenericCalls.kt": source,
            }, expected_stats=(7, 7, 0, 0))

    def test_kotlin_counts_multiple_exact_backend_identities_as_ambiguous(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-ambiguous-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            source = source_dir / "AmbiguityCalls.kt"
            source.write_text(KOTLIN_AMBIGUITY_CALLS, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("f" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(self.kotlin_stdlib()),
                "-Xjvm-default=all-compatibility", f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(source),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            plain_target = "method:demo/PlainDefault#plain(I)V"
            self.assert_v3_rows(output, {
                ("method:demo/AmbiguityCallsKt#useBridge(Ldemo/ComparableTarget;Ldemo/ComparableTarget;)V",
                 "method:demo/ComparableTarget#compareTo(Ldemo/ComparableTarget;)I",
                 "src/main/kotlin/demo/AmbiguityCalls.kt", *source_position(KOTLIN_AMBIGUITY_CALLS, "compareTo", 1)),
                ("method:demo/AmbiguityCallsKt#useSuspend(Ldemo/SuspendTarget;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                 "method:demo/SuspendTarget#suspendHit(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                 "src/main/kotlin/demo/AmbiguityCalls.kt", *source_position(KOTLIN_AMBIGUITY_CALLS, "suspendHit", 1)),
                ("method:demo/AmbiguityCallsKt#usePlain(Ldemo/PlainDefault;)V", plain_target,
                 "src/main/kotlin/demo/AmbiguityCalls.kt", *source_position(KOTLIN_AMBIGUITY_CALLS, "plain", 1)),
                ("method:demo/AmbiguityCallsKt#usePlain(Ldemo/PlainDefault;)V", plain_target,
                 "src/main/kotlin/demo/AmbiguityCalls.kt", *source_position(KOTLIN_AMBIGUITY_CALLS, "plain", 2)),
            }, {
                "src/main/kotlin/demo/AmbiguityCalls.kt": source,
            }, expected_stats=(5, 4, 0, 1))

    def test_kotlin_uses_compiler_line_map_for_bom_crlf_and_jvm_name(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-lines-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            caller = source_dir / "BomCaller.kt"
            target = source_dir / "JvmNameTarget.kt"
            caller.write_bytes(b"\xef\xbb\xbf" + KOTLIN_BOM_CRLF_CALLER.encode("utf-8"))
            target.write_text(KOTLIN_JVM_NAME_TARGET, encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("5" * 64, encoding="utf-8")
            stdlib = self.kotlin_stdlib()
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(stdlib), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(caller), str(target),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            compiler_text = KOTLIN_BOM_CRLF_CALLER.replace("\r\n", "\n")
            start = compiler_text.index("renamed()")
            self.assert_v3_rows(output, {
                ("method:demo/BomCallerKt#useJvmName()V", "method:demo/CustomTargets#renamedJvm()V",
                 "src/main/kotlin/demo/BomCaller.kt", str(start), str(start + len("renamed")), "4", "5"),
            }, {
                "src/main/kotlin/demo/BomCaller.kt": caller,
                "src/main/kotlin/demo/JvmNameTarget.kt": target,
            }, expected_stats=(1, 1, 0, 0))

    def test_kotlin_pending_call_byte_cap_fails_before_finalization(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-cap-") as temporary:
            root = Path(temporary)
            long_path = root.joinpath(*(("p" * 160,) * 3))
            long_path.mkdir(parents=True)
            source = long_path / "Calls.kt"
            per_function = 50
            call_count = 20_000
            functions = []
            for index in range(call_count // per_function):
                body = "\n".join("    target()" for _ in range(per_function))
                functions.append(f"fun use{index}() {{\n{body}\n}}")
            source.write_text("package demo\nfun target() {}\n" + "\n".join(functions) + "\n", encoding="utf-8")
            output = root / "evidence.tsv"
            output.write_text("stale", encoding="utf-8")
            token = root / "token"
            token.write_text("6" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(self.kotlin_stdlib()), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(source),
            ], root)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists(), result.stderr)
            self.assertIn("resource limit", result.stderr)

    def test_kotlin_scales_exact_symbol_lookup_without_changing_rows(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-kotlin-scale-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/kotlin/demo"
            source_dir.mkdir(parents=True)
            target = source_dir / "ScaleTargets.kt"
            caller = source_dir / "ScaleCaller.kt"
            call_count = 2_000
            per_function = 50
            target.write_text(
                "package demo\n\n" + "\n".join(f"fun target{index}() {{}}" for index in range(call_count)) + "\n",
                encoding="utf-8",
            )
            caller_parts = ["package demo\n\n"]
            offset = len(caller_parts[0])
            line = 3
            expected: set[tuple[str, str, str, str, str, str, str]] = set()
            for group in range(call_count // per_function):
                declaration = f"fun use{group}() {{\n"
                caller_parts.append(declaration)
                offset += len(declaration)
                line += 1
                for index in range(group * per_function, (group + 1) * per_function):
                    selector = f"target{index}"
                    statement = f"    {selector}()\n"
                    start = offset + 4
                    expected.add((
                        f"method:demo/ScaleCallerKt#use{group}()V",
                        f"method:demo/ScaleTargetsKt#target{index}()V",
                        "src/main/kotlin/demo/ScaleCaller.kt",
                        str(start), str(start + len(selector)), str(line), "5",
                    ))
                    caller_parts.append(statement)
                    offset += len(statement)
                    line += 1
                caller_parts.append("}\n")
                offset += 2
                line += 1
            caller.write_text("".join(caller_parts), encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("a" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/java"), "-cp", self.kotlin_classpath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
                "-jdk-home", str(self.jdk17), "-classpath", str(self.kotlin_stdlib()), f"-Xplugin={self.collector}",
                "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                "-P", f"plugin:kartograph.compiler-evidence:output={output}",
                "-P", f"plugin:kartograph.compiler-evidence:token={token}",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-d", str(root / "classes"), str(target), str(caller),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assert_v3_rows(output, expected, {
                "src/main/kotlin/demo/ScaleTargets.kt": target,
                "src/main/kotlin/demo/ScaleCaller.kt": caller,
            }, expected_stats=(call_count, call_count, 0, 0))

    def kotlin_stdlib(self) -> Path:
        stdlib = next(
            (item for item in self.kotlin_classpath.split(os.pathsep)
             if Path(item).name.startswith("kotlin-stdlib-2.4.10")),
            None,
        )
        self.assertIsNotNone(stdlib, self.kotlin_classpath)
        return Path(stdlib)

    def test_javac_call_position_row_cap_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-cap-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/java/demo"
            source_dir.mkdir(parents=True)
            calls = 200_001
            per_method = 50
            methods = []
            for index in range((calls + per_method - 1) // per_method):
                body = "\n".join("        JavaTargets.direct();" for _ in range(min(per_method, calls - index * per_method)))
                methods.append(f"    public void use{index}() {{\n{body}\n    }}")
            users = source_dir / "JavaUsers.java"
            targets = source_dir / "JavaTargets.java"
            users.write_text("package demo;\npublic final class JavaUsers {\n" + "\n".join(methods) + "\n}\n", encoding="utf-8")
            targets.write_text("package demo;\npublic final class JavaTargets { public static void direct() {} }\n", encoding="utf-8")
            output = root / "evidence.tsv"
            output.write_text("stale", encoding="utf-8")
            token = root / "token"
            token.write_text("3" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={output.as_uri()} token={token.as_uri()} callPositions=true",
                "-d", str(root / "classes"), str(users), str(targets),
            ], root)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists(), result.stderr)
            self.assertIn("resource limit", result.stderr)

    def test_javac_scales_target_source_lookup_without_changing_rows(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-positions-javac-scale-") as temporary:
            root = Path(temporary)
            source_dir = root / "src/main/java/demo"
            source_dir.mkdir(parents=True)
            targets = source_dir / "JavaScaleTargets.java"
            callers = source_dir / "JavaScaleCallers.java"
            call_count = 2_000
            target_name = f"target{call_count - 1}"
            targets.write_text(
                "package demo;\nfinal class JavaScaleTargets {\n" +
                "\n".join(f"    static void target{index}() {{}}" for index in range(call_count)) +
                "\n}\n",
                encoding="utf-8",
            )
            caller_parts = ["package demo;\nfinal class JavaScaleCallers {\n"]
            offset = len(caller_parts[0])
            line = 3
            expected: set[tuple[str, str, str, str, str, str, str]] = set()
            for index in range(call_count):
                prefix = f"    static void use{index}() {{ JavaScaleTargets."
                statement = prefix + target_name + "(); }\n"
                start = offset + len(prefix)
                expected.add((
                    f"method:demo/JavaScaleCallers#use{index}()V",
                    f"method:demo/JavaScaleTargets#{target_name}()V",
                    "src/main/java/demo/JavaScaleCallers.java",
                    str(start), str(start + len(target_name)), str(line), str(len(prefix) + 1),
                ))
                caller_parts.append(statement)
                offset += len(statement)
                line += 1
            caller_parts.append("}\n")
            callers.write_text("".join(caller_parts), encoding="utf-8")
            output = root / "evidence.tsv"
            token = root / "token"
            token.write_text("d" * 64, encoding="utf-8")
            result = run([
                str(self.jdk17 / "bin/javac"), "-g", "-proc:none", "-processorpath", str(self.collector),
                "-Xplugin:KartographEvidence collector=javac-constants "
                f"root={root.as_uri()} output={output.as_uri()} token={token.as_uri()} callPositions=true",
                "-d", str(root / "classes"), str(targets), str(callers),
            ], root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assert_v3_rows(output, expected, {
                "src/main/java/demo/JavaScaleTargets.java": targets,
                "src/main/java/demo/JavaScaleCallers.java": callers,
            }, expected_stats=(call_count + 2, call_count, 2, 0))

    def assert_v3_rows(
        self,
        output: Path,
        expected: set[tuple[str, str, str, str, str, str, str]],
        sources: dict[str, Path],
        forbidden: set[tuple[str, str, str, str, str, str, str]] = frozenset(),
        expected_stats: tuple[int, int, int, int] | None = None,
        forbidden_targets: set[str] = frozenset(),
    ) -> None:
        rows = [line.split("\t") for line in output.read_text(encoding="utf-8").splitlines() if line]
        self.assertEqual(["format", "kartograph-compiler-evidence", "3"], rows[0])
        stats_rows = [row for row in rows if row[0] == "callStats"]
        self.assertEqual(1, len(stats_rows))
        stats = stats_rows[0]
        self.assertEqual(5, len(stats))
        observed, emitted, unmapped, ambiguous = map(int, stats[1:])
        self.assertGreaterEqual(observed, 0)
        self.assertGreaterEqual(emitted, 0)
        self.assertGreaterEqual(unmapped, 0)
        self.assertGreaterEqual(ambiguous, 0)
        if expected_stats is not None:
            self.assertEqual(expected_stats, (observed, emitted, unmapped, ambiguous))
        self.assertEqual(observed, emitted + unmapped + ambiguous)
        calls = call_rows(output)
        self.assertTrue(calls)
        self.assertTrue(all(len(row) == 9 for row in calls), calls)
        self.assertEqual(len(calls), emitted)
        self.assertEqual(len(calls), len({tuple(row) for row in calls}), "duplicate call rows must be rejected")
        actual = {
            (base64.urlsafe_b64decode(row[1] + "==").decode(),
             base64.urlsafe_b64decode(row[2] + "==").decode(),
             base64.urlsafe_b64decode(row[3] + "==").decode(),
             row[5], row[6], row[7], row[8])
            for row in calls
        }
        self.assertEqual(expected, actual)
        self.assertTrue(actual.isdisjoint(forbidden), (actual & forbidden))
        self.assertTrue(all(not any(fragment in item[1] for fragment in forbidden_targets) for item in actual), actual)
        source_rows = {base64.urlsafe_b64decode(row[1] + "==").decode(): row[2] for row in rows if row[0] == "source"}
        for path, source in sources.items():
            self.assertEqual(sha256(source), source_rows.get(path))
        for row in calls:
            path = base64.urlsafe_b64decode(row[3] + "==").decode()
            self.assertIn(path, source_rows)
            self.assertEqual(source_rows[path], row[4])


if __name__ == "__main__":
    unittest.main(verbosity=2)
