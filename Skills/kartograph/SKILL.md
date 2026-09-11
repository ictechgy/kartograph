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

## After inspecting evidence

A public API is not a retention root: public visibility alone neither proves a caller nor rules out external callers. R8 removal is an optimizer result, not source-level unused-code or runtime-safety proof. Static facts do not create deletion authority.

For an analysis-only task, explain the finding, evidence, uncertainty and next check, then stop without edits. If the user requested a code change, make the smallest reviewable change supported by the evidence, run affected tests/build, and re-run kartograph against fresh outputs. If an `index-staleness` limitation persists, inspect the inputs; rebuilding does not guarantee that the marker disappears.

`--since` limits changed files and can miss an untouched symbol made unreachable by caller removal. A full-graph PR gate compares the base commit's baseline; never refresh that baseline just to make the gate pass.

## Bridge investigations

Only for Flutter MethodChannel or React Native NativeModule questions, use `kartograph bridges --project <root>` and, when available and in scope, join facts through isthmus. Dynamic or unattributed facts are limitations. Missing isthmus or other-platform evidence does not block a useful local explanation or prove that the other side is absent.

## Deliverable

Report the relevant symbol/variant, observed state and retention evidence, missing/truncated/stale inputs, and any authorized change plus its actual verification. Keep findings separate from recommendations; do not label a declaration safe to delete.
