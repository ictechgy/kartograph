# Bounded Android resource-impact experiment

This experiment exercises the product's bounded layout-file and literal-include mapping,
with separate producer, exact R-field and bytecode consumer witnesses.

## Scope

The existing `agp-minimum` hosted job supplies JDK 17, Gradle 8.10.2 and Android platform 35.
`Scripts/verify-agp-8-resource-impact.py` copies the tracked AGP 8.7.3/KGP 2.0.21 application
fixture into an owned temporary sibling directory. It excludes build/cache/local-properties
outputs and leaves the original compatibility/dead-code fixture unchanged. The copied build
uses the existing Google/Maven Central repositories and adds pinned JUnit 4.13.2 from Maven Central.
Non-final and non-transitive R are explicit experiment inputs.

The fixture contains framework-only screen/child/unrelated layouts and a parent layout with a
literal include of child. Java accessors read the exact generated R.layout fields. Four JUnit
methods call those accessors. The tests execute ordinary JVM methods, not layout inflation.

## Required evidence

Both before and after an XML-only child text change must have:
- A successful actual AGP build, successful unit-test execution with all four expected methods,
  and a snapshot whose captured inputs verify as matched.
- The exact generated R field selected by impact, with bytecode FIELD_ACCESS from the accessor
  and CALL then FIELD_ACCESS from the compiled test.
- Exact XML file→resource key→non-final R field receipts, with revision-local selected fields.
  Child XML must select child and its recorded parent include; other layouts must select only their own field.
  Exact accessor/test witnesses and unrelated-layout exclusions are required.
- A declared absent main root in facts, bindings and producer inventories while the directory remains absent.
- A channel-stripped snapshot must return resourceEvidenceUnavailable/exit64 instead of guessing from R fields.
- Identical code/test source hashes and changed child XML bytes, with other XML bytes unchanged.

The old snapshot must become stale after the XML edit and before rebuilding. The experiment
does not compare a stale snapshot as if current. Only admitted saved resource facts and exact field ownership supply seeds.
The recorded parent include supplies a bounded reverse-include candidate; bytecode paths remain separate evidence.

Only the bounded result.json is uploaded. Raw logs and checkout-specific input bindings are
not public artifacts. The result records the source revision, CLI library hashes, snapshot hashes,
source/XML hashes, exact graph witnesses, executed test inventory and explicit limitations.

## Interpretation

`capturedLayoutImpactEstablished` means these fixture-specific XML selection, literal include,
producer and code/test oracles passed. The earlier unmapped baseline is preserved in historical runs.
[PR188 CI](https://github.com/ictechgy/kartograph/actions/runs/39159309408) passed the new positive mapping
and negative controls. Coverage remains partial; this result does not establish a 0.25.0 installed package
or Android layout inflation. Release readiness must separately use retained packaged artifacts.

Final/inlined resource IDs, other AGP versions, overlays, navigation, data/view binding and
Android UI behavior are outside this experiment. Missing support remains unknown rather than
blocking this one-toolchain measurement or claiming universal coverage.

The subprocess budget is ten minutes per command; the hosted experiment step has a 25-minute
limit within the existing AGP job's explicit 45-minute budget. Timeouts fail; there is no skip path.
