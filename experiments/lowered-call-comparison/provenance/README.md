# Original run and publication correspondence

The original run completed all ten stages with exit 0. It is identified by:

- Fixture manifest: `ffef91dd0974160fa4c360e4453cd43965250b48dd48e8b99b9cfe7535c6dc1e`
- Executed implementation manifest: `0116440c3ca99b0301900928db55f83a8b1ed444de973b7c3b9d233070370e23`
- Executed `run.py`: `e72e218dda8542c0c9f6c60231d39655fb65ee917029cb1a78a74ab9013d9c2d`
- Executed `compare.py`: `b7d356cf35f88b7fb5f38b792ae1a4e7e9c267aeec43374b1cbdf631469cd90a`
- Executed ten-control file: `9da425e52f9e5071bbdb1f45d8f4c233e8eafde850a36c70b30f903fac06efbd`

The original manifests are unchanged [fixture](original-fixture-manifest.json), [execution](original-execution-manifest.json) and [result](original-result-manifest.json) records. They describe the original layout and bytes, not the relocated publication. The [copy ledger](publication-files.json) identifies every retained original artifact and any transformation.

## Exact and transformed bytes

The source fixtures, both authored oracles, runtime pins, source list, all three Python scripts, raw PSI, raw product graph, original comparison, stage exits, original/final selected-input and compiled-input inventories, and retained nonempty stage streams are byte-for-byte copies. The original result report is also retained in `../historical/`; their `run-01` refers to evidence now stored at `../results/2026-10-13/`.

Only two original text artifacts have path substitutions:

1. Historical `RUN-PLAN.md`: one local absolute classpath literal becomes `<prior-run>/semantic-classpath.txt`. Every other byte is retained. The original document hash remains in the original fixture manifest and ledger.
2. Original javap stream: the two leading `Classfile` headers now use `fixture-classes/lowered/LoweredJava.class` and `fixture-classes/lowered/LoweredKotlin.class`. All other bytes, including timestamps, bytecode, constants, flags, line tables, SMAP and bootstrap handles, are retained. The published file is explicitly named `javap.portable.txt`; its original-stream digest remains recorded.

The published root `fixture-manifest.json` moves the two proposal-document keys to `historical/` and updates only the sanitized RUN-PLAN hash, status and explanatory publication metadata. Root `execution-manifest.json` points to that derived fixture manifest and identifies its publication-only status/correspondence. Its three implementation hashes are unchanged. The original expectation contents and their pre-execution status strings are deliberately untouched.

The immutable original `comparison.json` therefore still records the original fixture and javap hashes. A replay using the published manifest and portable disassembly is expected to differ only in those two top-level digests. Do not replace the original result with the replay or present a replay as a fresh compiler/product observation.

## Scope of preservation

Runtime JARs, compiled classes, the absolute runtime classpath, local outer-wrapper output, bytecode caches and an earlier unexecuted implementation are omitted. Reproduction uses the public sibling adapter/dependency project instead. Original/final class hash inventories preserve compiled-input identity without distributing binaries. The twelve zero-byte successful stage streams are intentionally omitted from the public copy. Their original names, zero byte counts and SHA-256 digests remain in `omittedOriginalArtifacts` in the correspondence ledger; all ten original stage exits remain in `stages.json`. The five nonempty version/compiler/grader streams are retained. The publication-preparation note is also omitted because it is not a frozen input or observed result. No original local evidence was changed.

The recorded source revision/status and selected distribution hashes identify the original inputs. The installed distribution's independent build receipt is not included here; reproduction must retain its own successful `:cli:installDist` log. The recorded run date is retained from the original report; filesystem dates in javap remain unmodified observations.

The publication introduced no JVM/compiler/Gradle execution and makes no new performance or product-correctness claim.

## Membership-only compaction

After the pure controls and retained-evidence replay passed, publication membership was reduced by the twelve empty streams and one preparation note. No runner, grader, fixture, oracle, manifest used by the runner, or retained result bytes changed; those controls were not rerun. The validation record describes the original 43 copied artifacts checked at that time. The final ledger contains 30 retained original artifacts, 13 explicitly omitted artifacts, and hashes for every publication-only file other than the ledger itself.
