# Embedded Kotlin / Java PSI comparison

A reproducible, narrowly scoped comparison of actual Kotlin Analysis API + Java PSI with the actual Kartograph CLI. This reconstructs a small independent experiment; it does not restore missing historical evidence or substitute PSI syntax counts for semantic calls.

## Observed result

On 2026-10-13, the adapter used Kotlin Analysis API 2.4.10, repackaged by detekt 2.0.0-alpha.6, and Java PSI on JDK 17.0.20.1+1. Kartograph 0.20.1 was built from `ab056981a016e1b4452de1071bcf209818526849` (PR 158). This experiment is proposed separately on main `2cf3cb6058111906071084f403d73f3574db3c96`; it is not evidence that main produced the recorded graph.

- Exact shared input: two source files, compiled once per attempt with Kotlin 2.4.10 and javac 17; source/compiled-byte/runtime hashes are retained.
- PSI: four resolved method-call site records, two Kotlin and two Java, including both cross-language directions. The Java constructor is a separate boundary observation.
- Kartograph: all eight expected method declarations were present; the same four primary direct-call edges matched exactly. Wrong-overload and unused-method negatives held.
- Bytecode `location`: expected file+line for 8/8 methods; no identifier columns.
- Independent `sourceDeclaration` (`origin=sourceHeader`): exact identifier coordinates for the four uniquely named methods. The four same-arity overloads have no source-header anchor. These are separate location surfaces; columns are not recovered from bytecode.
- Raw Kartograph totals of 12 nodes/27 edges include additional facts and are not compared with PSI call-site counts.

The first attempt emitted the expected rows but reached its 90-second process timeout. It remains a failed attempt. The only adapter correction was an isolated CLI success exit after analysis, disposal, output, flush and output-error checking. A fresh complete run then passed. Runtime JARs, compiled fixture bytes and raw PSI rows matched across attempts. This is not a general embedded-library shutdown guarantee.

[Comparison](results/2026-10-13/comparison.json), [field-specific location readback](results/2026-10-13/location-readback.json), [actual PSI rows](results/2026-10-13/psi-calls.jsonl), [actual product graph](results/2026-10-13/kartograph-graph.json), [terminal provenance](results/2026-10-13/terminal.json), [first timeout](results/2026-10-13/attempt-01-timeout.json).

No precision/recall, performance, whole-project completeness, deletion safety, runtime dispatch, Android, generated-code or AI-productivity ranking follows from this small result.

## Run

Prerequisites: Python 3, POSIX process groups, JDK 17, Gradle (tested 9.7.1), and an installed Kartograph distribution. The caller configures its own authenticated network/proxy/TLS settings if needed. The runner contains no cloud paths, local helper dependency, credentials or TLS bypass.

```sh
export JAVA_HOME=/path/to/jdk17
export GRADLE=/path/to/gradle
export KARTOGRAPH_BIN=/path/to/kartograph/bin/kartograph
python3 experiments/embedded-psi-comparison/run.py \
  --output /path/to/new-evidence-directory
```

The directory must be new. Failed stages keep their logs and exit statuses. The adapter has a 90-second timeout; the experiment checks a 600-second stage budget, with up to five seconds of cleanup grace when stopping a child process. No automatic retry or engine substitution occurs. `runtime-jars.json` pins the actual resolved JAR order and bytes, including transitive dependencies. A mismatch fails rather than silently accepting a different runtime. The product launcher/distribution hashes and graph version identify the tested product.

The portable Python wrapper subsequently completed all 12 stages with exit 0 against a local overload source-header product candidate on the same baseline commit. The source bytes, compiled fixture bytes, runtime JAR inventory, measured adapter/grader and raw PSI rows matched the original successful attempt. All graph edges and bytecode locations stayed identical; the separate source-header check improved from 4/8 to 8/8 exact identifier anchors. [Portable readback and candidate provenance](results/2026-10-13-portable/readback.json), [stage exits](results/2026-10-13-portable/stages.json), [primary-edge comparison](results/2026-10-13-portable/comparison.json), and [eight-anchor check](results/2026-10-13-portable/source-header-check.json) preserve the actual result. The candidate was an uncommitted product change identified by source, patch and distribution hashes; these results do not claim that main includes it. Twelve pure-Python tests also passed, including negative grading and interrupted/timeout runner cleanup controls.

The runtime uses:

- `dev.detekt:detekt-kotlin-analysis-api:2.0.0-alpha.6`
- `dev.detekt:detekt-kotlin-analysis-api-standalone:2.0.0-alpha.6`
- unrelocated Kotlin compiler, stdlib and scripting-jvm 2.4.10
- JetBrains annotations 23.0.0

Kotlin calls are accepted only from `KaSuccessCallInfo`; error candidates are not success. Java calls use `resolveMethod`/`resolveConstructor`. Selected target PSI/navigation elements supply source anchors. The adapter does not read product output or reuse its matching/collector implementation. Do not add compiler-embeddable to this unrelocated `com.intellij` runtime.

## Independent gates

`fixture-manifest.json` freezes source bytes and the original primary grader. `compare.py` checks the exact four-edge set and requires all eight product declarations. PSI emits call-site rows, not an eight-node inventory. Locations and constructor observations do not determine this primary call-edge gate.

`source-location-expectations.json` separately freezes all eight identifier offsets/lines/columns from the authored fixture bytes. `check_source_declarations.py` requires the exact `sourceHeader` evidence including source SHA-256 and coordinate basis; a bytecode location cannot replace it. The recorded baseline intentionally fails this 8/8 gate because its four overload anchors are absent.

```sh
python3 experiments/embedded-psi-comparison/check_source_declarations.py \
  --graph /path/to/new-evidence-directory/kartograph-graph.json \
  --baseline-graph experiments/embedded-psi-comparison/results/2026-10-13/kartograph-graph.json \
  --output /path/to/new-source-header-check.json
python3 -m unittest discover -s experiments/embedded-psi-comparison -p 'test_*.py' -v
```

The optional baseline check independently requires bytecode locations to stay unchanged. Same-arity descriptor/type-aware improvements must retain ambiguity fallbacks; this fixture does not authorize generic, typealias or shadowed-simple-name inference.

`src/EmbeddedPsiDeclarations.java` and `check_declarations.py` preserve the smaller syntax/declaration-only milestone. That adapter was not executed and is not the semantic comparator.

## Source and license provenance

- [Published syntax seed](https://github.com/ictechgy/kartograph/blob/ab056981a016e1b4452de1071bcf209818526849/experiments/ai-utility-v5/SourceDeclarations.java), MIT.
- [Pinned detekt AA packaging](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/detekt-kotlin-analysis-api/build.gradle.kts), [standalone packaging](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/detekt-kotlin-analysis-api-standalone/build.gradle.kts), [versions](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/gradle/libs.versions.toml), Apache-2.0.
- [Mixed-source standalone setup](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/detekt-test-utils/src/main/kotlin/dev/detekt/test/utils/KotlinAnalysisApiEngine.kt), [resolved-call usage](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/detekt-psi-utils/src/main/kotlin/dev/detekt/psi/KtCallExpression.kt), Apache-2.0.
- [Kotlin 2.4.10 successful/error-call contract](https://github.com/JetBrains/kotlin/blob/v2.4.10/analysis/analysis-api/src/org/jetbrains/kotlin/analysis/api/resolution/KaCallInfo.kt), [symbol PSI contract](https://github.com/JetBrains/kotlin/blob/v2.4.10/analysis/analysis-api/src/org/jetbrains/kotlin/analysis/api/symbols/KaSymbol.kt), Apache-2.0.

See `NOTICE.txt` and `semantic/LICENSE-APACHE-2.0`. No third-party binary, local classpath, proxy value or private source is checked in. The immutable results contain only this authored public fixture and artifact hashes. In the original terminal receipt, `productExactIdentifierColumnsAvailable` refers only to bytecode `location`; the separate field-specific readback documents the four existing `sourceDeclaration` anchors.
