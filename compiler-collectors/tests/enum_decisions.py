"""Real javac/Kotlin enum producers, bounded protocol, and snapshot comparison integration."""
from __future__ import annotations

import base64
import json
import os
import re
from pathlib import Path


def decoded(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode()


def utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


def utf16_slice(text, start, end=None):
    # 엄격한 디코딩으로 서로게이트 쌍 중간의 오프셋도 거부한다.
    raw = text.encode("utf-16-le")
    return raw[start * 2:None if end is None else end * 2].decode("utf-16-le")


def source_text(path, collector):
    text = path.read_bytes().decode("utf-8")
    if collector == "kotlin-constants":
        return text.replace("\r\n", "\n").replace("\r", "\n")
    assert collector == "javac-constants"
    return text


def source_coordinates(text, offset, collector):
    prefix = utf16_slice(text, 0, offset)
    lines = re.split(r"\r\n|\r|\n", prefix)
    column = 0
    for character in lines[-1]:
        if character == "\t" and collector == "javac-constants":
            column = (column // 8 + 1) * 8
        else:
            column += utf16_length(character)
    return len(lines), column + 1


def read_enum_rows(path, root, sha256):
    rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()]
    assert rows[0] == ["format", "kartograph-compiler-evidence", "4"], rows[:1]
    collectors = [row[1] for row in rows if row[0] == "collector"]
    assert len(collectors) == 1
    collector = collectors[0]
    stats = [row for row in rows if row[0] == "enumStats"]
    assert len(stats) == 1 and len(stats[0]) == 5
    observed, emitted, unsupported, ignored = map(int, stats[0][1:])
    assert observed == emitted + unsupported + ignored
    decisions = []
    for row in rows:
        if row[0] != "enumDecision":
            continue
        assert len(row) >= 12 and len(row) == 12 + int(row[11])
        source = root / decoded(row[1])
        assert row[2] == sha256(source)
        start, end, line, column = map(int, row[3:7])
        text = source_text(source, collector)
        assert 0 <= start < end <= utf16_length(text)
        assert (line, column) == source_coordinates(text, start, collector)
        assert row[10] in ("true", "false")
        constants = list(map(decoded, row[12:]))
        assert constants == sorted(set(constants)) and len(constants) <= 4096
        decisions.append(dict(source=source, start=start, end=end, text=utf16_slice(text, start, end),
                              line=line, column=column, host=None if row[7] == "-" else decoded(row[7]),
                              enum=decoded(row[8]), kind=row[9], lambda_=row[10] == "true", constants=constants))
    assert len(decisions) == emitted
    return decisions, (observed, emitted, unsupported, ignored), rows


def marked(text, key):
    opening, closing = f"/*site:{key}*/", f"/*end:{key}*/"
    start = text.index(opening) + len(opening)
    end = text.index(closing, start)
    return utf16_length(text[:start]), utf16_length(text[:end]), text[start:end]


def check_sites(decisions, source, expected, collector="kotlin-constants"):
    text = source_text(source, collector)
    assert len(decisions) == len(expected), (len(decisions), expected, decisions)
    for key, (kind, constants, host, lexical_lambda, enum) in expected.items():
        start, end, snippet = marked(text, key)
        matches = [row for row in decisions if row["source"] == source and row["start"] == start and row["end"] == end]
        assert len(matches) == 1, (key, snippet, decisions)
        row = matches[0]
        assert row["text"] == snippet and row["kind"] == kind, (key, row)
        assert row["constants"] == constants and row["host"] == host and row["lambda_"] == lexical_lambda and row["enum"] == enum, (key, row)


def exercise(javac, java, collector, kotlin_classpath, work, run, encoded, sha256, fingerprint):
    root = work / "enum-decisions"
    root.mkdir()
    token = root / "input.token"
    token.write_text("4" * 64, encoding="utf-8")
    stdlib = next(Path(path) for path in kotlin_classpath.split(os.pathsep)
                  if Path(path).name.startswith("kotlin-stdlib-2.4.10"))

    def kotlin(sources, sidecar, classes, enums=True, calls=False, extra=(), classpath=None, expected=0):
        command = [java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-reflect", "-jvm-target", "17", "-jdk-home", java.parent.parent,
                   "-classpath", classpath or stdlib, f"-Xplugin={collector}",
                   "-P", f"plugin:kartograph.compiler-evidence:root={root}",
                   "-P", f"plugin:kartograph.compiler-evidence:output={sidecar}",
                   "-P", f"plugin:kartograph.compiler-evidence:token={token}"]
        if enums is not None:
            command += ["-P", f"plugin:kartograph.compiler-evidence:enumDecisions={str(enums).lower()}"]
        if calls:
            command += ["-P", "plugin:kartograph.compiler-evidence:callPositions=true"]
        return run(command + list(extra) + ["-d", classes] + list(sources), root, expected=expected)

    domain = root / "Domain.kt"
    domain.write_text("""package fixture.domain
enum class Phase { NEW, READY, DONE }
class Holder { enum class Nested { FIRST, SECOND } }
""", encoding="utf-8")
    empty_sidecar = root / "empty.tsv"
    kotlin([domain], empty_sidecar, root / "empty-classes")
    empty_decisions, empty_stats, _ = read_enum_rows(empty_sidecar, root, sha256)
    assert empty_decisions == [] and empty_stats == (0, 0, 0, 0)
    source = root / "Decisions.kt"
    source.write_text("""@file:JvmName("DecisionFacade")
package fixture
import fixture.domain.Phase
import fixture.domain.Phase.NEW as FIRST
import fixture.domain.Holder.Nested
import kotlin.collections.setOf as choices
typealias Status = Phase
fun fallback(value: Status) = /*site:fallback*/when (value) { FIRST, Phase.READY -> 1; else -> 0 }/*end:fallback*/
fun allCovered(value: Phase) = /*site:covered*/when (value) { Phase.NEW, Phase.READY, Phase.DONE -> 1; else -> 0 }/*end:covered*/
fun inSet(value: Phase) = /*site:in*/value in choices(FIRST, Phase.READY)/*end:in*/
fun notInList(value: Phase) = /*site:notin*/value !in listOf(FIRST, Phase.READY)/*end:notin*/
fun directContains(value: Phase) = /*site:direct*/listOf(FIRST, Phase.READY).contains(value)/*end:direct*/
fun singletonSet(value: Phase) = /*site:singleSet*/value in setOf(Phase.READY)/*end:singleSet*/
fun singletonList(value: Phase) = /*site:singleList*/value in listOf(Phase.READY)/*end:singleList*/
fun duplicates(value: Phase) = /*site:duplicate*/value in setOf(Phase.READY, Phase.READY)/*end:duplicate*/
fun emptySetLiteral(value: Phase) = /*site:emptySet*/value in setOf<Phase>()/*end:emptySet*/
fun emptyListLiteral(value: Phase) = /*site:emptyList*/value in listOf<Phase>()/*end:emptyList*/
fun nested(value: Nested) = /*site:nested*/value in setOf(Nested.FIRST)/*end:nested*/
fun lambdaFallback(values: List<Phase>) = values.map { value -> /*site:lambdaWhen*/when (value) { Phase.NEW -> 1; else -> 0 }/*end:lambdaWhen*/ }
val storedLambda: (Phase) -> Boolean = { value -> /*site:lambdaIn*/value !in listOf(Phase.NEW)/*end:lambdaIn*/ }
val anonymousFunction: (Phase) -> Boolean = fun(value: Phase) = /*site:anonymous*/setOf(Phase.NEW).contains(value)/*end:anonymous*/
class MemberHost { fun decide(value: Phase) = /*site:member*/value in setOf(Phase.NEW)/*end:member*/ }
@JvmOverloads fun overloadedDefault(value: Phase = Phase.NEW) = /*site:overloaded*/when (value) { Phase.NEW -> 1; else -> 0 }/*end:overloaded*/
fun receiverParen(value: Phase) = /*site:receiverParen*/(setOf(Phase.NEW)).contains(value)/*end:receiverParen*/
fun exhaustive(value: Phase) = when (value) { Phase.NEW -> 0; Phase.READY -> 1; Phase.DONE -> 2 }
fun nestedBodyElse(value: Phase, enabled: Boolean) = when (value) {
    Phase.NEW -> if (enabled) 1 else 2
    Phase.READY -> 3
    Phase.DONE -> 4
}
fun ordinaryIf(enabled: Boolean) = if (enabled) 1 else 2
""", encoding="utf-8")
    sidecar = root / "positive.tsv"
    kotlin([domain, source], sidecar, root / "positive-classes")
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    phase = "class:fixture/domain/Phase"
    new = "field:fixture/domain/Phase#NEW:Lfixture/domain/Phase;"
    ready = "field:fixture/domain/Phase#READY:Lfixture/domain/Phase;"
    done = "field:fixture/domain/Phase#DONE:Lfixture/domain/Phase;"
    nested_enum = "class:fixture/domain/Holder$Nested"
    nested_first = "field:fixture/domain/Holder$Nested#FIRST:Lfixture/domain/Holder$Nested;"
    positive, negative, fallback = "FINITE_MEMBERSHIP", "NEGATED_FINITE_MEMBERSHIP", "EXPLICIT_FALLBACK"
    expected = {
        "fallback": (fallback, sorted([new, ready]), None, False, phase),
        "covered": (fallback, sorted([new, ready, done]), None, False, phase),
        "in": (positive, sorted([new, ready]), None, False, phase),
        "notin": (negative, sorted([new, ready]), None, False, phase),
        "direct": (positive, sorted([new, ready]), None, False, phase),
        "singleSet": (positive, [ready], None, False, phase),
        "singleList": (positive, [ready], None, False, phase),
        "duplicate": (positive, [ready], None, False, phase),
        "emptySet": (positive, [], None, False, phase),
        "emptyList": (positive, [], None, False, phase),
        "nested": (positive, [nested_first], None, False, nested_enum),
        "lambdaWhen": (fallback, [new], None, True, phase),
        "lambdaIn": (negative, [new], None, True, phase),
        "anonymous": (positive, [new], None, True, phase),
        "member": (positive, [new], "class:fixture/MemberHost", False, phase),
        "overloaded": (fallback, [new], None, False, phase),
        "receiverParen": (positive, [new], None, False, phase),
    }
    check_sites(decisions, source, expected)
    assert stats == (19, 17, 0, 2), stats
    first_rows = decisions
    kotlin([domain, source], sidecar, root / "combined-classes", calls=True)
    decisions, stats, rows = read_enum_rows(sidecar, root, sha256)
    assert decisions == first_rows and stats == (19, 17, 0, 2)
    assert len([row for row in rows if row[0] == "callStats"]) == 1

    # 옵션 생략과 명시적 false 모두 각 이전 교환 형식의 바이트를 그대로 유지한다.
    for calls, version in ((False, "1"), (True, "3")):
        kotlin([domain, source], sidecar, root / "legacy-classes", enums=None, calls=calls)
        before = sidecar.read_bytes()
        kotlin([domain, source], sidecar, root / "legacy-classes", enums=False, calls=calls)
        assert sidecar.read_bytes() == before
        assert before.startswith(f"format\tkartograph-compiler-evidence\t{version}\n".encode())
        assert b"enumStats" not in before and b"enumDecision" not in before

    negation = root / "Negation.kt"
    negation.write_text("""package fixture
import fixture.domain.Phase
import fixture.domain.Phase.NEW
fun positiveParen(v: Phase) = (/*site:paren*/setOf(NEW).contains(v)/*end:paren*/)
fun operatorIn(v: Phase) = /*site:in*/v in setOf(NEW)/*end:in*/
fun ordinaryNotIn(v: Phase) = /*site:notin*/v !in setOf(NEW)/*end:notin*/
fun notDirect(v: Phase) = /*site:reject1*/!setOf(NEW).contains(v)/*end:reject1*/
fun notParen(v: Phase) = /*site:reject2*/!(setOf(NEW).contains(v))/*end:reject2*/
fun doubleNot(v: Phase) = /*site:reject3*/!(!setOf(NEW).contains(v))/*end:reject3*/
fun explicitNot(v: Phase) = /*site:reject4*/setOf(NEW).contains(v).not()/*end:reject4*/
fun stackedNot(v: Phase) = /*site:reject5*/setOf(NEW).contains(v).not().not()/*end:reject5*/
fun negatedNotIn(v: Phase) = /*site:reject6*/!(v !in setOf(NEW))/*end:reject6*/
""", encoding="utf-8")
    kotlin([domain, negation], sidecar, root / "negation-classes")
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    assert stats == (9, 3, 6, 0), stats
    check_sites(decisions, negation, {
        "paren": (positive, [new], None, False, phase),
        "in": (positive, [new], None, False, phase),
        "notin": (negative, [new], None, False, phase),
    })
    text = source_text(negation, "kotlin-constants")
    for index in range(1, 7):
        start, end, _ = marked(text, f"reject{index}")
        assert not any(start <= row["start"] < row["end"] <= end for row in decisions)

    unicode_source = root / "Unicode.kt"
    unicode_source.write_bytes(("package fixture\r\nimport fixture.domain.Phase\r\n" +
        "fun unicode(v: Phase) = /* 😀 */\t/*site:unicode*/v !in setOf(Phase.NEW)/*end:unicode*/\r\n").encode("utf-8"))
    kotlin([domain, unicode_source], sidecar, root / "unicode-classes")
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    assert stats == (1, 1, 0, 0)
    check_sites(decisions, unicode_source, {"unicode": (negative, [new], None, False, phase)})

    unsupported = root / "Unsupported.kt"
    unsupported.write_text("""package fixture
import fixture.domain.Phase
import fixture.domain.Phase.NEW
val saved = setOf(NEW)
fun receiverAlias(v: Phase) = v in saved
fun dynamic(v: Phase) = v in setOf(v)
fun spread(v: Phase, supplied: Array<Phase>) = v in setOf(*supplied)
fun mutable(v: Phase) = v in mutableSetOf(NEW)
fun builder(v: Phase) = v in buildSet { add(NEW) }
fun subjectless(v: Phase) = when { v == NEW -> 1; else -> 0 }
fun nullable(v: Phase?) = when (v) { NEW -> 1; null -> 2; else -> 0 }
fun guarded(v: Phase, enabled: Boolean) = when (v) { NEW if enabled -> 1; else -> 0 }
class Bag { operator fun contains(v: Phase) = true }
fun customContains(v: Phase) = v in Bag()
""", encoding="utf-8")
    kotlin([domain, unsupported], sidecar, root / "unsupported-classes")
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    assert decisions == [] and stats == (9, 0, 9, 0), (stats, decisions)

    # 패키지와 이름만으로 사용자 정의 팩토리의 진위를 인정해서는 안 된다.
    shadow = root / "Shadow.kt"
    shadow.write_text("""package kotlin.collections
import fixture.domain.Phase
fun setOf(element: Phase): Set<Phase> = emptySet()
fun shadowed(v: Phase) = v in setOf(Phase.NEW)
""", encoding="utf-8")
    kotlin([domain, shadow], sidecar, root / "shadow-classes", extra=["-Xallow-kotlin-package"])
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    assert decisions == [] and stats == (1, 0, 1, 0), (stats, decisions)

    # kotlin.collections의 예상하지 않은 바이너리 오버로드도 모델링한 시그니처 검사를 통과해서는 안 된다.
    fake_library = root / "FakeLibrary.kt"
    fake_library.write_text("""package kotlin.collections
import fixture.domain.Phase
fun setOf(element: Phase): Set<Phase> = emptySet()
""", encoding="utf-8")
    fake_classes = root / "fake-library-classes"
    kotlin([domain, fake_library], root / "fake-library.tsv", fake_classes, enums=False,
           extra=["-Xallow-kotlin-package"])
    fake_use = root / "FakeUse.kt"
    fake_use.write_text("""package fixture
import fixture.domain.Phase
fun unexpectedBinaryFactory(v: Phase) = v in setOf(Phase.NEW)
""", encoding="utf-8")
    kotlin([fake_use], sidecar, root / "fake-use-classes",
           classpath=os.pathsep.join(map(str, [stdlib, fake_classes])))
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    assert decisions == [] and stats == (1, 0, 1, 0), (stats, decisions)

    # 생산자는 의존성 enum의 해석된 소유자를 보존한다. importer의 귀속 판단은 별도다.
    library = root / "External.kt"
    library.write_text("package external.fixture\nenum class Phase { NEW }\n", encoding="utf-8")
    library_classes = root / "external-classes"
    kotlin([library], root / "external-library.tsv", library_classes, enums=False)
    external = root / "ExternalUse.kt"
    external.write_text("""package fixture
fun dependency(v: external.fixture.Phase) = v in setOf(external.fixture.Phase.NEW)
""", encoding="utf-8")
    kotlin([external], sidecar, root / "external-use-classes",
           classpath=os.pathsep.join(map(str, [stdlib, library_classes])))
    decisions, stats, _ = read_enum_rows(sidecar, root, sha256)
    assert stats == (1, 1, 0, 0) and decisions[0]["enum"] == "class:external/fixture/Phase"
    assert decisions[0]["constants"] == ["field:external/fixture/Phase#NEW:Lexternal/fixture/Phase;"]

    # 호출 실패 시 이전 v4 파일을 무효화해야 한다.
    broken = root / "Broken.kt"
    broken.write_text("package fixture\nfun broken() =", encoding="utf-8")
    kotlin([domain, source, broken], sidecar, root / "failed-classes", expected=1)
    assert not sidecar.exists()

    _java_producer(javac, collector, root, token, run, sha256)
    _snapshot_comparison(javac, java, collector, kotlin_classpath, stdlib, root, run, encoded, sha256, fingerprint)


def _java_producer(javac, collector, root, token, run, sha256):
    import re

    source = root / "JavaEnumDecisions.java"
    text = """\
package fixture;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class JavaEnumDecisions {
    enum Phase { NEW, READY, DONE }
    static final class Custom { boolean contains(Object value) { return false; } }

    static boolean membership(Phase p) {
\t/* café 😀 */ boolean set = /*site:set*/((Set.of((Phase.NEW), Phase.READY).contains(p)))/*end:set*/;
        boolean list = /*site:list-not*/!(List.of(Phase.NEW, Phase.DONE).contains(p))/*end:list-not*/;
        boolean arrays = /*site:arrays-double-not*/!!(Arrays.asList(Phase.READY, Phase.DONE).contains(p))/*end:arrays-double-not*/;
        BooleanSupplier enumSet = () -> /*site:enumset-double-not*/!(!((EnumSet.of(Phase.NEW, Phase.READY)).contains(p)))/*end:enumset-double-not*/;
        Set<Phase> alias = Set.of(Phase.NEW);
        boolean collectionAlias = /*site:collection-alias*/alias.contains(p)/*end:collection-alias*/;
        final Phase constantAlias = Phase.NEW;
        boolean enumAlias = /*site:constant-alias*/Set.of(constantAlias).contains(p)/*end:constant-alias*/;
        Custom custom = new Custom();
        boolean customResult = /*site:custom*/custom.contains(p)/*end:custom*/;
        Phase[] variable = { Phase.NEW, Phase.READY };
        boolean arrayVariable = /*site:array-variable*/Arrays.asList(variable).contains(p)/*end:array-variable*/;
        return set || list || arrays || enumSet.getAsBoolean()
            || collectionAlias || enumAlias || customResult || arrayVariable;
    }

    static int statement(Phase p) {
        int answer = 0;
        /*site:statement*/switch (p) {
            case NEW: answer = 1; break;
            case READY: answer = 2; break;
            default: answer = -1;
        }/*end:statement*/
        return answer;
    }

    static int expression(Phase p) {
        return /*site:expression*/switch (p) {
            case NEW, DONE -> 1;
            default -> 0;
        }/*end:expression*/;
    }

    static int noDefault(Phase p) {
        int answer = 0;
        /*site:no-default*/switch (p) {
            case NEW: answer = 1; break;
        }/*end:no-default*/
        return answer;
    }

    static int exhaustive(Phase p) {
        return /*site:exhaustive*/switch (p) {
            case NEW -> 1;
            case READY -> 2;
            case DONE -> 3;
        }/*end:exhaustive*/;
    }

    static int nested(Phase p, Phase other) {
        int answer = 0;
        /*site:outer-no-default*/switch (p) {
            case NEW:
                /*site:inner-default*/switch (other) {
                    case READY: answer = 1; break;
                    default: answer = -1;
                }/*end:inner-default*/
                break;
            case READY: answer = 2; break;
            case DONE: answer = 3; break;
        }/*end:outer-no-default*/
        return answer;
    }
}
""".replace("\n", "\r\n")
    source.write_bytes(text.encode("utf-8"))
    sidecar = root / "java-enums.tsv"
    enum_class = "class:fixture/JavaEnumDecisions$Phase"
    owner = "fixture/JavaEnumDecisions$Phase"

    # (소스, 종류, 이름 있는 상수, 렉시컬 람다). 표식은 모든 바깥쪽 !와 ()를 포함해
    # 직접 작성한 식 또는 switch 전체의 경계를 표시한다.
    expected = {
        "set": (source, "FINITE_MEMBERSHIP", ("NEW", "READY"), False),
        "list-not": (source, "NEGATED_FINITE_MEMBERSHIP", ("NEW", "DONE"), False),
        "arrays-double-not": (source, "FINITE_MEMBERSHIP", ("READY", "DONE"), False),
        "enumset-double-not": (source, "FINITE_MEMBERSHIP", ("NEW", "READY"), True),
        "statement": (source, "EXPLICIT_FALLBACK", ("NEW", "READY"), False),
        "expression": (source, "EXPLICIT_FALLBACK", ("NEW", "DONE"), False),
        "inner-default": (source, "EXPLICIT_FALLBACK", ("READY",), False),
    }

    def compile(enums=None, calls=False, sources=None, release=17, expected_exit=0):
        options = "" if enums is None else f" enumDecisions={str(enums).lower()}"
        if calls:
            options += " callPositions=true"
        return run([
            javac, "--release", str(release), "-encoding", "UTF-8", "-g", "-proc:none",
            "-processorpath", collector,
            f"-Xplugin:KartographEvidence collector=javac-constants root={root.as_uri()} output={sidecar.as_uri()} token={token.as_uri()}{options}",
            "-d", root / "java-enum-classes", *(sources or [source]),
        ], root, expected=expected_exit)

    def check(expected_stats, calls):
        decisions, stats, rows = read_enum_rows(sidecar, root, sha256)
        assert stats == expected_stats, (stats, expected_stats)
        assert rows[0] == ["format", "kartograph-compiler-evidence", "4"]
        assert sum(row[0] == "callStats" for row in rows) == int(calls)
        actual = {(row["source"].resolve(), row["start"], row["end"]): row for row in decisions}
        assert len(actual) == len(decisions) == len(expected), decisions
        expected_spans = set()
        by_site = {}
        for key, (path, kind, names, in_lambda) in expected.items():
            authored = source_text(path, "javac-constants")
            start, end, snippet = marked(authored, key)
            span = (path.resolve(), start, end)
            expected_spans.add(span)
            row = actual[span]
            by_site[key] = row
            assert row["text"] == snippet, (key, row)
            assert row["kind"] == kind, (key, row)
            assert row["enum"] == enum_class, (key, row)
            assert row["host"] == "class:fixture/" + path.stem, (key, row)
            assert row["lambda_"] is in_lambda, (key, row)
            assert row["constants"] == sorted(
                f"field:{owner}#{name}:L{owner};" for name in names
            ), (key, row)
            assert (row["line"], row["column"]) == source_coordinates(
                authored, start, "javac-constants"
            ), (key, row)
        assert set(actual) == expected_spans, decisions
        for key in (
            "collection-alias", "constant-alias", "custom", "array-variable",
            "no-default", "exhaustive", "outer-no-default",
        ):
            start, end, _ = marked(text, key)
            assert (source.resolve(), start, end) not in actual, key
        # 독립 기준값: 탭 간격은 8, é는 UTF-16 1단위, 😀는 2단위다.
        assert (by_site["set"]["line"], by_site["set"]["column"]) == (14, 49)
        assert by_site["set"]["text"] == "((Set.of((Phase.NEW), Phase.READY).contains(p)))"
        assert by_site["list-not"]["text"] == "!(List.of(Phase.NEW, Phase.DONE).contains(p))"
        assert by_site["arrays-double-not"]["text"] == "!!(Arrays.asList(Phase.READY, Phase.DONE).contains(p))"
        assert by_site["enumset-double-not"]["text"] == "!(!((EnumSet.of(Phase.NEW, Phase.READY)).contains(p)))"
        return decisions

    # 필수 Java 17 비프리뷰 입력이다. 미지원 멤버십 4개와 무시한 switch 3개는
    # 방출한 행 7개와 구분해야 한다.
    compile(True)
    enum_only = check((14, 7, 4, 3), False)
    compile(True, calls=True)
    assert check((14, 7, 4, 3), True) == enum_only

    # 옵션 생략 또는 false일 때 이전 교환 형식 둘 중 어느 것도 바꾸면 안 된다.
    for calls, version in ((False, b"1"), (True, b"3")):
        compile(None, calls=calls)
        absent = sidecar.read_bytes()
        assert absent.splitlines()[0] == b"format\tkartograph-compiler-evidence\t" + version
        assert not any(line.startswith(b"enum") for line in absent.splitlines())
        compile(False, calls=calls)
        assert sidecar.read_bytes() == absent

    version_result = run([javac, "-version"], root)
    match = re.search(r"\bjavac\s+(\d+)", version_result.stdout + version_result.stderr)
    assert match is not None, version_result
    major = int(match.group(1))
    if major >= 21:
        # 조건부 언어 조합만 검증한다. 현재 통합 실행기는 JDK 17을 필수로 사용하므로
        # 이 JDK 21 이상 분기를 실행하지 않는다.
        modern = root / "JavaEnumDecisionsModern.java"
        modern.write_bytes("""\
package fixture;
import fixture.JavaEnumDecisions.Phase;
final class JavaEnumDecisionsModern {
    static int nullable(Phase p) {
        return /*site:null-default*/switch (p) {
            case null -> -1;
            case NEW -> 1;
            default -> 0;
        }/*end:null-default*/;
    }
    static int exhaustiveNullable(Phase p) {
        return /*site:null-no-default*/switch (p) {
            case null -> -1;
            case NEW, READY, DONE -> 1;
        }/*end:null-no-default*/;
    }
    static int guarded(Phase p) {
        return /*site:guarded-pattern*/switch (p) {
            case Phase matched when matched == Phase.NEW -> 1;
            default -> 0;
        }/*end:guarded-pattern*/;
    }
}
""".encode("utf-8"))
        expected["null-default"] = (modern, "EXPLICIT_FALLBACK", ("NEW",), False)
        compile(True, sources=[source, modern], release=21)
        modern_enum_only = check((17, 8, 5, 4), False)
        compile(True, calls=True, sources=[source, modern], release=21)
        assert check((17, 8, 5, 4), True) == modern_enum_only
        modern_rows = {(row["source"].resolve(), row["start"], row["end"]) for row in modern_enum_only}
        for key in ("null-no-default", "guarded-pattern"):
            start, end, _ = marked(source_text(modern, "javac-constants"), key)
            assert (modern.resolve(), start, end) not in modern_rows, key
    else:
        print("Java enum null/pattern matrix not executed: requires a JDK 21+ compiler.")

    broken = root / "BrokenEnumDecisions.java"
    broken.write_text("package fixture; final class BrokenEnumDecisions {", encoding="utf-8")
    compile(True, sources=[source, broken], expected_exit=1)
    assert not sidecar.exists(), "failed javac compilation published enum evidence"


def _snapshot_comparison(javac, java, collector, kotlin_classpath, stdlib, root, run, encoded, sha256, fingerprint):
    configured = os.environ.get("KARTOGRAPH_SNAPSHOT_CLI")
    if not configured:
        print("enum producer checks passed; set KARTOGRAPH_SNAPSHOT_CLI for completed-witness snapshot comparisons")
        return
    cli = Path(configured).resolve()
    assert cli.is_file(), "KARTOGRAPH_SNAPSHOT_CLI must name the installed CLI"
    for language in ("javac", "kotlin"):
        project = root / ("snapshot-" + language)
        sources = project / "src"
        sources.mkdir(parents=True)
        configuration = project / "build.gradle"
        configuration.write_text("enum decision integration fixture\n", encoding="utf-8")
        output = project / "classes"
        raw = project / "evidence.tsv"
        token_file = project / "input.token"
        witness_file = project / "witness.json"
        scope = "enum-fixture:main"
        task = ":compileJava" if language == "javac" else ":compileKotlin"
        if language == "javac":
            enum_file = sources / "Phase.java"
            enum_file.write_text("package fixture; public enum Phase { NEW, READY }\n", encoding="utf-8")
            other = sources / "unrelated" / "Phase.java"
            other.parent.mkdir()
            other.write_text("package unrelated; public enum Phase { NEW, READY }\n", encoding="utf-8")
            users = sources / "Users.java"
            users.write_text("""package fixture;
import java.util.Set;
public class Users {
  public static int fallback(Phase value) { return switch(value) { case NEW -> 1; default -> 0; }; }
  public static boolean member(Phase value) { return Set.of(Phase.NEW).contains(value); }
  public static boolean negative(Phase value) { return !(Set.of(Phase.NEW).contains(value)); }
  public static boolean unrelated(unrelated.Phase value) { return Set.of(unrelated.Phase.NEW).contains(value); }
  public static int invoke(Phase value) { return fallback(value); }
}
""", encoding="utf-8")
            compiler_paths = [javac]
            compile_options = ["-g", "-proc:none", "enumDecisions=true", "callPositions=true"]
        else:
            enum_file = sources / "Phase.kt"
            enum_file.write_text("package fixture\nenum class Phase { NEW, READY }\n", encoding="utf-8")
            other = sources / "unrelated" / "Phase.kt"
            other.parent.mkdir()
            other.write_text("package unrelated\nenum class Phase { NEW, READY }\n", encoding="utf-8")
            users = sources / "Users.kt"
            users.write_text("""@file:JvmName("DecisionFacade")
package fixture
fun fallback(value: Phase) = when (value) { Phase.NEW -> 1; else -> 0 }
fun member(value: Phase) = value in setOf(Phase.NEW)
fun negative(value: Phase) = value !in listOf(Phase.NEW)
val lambda: (Phase) -> Boolean = { value -> value in setOf(Phase.NEW) }
fun unsupported(value: Phase) = !setOf(Phase.NEW).contains(value)
fun unrelated(value: unrelated.Phase) = value in setOf(unrelated.Phase.NEW)
fun invoke(value: Phase) = fallback(value)
""", encoding="utf-8")
            compiler_paths = [Path(path) for path in kotlin_classpath.split(os.pathsep)]
            compile_options = ["-no-reflect", "-jvm-target", "17", "enumDecisions=true", "callPositions=true"]

        bindings = {}
        def input_row(role, path, slot):
            absolute = path.resolve()
            try:
                identity = absolute.relative_to(project.resolve()).as_posix()
            except ValueError:
                identity = "external/" + slot
                bindings[identity] = absolute
            return dict(role=role, path=identity, sha256=_content_hash(absolute, sha256, sources_only=role == "sources"))

        extra_classpaths = []
        def observe_inputs():
            return [
                input_row("sources", sources, "sources"),
                input_row("buildConfig", configuration, "build-config"),
                *[input_row("compiler", path, f"compiler-{index}") for index, path in enumerate(compiler_paths)],
                input_row("processor", collector, "collector"),
                *[input_row("classpath", path, f"dependency-{index}") for index, path in enumerate(extra_classpaths)],
                dict(role="options", path="compile-options", sha256=_values(compile_options)),
                dict(role="options", path="compiler-enum-decisions", sha256=_values(["enumDecisions=true"])),
                dict(role="options", path="compiler-call-positions", sha256=_values(["callPositions=true"])),
            ]

        def capture(name, expected_stats=None, extra_classpath=None):
            import shutil
            if output.exists():
                shutil.rmtree(output)
            extra_classpaths.clear()
            if extra_classpath is not None:
                extra_classpaths.append(extra_classpath)
            inputs = observe_inputs()
            token = _values(["kartograph-compiler-inputs-v1", scope, language, task] +
                            [value for item in inputs for value in (item["role"], item["path"], item["sha256"])])
            token_file.write_text(token, encoding="utf-8")
            if language == "javac":
                dependency_options = [] if extra_classpath is None else ["-classpath", extra_classpath]
                run([javac, "-g", "-proc:none", *dependency_options, "-processorpath", collector,
                     f"-Xplugin:KartographEvidence collector=javac-constants root={project.as_uri()} output={raw.as_uri()} token={token_file.as_uri()} enumDecisions=true callPositions=true",
                     "-d", output, enum_file, other, users], project)
            else:
                run([java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                     "-no-reflect", "-jvm-target", "17", "-jdk-home", java.parent.parent,
                     "-classpath", str(stdlib) if extra_classpath is None else os.pathsep.join(map(str, [stdlib, extra_classpath])), f"-Xplugin={collector}",
                     "-P", f"plugin:kartograph.compiler-evidence:root={project}",
                     "-P", f"plugin:kartograph.compiler-evidence:output={raw}",
                     "-P", f"plugin:kartograph.compiler-evidence:token={token_file}",
                     "-P", "plugin:kartograph.compiler-evidence:enumDecisions=true",
                     "-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                     "-d", output, enum_file, other, users], project)
            assert observe_inputs() == inputs, "compiler inputs changed during the successful compilation"
            decisions, stats, rows = read_enum_rows(raw, project, sha256)
            assert stats == (expected_stats or ((4, 4, 0, 0) if language == "javac" else (6, 5, 1, 0))), stats
            inventory = {(decoded(row[1]), row[2]) for row in rows if row[0] == "source"}
            assert inventory == {(path.relative_to(project).as_posix(), sha256(path)) for path in (enum_file, other, users)}
            # 컴파일 성공 후에만 전후 입력과 정확한 단위가 일치하는지 확인하고 완료 영수증을 만든다.
            witness = dict(format="kartograph-build-witness", version=2, scope=scope, compiler=language,
                           artifact=task, inputs=inputs, outputs=[input_row("classes", output, "classes")],
                           compilerEvidence=[input_row("compilerEvidence", raw, "evidence")], evidenceToken=token)
            witness_file.write_text(json.dumps(witness, sort_keys=True) + "\n", encoding="utf-8")
            command = [cli, "snapshot", "--project", project, "--classes", output, "--scope", scope,
                       "--build-witness", witness_file, "--compiler-evidence", raw, "--source-root", "src",
                       "--build-input", "build.gradle", "--include-paths"]
            if extra_classpath is not None:
                command += ["--classpath", extra_classpath]
            for slot, path in bindings.items():
                command += ["--input", f"{slot}={path}"]
            ordinary = json.loads(run(command, project).stdout)
            captured_result = run(command + ["--include-enum-decisions"], project)
            captured = json.loads(captured_result.stdout)
            assert ordinary["graph"]["nodes"] == captured["graph"]["nodes"]
            assert ordinary["graph"]["edges"] == captured["graph"]["edges"]
            assert ordinary["retention"] == captured["retention"]
            snapshot_file = project / f"{name}.json"
            snapshot_file.write_text(captured_result.stdout, encoding="utf-8")
            if name == "current":
                # 같은 완료 영수증으로 단독·결합 캡처를 비교해 두 증거 채널의 보존을 검증한다.
                source_result = run(command + ["--include-source-uses"], project)
                source_only = json.loads(source_result.stdout)
                combined_result = run(command + ["--include-enum-decisions", "--include-source-uses"], project)
                combined = json.loads(combined_result.stdout)
                source_evidence = source_only["graph"]["compilerSourceUses"]
                enum_evidence = captured["enumDecisions"]
                assert source_evidence["uses"] and enum_evidence["sites"]
                assert "enumDecisions" not in source_only
                assert "compilerSourceUses" not in captured["graph"]
                assert combined["graph"]["compilerSourceUses"] == source_evidence
                assert combined["enumDecisions"] == enum_evidence
                for snapshot in (source_only, combined):
                    assert snapshot["graph"]["nodes"] == ordinary["graph"]["nodes"]
                    assert snapshot["graph"]["edges"] == ordinary["graph"]["edges"]
                    assert snapshot["retention"] == ordinary["retention"]
                host = "fixture/Users" if language == "javac" else "fixture/DecisionFacade"
                target = f"method:{host}#fallback(Lfixture/Phase;)I"
                caller = f"method:{host}#invoke(Lfixture/Phase;)I"
                observed = [use for use in source_evidence["uses"] if use["source"] == caller and use["target"] == target]
                assert len(observed) == 1, observed
                use = observed[0]
                assert use["path"] == "src/" + users.name and use["sourceSha256"] == sha256(users)
                source = source_text(users, "javac-constants" if language == "javac" else "kotlin-constants")
                assert utf16_slice(source, use["offsetUtf16"], use["endOffsetUtf16"]) == "fallback(value)"
                source_file = project / f"{name}-source-uses.json"
                source_file.write_text(source_result.stdout, encoding="utf-8")
                combined_file = project / f"{name}-combined.json"
                combined_file.write_text(combined_result.stdout, encoding="utf-8")
                queries = [json.loads(run([cli, "query", target, "--graph-file", path, "--source-uses"], project).stdout)
                           for path in (source_file, combined_file)]
                assert all(query["format"] == "kartograph-source-use-query" for query in queries)
                assert queries[0]["sourceUses"] == queries[1]["sourceUses"]
                result = queries[1]["sourceUses"]
                assert result["selectionStatus"] == "found" and result["subject"] == target
                assert result["page"]["observedUses"] == result["page"]["returnedUses"] == 1
                assert result["page"]["observations"][0]["use"] == use
                snapshot_file, captured = combined_file, combined
            return snapshot_file, captured

        base_file, base_snapshot = capture("base")
        original_enum = enum_file.read_text(encoding="utf-8")
        enum_file.write_text(original_enum.replace("NEW, READY", "NEW, READY, DONE"), encoding="utf-8")
        current_file, current_snapshot = capture("current")
        command = [cli, "impact", "--enum-expansion", "--graph-file", current_file, "--base-graph", base_file,
                   "--symbol", "class:fixture/Phase", "--format", "json"]
        report = json.loads(run(command, project).stdout)
        _assert_expansion_report(report, language, users, json.loads(witness_file.read_text(encoding="utf-8")))
        text_report = run(command[:-1] + ["text"], project).stdout
        assert "fixture/Phase" in text_report and "DONE" in text_report
        unchanged = json.loads(run([cli, "impact", "--enum-expansion", "--graph-file", current_file, "--base-graph", base_file,
                                    "--symbol", "class:unrelated/Phase", "--format", "json"], project).stdout)
        _assert_no_expansion(unchanged)

        # 외부 enum은 위치가 있는 사실로 보존하지만 이 작업의 출력에 귀속하지 않는다.
        dependency_sources = project / "dependency-src"
        dependency_sources.mkdir()
        dependency_classes = project / "dependency-classes"
        if language == "javac":
            dependency = dependency_sources / "Phase.java"
            dependency.write_text("package external; public enum Phase { NEW }\n", encoding="utf-8")
            run([javac, "-g", "-proc:none", "-d", dependency_classes, dependency], project)
            users.write_text("""package fixture;
public class Users {
  public static boolean dependency(external.Phase value) { return java.util.Set.of(external.Phase.NEW).contains(value); }
}
""", encoding="utf-8")
        else:
            dependency = dependency_sources / "Phase.kt"
            dependency.write_text("package external\nenum class Phase { NEW }\n", encoding="utf-8")
            run([java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                 "-no-reflect", "-jvm-target", "17", "-jdk-home", java.parent.parent,
                 "-classpath", stdlib, "-d", dependency_classes, dependency], project)
            users.write_text("""package fixture
fun dependency(value: external.Phase) = value in setOf(external.Phase.NEW)
""", encoding="utf-8")
        external_file, _ = capture("external", (1, 1, 0, 0), dependency_classes)
        unavailable = json.loads(run([cli, "impact", "--enum-expansion", "--graph-file", external_file,
                                      "--base-graph", base_file, "--symbol", "class:external/Phase",
                                      "--format", "json"], project).stdout)
        assert unavailable["candidates"] == []
        assert unavailable["coverage"]["evidenceAvailable"] is True
        assert unavailable["coverage"]["resolvedSites"] == 0
        assert unavailable["coverage"]["unresolvedSites"] == 1
        assert unavailable["coverage"]["producerEmitted"] == 1
        assert unavailable["coverage"]["producerUnsupported"] == 0
        assert len(unavailable["unresolved"]) == 1
        assert unavailable["unresolved"][0]["enum"] == "class:external/Phase"
        assert unavailable["unresolved"][0]["reason"] in ("ENUM_ROOT_UNRESOLVED", "OUTSIDE_CLAIMED_OUTPUTS")
        assert unavailable["unresolved"][0]["source"]["path"] == "src/" + users.name


def _values(values):
    import hashlib
    digest = hashlib.sha256()
    for value in values:
        raw = value.encode("utf-8")
        digest.update(len(raw).to_bytes(4, "big"))
        digest.update(raw)
    return digest.hexdigest()


def _content_hash(path, sha256, sources_only=False):
    if path.is_file():
        return _values(["file", sha256(path)])
    values = ["directory"]
    members = sorted((item for item in path.rglob("*") if item.is_file() and
                      (not sources_only or item.suffix in (".java", ".kt"))),
                     key=lambda item: item.relative_to(path).as_posix())
    for item in members:
        values += [item.relative_to(path).as_posix(), sha256(item)]
    return _values(values)


def _assert_expansion_report(report, language, users, witness):
    assert report["format"] == "kartograph-enum-expansion" and report["version"] == 1
    added = "field:fixture/Phase#DONE:Lfixture/Phase;"
    listed = "field:fixture/Phase#NEW:Lfixture/Phase;"
    comparisons = report["comparisons"]
    assert len(comparisons) == 1
    assert comparisons[0]["enum"] == "class:fixture/Phase"
    assert comparisons[0]["status"] == "AVAILABLE"
    assert comparisons[0]["addedConstants"] == [added] and comparisons[0]["removedConstants"] == []
    expected_count = 3 if language == "javac" else 4
    candidates = report["candidates"]
    assert len(candidates) == expected_count
    navigation = report["navigation"]
    assert navigation["totalCandidates"] == navigation["returnedCandidates"] == expected_count
    assert navigation["omittedCandidates"] == 0 and navigation["offset"] == 0
    coverage = report["coverage"]
    assert coverage["evidenceAvailable"] is True
    assert coverage["resolvedSites"] == (3 if language == "javac" else 4)
    assert coverage["unresolvedSites"] == coverage["viewUnavailableSites"] == 0
    assert (coverage["producerObserved"], coverage["producerEmitted"], coverage["producerUnsupported"],
            coverage["producerIgnored"]) == ((4, 4, 0, 0) if language == "javac" else (6, 5, 1, 0))
    assert report["unresolved"] == []
    collector = "javac-constants" if language == "javac" else "kotlin-constants"
    text = source_text(users, collector)
    kinds = []
    for candidate in candidates:
        assert candidate["enum"] == "class:fixture/Phase"
        assert candidate["revision"] == "current"
        assert candidate["addedConstants"] == [added] and candidate["listedConstants"] == [listed]
        assert candidate["attribution"] == "RESOLVED_SAME_OUTPUT_ROOT"
        assert candidate["sourceRootId"] is not None and candidate["enumRootId"] is not None
        source = candidate["source"]
        assert source["path"] == "src/" + users.name
        assert source["sha256"] == __import__("hashlib").sha256(users.read_bytes()).hexdigest()
        start, end = source["offsetUtf16"], source["endOffsetUtf16"]
        assert (source["line"], source["column"]) == source_coordinates(text, start, collector)
        snippet = utf16_slice(text, start, end)
        assert "value" in snippet and "NEW" in snippet
        if candidate["kind"] == "EXPLICIT_FALLBACK":
            assert snippet.startswith("switch" if language == "javac" else "when")
        else:
            assert "Phase.NEW" in snippet
        assert "unsupported" not in snippet
        assert not snippet.startswith("!") or candidate["kind"] == "NEGATED_FINITE_MEMBERSHIP"
        assert source["coordinateBasis"] == ("JAVAC_UTF16_CHAR_SEQUENCE" if language == "javac" else "KOTLIN_UTF16_NORMALIZED_SOURCE")
        evidence = candidate["evidence"]
        assert evidence["collector"] == ("javac-constants" if language == "javac" else "kotlin-constants")
        assert evidence["inputToken"] == witness["evidenceToken"]
        assert evidence["receipt"] in witness["compilerEvidence"]
        assert evidence["claims"]
        kinds.append(candidate["kind"])
    assert kinds.count("EXPLICIT_FALLBACK") == 1
    assert kinds.count("FINITE_MEMBERSHIP") == (1 if language == "javac" else 2)
    assert kinds.count("NEGATED_FINITE_MEMBERSHIP") == 1
    assert sum(row["lexicalLambda"] for row in candidates) == (0 if language == "javac" else 1)


def _assert_no_expansion(report):
    assert report["format"] == "kartograph-enum-expansion" and report["version"] == 1
    assert report["candidates"] == [] and report["navigation"]["totalCandidates"] == 0
    assert report["coverage"]["resolvedSites"] == 1
    comparisons = report["comparisons"]
    assert len(comparisons) == 1 and comparisons[0]["enum"] == "class:unrelated/Phase"
    assert comparisons[0]["status"] == "AVAILABLE"
    assert comparisons[0]["addedConstants"] == comparisons[0]["removedConstants"] == []
