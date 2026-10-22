# Inline, default-argument and SAM-call comparison

A preserved, small actual comparison of the existing Kotlin Analysis API / Java PSI adapter and Kartograph's compiled graph. **The original run qualified; this publication is packaging, not a new benchmark execution.**

## Actual result

The 2026-10-13 run used clean product revision `254c8ad40e08de6fa899a9ffd8bd3c606b8aad1b`, the unchanged [published adapter](../embedded-psi-comparison/semantic/PsiResolvedCalls.kt), JDK 17.0.20.1+1 and the pinned Kotlin 2.4.10 runtime. All ten stages exited 0.

- Exactly 12 PSI rows matched their authored site ranges, enclosing callers, targets and multiplicity: ten internal calls and two resolved, target-null external SAM boundaries. The two external rows receive no exact-target credit.
- All 14 explicit methods were present. Five direct source pairs were preserved. The other five sites were classified with disassembly and graph evidence: inline-target erasure, default-bridge redirection, default-expression owner relocation, and two lambda-body owner relocations.
- The existing `kotlinDefaultBridge` association selected the correct source method. The unused method had no incoming raw CALL.
- Neither lambda implementation-handle edge had `callSiteLines`. The actual generated-body calls retained lines 10 and 12. Kotlin's generated body lacked `ACC_SYNTHETIC`; Java's had it. Body identity came from actual bootstrap linkage plus instructions, not spelling or flags alone.

This fixture found no resolution defect. It does not establish callback execution, general source-coordinate fidelity, whole-project completeness, deletion safety, precision/recall or performance. Expected lowering is not an accuracy failure. In particular, inline debug-line/SMAP interpretation needs separate field-contract analysis.

Evidence: [original comparison](results/2026-10-13/comparison.json), [unchanged PSI JSONL](results/2026-10-13/psi-calls.jsonl), [unchanged graph](results/2026-10-13/kartograph-graph.json), [portable disassembly](results/2026-10-13/javap.portable.txt), [stage exits](results/2026-10-13/stages.json), [selected input hashes](results/2026-10-13/inputs.json), [final input hashes](results/2026-10-13/final-inputs.json), [compiled hashes](results/2026-10-13/compiled-inputs.json), [final compiled hashes](results/2026-10-13/final-compiled-inputs.json).

## Reproduce using a separate measured product checkout

Prerequisites: Python 3, POSIX process groups, JDK 17, and access to the repository's pinned Gradle distribution and official Maven dependencies. Use a clean detached checkout of the measured product revision, separate from the checkout containing this experiment. No dependency binaries, private classpath, proxy configuration or credentials are included.

The following is a reproduction recipe, **not a claim that these setup commands ran during publication**. Run it serially from a checkout containing this directory; choose a new sibling worktree path if the suggested one already exists. Preserve the setup logs. A source checkout plus installed JAR hashes alone is not a build attestation.

```sh
set -eu
REPO=$(git rev-parse --show-toplevel)
EXP="$REPO/experiments/lowered-call-comparison"
MEASURED="$(dirname "$REPO")/kartograph-lowered-product-254c8ad"
export JAVA_HOME=/path/to/jdk17
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS KARTOGRAPH_OPTS
export JAVA_OPTS='-Xmx512m -Dfile.encoding=UTF-8'

test ! -e "$MEASURED"
git -C "$REPO" worktree add --detach "$MEASURED" \
  254c8ad40e08de6fa899a9ffd8bd3c606b8aad1b
mkdir -p "$EXP/runs/setup"
"$MEASURED/gradlew" -p "$MEASURED" \
  --no-daemon --console=plain --no-scan --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process :cli:installDist \
  > "$EXP/runs/setup/installDist.log" 2>&1

SEMANTIC="$MEASURED/experiments/embedded-psi-comparison/semantic"
"$MEASURED/gradlew" -p "$SEMANTIC" \
  --no-daemon --console=plain --no-scan --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8' \
  writeSemanticClasspath > "$EXP/runs/setup/dependencies.log" 2>&1

export KARTOGRAPH_BIN="$MEASURED/cli/build/install/kartograph/bin/kartograph"
python3 "$EXP/run.py" \
  --output "$EXP/runs/reproduction-01" \
  --runtime-classpath "$SEMANTIC/build/semantic-classpath.txt" \
  --adapter-source "$SEMANTIC/PsiResolvedCalls.kt" \
  --product-source "$MEASURED"
```

The output directory must be new. The runner rejects a dirty or different product checkout, a CLI outside that checkout's installed distribution, changed adapter/source/oracle bytes, or a different ordered runtime inventory. It rechecks selected source, runtime, product and compiled inputs at the end. Runtime resolution reuses the existing dependency project; [runtime-jars.json](runtime-jars.json) pins all 18 ordered JAR names, sizes and digests. Do not change those pins to hide a mismatch.

The exact executed runner and grader are retained. Compiler, adapter, javac and javap heaps are explicitly limited to 512 MiB; the CLI gets bounded `JAVA_OPTS`, with ambient JVM option overrides removed. Each stage has a 90-second limit within a 600-second stage budget. Process-group timeout/interruption cleanup sends TERM, waits up to five seconds, then KILLs and waits. The first failed stage stops the run and leaves logs/statuses; there is no automatic retry or engine substitution. Gradle setup above is separate from this stage budget. Fixture entry points are not executed.

## Pure controls and retained-evidence replay

The publication copy passed all ten pure controls and a retained-evidence replay; the PSI/product objects match the original exactly ([validation](provenance/publication-validation.json), [test log](provenance/pure-controls.log)). These commands only use Python and saved text. The ten original controls are synthetic gate/parser tests; they are not additional semantic runs.

```sh
EXP="$(git rev-parse --show-toplevel)/experiments/lowered-call-comparison"
python3 -B -m unittest discover -s "$EXP" -p 'test_compare.py' -v
mkdir -p "$EXP/runs"
python3 -B "$EXP/compare.py" \
  --psi "$EXP/results/2026-10-13/psi-calls.jsonl" \
  --graph "$EXP/results/2026-10-13/kartograph-graph.json" \
  --javap "$EXP/results/2026-10-13/javap.portable.txt" \
  --output "$EXP/runs/retained-evidence-regrade.json"
```

Use a new regrade output filename on each attempt. The qualified PSI/product objects should match the original comparison exactly. Only the top-level fixture-manifest and javap hashes differ, because publication relocates historical documents and replaces two disassembly path headers. See [provenance](provenance/README.md) before comparing hashes.

## Frozen scope and provenance

The [two sources](fixture/lowered/), [source-call oracle](source-call-expectations.json) and [lowering hypotheses](lowering-expectations.json) were authored before compilation. The original source-only documents remain [historical inputs](historical/README.md), not current result claims. Their anticipated use of "synthetic" is not evidence of `ACC_SYNTHETIC`; the actual Kotlin/Java flag distinction above is authoritative for this run.

The [publication correspondence ledger](provenance/publication-files.json) gives original and published hashes, with original manifests retained alongside it. Twelve zero-byte stage streams are intentionally omitted; their names, byte counts and original hashes remain in the ledger, and all ten stage exits and five nonempty streams are retained. A packaging-preparation note is also omitted. This membership-only compaction did not change retained evidence or require another test/semantic run. Root manifests are a runnable publication-only rebase; no source, expectation, runtime pin, adapter identity or executed Python implementation was changed. The source and runtime license provenance remains in [the reused adapter project's NOTICE](../embedded-psi-comparison/NOTICE.txt) and [Apache-2.0 license](../embedded-psi-comparison/semantic/LICENSE-APACHE-2.0). No duplicate adapter/dependency project or new runner framework is introduced.
