# Descriptive architecture assessment

This manual, nonpublishing diagnostic compares the exact reviewed architecture dispatch-exclusion patch against provenance-enabled PR172 commit `13277fbbfd7d26e14a0c5bf7becf826ccb33f6c4`. It is a prospective descriptive engineering screen. It does not estimate population confidence, prove independent observations, qualify a runner, or replace the ordinary smoke/release gates.

The previous architecture and inherited-guard pilots remain rejected under their original screens. The separate same-executable [A/A qualification run 38554070997](https://github.com/ictechgy/kartograph/actions/runs/38554070997) remains **not-qualified**: all 84 CLI launches preserved outputs, but all six individual-command repeatability screens failed. Its suite-total screen passing does not rescue that result. This protocol is not a passing regrade of any prior attempt.

## Exact inputs and setup

The workflow has no arbitrary target input. It checks out the helper at the dispatched workflow commit and two product trees at the fixed PR172 commit. `candidate-source.json` pins the product tree, exact patch SHA256, three candidate bodies and mechanical old-command oracle. The patch is applied only to the candidate checkout. The ordinary production files on the helper's default branch are unchanged.

The baseline supplies the common six compiled roots and keep/layer configuration used by **both** arms. The candidate differs only in the reviewed `ArchitectureCommand` source plus its two tests. Both installations must have the same paths and bytes except `lib/cli-0.21.0.jar`. Baseline source must be clean; candidate staged changes and complete source inventories must match the exact three-file delta. The old command oracle, full index/CLI correctness tests and candidate install must pass before assessment.

Builds run sequentially with the existing dependency verification and compiler pins. In-process Kotlin compilation prevents a separate compiler daemon from remaining during the measurement. CLI processes use the ordinary CI JVM policy, with ambient Java/Kartograph option injection excluded. The setup-only small-fixture javac has an explicit 512 MiB heap and a 60-second bound; it is not a timed CLI row.

The one-class and 17-class fixtures are the same authored `small.Small00`…`Small16` Java definitions used in the earlier pilot. They are compiled once before measurement, copied into the two declared roots, and included in input membership/hash brackets.

## Fixed 172-CLI schedule

A always means baseline; B always means candidate. Both labels use the same baseline working directory, common input paths, environment and argument tails. Only the installation launcher path differs.

Each main suite uses graph, dead, private dead, cycles, rules, metrics in that order. Its primary total is the sum of the six command intervals; enclosing suite wall is also retained.

1. Two excluded complete-suite warmups: A, then B (12 CLI invocations).
2. Twelve measured whole-suite pairs: `AB, BA, BA, AB, AB, BA, BA, AB, AB, BA, BA, AB` (144 CLI invocations).
3. After all main suites, the one-class graph block and then the 17-class graph block. Each uses A/B warmups and measured `AB, BA, AB` pairs (16 CLI invocations).

Total: 172 CLI invocations. Four separate JVM metadata probes run outside the timed intervals. The setup javac is a separate, recorded process. No additional observations are added after seeing results.

## Descriptive summaries

For each main command and the suite total, retain all twelve raw paired ratios `B/A` and signed symmetric differences `2*(B-A)/(A+B)`. The reported paired geometric mean ratio is `exp(mean(log(B/A)))` for:

- all twelve pairs;
- early pairs 0–5 and late pairs 6–11;
- the six AB pairs and six BA pairs;
- early/late × AB/BA intersections, each containing three pairs.

Also retain raw ranges, arithmetic medians, CPU and direct-process peak RSS. These subgroups are sensitivity descriptions, not independent replicates. Geometric ratios are not median speedups or the production sum-of-command-medians statistic. No confidence interval, p-value, autocorrelation-based independence claim or effective sample-size adjustment confers acceptance.

## Frozen continuation screen

A completed valid capture warrants **continuing to ordinary exact-head validation** only if all these descriptive predicates hold:

- Whole-suite all-pair geometric ratio ≤0.90.
- Whole-suite early, late, AB and BA ratios each <1.00.
- Every command's all-pair ratio ≤1.05; each of its early/late/AB/BA ratios ≤1.10.
- Declared unchanged implementation controls are graph, dead and private dead. Each all-pair ratio must lie in [0.95, 1.05], and each of its four main subgroup ratios in [0.90, 1.10]. Their apparent gains are never subtracted from other measurements.
- Candidate median direct-process peak RSS for every command/case ≤1.25 × baseline median; no invocation, including warmups, exceeds 1.2 GiB.
- Each small-case candidate median wall ≤1.20 × baseline median +0.020 seconds.
- Exact output/error parity, original semantic contracts and all source/input/installation identities hold.

Every valid completed capture failing a continuation predicate is **inconclusive for release-headroom justification**. Adverse values are still reported plainly; there is no discretionary alternate status. Correctness, identity, output-cap, resource-policy or RSS-bound failures reject the capture. Timeout, cancellation or unreaped-child cleanup leaves the schedule incomplete; none is eligible for continuation or an automatic retry. The small-case wall predicate is descriptive, so its failure is inconclusive rather than a resource failure.

A favorable result is only a recommendation to continue normal validation. It cannot itself authorize a merge, publication or release. PR172's original timing failure remains separate until actual subsequent reviewed source passes its required gates.

## Bounds, evidence and failure handling

The capture has a 900-second TERM / 15-second forced-stop outer bound, 120 seconds per CLI, ten seconds per metadata probe, 64 MiB stdout and eight MiB stderr limits. Polling can overshoot a checked limit; over-cap bodies are not read or hashed. The workflow has a 45-minute ceiling and no release environment, secrets, tag operation or publishing permissions.

The new supervisor imports the unchanged, tested capture/resource primitives from `Scripts/verify-cli-repeatability.py`. It records owned-process exits, wall/CPU/RSS, complete raw streams and hashes, and terminal primary/secondary failures. Resource snapshots occur outside timers and cover observed leaf cgroup policies, affinity, counters and host context. They do not certify ancestor policies, exclusive hardware or internal settings of each timed JVM.

Before/after records include exact helper and product identities, full source/file membership, both installations, common corpus, fixtures and selected JDK artifacts. The same fixed settings probes run before and after. Setup logs, actual exits, candidate correctness XML and the complete capture are retained in `descriptive-architecture-<run_id>-<attempt>` even on failure. Artifact collection is confined to the declared evidence directory and excludes the temporary working directory.

Stop at the first validity failure; otherwise complete the frozen schedule and evaluate once. No outlier removal, optional sample extension, rerun-to-win, threshold retuning or reconstruction of a passing historical result is permitted. An incomplete attempt stays incomplete.

## Product gates and authorization

The existing production smoke limits remain exactly 20 seconds total and five seconds per command. Ordinary CI, broader release fixtures and complete release-readiness gates remain required. This diagnostic never moves the fixed 0.21.0 tag or fills an incomplete publication automatically.

The candidate is reviewed source preparation for this bounded experiment only. Publishing the helper/workflow requires independent review and exact-head CI. Dispatch requires a separate explicit approval for the exact helper commit and fixed product/patch identities, after checking prior dispatch history. A failed or inconclusive attempt creates no automatic permission to repeat it.
