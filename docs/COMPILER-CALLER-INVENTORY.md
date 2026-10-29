# Optional source/backend caller inventory

The compiler-authored ordinary-body supplement proves individual callers. `callerInventory=true` adds a separate `kartograph-source-caller-inventory` v1 sidecar to account for the requested source universe before querying `authoredCall` v1. Existing `sourceCall`, v3 call positions, raw CALL, dispatch and reachability do not change.

## Producer contract

Enable both `authoredCalls=true` and `callerInventory=true` on the existing javac/Kotlin collector. Pass `classOutput=<absolute path or file URI>` for one task-owned output directory. It must already exist, be inside the collector's project root, and contain no preexisting files. The collector does not delete or clean application outputs. Each actual compilation must start with fresh outputs; charge that preparation and compilation during edit-refresh.

The profile supports the same native SDK/UTF-8/no-processor ordinary JVM layout as C1: Kotlin 2.4.20, or javac 17/21. It does not support partial or incremental compilation. Existing full-compilation witness registration remains required. UP-TO-DATE and FROM-CACHE reuse is accepted only through unchanged successful task receipts, sources, options, SDK/dependencies, output files and sidecars.

Use distinct output roots for Kotlin and javac tasks. A module can own their ordered union. Javac may resolve against the already sealed Kotlin root, but must not write into it. A subsequent task changing an earlier task's output invalidates that earlier receipt.

Javac inventories every actually parsed source, including implicit source lookup. Completion requires that set to equal the explicitly selected declaration source files, so unnamed implicit compilation fails closed. Materialized generated source files may be explicitly selected and byte-bound; annotation processors are not enabled by this profile. Kotlin inventories Kotlin declarations through complete FIR file traversal. Same-module Java files used for Kotlin resolution must remain separately byte-bound compiler inputs and get their declarations from the javac inventory.

No-class source files have positive source entries with zero body counts when the task emits at least one class. Entirely output-free tasks remain unsupported. Zero-method class outputs have positive output entries. Every class output and every emitted method, including methods without bytecode CALL instructions, is accounted for. Bounded actual class headers are reconciled independently by the importer using ASM. The full output file set, including Kotlin metadata, is hashed.

## Facts and conservative categories

Source records retain path/raw hash, coordinate basis, observed range or explicit unavailable range, declaration kind, body presence, compiler-origin discriminator and associated actual backend IDs. Constructors, property/field initialization, static/instance/init blocks, parameter/annotation defaults, accessors, local functions and anonymous bodies remain visible even when unsupported. No lowered descriptor is guessed from a source name or span.

Backend methods are `SOURCE_BOUND` only when exactly one observed declaration is associated with the actual emitted ID; otherwise they are `UNKNOWN`. `SOURCE_BOUND` does not mean complete authored-call coverage. The initial generated-exclusion allowlist is empty. Bridge/synthetic flags, generated-looking names, missing source rows and missing line information never justify exclusion.

An explicitly observed abstract declaration can be complete-zero only with supported positive source origin, an available source range, no source body, an actual abstract backend declaration and no Code attribute. Native/external methods, constructors and initializers are unsupported. C1's own unsupported call/body outcomes are preserved.

## Import and explicit query scope

Register the existing successful compiler witness and include all source, compiler/plugin, native SDK, ordered dependency, option and build-configuration inputs. The new marker is `CompilerCallerInventoryOptions.enabledInput()`. Raw TSV files or a token alone are not completed-build proof. Parent applications constructing receipts directly are responsible for the same verified producer contract.

Import with `CompilerCallerInventoryIndexer.index(classRoots, orderedClasspath, targetJdkHome, documents, context)`. Supply the paired C1 authored document, new inventory and any legacy evidence documents. Import verifies the exact source and output sets, selected-root ownership, all method headers, one unambiguous successful receipt and matching compiler/artifact/token. Missing methods/files, source-deleted leftovers, partial recompilation and root shadowing are rejected. The result exposes `graph.compilerCallerInventories` alongside `graph.compilerAuthoredCallers`.

Call `AuthoredCallUniverse.examine(graph, AuthoredCallerScope(filesByModule, bodyKinds, wholeProject))` from the analysis API. `filesByModule` is the caller's explicit requested module-scope-to-source-file set. It must come from the selected project/module model, never from whichever C1 rows happen to exist. A scoped ordinary-method request is visibly different from a whole-project request. Module scope/artifact identities come from completed witnesses; model/source-state files must be declared hashed inputs when an external module model is used.

At `examine` entry, the request is revalidated and captured as a deeply immutable scope, including nested file sets. A later caller mutation cannot change the scope reported with an existing result. Capture is bounded to 256 modules, 200,000 selected files, 16,384 characters per portable source path and the declared body-kind enum. Concurrent mutation is not a supported request construction pattern.

The result reports the exact requested scope, included source-body count, accountable caller IDs, authored calls and `COMPLETE`, `INCOMPLETE` or `UNKNOWN`. It enumerates the source inventory before checking caller coverage. Missing complete-zero coverage stays unknown. Positive calls retained in an incomplete result are not a complete set. A complete result can support reverse negatives only within that exact requested scope.

Whole-project mode requires all body kinds, exact requested source coverage across every retained inventory scope (including projected-away modules), every selected class/method accounted for without unknown backend origins, and complete coverage for every source body. Unsupported constructors/initializers and unproved generated methods prevent that claim. The feature does not promise whole-project completeness for general Java/Kotlin projects.

## Snapshots and trust boundary

Plain and compact snapshots use the same additive versioned side table. Legacy documents omit it and keep their previous bytes. Projection preserves accountable identities and marks the inventory incomplete if required output classes/methods are removed or their method kind, external origin or represented JVM access modifiers contradict the inventory. C1 target filtering also keeps its existing incomplete behavior.

`CodeGraph.withCompilerSourceFactsFrom(original)` is the shared operation used by identity-preserving source-location/module enrichment and node projections. It requires the original JVM identities and unchanged projected graph relations. Missing or incompatible nodes and observed source-path/hash contradictions retain the full inventory with `projectionComplete=false`; incompatible C1 callers lose coverage, and filtered project targets keep C1 incomplete. It does not copy completion across renaming or architecture aggregation, replace another capture, rewrite evidence identities, or restore a previously incomplete projection.

Snapshot assertions are verified-at-capture facts, not current build validation. Replay currentness must use the existing provenance verifier. `examine` evaluates the graph supplied to it and does not access files. Receipts and inventories are trusted-producer assertions, not authentication against a malicious compiler or fabricated producer.

## Packaged analysis-library entrypoint

`AuthoredCallUniverse.examine(CodeGraph, AuthoredCallerScope): AuthoredCallUniverseResult` is a supported external analysis-library entrypoint. The self-analysis contract explicitly roots this class and exact erased method signature in `.kartograph-self.pro`. It does not automatically retain other public APIs, overloads or same-owner helpers. Helpers reached by that entrypoint retain their ordinary graph reachability semantics.

The permanent `compiler-collectors:publicAuthoredApiTest` verifier compiles a separate Kotlin module against only the installed analysis, core and pinned Kotlin stdlib JARs, without source directories or friend/internal-test paths. It exercises complete-zero, incomplete, missing-coverage and invalid/mutated-scope cases, and separately verifies that an internal helper cannot compile. Selected installed JARs, compiler runtime, source bytes and native JDK are hash-bound before and after the run. These API fixture assertions prove the public consumption contract, not producer authenticity or current source freshness.

Build `:cli:installDist` first, then pass the explicit installation to the standalone build:

```sh
./gradlew :cli:installDist
./gradlew -p compiler-collectors \
  -Pkartograph.consumerDistribution="$PWD/cli/build/install/kartograph" publicAuthoredApiTest
```

`integrationTest` includes this verifier; official CI supplies its built installation. A missing, mixed or incompatible installation fails clearly and invalidates any previous success receipt. Evidence is written to `compiler-collectors/build/reports/public-authored-api/`.
Planned commands, timeout/launch/nonzero outcomes and partial streams remain in local evidence. Official CI uploads only `public-report.json`, using stable input tokens, normalized executable/flag/classpath order, and raw-command/attempt hashes without machine-local paths. Unknown command paths are rejected rather than omitted.
