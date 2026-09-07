# kartograph

Queryable dependency graphs for Kotlin/Android codebases. A sister project of cartograph (Swift) sharing its design and exchange contracts.

> [한국어](README.ko.md)

The name is **K**otlin + cartograph. Where cartograph maps iOS, kartograph maps Android.

## What it does

Kotlin has no Periphery equivalent. What exists is one Gradle plugin leaning on R8 and one syntax-only CLI, and neither says *why* something is unused or *why* it survived. kartograph fills that gap the way cartograph already proved:

- Facts recorded by the compiler are the source of truth — not text search.
- Unused code, dependency cycles, layer rules, and architecture metrics come from one graph.
- Every verdict carries evidence. It never approves a deletion.
- `query` and `skill` are built in from day one, with agent consumers in mind.

Android has one unfair advantage: "looks unused but must not be deleted" has been codified for over a decade as **ProGuard/R8 keep rules**. The retention knowledge the Swift side had to collect by hand already lives in the ecosystem here.

## Status

The current source version is declared in [VERSION](VERSION). Released versions and artifacts are on [GitHub Releases](https://github.com/ictechgy/kartograph/releases). The source experiments settled on JVM bytecode plus official Kotlin metadata as the primary graph; the rationale is in [`docs/DECISION-truth-source.md`](docs/DECISION-truth-source.md).

Working today:

- `graph` renders compiled class roots as DOT or as a `code-graph` JSON exchange document, with optional project-relative source paths (`--include-paths --project`). Repeated `--classes` merge several module/variant outputs; the first root wins deterministically for a repeated JVM class.
- `dead` reports unreachable class declarations from Android retention roots (manifest, XML, `@Keep`, keep rules, inheritance hierarchies, DI/serialization annotations, JNI and framework callbacks), with `--explain`, baselines, `--since`, and machine-readable reports. Recursive includes and consumer rules are supported.
- `query`/`bridges`/`skill` expose one symbol's users, dependencies, and reachability, plus Flutter/React Native bridge facts, for agent consumers.
- `cycles`/`rules`/`metrics` analyze module/package cycles with weakest edges, fail-closed layer YAML, and Martin Ca/Ce/I/A/D metrics.
- The Gradle plugin registers `kartographDead<Variant>` and `kartographGraph<Variant>` per Android variant over the AGP public Variant API.
- Keep-rule parsing fails closed with file and line instead of silently dropping unsupported syntax. Errors and evidence never print absolute paths.

See [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) for what the graph cannot see, and [`docs/PHASE2-VALIDATION.md`](docs/PHASE2-VALIDATION.md) for measured retention behavior.

## Installation and compatibility

Download the CLI archive from GitHub Releases. The Gradle plugin `io.github.ictechgy.kartograph` becomes installable once its version appears on the [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph); a GitHub Release and Portal approval are separate events.

- Building kartograph from source is verified with JDK 17 or 21 and Gradle 9.6.1.
- Applying the Gradle plugin: AGP 8.7+, Gradle 8.10+, JDK 17+ (AGP 8.7 + Gradle 8.10 verified; AGP 9.x covered by the Android fixture gate).

```kotlin
plugins {
    id("io.github.ictechgy.kartograph") version "0.4.0"
}
```

Download `kartograph-<version>.zip` or `.tar` from a GitHub release, unpack it, and run `bin/kartograph`. No separate checksums or signatures are published yet.

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
    reportFormat.set("github-actions") // gradle, github-actions, sarif, json, text
    includeSourcePaths.set(true) // resolve project-relative source paths into the graph document (default false)
}
```

```bash
./gradlew kartographDeadDebug
./gradlew kartographGraphDebug
```

AGP does not expose dependency consumer rules as a merged file through the public Variant API, so pass those files explicitly. The dead task never reuses up-to-date/cache results, because keep-rule includes are discovered while it runs; the graph task skips reuse only when source-path resolution reads undeclared project sources.

### Private members

Private-member diagnostics are opt-in via `dead --include-private-members` (added in 0.2.0, not in 0.1.x). On top of the default class report it adds private methods and fields/properties of reachable, non-synthesized classes; use the same option for baselines and `query`. In Gradle: `kartograph { includePrivateMembers.set(true) }`.

This mode conservatively retains `-keepclassmembers` targets with their owners, so it can report fewer class findings than class-only mode. Constructors, natives, synthesized members, compile-time constants, file facades, and members under unreachable owners are not reported, and field writes count as uses — it is not an unread-field check. Unexplainable `-keepclassmembers` signatures widen to all direct members of matching classes, while ordinary `-keep` parsing still fails closed. Members reachable from assumed-external entry points keep their private helpers too (explained as `EXTERNAL_MEMBER_ENTRY`, which can under-report). Review keep/consumer rules and runtime tests alongside, since private reflection/serialization conventions are not fully proven.

## Development and verification

To block **all newly introduced diagnostics** in a PR, follow the [PR gate guide](docs/PR-CHECK.md). The released `Scripts/check-pr.py` reads the base commit's baseline and also checks untouched files. `--since` is a changed-files filter, so it differs from the PR gate, which must catch the blast radius of a caller deletion. Measured public samples (Hilt/Compose/KSP) and remaining limits are in the [public validation record](docs/PUBLIC-VALIDATION.md).

Developing kartograph itself needs JDK 17+.

```bash
./gradlew test
./gradlew :koverVerify
./gradlew :cli:installDist
Scripts/verify-cli-contract.sh
Scripts/verify-fixture-corpus.sh
Scripts/verify-gradle-plugin-fixture.sh
Scripts/verify-agent-surface.sh
python3 -m unittest discover -s Scripts/tests -v
Scripts/verify-release-readiness.sh # two clean builds, never publishes
```

## Interpreting results safely

A finding, and `unreachable`, is a fact about the given input graph. **It never says any code is safe to delete.** Reflection, JNI, dynamic registration, missing variants/classpaths, and stale build outputs can change the result. Before changing code, review `--explain`, the runtime paths, and that variant's tests. Full boundaries are in [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md). How local inputs are handled, and what to review before publishing reports, is in [`SECURITY.md`](SECURITY.md).

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

kartograph is MIT licensed. Copyright and license texts of dependencies bundled in distributions ship together in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and [`LICENSES/`](LICENSES/).
