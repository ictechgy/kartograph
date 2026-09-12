---
name: kartograph
description: Investigate kartograph Kotlin/Android dependency findings, symbol reachability, and Flutter/React Native bridge evidence. Use for interpreting analysis reports or planning a requested edit, not for unrelated Kotlin development.
---

# kartograph

## Scope and inputs

Follow the user's requested outcome. Explanation/review requests are read-only; this skill does not authorize edits, builds, downloads, baseline updates, commits, or publishing by itself. Make an authorized change only after examining its evidence.

Use an available trusted CLI and the matching compiled variant. Do not guess class roots, install a tool, or run the application's build merely to answer a report question when supplied evidence suffices. If evidence is missing or stale, identify the missing input; rebuild when that is within the authorized task.

```bash
kartograph query '<symbol-or-usr>' --classes <compiled-root> --project <root>
```

Repeat `--classes` for relevant main Kotlin/Java/generated outputs. Preserve the report's manifest, resources, namespace, keep/consumer rules, dependency `--classpath`, baseline and `--include-private-members` mode. Missing inputs are not proof that a declaration is unused.

Preserve any `--generated-classes` markers: they identify supplied roots containing only generated declarations. The graph still contains those nodes; the marker does not make a declaration a retention root. Do not infer generated input from class names.

For repeated investigation, `kartograph snapshot <the same live-input options>` writes a versioned graph with retention evidence, baseline state and measured limitations. Query it with `kartograph query '<symbol-or-usr>' --graph-file <snapshot.json>`. Ordinary `graph --format json` output lacks this query context. Saved queries use the captured state, add a `saved-graph` limitation, and do not recheck source freshness. Recapture after authorized changes before drawing conclusions about the current build. Live-input options cannot be mixed with `--graph-file`.

For a current-build check, attach the successful registered compiler task's file with `snapshot --build-witness <file>`
and include the matching `--source-root` / `--build-input` inputs. Run
`kartograph verify-snapshot --graph-file snapshot.json --project <root>` with the required `--input external/slot=<path>`
bindings. `matched` confirms recorded file contents and compiler lifecycle evidence; `stale` or `unverified` cannot support
a current-build claim. Preserve the reason list, and rebuild/recapture only within the authorized task. A commit label alone
is not compiler evidence, and matching evidence is not runtime completeness or authentication of a malicious producer.

For inlined constant uses or selected Dagger relations, use a snapshot captured
with explicit `--compiler-evidence` inputs and completed version 2 build receipts.
Raw TSV resources are not discovered or trusted automatically. The producer and
all external inputs must match. Keep `compiler-evidence-unmapped-references`,
`compiler-evidence-outside-graph`, and `compiler-evidence-shadowed-references`
counts in the assessment; default retention still applies. See
`docs/COMPILER-EVIDENCE.md` for the supported collector/build workflow.

## Read the actual document

- Check top-level `status` and `limitations` first. For `ambiguous`, use an explicit candidate `usr`; never choose by display name. `notFound` is missing evidence, not an unused result; retain its limitations.
- For a found symbol, read `result.usedBy`, `dependsOn`, `members`, `reachability`, and `truncated` together.
- `result.truncated` is an object with `usedBy`, `dependsOn`, and `members` booleans, not a single boolean. When a relevant flag is true, increase the supported limit/depth or narrow the query; do not treat omitted neighbors as absent.
- `result.reachability.suppressedByBaseline` means the fingerprint is suppressed. It does not establish who reviewed it or grant permission to change/delete the symbol.
- `reachability.state = unreachable` means no configured root reaches the declaration in this compiled graph: evidence for review and never deletion approval. Check reflection, JNI, resources, runtime registration, variants, generated code and cross-language callers as relevant. Empty query limitations are not a completeness guarantee.

## Change impact preflight

Before an authorized edit, capture the matching compiled inputs with `kartograph snapshot ... --include-paths`
and run `kartograph impact '<symbol-or-usr>' --graph-file snapshot.json`. Repeat `--symbol` for multiple declarations.
For large inputs, add `snapshot --compact` to store the same facts with a v2 string table and indexed rows; query/impact read both versions.
For a committed change, provide `--base-graph base.json` and repeat `--file <project-relative-path>` for the changed paths.
The CI helper `Scripts/check-impact.py` obtains both old and new paths for renames/deletions from Git and requires
snapshot `--revision <full-commit-hash>` labels to match the commits. Use the same `--scope <project:variant>` when capturing.
Labels identify the intended inputs; freshness limitations still need review.
The helper compares current input contents and can verify the base with `--base-project`; without that checkout the base is
unverified. Read `freshness` with the impact report. Report-only mode retains these diagnostics, while `--strict` fails on
stale/unverified evidence. Pass external bindings separately for base and current inputs.

Read `changed`, `affected`, `unresolved`, `truncated` and `limitations` together. Each affected declaration has `paths.nodes`
ordered from the dependent toward the changed declaration. `edges` preserve original graph endpoints; `traversal` marks
`overrideContract` when a changed method contract affects an implementation in the opposite graph direction.
`revision` selects the base or current graph for that path;
never combine edges from different revisions. `origin` separates bytecode, metadata and runtime/dispatch models.
Retention entries have their own revision and explain exposure roots; retention alone is not a caller edge.

`observedAffected` counts discovered candidates within traversal bounds. Missing symbols/files and truncated searches are
incomplete checks. Empty affected lists do not certify no behavioral impact. Generated/private declarations stay in impact
results, and baseline suppression never hides a candidate. Review known runtime gaps, inlined constants, unbuilt variants and
external consumers before acting. The report does not approve deleting code or skipping tests.

For a large impact report, treat `affected` as a page, not the complete candidate set. `summary.observed` is calculated
before filters and page limits; `summary.filtered` is calculated after filters but before the page. Use
`navigation.hasNext`, `navigation.offset`, and `navigation.limit` for deterministic pagination, or `--all` for an export of
all observed matching candidates. `--module`, `--affected-file`, `--kind`, `--test-status`, `--relation`, and
`--path-status` are focused filters. `--file` remains the changed-declaration selector and must not be confused with
`--affected-file`, which filters candidate source locations. When base/current facts differ, a candidate can appear in
more than one module, file, or test-status summary bucket; bucket totals are not a partition of the observed candidate count.
Each metadata filter axis can match either revision independently; inspect `facts` to identify the matching revision.
Test status describes a recognized source-root convention, not proof that a declaration is a runnable test.
Keep inputs, filters, sort, and budgets fixed between pages. Set `--path-limit` explicitly when changing page limits,
because the default path budget depends on the result limit.
`--kind` selects the representative declaration's kind (current if present). Sorting by test status uses the common
revision status, or `unknown` when statuses differ; test filters and summary buckets use individual revision facts.
An offset page remains `partial` relative to the full candidate set even when `hasNext` is false.

Read each candidate's `facts`, `observedIn`, `relation`, `pathStatus`, and `pathOmissions` with its existing `paths`.
Facts are revision-specific; null module/location and `unknown` test status are missing evidence, not inferred values.
`direct`, `structural`, and `transitive` are explainable path categories, not risk scores or test-selection advice.
When `pathStatus` is partial or unavailable, inspect `budgets` and the omission's `requiredEdges`; a path budget omission
does not remove the candidate or establish that no path exists. Filters and pages never erase unresolved selectors,
base/current path separation, traversal truncation, freshness limitations, or runtime uncertainty.
An omission's `edgeKinds` contains distinct kinds, not the full sequence of omitted edges.

## After inspecting evidence

A public API is not a retention root: public visibility alone neither proves a caller nor rules out external callers. R8 removal is an optimizer result, not source-level unused-code or runtime-safety proof. Static facts do not create deletion authority.

For an analysis-only task, explain the finding, evidence, uncertainty and next check, then stop without edits. If the user requested a code change, make the smallest reviewable change supported by the evidence, run affected tests/build, and re-run kartograph against fresh outputs. If an `index-staleness` limitation persists, inspect the inputs; rebuilding does not guarantee that the marker disappears.

`--since` limits changed files and can miss an untouched symbol made unreachable by caller removal. A full-graph PR gate compares the base commit's baseline; never refresh that baseline just to make the gate pass.

## Bridge investigations

Only for Flutter MethodChannel or React Native NativeModule questions, use `kartograph bridges --project <root>` and, when available and in scope, join facts through isthmus. Dynamic or unattributed facts are limitations. Missing isthmus or other-platform evidence does not block a useful local explanation or prove that the other side is absent.

## Deliverable

Report the relevant symbol/variant, observed state and retention evidence, missing/truncated/stale inputs, and any authorized change plus its actual verification. Keep findings separate from recommendations; do not label a declaration safe to delete.
