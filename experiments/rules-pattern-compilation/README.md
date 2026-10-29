# Per-operation rule pattern compilation

## Change

`LayerRuleEvaluator` previously rebuilt and compiled the same glob-derived regular expression for every candidate of every node. It now keeps a lazy pattern-to-regex map within each `evaluate`, `unassignedNodes`, or standalone `assignment` call. The map does not outlive the operation. Glob translation, first layer/pattern/candidate precedence, evidence, and rule ordering are unchanged. Caller-supplied mutable configuration is read again on the next call; concurrent operations have independent maps.

The source is deliberately unchanged for glob syntax: `*` becomes `.*`, `?` becomes `.`, and every other UTF-16 character is quoted with `Regex.escape`. Regex metacharacters such as `[` and `\\E` remain literals. No global cache or pattern-language extension is added.

## Validation and measured pilot

The candidate based on `2cf3cb6058111906071084f403d73f3574db3c96` passed `:analysis:test :cli:installDist`: all 159 analysis tests, with zero failures, errors, or skips. Eight new tests compare a mechanical copy of the old evaluator and authored results, including wildcard/literal metacharacters, empty/newline/Unicode/surrogate strings, first-match evidence, exact violations, mutable configuration, lazy unused patterns, and concurrent calls. Only the analysis JAR differed among the 47 installed files.

The same production/test bytes were then carried onto main `254c8ad40e08de6fa899a9ffd8bd3c606b8aad1b`. On that final publication base, `:analysis:test :cli:installDist` again passed all 159 tests with zero failures, errors, or skips. No timing samples were repeated after this rebase.

A JFR diagnosis of the unchanged baseline motivated this change: rules glob matching appeared in 29 of 144 Java execution samples and 847,254,976 of 1,465,136,608 allocation-weight bytes. These are sampled events and weights, not exact time or allocated bytes.

The separate uninstrumented pilot in `pilot.json` ran the actual installed CLI against the same baseline's six production class roots and `.kartograph.yml`. Compilation/setup was outside the timer. Both arms used Temurin 17.0.20.1+1, a 1 GiB heap, the same working directory/options, and the same bytes. Ambient JVM and Kartograph options were removed. The fixed schedule was baseline/candidate excluded warmups, then baseline/candidate, candidate/baseline, baseline/candidate timed pairs. Each direct process was reaped with `wait4`; elapsed detection used 1 ms polling.

- Median startup-inclusive rules latency: 3.233070109 s baseline, 2.903830369 s candidate (10.18% lower).
- Median direct-JVM peak RSS: 619,560 KiB baseline, 533,880 KiB candidate (13.83% lower).
- All eight invocations exited 0 and produced identical complete stdout and stderr: zero findings and zero unassigned nodes.
- Sources, configuration, compiled corpus, and both installations were unchanged across measurement.

This is a three-pair descriptive cloud pilot. It does not prove statistical superiority, cold-cache performance, or that the full CI smoke suite will meet its 20-second budget. Peak RSS is not exact allocation volume. The measurement is tied to the stated baseline, even if this patch is subsequently rebased.

## Reproduce

Build the baseline and candidate independently with the repository's checked-in Gradle/Kotlin pins and the same JDK:

```sh
./gradlew --no-daemon :analysis:test :cli:installDist :gradle-plugin:classes
```

Keep one fixed, complete six-root corpus from the baseline. For each arm, run its installed `bin/kartograph` with the same arguments and working directory:

```sh
kartograph rules \
  --classes "$BASE/core/build/classes/kotlin/main" \
  --classes "$BASE/index/build/classes/kotlin/main" \
  --classes "$BASE/analysis/build/classes/kotlin/main" \
  --classes "$BASE/export/build/classes/kotlin/main" \
  --classes "$BASE/cli/build/classes/kotlin/main" \
  --classes "$BASE/gradle-plugin/build/classes/kotlin/main" \
  --config "$BASE/.kartograph.yml" --strict
```

Use the fixed schedule above, preserve every exit and output, compare complete stdout/stderr bytes, and record wall time and direct-process peak RSS. Retain failures rather than rerunning until a favorable result appears. The repository's full CI/coverage, compatibility, CLI, Android, plugin, and smoke gates remain required for publication/merge.
