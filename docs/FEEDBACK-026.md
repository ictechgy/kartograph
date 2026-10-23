# 0.26.0 input and reporting migration

This release candidate addresses generalized fixtures from 0.25.0 feedback. It has
not been revalidated against the private Android application described in that feedback.
The existing streaming graph export, deduplication, dispatch candidate cap, and
external stub options remain in place.

## Keep inputs

`dead`, `why`, and live `query`/`snapshot` accept `--keep-rule-mode strict|lenient`.
Strict is the default and reports independent parse failures together. Missing
dependency hierarchy failures are also collected across applicable parsed rules;
parser failures and missing dependency headers remain separate failure stages.
Concrete static/final members and the `***` field type are supported. `-dontshrink` disables
shrinking for the whole graph: retained nodes carry `SHRINKING_DISABLED`, which is
not a runtime entry-point assertion. This follows the [ProGuard definition](https://www.guardsquare.com/manual/configuration/usage#dontshrink).

Lenient mode preserves unsupported rules or missing hierarchy as `UNKNOWN_KEEP_RULE`
and withholds dead candidates conservatively. It does not prove reachability.
Empty results retain `unknown-keep-rules`; `dead --strict` returns 2 and refuses to
write a baseline from unknown keep inputs. Query reports `unknown` when that is the
only retention evidence, and SARIF marks that analysis unsuccessful. Parser leniency
does not bypass unreadable input files. Raw unmatched inheritance specifications
remain visible. Explicitly excluding rules reduces the supplied analysis scope.

The Gradle extension has `keepRuleMode` (default `strict`), `useVariantKeepRules`
(default `true`), and `excludedKeepRules`. Explicit `keepRules` can replace variant
rules by setting `useVariantKeepRules=false`; excluded files and opt-in policy must
be reviewed with the remaining inputs. See [Android CLI and plugin inputs](ANDROID-CLI-INPUTS.md)
for local plugin JARs, existing repository coordinates, classpath capture, graph
export options and source paths.

## Android and source evidence

Repeated `--resources` roots accumulate deterministically. Duplicates do not add
evidence. Compiled `.flat` inputs and unreadable resource input fail with an actionable
diagnostic; pass merged source XML. [Callback evidence](ANDROID-CALLBACK-INPUTS.md)
documents owner-conditioned framework callbacks and the bounded mapper chain.
XML `requireAll`, runtime overload/variant selection and unused attributes are not
claimed as resolved. Name suffixes alone do not establish generated provenance.

An unreadable source directory no longer discards readable sibling mappings.
`unreadable-source-directories` remains in the result; a unique observed file may
have an unseen namesake in the omitted directory. Excluded build directories are
pruned before opening. Known ambiguity still omits a mapping. Source paths and
declaration coordinates are reported when recoverable; absent JSON coordinates are
`null`, and SARIF omits the region. Bytecode call-site positions are distinct from
declaration coordinates. Baseline filtering retains the pre-existing fingerprint
identity before report-only source enrichment.
This removes the old fabricated 1:1 fallback: JSON consumers must accept nullable
`location.line`/`location.column`, and SARIF consumers must allow a missing region.

`qualifiedName` now contains the language package/owner name. The separate `module`
field and USR are unchanged. Old module-prefixed query selectors remain accepted as
input aliases, including ambiguity checks. Consumers should join by USR and use
`module` as metadata. Recompile library consumers against 0.26.0 when using the
extended keep-rule model; existing retention function JVM overloads are preserved.

## Larger snapshots and output errors

The default snapshot byte limit is still 64 MiB. CLI snapshot readers/writers, MCP
startup, and the Gradle `snapshotMaxMiB` property accept an explicit 1–1024 MiB bound.
Use the same setting to capture and read, for example `--snapshot-max-mib 256`.
Compact output still enforces both its byte limit and its existing independent
expansion budgets (2^31 characters and 2^25 references), now during writing too.
This prevents a writer from producing an expansion the reader would reject.
Version 1 and compact version 2 input remain supported; malformed or truncated
input remains an error.

Snapshots still materialize the graph and JSON in memory. The byte limit is not a
heap or RSS budget; larger snapshots require an appropriate JVM heap, for example
`JAVA_OPTS=-Xmx4g`, and sufficient host memory. The large regression uses a synthetic
6200-node chain with long distinct labels and checks actual files above 128 MiB.
Its measured heap values are observations, not a memory guarantee for a large app.

A closed output consumer and an out-of-space destination now produce different
diagnostics when the operating system exposes those causes. Both return failure
because output was incomplete. Other I/O failures retain a generic destination
diagnostic; no localized operating-system error text is interpreted as success.

The candidate archive contains the current changelog including the published
0.25.0 entry. Previously published archives and tags are not modified.
