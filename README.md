# kartograph

Queryable dependency graphs for Kotlin/Android codebases. A sister project of cartograph (Swift) that shares its design and exchange contracts.

> [한국어](README.ko.md)

The name is **K**otlin + cartograph. Where cartograph maps iOS, kartograph maps Android.

## What it does

kartograph builds a dependency graph from compiled Kotlin/Android code and explains why a declaration is reachable, retained, or unreachable:

- The source of truth is what the compiler recorded, not text search.
- Unused code, dependency cycles, layer rules, and architecture metrics all come from one graph.
- Every verdict carries evidence. No verdict approves a deletion.
- `query` and `skill` were built for agent consumers from day one.

Android has one unfair advantage: "looks unused but must not be deleted" has been codified for over a decade as **ProGuard/R8 keep rules**. The retention knowledge the Swift side had to collect by hand already lives in this ecosystem.

## Status

The current source version is declared in [VERSION](VERSION). Released versions and artifacts are on [GitHub Releases](https://github.com/ictechgy/kartograph/releases). The truth-source experiments settled on JVM bytecode plus official Kotlin metadata as the primary graph; the rationale is in [`docs/DECISION-truth-source.md`](docs/DECISION-truth-source.md).

Working today:

- `impact` checks the potential effect of a planned symbol edit, or of the files changed since a base commit, using captured graphs. It reports base/current paths, deletions, runtime evidence and uncertainty identically to people, agents and CI. See [change impact](docs/IMPACT.md) and the [scored public replays](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/change-impact/README.md).
- `graph` renders compiled class roots as DOT or as a `code-graph` JSON exchange document. With `--include-paths --project` it also resolves project-relative source paths. The JSON records edge origins and external calls with their resolution status. Repeating `--classes` merges several module/variant outputs; when a JVM class appears in more than one root, the first root always wins.
- `dead` reports unreachable class declarations from Android retention roots (manifest, XML, `@Keep`, keep rules, inheritance hierarchies, DI/serialization annotations, JNI and framework callbacks). It supports `--explain`, baselines, expiring `--suppress` entries, `--since`, and machine-readable reports (text/gradle/github-actions/sarif/json/markdown). JSON, SARIF and markdown findings carry a `confidence` tier (static / needs-runtime-review / runtime-observed / unmeasured) derived from the unresolved runtime channels measured in the declaration's own source file or from user-supplied runtime evidence. Recursive includes and consumer rules are supported. Keep rules that produced no retention evidence are reported as `unmatched-keep-rule` input diagnostics with file:line provenance — a measurement, not proof the rule can be removed. When findings exist, inputs that were not supplied (keep rules, dependency classpath, or a manifest with component declarations) are reported as `input-hint` diagnostics so a possibly over-reported result is visible before it is trusted. Optional `--runtime-classes` and `--coverage` inputs (user-supplied class lists and JaCoCo/Kover XML) mark findings whose class was observed as `runtime-observed` confidence; they change neither findings nor exit codes.
- `why <symbol>` answers in one step why a declaration is retained, reachable, or unreachable: retention evidence with file:line provenance, the representative path from a retention root, direct callers, a test-only marker, and a measured confidence tier for unreachable declarations. The answer is a reachability fact, not a deletion approval.
- `query`/`bridges`/`skill` give agents the users, dependencies and reachability of a single symbol, plus Flutter/React Native bridge facts, instead of a full graph dump. `query` also reports measured counts of unresolved runtime paths and conservative dispatch candidates. React Native coverage spans core `@ReactModule`/`@ReactMethod` and Expo Modules (`class X : Module()` with `ModuleDefinition { Name(...) / Function(...) / View(...) }`); Expo `module-export`/`component-export` facts carry `"mechanism": "expo"` so isthmus keeps Expo and core resolution paths separate.
- `dependencies` compares a declared dependency list (TSV: coordinate, scope, artifact) with bytecode references from the supplied class roots and reports `unused-dependency` findings. It does not run the build or collect coverage; processor and runtime-only scopes are counted but not judged. See [declared dependencies](docs/DEPENDENCIES.md). Version 0.12.0 adds `--library`
  API/implementation advice, `--resolved-dependencies` ownership checks, all six report formats,
  and JVM/Android `kartographDependencies` tasks. Version 0.13.0 adds exact baselines, expiring suppressions, and capture of all observed diagnostics before filtering.
- Optional [processor source attribution](docs/PROCESSOR-GENERATION.md) records actual JSR-269 Filer outputs and their generating artifact through completed compiler receipts. The collector is built separately from the tagged source; attribution does not change reachability or dependency-unused decisions.
- `cycles`/`rules`/`metrics` analyze module/package cycles with weakest edges, fail-closed layer YAML, and Martin Ca/Ce/I/A/D metrics.
- The Gradle plugin registers `kartographDead<Variant>` and `kartographGraph<Variant>` per Android variant over the AGP public Variant API.
- The Gradle plugin's `kartographSnapshot` and `kartographSnapshot<Variant>` tasks capture JVM main/test and Android main/unit-test inputs automatically, together with compiler witnesses, for repeated impact queries. Android application variants also cover the generated `R.jar` through a `processResources` producer witness. See [automatic capture and toolchain configuration](docs/IMPACT.md#jvm-빌드에서-자동-캡처) and the [build provenance contract](docs/BUILD-PROVENANCE.md).
- Optional [incremental parsing](docs/INDEX-CACHE.md) reuses unchanged class facts and dependency JAR headers. Current inputs are still checked and the analysis is rebuilt on every capture.
- The [MCP stdio server](docs/MCP.md) exposes `query_symbol`, `impact` and `freshness` over fixed local snapshots, using the same reports as the CLI.
- Keep-rule parsing fails closed with file and line instead of silently dropping unsupported syntax. Errors and evidence never print absolute paths.

Class loading, reflective construction, and known method/field access are connected through bounded intra-method value tracking; external dispatch uses conservative hierarchy candidates. `META-INF/services` registrations in class roots and in explicit CLI `--service-resources` inputs retain their providers. The Gradle plugin supplies the selected variant's Java resource source directories. In the external-call JSON, the matching API model and the resolution result are separate fields. Optional [compiler collectors](docs/COMPILER-EVIDENCE.md) add javac/Kotlin 2.4.10 constant references and javac Dagger 2.59 selected bindings to snapshots. These collectors must be built and connected explicitly; their supported patterns and remaining gaps are documented. The primary graph and retention policy still apply. Callgraph precision remains an experiment.

See [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) for what the graph cannot see, and [`docs/PHASE2-VALIDATION.md`](docs/PHASE2-VALIDATION.md) for measured retention behavior.

The analyzer also tracks immutable arguments and String/Class return values through bounded project static helpers. In [five executed comparison fixtures](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/runtime-returns/README.md) this recovers three previously missed reflection paths while keeping every unused control distinct. The same report compares SearchDeadCode and current R8, including optimization controls and a remaining unknown-input failure. It does not establish overall accuracy or speed superiority. Static field values and reflective reads receive additional bounded tracking, with unknown assignments and analysis limits retained. Exact private/final instance helpers, including Kotlin object/companion methods, get the same bounded String/Class return tracking. This recovers the runtime targets of four more executed Java/Kotlin cases; overridable methods and unknown receiver state remain unresolved. The [expanded evaluation](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/impact-evaluation/README.md) records concrete pre-edit review benefits on Java/Kotlin, and also the AI repair result: 6/12 passes in each condition with no graph queries. A general AI productivity gain remains unproven.

## Installation and compatibility

Download the CLI archive from GitHub Releases. The Gradle plugin `io.github.ictechgy.kartograph` becomes installable once its version appears on the [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph); a GitHub Release and Portal approval are separate events.

- Building kartograph from source is verified with JDK 17 or 21 and Gradle 9.6.1.
- Android graph/dead tasks: AGP 8.7+, Gradle 8.10+, JDK 17+ (verified with AGP 8.7.3 / Gradle 8.10.2 and with AGP 9.x).
- Automatic snapshots: JVM Java/Kotlin on Gradle 9.6.1 and JDK 17/21, plus the [tested Android combinations](docs/IMPACT.md#android-variant-자동-캡처). The Kotlin compiler adapter is verified with KGP 2.4.10; other KGP versions are not guaranteed. The minimum Android combination was tested on Gradle 8.10.2, although KGP itself recommends 8.14.4 or later.

```kotlin
plugins {
    id("io.github.ictechgy.kartograph") version "0.13.0"
}
```

Download `kartograph-<version>.zip` or `.tar` from a GitHub release. For 0.5.0 and later, check its SHA256 against the matching entry in `SHA256SUMS` before unpacking, then run `bin/kartograph`. Releases also include CycloneDX runtime SBOMs. Detached signatures are not published.

## Usage

```bash
# Dependency graph as DOT.
cli/build/install/kartograph/bin/kartograph graph \
  --classes path/to/build/tmp/kotlin-classes/debug \
  --format dot

# Exchange JSON for other tools. --include-paths resolves each source file name
# against --project and reports the project-relative path with its pathKind origin.
# The kartographGraph<Variant> Gradle task writes the same document.
cli/build/install/kartograph/bin/kartograph graph \
  --classes path/to/build/tmp/kotlin-classes/debug \
  --format json \
  --include-paths \
  --project path/to/project

# Unreachable declarations. The output is a reachability fact, not a deletion approval.
cli/build/install/kartograph/bin/kartograph dead \
  --classes path/to/compiled/classes \
  --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml \
  --resources app/src/main/res \
  --namespace dev.example.app \
  --keep-rules app/proguard-rules.pro \
  --classpath path/to/dependency/classes.jar \
  --test-classes path/to/test/classes \
  --strict

# Pin current findings, then gate only new ones on strict.
# A relative --write path resolves against --project, not the calling shell.
cli/build/install/kartograph/bin/kartograph baseline --write .kartograph-baseline.json \
  --classes path/to/compiled/classes --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml --resources app/src/main/res \
  --namespace dev.example.app
cli/build/install/kartograph/bin/kartograph dead \
  --classes path/to/compiled/classes --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml --resources app/src/main/res \
  --namespace dev.example.app --baseline .kartograph-baseline.json \
  --since origin/main --report-format sarif --strict
```

```bash
# One symbol instead of a full graph dump.
kartograph query UserService --classes path/to/classes --project . --depth 2 --limit 100
kartograph bridges --project . --format json
# Opt-in Flutter BasicMessageChannel facts for Kotlin/JVM sources.
kartograph bridges --project . --target flutter --messages --graph-file build/reports/kartograph/main-graph.json
kartograph skill --project .
```

```bash
kartograph cycles --classes path/to/classes --strict
kartograph rules --classes path/to/classes --config .kartograph.yml --strict
kartograph metrics --classes path/to/classes
```

The Gradle plugin writes reports to `build/reports/kartograph/<variant>.txt` and graph documents to `build/reports/kartograph/<variant>-graph.json`:

```kotlin
plugins {
    id("io.github.ictechgy.kartograph")
}

kartograph {
    keepRules.from("proguard-rules.pro", "path/to/dependency/consumer-rules.pro")
    strict.set(true)
    baseline.set(layout.projectDirectory.file(".kartograph-baseline.json"))
    reportFormat.set("github-actions") // gradle, github-actions, sarif, json, markdown, text
    includeSourcePaths.set(true) // resolve project-relative source paths into the graph document (default false)
}
```

```bash
./gradlew kartographDeadDebug
./gradlew kartographGraphDebug
```

AGP does not expose dependency consumer rules as a merged file through the public Variant API, so pass those files explicitly. The dead task never reuses up-to-date/cache results, because keep-rule includes are only discovered while it runs. The graph task skips reuse only when source-path resolution is on, since that reads project sources it has not declared.

### Saved graph queries and generated inputs

These features are included in the 0.10.1 binaries.
Use `snapshot` to capture the graph, retention evidence, baseline state, and measured limitations once.
Pass the same manifest/resource/namespace/keep/consumer/classpath inputs and the same private-member option as the live query.

```bash
kartograph snapshot --classes path/to/classes --project . \
  --keep-rules proguard-rules.pro > graph.snapshot.json
kartograph query UserService --graph-file graph.snapshot.json --depth 2 --limit 100
```

Saved queries do not reread current sources or rules and report a `saved-graph` limitation. Recapture after changes.
Ordinary `graph --format json` output lacks retention context and cannot be used as a query snapshot.

Mark compiled outputs that contain only generated code with `--generated-classes`, while still including them in `--classes`.
The marker is shared by `dead`, `baseline`, `graph`, `query`, and `snapshot`.

```bash
kartograph graph --classes path/to/normal/classes --classes path/to/generated/classes \
  --generated-classes path/to/generated/classes --format json
```

Nodes and edges remain, with `synthesized` and `generatedInput` marking their origin. Do not mark roots that mix generated and handwritten code. In Gradle, configure `kartograph.generatedClassRoots` or the variant task's `generatedClassRoots`; each marked root must also be a project class input of that task. Class names are not used to infer this origin.

The extension applies to every variant. For variant-specific outputs, configure `generatedClassRoots` on the named variant tasks instead; a debug-only root at extension level cannot match the release task's inputs.

### Private members

Private-member diagnostics are opt-in via `dead --include-private-members` (added in 0.2.0, not in 0.1.x). On top of the default class report, this adds private methods and fields/properties of reachable, non-synthesized classes. Use the same option for baselines and `query`. In Gradle: `kartograph { includePrivateMembers.set(true) }`.

This mode conservatively retains `-keepclassmembers` targets together with their owners, so it can report fewer class findings than class-only mode. Constructors, natives, synthesized members, compile-time constants, file facades, and members under unreachable owners are not reported. Field writes count as uses, so this is not an unread-field check. An unexplainable `-keepclassmembers` signature widens to all direct members of the matching classes, while ordinary `-keep` parsing still fails closed. Members reachable from assumed-external entry points keep their private helpers too (explained as `EXTERNAL_MEMBER_ENTRY`, which can under-report). Private reflection/serialization conventions are not fully proven, so review keep/consumer rules and runtime tests alongside.

## Development and verification

To block **all newly introduced diagnostics** in a PR, follow the [PR gate guide](docs/PR-CHECK.md). The released `Scripts/check-pr.py` reads the base commit's baseline and also checks untouched files. `--since` is a changed-files filter, so it differs from the PR gate, which must catch the blast radius of a caller deletion. Measurements on public samples (Hilt/Compose/KSP), and the limits that remain, are in the [public validation record](docs/PUBLIC-VALIDATION.md).

The following development checks require a source checkout and JDK 17+.

```bash
./gradlew test
./gradlew :koverVerify
./gradlew :cli:installDist
Scripts/verify-cli-contract.sh
Scripts/verify-fixture-corpus.sh
Scripts/verify-gradle-plugin-fixture.sh
Scripts/verify-agent-surface.sh
python3 -m unittest discover -s Scripts/tests -v
python3 Scripts/verify-runtime-corpus.py # 13 Java/Kotlin cases; JDK 17
python3 Scripts/verify-runtime-contracts.py # 6 differential cases; SDK Build Tools 35.0.0
python3 experiments/compiler-references/run.py # source checkout only; JDK 17
python3 experiments/dagger-bindings/run.py # source checkout only; JDK 17
python3 experiments/callgraph-precision/run.py # source checkout only; JDK 17
Scripts/verify-release-readiness.sh # two clean builds, never publishes
```

## Interpreting results safely

A finding, including `unreachable`, is a fact about the input graph you supplied. **It never says any code is safe to delete.** Reflection, JNI, dynamic registration, missing variants or classpaths, and stale build outputs can all change the result. Before changing code, review `--explain`, the runtime paths, and that variant's tests. The full boundaries are in [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md). How local inputs are handled, and what to review before publishing reports, is in [`SECURITY.md`](SECURITY.md).

| Document | Contents |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | What, for whom, how far, and what it will not do |
| [`docs/PLAN.md`](docs/PLAN.md) | Staged plan. Phase 0 is a **source-decision experiment**, not code |
| [`docs/PHASE3-ADOPTION.md`](docs/PHASE3-ADOPTION.md) | Baseline, `--since`, machine reports, and the Gradle adoption contract |
| [`docs/PHASE4-AGENT.md`](docs/PHASE4-AGENT.md) | Query, measured limitations, bridge-facts, and the agent skill contract |
| [`docs/PHASE5-VALIDATION.md`](docs/PHASE5-VALIDATION.md) | Cycles, layer rules, Martin metrics, self-analysis, and performance evidence |
| [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) | Analysis boundaries and safe reading of findings |
| [`docs/RESEARCH.md`](docs/RESEARCH.md) | Confirmed facts, unconfirmed claims, and sources |

## License

kartograph is MIT licensed. Copyright and license texts of the dependencies bundled in distributions ship together in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`LICENSES/`](LICENSES/).

## External bridge evidence

`dead --external-retentions <file>` reads v0 documents from isthmus 0.8.0+
`retentions --for kartograph`. Every actual JVM node ID must exist in the indexed graph; malformed
or unmatched input fails without partial application. The called member's containing-type chain is retained. The normal opt-in private-member
entry policy still applies; the owner expansion itself does not select sibling methods. `dead --explain` shows EXTERNAL_BRIDGE with the
original Dart/JS caller locations, channel, method and omitted caller count. Supply the normal
required dead arguments as well.

`bridges --rn-events [--target react-native]` exports explicit
`getJSModule(...RCTDeviceEventEmitter::class.java).emit(...)` calls (Java `.class` is also supported)
in a separate v2 `react-native-event` document. `--graph-file` can attach actual JVM identities.
Capture snapshots with `snapshot --include-paths` so bridge source paths can match indexed methods.
Expo/codegen events and emitter variables/wrappers are not resolved. This flag is separate from
Flutter `--events` and `--messages`. Both extensions are available from 0.11.0. In 0.13.0, bridge `generatedAt` records extraction time and optional `sourceModifiedAt` separately records observed source mtime; neither proves compiler freshness.
