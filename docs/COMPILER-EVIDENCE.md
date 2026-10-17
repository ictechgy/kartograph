# Compiler-produced references

Compiler collectors add references that compiled instructions can erase or leave
indirect. The optional collectors cover javac constants, Kotlin 2.4.10 constant
uses, and Dagger 2.59 selected bindings. Kotlin joins resolved FIR references to
names and descriptors emitted by the JVM backend; it does not use the experiment's
fixture mapping. Compiler dependencies are confined to the separate
[`compiler-collectors`](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/compiler-collectors/README.md) build.

These references enter a `snapshot` explicitly. `query` and `impact` then consume
the saved graph. Default retention remains conservative, and a missing reference
does not authorize deletion.

## Build the collector

The optional collector JAR is built from a source checkout; it is not included in
the CLI archive or Gradle plugin. From the checkout root, with `JAVA_HOME` set to JDK 17:

```sh
./gradlew --no-daemon -p compiler-collectors integrationTest
```

This produces `compiler-collectors/build/libs/kartograph-compiler-collectors.jar`.
Dependency checksums are enforced. The standalone tests execute javac, the Kotlin
compiler and the Dagger processor, including unused controls and failed builds.

## Connect a real compiler task

Apply the kartograph Gradle plugin and use its actual compiler task/output
providers. This Java example uses Groovy DSL and a local collector JAR:

```groovy
def compiler = tasks.named('compileJava', JavaCompile)
def token = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project, compiler)
def directory = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project, compiler)
def output = directory.map { it.file('references.tsv') }
def collector = files('/path/to/kartograph-compiler-collectors.jar')
compiler.configure {
    options.annotationProcessorPath = collector
    options.compilerArgs.add('-Xplugin:KartographEvidence collector=javac-constants' +
        ' root=' + project.projectDir.toPath().toUri().toASCIIString() +
        ' output=' + output.get().asFile.toPath().toUri().toASCIIString() +
        ' token=' + token.get().asFile.toPath().toUri().toASCIIString())
}
def witness = dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(
    project, compiler, 'sample:main', files('src/main/java'),
    files('build.gradle', 'settings.gradle'), files(), true)
```

Preserve any existing annotation-processor path when adding the collector. For
Dagger, include the Dagger processor and dependencies on that path, select
`collector=dagger-bindings`, and pass the same `root`, `output`, and `token` as
`-Akartograph.evidence.root=...`, `-Akartograph.evidence.output=...`, and
`-Akartograph.evidence.token=...`. The paired javac plugin is required. The SPI and
compiler may use separate classloaders under Gradle; only the compiler completion
listener publishes the final evidence.

For a Kotlin 2.4.10 task, add the collector to the public `pluginClasspath` and
configure `-P plugin:kartograph.compiler-evidence:root=...`, `:output=...`, and
`:token=...` through `compilerOptions.freeCompilerArgs`. Register
`KotlinCompilerWitnesses.kotlinCompile` with the same source/build inputs and JDK
provider as [build provenance](BUILD-PROVENANCE.md), the selected compiler runtime
configuration in `additionalInputs`, and `compilerEvidence = true`. The default
2.4.10 runtime is `kotlinBuildToolsApiClasspath`.

Use file URIs for options so paths containing spaces remain one javac plugin
argument. Source roots must match the compiler inputs. Register each compilation
with a deliberate scope; this feature does not discover Android variants or main
and test roots. It does not attribute a transformed AGP JAR to an earlier compiler
directory automatically.

## Capture, verify and inspect impact

### Optional selected-call positions

The javac and Kotlin collectors accept the explicit boolean `callPositions=true` option. With
javac, append `callPositions=true` inside the existing `-Xplugin:KartographEvidence ...` argument.
With Kotlin, add `-P plugin:kartograph.compiler-evidence:callPositions=true`. Duplicate or invalid
values fail rather than selecting an arbitrary setting. Omitting the option or using `false` keeps
the existing reference protocol behavior.

The opt-in v3 evidence records compiler-selected method-call selectors, their actual JVM caller and
target, relative source identity, raw source SHA-256 and source coordinates. It supplements an
existing bytecode call pair; it does not invent call edges or replace declarations with source guesses.
Some synthetic, external, source-less and ambiguous compiler emissions cannot supply positions and
remain counted omissions. A captured empty result is distinct from positions not captured. Completed
receipts, source identity and witness freshness remain required before snapshot import.

Offsets are UTF-16 code units in the explicitly named compiler source sequence. Javac columns use
its `LineMap`, including tab expansion; Kotlin coordinates use its normalized source sequence.
The raw source hash still refers to file bytes. These columns are not interchangeable with bytecode
debug columns or HTTP exchange UTF-8 columns. Consumers must inspect the coordinate basis.

Snapshots preserve optional compiler call evidence in ordinary and compact forms. Query output and
graph exports retain it as separate evidence. The producer has a finite row budget and fails before
unbounded evidence growth. Exact selector fixture checks do not establish whole-program coverage or
accuracy/performance superiority over an IDE.

The source-checkout gate runs actual compilers and the pinned released reader without skipping the
required integration cases:

```sh
python3 Scripts/verify-compiler-call-positions.py
```

Use `--offline --old-archive <verified-archive.zip>` when the required SDKs/dependencies and pinned
compatibility archive are already available.

After compilation, use the returned witness and actual output providers:

```sh
kartograph snapshot --project . --classes "$CLASS_OUTPUT" --scope sample:main \
  --build-witness "$WITNESS" --compiler-evidence "$COLLECTOR_OUTPUT" \
  --source-root src/main/java --include-paths \
  --input external/slot=/local/input > snapshot.json
kartograph verify-snapshot --project . --graph-file snapshot.json \
  --input external/slot=/local/input
kartograph impact 'field:example/Constants#VALUE:I' --graph-file snapshot.json
```

Repeat `--compiler-evidence` and `--input` as needed. Bind **every** external slot
recorded by the witness, including collector/compiler/JDK and dependency inputs.
Slot names are portable identities; actual binding paths are not serialized.
The producer's evidence directory is managed output: prior collector files are
removed before a new compiler execution.

Witness version 2 records the input token and exact collector-file fingerprints.
Generated source outputs observed by processors receive separate fingerprints.
Older witnesses remain version 1 and readable; they do not authorize compiler
reference import. Raw TSV alone, a caller revision label, or manually generated
hashes cannot substitute for the supported producer lifecycle.

## Completeness and lifecycle

Enabled tasks perform full compiler analysis. Gradle up-to-date and build-cache
reuse remain supported; this is not a claim of incremental collector merging.
An incomplete source inventory, stale token, missing/changed sidecar, or failed
compilation cannot produce a completed receipt. Late task actions and failed
`--continue` builds invalidate the witness as described in build provenance.

Mapped references use `compilerReference` origin and `reference` edges. Dagger
bindings describe compiler selection, not a JVM call to an abstract `@Binds`
method. References outside the selected graph, shadowed source declarations and
unmapped references have separate measured counts in snapshot limitations.
Missing members of an included class are rejected. Class-root precedence is
reused from the original class parse, including declarations shadowed by an
earlier root.

The collectors cover tested compiler APIs and reference patterns. They do not
prove arbitrary runtime completeness, source transformations outside the supplied
inputs, or the honesty of a malicious producer. Custom Kotlin non-JAR plugin
inputs must be supplied explicitly under the provenance contract.

Javac scans each attributed top-level class separately, and inventory-only units
such as `package-info.java` need no class output. Constructors whose JVM parameters
include implicit outer/enum/capture arguments are currently reported as unmapped;
their descriptors are not guessed. Kotlin fields are joined through compiler IR
declaration identity captured before lowering. Unavailable external declarations
remain unmapped instead of being matched to a same-name project field.

One javac collector mode is selected per task. Raw collector completion callbacks
are not an independent compiler exit-status oracle: a completed Gradle receipt is
required before product import, including for late compiler/task failures.

## Reproduce the product integration

After building the CLI/plugin and the existing Dagger experiment dependencies:

```sh
./gradlew --no-daemon :cli:installDist :gradle-plugin:jar
./gradlew --no-daemon -p experiments/dagger-bindings dependenciesForFixture
./gradlew --no-daemon -p compiler-collectors integrationTest
python3 Scripts/verify-compiler-evidence.py
```

The validator uses temporary Java, Kotlin and Dagger projects, actual compiler
outputs and the product CLI. It checks impact traversal, unused controls,
configuration-cache reuse, wrong scopes, changed collector bytes, preserved-mtime
source changes, replayed old sidecars and missing bindings. The CI runs the same
validator and retains its report and fixture logs. These fixture results are not
the later broad evaluation of real application changes or AI repair success.

## Direct source-selection capture and inspection

`snapshot --include-source-uses` explicitly retains compiler-selected invocation dependencies from admitted v3 evidence.
It requires explicit `--compiler-evidence`, `--scope` and completed `--build-witness` inputs; the producer must have enabled
`callPositions=true`. A validated zero-emission v3 unit is a captured empty observation. Missing v3 evidence, stale source,
output, collector, token, option or receipt inputs fail capture rather than falling back. No sidecar is discovered implicitly.
Without this flag the existing snapshot path and option fingerprint are unchanged. There is no Gradle or MCP activation.

Inspect a saved snapshot using `query '<exact-usr>' --graph-file captured.json --source-uses`, or inspect direct source
selection dependencies on resolved changed declarations using `impact '<exact-usr>' --graph-file captured.json --source-edits`.
These return separately typed `kartograph-source-use-query` and `kartograph-source-edit-impact` version 1 reports. The nested
ordinary `query` / `graphImpact` retains the existing graph result. Source observations do not become graph edges. A method
selected by the compiler may have no matching bytecode CALL; that correlation does not explain why a CALL is absent.

Each returned observation preserves the immutable source path, raw source SHA-256, UTF-16 range, compiler/collector and unit/root
IDs. Caller/target declaration metadata belongs to the selected graph view and is distinct from that source location. Returned
unit bindings identify the receipt InputFingerprint, collector artifact fingerprint, input token and claim count; the original
ordered claims, outputs and root ownership remain in the bound snapshot. A receipt fingerprint is not a raw TSV hash.

Coverage is global to the captured revision/view: `unavailable` means no channel was captured; `captured` may be empty.
`partial` preserves known producer/import omissions, view omissions and historical uncovered callable roots; otherwise coverage
is `unknown`, never complete. Counts refer to retained observations and original capture units, not all real-world source uses.
Saved reports hash the exact parsed snapshot bytes and say `currentness: notChecked`. They do not re-read source files or
recapture evidence. Use the existing `verify-snapshot` workflow for a separate currentness check at a specific time.

## 0.23.0 upgrade and API compatibility

[v0.23.0](https://github.com/ictechgy/kartograph/releases/tag/v0.23.0) was published on 2026-10-15
as a GitHub prerelease with seven assets. Its [release workflow](https://github.com/ictechgy/kartograph/actions/runs/38721915580)
and Gradle plugin publishing step succeeded. As of 2026-10-17, independent consumption of those public assets
and the Portal plugin has not been verified here; v0.22.0 remains the latest stable GitHub release.
The original v0.23.0 tag, assets, packaged documents and checksums remain unchanged.

- New core/index APIs are `CodeGraph.compilerSourceUseEvidence`, `withCompilerSourceUses`,
  `preserveCompilerSourceUsesFrom`, and `CompilerEvidenceIndexer.enrichWithSourceUses`, with eight separate
  `CompilerSourceUse*` evidence/value types. Analysis adds `SourceUseQuery.inspect`, `SourceEditImpact.analyze`,
  `SourceUseCoverage.from` and page/observation types; export adds typed source-use documents and
  `SourceUseDocumentRenderer.query` / `impact`. Existing `SymbolQuery` and `ChangeImpact` remain separate.
- New-API consumers need matching new library modules. Keep CLI, plugin, collector and direct-library artifacts
  on the same version/source revision; do not mix published 0.22.0 JARs with new modules. Retained execution of one
  compiled 0.22 client and preservation of 132 public declaration/descriptor pairs in 11 released classes, plus
  252 pairs in 19 Stage A classes, are bounded compatibility evidence. They are not whole-library ABI, all-client
  or Kotlin source-compatibility proof. New Stage B types lie outside that 19-class inventory. A real new-API
  client negative/positive test against old/new artifacts remains unexecuted.
- Ordinary and compact snapshot versions stay 1 and 2. Optional `graph.compilerSourceUses` has its own version 1,
  independent of compact string-table encoding. Missing evidence is unavailable, not captured-empty;
  malformed or unsupported present extensions fail validation. The actual published 0.22 reader can read the
  compatible graph but drops source-use evidence on reserialization in both encodings. This is not lossless downgrade.
  Unsupported snapshot merge/aggregation paths reject source-use evidence rather than silently combining it.
- Recapture with the explicit source-use flag and fresh admitted compiler receipts when evidence was never captured
  or was lost through an old reader. Where base/current comparison requires equal analyzer/tool versions, recapture both
  revisions with the same 0.23.0 tool version and capture configuration. Do not edit a snapshot's version field to
  bypass compatibility. A saved historical report does not recapture or validate live sources: it always reports
  `currentness: notChecked`; use separate snapshot verification and respect any stale or unavailable result.
- Capture status (`unavailable` or `captured`, including empty) and coverage (`partial` or `unknown`) are distinct.
  An empty selection or exhausted page does not prove no uses. Known producer/import/view omissions and historical
  uncovered roots stay visible. Direct source-edit results include only observations targeting an exact changed
  declaration whose caller is not changed; they do not traverse source selections, graph edges, overrides or owners.
  Ordinary CALL edges, weights, retention and reachability are unchanged by opting into this separate evidence channel.

The explicit CLI prerequisites and report contract are described above and in [impact](IMPACT.md#opt-in-direct-observed-source-selections).
There is no automatic Gradle-plugin or MCP activation. These observations do not establish overall PSI/IDE accuracy,
performance superiority, runtime behavior, deletion safety or permission to skip tests.

## 0.24.0 upgrade and enum migration

0.24.0 is release preparation. A versioned source build, ordinary CI success, packaged readiness,
publication and independent public installation are separate gates. The enum feature's
[source-built Java/Kotlin integration passed](https://github.com/ictechgy/kartograph/actions/runs/38928690616)
with `KARTOGRAPH_SNAPSHOT_CLI` enabled, including combined enum/source-use capture and saved queries.
That result predates this version bump and does not establish 0.24.0 packaged readiness or publication.
Published v0.23.0 artifacts do not contain the enum-expansion feature.

- Build matching CLI, Gradle plugin, collector and direct-library modules from the same 0.24.0 revision.
  Do not combine the published v0.23.0 collector with the new CLI or identify an artifact by its version
  string alone: the enum feature initially landed on main while main still reported 0.23.0.
- Rebuild and capture **both baseline and current revisions** with the same compatible tool version,
  scope and capture settings. The baseline needs a complete enum inventory; it does not need decision
  evidence. The current build needs `enumDecisions=true`, a fresh completed compiler witness and
  `snapshot --include-enum-decisions` for source-located candidates. Do not relabel an old snapshot's
  version or interpret missing inventory/evidence as a complete empty set.
- An enum change in a library requires recompiling its clients to capture their current decisions.
  This first slice attributes decisions only within a unique claimed compiler output root;
  dependency-only and shadowed-root attribution remains unresolved even after an otherwise valid build.
- Direct JVM library consumers must recompile. `GraphNode.enumConstants` and
  `QuerySnapshot.enumDecisionEvidence` change public Kotlin data-class constructor/`copy` shapes.
  Defaulted parameters do not preserve all previously compiled JVM call sites.
  Old readers can reject the new `ENUM_CONSTANT` attribute or lose optional enum evidence.
  There is no lossless downgrade promise; snapshot merge/aggregation with captured enum decisions
  is currently rejected.
- Enum inventory `null` means unavailable; `[]` is a captured complete empty enum. The captured
  full inventory is retained when a view prunes constant field nodes. Conflicted captures cannot
  become a complete inventory by unioning their cases.
- To retain both channels, enable collector `enumDecisions=true` and `callPositions=true`, then pass
  both `--include-enum-decisions` and `--include-source-uses` to snapshot capture. The channels have
  separate evidence/coverage and do not add ordinary CALL or retention edges.
- Supported candidates are authored default/else and the finite standard-factory membership forms
  below. General equality, arbitrary filters and dynamic/custom collection semantics remain outside
  this slice. Kotlin `!in` is supported; authored `!contains`/`.not()` is counted unsupported without
  emitting an inner positive fact. The actual collector fixture uses JDK 17; its conditional Java 21
  null/pattern-label branch has not been executed. JDK 21 product CI does not establish that coverage.

A candidate is a review location, not proof of an executed path or defect. Saved analysis uses the
captured source hashes and reports `currentness: notChecked`; use a separate verification/build when
freshness is required. See the [query contract](IMPACT.md#opt-in-enum-expansion-candidates).

## Opt-in enum expansion decisions

The 0.24.0 preparation adds a separate enum decision channel. Build matching
CLI, index/export, Gradle plugin and collector artifacts from the same revision.
It does not add dependency or execution edges.

Each unambiguous indexed enum captures its complete JVM `ACC_ENUM` constant inventory.
An unavailable inventory is distinct from a complete empty enum. Static aliases
with the enum's type and synthetic values/entries storage are not constants.
The captured inventory survives view pruning of the individual field nodes;
conflicting or shadowed captures cannot be merged into a supposedly complete set.
Older snapshots without this fact remain unavailable, and the class cache is
invalidated by the new payload version. Recompile direct library clients for the
new `GraphNode`/`QuerySnapshot` shape. Old readers need not accept the new
`ENUM_CONSTANT` attribute and must not be used for a lossless downgrade.

Add `enumDecisions=true` to the existing real compiler configuration:

- Java: add `enumDecisions=true` inside the single
  `-Xplugin:KartographEvidence collector=javac-constants ...` argument.
- Kotlin 2.4.10: add
  `-P plugin:kartograph.compiler-evidence:enumDecisions=true`.
- The option is independent of `callPositions=true`; enable both if both channels
  are needed. Omitted/false enum capture preserves the v1–v3 producer formats.
- The Gradle compiler-witness adapter derives a separate token-bound
  `compiler-enum-decisions` marker from these actual arguments. Keep
  `compilerEvidence = true` and use the completed witness from the successful task.

A successful enabled collector writes a v4 document with `enumStats` and typed
`enumDecision` rows. Counts partition observed decisions into emitted,
unsupported and ignored; zero rows alone do not mean zero decisions were examined.
The decoder validates exact source hashes, UTF-16 ranges, kinds, enum identities,
same-enum literal constant identities and duplicate source ranges. Snapshot
import additionally verifies the completed compiler receipt/token, recorded
collector/compiler inputs, current source hashes and output-root attribution.

Capture the current snapshot using the existing project/class/input arguments
plus `--include-enum-decisions`, explicit `--compiler-evidence`, `--scope` and
completed `--build-witness` inputs. The channel is optional snapshot-level
`enumDecisions` metadata; it is separate from ordinary call-position or
source-use evidence. Inventory comparison needs base/current snapshots captured
by compatible versions for the same scope. The baseline does not need source
decision rows: actionable decision locations always come from the current snapshot.

Snapshot aggregation/merge of captured enum decisions is unsupported and rejects
rather than dropping or rebasing the channel. Plain inventory-only captures can
still follow the existing aggregation rules.

The first supported source patterns are:

- Java enum switch statements/expressions with an authored `default`, and Kotlin
  subjectful enum `when` with finite enum-entry branches and an authored `else`. Compiler-generated exhaustive throws are
  not authored fallback evidence.
- Java `contains` on direct `Set.of`, `List.of`, `Arrays.asList` or `EnumSet.of`
  calls whose arguments are constants of one enum. Direct surrounding `!` and
  parentheses retain their polarity.
- Kotlin direct standard `setOf`/`listOf` finite literal membership, including
  `in` and `!in`. Explicit `!contains`/`.not()` chains are currently unsupported;
  their inner positive call is not emitted as if it were the whole expression.
- Supported sites inside lambdas retain `lexicalLambda` as descriptive source
  context, without assuming that the enclosing method executes the lambda.

Resolved attribution requires a unique claimed selected output root shared by
the source and enum. A bytecode SourceFile basename is only a consistency check,
not independent source identity. Ambiguous same-basename/multi-output sources,
shadowed classes, dependency enums outside the claimed output, unavailable
inventories and constants outside that inventory retain unresolved reasons and
locations. Arbitrary equality, custom or dynamic collections, aliases, spread
arguments, general filter behavior, and cross-output/dependency attribution are
outside this first slice. Unsupported syntax has unit-level counts rather than
located emitted rows.

The dedicated collector integration runner requires JDK 17. Newer Java switch
syntax must not be described as executed coverage merely because a conditional
fixture exists; supported syntax and observed test coverage are separate.

See [enum expansion impact](IMPACT.md#opt-in-enum-expansion-candidates) for the
saved-snapshot query and its coverage contract. A saved query does not recheck
live inputs, establish runtime behavior, or authorize skipping tests.
