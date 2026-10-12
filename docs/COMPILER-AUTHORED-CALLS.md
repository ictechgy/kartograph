# Optional compiler-authored calls

`authoredCalls=true` enables `ordinary-body-v1`. It emits a separate
`compiler-references-authored.tsv` sidecar, version 1, alongside the unchanged
v1/v3 compiler references document. This is an additive library API. It does not
change raw `CALL`, dispatch, reachability, dead-code decisions, v3
`callPositions`, or the existing global reverse-query uncertainty policy.

The collector is an optional build-time dependency. The analyzer does not load
Kotlin compiler classes.

## Meaning and supported profile

A caller record binds the compiler-observed full relative source path and raw
SHA-256, original callable range, compiler coordinate basis, and JVM method
identity. `COMPLETE` is a positive assertion about that entire caller's authored
method-call set, including an explicitly empty set. No record means unknown.
`INCOMPLETE` includes typed reasons; its positive calls are not a complete set.
All facts remain assertions by the selected producer, not authentication against
a malicious build or tampered witness created by that producer.

The Kotlin authored profile is pinned to Kotlin **2.4.20**. It traverses every
FIR body node through an explicit allowlist and connects FIR declaration symbols
to one actual JVM backend-emitted method. Ordinary expressions, literals, local
reads/declarations, returns, supported blocks/control flow and string templates
are included. `${receiver.toString()}` records its semantic target; `$receiver`
does not invent a source call, even when both forms produce identical class
bytes. Overloads, nested owners and `@JvmName` use backend IDs.

Kotlin external target mappings, operators/desugared calls, properties,
delegation, lambdas/references, local/anonymous classes, inline/suspend/default
arguments, context parameters, expect/actual declarations, ambiguous backend
aliases and unclassified nodes remain incomplete. Missing/generated callable
rows remain unknown. There is no claim of complete Kotlin or corpus coverage.
Kotlin 2.4.10 is the historical v1/v3 baseline; the new feature rejects versions
other than 2.4.20. Do not assume backward compiler-plugin ABI compatibility.

The javac 17/21 profile supports ordinary methods with a bounded, exhaustive
`Tree.Kind` classification and public Trees/Elements/Types semantic identities.
Constructors, allocations, lambdas, method references, local/anonymous classes
and unclassified syntax are unsupported. Binary method identities are retained
only after the importer's selected classpath/native SDK headers confirm the
exact declaration outside selected project outputs. Package-name prefixes and
absence of a source file alone never establish that provenance.

## Capturing completed evidence

Use the existing `CompilerWitnesses.javaCompile` or
`KotlinCompilerWitnesses.kotlinCompile` registration with `compilerEvidence =
true`. Pass the existing pending token and evidence-directory providers to the
collector and add `authoredCalls=true` to its plugin options. `callPositions`
remains independent and can be enabled simultaneously.

Authored javac capture requires explicit `options.encoding = "UTF-8"`,
`-proc:none`, ordinary classpath inputs and the native selected SDK. A different
`--release` API, custom boot/system/module paths and annotation processing are
unsupported. Kotlin must use the selected native JVM toolchain; `-no-jdk`,
`-Xjdk-release`, multiplatform inputs and other compiler plugins are unsupported
for this profile.

Include the selected JDK's `jmods` directory and `release` file in the witness's
`additionalInputs`, along with the required compiler/collector artifacts. The
marker is emitted only after these files are included in the declared byte
inputs. The normal witness already records `lib/modules`, exact source bytes,
ordered dependencies, compiler options and artifacts. The completed receipt
binds both sidecars and all output bytes. Failed compilation invalidates the
previous receipt. Raw sidecars or a manually supplied token are not build proof.

## Importing and querying

Use `CompilerAuthoredCallIndexer.index(classRoots, classpath, targetJdkHome,
explicitDocuments, context)`. Supply the exact selected class-root order,
ordered classpath, native JDK and existing completed `CompilerEvidenceContext`.
The method constructs the index from those inputs and reuses the legacy
`CompilerEvidenceIndexer` for v1/v3 documents. It rechecks source bytes,
source-coordinate bounds, task scope, feature token, collector identity,
receipts, outputs, selected-root ownership, classpath order, and SDK inputs,
including a final freshness check. It rejects an unverified/mismatching input;
coverage from a previous import is never retained.

The graph's additive `compilerAuthoredCallers` side table and
`compilerAuthoredCallsFrom(caller)` API expose the result. For a fresh COMPLETE
caller, its `calls` are the full authored relation. Do not union them with raw
bytecode/bootstrap/lowering calls. This dedicated API does not confer complete
reverse coverage: every caller in the requested universe would need its own
proof, and generated/unsupported/missing callers still block that conclusion.

Plain and compact query snapshots preserve the same versioned side table and
`verifiedAtCapture` status. Snapshot replay must use the existing provenance
verification against current inputs before presenting the data as current.
Legacy snapshots have no source-call coverage. Filtering a project target drops
that fact and makes the retained caller incomplete; removing a caller removes
its coverage. Rebuilding a graph without copying this side table yields unknown
coverage, not a complete empty result.

All source-call records are non-edges. Bytecode-only consumers receive the same
raw graph and no extra reachability edges. The older v1/v2/v3 reader deliberately
rejects the separate format; use the authored importer for these opted-in
sidecars rather than silently dropping them from a normal snapshot command.

The compatibility control actually runs the packaged candidate with the exact
Kotlin 2.4.10 compiler/runtime artifact set: legacy v1 and v3 succeed, and the
authored option fails before output. This is a tested narrow compatibility
result, not support for authored completeness on 2.4.10.
