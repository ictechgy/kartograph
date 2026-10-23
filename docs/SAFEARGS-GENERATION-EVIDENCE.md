# Safe Args generation evidence

The snapshot adapter supports a bounded Java Safe Args 2.10.2 profile with
Gradle 9.7.1 and the observed Gradle 8.13 action artifact. The Android fixture
has two explicit profiles: Gradle 9.7.1 / AGP 9.3.2 and Gradle 8.13 / AGP 8.13.0,
both with JDK 17 and Java generation. The ordinary minimum-AGP gate does not
establish compatibility for this adapter. Each profile requires its own actual
Android execution; an SDK-free action observation alone is insufficient. Other generator artifacts,
Kotlin generation, additional or replaced generator actions, configuration-cache
hook restoration, and missing providers remain `generation-unverified`.

An initially empty output and mapping inventory, the supported original action
and this invocation's owned hooks, and a successful task completion establish a
cold generation receipt. The per-input mapping identifies candidate Java sources;
the whole output inventory also records unmapped files. Mapping or a directory
name alone does not establish ownership. Journal failures leave ordinary
generation and compilation running and make generation evidence unavailable.
An original generator failure still fails the build.

Snapshot attribution requires the mapped source bytes to match one actual javac
source witness, the class SourceFile and owner identity, and the selected class
bytes. Nested classes require classfile ownership evidence. Handwritten classes,
same-basename sources in another package, ambiguous inputs, missing debug data,
and changed transformed classes are not admitted. Generation classification adds
`generatedSourceEvidence` and `synthesized`; it does not add CALL edges or roots.
Because dead findings omit synthesized declarations, this changes classification,
not a prediction that the declaration executes or may safely be removed.

Generation snapshots use mandatory plain version 3 or compact version 4. Older
readers must reject them. Snapshots without generation evidence retain versions
1 and 2. The current adapter separates this invocation's successful cold work
from historical origin during an actual UP-TO-DATE task. Reuse requires the
original origin-context sidecar and matching current providers, actions, options,
mapping, whole output inventory, compiler source and selected class bytes. It
records `historical-byte-equivalence` and `UP_TO_DATE`; it does not call this a new
successful generation. FROM-CACHE, skipped/excluded tasks, failed attempts and
missing contexts remain unverified. No user clean or forced regeneration is performed.

When `snapshotsEnabled=true` and Java SafeArgs are present, ordinary Android
`kartographGraph<Variant>` and `kartographDead<Variant>` depend on the existing
variant snapshot. Its existing `snapshotIncludeUnitTests` selection controls the
additional preparation cost; snapshots disabled does not add these dependencies.
The live task retains its own selected roots, graph filters and retention policy.
It revalidates the complete snapshot proof before indexing and after attribution,
and rechecks it immediately before output. A main-only live root subset does not
silently discard the extra snapshot roots from freshness verification. Generated
declarations are joined again to the live class bytes, and diagnostics report the
live count, full proof scope, receipt and current cold/warm status. Missing or stale
proof leaves classification unverified rather than copying saved graph markers.

Saved CLI/MCP query and impact outputs preserve a bounded `generationSummary`.
It describes admitted evidence across the entire loaded snapshot, independently
for before/current revisions, and keeps status totals when detail rows are
omitted. `observationTime=snapshotCapture` and `liveRevalidated=false` distinguish
captured observations from a new build verification. Full records remain in the
original snapshot. No evidence means unavailable classification, not zero
generated declarations.

The native fixture compares the same generated class bytes with the pinned public
0.25.0 CLI and consumes the new snapshot with the current installed CLI. It checks
manual lookalikes, an unused generated action, unchanged calls and retention, and
both debug/release navigation inputs. Its JSON receipts distinguish execution
from unavailable baseline tools; the dedicated CI verifier requires both CLIs.
Local JVM source/class tests and SDK-free task lifecycle checks do not replace
this Android execution, and none of these fixtures remeasure a private app.

The dedicated native workflow runs the same fixture body once for each profile.
Its verifier checks the consumer's actual Gradle, AGP and Java runtime identity,
the exact generator/action JAR hashes, and both variants' source/compiler bytes.
The separate profile-observation task records SUCCESS or UP-TO-DATE explicitly;
an unchanged observation is not reported as a new generator execution.
The Gradle 8.13 fixture uses a separately checksum-verified official distribution;
the repository wrapper still builds the plugin and CLI. The Java-only profile
does not establish Kotlin 2.1, Hilt, or other Safe Args versions' compatibility.
Build receipts record elapsed preparation for each TestKit invocation separately
from CLI process timings. Preparation includes configuration, dependency work,
generation, compiler and analysis tasks actually run in that invocation; external
workflow downloads and tool setup remain separate costs.
