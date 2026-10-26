# Same-executable CLI repeatability qualification

This prospective diagnostic asks whether one ordinary CI-runner session is
repeatable enough for a later comparison. Both labels invoke the exact same
executable path, JARs, arguments, working directory, environment and corpus.
Labels only identify evidence files. No product optimization is included.

It does not run or replace the production smoke gate. Its existing 20-second
total and 5-second command budgets remain unchanged. In particular, a result
here cannot clear PR172's recorded 20.361-second failure, the incomplete 0.21
release, or reinterpret rejected local optimization pilots. A new comparison
requires its own review and contemporaneous controls.

## Source and runner

The first proposed target is unchanged PR172 commit
`13277fbbfd7d26e14a0c5bf7becf826ccb33f6c4`. The manual workflow requires a full
lowercase commit SHA and verifies its checkout. It separately checks out and
records the workflow/helper revision, so selecting older product source does not
replace the diagnostic code.

The workflow uses the existing `ubuntu-latest` runner and existing SHA-pinned
checkout, wrapper-validation, Temurin 17 and artifact actions. Its only repository
permission is `contents: read`. It has no release environment, secrets, tag or
publication steps and does not cancel another diagnostic run.

GitHub provides a fresh VM for standard hosted jobs; this does not guarantee low
timing variance. The actual image/version and resource observations are retained.
[Hosted runner documentation](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

Setup builds one installation and all six production roots with:

```sh
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  :cli:installDist :gradle-plugin:classes
```

The process-local Kotlin setting keeps compiler work in the build process rather
than leaving a separate Kotlin daemon for the measurement stage. It does not
change compiler language/backend options. Setup time is excluded and compiled
byte identity with earlier CI builds is not assumed.
[Official compiler execution strategy](https://kotlinlang.org/docs/compiler-execution-strategy.html).

Both labels use `target/cli/build/install/kartograph/bin/kartograph` directly.
Installation files, six class roots, configuration, keep rules, the unchanged
smoke checker, generated manifest/resources and selected JDK files are hashed
before and after. Actual root sizes and the first warmup's graph node count are
recorded, not substituted to match historical timing.

CLI processes receive one identical environment allowlist: JAVA_HOME, PATH, HOME
path only, LANG, LC_ALL, TZ and an owned TMPDIR. JVM/CLI injection variables and CI
tokens are omitted. No heap, GC, JIT or CPU override is added. This follows the
ordinary CI workflow's declared JVM policy and explicitly differs from the local
pilots' `-Xmx1g`. These observations must not be pooled with those pilots.

## Fixed schedule

Each complete suite executes graph/DOT, dead/strict, private-dead/strict,
cycles/strict, rules/strict and metrics, in the existing smoke order and with its
same command arguments/contracts. Every invocation starts a fresh JVM.

- Two excluded warmup suites: A, B (12 CLI launches).
- Six measured adjacent suite pairs: AB, BA, BA, AB, AB, BA (72 launches).
- Total: 84 CLI launches, with no adaptive samples or exclusions.

The primary suite total is the sum of its six individual command wall intervals,
matching the smoke endpoint. Enclosing suite wall is retained separately. wait4
also records user/system CPU and direct-process peak RSS. Every command's raw
stdout/stderr must match its first warmup, and the original smoke contracts must
pass. There is no path or timing normalization.

## Prospective repeatability screen

For each command and the six-command suite total, let A_i and B_i be the finite,
positive wall measurements for pair i, i = 1..6. Define:

```text
d_i = 2 * (B_i - A_i) / (A_i + B_i)
```

All conditions must hold for every command and suite total:

1. `max(abs(d_i)) <= 0.05`
2. `abs(median(d_i)) <= 0.025`
3. `max(all 12 times) / min(all 12 times) <= 1.10`

These are prospective engineering limits tied to the existing 5% comparison
sensitivity, not fitted to earlier arrays: half that scale for signed label
imbalance, twice it for full-schedule range. They are not confidence intervals or
a statistical equivalence claim. CPU/RSS observations remain descriptive.

Correctness, expected exits, exact identities and unchanged resource-policy
fields are prerequisites. Complete the fixed schedule before grading variability;
do not stop selectively after a noisy pair. A failed predicate rejects the
session at this sensitivity. Retain that result without changing thresholds or
retrying to obtain a pass. A pass describes only this session, not future hosted
runner stability or permission to rerun an optimization.

## Metadata and limits

Metadata-only JVM calls before and after use separately bounded
`-XX:+PrintFlagsFinal -version` and `-XshowSettings:system -version`: four metadata
JVMs total. These expose the separate probes' effective defaults, not the internal
state of each measured CLI. JDK version and release/launcher/libjvm hashes are
retained. No JFR, agent, jcmd, GC logging or running-CLI inspection accompanies
timing.

Small host snapshots occur outside each CLI interval: timestamps, inherited CPU
affinity, selected CPU/load/pressure and available-memory fields, and available
cgroup policy/counter files. Dynamic pressure is recorded without excluding
samples; unavailable fields are explicit. These snapshots include supervisor
gaps and cannot attribute CPU variation to GC/JIT or the host. No unrelated
processes, environment dump, HOME/config files or credentials are collected.
The current cgroup path is re-resolved at every snapshot so migration is visible.
Policy stability covers the observed leaf files and inherited CPU affinity;
unread ancestor policies or host-level limits are not certified unchanged.

The workflow is bounded to 45 minutes. The supervisor has a 900-second outer
TERM/15-second KILL envelope, 120 seconds per CLI and 10 seconds per metadata
call. Checked CLI stdout/stderr limits are 64 MiB/8 MiB, and metadata limits are
1 MiB per stream. Polling can overshoot a cap; overflow size is retained without
hashing the oversized body. Failure cleanup kills the owned process group,
boundedly reaps it and preserves the primary error alongside secondary failures.

Nonzero exits, timeout/cap, identity drift or detected parity/contract failures
stop the attempt and leave incomplete evidence. All original streams, source and
artifact maps, metadata, resource snapshots, rows, suite totals and the final
score are retained even on failure. Only the dedicated evidence directory is
uploaded, excluding transient TMPDIR contents. No release assets are touched.

## Validation before dispatch

The focused controls verify the schedule/formulas, inclusive limits, invalid
rows, command equivalence with the existing smoke script, environment filtering,
and small owned-child timeout/nonzero/overflow/cleanup behavior. Official
actionlint checks workflow contexts. Exact source/test review, ordinary CI and
all workflow-validation results must be accepted before a separately authorized
manual dispatch. A local test pass is not evidence of runner repeatability.

## Optional sealed Linux runtime diagnosis

`runtime_profile: true` selects a separate, reviewed diagnostic for PR #204. It
accepts only original CI checkout `c8d9ab3e3492afc5dc645dcfa50d7401c58796c2`
(synthetic merge of head `003d5522` and base `cb8efddd`). The normal default-false
qualification above is unchanged. Runtime mode runs six adjacent reference/profile
pairs, twelve CLI launches total; both arms have `/proc` observation and only the
second enables JFR. Neither is an uninstrumented acceptance observation. It does
not run or clear the 20 s / 5 s gate.
The original 22.627 s failure is retained. Diagnostic dispatch may investigate that
known failing gate; merge and publication still require the acceptance gates.

`LinuxRuntimeReference.json` seals all 1,542 class-root files, eleven runtime
libraries, both launchers, project inputs, and the selected JDK against artifact
12604786455 from run 40205795058. Content drift stops before profiling. The runner
class stays Ubuntu/Temurin 17, but a new host and the in-process setup build do not
reproduce original CI's ambient Kotlin-daemon state.

JFR and synchronous owned-child `/proc` observations are diagnostic only. CPU
user/system counters, scheduler runqueue waits, compilation durations, GC pause
unions, and stack samples overlap and are not an additive wall-time breakdown.
Native-state Java frames do not identify native C-stack CPU; that remains unknown.
VM bootstrap before recording and exited-thread tail CPU may be unobserved.
The synchronous file census and sampling add controller CPU on the same runner;
controller user/system deltas are reported separately and can affect scheduling.

The controller has an 840 s budget inside a 900 s TERM / 15 s KILL envelope;
children have 120 s wall/CPU, 3 GiB observed/final-peak RSS, 32 MiB ordinary stream
limits (64 MiB graph stdout, 256 MiB shared spool reservation), and bounded
JFR/private storage/events/stacks. Limits and data loss produce failure evidence.
Only the report and six sanitized event ledgers are uploaded through explicit
paths. Raw recordings, streams, names, paths, and decoded source events stay in a
separate private temporary directory and are not retained after the job. Ledger
hashes and numeric coverage limits are retained; no profiler or permission change
is installed. An inconclusive result cannot establish a speedup or permit release.

## Optional focused JIT attribution

`jit_attribution: true` is a separate diagnostic mode for the same sealed target.
It cannot be combined with runtime mode. One adjacent `dead --include-private-members`
reference/JFR pair preserves exact output and exit equivalence. Compilation uses
threshold 0 ms, as the earlier runtime mode already did; this mode additionally
retains compile IDs, OSR/tier/compiler, public method descriptors and numeric OS
thread IDs. These can be joined to bounded owned-child compiler CPU timelines.
Compilation duration is elapsed time, not per-method CPU or saved wall time.
JFR samples omit compiler threads; native compiler C++ stacks remain unavailable.

The 420 s controller uses the existing supervisor and a 450 s outer timeout.
Recordings are noncircular, with a 32 MiB hard file bound; complete chunk headers,
recorded settings and DataLoss are checked. Decoded JSON, event counts, stacks and
sanitized derivatives have explicit limits. VM bootstrap before recording and
exited-thread tails remain gaps. Only `result.json` and the sanitized event ledger
are uploaded; raw recordings, failure messages, paths and thread names stay private.
Both arms include `/proc` observation, so neither replaces the unchanged smoke gate.
