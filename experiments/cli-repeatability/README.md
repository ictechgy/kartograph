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
# Optional fixed-target JDK contrast

`runtime_pair=true` is a separately authorized diagnostic for engine
`741903922f011aab92888c395226f8e4d4f4b6a9`, not a production performance gate.
It runs once on a standard public `ubuntu-24.04` VM. Historical CPU/image
identity is not selectable. The build uses the preinstalled Temurin17.0.21+9;
if that exact build JDK is missing, the job stops instead of installing a substitute.
The six complete production class roots and installation must match the prior
public input digest, and all files, modes and internal symlink bindings are
sealed again before/after measurement. Kotlin module files are included in this
additional seal. The engine revision and diagnostic harness revision are distinct.

Official Adoptium HotSpot Linux x64 JDK17.0.20.1+1 (A) and17.0.21+9 (B)
are downloaded without credentials to `RUNNER_TEMP/kartograph-runtime-pair/`.
The fixed official archive SHA256/length, archive paths/link graph, release and
actual executed version/vendor are checked. No Mac installation, package manager,
global Java selection, external AI, new credentials or remote cache writes are used.
Archives total386,847,308 bytes; expanded official size is unknown. Each runtime
has a768MiB regular-file cap and archive+installation has a2GiB cap. After the
guarded build at least9GiB must remain; preparation/measurement stop at5.5GiB.

Two warmup suites (A/B) are excluded, followed by the six declared permutations
of A/B1/B2: eighteen measured suites,120 CLI calls total. B1/B2 use the identical
new-JDK path, executable and child environment. Eight separate metadata JVM calls
run outside CLI intervals. Their flags are metadata, not live per-CLI flag proof.
No rows are removed or adaptive retries run. The existing5% paired difference,
2.5% median imbalance and1.10 range screens apply to B1/B2 for each command and
suite sum. A also has a1.10 max/min range screen. A failed noise screen prevents a runtime-effect claim. Contrasts remain
descriptive within this VM and cannot explain historical CPU/image changes.

The same full raw stdout/stderr and existing command contracts must match across
all arms. Runtime/source/install/input/resource-policy drift fails the diagnostic.
Process-tree RSS samples are bounded at12GiB with a50ms observer interval; direct
child wait4 peak RSS is separate. Sampling can miss short descendant peaks. The
observer adds controller work symmetrically; this is not uninstrumented CI timing.
All task children are sampled; a final process-group census occurs while the root
is held with WNOWAIT. Observed descendants have stable pidfd handles for cleanup.
Detached descendants that appear and reparent entirely between samples can still
escape attribution; this is a bounded observer of reviewed programs, not a sandbox
for adversarial process creation. The original shared A/A helper remains byte-exact.
The build is measured separately and shares no live Kotlin daemon with the probe.
The original20-second total and5-second command acceptance requirements are unchanged.

The job is45minutes, build at most1800seconds, measurement at most900seconds and
each CLI at most120seconds. Raw evidence is bounded at1GiB; complete retained
streams are losslessly ZIP-compressed and every file is read back/hash-verified.
The inner ZIP is at most127MiB, leaving room under128MiB for GitHub's artifact
wrapper. It is retained7days (new artifact budget at mostUS$0.01) and Mac evidence
acquisition is capped at160MiB with a5.5GiB stop reserve. Incomplete/noisy outcomes
are retained as such. If disk/size limits prevent a complete verified ZIP, only the
three size-bounded JSON receipts (at most12MiB each), fixed small identity files
and an explicit `complete=false` manifest are uploaded under an INCOMPLETE name.
That artifact never proves raw parity or a runtime effect. The package failure
still fails the job. This emergency upload reads existing files without new disk
allocation and is mutually exclusive with the complete artifact, under the same
128MiB/7day budget. Runtime archives and private temporary files are excluded.
Existing automatic PR CI artifact costs are separate and unverified. A result
does not authorize a CI JDK downgrade, budget waiver, merge or another probe.

