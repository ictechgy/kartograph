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

## Read the actual document

- Check top-level `status` and `limitations` first. For `ambiguous`, use an explicit candidate `usr`; never choose by display name. `notFound` is missing evidence, not an unused result; retain its limitations.
- For a found symbol, read `result.usedBy`, `dependsOn`, `members`, `reachability`, and `truncated` together.
- `result.truncated` is an object with `usedBy`, `dependsOn`, and `members` booleans, not a single boolean. When a relevant flag is true, increase the supported limit/depth or narrow the query; do not treat omitted neighbors as absent.
- `result.reachability.suppressedByBaseline` means the fingerprint is suppressed. It does not establish who reviewed it or grant permission to change/delete the symbol.
- `reachability.state = unreachable` means no configured root reaches the declaration in this compiled graph: evidence for review and never deletion approval. Check reflection, JNI, resources, runtime registration, variants, generated code and cross-language callers as relevant. Empty query limitations are not a completeness guarantee.

## After the rules pass

A public API is not a retention root: public visibility alone neither proves a caller nor rules out external callers. R8 removal is an optimizer result, not source-level unused-code or runtime-safety proof. Static facts do not create deletion authority.

For an analysis-only task, explain the finding, evidence, uncertainty and next check, then stop without edits. If the user requested a code change, make the smallest reviewable change supported by the evidence, run affected tests/build, and re-run kartograph against fresh outputs. If an `index-staleness` limitation persists, inspect the inputs; rebuilding does not guarantee that the marker disappears.

`--since` limits changed files and can miss an untouched symbol made unreachable by caller removal. A full-graph PR gate compares the base commit's baseline; never refresh that baseline just to make the gate pass.

## Bridge investigations

Only for Flutter MethodChannel or React Native NativeModule questions, use `kartograph bridges --project <root>` and, when available and in scope, join facts through isthmus. Dynamic or unattributed facts are limitations. Missing isthmus or other-platform evidence does not block a useful local explanation or prove that the other side is absent.

## Deliverable

Report the relevant symbol/variant, observed state and retention evidence, missing/truncated/stale inputs, and any authorized change plus its actual verification. Keep findings separate from recommendations; do not label a declaration safe to delete.
