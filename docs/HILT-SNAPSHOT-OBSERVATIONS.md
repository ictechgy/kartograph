# Hilt artifact observations

Android snapshot capture can compare original compiler outputs with final public
`ScopedArtifact.CLASSES` PROJECT artifacts when the Hilt Android plugin is present.
It retains the original compiler witness and indexes only the selected final artifacts.
Raw javac/Kotlin directories are never substituted into the graph to make capture pass.

The bounded `hilt-superclass-v1` policy compares both class inventories and their actual
bytes. An annotated entry point may change its superclass to the exact generated Hilt
superclass, its generic superclass signature, and the super `invokespecial` references
rewritten by the Hilt visitor. The generated superclass must also be in the original
compiler inventory, extend the original superclass, and have a generated source filename
absent from the recorded primary source files. A handwritten `Hilt_*` primary source
is insufficient. Other method changes, missing/extra/duplicate classes, unavailable
inputs and unsupported transformations reject capture. Receiver `onReceive` injection
is not supported by this initial policy.

Stack-map frames and constant-pool layout are excluded from structural comparison;
raw before/after SHA-256 values still record all byte differences. Each side is bounded
to 100,000 classes, 200,000 entries and 256 MiB of class bytes; each class is bounded
to 8 MiB. These are input budgets, not heap or runtime guarantees. The observer task
is the snapshot task: it records a matching bounded byte relation, **not proof that
a particular Hilt transform task executed**, nor runtime completeness. The
`hilt-artifact-byte-relation` limitation remains in saved query, impact, reach and MCP.

## Compatibility and freshness

Only transformed captures use **nested provenance version 2**, with a separate
`transformations` array. This is independent of the enclosing plain snapshot version 1
or compact snapshot version 2. Original BuildWitness documents and compiler evidence
tokens remain unchanged. Captures without transformations retain provenance version 1.
Older readers reject provenance version 2. New readers reject version 1 with a
transformations key, including an empty array, so downgrading cannot silently ignore
the observation. Upgrade consumers together; recapture from supported original inputs
when an older reader is required.

`verify-snapshot` rechecks original compiler inputs, original outputs, selected outputs,
both class inventories, recorded deltas and scope. Source/configuration or artifact
byte changes remain stale. Aggregation rebinds both sets of paths and external slots,
retaining each observation's member scope. `matched` means content correspondence with
supported lifecycle records, not producer authentication.

Original compiler call positions, source-use receipts and authored call facts are not
mapped onto transformed roots. Existing selected-root checks remain intact. Successful
snapshot/query/impact/reach is not transformed `--source-edits` or exact source-call
support. Unsupported explicit compiler evidence still fails its own import validation.

## Validation boundary

The native regression uses public AGP 9.3.2 artifact/compiler providers with Hilt 2.60.1,
Java Application and Service entry points. It requires snapshot capture and separate
CLI query/impact/reach consumption, original/transformed/source/configuration mutation
rejection, variant mismatch and configuration-cache reuse. Check its exact candidate
CI result before claiming native execution passed. ASM unit fixtures exercise malformed
and mismatched inventories; they do not replace the native test. Kotlin/kapt/KSP,
device execution, other Hilt entry kinds and the private application in the feedback
have not been revalidated by this fixture.

The policy follows the [Hilt 2.60.1 visitor](https://github.com/google/dagger/blob/dagger-2.60.1/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/transform/AndroidEntryPointClassVisitor.kt).
